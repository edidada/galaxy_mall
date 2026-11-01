package cn.wdidada.galaxymall.payment;

import cn.wdidada.galaxymall.common.IdGenerator;
import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.OrderView;
import cn.wdidada.galaxymall.common.api.PaymentView;
import cn.wdidada.galaxymall.common.client.OrderClient;
import cn.wdidada.galaxymall.db.JdbcPaymentStore;
import cn.wdidada.galaxymall.monitor.MetricsCollector;

public final class CloudPaymentService {

    private final OrderClient orderClient;
    private final MetricsCollector metricsCollector;
    private final JdbcPaymentStore paymentStore;   // null = local 后端（与历史行为一致，不留流水）

    public CloudPaymentService(OrderClient orderClient, MetricsCollector metricsCollector) {
        this(orderClient, metricsCollector, null);
    }

    public CloudPaymentService(OrderClient orderClient, MetricsCollector metricsCollector, JdbcPaymentStore paymentStore) {
        this.orderClient = orderClient;
        this.metricsCollector = metricsCollector;
        this.paymentStore = paymentStore;
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
        PaymentView view = new PaymentView(IdGenerator.nextOrderId(), orderId, "SUCCESS", "payment callback completed");
        recordPayment(view, orderResponse.data().amountCent());
        metricsCollector.increment("payment.success");
        return ApiResponse.success("payment-success", view);
    }

    /**
     * 流水落库是旁路：写库失败绝不阻断支付（走到这里订单已被置为 PAID），
     * 只记 payment.store.write-failed 计数供监控告警。
     */
    private void recordPayment(PaymentView view, int amountCent) {
        if (paymentStore == null) {
            return;
        }
        try {
            paymentStore.record(view.paymentId(), view.orderId(), amountCent, view.status(), view.message());
            metricsCollector.increment("payment.store.record");
        } catch (RuntimeException writeFailure) {
            metricsCollector.increment("payment.store.write-failed");
        }
    }
}
