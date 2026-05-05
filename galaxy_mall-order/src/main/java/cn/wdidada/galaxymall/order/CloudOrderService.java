package cn.wdidada.galaxymall.order;

import cn.wdidada.galaxymall.common.IdGenerator;
import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.CreateOrderRequest;
import cn.wdidada.galaxymall.common.api.OrderView;
import cn.wdidada.galaxymall.common.api.ProductView;
import cn.wdidada.galaxymall.common.client.ProductClient;
import cn.wdidada.galaxymall.common.domain.Order;
import cn.wdidada.galaxymall.common.domain.OrderStatus;
import cn.wdidada.galaxymall.db.LocalBinlogRepository;
import cn.wdidada.galaxymall.db.OrderStore;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;

public final class CloudOrderService {

    private final OrderStore orderStore;
    private final LocalBinlogRepository localBinlogRepository;
    private final MetricsCollector metricsCollector;
    private final TraceLogger traceLogger;
    private final ProductClient productClient;
    private final OrderMessagePublisher orderMessagePublisher;

    public CloudOrderService(
            OrderStore orderStore,
            LocalBinlogRepository localBinlogRepository,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger,
            ProductClient productClient,
            OrderMessagePublisher orderMessagePublisher
    ) {
        this.orderStore = orderStore;
        this.localBinlogRepository = localBinlogRepository;
        this.metricsCollector = metricsCollector;
        this.traceLogger = traceLogger;
        this.productClient = productClient;
        this.orderMessagePublisher = orderMessagePublisher;
    }

    public ApiResponse<OrderView> createOrder(CreateOrderRequest request) {
        ApiResponse<ProductView> productResponse = productClient.getProduct(request.productId());
        if (!productResponse.success() || productResponse.data() == null) {
            return ApiResponse.failure("product-not-found");
        }

        ProductView product = productResponse.data();
        Order order = new Order(
                IdGenerator.nextOrderId(),
                request.userId(),
                request.productId(),
                request.quantity(),
                product.priceCent() * request.quantity(),
                product.seckillProduct()
        );
        order.updateStatus(OrderStatus.SUBMITTED);
        orderStore.save(order);
        localBinlogRepository.append("ORDER_SUBMITTED|" + order.orderId() + "|" + request.userId() + "|" + request.productId());
        orderMessagePublisher.publish(new OrderSubmittedEvent(order.orderId()));
        metricsCollector.increment("order.submitted");
        traceLogger.log("order-service", "submit", order.orderId());
        return ApiResponse.success("order-submitted", toView(order));
    }

    public ApiResponse<OrderView> getOrder(String orderId) {
        return orderStore.findById(orderId)
                .map(order -> ApiResponse.success("order-found", toView(order)))
                .orElseGet(() -> ApiResponse.failure("order-not-found"));
    }

    public ApiResponse<OrderView> markPaid(String orderId) {
        return orderStore.findById(orderId)
                .map(order -> {
                    if (order.status() != OrderStatus.WAITING_PAYMENT) {
                        return ApiResponse.<OrderView>failure("order-not-payable");
                    }
                    order.updateStatus(OrderStatus.PAID);
                    orderStore.updateStatus(orderId, OrderStatus.PAID);
                    localBinlogRepository.append("ORDER_PAID|" + orderId);
                    metricsCollector.increment("order.paid");
                    traceLogger.log("order-service", "paid", orderId);
                    return ApiResponse.success("order-paid", toView(order));
                })
                .orElseGet(() -> ApiResponse.failure("order-not-found"));
    }

    public ApiResponse<OrderView> markRefunded(String orderId) {
        return orderStore.findById(orderId)
                .map(order -> {
                    if (order.status() != OrderStatus.PAID) {
                        return ApiResponse.<OrderView>failure("order-not-refundable");
                    }
                    order.updateStatus(OrderStatus.REFUNDED);
                    orderStore.updateStatus(orderId, OrderStatus.REFUNDED);
                    localBinlogRepository.append("ORDER_REFUNDED|" + orderId);
                    metricsCollector.increment("order.refunded");
                    traceLogger.log("order-service", "refunded", orderId);
                    return ApiResponse.success("order-refunded", toView(order));
                })
                .orElseGet(() -> ApiResponse.failure("order-not-found"));
    }

    public ApiResponse<OrderView> rollbackRefund(String orderId) {
        return orderStore.findById(orderId)
                .map(order -> {
                    if (order.status() != OrderStatus.REFUNDED) {
                        return ApiResponse.<OrderView>failure("order-refund-not-rollbackable");
                    }
                    order.updateStatus(OrderStatus.PAID);
                    orderStore.updateStatus(orderId, OrderStatus.PAID);
                    localBinlogRepository.append("ORDER_REFUND_ROLLBACK|" + orderId);
                    metricsCollector.increment("order.refund.rollback");
                    traceLogger.log("order-service", "refund-rollback", orderId);
                    return ApiResponse.success("order-refund-rolled-back", toView(order));
                })
                .orElseGet(() -> ApiResponse.failure("order-not-found"));
    }

    private OrderView toView(Order order) {
        return new OrderView(
                order.orderId(),
                order.userId(),
                order.productId(),
                order.quantity(),
                order.amountCent(),
                order.status().name(),
                null,
                order.createdAt().toEpochMilli()
        );
    }
}
