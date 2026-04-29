package cn.wdidada.galaxymall.stock;

import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.domain.StockReservation;
import cn.wdidada.galaxymall.db.StockLedgerRepository;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class StockService {

    private final StockLedgerRepository stockLedgerRepository;
    private final MetricsCollector metricsCollector;
    private final LocalRateGuard localRateGuard;
    private final Map<String, AtomicInteger> redisStock = new ConcurrentHashMap<>();

    public StockService(StockLedgerRepository stockLedgerRepository, MetricsCollector metricsCollector, int maxPerSecond) {
        this.stockLedgerRepository = stockLedgerRepository;
        this.metricsCollector = metricsCollector;
        this.localRateGuard = new LocalRateGuard(maxPerSecond);
    }

    public void preload(String productId) {
        redisStock.put(productId, new AtomicInteger(stockLedgerRepository.get(productId)));
    }

    public OperationResult<StockReservation> reserve(String productId, int quantity) {
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
                metricsCollector.increment("stock.redis.reserve.success");
                return OperationResult.success("stock-reserved", new StockReservation(productId, quantity, current - quantity));
            }
        }
    }

    public OperationResult<Integer> confirm(String productId, int quantity) {
        boolean success = stockLedgerRepository.decreaseWithLock(productId, quantity);
        if (!success) {
            restore(productId, quantity);
            metricsCollector.increment("stock.db.confirm.fail");
            return OperationResult.failure("db-confirm-failed");
        }
        metricsCollector.increment("stock.db.confirm.success");
        return OperationResult.success("db-confirmed", stockLedgerRepository.get(productId));
    }

    public void restore(String productId, int quantity) {
        redisStock.computeIfAbsent(productId, ignored -> new AtomicInteger()).addAndGet(quantity);
        metricsCollector.increment("stock.restore");
    }

    public void reconcile(String productId) {
        int dbStock = stockLedgerRepository.get(productId);
        redisStock.put(productId, new AtomicInteger(dbStock));
        metricsCollector.increment("stock.reconcile");
    }

    public int redisStock(String productId) {
        return redisStock.getOrDefault(productId, new AtomicInteger()).get();
    }

    public int databaseStock(String productId) {
        return stockLedgerRepository.get(productId);
    }
}
