package cn.wdidada.galaxymall.product;

import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.api.ProductDetail;
import cn.wdidada.galaxymall.common.api.ProductListItem;
import cn.wdidada.galaxymall.db.ProductRepository;
import java.util.ArrayList;
import java.util.List;

/**
 * 图文列表 / 详情的读服务。
 *
 * <p>只读 {@link ProductRepository} 判断“能不能下单”，不走缓存与布隆过滤器：
 * 布隆过滤器是压测链路挡穿透用的，列表接口一次读全量，走它反而会把没播种过的 id 误挡掉。
 */
public final class ProductShowcaseService {

    private final ProductRepository productRepository;
    private final ProductShowcaseCatalog catalog;

    public ProductShowcaseService(ProductRepository productRepository, ProductShowcaseCatalog catalog) {
        this.productRepository = productRepository;
        this.catalog = catalog;
    }

    public List<ProductListItem> listProducts() {
        List<String> orderable = new ArrayList<>();
        productRepository.findAll().forEach(product -> orderable.add(product.productId()));
        return catalog.listItems(orderable);
    }

    public OperationResult<ProductDetail> queryDetail(String productId) {
        boolean orderable = productRepository.findById(productId).isPresent();
        return catalog.detail(productId, orderable)
                .map(detail -> OperationResult.success("product-detail-found", detail))
                .orElseGet(() -> OperationResult.failure("product-detail-not-found"));
    }
}
