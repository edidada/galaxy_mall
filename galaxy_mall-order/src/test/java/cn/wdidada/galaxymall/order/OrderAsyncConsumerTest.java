package cn.wdidada.galaxymall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.ProductView;
import cn.wdidada.galaxymall.common.api.StockConfirmRequest;
import cn.wdidada.galaxymall.common.api.StockDeductRequest;
import cn.wdidada.galaxymall.common.api.StockReleaseRequest;
import cn.wdidada.galaxymall.common.api.StockRestockCompensateRequest;
import cn.wdidada.galaxymall.common.api.StockRestockRequest;
import cn.wdidada.galaxymall.common.api.StockView;
import cn.wdidada.galaxymall.common.client.ProductClient;
import cn.wdidada.galaxymall.common.client.StockClient;
import cn.wdidada.galaxymall.common.domain.Order;
import cn.wdidada.galaxymall.common.domain.OrderStatus;
import cn.wdidada.galaxymall.db.LocalBinlogRepository;
import cn.wdidada.galaxymall.db.OrderRepository;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OrderAsyncConsumerTest {

    @Test
    void handleMovesOverRetriedOrderToDeadLetter() {
        OrderRepository orderStore = new OrderRepository();
        Order order = new Order("order-dead-letter", "u1", "sku-1", 1, 100, false);
        order.resetAsyncAttempts(2);
        orderStore.save(order);

        LocalBinlogRepository binlog = new LocalBinlogRepository();
        MetricsCollector metrics = new MetricsCollector();
        OrderAsyncConsumer consumer = new OrderAsyncConsumer(
                orderStore,
                binlog,
                productClientSuccess(),
                stockClientSuccess(),
                metrics,
                new TraceLogger()
        );

        consumer.handle(new OrderSubmittedEvent(order.orderId()));

        assertEquals(OrderStatus.FAILED, orderStore.findById(order.orderId()).orElseThrow().status());
        assertEquals(1L, metrics.counter("order.async.deadletter"));
        assertTrue(binlog.snapshot().stream().anyMatch(event -> event.startsWith("ORDER_ASYNC_DEAD_LETTER|" + order.orderId())));
    }

    @Test
    void handleRecordsReleaseCompensationWhenReleaseFails() {
        OrderRepository orderStore = new OrderRepository();
        Order order = new Order("order-release-retry", "u1", "sku-1", 1, 100, false);
        orderStore.save(order);

        LocalBinlogRepository binlog = new LocalBinlogRepository();
        MetricsCollector metrics = new MetricsCollector();
        OrderAsyncConsumer consumer = new OrderAsyncConsumer(
                orderStore,
                binlog,
                productClientSuccess(),
                stockClientConfirmFailsAndReleaseFails(),
                metrics,
                new TraceLogger()
        );

        consumer.handle(new OrderSubmittedEvent(order.orderId()));

        assertEquals(OrderStatus.FAILED, orderStore.findById(order.orderId()).orElseThrow().status());
        assertEquals(1L, metrics.counter("order.stock.release.retry"));
        assertTrue(binlog.snapshot().stream().anyMatch(event -> event.startsWith("ORDER_STOCK_RELEASE_RETRY|" + order.orderId())));
    }

    @Test
    void releaseCompensationTaskConsumesRetryEventAfterStockRecovery() {
        LocalBinlogRepository binlog = new LocalBinlogRepository();
        binlog.append("ORDER_STOCK_RELEASE_RETRY|order-release-retry|release-failed");
        MetricsCollector metrics = new MetricsCollector();
        try (OrderStockReleaseCompensationTask task = new OrderStockReleaseCompensationTask(
                binlog,
                stockClientReleaseOnlySuccess(),
                metrics,
                Duration.ofMinutes(1)
        )) {
            task.runOnce();
        }

        assertTrue(binlog.snapshot().isEmpty());
        assertEquals(1L, metrics.counter("order.stock.release.compensation.success"));
    }

    private static ProductClient productClientSuccess() {
        return productId -> ApiResponse.success(
                "ok",
                new ProductView(productId, "demo", 100, false, "<html></html>")
        );
    }

    private static StockClient stockClientSuccess() {
        return new StockClient() {
            @Override
            public ApiResponse<StockView> reserve(StockDeductRequest request) {
                return ApiResponse.success("reserved", new StockView(request.reservationId(), request.productId(), request.quantity(), 9, "RESERVED"));
            }

            @Override
            public ApiResponse<StockView> confirm(StockConfirmRequest request) {
                return ApiResponse.success("confirmed", new StockView(request.reservationId(), "sku-1", 1, 9, "CONFIRMED"));
            }

            @Override
            public ApiResponse<StockView> release(StockReleaseRequest request) {
                return ApiResponse.success("released", new StockView(request.reservationId(), "sku-1", 1, 10, "RELEASED"));
            }

            @Override
            public ApiResponse<StockView> restock(StockRestockRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ApiResponse<StockView> compensateRestock(StockRestockCompensateRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static StockClient stockClientConfirmFailsAndReleaseFails() {
        return new StockClient() {
            @Override
            public ApiResponse<StockView> reserve(StockDeductRequest request) {
                return ApiResponse.success("reserved", new StockView(request.reservationId(), request.productId(), request.quantity(), 9, "RESERVED"));
            }

            @Override
            public ApiResponse<StockView> confirm(StockConfirmRequest request) {
                return ApiResponse.failure("confirm-failed");
            }

            @Override
            public ApiResponse<StockView> release(StockReleaseRequest request) {
                return ApiResponse.failure("release-failed");
            }

            @Override
            public ApiResponse<StockView> restock(StockRestockRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ApiResponse<StockView> compensateRestock(StockRestockCompensateRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static StockClient stockClientReleaseOnlySuccess() {
        return new StockClient() {
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
                return ApiResponse.success("released", new StockView(request.reservationId(), "sku-1", 1, 10, "RELEASED"));
            }

            @Override
            public ApiResponse<StockView> restock(StockRestockRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ApiResponse<StockView> compensateRestock(StockRestockCompensateRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
