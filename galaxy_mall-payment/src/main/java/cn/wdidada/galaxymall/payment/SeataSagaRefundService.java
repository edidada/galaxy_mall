package cn.wdidada.galaxymall.payment;

import cn.wdidada.galaxymall.common.IdGenerator;
import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.OrderView;
import cn.wdidada.galaxymall.common.api.RefundView;
import cn.wdidada.galaxymall.common.client.OrderClient;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import java.util.Map;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.spring.annotation.GlobalTransactional;

public final class SeataSagaRefundService {

    private final OrderClient orderClient;
    private final SeataSagaPaymentRefundAction paymentRefundAction;
    private final SeataSagaStockRestoreAction stockRestoreAction;
    private final SeataSagaOrderRefundAction orderRefundAction;
    private final MetricsCollector metricsCollector;

    public SeataSagaRefundService(
            OrderClient orderClient,
            SeataSagaPaymentRefundAction paymentRefundAction,
            SeataSagaStockRestoreAction stockRestoreAction,
            SeataSagaOrderRefundAction orderRefundAction,
            MetricsCollector metricsCollector
    ) {
        this.orderClient = orderClient;
        this.paymentRefundAction = paymentRefundAction;
        this.stockRestoreAction = stockRestoreAction;
        this.orderRefundAction = orderRefundAction;
        this.metricsCollector = metricsCollector;
    }

    @GlobalTransactional(name = "galaxy-payment-refund-saga", rollbackFor = Exception.class)
    public ApiResponse<RefundView> refund(String orderId) {
        ApiResponse<OrderView> orderResponse = orderClient.getOrder(orderId);
        if (!orderResponse.success() || orderResponse.data() == null) {
            return ApiResponse.failure("order-not-found");
        }
        OrderView order = orderResponse.data();
        if (!"PAID".equals(order.status())) {
            return ApiResponse.failure("order-not-refundable");
        }

        String refundId = IdGenerator.nextBusinessId("RFD");
        BusinessActionContext context = context(
                "galaxy-payment-refund-saga",
                Map.of(
                        "refundId", refundId,
                        "orderId", orderId,
                        "productId", order.productId(),
                        "quantity", order.quantity(),
                        "amountCent", order.amountCent()
                )
        );

        boolean paymentExecuted = false;
        boolean stockExecuted = false;
        try {
            paymentExecuted = paymentRefundAction.execute(context, refundId, orderId, order.amountCent());
            stockExecuted = stockRestoreAction.execute(context, refundId, order.productId(), order.quantity());
            orderRefundAction.execute(context, orderId);
            metricsCollector.increment("payment.saga.refund.success");
            return ApiResponse.success(
                    "refund-success",
                    new RefundView(refundId, orderId, "SUCCESS", "refund saga committed")
            );
        } catch (RuntimeException ex) {
            if (stockExecuted) {
                compensateQuietly(() -> stockRestoreAction.compensate(context));
            }
            if (paymentExecuted) {
                compensateQuietly(() -> paymentRefundAction.compensate(context));
            }
            metricsCollector.increment("payment.saga.refund.failed");
            return ApiResponse.failure(sanitize(ex.getMessage()));
        }
    }

    private BusinessActionContext context(String actionName, Map<String, Object> values) {
        return new BusinessActionContext("manual-xid", actionName, values);
    }

    private void compensateQuietly(Runnable compensation) {
        try {
            compensation.run();
        } catch (RuntimeException ignored) {
            metricsCollector.increment("payment.saga.compensation.error");
        }
    }

    private String sanitize(String message) {
        return message == null ? "unknown" : message.replace('|', '/');
    }
}
