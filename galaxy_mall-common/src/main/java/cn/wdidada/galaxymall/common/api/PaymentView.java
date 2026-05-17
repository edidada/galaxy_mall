package cn.wdidada.galaxymall.common.api;

import java.io.Serializable;

public final class PaymentView implements Serializable {

    private String paymentId;
    private String orderId;
    private String status;
    private String message;

    public PaymentView() {
    }

    public PaymentView(String paymentId, String orderId, String status, String message) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.status = status;
        this.message = message;
    }

    public String paymentId() {
        return paymentId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
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
