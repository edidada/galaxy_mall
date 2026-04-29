package cn.wdidada.galaxymall.product;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.ProductView;
import cn.wdidada.galaxymall.common.domain.Product;
import cn.wdidada.galaxymall.common.OperationResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductCatalogService productCatalogService;

    public ProductController(ProductCatalogService productCatalogService) {
        this.productCatalogService = productCatalogService;
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductView> getProduct(@PathVariable String productId) {
        OperationResult<Product> result = productCatalogService.queryProduct(productId);
        return result.success()
                ? ApiResponse.success(result.message(), toView(result.data()))
                : ApiResponse.failure(result.message());
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
