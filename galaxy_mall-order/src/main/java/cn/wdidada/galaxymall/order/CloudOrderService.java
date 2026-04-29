package cn.wdidada.galaxymall.order;

import cn.wdidada.galaxymall.common.IdGenerator;
import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.CreateOrderRequest;
import cn.wdidada.galaxymall.common.api.OrderView;
import cn.wdidada.galaxymall.common.api.ProductView;
import cn.wdidada.galaxymall.common.api.StockDeductRequest;
import cn.wdidada.galaxymall.common.api.StockView;
import cn.wdidada.galaxymall.common.client.ProductClient;
import cn.wdidada.galaxymall.common.client.StockClient;
import cn.wdidada.galaxymall.common.domain.Order;
import cn.wdidada.galaxymall.common.domain.OrderStatus;
import cn.wdidada.galaxymall.db.LocalBinlogRepository;
import cn.wdidada.galaxymall.db.OrderRepository;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;

public final class CloudOrderService {

    private final OrderRepository orderRepository;
    private final LocalBinlogRepository localBinlogRepository;
    private final MetricsCollector metricsCollector;
    private final TraceLogger traceLogger;
    private final ProductClient productClient;
    private final StockClient stockClient;

    public CloudOrderService(
            OrderRepository orderRepository,
            LocalBinlogRepository localBinlogRepository,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger,
            ProductClient productClient,
            StockClient stockClient
    ) {
        this.orderRepository = orderRepository;
        this.localBinlogRepository = localBinlogRepository;
        this.metricsCollector = metricsCollector;
        this.traceLogger = traceLogger;
        this.productClient = productClient;
        this.stockClient = stockClient;
    }

    public ApiResponse<OrderView> createOrder(CreateOrderRequest request) {
        ApiResponse<ProductView> productResponse = productClient.getProduct(request.productId());
        if (!productResponse.success() || productResponse.data() == null) {
            return ApiResponse.failure("product-not-found");
        }

        ApiResponse<StockView> stockResponse = stockClient.reserve(new StockDeductRequest(request.productId(), request.quantity()));
        if (!stockResponse.success()) {
            return ApiResponse.failure(stockResponse.message());
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
        order.updateStatus(OrderStatus.WAITING_PAYMENT);
        orderRepository.save(order);
        localBinlogRepository.append("ORDER_CREATED|" + order.orderId() + "|" + request.userId() + "|" + request.productId());
        metricsCollector.increment("order.created");
        traceLogger.log("order-service", "create", order.orderId());
        return ApiResponse.success("order-created", toView(order));
    }

    public ApiResponse<OrderView> getOrder(String orderId) {
        return orderRepository.findById(orderId)
                .map(order -> ApiResponse.success("order-found", toView(order)))
                .orElseGet(() -> ApiResponse.failure("order-not-found"));
    }

    public ApiResponse<OrderView> markPaid(String orderId) {
        return orderRepository.findById(orderId)
                .map(order -> {
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
                order.status().name()
        );
    }
}
