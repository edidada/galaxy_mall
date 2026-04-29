package cn.wdidada.galaxymall.order;

import cn.wdidada.galaxymall.common.IdGenerator;
import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.domain.Order;
import cn.wdidada.galaxymall.common.domain.OrderStatus;
import cn.wdidada.galaxymall.common.domain.Product;
import cn.wdidada.galaxymall.common.domain.User;
import cn.wdidada.galaxymall.db.LocalBinlogRepository;
import cn.wdidada.galaxymall.db.OrderRepository;
import cn.wdidada.galaxymall.message.InMemoryMessageBus;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;
import cn.wdidada.galaxymall.stock.StockService;

public final class OrderService {

    private final OrderRepository orderRepository;
    private final LocalBinlogRepository localBinlogRepository;
    private final InMemoryMessageBus messageBus;
    private final StockService stockService;
    private final MetricsCollector metricsCollector;
    private final TraceLogger traceLogger;

    public OrderService(
            OrderRepository orderRepository,
            LocalBinlogRepository localBinlogRepository,
            InMemoryMessageBus messageBus,
            StockService stockService,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger
    ) {
        this.orderRepository = orderRepository;
        this.localBinlogRepository = localBinlogRepository;
        this.messageBus = messageBus;
        this.stockService = stockService;
        this.metricsCollector = metricsCollector;
        this.traceLogger = traceLogger;
    }

    public OperationResult<Order> placeOrder(User user, Product product, int quantity, boolean seckill) {
        long start = System.currentTimeMillis();
        OperationResult<?> stockResult = stockService.reserve(product.productId(), quantity);
        if (!stockResult.success()) {
            return OperationResult.failure(stockResult.message());
        }

        Order order = new Order(
                IdGenerator.nextOrderId(),
                user.userId(),
                product.productId(),
                quantity,
                product.priceCent() * quantity,
                seckill
        );
        orderRepository.save(order);
        localBinlogRepository.append("CREATE|" + order.orderId() + "|" + order.userId() + "|" + order.productId());
        messageBus.publish(OrderTopics.ORDER_CREATED, order.orderId(), order.orderId());
        metricsCollector.increment("order.place.accepted");
        metricsCollector.recordLatency("order.place.latency", System.currentTimeMillis() - start);
        traceLogger.log("order", "placeOrder", "accepted " + order.orderId());
        return OperationResult.success("order-accepted", order);
    }

    public OrderRepository orderRepository() {
        return orderRepository;
    }

    public void rejectOrder(Order order, String reason) {
        order.updateStatus(OrderStatus.REJECTED);
        traceLogger.log("order", "reject", order.orderId() + " " + reason);
    }
}
