package cn.wdidada.galaxymall.db;

import cn.wdidada.galaxymall.common.domain.Order;
import cn.wdidada.galaxymall.common.domain.OrderStatus;
import java.util.List;
import java.util.Optional;

public interface OrderStore {

    void save(Order order);

    Optional<Order> findById(String orderId);

    void updateStatus(String orderId, OrderStatus status);

    void updateAsyncAttempts(String orderId, int attempts);

    List<Order> findAll();
}
