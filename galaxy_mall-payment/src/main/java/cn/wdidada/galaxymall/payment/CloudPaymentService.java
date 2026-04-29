package cn.wdidada.galaxymall.payment;

import cn.wdidada.galaxymall.common.IdGenerator;
import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.OrderView;
import cn.wdidada.galaxymall.common.api.PaymentView;
import cn.wdidada.galaxymall.common.client.OrderClient;
import cn.wdidada.galaxymall.monitor.MetricsCollector;

public final class CloudPaymentService {

    private final OrderClient orderClient;
    private final MetricsCollector metricsCollector;

    public CloudPaymentService(OrderClient orderClient, MetricsCollector metricsCollector) {
        this.orderClient = orderClient;
        this.metricsCollector = metricsCollector;
    }

    public ApiResponse<PaymentView> pay(String orderId) {
        ApiResponse<OrderView> orderResponse = orderClient.getOrder(orderId);
        if (!orderResponse.success() || orderResponse.data() == null) {
            return ApiResponse.failure("order-not-found");
        }
        if (!"WAITING_PAYMENT".equals(orderResponse.data().status())) {
            return ApiResponse.failure("order-not-payable");
        }
        ApiResponse<OrderView> paidResponse = orderClient.markPaid(orderId);
        if (!paidResponse.success()) {
            return ApiResponse.failure(paidResponse.message());
        }
        metricsCollector.increment("payment.success");
        return ApiResponse.success(
                "payment-success",
                new PaymentView(IdGenerator.nextOrderId(), orderId, "SUCCESS", "payment callback completed")
        );
    }
}
