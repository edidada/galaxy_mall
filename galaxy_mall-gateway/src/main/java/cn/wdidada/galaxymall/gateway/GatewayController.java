package cn.wdidada.galaxymall.gateway;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.GatewayOrderRequest;
import cn.wdidada.galaxymall.common.api.OrderView;
import cn.wdidada.galaxymall.common.api.PaymentView;
import cn.wdidada.galaxymall.common.api.ProductView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gateway")
public class GatewayController {

    private final GatewayWorkflowService gatewayWorkflowService;

    public GatewayController(GatewayWorkflowService gatewayWorkflowService) {
        this.gatewayWorkflowService = gatewayWorkflowService;
    }

    @GetMapping("/products/{productId}")
    public ApiResponse<ProductView> queryProduct(
            @PathVariable String productId,
            @RequestParam String userId,
            @RequestParam String clientIp
    ) {
        return gatewayWorkflowService.queryProduct(userId, productId, clientIp);
    }

    @PostMapping("/orders")
    public ApiResponse<OrderView> placeOrder(@Valid @RequestBody GatewayOrderRequest request) {
        return gatewayWorkflowService.placeOrder(request);
    }

    @PostMapping("/payments/{orderId}")
    public ApiResponse<PaymentView> pay(@PathVariable String orderId) {
        return gatewayWorkflowService.pay(orderId);
    }
}
