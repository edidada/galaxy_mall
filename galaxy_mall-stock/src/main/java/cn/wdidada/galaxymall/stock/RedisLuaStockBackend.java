package cn.wdidada.galaxymall.stock;

import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.domain.StockReservation;
import cn.wdidada.galaxymall.db.LocalBinlogRepository;
import cn.wdidada.galaxymall.db.StockLedgerRepository;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisLuaStockBackend implements StockBackend {

    private final StockLedgerRepository stockLedgerRepository;
    private final MetricsCollector metricsCollector;
    private final LocalBinlogRepository compensationLog;
    private final LocalRateGuard localRateGuard;
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> reserveScript;
    private final DefaultRedisScript<List> confirmScript;
    private final DefaultRedisScript<List> releaseScript;
    private final Map<String, ReservationSnapshot> confirmedReservations = new ConcurrentHashMap<>();

    public RedisLuaStockBackend(
            StockLedgerRepository stockLedgerRepository,
            MetricsCollector metricsCollector,
            LocalBinlogRepository compensationLog,
            int maxPerSecond,
            StringRedisTemplate redisTemplate
    ) {
        this.stockLedgerRepository = stockLedgerRepository;
        this.metricsCollector = metricsCollector;
        this.compensationLog = compensationLog;
        this.localRateGuard = new LocalRateGuard(maxPerSecond);
        this.redisTemplate = redisTemplate;
        this.reserveScript = script("lua/reserve_stock.lua");
        this.confirmScript = script("lua/confirm_stock.lua");
        this.releaseScript = script("lua/release_stock.lua");
    }

    @Override
    public void preload(String productId) {
        redisTemplate.opsForValue().setIfAbsent(stockKey(productId), String.valueOf(stockLedgerRepository.get(productId)));
    }

    @Override
    public OperationResult<StockReservation> reserve(String reservationId, String productId, int quantity) {
        if (!localRateGuard.tryAcquire()) {
            metricsCollector.increment("stock.local.limit.reject");
            return OperationResult.failure("local-rate-limit-rejected");
        }
        List result = redisTemplate.execute(
                reserveScript,
                List.of(stockKey(productId), reservationKey(reservationId)),
                String.valueOf(quantity),
                productId
        );
        long code = longAt(result, 0);
        int remaining = (int) longAt(result, 1);
        if (code == 1L) {
            return OperationResult.failure("stock-not-enough");
        }
        if (code == 2L) {
            return OperationResult.failure("stock-key-missing");
        }
        if (code == 3L) {
            return OperationResult.failure("reservation-already-released");
        }
        metricsCollector.increment("stock.redis.reserve.success");
        return OperationResult.success(
                "stock-reserved",
                new StockReservation(reservationId, productId, quantity, remaining, false)
        );
    }

    @Override
    public OperationResult<StockReservation> confirm(String reservationId) {
        ReservationSnapshot alreadyConfirmed = confirmedReservations.get(reservationId);
        if (alreadyConfirmed != null) {
            return confirmedResult("db-confirmed-idempotent", reservationId, alreadyConfirmed);
        }
        ReservationSnapshot snapshot = readReservation(reservationId);
        if (snapshot == null) {
            return OperationResult.failure("reservation-not-found");
        }
        boolean success = stockLedgerRepository.decreaseWithLock(snapshot.productId(), snapshot.quantity());
        if (!success) {
            OperationResult<StockReservation> rollback = releaseReservation(reservationId, "db-confirm-failed");
            if (!rollback.success()) {
                recordCompensation("stock-confirm-rollback-required", reservationId, snapshot.productId(), rollback.message());
                metricsCollector.increment("stock.confirm.rollback.required");
                return OperationResult.failure("db-confirm-failed-compensation-required");
            }
            metricsCollector.increment("stock.db.confirm.fail");
            return OperationResult.failure("db-confirm-failed");
        }
        confirmedReservations.put(reservationId, snapshot);
        try {
            List result = redisTemplate.execute(confirmScript, List.of(reservationKey(reservationId)), snapshot.productId());
            long code = longAt(result, 0);
            if (code == 1L || code == 2L) {
                recordCompensation("stock-confirm-redis-reconcile", reservationId, snapshot.productId(), "confirm-script-code-" + code);
                metricsCollector.increment("stock.confirm.redis.reconcile.required");
            }
        } catch (RuntimeException ex) {
            recordCompensation("stock-confirm-redis-reconcile", reservationId, snapshot.productId(), ex.getMessage());
            metricsCollector.increment("stock.confirm.redis.reconcile.required");
        }
        metricsCollector.increment("stock.db.confirm.success");
        return confirmedResult("db-confirmed", reservationId, snapshot);
    }

    @Override
    public OperationResult<StockReservation> release(String reservationId, String reason) {
        ReservationSnapshot alreadyConfirmed = confirmedReservations.get(reservationId);
        if (alreadyConfirmed != null) {
            metricsCollector.increment("stock.release.skipped.confirmed");
            return OperationResult.success(
                    "reservation-release-skipped:" + reason,
                    new StockReservation(
                            reservationId,
                            alreadyConfirmed.productId(),
                            alreadyConfirmed.quantity(),
                            redisStock(alreadyConfirmed.productId()),
                            true
                    )
            );
        }
        return releaseReservation(reservationId, reason);
    }

    private OperationResult<StockReservation> releaseReservation(String reservationId, String reason) {
        ReservationSnapshot snapshot = readReservation(reservationId);
        if (snapshot == null) {
            return OperationResult.failure("reservation-not-found");
        }
        List result = redisTemplate.execute(
                releaseScript,
                List.of(stockKey(snapshot.productId()), reservationKey(reservationId))
        );
        metricsCollector.increment("stock.release");
        return OperationResult.success(
                "reservation-released:" + reason,
                new StockReservation(reservationId, snapshot.productId(), snapshot.quantity(), (int) longAt(result, 1), false)
        );
    }

    @Override
    public void reconcile(String productId) {
        redisTemplate.opsForValue().set(stockKey(productId), String.valueOf(stockLedgerRepository.get(productId)));
        metricsCollector.increment("stock.reconcile");
    }

    @Override
    public int redisStock(String productId) {
        String stock = redisTemplate.opsForValue().get(stockKey(productId));
        return stock == null ? 0 : Integer.parseInt(stock);
    }

    @Override
    public int databaseStock(String productId) {
        return stockLedgerRepository.get(productId);
    }

    boolean processCompensation(String eventType, String reservationId, String productId) {
        return switch (eventType) {
            case "stock-confirm-rollback-required" -> rollbackFailedConfirmReservation(reservationId, productId);
            case "stock-confirm-redis-reconcile" -> reconcileConfirmedReservation(reservationId, productId);
            default -> false;
        };
    }

    private ReservationSnapshot readReservation(String reservationId) {
        var entries = redisTemplate.opsForHash().entries(reservationKey(reservationId));
        if (entries.isEmpty()) {
            return null;
        }
        return new ReservationSnapshot(
                String.valueOf(entries.get("productId")),
                Integer.parseInt(String.valueOf(entries.get("quantity")))
        );
    }

    private OperationResult<StockReservation> confirmedResult(String message, String reservationId, ReservationSnapshot snapshot) {
        return OperationResult.success(
                message,
                new StockReservation(
                        reservationId,
                        snapshot.productId(),
                        snapshot.quantity(),
                        stockLedgerRepository.get(snapshot.productId()),
                        true
                )
        );
    }

    private boolean rollbackFailedConfirmReservation(String reservationId, String productId) {
        OperationResult<StockReservation> releaseResult = releaseReservation(reservationId, "compensation-retry");
        if (releaseResult.success() || "reservation-not-found".equals(releaseResult.message())) {
            reconcile(productId);
            return true;
        }
        return false;
    }

    private boolean reconcileConfirmedReservation(String reservationId, String productId) {
        reconcile(productId);
        try {
            List result = redisTemplate.execute(confirmScript, List.of(reservationKey(reservationId)), productId);
            long code = longAt(result, 0);
            return code == 0L || code == 1L || code == 2L;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void recordCompensation(String event, String reservationId, String productId, String detail) {
        compensationLog.append("STOCK_COMPENSATION|" + event + "|" + reservationId + "|" + productId + "|" + safe(detail));
    }

    private String safe(String value) {
        return value == null ? "unknown" : value.replace('|', '/');
    }

    private static DefaultRedisScript<List> script(String path) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }

    private static long longAt(List result, int index) {
        return result == null || result.size() <= index ? -1L : Long.parseLong(String.valueOf(result.get(index)));
    }

    private static String stockKey(String productId) {
        return "galaxy:stock:" + productId;
    }

    private static String reservationKey(String reservationId) {
        return "galaxy:stock:reservation:" + reservationId;
    }

    private record ReservationSnapshot(String productId, int quantity) {
    }
}
