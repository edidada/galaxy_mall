package cn.wdidada.galaxymall.common.api;

import java.io.Serializable;

public record OrderView(
        String orderId,
        String userId,
        String productId,
        int quantity,
        int amountCent,
        String status,
        String queueToken,
        long acceptedEpochMillis
) implements Serializable {
}
