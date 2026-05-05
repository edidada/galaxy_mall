package cn.wdidada.galaxymall.stock;

import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.domain.StockReservation;
import cn.wdidada.galaxymall.db.StockLedgerRepository;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class InMemoryStockBackend implements StockBackend {

    private final StockLedgerRepository stockLedgerRepository;
    private final MetricsCollector metricsCollector;
    private final LocalRateGuard localRateGuard;
    private final Map<String, AtomicInteger> redisStock = new ConcurrentHashMap<>();
    private final Map<String, ReservationRecord> reservations = new ConcurrentHashMap<>();

    public InMemoryStockBackend(StockLedgerRepository stockLedgerRepository, MetricsCollector metricsCollector, int maxPerSecond) {
        this.stockLedgerRepository = stockLedgerRepository;
        this.metricsCollector = metricsCollector;
        this.localRateGuard = new LocalRateGuard(maxPerSecond);
    }

    @Override
    public void preload(String productId) {
        redisStock.put(productId, new AtomicInteger(stockLedgerRepository.get(productId)));
    }

    @Override
    public OperationResult<StockReservation> reserve(String reservationId, String productId, int quantity) {
        if (!localRateGuard.tryAcquire()) {
            metricsCollector.increment("stock.local.limit.reject");
            return OperationResult.failure("local-rate-limit-rejected");
        }
        AtomicInteger stock = redisStock.computeIfAbsent(productId, ignored -> new AtomicInteger(stockLedgerRepository.get(productId)));
        while (true) {
            int current = stock.get();
            if (current < quantity) {
                metricsCollector.increment("stock.redis.reject");
                return OperationResult.failure("stock-not-enough");
            }
            if (stock.compareAndSet(current, current - quantity)) {
                reservations.put(reservationId, new ReservationRecord(reservationId, productId, quantity));
                metricsCollector.increment("stock.redis.reserve.success");
                return OperationResult.success(
                        "stock-reserved",
                        new StockReservation(reservationId, productId, quantity, current - quantity, false)
                );
            }
        }
    }

    @Override
    public OperationResult<StockReservation> confirm(String reservationId) {
        ReservationRecord record = reservations.get(reservationId);
        if (record == null) {
            return OperationResult.failure("reservation-not-found");
        }
        boolean success = stockLedgerRepository.decreaseWithLock(record.productId(), record.quantity());
        if (!success) {
            restore(record.productId(), record.quantity());
            reservations.remove(reservationId);
            metricsCollector.increment("stock.db.confirm.fail");
            return OperationResult.failure("db-confirm-failed");
        }
        reservations.remove(reservationId);
        metricsCollector.increment("stock.db.confirm.success");
        return OperationResult.success(
                "db-confirmed",
                new StockReservation(reservationId, record.productId(), record.quantity(), stockLedgerRepository.get(record.productId()), true)
        );
    }

    @Override
    public OperationResult<StockReservation> release(String reservationId, String reason) {
        ReservationRecord record = reservations.remove(reservationId);
        if (record == null) {
            return OperationResult.failure("reservation-not-found");
        }
        restore(record.productId(), record.quantity());
        metricsCollector.increment("stock.release");
        return OperationResult.success(
                "reservation-released:" + reason,
                new StockReservation(reservationId, record.productId(), record.quantity(), redisStock(record.productId()), false)
        );
    }

    @Override
    public OperationResult<StockReservation> restock(String adjustmentId, String productId, int quantity, String reason) {
        stockLedgerRepository.increase(productId, quantity);
        redisStock.computeIfAbsent(productId, ignored -> new AtomicInteger()).addAndGet(quantity);
        metricsCollector.increment("stock.restock");
        return OperationResult.success(
                "stock-restocked:" + reason,
                new StockReservation(adjustmentId, productId, quantity, redisStock(productId), true)
        );
    }

    @Override
    public OperationResult<StockReservation> compensateRestock(String adjustmentId, String productId, int quantity) {
        boolean success = stockLedgerRepository.decreaseWithLock(productId, quantity);
        if (!success) {
            metricsCollector.increment("stock.restock.compensation.reject");
            return OperationResult.failure("stock-not-enough-for-restock-compensation");
        }
        AtomicInteger stock = redisStock.computeIfAbsent(productId, ignored -> new AtomicInteger(stockLedgerRepository.get(productId)));
        stock.addAndGet(-quantity);
        metricsCollector.increment("stock.restock.compensation");
        return OperationResult.success(
                "restock-compensated",
                new StockReservation(adjustmentId, productId, quantity, stock.get(), true)
        );
    }

    @Override
    public void reconcile(String productId) {
        int dbStock = stockLedgerRepository.get(productId);
        redisStock.put(productId, new AtomicInteger(dbStock));
        metricsCollector.increment("stock.reconcile");
    }

    @Override
    public int redisStock(String productId) {
        return redisStock.getOrDefault(productId, new AtomicInteger()).get();
    }

    @Override
    public int databaseStock(String productId) {
        return stockLedgerRepository.get(productId);
    }

    private void restore(String productId, int quantity) {
        redisStock.computeIfAbsent(productId, ignored -> new AtomicInteger()).addAndGet(quantity);
        metricsCollector.increment("stock.restore");
    }

    private record ReservationRecord(String reservationId, String productId, int quantity) {
    }
}
