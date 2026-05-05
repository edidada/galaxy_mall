package cn.wdidada.galaxymall.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.OrderView;
import cn.wdidada.galaxymall.common.api.RefundView;
import cn.wdidada.galaxymall.common.api.StockConfirmRequest;
import cn.wdidada.galaxymall.common.api.StockDeductRequest;
import cn.wdidada.galaxymall.common.api.StockReleaseRequest;
import cn.wdidada.galaxymall.common.api.StockRestockCompensateRequest;
import cn.wdidada.galaxymall.common.api.StockRestockRequest;
import cn.wdidada.galaxymall.common.api.StockView;
import cn.wdidada.galaxymall.common.client.OrderClient;
import cn.wdidada.galaxymall.common.client.StockClient;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import org.apache.seata.saga.rm.api.CompensationBusinessAction;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.junit.jupiter.api.Test;

class SeataSagaRefundServiceTest {

    @Test
    void refundCommitsAllParticipantsWhenOrderIsPaid() {
        MetricsCollector metrics = new MetricsCollector();
        RefundRecordRepository refundRecordRepository = new RefundRecordRepository();
        FakeOrderClient orderClient = new FakeOrderClient();
        FakeStockClient stockClient = new FakeStockClient();
        SeataSagaRefundService service = service(orderClient, stockClient, refundRecordRepository, metrics);

        ApiResponse<RefundView> response = service.refund("order-1");

        assertTrue(response.success());
        assertEquals("refund-success", response.message());
        assertEquals("REFUNDED", orderClient.currentStatus);
        assertEquals(1, stockClient.restockCalls);
        assertEquals(0, stockClient.compensateRestockCalls);
        assertNotNull(response.data().refundId());
        assertEquals(1L, metrics.counter("payment.saga.refund.success"));
        assertEquals(RefundRecordRepository.RefundStatus.COMPLETED,
                refundRecordRepository.findByRefundId(response.data().refundId()).orElseThrow().status());
    }

    @Test
    void refundCompensatesEarlierStepsWhenOrderStageFails() {
        MetricsCollector metrics = new MetricsCollector();
        RefundRecordRepository refundRecordRepository = new RefundRecordRepository();
        FakeOrderClient orderClient = new FakeOrderClient();
        orderClient.markRefundedResponse = ApiResponse.failure("order-refund-failed");
        FakeStockClient stockClient = new FakeStockClient();
        SeataSagaRefundService service = service(orderClient, stockClient, refundRecordRepository, metrics);

        ApiResponse<RefundView> response = service.refund("order-1");

        assertFalse(response.success());
        assertEquals("order-refund-failed", response.message());
        assertEquals("PAID", orderClient.currentStatus);
        assertEquals(1, stockClient.restockCalls);
        assertEquals(1, stockClient.compensateRestockCalls);
        assertEquals(1L, metrics.counter("payment.saga.refund.failed"));
        assertEquals(1L, metrics.counter("payment.saga.refund.compensate"));
        assertEquals(RefundRecordRepository.RefundStatus.COMPENSATED,
                refundRecordRepository.latest().orElseThrow().status());
    }

    @Test
    void refundActionsCarrySeataAnnotations() throws Exception {
        GlobalTransactional globalTransactional = SeataSagaRefundService.class
                .getMethod("refund", String.class)
                .getAnnotation(GlobalTransactional.class);
        CompensationBusinessAction paymentAnnotation = SeataSagaPaymentRefundAction.class
                .getMethod("execute", org.apache.seata.rm.tcc.api.BusinessActionContext.class, String.class, String.class, int.class)
                .getAnnotation(CompensationBusinessAction.class);

        assertNotNull(globalTransactional);
        assertEquals("galaxy-payment-refund-saga", globalTransactional.name());
        assertNotNull(paymentAnnotation);
        assertEquals("galaxyPaymentRefundSagaAction", paymentAnnotation.name());
    }

    private static SeataSagaRefundService service(
            FakeOrderClient orderClient,
            FakeStockClient stockClient,
            RefundRecordRepository refundRecordRepository,
            MetricsCollector metrics
    ) {
        return new SeataSagaRefundService(
                orderClient,
                new SeataSagaPaymentRefundAction(refundRecordRepository, metrics),
                new SeataSagaStockRestoreAction(stockClient, metrics),
                new SeataSagaOrderRefundAction(orderClient, metrics),
                metrics
        );
    }

    private static final class FakeOrderClient implements OrderClient {

        private String currentStatus = "PAID";
        private ApiResponse<OrderView> markRefundedResponse = null;

        @Override
        public ApiResponse<OrderView> createOrder(cn.wdidada.galaxymall.common.api.CreateOrderRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ApiResponse<OrderView> getOrder(String orderId) {
            return ApiResponse.success(
                    "order-found",
                    new OrderView(orderId, "u1", "sku-1", 1, 100, currentStatus, null, System.currentTimeMillis())
            );
        }

        @Override
        public ApiResponse<OrderView> markPaid(String orderId) {
            currentStatus = "PAID";
            return getOrder(orderId);
        }

        @Override
        public ApiResponse<OrderView> markRefunded(String orderId) {
            if (markRefundedResponse != null) {
                return markRefundedResponse;
            }
            currentStatus = "REFUNDED";
            return getOrder(orderId);
        }

        @Override
        public ApiResponse<OrderView> rollbackRefund(String orderId) {
            currentStatus = "PAID";
            return getOrder(orderId);
        }
    }

    private static final class FakeStockClient implements StockClient {

        private int restockCalls;
        private int compensateRestockCalls;

        @Override
        public ApiResponse<StockView> reserve(StockDeductRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ApiResponse<StockView> confirm(StockConfirmRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ApiResponse<StockView> release(StockReleaseRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ApiResponse<StockView> restock(StockRestockRequest request) {
            restockCalls++;
            return ApiResponse.success("restocked", new StockView(request.adjustmentId(), request.productId(), request.quantity(), 10, "RESTOCKED"));
        }

        @Override
        public ApiResponse<StockView> compensateRestock(StockRestockCompensateRequest request) {
            compensateRestockCalls++;
            return ApiResponse.success("restock-compensated", new StockView(request.adjustmentId(), request.productId(), request.quantity(), 9, "RESTOCK_COMPENSATED"));
        }
    }
}
