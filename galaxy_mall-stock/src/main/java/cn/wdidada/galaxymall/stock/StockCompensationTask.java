package cn.wdidada.galaxymall.stock;

import cn.wdidada.galaxymall.db.LocalBinlogRepository;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class StockCompensationTask implements AutoCloseable {

    static final String EVENT_PREFIX = "STOCK_COMPENSATION|";

    private final LocalBinlogRepository compensationLog;
    private final RedisLuaStockBackend stockBackend;
    private final MetricsCollector metricsCollector;
    private final ScheduledExecutorService executor;

    StockCompensationTask(
            LocalBinlogRepository compensationLog,
            RedisLuaStockBackend stockBackend,
            MetricsCollector metricsCollector,
            Duration interval
    ) {
        this.compensationLog = compensationLog;
        this.stockBackend = stockBackend;
        this.metricsCollector = metricsCollector;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "galaxy-mall-stock-compensation");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = Math.max(100L, interval.toMillis());
        this.executor.scheduleWithFixedDelay(this::runOnce, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    void runOnce() {
        Optional<LocalBinlogRepository.ClaimedEvent> claimed = compensationLog.claimFirst(EVENT_PREFIX);
        if (claimed.isEmpty()) {
            return;
        }
        LocalBinlogRepository.ClaimedEvent event = claimed.get();
        try {
            if (process(event.payload())) {
                compensationLog.ack(event.id());
                metricsCollector.increment("stock.compensation.success");
                return;
            }
            compensationLog.release(event.id());
            metricsCollector.increment("stock.compensation.retry");
        } catch (RuntimeException ex) {
            compensationLog.release(event.id());
            metricsCollector.increment("stock.compensation.retry");
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private boolean process(String payload) {
        String[] parts = payload.split("\\|", 5);
        if (parts.length < 4) {
            metricsCollector.increment("stock.compensation.invalid");
            return true;
        }
        return stockBackend.processCompensation(parts[1], parts[2], parts[3]);
    }
}
