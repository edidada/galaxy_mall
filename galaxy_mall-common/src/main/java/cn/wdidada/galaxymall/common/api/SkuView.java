package cn.wdidada.galaxymall.common.api;

import java.util.Map;

/**
 * 一个可下单的规格（SKU）。
 *
 * <p>注意：{@code stock} 是商品服务自己的展示库存，与 stock 服务的真实预扣库存无关，
 * 仅用于前端“有货/无货”的图文展示；真正下单仍然走 stock 服务链路。
 *
 * @param specs 规格维度 -&gt; 选中值，例如 {"颜色":"原色钛金属","内存":"256GB"}
 */
public record SkuView(
        String skuId,
        String productId,
        String specText,
        Map<String, String> specs,
        int priceCent,
        int stock,
        String image
) {
}
