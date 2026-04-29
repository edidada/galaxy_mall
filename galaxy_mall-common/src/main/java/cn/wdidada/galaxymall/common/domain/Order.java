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
        this(orderId, userId, productId, quantity, amountCent, seckill, Instant.now(), OrderStatus.SUBMITTED, 0);
    }

    private Order(
            String orderId,
            String userId,
            String productId,
            int quantity,
            int amountCent,
            boolean seckill,
            Instant createdAt,
            OrderStatus status,
            int asyncAttempts
    ) {
        this.orderId = orderId;
        this.userId = userId;
        this.productId = productId;
        this.quantity = quantity;
        this.amountCent = amountCent;
        this.seckill = seckill;
        this.createdAt = createdAt;
        this.status = status;
        this.asyncAttempts.set(asyncAttempts);
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

    public void resetAsyncAttempts(int attempts) {
        asyncAttempts.set(attempts);
    }

    public static Order restore(
            String orderId,
            String userId,
            String productId,
            int quantity,
            int amountCent,
            boolean seckill,
            Instant createdAt,
            OrderStatus status,
            int asyncAttempts
    ) {
        return new Order(orderId, userId, productId, quantity, amountCent, seckill, createdAt, status, asyncAttempts);
    }
}
