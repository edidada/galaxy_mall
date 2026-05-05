package cn.wdidada.galaxymall.payment;

import cn.wdidada.galaxymall.monitor.MetricsCollector;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextParameter;
import org.apache.seata.saga.rm.api.CompensationBusinessAction;

final class SeataSagaPaymentRefundAction {

    private final RefundRecordRepository refundRecordRepository;
    private final MetricsCollector metricsCollector;

    SeataSagaPaymentRefundAction(RefundRecordRepository refundRecordRepository, MetricsCollector metricsCollector) {
        this.refundRecordRepository = refundRecordRepository;
        this.metricsCollector = metricsCollector;
    }

    @CompensationBusinessAction(name = "galaxyPaymentRefundSagaAction", compensationMethod = "compensate")
    public boolean execute(
            BusinessActionContext actionContext,
            @BusinessActionContextParameter(paramName = "refundId") String refundId,
            @BusinessActionContextParameter(paramName = "orderId") String orderId,
            @BusinessActionContextParameter(paramName = "amountCent") int amountCent
    ) {
        refundRecordRepository.saveInitiated(refundId, orderId, amountCent);
        refundRecordRepository.markCompleted(refundId);
        metricsCollector.increment("payment.saga.refund.execute");
        return true;
    }

    public boolean compensate(BusinessActionContext actionContext) {
        String refundId = actionContext.getActionContext("refundId", String.class);
        refundRecordRepository.markCompensated(refundId);
        metricsCollector.increment("payment.saga.refund.compensate");
        return true;
    }
}
