package cn.wdidada.galaxymall.payment;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.OrderView;
import cn.wdidada.galaxymall.common.client.OrderClient;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextParameter;
import org.apache.seata.saga.rm.api.CompensationBusinessAction;

final class SeataSagaOrderRefundAction {

    private final OrderClient orderClient;
    private final MetricsCollector metricsCollector;

    SeataSagaOrderRefundAction(OrderClient orderClient, MetricsCollector metricsCollector) {
        this.orderClient = orderClient;
        this.metricsCollector = metricsCollector;
    }

    @CompensationBusinessAction(name = "galaxyOrderRefundSagaAction", compensationMethod = "compensate")
    public boolean execute(
            BusinessActionContext actionContext,
            @BusinessActionContextParameter(paramName = "orderId") String orderId
    ) {
        ApiResponse<OrderView> response = orderClient.markRefunded(orderId);
        if (!response.success()) {
            metricsCollector.increment("payment.saga.order.execute.reject");
            throw new IllegalStateException(response.message());
        }
        metricsCollector.increment("payment.saga.order.execute");
        return true;
    }

    public boolean compensate(BusinessActionContext actionContext) {
        String orderId = actionContext.getActionContext("orderId", String.class);
        ApiResponse<OrderView> response = orderClient.rollbackRefund(orderId);
        if (!response.success()) {
            metricsCollector.increment("payment.saga.order.compensate.reject");
            throw new IllegalStateException(response.message());
        }
        metricsCollector.increment("payment.saga.order.compensate");
        return true;
    }
}
