package cn.wdidada.galaxymall.common.api;

import java.io.Serializable;

public record RefundView(
        String refundId,
        String orderId,
        String status,
        String message
) implements Serializable {
}
