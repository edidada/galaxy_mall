package cn.wdidada.galaxymall.product;

import cn.wdidada.galaxymall.cache.BloomFilterGuard;
import cn.wdidada.galaxymall.cache.MultiLevelCache;
import cn.wdidada.galaxymall.common.domain.Product;
import cn.wdidada.galaxymall.db.ProductRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(
        scanBasePackageClasses = ProductServiceApplication.class,
        excludeName = {
                "org.springframework.cloud.gateway.config.GatewayAutoConfiguration",
                "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration",
                "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration"
        }
)
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
        // 下面是为前端商城图文列表补的商品；必须与 ProductShowcaseSeeds 的 productId 一一对应，
        // 否则前端看得到、下单却报 product-not-found。价格取 SKU 的最低规格价。
        repository.save(new Product("sku-laptop", "Aurora Book 14", 649900, false, staticPage("Aurora Book 14")));
        repository.save(new Product("sku-monitor", "Halo 27 4K", 219900, false, staticPage("Halo 27 4K")));
        repository.save(new Product("sku-earphone", "Pulse ANC Pro", 129900, false, staticPage("Pulse ANC Pro")));
        repository.save(new Product("sku-watch", "Orbit Watch 3", 159900, false, staticPage("Orbit Watch 3")));
        repository.save(new Product("sku-fridge", "Polar 501L", 379900, false, staticPage("Polar 501L")));
        repository.save(new Product("sku-jacket", "Vector 3-in-1", 69900, false, staticPage("Vector 3-in-1")));
        repository.save(new Product("sku-coffee", "Comet Yunnan Beans", 12800, false, staticPage("Comet Yunnan Beans")));
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

    @Bean
    ProductShowcaseCatalog productShowcaseCatalog() {
        ProductShowcaseCatalog catalog = new ProductShowcaseCatalog();
        ProductShowcaseSeeds.registerInto(catalog);
        return catalog;
    }

    @Bean
    ProductShowcaseService productShowcaseService(
            ProductRepository productRepository,
            ProductShowcaseCatalog productShowcaseCatalog
    ) {
        return new ProductShowcaseService(productRepository, productShowcaseCatalog);
    }

    private static String staticPage(String title) {
        return "<html><body><h1>" + title + "</h1><p>static page</p></body></html>";
    }
}
