package cn.wdidada.galaxymall.common.client;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.CreateOrderRequest;
import cn.wdidada.galaxymall.common.api.OrderView;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "order-service", url = "${services.order.url}")
public interface OrderClient {

    @PostMapping("/api/orders")
    ApiResponse<OrderView> createOrder(@RequestBody CreateOrderRequest request);

    @GetMapping("/api/orders/{orderId}")
    ApiResponse<OrderView> getOrder(@PathVariable("orderId") String orderId);

    @PostMapping("/api/orders/{orderId}/paid")
    ApiResponse<OrderView> markPaid(@PathVariable("orderId") String orderId);

    @PostMapping("/api/orders/{orderId}/refunded")
    ApiResponse<OrderView> markRefunded(@PathVariable("orderId") String orderId);

    @PostMapping("/api/orders/{orderId}/refund-rollback")
    ApiResponse<OrderView> rollbackRefund(@PathVariable("orderId") String orderId);
}
