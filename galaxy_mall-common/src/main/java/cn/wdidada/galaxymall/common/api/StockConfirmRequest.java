package cn.wdidada.galaxymall.common.api;

import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;

public record StockConfirmRequest(@NotBlank String reservationId) implements Serializable {
}
