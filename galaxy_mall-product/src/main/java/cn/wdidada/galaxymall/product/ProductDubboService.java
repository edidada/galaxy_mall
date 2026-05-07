package cn.wdidada.galaxymall.product;

import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.ProductView;
import cn.wdidada.galaxymall.common.client.ProductClient;
import cn.wdidada.galaxymall.common.domain.Product;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class ProductDubboService implements ProductClient {

    private final ProductCatalogService productCatalogService;

    public ProductDubboService(ProductCatalogService productCatalogService) {
        this.productCatalogService = productCatalogService;
    }

    @Override
    public ApiResponse<ProductView> getProduct(String productId) {
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
