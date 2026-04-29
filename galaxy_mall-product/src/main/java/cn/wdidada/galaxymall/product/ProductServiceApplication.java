package cn.wdidada.galaxymall.product;

import cn.wdidada.galaxymall.cache.BloomFilterGuard;
import cn.wdidada.galaxymall.cache.MultiLevelCache;
import cn.wdidada.galaxymall.common.domain.Product;
import cn.wdidada.galaxymall.db.ProductRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackageClasses = ProductServiceApplication.class)
public class ProductServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }

    @Bean
    ProductRepository productRepository() {
        ProductRepository repository = new ProductRepository();
        repository.save(new Product(
                "sku-iphone",
                "iPhone Galaxy Edition",
                899900,
                false,
                "<html><body><h1>iPhone Galaxy Edition</h1><p>static page</p></body></html>"
        ));
        repository.save(new Product(
                "sku-mouse",
                "Nebula Mouse",
                9900,
                false,
                "<html><body><h1>Nebula Mouse</h1></body></html>"
        ));
        repository.save(new Product(
                "sku-seckill",
                "Meteor Flash Sale",
                19900,
                true,
                "<html><body><h1>Meteor Flash Sale</h1></body></html>"
        ));
        return repository;
    }

    @Bean
    ProductCatalogService productCatalogService(ProductRepository productRepository) {
        ProductCatalogService service = new ProductCatalogService(
                productRepository,
                new MultiLevelCache<>(),
                new BloomFilterGuard(2048)
        );
        service.warmHotProducts();
        return service;
    }
}
