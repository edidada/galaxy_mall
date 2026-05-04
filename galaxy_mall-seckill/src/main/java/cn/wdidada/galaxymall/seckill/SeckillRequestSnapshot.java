package cn.wdidada.galaxymall.seckill;

record SeckillRequestSnapshot(
        String requestId,
        String orderId,
        String userId,
        String productId,
        int quantity,
        String clientIp,
        SeckillStatus status,
        String orderStatus,
        String detail,
        long acceptedEpochMillis,
        long updatedEpochMillis
) {
    boolean terminal() {
        return status.terminal();
    }
}
