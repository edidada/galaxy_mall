package cn.wdidada.galaxymall.common.api;

import java.util.Map;

/**
 * 前端埋点 SDK 上报的单条行为事件。
 *
 * <p>字段刻意保持扁平，方便网关侧直接计数与落环形缓冲：
 *
 * <ul>
 *   <li>{@code type}：pv / click / expose / input / error / perf / biz</li>
 *   <li>{@code page}：路由标识，如 product-list、product-detail</li>
 *   <li>{@code target}：被点击/曝光的元素语义标识，如 buy-now、sku-add-cart</li>
 *   <li>{@code payload}：任意业务侧补充维度（skuId、priceCent、orderId…），网关不解析只透传</li>
 * </ul>
 */
public record CollectEvent(
        String eventId,
        String type,
        String page,
        String target,
        String userId,
        String sessionId,
        String traceId,
        Long clientTs,
        Map<String, Object> payload
) {
}
