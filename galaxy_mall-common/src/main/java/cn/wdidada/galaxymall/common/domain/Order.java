package cn.wdidada.galaxymall.common.domain;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public final class Order {

    private final String orderId;
    private final String userId;
    private final String productId;
    private final int quantity;
    private final int amountCent;
    private final boolean seckill;
    private final Instant createdAt;
    private final AtomicInteger asyncAttempts = new AtomicInteger();
    private volatile OrderStatus status;

    public Order(String orderId, String userId, String productId, int quantity, int amountCent, boolean seckill) {
        this.orderId = orderId;
        this.userId = userId;
        this.productId = productId;
        this.quantity = quantity;
        this.amountCent = amountCent;
        this.seckill = seckill;
        this.createdAt = Instant.now();
        this.status = OrderStatus.STOCK_RESERVED;
    }

    public String orderId() {
        return orderId;
    }

    public String userId() {
        return userId;
    }

    public String productId() {
        return productId;
    }

    public int quantity() {
        return quantity;
    }

    public int amountCent() {
        return amountCent;
    }

    public boolean seckill() {
        return seckill;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public OrderStatus status() {
        return status;
    }

    public void updateStatus(OrderStatus nextStatus) {
        this.status = nextStatus;
    }

    public int incrementAsyncAttempts() {
        return asyncAttempts.incrementAndGet();
    }

    public int asyncAttempts() {
        return asyncAttempts.get();
    }
}
