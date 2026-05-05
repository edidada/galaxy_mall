package cn.wdidada.galaxymall.common.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record StockRestockRequest(
        @NotBlank String adjustmentId,
        @NotBlank String productId,
        @Min(1) int quantity,
        @NotBlank String reason
) {
}
