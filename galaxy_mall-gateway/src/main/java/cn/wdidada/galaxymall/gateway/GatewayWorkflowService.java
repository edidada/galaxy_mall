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

    private final TokenBucketRateLimiter queryTokenBucket;
    private final TokenBucketRateLimiter orderTokenBucket;
    private final LeakyBucketRateLimiter orderLeakyBucket;
    private final WindowRateLimiter userLimiter;
    private final WaitingRoomService waitingRoomService;
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
            GatewayTrafficProperties trafficProperties,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger
    ) {
        this.userClient = userClient;
        this.productClient = productClient;
        this.orderClient = orderClient;
        this.paymentClient = paymentClient;
        this.queryTokenBucket = new TokenBucketRateLimiter(
                trafficProperties.getQuery().getTokenCapacity(),
                trafficProperties.getQuery().getRefillPerSecond()
        );
        this.orderTokenBucket = new TokenBucketRateLimiter(
                trafficProperties.getOrder().getTokenCapacity(),
                trafficProperties.getOrder().getRefillPerSecond()
        );
        this.orderLeakyBucket = new LeakyBucketRateLimiter(
                trafficProperties.getOrder().getLeakyBucketCapacity(),
                trafficProperties.getOrder().getLeakyBucketPerSecond()
        );
        this.userLimiter = new WindowRateLimiter(trafficProperties.getOrder().getUserWindowLimit());
        this.waitingRoomService = new WaitingRoomService(
                trafficProperties.getWaitingRoom().getCapacity(),
                trafficProperties.getWaitingRoom().getReleasePerSecond()
        );
        this.metricsCollector = metricsCollector;
        this.traceLogger = traceLogger;
    }

    public ApiResponse<ProductView> queryProduct(String userId, String productId, String clientIp) {
        ApiResponse<UserView> userResponse = getUser(userId);
        if (!userResponse.success()) {
            return ApiResponse.failure("user-not-found");
        }
        if (!queryTokenBucket.tryAcquire()) {
            metricsCollector.increment("gateway.query.throttled");
            return ApiResponse.failure("query-throttled");
        }
        ApiResponse<ProductView> productResponse = getProduct(productId);
        traceLogger.log("gateway", "queryProduct", clientIp + " " + productId + " " + productResponse.message());
        return productResponse;
    }

    public ApiResponse<OrderView> placeOrder(GatewayOrderRequest request) {
        ApiResponse<UserView> userResponse = getUser(request.userId());
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
        ApiResponse<ProductView> productResponse = getProduct(request.productId());
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

    private ApiResponse<UserView> getUser(String userId) {
        return userClient.getUser(userId);
    }

    private ApiResponse<ProductView> getProduct(String productId) {
        return productClient.getProduct(productId);
    }
}
