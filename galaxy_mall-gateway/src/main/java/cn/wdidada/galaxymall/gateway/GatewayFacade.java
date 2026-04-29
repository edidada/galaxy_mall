package cn.wdidada.galaxymall.gateway;

import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.TraceContext;
import cn.wdidada.galaxymall.common.domain.Order;
import cn.wdidada.galaxymall.common.domain.Product;
import cn.wdidada.galaxymall.common.domain.User;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;
import cn.wdidada.galaxymall.order.OrderService;
import cn.wdidada.galaxymall.product.ProductCatalogService;
import cn.wdidada.galaxymall.user.UserService;

public final class GatewayFacade {

    private final WindowRateLimiter ipLimiter = new WindowRateLimiter(2);
    private final WindowRateLimiter interfaceLimiter = new WindowRateLimiter(4);
    private final WindowRateLimiter userLimiter = new WindowRateLimiter(3);
    private final QueuePageService queuePageService = new QueuePageService();
    private final ProductCatalogService productCatalogService;
    private final UserService userService;
    private final OrderService orderService;
    private final MetricsCollector metricsCollector;
    private final TraceLogger traceLogger;

    public GatewayFacade(
            ProductCatalogService productCatalogService,
            UserService userService,
            OrderService orderService,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger
    ) {
        this.productCatalogService = productCatalogService;
        this.userService = userService;
        this.orderService = orderService;
        this.metricsCollector = metricsCollector;
        this.traceLogger = traceLogger;
    }

    public OperationResult<Product> queryProduct(String userId, String productId, String ip) {
        TraceContext.start();
        try {
            OperationResult<User> userResult = userService.findUser(userId);
            if (!userResult.success()) {
                return OperationResult.failure(userResult.message());
            }
            if (!allow(ip, userId, "product.query")) {
                return OperationResult.failure(queuePageService.build("query throttled"));
            }
            OperationResult<Product> result = productCatalogService.queryProduct(productId);
            traceLogger.log("gateway", "queryProduct", productId + " " + result.message());
            return result;
        } finally {
            TraceContext.clear();
        }
    }

    public OperationResult<Order> placeOrder(String userId, String productId, int quantity, String ip, boolean seckill) {
        TraceContext.start();
        try {
            OperationResult<User> userResult = userService.findUser(userId);
            if (!userResult.success()) {
                return OperationResult.failure(userResult.message());
            }
            if (!allow(ip, userId, "order.place")) {
                metricsCollector.increment("gateway.queue");
                return OperationResult.failure(queuePageService.build("order throttled"));
            }
            OperationResult<Product> productResult = productCatalogService.queryProduct(productId);
            if (!productResult.success()) {
                return OperationResult.failure(productResult.message());
            }
            return orderService.placeOrder(userResult.data(), productResult.data(), quantity, seckill);
        } finally {
            TraceContext.clear();
        }
    }

    private boolean allow(String ip, String userId, String api) {
        boolean allowed = ipLimiter.allow(ip) && userLimiter.allow(userId) && interfaceLimiter.allow(api);
        if (!allowed) {
            traceLogger.log("gateway", "queue", ip + " " + api);
        }
        return allowed;
    }
}
