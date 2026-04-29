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
import cn.wdidada.galaxymall.db.OrderRepository;
import cn.wdidada.galaxymall.message.InMemoryMessageBus;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;

public final class CloudOrderService {

    private final OrderRepository orderRepository;
    private final LocalBinlogRepository localBinlogRepository;
    private final MetricsCollector metricsCollector;
    private final TraceLogger traceLogger;
    private final ProductClient productClient;
    private final InMemoryMessageBus messageBus;

    public CloudOrderService(
            OrderRepository orderRepository,
            LocalBinlogRepository localBinlogRepository,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger,
            ProductClient productClient,
            InMemoryMessageBus messageBus
    ) {
        this.orderRepository = orderRepository;
        this.localBinlogRepository = localBinlogRepository;
        this.metricsCollector = metricsCollector;
        this.traceLogger = traceLogger;
        this.productClient = productClient;
        this.messageBus = messageBus;
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
        orderRepository.save(order);
        localBinlogRepository.append("ORDER_SUBMITTED|" + order.orderId() + "|" + request.userId() + "|" + request.productId());
        messageBus.publish(OrderTopics.ORDER_SUBMITTED, order.orderId(), new OrderSubmittedEvent(order.orderId()));
        metricsCollector.increment("order.submitted");
        traceLogger.log("order-service", "submit", order.orderId());
        return ApiResponse.success("order-submitted", toView(order));
    }

    public ApiResponse<OrderView> getOrder(String orderId) {
        return orderRepository.findById(orderId)
                .map(order -> ApiResponse.success("order-found", toView(order)))
                .orElseGet(() -> ApiResponse.failure("order-not-found"));
    }

    public ApiResponse<OrderView> markPaid(String orderId) {
        return orderRepository.findById(orderId)
                .map(order -> {
                    if (order.status() != OrderStatus.WAITING_PAYMENT) {
                        return ApiResponse.<OrderView>failure("order-not-payable");
                    }
                    order.updateStatus(OrderStatus.PAID);
                    localBinlogRepository.append("ORDER_PAID|" + orderId);
                    metricsCollector.increment("order.paid");
                    traceLogger.log("order-service", "paid", orderId);
                    return ApiResponse.success("order-paid", toView(order));
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
