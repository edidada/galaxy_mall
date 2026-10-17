package cn.wdidada.galaxymall.common.api;

import java.util.List;

/**
 * 商品图文列表项（{@code GET /api/products}）。
 *
 * <p>与既有 {@link ProductView} 的区别：ProductView 是压测链路用的最小对象（单 SKU、无图），
 * 这里给前端商城页补充图片、标签、口碑与多规格，二者并存、互不影响。
 */
public record ProductListItem(
        String productId,
        String name,
        String subtitle,
        String categoryName,
        int priceCent,
        int originalPriceCent,
        String mainImage,
        List<String> images,
        List<String> tags,
        List<String> specNames,
        List<SkuView> skus,
        boolean seckillProduct,
        double rating,
        int reviewCount
) {
}
