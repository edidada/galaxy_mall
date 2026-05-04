package cn.wdidada.galaxymall.seckill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.CreateOrderRequest;
import cn.wdidada.galaxymall.common.api.OrderView;
import cn.wdidada.galaxymall.common.api.ProductView;
import cn.wdidada.galaxymall.common.api.SeckillRequest;
import cn.wdidada.galaxymall.common.api.SeckillView;
import cn.wdidada.galaxymall.common.client.OrderClient;
import cn.wdidada.galaxymall.common.client.ProductClient;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SeckillServiceTest {

    @Test
    void submitReusesActiveRequestAndEventuallyReportsSuccess() throws Exception {
        FakeOrderClient orderClient = new FakeOrderClient();
        try (SeckillService service = new SeckillService(
                orderClient,
                productClient(Map.of(
                        "sku-seckill", new ProductView("sku-seckill", "flash sale", 19900, true, "<html></html>")
                )),
                new MetricsCollector(),
                new TraceLogger(),
                100,
                1,
                10,
                1,
                8,
                Duration.ofMillis(20),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)
        )) {
            ApiResponse<SeckillView> first = service.submit(new SeckillRequest("u1", "sku-seckill", 1, "127.0.0.1"));
            ApiResponse<SeckillView> second = service.submit(new SeckillRequest("u1", "sku-seckill", 1, "127.0.0.1"));

            assertTrue(first.success());
            assertTrue(second.success());
            assertEquals(first.data().requestId(), second.data().requestId());
            assertEquals("seckill-request-reused", second.message());

            SeckillView terminal = waitUntilTerminal(service, first.data().requestId());
            assertEquals("SUCCESS", terminal.status());
            assertEquals("WAITING_PAYMENT", terminal.orderStatus());
            assertEquals("ORD-1", terminal.orderId());
            assertEquals(1, orderClient.createCalls());
        }
    }

    @Test
    void submitRejectsNonSeckillProduct() {
        try (SeckillService service = new SeckillService(
                new FakeOrderClient(),
                productClient(Map.of(
                        "sku-normal", new ProductView("sku-normal", "normal", 100, false, "<html></html>")
                )),
                new MetricsCollector(),
                new TraceLogger(),
                100,
                1,
                10,
                1,
                8,
                Duration.ofMillis(20),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)
        )) {
            ApiResponse<SeckillView> response = service.submit(new SeckillRequest("u1", "sku-normal", 1, "127.0.0.1"));
            assertFalse(response.success());
            assertEquals("product-not-seckill", response.message());
        }
    }

    @Test
    void submitAppliesPerUserRateLimitToDistinctRequests() {
        try (SeckillService service = new SeckillService(
                new FakeOrderClient(),
                productClient(Map.of(
                        "sku-a", new ProductView("sku-a", "A", 100, true, "<html></html>"),
                        "sku-b", new ProductView("sku-b", "B", 100, true, "<html></html>")
                )),
                new MetricsCollector(),
                new TraceLogger(),
                100,
                1,
                10,
                1,
                8,
                Duration.ofMillis(20),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5)
        )) {
            ApiResponse<SeckillView> first = service.submit(new SeckillRequest("u1", "sku-a", 1, "127.0.0.1"));
            ApiResponse<SeckillView> second = service.submit(new SeckillRequest("u1", "sku-b", 1, "127.0.0.1"));

            assertTrue(first.success());
            assertFalse(second.success());
            assertEquals("seckill-user-rate-limited", second.message());
        }
    }

    private static ProductClient productClient(Map<String, ProductView> products) {
        return productId -> {
            ProductView product = products.get(productId);
            return product == null ? ApiResponse.failure("product-not-found") : ApiResponse.success("ok", product);
        };
    }

    private static SeckillView waitUntilTerminal(SeckillService service, String requestId) throws Exception {
        for (int i = 0; i < 40; i++) {
            ApiResponse<SeckillView> response = service.getRequest(requestId);
            if (response.success() && response.data() != null && "SUCCESS".equals(response.data().status())) {
                return response.data();
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("seckill request did not reach terminal state");
    }

    private static final class FakeOrderClient implements OrderClient {

        private final AtomicInteger createCalls = new AtomicInteger();
        private final Map<String, String> orderStatuses = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> refreshCalls = new ConcurrentHashMap<>();

        @Override
        public ApiResponse<OrderView> createOrder(CreateOrderRequest request) {
            createCalls.incrementAndGet();
            orderStatuses.put("ORD-1", "SUBMITTED");
            refreshCalls.put("ORD-1", new AtomicInteger());
            return ApiResponse.success(
                    "order-submitted",
                    new OrderView("ORD-1", request.userId(), request.productId(), request.quantity(), 19900, "SUBMITTED", null, System.currentTimeMillis())
            );
        }

        @Override
        public ApiResponse<OrderView> getOrder(String orderId) {
            String status = orderStatuses.get(orderId);
            if (status == null) {
                return ApiResponse.failure("order-not-found");
            }
            AtomicInteger seen = refreshCalls.computeIfAbsent(orderId, ignored -> new AtomicInteger());
            if (seen.incrementAndGet() >= 2) {
                status = "WAITING_PAYMENT";
                orderStatuses.put(orderId, status);
            }
            return ApiResponse.success(
                    "order-found",
                    new OrderView(orderId, "u1", "sku-seckill", 1, 19900, status, null, System.currentTimeMillis())
            );
        }

        @Override
        public ApiResponse<OrderView> markPaid(String orderId) {
            orderStatuses.put(orderId, "PAID");
            return ApiResponse.success(
                    "order-paid",
                    new OrderView(orderId, "u1", "sku-seckill", 1, 19900, "PAID", null, System.currentTimeMillis())
            );
        }

        int createCalls() {
            return createCalls.get();
        }
    }
}
