package cn.wdidada.galaxymall.order;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.CreateOrderRequest;
import cn.wdidada.galaxymall.common.api.OrderView;
import cn.wdidada.galaxymall.common.client.OrderClient;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class OrderDubboService implements OrderClient {

    private final CloudOrderService cloudOrderService;

    public OrderDubboService(CloudOrderService cloudOrderService) {
        this.cloudOrderService = cloudOrderService;
    }

    @Override
    public ApiResponse<OrderView> createOrder(CreateOrderRequest request) {
        return cloudOrderService.createOrder(request);
    }

    @Override
    public ApiResponse<OrderView> getOrder(String orderId) {
        return cloudOrderService.getOrder(orderId);
    }

    @Override
    public ApiResponse<OrderView> markPaid(String orderId) {
        return cloudOrderService.markPaid(orderId);
    }

    @Override
    public ApiResponse<OrderView> markRefunded(String orderId) {
        return cloudOrderService.markRefunded(orderId);
    }

    @Override
    public ApiResponse<OrderView> rollbackRefund(String orderId) {
        return cloudOrderService.rollbackRefund(orderId);
    }
}
