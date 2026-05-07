package cn.wdidada.galaxymall.common.api;

import java.io.Serializable;

public record PaymentView(
        String paymentId,
        String orderId,
        String status,
        String message
) implements Serializable {
}
