package cn.wdidada.galaxymall.common.api;

import jakarta.validation.constraints.NotBlank;

public record StockConfirmRequest(@NotBlank String reservationId) {
}
