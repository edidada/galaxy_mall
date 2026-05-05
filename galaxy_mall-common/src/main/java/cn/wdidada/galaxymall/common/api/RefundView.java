package cn.wdidada.galaxymall.common.api;

public record RefundView(
        String refundId,
        String orderId,
        String status,
        String message
) {
}
