package cn.wdidada.galaxymall.seckill;

import cn.wdidada.galaxymall.common.IdGenerator;
import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.CreateOrderRequest;
import cn.wdidada.galaxymall.common.api.OrderView;
import cn.wdidada.galaxymall.common.api.ProductView;
import cn.wdidada.galaxymall.common.api.SeckillRequest;
import cn.wdidada.galaxymall.common.api.SeckillView;
import cn.wdidada.galaxymall.common.client.OrderClient;
import cn.wdidada.galaxymall.common.client.ProductClient;
import cn.wdidada.galaxymall.common.domain.OrderStatus;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;
import cn.wdidada.galaxymall.stock.LocalRateGuard;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class SeckillService implements AutoCloseable {

    private final OrderClient orderClient;
    private final SeckillProductCatalog productCatalog;
    private final MetricsCollector metricsCollector;
    private final TraceLogger traceLogger;
    private final LocalRateGuard globalRateGuard;
    private final KeyedRequestRateLimiter userRateLimiter;
    private final KeyedRequestRateLimiter ipRateLimiter;
    private final SeckillRequestRepository requestRepository;
    private final java.util.concurrent.ConcurrentHashMap<String, String> activeRequests = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object[] submissionStripes = new Object[64];
    private final ThreadPoolExecutor workerPool;
    private final ScheduledExecutorService refreshExecutor;
    private final long terminalRetentionMillis;

    public SeckillService(
            OrderClient orderClient,
            ProductClient productClient,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger,
            int globalRateLimit,
            int userRateLimit,
            int ipRateLimit,
            int workerCount,
            int queueCapacity,
            Duration refreshInterval,
            Duration productCacheTtl,
            Duration terminalRetention
    ) {
        this.orderClient = orderClient;
        this.productCatalog = new SeckillProductCatalog(productClient, Math.max(100L, productCacheTtl.toMillis()));
        this.metricsCollector = metricsCollector;
        this.traceLogger = traceLogger;
        this.globalRateGuard = new LocalRateGuard(globalRateLimit);
        this.userRateLimiter = new KeyedRequestRateLimiter(userRateLimit);
        this.ipRateLimiter = new KeyedRequestRateLimiter(ipRateLimit);
        this.requestRepository = new SeckillRequestRepository();
        for (int i = 0; i < submissionStripes.length; i++) {
            submissionStripes[i] = new Object();
        }
        this.workerPool = new ThreadPoolExecutor(
                Math.max(1, workerCount),
                Math.max(1, workerCount),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                runnable -> {
                    Thread thread = new Thread(runnable, "galaxy-mall-seckill-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.refreshExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "galaxy-mall-seckill-refresh");
            thread.setDaemon(true);
            return thread;
        });
        this.terminalRetentionMillis = Math.max(1_000L, terminalRetention.toMillis());
        long refreshMillis = Math.max(50L, refreshInterval.toMillis());
        this.refreshExecutor.scheduleWithFixedDelay(this::refreshPendingRequests, refreshMillis, refreshMillis, TimeUnit.MILLISECONDS);
    }

    public ApiResponse<SeckillView> submit(SeckillRequest request) {
        long start = System.currentTimeMillis();
        Optional<ProductView> product = productCatalog.get(request.productId());
        if (product.isEmpty()) {
            metricsCollector.increment("seckill.reject.product-not-found");
            return ApiResponse.failure("product-not-found");
        }
        if (!product.get().seckillProduct()) {
            metricsCollector.increment("seckill.reject.not-seckill");
            return ApiResponse.failure("product-not-seckill");
        }

        String dedupeKey = dedupeKey(request.userId(), request.productId());
        synchronized (submissionStripe(dedupeKey)) {
            while (true) {
                String existingRequestId = activeRequests.get(dedupeKey);
                if (existingRequestId == null) {
                    break;
                }
                Optional<SeckillRequestSnapshot> existing = requestRepository.findById(existingRequestId);
                if (existing.isEmpty() || existing.get().terminal()) {
                    activeRequests.remove(dedupeKey, existingRequestId);
                    continue;
                }
                metricsCollector.increment("seckill.duplicate.reuse");
                return ApiResponse.success("seckill-request-reused", toView(existing.get()));
            }

            if (!globalRateGuard.tryAcquire()) {
                metricsCollector.increment("seckill.reject.global-rate-limit");
                return ApiResponse.failure("seckill-global-rate-limited");
            }
            if (!userRateLimiter.tryAcquire(request.userId())) {
                metricsCollector.increment("seckill.reject.user-rate-limit");
                return ApiResponse.failure("seckill-user-rate-limited");
            }
            if (!ipRateLimiter.tryAcquire(request.clientIp())) {
                metricsCollector.increment("seckill.reject.ip-rate-limit");
                return ApiResponse.failure("seckill-ip-rate-limited");
            }

            String requestId = IdGenerator.nextSeckillRequestId();
            SeckillRequestSnapshot snapshot = new SeckillRequestSnapshot(
                    requestId,
                    null,
                    request.userId(),
                    request.productId(),
                    request.quantity(),
                    request.clientIp(),
                    SeckillStatus.ACCEPTED,
                    null,
                    "seckill-accepted",
                    start,
                    start
            );
            activeRequests.put(dedupeKey, requestId);
            requestRepository.saveAccepted(snapshot);
            try {
                workerPool.execute(() -> processAcceptedRequest(requestId));
            } catch (RejectedExecutionException ex) {
                activeRequests.remove(dedupeKey, requestId);
                requestRepository.markFailed(requestId, "seckill-queue-full");
                metricsCollector.increment("seckill.reject.queue-full");
                return ApiResponse.failure("seckill-queue-full");
            }
            metricsCollector.increment("seckill.accepted");
            metricsCollector.recordLatency("seckill.submit.latency", System.currentTimeMillis() - start);
            traceLogger.log("seckill-service", "accept", requestId + " " + request.userId() + " " + request.productId());
            return ApiResponse.success("seckill-accepted", toView(snapshot));
        }
    }

    public ApiResponse<SeckillView> getRequest(String requestId) {
        Optional<SeckillRequestSnapshot> snapshot = requestRepository.findById(requestId);
        if (snapshot.isEmpty()) {
            return ApiResponse.failure("seckill-request-not-found");
        }
        if (!snapshot.get().terminal() && snapshot.get().orderId() != null) {
            refreshRequest(requestId);
            snapshot = requestRepository.findById(requestId);
        }
        return snapshot
                .map(value -> ApiResponse.success("seckill-request-found", toView(value)))
                .orElseGet(() -> ApiResponse.failure("seckill-request-not-found"));
    }

    @Override
    public void close() {
        refreshExecutor.shutdownNow();
        workerPool.shutdownNow();
    }

    private void processAcceptedRequest(String requestId) {
        long start = System.currentTimeMillis();
        requestRepository.markProcessing(requestId);
        Optional<SeckillRequestSnapshot> snapshot = requestRepository.findById(requestId);
        if (snapshot.isEmpty()) {
            return;
        }
        try {
            ApiResponse<OrderView> orderResponse = orderClient.createOrder(
                    new CreateOrderRequest(snapshot.get().userId(), snapshot.get().productId(), snapshot.get().quantity())
            );
            if (!orderResponse.success() || orderResponse.data() == null) {
                requestRepository.markFailed(requestId, orderResponse.message());
                clearActive(snapshot.get());
                metricsCollector.increment("seckill.order-submit.failed");
                traceLogger.log("seckill-service", "submit-failed", requestId + " " + orderResponse.message());
                return;
            }
            OrderView order = orderResponse.data();
            requestRepository.markSubmitted(requestId, order.orderId(), order.status(), orderResponse.message());
            metricsCollector.increment("seckill.order-submit.success");
            traceLogger.log("seckill-service", "submitted", requestId + " -> " + order.orderId());
            refreshRequest(requestId);
        } catch (RuntimeException ex) {
            requestRepository.markFailed(requestId, "order-service-exception");
            clearActive(snapshot.get());
            metricsCollector.increment("seckill.order-submit.exception");
            traceLogger.log("seckill-service", "submit-exception", requestId + " " + sanitize(ex.getMessage()));
        } finally {
            metricsCollector.recordLatency("seckill.worker.latency", System.currentTimeMillis() - start);
        }
    }

    private void refreshPendingRequests() {
        for (SeckillRequestSnapshot snapshot : requestRepository.findRefreshCandidates()) {
            refreshRequest(snapshot.requestId());
        }
        requestRepository.cleanupTerminal(terminalRetentionMillis);
    }

    private void refreshRequest(String requestId) {
        Optional<SeckillRequestSnapshot> snapshot = requestRepository.findById(requestId);
        if (snapshot.isEmpty() || snapshot.get().orderId() == null || snapshot.get().terminal()) {
            return;
        }
        try {
            ApiResponse<OrderView> orderResponse = orderClient.getOrder(snapshot.get().orderId());
            if (!orderResponse.success() || orderResponse.data() == null) {
                metricsCollector.increment("seckill.order-refresh.miss");
                return;
            }
            OrderView order = orderResponse.data();
            OrderStatus orderStatus = parseOrderStatus(order.status());
            switch (orderStatus) {
                case WAITING_PAYMENT, PAID -> {
                    requestRepository.markSuccess(requestId, order.orderId(), order.status(), orderResponse.message());
                    clearActive(snapshot.get());
                    metricsCollector.increment("seckill.success");
                }
                case FAILED, REJECTED -> {
                    requestRepository.markFailed(requestId, "order-" + order.status().toLowerCase());
                    clearActive(snapshot.get());
                    metricsCollector.increment("seckill.failed");
                }
                default -> requestRepository.markSubmitted(requestId, order.orderId(), order.status(), orderResponse.message());
            }
        } catch (RuntimeException ex) {
            metricsCollector.increment("seckill.order-refresh.exception");
        }
    }

    private void clearActive(SeckillRequestSnapshot snapshot) {
        activeRequests.remove(dedupeKey(snapshot.userId(), snapshot.productId()), snapshot.requestId());
    }

    private OrderStatus parseOrderStatus(String status) {
        try {
            return OrderStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            return OrderStatus.PROCESSING;
        }
    }

    private SeckillView toView(SeckillRequestSnapshot snapshot) {
        return new SeckillView(
                snapshot.requestId(),
                snapshot.orderId(),
                snapshot.userId(),
                snapshot.productId(),
                snapshot.quantity(),
                snapshot.status().name(),
                snapshot.orderStatus(),
                snapshot.detail(),
                snapshot.acceptedEpochMillis(),
                snapshot.updatedEpochMillis()
        );
    }

    private String dedupeKey(String userId, String productId) {
        return userId + "|" + productId;
    }

    private Object submissionStripe(String dedupeKey) {
        return submissionStripes[Math.floorMod(dedupeKey.hashCode(), submissionStripes.length)];
    }

    private String sanitize(String message) {
        return message == null ? "unknown" : message.replace('|', '/');
    }
}
