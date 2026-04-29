package cn.wdidada.galaxymall.order;

import cn.wdidada.galaxymall.common.domain.Order;
import cn.wdidada.galaxymall.common.domain.OrderStatus;
import cn.wdidada.galaxymall.db.OrderRepository;
import cn.wdidada.galaxymall.message.MallMessage;
import cn.wdidada.galaxymall.message.MessageConsumer;
import cn.wdidada.galaxymall.monitor.TraceLogger;
import cn.wdidada.galaxymall.stock.StockService;

public final class OrderFulfillmentConsumer implements MessageConsumer {

    private final OrderRepository orderRepository;
    private final StockService stockService;
    private final TraceLogger traceLogger;

    public OrderFulfillmentConsumer(OrderRepository orderRepository, StockService stockService, TraceLogger traceLogger) {
        this.orderRepository = orderRepository;
        this.stockService = stockService;
        this.traceLogger = traceLogger;
    }

    @Override
    public void consume(MallMessage message) {
        String orderId = String.valueOf(message.payload());
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("order missing: " + orderId));
        int attempt = order.incrementAsyncAttempts();
        if (attempt == 1 && order.quantity() >= 2) {
            traceLogger.log("order-consumer", "retry", "simulated transient failure for " + orderId);
            throw new IllegalStateException("simulated async failure");
        }

        if (!stockService.confirm(order.productId(), order.quantity()).success()) {
            order.updateStatus(OrderStatus.FAILED);
            traceLogger.log("order-consumer", "confirm", "failed " + orderId);
            throw new IllegalStateException("stock confirm failed");
        }
        order.updateStatus(OrderStatus.WAITING_PAYMENT);
        traceLogger.log("order-consumer", "confirm", "waiting payment " + orderId);
    }
}
