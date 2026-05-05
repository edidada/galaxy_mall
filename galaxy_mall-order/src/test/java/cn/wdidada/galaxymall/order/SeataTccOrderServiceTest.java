package cn.wdidada.galaxymall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.CreateOrderRequest;
import cn.wdidada.galaxymall.common.api.OrderView;
import cn.wdidada.galaxymall.common.api.ProductView;
import cn.wdidada.galaxymall.common.api.StockConfirmRequest;
import cn.wdidada.galaxymall.common.api.StockDeductRequest;
import cn.wdidada.galaxymall.common.api.StockReleaseRequest;
import cn.wdidada.galaxymall.common.api.StockRestockCompensateRequest;
import cn.wdidada.galaxymall.common.api.StockRestockRequest;
import cn.wdidada.galaxymall.common.api.StockView;
import cn.wdidada.galaxymall.common.client.ProductClient;
import cn.wdidada.galaxymall.common.client.StockClient;
import cn.wdidada.galaxymall.common.domain.OrderStatus;
import cn.wdidada.galaxymall.db.LocalBinlogRepository;
import cn.wdidada.galaxymall.db.OrderRepository;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;
import org.junit.jupiter.api.Test;

class SeataTccOrderServiceTest {

    @Test
    void createOrderConfirmsOrderWhenTryAndConfirmSucceed() {
        OrderRepository orderStore = new OrderRepository();
        LocalBinlogRepository binlog = new LocalBinlogRepository();
        MetricsCollector metrics = new MetricsCollector();
        FakeStockClient stockClient = new FakeStockClient();
        SeataTccOrderService service = new SeataTccOrderService(
                orderStore,
                binlog,
                productClientSuccess(),
                stockClient,
                metrics,
                new TraceLogger()
        );

        ApiResponse<OrderView> response = service.createOrder(new CreateOrderRequest("u1", "sku-1", 1));

        assertTrue(response.success());
        assertEquals("order-tcc-confirmed", response.message());
        assertEquals("WAITING_PAYMENT", response.data().status());
        assertEquals(OrderStatus.WAITING_PAYMENT, orderStore.findById(response.data().orderId()).orElseThrow().status());
        assertEquals(1, stockClient.reserveCalls);
        assertEquals(1, stockClient.confirmCalls);
        assertEquals(0, stockClient.releaseCalls);
        assertEquals(1L, metrics.counter("order.tcc.success"));
        assertTrue(binlog.snapshot().stream().anyMatch(event -> event.startsWith("ORDER_TCC_TRY|" + response.data().orderId())));
        assertTrue(binlog.snapshot().stream().anyMatch(event -> event.equals("ORDER_TCC_CONFIRM|" + response.data().orderId())));
    }

    @Test
    void createOrderCancelsOrderWhenTryFails() {
        OrderRepository orderStore = new OrderRepository();
        LocalBinlogRepository binlog = new LocalBinlogRepository();
        MetricsCollector metrics = new MetricsCollector();
        FakeStockClient stockClient = new FakeStockClient();
        stockClient.reserveResponse = ApiResponse.failure("stock-not-enough");
        SeataTccOrderService service = new SeataTccOrderService(
                orderStore,
                binlog,
                productClientSuccess(),
                stockClient,
                metrics,
                new TraceLogger()
        );

        ApiResponse<OrderView> response = service.createOrder(new CreateOrderRequest("u1", "sku-1", 1));

        assertFalse(response.success());
        assertEquals("stock-not-enough", response.message());
        String orderId = binlog.snapshot().stream()
                .filter(event -> event.startsWith("ORDER_TCC_TRY|"))
                .map(event -> event.split("\\|")[1])
                .findFirst()
                .orElseThrow();
        assertEquals(OrderStatus.FAILED, orderStore.findById(orderId).orElseThrow().status());
        assertEquals(1, stockClient.reserveCalls);
        assertEquals(0, stockClient.confirmCalls);
        assertEquals(0, stockClient.releaseCalls);
        assertEquals(1L, metrics.counter("order.tcc.try.reject"));
        assertEquals(1L, metrics.counter("order.tcc.cancel"));
        assertTrue(binlog.snapshot().stream().anyMatch(event -> event.startsWith("ORDER_TCC_CANCEL|" + orderId + "|stock-not-enough")));
    }

    @Test
    void createOrderReleasesStockWhenConfirmFails() {
        OrderRepository orderStore = new OrderRepository();
        LocalBinlogRepository binlog = new LocalBinlogRepository();
        MetricsCollector metrics = new MetricsCollector();
        FakeStockClient stockClient = new FakeStockClient();
        stockClient.confirmResponse = ApiResponse.failure("db-confirm-failed");
        SeataTccOrderService service = new SeataTccOrderService(
                orderStore,
                binlog,
                productClientSuccess(),
                stockClient,
                metrics,
                new TraceLogger()
        );

        ApiResponse<OrderView> response = service.createOrder(new CreateOrderRequest("u1", "sku-1", 1));

        assertFalse(response.success());
        assertEquals("db-confirm-failed", response.message());
        String orderId = binlog.snapshot().stream()
                .filter(event -> event.startsWith("ORDER_TCC_TRY|"))
                .map(event -> event.split("\\|")[1])
                .findFirst()
                .orElseThrow();
        assertEquals(OrderStatus.FAILED, orderStore.findById(orderId).orElseThrow().status());
        assertEquals(1, stockClient.reserveCalls);
        assertEquals(1, stockClient.confirmCalls);
        assertEquals(1, stockClient.releaseCalls);
        assertEquals(1L, metrics.counter("order.tcc.confirm.reject"));
        assertEquals(1L, metrics.counter("order.tcc.cancel"));
        assertTrue(binlog.snapshot().stream().anyMatch(event -> event.startsWith("ORDER_TCC_CANCEL|" + orderId + "|db-confirm-failed")));
    }

    private static ProductClient productClientSuccess() {
        return productId -> ApiResponse.success(
                "ok",
                new ProductView(productId, "demo", 100, false, "<html></html>")
        );
    }

    private static final class FakeStockClient implements StockClient {

        private ApiResponse<StockView> reserveResponse =
                ApiResponse.success("reserved", new StockView("reservation", "sku-1", 1, 9, "RESERVED"));
        private ApiResponse<StockView> confirmResponse =
                ApiResponse.success("confirmed", new StockView("reservation", "sku-1", 1, 9, "CONFIRMED"));
        private ApiResponse<StockView> releaseResponse =
                ApiResponse.success("released", new StockView("reservation", "sku-1", 1, 10, "RELEASED"));
        private int reserveCalls;
        private int confirmCalls;
        private int releaseCalls;

        @Override
        public ApiResponse<StockView> reserve(StockDeductRequest request) {
            reserveCalls++;
            return reserveResponse;
        }

        @Override
        public ApiResponse<StockView> confirm(StockConfirmRequest request) {
            confirmCalls++;
            return confirmResponse;
        }

        @Override
        public ApiResponse<StockView> release(StockReleaseRequest request) {
            releaseCalls++;
            return releaseResponse;
        }

        @Override
        public ApiResponse<StockView> restock(StockRestockRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ApiResponse<StockView> compensateRestock(StockRestockCompensateRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
