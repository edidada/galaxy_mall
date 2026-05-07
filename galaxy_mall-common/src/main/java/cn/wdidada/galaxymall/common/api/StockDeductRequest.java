package cn.wdidada.galaxymall.common.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;

public record StockDeductRequest(
        @NotBlank String reservationId,
        @NotBlank String productId,
        @Min(1) int quantity
) implements Serializable {
}
