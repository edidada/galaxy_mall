package cn.wdidada.galaxymall.payment;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.StockRestockCompensateRequest;
import cn.wdidada.galaxymall.common.api.StockRestockRequest;
import cn.wdidada.galaxymall.common.api.StockView;
import cn.wdidada.galaxymall.common.client.StockClient;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextParameter;
import org.apache.seata.saga.rm.api.CompensationBusinessAction;

final class SeataSagaStockRestoreAction {

    private final StockClient stockClient;
    private final MetricsCollector metricsCollector;

    SeataSagaStockRestoreAction(StockClient stockClient, MetricsCollector metricsCollector) {
        this.stockClient = stockClient;
        this.metricsCollector = metricsCollector;
    }

    @CompensationBusinessAction(name = "galaxyStockRestoreSagaAction", compensationMethod = "compensate")
    public boolean execute(
            BusinessActionContext actionContext,
            @BusinessActionContextParameter(paramName = "refundId") String refundId,
            @BusinessActionContextParameter(paramName = "productId") String productId,
            @BusinessActionContextParameter(paramName = "quantity") int quantity
    ) {
        ApiResponse<StockView> response = stockClient.restock(new StockRestockRequest(refundId, productId, quantity, "saga-refund"));
        if (!response.success()) {
            metricsCollector.increment("payment.saga.stock.execute.reject");
            throw new IllegalStateException(response.message());
        }
        metricsCollector.increment("payment.saga.stock.execute");
        return true;
    }

    public boolean compensate(BusinessActionContext actionContext) {
        String refundId = actionContext.getActionContext("refundId", String.class);
        String productId = actionContext.getActionContext("productId", String.class);
        Integer quantity = actionContext.getActionContext("quantity", Integer.class);
        ApiResponse<StockView> response = stockClient.compensateRestock(
                new StockRestockCompensateRequest(refundId, productId, quantity == null ? 0 : quantity)
        );
        if (!response.success()) {
            metricsCollector.increment("payment.saga.stock.compensate.reject");
            throw new IllegalStateException(response.message());
        }
        metricsCollector.increment("payment.saga.stock.compensate");
        return true;
    }
}
