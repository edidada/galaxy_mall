package cn.wdidada.galaxymall.common.client;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.PaymentView;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@FeignClient(name = "payment-service", url = "${services.payment.url}")
public interface PaymentClient {

    @PostMapping("/api/payments/{orderId}")
    ApiResponse<PaymentView> pay(@PathVariable("orderId") String orderId);
}
