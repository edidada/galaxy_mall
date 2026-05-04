package cn.wdidada.galaxymall.order;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.CreateOrderRequest;
import cn.wdidada.galaxymall.common.api.OrderView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final CloudOrderService cloudOrderService;
    private final SeataTccOrderService seataTccOrderService;

    public OrderController(CloudOrderService cloudOrderService, SeataTccOrderService seataTccOrderService) {
        this.cloudOrderService = cloudOrderService;
        this.seataTccOrderService = seataTccOrderService;
    }

    @PostMapping
    public ApiResponse<OrderView> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        return cloudOrderService.createOrder(request);
    }

    @PostMapping("/tcc")
    public ApiResponse<OrderView> createOrderByTcc(@Valid @RequestBody CreateOrderRequest request) {
        return seataTccOrderService.createOrder(request);
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderView> getOrder(@PathVariable String orderId) {
        return cloudOrderService.getOrder(orderId);
    }

    @PostMapping("/{orderId}/paid")
    public ApiResponse<OrderView> markPaid(@PathVariable String orderId) {
        return cloudOrderService.markPaid(orderId);
    }
}
