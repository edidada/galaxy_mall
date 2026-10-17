package cn.wdidada.galaxymall.product;

import cn.wdidada.galaxymall.common.api.ProductDetail;
import cn.wdidada.galaxymall.common.api.ProductListItem;
import cn.wdidada.galaxymall.common.api.SkuView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 商品图文展示目录（前端商城页的数据源）。
 *
 * <p>为什么单独一个类，而不动 {@code Product} / {@code ProductView}：
 * 那两个对象是 100w TPS 压测链路上的最小结构，构造点散落 7 处，加字段等于全链路改造。
 * 展示维度（图片、多规格、口碑、店铺）改在这里以“追加 record”的方式提供，
 * 压测接口 {@code /api/products/{id}} 与图文接口 {@code /api/products} 各走各的，互不影响。
 *
 * <p>约束：这里每个条目的 productId 必须在 {@code ProductRepository} 里也存在，
 * 否则前端看得到、下单却报 {@code product-not-found}。{@link #listItems()} 会按仓储过滤，
 * 新增展示条目时同步在 {@code ProductServiceApplication#productRepository()} 里补一条 Product。
 *
 * <p>SKU 的 {@code stock} 是按 skuId 哈希出来的展示库存，只服务“有货/无货”文案，
 * 真实预扣仍然由 stock 服务负责。
 */
public final class ProductShowcaseCatalog {

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public void register(Entry entry) {
        entries.put(entry.productId(), entry);
    }

    public List<ProductListItem> listItems(List<String> availableProductIds) {
        List<ProductListItem> items = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (availableProductIds != null && !availableProductIds.contains(entry.productId())) {
                continue;
            }
            items.add(entry.toListItem());
        }
        items.sort(Comparator.comparing(ProductListItem::productId));
        return items;
    }

    public Optional<ProductDetail> detail(String productId, boolean available) {
        Entry entry = entries.get(productId);
        if (entry == null || !available) {
            return Optional.empty();
        }
        return Optional.of(entry.toDetail());
    }

    public record SpecOption(String value, int priceDeltaCent) {

        public static SpecOption of(String value) {
            return new SpecOption(value, 0);
        }
    }

    public record SpecGroup(String name, List<SpecOption> options) {
    }

    /**
     * 一个展示条目。字段与 {@link ProductListItem} / {@link ProductDetail} 一一对应，
     * 只有 {@code specGroups} 会被展开成笛卡尔积 SKU。
     */
    public record Entry(
            String productId,
            String name,
            String subtitle,
            String categoryName,
            int basePriceCent,
            int originalPriceCent,
            String mainImage,
            List<String> images,
            List<String> tags,
            List<SpecGroup> specGroups,
            double rating,
            int reviewCount,
            List<String> highlights,
            String detailHtml,
            String shopName,
            String serviceNote,
            String deliveryNote,
            boolean seckillProduct
    ) {

        ProductListItem toListItem() {
            return new ProductListItem(
                    productId, name, subtitle, categoryName, basePriceCent, originalPriceCent,
                    mainImage, images, tags,
                    specGroups.stream().map(SpecGroup::name).toList(),
                    buildSkus(), seckillProduct, rating, reviewCount
            );
        }

        ProductDetail toDetail() {
            return new ProductDetail(toListItem(), highlights, detailHtml, shopName, serviceNote, deliveryNote);
        }

        /** 把规格组展开成 SKU；SKU 价 = 基础价 + 各规格选中项加价。 */
        List<SkuView> buildSkus() {
            List<SkuView> skus = new ArrayList<>();
            if (specGroups.isEmpty()) {
                skus.add(new SkuView(productId + "-default", productId, "默认规格", Map.of(),
                        basePriceCent, displayStock(productId + "-default"), mainImage));
                return skus;
            }
            forEachCombination(new ArrayList<>(), 0, skus);
            return skus;
        }

        private void forEachCombination(List<SpecOption> chosen, int depth, List<SkuView> sink) {
            if (depth == specGroups.size()) {
                Map<String, String> specs = new LinkedHashMap<>();
                StringBuilder specText = new StringBuilder();
                int price = basePriceCent;
                for (int i = 0; i < chosen.size(); i++) {
                    SpecGroup group = specGroups.get(i);
                    SpecOption option = chosen.get(i);
                    specs.put(group.name(), option.value());
                    price += option.priceDeltaCent();
                    if (!specText.isEmpty()) {
                        specText.append(' ');
                    }
                    specText.append(option.value());
                }
                String skuId = productId + "-" + Integer.toHexString(specs.toString().hashCode() & 0x7fffffff);
                sink.add(new SkuView(skuId, productId, specText.toString(), specs, price, displayStock(skuId), mainImage));
                return;
            }
            for (SpecOption option : specGroups.get(depth).options()) {
                List<SpecOption> next = new ArrayList<>(chosen);
                next.add(option);
                forEachCombination(next, depth + 1, sink);
            }
        }
    }

    private static int displayStock(String skuId) {
        int hash = Math.abs(skuId.hashCode());
        // 少量 SKU 落在“无货”上，让前端有真实的置灰态可展示
        return hash % 17 == 0 ? 0 : 30 + hash % 970;
    }
}
