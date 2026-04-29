package cn.wdidada.galaxymall.seckill;

import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.domain.Order;
import cn.wdidada.galaxymall.common.domain.Product;
import cn.wdidada.galaxymall.common.domain.User;
import cn.wdidada.galaxymall.gateway.WindowRateLimiter;
import cn.wdidada.galaxymall.order.OrderService;

public final class SeckillService {

    private final WindowRateLimiter userLimiter = new WindowRateLimiter(1);
    private final WindowRateLimiter ipLimiter = new WindowRateLimiter(2);
    private final OrderService orderService;

    public SeckillService(OrderService orderService) {
        this.orderService = orderService;
    }

    public OperationResult<Order> attempt(User user, Product product, int quantity, String ip) {
        if (!product.seckillProduct()) {
            return OperationResult.failure("not-seckill-product");
        }
        if (!userLimiter.allow(user.userId()) || !ipLimiter.allow(ip)) {
            return OperationResult.failure("seckill-throttled");
        }
        return orderService.placeOrder(user, product, quantity, true);
    }
}
