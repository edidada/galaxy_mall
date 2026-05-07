package cn.wdidada.galaxymall.common.api;

import java.io.Serializable;

public record StockView(
        String reservationId,
        String productId,
        int reservedQuantity,
        int remainingStock,
        String status
) implements Serializable {
}
