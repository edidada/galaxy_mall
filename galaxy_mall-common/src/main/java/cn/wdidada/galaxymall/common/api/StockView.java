package cn.wdidada.galaxymall.common.api;

public record StockView(
        String reservationId,
        String productId,
        int reservedQuantity,
        int remainingStock,
        String status
) {
}
