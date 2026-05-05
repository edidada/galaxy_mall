package cn.wdidada.galaxymall.payment;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.PaymentView;
import cn.wdidada.galaxymall.common.api.RefundView;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final CloudPaymentService cloudPaymentService;
    private final SeataSagaRefundService seataSagaRefundService;

    public PaymentController(CloudPaymentService cloudPaymentService, SeataSagaRefundService seataSagaRefundService) {
        this.cloudPaymentService = cloudPaymentService;
        this.seataSagaRefundService = seataSagaRefundService;
    }

    @PostMapping("/{orderId}")
    public ApiResponse<PaymentView> pay(@PathVariable String orderId) {
        return cloudPaymentService.pay(orderId);
    }

    @PostMapping("/{orderId}/refund")
    public ApiResponse<RefundView> refund(@PathVariable String orderId) {
        return seataSagaRefundService.refund(orderId);
    }
}
