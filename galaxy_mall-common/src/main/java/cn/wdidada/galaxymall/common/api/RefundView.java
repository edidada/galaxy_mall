package cn.wdidada.galaxymall.common.api;

import java.io.Serializable;

public final class RefundView implements Serializable {

    private String refundId;
    private String orderId;
    private String status;
    private String message;

    public RefundView() {
    }

    public RefundView(String refundId, String orderId, String status, String message) {
        this.refundId = refundId;
        this.orderId = orderId;
        this.status = status;
        this.message = message;
    }

    public String refundId() {
        return refundId;
    }

    public String getRefundId() {
        return refundId;
    }

    public void setRefundId(String refundId) {
        this.refundId = refundId;
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

    public String status() {
        return status;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String message() {
        return message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
