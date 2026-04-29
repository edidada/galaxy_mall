package cn.wdidada.galaxymall.order;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.ProductView;
import cn.wdidada.galaxymall.common.api.StockConfirmRequest;
import cn.wdidada.galaxymall.common.api.StockDeductRequest;
import cn.wdidada.galaxymall.common.api.StockReleaseRequest;
import cn.wdidada.galaxymall.common.api.StockView;
import cn.wdidada.galaxymall.common.client.ProductClient;
import cn.wdidada.galaxymall.common.client.StockClient;
import cn.wdidada.galaxymall.common.domain.Order;
import cn.wdidada.galaxymall.common.domain.OrderStatus;
import cn.wdidada.galaxymall.db.LocalBinlogRepository;
import cn.wdidada.galaxymall.db.OrderStore;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;

public final class OrderAsyncConsumer {

    private final OrderStore orderStore;
    private final LocalBinlogRepository localBinlogRepository;
    private final ProductClient productClient;
    private final StockClient stockClient;
    private final MetricsCollector metricsCollector;
    private final TraceLogger traceLogger;

    public OrderAsyncConsumer(
            OrderStore orderStore,
            LocalBinlogRepository localBinlogRepository,
            ProductClient productClient,
            StockClient stockClient,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger
    ) {
        this.orderStore = orderStore;
        this.localBinlogRepository = localBinlogRepository;
        this.productClient = productClient;
        this.stockClient = stockClient;
        this.metricsCollector = metricsCollector;
        this.traceLogger = traceLogger;
    }

    public void handle(OrderSubmittedEvent event) {
        Order order = orderStore.findById(event.orderId())
                .orElseThrow(() -> new IllegalStateException("order-not-found: " + event.orderId()));
        int attempts = order.incrementAsyncAttempts();
        orderStore.updateAsyncAttempts(order.orderId(), attempts);
        order.updateStatus(OrderStatus.PROCESSING);
        orderStore.updateStatus(order.orderId(), OrderStatus.PROCESSING);
        localBinlogRepository.append("ORDER_PROCESSING|" + order.orderId());

        ApiResponse<ProductView> productResponse = productClient.getProduct(order.productId());
        if (!productResponse.success() || productResponse.data() == null) {
            failOrder(order, "product-not-found");
            return;
        }

        ApiResponse<StockView> reserveResponse = stockClient.reserve(new StockDeductRequest(order.orderId(), order.productId(), order.quantity()));
        if (!reserveResponse.success()) {
            failOrder(order, reserveResponse.message());
            return;
        }

        ApiResponse<StockView> confirmResponse = stockClient.confirm(new StockConfirmRequest(order.orderId()));
        if (!confirmResponse.success()) {
            stockClient.release(new StockReleaseRequest(order.orderId(), "confirm-failed"));
            failOrder(order, confirmResponse.message());
            return;
        }

        order.updateStatus(OrderStatus.WAITING_PAYMENT);
        orderStore.updateStatus(order.orderId(), OrderStatus.WAITING_PAYMENT);
        localBinlogRepository.append("ORDER_WAITING_PAYMENT|" + order.orderId());
        metricsCollector.increment("order.async.success");
        traceLogger.log("order-consumer", "confirm", order.orderId() + " waiting-payment");
    }

    private void failOrder(Order order, String reason) {
        order.updateStatus(OrderStatus.FAILED);
        orderStore.updateStatus(order.orderId(), OrderStatus.FAILED);
        localBinlogRepository.append("ORDER_FAILED|" + order.orderId() + "|" + reason);
        metricsCollector.increment("order.async.failed");
        traceLogger.log("order-consumer", "fail", order.orderId() + " " + reason);
    }
}
