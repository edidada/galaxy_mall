package cn.wdidada.galaxymall.common.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record GatewayOrderRequest(
        @NotBlank String userId,
        @NotBlank String productId,
        @Min(1) int quantity,
        @NotBlank String clientIp,
        String queueToken
) {
}
