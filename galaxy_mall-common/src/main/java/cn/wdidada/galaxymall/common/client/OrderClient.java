package cn.wdidada.galaxymall.common.client;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.CreateOrderRequest;
import cn.wdidada.galaxymall.common.api.OrderView;
public interface OrderClient {

    ApiResponse<OrderView> createOrder(CreateOrderRequest request);

    ApiResponse<OrderView> getOrder(String orderId);

    ApiResponse<OrderView> markPaid(String orderId);

    ApiResponse<OrderView> markRefunded(String orderId);

    ApiResponse<OrderView> rollbackRefund(String orderId);
}
