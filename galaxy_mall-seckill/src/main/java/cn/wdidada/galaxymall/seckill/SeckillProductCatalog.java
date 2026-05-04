package cn.wdidada.galaxymall.seckill;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.ProductView;
import cn.wdidada.galaxymall.common.client.ProductClient;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class SeckillProductCatalog {

    private final ProductClient productClient;
    private final long ttlMillis;
    private final Map<String, CachedProduct> cache = new ConcurrentHashMap<>();

    SeckillProductCatalog(ProductClient productClient, long ttlMillis) {
        this.productClient = productClient;
        this.ttlMillis = ttlMillis;
    }

    Optional<ProductView> get(String productId) {
        long now = System.currentTimeMillis();
        CachedProduct cached = cache.get(productId);
        if (cached != null && cached.expireAtMillis() >= now) {
            return cached.product();
        }
        ApiResponse<ProductView> response = productClient.getProduct(productId);
        Optional<ProductView> product = response.success() && response.data() != null
                ? Optional.of(response.data())
                : Optional.empty();
        cache.put(productId, new CachedProduct(product, now + ttlMillis));
        return product;
    }

    private record CachedProduct(Optional<ProductView> product, long expireAtMillis) {
    }
}
