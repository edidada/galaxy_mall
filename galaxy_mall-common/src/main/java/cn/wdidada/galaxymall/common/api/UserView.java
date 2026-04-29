package cn.wdidada.galaxymall.common.api;

public record UserView(
        String userId,
        String nickname,
        int level
) {
}
