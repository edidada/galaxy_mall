package cn.wdidada.galaxymall.common.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SeckillRequest(
        @NotBlank String userId,
        @NotBlank String productId,
        @Min(1) @Max(1) int quantity,
        @NotBlank String clientIp
) {
}
