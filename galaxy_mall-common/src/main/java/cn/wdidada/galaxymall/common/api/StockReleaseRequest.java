package cn.wdidada.galaxymall.common.api;

import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;

public record StockReleaseRequest(
        @NotBlank String reservationId,
        @NotBlank String reason
) implements Serializable {
}
