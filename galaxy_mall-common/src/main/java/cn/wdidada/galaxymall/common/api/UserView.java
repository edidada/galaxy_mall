package cn.wdidada.galaxymall.common.api;

import java.io.Serializable;

public record UserView(
        String userId,
        String nickname,
        int level
) implements Serializable {
}
