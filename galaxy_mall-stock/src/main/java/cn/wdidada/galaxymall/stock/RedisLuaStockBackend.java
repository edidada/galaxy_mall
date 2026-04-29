package cn.wdidada.galaxymall.stock;

import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.domain.StockReservation;
import cn.wdidada.galaxymall.db.StockLedgerRepository;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisLuaStockBackend implements StockBackend {

    private final StockLedgerRepository stockLedgerRepository;
    private final MetricsCollector metricsCollector;
    private final LocalRateGuard localRateGuard;
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> reserveScript;
    private final DefaultRedisScript<List> confirmScript;
    private final DefaultRedisScript<List> releaseScript;

    public RedisLuaStockBackend(
            StockLedgerRepository stockLedgerRepository,
            MetricsCollector metricsCollector,
            int maxPerSecond,
            StringRedisTemplate redisTemplate
    ) {
        this.stockLedgerRepository = stockLedgerRepository;
        this.metricsCollector = metricsCollector;
        this.localRateGuard = new LocalRateGuard(maxPerSecond);
        this.redisTemplate = redisTemplate;
        this.reserveScript = script("lua/reserve_stock.lua");
        this.confirmScript = script("lua/confirm_stock.lua");
        this.releaseScript = script("lua/release_stock.lua");
    }

    @Override
    public void preload(String productId) {
        redisTemplate.opsForValue().set(stockKey(productId), String.valueOf(stockLedgerRepository.get(productId)));
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
        metricsCollector.increment("stock.redis.reserve.success");
        return OperationResult.success(
                "stock-reserved",
                new StockReservation(reservationId, productId, quantity, remaining, false)
        );
    }

    @Override
    public OperationResult<StockReservation> confirm(String reservationId) {
        ReservationSnapshot snapshot = readReservation(reservationId);
        if (snapshot == null) {
            return OperationResult.failure("reservation-not-found");
        }
        boolean success = stockLedgerRepository.decreaseWithLock(snapshot.productId(), snapshot.quantity());
        if (!success) {
            release(reservationId, "db-confirm-failed");
            metricsCollector.increment("stock.db.confirm.fail");
            return OperationResult.failure("db-confirm-failed");
        }
        redisTemplate.execute(confirmScript, List.of(reservationKey(reservationId)), snapshot.productId());
        metricsCollector.increment("stock.db.confirm.success");
        return OperationResult.success(
                "db-confirmed",
                new StockReservation(reservationId, snapshot.productId(), snapshot.quantity(), stockLedgerRepository.get(snapshot.productId()), true)
        );
    }

    @Override
    public OperationResult<StockReservation> release(String reservationId, String reason) {
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
