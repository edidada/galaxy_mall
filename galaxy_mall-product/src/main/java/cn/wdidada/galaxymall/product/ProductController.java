package cn.wdidada.galaxymall.product;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.ProductDetail;
import cn.wdidada.galaxymall.common.api.ProductListItem;
import cn.wdidada.galaxymall.common.api.ProductView;
import cn.wdidada.galaxymall.common.domain.Product;
import cn.wdidada.galaxymall.common.OperationResult;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductCatalogService productCatalogService;
    private final ProductShowcaseService productShowcaseService;

    public ProductController(
            ProductCatalogService productCatalogService,
            ProductShowcaseService productShowcaseService
    ) {
        this.productCatalogService = productCatalogService;
        this.productShowcaseService = productShowcaseService;
    }

    /**
     * 图文商品列表。
     *
     * @param category 可选，按分类名过滤（“手机通讯”等中文分类名）
     * @param keyword  可选，对 name/subtitle/tags 做包含匹配
     */
    @GetMapping
    public ApiResponse<List<ProductListItem>> listProducts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword
    ) {
        List<ProductListItem> items = productShowcaseService.listProducts();
        if (category != null && !category.isBlank() && !"全部".equals(category)) {
            items = items.stream().filter(item -> category.equals(item.categoryName())).toList();
        }
        if (keyword != null && !keyword.isBlank()) {
            String q = keyword.toLowerCase();
            items = items.stream()
                    .filter(item -> item.name().toLowerCase().contains(q)
                            || item.subtitle().toLowerCase().contains(q)
                            || item.tags().stream().anyMatch(tag -> tag.toLowerCase().contains(q)))
                    .toList();
        }
        return ApiResponse.success("product-list-found", items);
    }

    /** 图文详情：多规格 SKU、详情富文本、店铺与服务信息。 */
    @GetMapping("/{productId}/detail")
    public ApiResponse<ProductDetail> getProductDetail(@PathVariable String productId) {
        OperationResult<ProductDetail> result = productShowcaseService.queryDetail(productId);
        return result.success()
                ? ApiResponse.success(result.message(), result.data())
                : ApiResponse.failure(result.message());
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductView> getProduct(@PathVariable String productId) {
        OperationResult<Product> result = productCatalogService.queryProduct(productId);
        return result.success()
                ? ApiResponse.success(result.message(), toView(result.data()))
                : ApiResponse.failure(result.message());
    }

    @GetMapping(value = "/{productId}/page", produces = MediaType.TEXT_HTML_VALUE)
    public String getStaticPage(@PathVariable String productId) {
        return productCatalogService.renderStaticPage(productId);
    }

    private ProductView toView(Product product) {
        return new ProductView(
                product.productId(),
                product.name(),
                product.priceCent(),
                product.seckillProduct(),
                product.staticPage()
        );
    }
}
