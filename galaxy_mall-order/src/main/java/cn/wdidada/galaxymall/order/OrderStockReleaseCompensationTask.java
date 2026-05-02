package cn.wdidada.galaxymall.order;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.StockReleaseRequest;
import cn.wdidada.galaxymall.common.api.StockView;
import cn.wdidada.galaxymall.common.client.StockClient;
import cn.wdidada.galaxymall.db.LocalBinlogRepository;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class OrderStockReleaseCompensationTask implements AutoCloseable {

    static final String EVENT_PREFIX = "ORDER_STOCK_RELEASE_RETRY|";

    private final LocalBinlogRepository localBinlogRepository;
    private final StockClient stockClient;
    private final MetricsCollector metricsCollector;
    private final ScheduledExecutorService executor;

    OrderStockReleaseCompensationTask(
            LocalBinlogRepository localBinlogRepository,
            StockClient stockClient,
            MetricsCollector metricsCollector,
            Duration interval
    ) {
        this.localBinlogRepository = localBinlogRepository;
        this.stockClient = stockClient;
        this.metricsCollector = metricsCollector;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "galaxy-mall-order-compensation");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = Math.max(100L, interval.toMillis());
        this.executor.scheduleWithFixedDelay(this::runOnce, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    void runOnce() {
        Optional<LocalBinlogRepository.ClaimedEvent> claimed = localBinlogRepository.claimFirst(EVENT_PREFIX);
        if (claimed.isEmpty()) {
            return;
        }
        LocalBinlogRepository.ClaimedEvent event = claimed.get();
        try {
            if (retryRelease(event.payload())) {
                localBinlogRepository.ack(event.id());
                metricsCollector.increment("order.stock.release.compensation.success");
                return;
            }
            localBinlogRepository.release(event.id());
            metricsCollector.increment("order.stock.release.compensation.retry");
        } catch (RuntimeException ex) {
            localBinlogRepository.release(event.id());
            metricsCollector.increment("order.stock.release.compensation.retry");
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private boolean retryRelease(String payload) {
        String[] parts = payload.split("\\|", 3);
        if (parts.length < 2) {
            metricsCollector.increment("order.stock.release.compensation.invalid");
            return true;
        }
        String reservationId = parts[1];
        ApiResponse<StockView> response = stockClient.release(new StockReleaseRequest(reservationId, "compensation-retry"));
        return response.success();
    }
}
