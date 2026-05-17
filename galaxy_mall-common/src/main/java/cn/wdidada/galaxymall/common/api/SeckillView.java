package cn.wdidada.galaxymall.common.api;

import java.io.Serializable;

public final class SeckillView implements Serializable {

    private String requestId;
    private String orderId;
    private String userId;
    private String productId;
    private int quantity;
    private String status;
    private String orderStatus;
    private String detail;
    private long acceptedEpochMillis;
    private long updatedEpochMillis;

    public SeckillView() {
    }

    public SeckillView(String requestId, String orderId, String userId, String productId, int quantity, String status,
                       String orderStatus, String detail, long acceptedEpochMillis, long updatedEpochMillis) {
        this.requestId = requestId;
        this.orderId = orderId;
        this.userId = userId;
        this.productId = productId;
        this.quantity = quantity;
        this.status = status;
        this.orderStatus = orderStatus;
        this.detail = detail;
        this.acceptedEpochMillis = acceptedEpochMillis;
        this.updatedEpochMillis = updatedEpochMillis;
    }

    public String requestId() {
        return requestId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
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

    public String status() {
        return status;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String orderStatus() {
        return orderStatus;
    }

    public String getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(String orderStatus) {
        this.orderStatus = orderStatus;
    }

    public String detail() {
        return detail;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
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

    public long updatedEpochMillis() {
        return updatedEpochMillis;
    }

    public long getUpdatedEpochMillis() {
        return updatedEpochMillis;
    }

    public void setUpdatedEpochMillis(long updatedEpochMillis) {
        this.updatedEpochMillis = updatedEpochMillis;
    }
}
