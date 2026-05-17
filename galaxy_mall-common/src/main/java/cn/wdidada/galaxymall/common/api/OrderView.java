package cn.wdidada.galaxymall.common.api;

import java.io.Serializable;

public final class OrderView implements Serializable {

    private String orderId;
    private String userId;
    private String productId;
    private int quantity;
    private int amountCent;
    private String status;
    private String queueToken;
    private long acceptedEpochMillis;

    public OrderView() {
    }

    public OrderView(String orderId, String userId, String productId, int quantity, int amountCent, String status,
                     String queueToken, long acceptedEpochMillis) {
        this.orderId = orderId;
        this.userId = userId;
        this.productId = productId;
        this.quantity = quantity;
        this.amountCent = amountCent;
        this.status = status;
        this.queueToken = queueToken;
        this.acceptedEpochMillis = acceptedEpochMillis;
    }

    public String orderId() {
        return orderId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String userId() {
        return userId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String productId() {
        return productId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public int quantity() {
        return quantity;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public int amountCent() {
        return amountCent;
    }

    public int getAmountCent() {
        return amountCent;
    }

    public void setAmountCent(int amountCent) {
        this.amountCent = amountCent;
    }

    public String status() {
        return status;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String queueToken() {
        return queueToken;
    }

    public String getQueueToken() {
        return queueToken;
    }

    public void setQueueToken(String queueToken) {
        this.queueToken = queueToken;
    }

    public long acceptedEpochMillis() {
        return acceptedEpochMillis;
    }

    public long getAcceptedEpochMillis() {
        return acceptedEpochMillis;
    }

    public void setAcceptedEpochMillis(long acceptedEpochMillis) {
        this.acceptedEpochMillis = acceptedEpochMillis;
    }
}
