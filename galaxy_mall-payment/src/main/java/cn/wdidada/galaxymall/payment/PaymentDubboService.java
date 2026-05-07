package cn.wdidada.galaxymall.payment;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.PaymentView;
import cn.wdidada.galaxymall.common.client.PaymentClient;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class PaymentDubboService implements PaymentClient {

    private final CloudPaymentService cloudPaymentService;

    public PaymentDubboService(CloudPaymentService cloudPaymentService) {
        this.cloudPaymentService = cloudPaymentService;
    }

    @Override
    public ApiResponse<PaymentView> pay(String orderId) {
        return cloudPaymentService.pay(orderId);
    }
}
