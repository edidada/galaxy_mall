package cn.wdidada.galaxymall.common.api;

import jakarta.validation.constraints.NotBlank;

public record StockReleaseRequest(
        @NotBlank String reservationId,
        @NotBlank String reason
) {
}
