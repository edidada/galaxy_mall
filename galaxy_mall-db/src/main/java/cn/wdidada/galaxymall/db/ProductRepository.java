package cn.wdidada.galaxymall.db;

import cn.wdidada.galaxymall.common.domain.Product;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ProductRepository {

    private final Map<String, Product> products = new ConcurrentHashMap<>();

    public void save(Product product) {
        products.put(product.productId(), product);
    }

    public Optional<Product> findById(String productId) {
        return Optional.ofNullable(products.get(productId));
    }

    public Collection<Product> findAll() {
        return new ArrayList<>(products.values());
    }

    public List<Product> findHotProducts() {
        return new ArrayList<>(products.values());
    }
}
