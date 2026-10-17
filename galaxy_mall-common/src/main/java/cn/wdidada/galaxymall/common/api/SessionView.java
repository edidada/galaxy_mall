package cn.wdidada.galaxymall.common.api;

/**
 * {@code GET /api/users/me} 的出参：由令牌反查出的当前登录态。
 */
public record SessionView(
        String userId,
        String nickname,
        int level,
        long issuedAtEpochMillis,
        long expiresAtEpochMillis
) {
}
