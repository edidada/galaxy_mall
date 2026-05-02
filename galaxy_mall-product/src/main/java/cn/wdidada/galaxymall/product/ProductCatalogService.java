package cn.wdidada.galaxymall.product;

import cn.wdidada.galaxymall.cache.BloomFilterGuard;
import cn.wdidada.galaxymall.cache.CacheWarmupService;
import cn.wdidada.galaxymall.cache.MultiLevelCache;
import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.domain.Product;
import cn.wdidada.galaxymall.db.ProductRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ProductCatalogService {

    private final ProductRepository productRepository;
    private final MultiLevelCache<String, Product> cache;
    private final BloomFilterGuard bloomFilterGuard;
    private final CacheWarmupService<Product> cacheWarmupService = new CacheWarmupService<>();
    private final Map<String, Boolean> nullValueCache = new ConcurrentHashMap<>();

    public ProductCatalogService(
            ProductRepository productRepository,
            MultiLevelCache<String, Product> cache,
            BloomFilterGuard bloomFilterGuard
    ) {
        this.productRepository = productRepository;
        this.cache = cache;
        this.bloomFilterGuard = bloomFilterGuard;
    }

    public void warmHotProducts() {
        cacheWarmupService.warmup(productRepository.findHotProducts(), product -> {
            cache.put(product.productId(), product);
            bloomFilterGuard.add(product.productId());
        });
    }

    public OperationResult<Product> queryProduct(String productId) {
        if (!bloomFilterGuard.mightContain(productId)) {
            return OperationResult.failure("product-filtered-by-bloom");
        }

        if (nullValueCache.containsKey(productId)) {
            return OperationResult.failure("product-empty-cache-hit");
        }

        Optional<Product> cached = cache.getOrLoad(productId, () -> productRepository.findById(productId));
        if (cached.isPresent()) {
            return OperationResult.success("cache-or-db-hit", cached.get());
        }
        nullValueCache.put(productId, Boolean.TRUE);
        return OperationResult.failure("product-not-found");
    }

    public String renderStaticPage(String productId) {
        OperationResult<Product> result = queryProduct(productId);
        return result.success()
                ? result.data().staticPage()
                : "<html><body><h1>Product not found</h1></body></html>";
    }

    public MultiLevelCache<String, Product> cache() {
        return cache;
    }
}
