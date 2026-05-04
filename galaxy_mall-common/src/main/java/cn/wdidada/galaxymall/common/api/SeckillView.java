package cn.wdidada.galaxymall.common.api;

public record SeckillView(
        String requestId,
        String orderId,
        String userId,
        String productId,
        int quantity,
        String status,
        String orderStatus,
        String detail,
        long acceptedEpochMillis,
        long updatedEpochMillis
) {
}
