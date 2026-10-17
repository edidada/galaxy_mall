package cn.wdidada.galaxymall.common.api;

/**
 * 登录成功返回：用户信息 + 会话令牌。
 *
 * @param token                后续请求放在 {@code X-Auth-Token} 头上
 * @param expiresAtEpochMillis 令牌绝对过期时间（毫秒），过期后需重新登录
 */
public record LoginView(
        String userId,
        String nickname,
        int level,
        String token,
        long expiresAtEpochMillis
) {
}
