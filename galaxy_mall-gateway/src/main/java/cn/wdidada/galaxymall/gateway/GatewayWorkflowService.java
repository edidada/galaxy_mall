package cn.wdidada.galaxymall.gateway;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.CreateOrderRequest;
import cn.wdidada.galaxymall.common.api.GatewayOrderRequest;
import cn.wdidada.galaxymall.common.api.OrderView;
import cn.wdidada.galaxymall.common.api.PaymentView;
import cn.wdidada.galaxymall.common.api.ProductView;
import cn.wdidada.galaxymall.common.api.UserView;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

public final class GatewayWorkflowService {

    private static final ParameterizedTypeReference<ApiResponse<UserView>> USER_RESPONSE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<ProductView>> PRODUCT_RESPONSE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<OrderView>> ORDER_RESPONSE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<PaymentView>> PAYMENT_RESPONSE =
            new ParameterizedTypeReference<>() {};

    private final TokenBucketRateLimiter queryTokenBucket = new TokenBucketRateLimiter(10_000, 5_000);
    private final TokenBucketRateLimiter orderTokenBucket = new TokenBucketRateLimiter(5_000, 2_000);
    private final LeakyBucketRateLimiter orderLeakyBucket = new LeakyBucketRateLimiter(1_500, 1_000);
    private final WindowRateLimiter userLimiter = new WindowRateLimiter(3);
    private final WaitingRoomService waitingRoomService = new WaitingRoomService(1_000_000, 100_000);
    private final QueuePageService queuePageService = new QueuePageService();
    private final RestClient userServiceRestClient;
    private final RestClient productServiceRestClient;
    private final RestClient orderServiceRestClient;
    private final RestClient paymentServiceRestClient;
    private final MetricsCollector metricsCollector;
    private final TraceLogger traceLogger;

    public GatewayWorkflowService(
            RestClient userServiceRestClient,
            RestClient productServiceRestClient,
            RestClient orderServiceRestClient,
            RestClient paymentServiceRestClient,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger
    ) {
        this.userServiceRestClient = userServiceRestClient;
        this.productServiceRestClient = productServiceRestClient;
        this.orderServiceRestClient = orderServiceRestClient;
        this.paymentServiceRestClient = paymentServiceRestClient;
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
        return orderServiceRestClient.get()
                .uri("/api/orders/{orderId}", orderId)
                .retrieve()
                .body(ORDER_RESPONSE);
    }

    public ApiResponse<PaymentView> pay(String orderId) {
        return paymentServiceRestClient.post()
                .uri("/api/payments/{orderId}", orderId)
                .retrieve()
                .body(PAYMENT_RESPONSE);
    }

    private ApiResponse<OrderView> submitOrder(GatewayOrderRequest request) {
        ApiResponse<ProductView> productResponse = getProduct(request.productId());
        if (!productResponse.success()) {
            return ApiResponse.failure(productResponse.message());
        }
        return orderServiceRestClient.post()
                .uri("/api/orders")
                .body(new CreateOrderRequest(request.userId(), request.productId(), request.quantity()))
                .retrieve()
                .body(ORDER_RESPONSE);
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
        return userServiceRestClient.get()
                .uri("/api/users/{userId}", userId)
                .retrieve()
                .body(USER_RESPONSE);
    }

    private ApiResponse<ProductView> getProduct(String productId) {
        return productServiceRestClient.get()
                .uri("/api/products/{productId}", productId)
                .retrieve()
                .body(PRODUCT_RESPONSE);
    }
}
