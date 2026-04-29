package cn.wdidada.galaxymall.payment;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.PaymentView;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final CloudPaymentService cloudPaymentService;

    public PaymentController(CloudPaymentService cloudPaymentService) {
        this.cloudPaymentService = cloudPaymentService;
    }

    @PostMapping("/{orderId}")
    public ApiResponse<PaymentView> pay(@PathVariable String orderId) {
        return cloudPaymentService.pay(orderId);
    }
}
