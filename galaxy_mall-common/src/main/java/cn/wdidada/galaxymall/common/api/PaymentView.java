package cn.wdidada.galaxymall.common.api;

public record PaymentView(
        String paymentId,
        String orderId,
        String status,
        String message
) {
}
