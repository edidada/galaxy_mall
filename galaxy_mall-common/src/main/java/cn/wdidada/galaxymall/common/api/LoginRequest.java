package cn.wdidada.galaxymall.common.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 登录入参。演示级：用户名 + 明文口令，仅用于前端联调，不做生产鉴权。
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password,
        @Min(0) long clientTs
) {
}
