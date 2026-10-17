package cn.wdidada.galaxymall.common.api;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 埋点批量上报入参（{@code POST /api/collect}）。
 *
 * <p>{@code events} 允许为空：SDK 在页面卸载时用 sendBeacon 兜底，可能只发来元信息。
 */
public record CollectBatchRequest(
        @NotBlank String sessionId,
        String userId,
        String page,
        Long sentAt,
        List<CollectEvent> events
) {
}
