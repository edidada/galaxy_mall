package cn.wdidada.galaxymall.payment;

import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.domain.Order;
import cn.wdidada.galaxymall.common.domain.OrderStatus;
import cn.wdidada.galaxymall.db.OrderRepository;
import cn.wdidada.galaxymall.monitor.MetricsCollector;

public final class PaymentService {

    private final OrderRepository orderRepository;
    private final MetricsCollector metricsCollector;

    public PaymentService(OrderRepository orderRepository, MetricsCollector metricsCollector) {
        this.orderRepository = orderRepository;
        this.metricsCollector = metricsCollector;
    }

    public OperationResult<Order> pay(String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElse(null);
        if (order == null) {
            return OperationResult.failure("order-not-found");
        }
        if (order.status() != OrderStatus.WAITING_PAYMENT) {
            return OperationResult.failure("order-not-payable");
        }
        order.updateStatus(OrderStatus.PAID);
        metricsCollector.increment("payment.success");
        return OperationResult.success("payment-success", order);
    }
}
