package cn.wdidada.galaxymall.common.api;

public record StockView(
        String productId,
        int reservedQuantity,
        int remainingStock
) {
}
