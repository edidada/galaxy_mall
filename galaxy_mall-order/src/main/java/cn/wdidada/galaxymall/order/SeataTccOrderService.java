package cn.wdidada.galaxymall.order;

import cn.wdidada.galaxymall.common.IdGenerator;
import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.CreateOrderRequest;
import cn.wdidada.galaxymall.common.api.OrderView;
import cn.wdidada.galaxymall.common.api.ProductView;
import cn.wdidada.galaxymall.common.api.StockConfirmRequest;
import cn.wdidada.galaxymall.common.api.StockDeductRequest;
import cn.wdidada.galaxymall.common.api.StockReleaseRequest;
import cn.wdidada.galaxymall.common.api.StockView;
import cn.wdidada.galaxymall.common.client.ProductClient;
import cn.wdidada.galaxymall.common.client.StockClient;
import cn.wdidada.galaxymall.common.domain.Order;
import cn.wdidada.galaxymall.common.domain.OrderStatus;
import cn.wdidada.galaxymall.db.LocalBinlogRepository;
import cn.wdidada.galaxymall.db.OrderStore;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;

public final class SeataTccOrderService {

    private final OrderStore orderStore;
    private final LocalBinlogRepository localBinlogRepository;
    private final ProductClient productClient;
    private final StockClient stockClient;
    private final MetricsCollector metricsCollector;
    private final TraceLogger traceLogger;

    public SeataTccOrderService(
            OrderStore orderStore,
            LocalBinlogRepository localBinlogRepository,
            ProductClient productClient,
            StockClient stockClient,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger
    ) {
        this.orderStore = orderStore;
        this.localBinlogRepository = localBinlogRepository;
        this.productClient = productClient;
        this.stockClient = stockClient;
        this.metricsCollector = metricsCollector;
        this.traceLogger = traceLogger;
    }

    public ApiResponse<OrderView> createOrder(CreateOrderRequest request) {
        ApiResponse<ProductView> productResponse = productClient.getProduct(request.productId());
        if (!productResponse.success() || productResponse.data() == null) {
            return ApiResponse.failure("product-not-found");
        }

        ProductView product = productResponse.data();
        Order order = new Order(
                IdGenerator.nextOrderId(),
                request.userId(),
                request.productId(),
                request.quantity(),
                product.priceCent() * request.quantity(),
                product.seckillProduct()
        );
        order.updateStatus(OrderStatus.PROCESSING);
        orderStore.save(order);
        localBinlogRepository.append("ORDER_TCC_TRY|" + order.orderId() + "|" + request.userId() + "|" + request.productId());
        traceLogger.log("order-tcc", "try", order.orderId());

        ApiResponse<StockView> reserveResponse;
        try {
            reserveResponse = stockClient.reserve(new StockDeductRequest(order.orderId(), request.productId(), request.quantity()));
        } catch (RuntimeException ex) {
            cancelOrder(order, "stock-try-exception", false);
            metricsCollector.increment("order.tcc.try.exception");
            return ApiResponse.failure("stock-try-exception");
        }
        if (!reserveResponse.success()) {
            cancelOrder(order, reserveResponse.message(), false);
            metricsCollector.increment("order.tcc.try.reject");
            return ApiResponse.failure(reserveResponse.message());
        }

        localBinlogRepository.append("ORDER_TCC_STOCK_TRY|" + order.orderId());

        ApiResponse<StockView> confirmResponse;
        try {
            confirmResponse = stockClient.confirm(new StockConfirmRequest(order.orderId()));
        } catch (RuntimeException ex) {
            cancelOrder(order, "stock-confirm-exception", true);
            metricsCollector.increment("order.tcc.confirm.exception");
            return ApiResponse.failure("stock-confirm-exception");
        }
        if (!confirmResponse.success()) {
            cancelOrder(order, confirmResponse.message(), true);
            metricsCollector.increment("order.tcc.confirm.reject");
            return ApiResponse.failure(confirmResponse.message());
        }

        order.updateStatus(OrderStatus.WAITING_PAYMENT);
        orderStore.updateStatus(order.orderId(), OrderStatus.WAITING_PAYMENT);
        localBinlogRepository.append("ORDER_TCC_CONFIRM|" + order.orderId());
        metricsCollector.increment("order.tcc.success");
        traceLogger.log("order-tcc", "confirm", order.orderId());
        return ApiResponse.success("order-tcc-confirmed", toView(order));
    }

    private void cancelOrder(Order order, String reason, boolean releaseStock) {
        if (releaseStock) {
            try {
                ApiResponse<StockView> releaseResponse = stockClient.release(new StockReleaseRequest(order.orderId(), "tcc-cancel:" + reason));
                if (!releaseResponse.success()) {
                    localBinlogRepository.append("ORDER_TCC_STOCK_CANCEL_RETRY|" + order.orderId() + "|" + releaseResponse.message());
                    metricsCollector.increment("order.tcc.stock.cancel.retry");
                }
            } catch (RuntimeException ex) {
                localBinlogRepository.append("ORDER_TCC_STOCK_CANCEL_RETRY|" + order.orderId() + "|" + sanitize(ex.getMessage()));
                metricsCollector.increment("order.tcc.stock.cancel.retry");
            }
        }
        order.updateStatus(OrderStatus.FAILED);
        orderStore.updateStatus(order.orderId(), OrderStatus.FAILED);
        localBinlogRepository.append("ORDER_TCC_CANCEL|" + order.orderId() + "|" + sanitize(reason));
        metricsCollector.increment("order.tcc.cancel");
        traceLogger.log("order-tcc", "cancel", order.orderId() + " " + reason);
    }

    private OrderView toView(Order order) {
        return new OrderView(
                order.orderId(),
                order.userId(),
                order.productId(),
                order.quantity(),
                order.amountCent(),
                order.status().name(),
                null,
                order.createdAt().toEpochMilli()
        );
    }

    private String sanitize(String message) {
        return message == null ? "unknown" : message.replace('|', '/');
    }
}
