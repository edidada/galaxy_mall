package cn.wdidada.galaxymall.common.domain;

public record StockReservation(
        String reservationId,
        String productId,
        int quantity,
        int remainingStock,
        boolean confirmed
) {
}
