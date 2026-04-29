package cn.wdidada.galaxymall.db;

import cn.wdidada.galaxymall.common.domain.Order;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class OrderRepository implements OrderStore {

    private final List<Map<String, Order>> shards = List.of(
            new ConcurrentHashMap<>(),
            new ConcurrentHashMap<>(),
            new ConcurrentHashMap<>(),
            new ConcurrentHashMap<>()
    );

    @Override
    public void save(Order order) {
        shard(order.orderId()).put(order.orderId(), order);
    }

    @Override
    public Optional<Order> findById(String orderId) {
        return Optional.ofNullable(shard(orderId).get(orderId));
    }

    @Override
    public void updateStatus(String orderId, cn.wdidada.galaxymall.common.domain.OrderStatus status) {
        findById(orderId).ifPresent(order -> order.updateStatus(status));
    }

    @Override
    public void updateAsyncAttempts(String orderId, int attempts) {
        findById(orderId).ifPresent(order -> order.resetAsyncAttempts(attempts));
    }

    @Override
    public List<Order> findAll() {
        List<Order> orders = new ArrayList<>();
        for (Map<String, Order> shard : shards) {
            orders.addAll(shard.values());
        }
        return orders;
    }

    private Map<String, Order> shard(String orderId) {
        int index = Math.abs(orderId.hashCode()) % shards.size();
        return shards.get(index);
    }
}
