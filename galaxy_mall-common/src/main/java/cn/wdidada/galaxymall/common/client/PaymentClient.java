package cn.wdidada.galaxymall.common.client;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.PaymentView;
public interface PaymentClient {

    ApiResponse<PaymentView> pay(String orderId);
}
