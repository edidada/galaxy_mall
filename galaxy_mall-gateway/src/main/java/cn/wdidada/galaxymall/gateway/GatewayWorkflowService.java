package cn.wdidada.galaxymall.gateway;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.CreateOrderRequest;
import cn.wdidada.galaxymall.common.api.GatewayOrderRequest;
import cn.wdidada.galaxymall.common.api.OrderView;
import cn.wdidada.galaxymall.common.api.PaymentView;
import cn.wdidada.galaxymall.common.api.ProductView;
import cn.wdidada.galaxymall.common.api.UserView;
import cn.wdidada.galaxymall.common.client.OrderClient;
import cn.wdidada.galaxymall.common.client.PaymentClient;
import cn.wdidada.galaxymall.common.client.ProductClient;
import cn.wdidada.galaxymall.common.client.UserClient;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;

public final class GatewayWorkflowService {

    private final WindowRateLimiter ipLimiter = new WindowRateLimiter(2);
    private final WindowRateLimiter interfaceLimiter = new WindowRateLimiter(4);
    private final WindowRateLimiter userLimiter = new WindowRateLimiter(3);
    private final QueuePageService queuePageService = new QueuePageService();
    private final UserClient userClient;
    private final ProductClient productClient;
    private final OrderClient orderClient;
    private final PaymentClient paymentClient;
    private final MetricsCollector metricsCollector;
    private final TraceLogger traceLogger;

    public GatewayWorkflowService(
            UserClient userClient,
            ProductClient productClient,
            OrderClient orderClient,
            PaymentClient paymentClient,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger
    ) {
        this.userClient = userClient;
        this.productClient = productClient;
        this.orderClient = orderClient;
        this.paymentClient = paymentClient;
        this.metricsCollector = metricsCollector;
        this.traceLogger = traceLogger;
    }

    public ApiResponse<ProductView> queryProduct(String userId, String productId, String clientIp) {
        ApiResponse<UserView> userResponse = userClient.getUser(userId);
        if (!userResponse.success()) {
            return ApiResponse.failure("user-not-found");
        }
        if (!allow(clientIp, userId, "product.query")) {
            return ApiResponse.failure(queuePageService.build("query throttled"));
        }
        ApiResponse<ProductView> productResponse = productClient.getProduct(productId);
        traceLogger.log("gateway", "queryProduct", productId + " " + productResponse.message());
        return productResponse;
    }

    public ApiResponse<OrderView> placeOrder(GatewayOrderRequest request) {
        ApiResponse<UserView> userResponse = userClient.getUser(request.userId());
        if (!userResponse.success()) {
            return ApiResponse.failure("user-not-found");
        }
        if (!allow(request.clientIp(), request.userId(), "order.place")) {
            metricsCollector.increment("gateway.queue");
            return ApiResponse.failure(queuePageService.build("order throttled"));
        }
        ApiResponse<ProductView> productResponse = productClient.getProduct(request.productId());
        if (!productResponse.success()) {
            return ApiResponse.failure(productResponse.message());
        }
        return orderClient.createOrder(new CreateOrderRequest(request.userId(), request.productId(), request.quantity()));
    }

    public ApiResponse<PaymentView> pay(String orderId) {
        return paymentClient.pay(orderId);
    }

    private boolean allow(String ip, String userId, String api) {
        boolean allowed = ipLimiter.allow(ip) && userLimiter.allow(userId) && interfaceLimiter.allow(api);
        if (!allowed) {
            traceLogger.log("gateway", "queue", ip + " " + api);
        }
        return allowed;
    }
}
