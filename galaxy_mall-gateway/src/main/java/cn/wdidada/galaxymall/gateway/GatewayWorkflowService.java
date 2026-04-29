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

    private final TokenBucketRateLimiter queryTokenBucket = new TokenBucketRateLimiter(10_000, 5_000);
    private final TokenBucketRateLimiter orderTokenBucket = new TokenBucketRateLimiter(5_000, 2_000);
    private final LeakyBucketRateLimiter orderLeakyBucket = new LeakyBucketRateLimiter(1_500, 1_000);
    private final WindowRateLimiter userLimiter = new WindowRateLimiter(3);
    private final WaitingRoomService waitingRoomService = new WaitingRoomService(1_000_000, 100_000);
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
        if (!queryTokenBucket.tryAcquire()) {
            metricsCollector.increment("gateway.query.throttled");
            return ApiResponse.failure("query-throttled");
        }
        ApiResponse<ProductView> productResponse = productClient.getProduct(productId);
        traceLogger.log("gateway", "queryProduct", clientIp + " " + productId + " " + productResponse.message());
        return productResponse;
    }

    public ApiResponse<OrderView> placeOrder(GatewayOrderRequest request) {
        ApiResponse<UserView> userResponse = userClient.getUser(request.userId());
        if (!userResponse.success()) {
            return ApiResponse.failure("user-not-found");
        }
        String businessKey = businessKey(request);
        if (request.queueToken() != null && waitingRoomService.canPass(businessKey, request.queueToken())) {
            metricsCollector.increment("gateway.queue.admit");
            return submitOrder(request);
        }
        if (!allowFastLane(request.userId())) {
            metricsCollector.increment("gateway.queue");
            WaitingRoomService.WaitingTicket ticket = waitingRoomService.enter(businessKey);
            if (ticket.rejected()) {
                return ApiResponse.failure("waiting-room-full");
            }
            return ApiResponse.success(
                    queuePageService.build("order queued", ticket.token(), ticket.ahead(), ticket.readyAtMillis()),
                    new OrderView(
                            "QUEUE-" + ticket.token(),
                            request.userId(),
                            request.productId(),
                            request.quantity(),
                            0,
                            "QUEUED",
                            ticket.token(),
                            System.currentTimeMillis()
                    )
            );
        }
        return submitOrder(request);
    }

    public ApiResponse<OrderView> getOrder(String orderId) {
        return orderClient.getOrder(orderId);
    }

    public ApiResponse<PaymentView> pay(String orderId) {
        return paymentClient.pay(orderId);
    }

    private ApiResponse<OrderView> submitOrder(GatewayOrderRequest request) {
        ApiResponse<ProductView> productResponse = productClient.getProduct(request.productId());
        if (!productResponse.success()) {
            return ApiResponse.failure(productResponse.message());
        }
        return orderClient.createOrder(new CreateOrderRequest(request.userId(), request.productId(), request.quantity()));
    }

    private boolean allowFastLane(String userId) {
        boolean allowed = orderTokenBucket.tryAcquire() && orderLeakyBucket.allow() && userLimiter.allow(userId);
        if (!allowed) {
            traceLogger.log("gateway", "queue", userId + " order.place");
        }
        return allowed;
    }

    private String businessKey(GatewayOrderRequest request) {
        return request.userId() + "|" + request.productId() + "|" + request.clientIp();
    }
}
