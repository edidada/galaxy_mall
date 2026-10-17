package cn.wdidada.galaxymall.common.api;

import java.util.List;

/**
 * 商品详情（{@code GET /api/products/{productId}/detail}）：列表项 + 图文详情 + 店铺与服务信息。
 */
public record ProductDetail(
        ProductListItem item,
        List<String> highlights,
        String detailHtml,
        String shopName,
        String serviceNote,
        String deliveryNote
) {
}
