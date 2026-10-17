package cn.wdidada.galaxymall.common.api;

import java.util.Map;

/**
 * 埋点聚合视图（{@code GET /api/collect/stats}），给前端埋点看板用。
 */
public record CollectStatsView(
        long totalBatches,
        long totalEvents,
        long droppedByOverflow,
        int bufferSize,
        int capacity,
        long sinceEpochMillis,
        Map<String, Long> countByType,
        Map<String, Long> countByPage,
        Map<String, Long> countByTarget
) {
}
