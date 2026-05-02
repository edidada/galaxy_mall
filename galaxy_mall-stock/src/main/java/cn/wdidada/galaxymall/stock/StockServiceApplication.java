package cn.wdidada.galaxymall.stock;

import cn.wdidada.galaxymall.db.LocalBinlogRepository;
import cn.wdidada.galaxymall.db.StockLedgerRepository;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootApplication(
        scanBasePackageClasses = StockServiceApplication.class,
        excludeName = {
                "org.springframework.cloud.gateway.config.GatewayAutoConfiguration",
                "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration",
                "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration"
        }
)
public class StockServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockServiceApplication.class, args);
    }

    @Bean
    MetricsCollector metricsCollector() {
        return new MetricsCollector();
    }

    @Bean
    LocalBinlogRepository localBinlogRepository() {
        return new LocalBinlogRepository();
    }

    @Bean
    StockLedgerRepository stockLedgerRepository() {
        StockLedgerRepository repository = new StockLedgerRepository();
        repository.seed("sku-iphone", 10);
        repository.seed("sku-mouse", 1);
        repository.seed("sku-seckill", 2);
        return repository;
    }

    @Bean
    @ConditionalOnProperty(name = "galaxy.stock.backend", havingValue = "in-memory", matchIfMissing = true)
    StockBackend inMemoryStockBackend(
            StockLedgerRepository stockLedgerRepository,
            MetricsCollector metricsCollector,
            @Value("${galaxy.stock.local-rate-limit:10000}") int localRateLimit
    ) {
        return new InMemoryStockBackend(stockLedgerRepository, metricsCollector, localRateLimit);
    }

    @Bean
    @ConditionalOnProperty(name = "galaxy.stock.backend", havingValue = "redis")
    StockBackend redisLuaStockBackend(
            StockLedgerRepository stockLedgerRepository,
            MetricsCollector metricsCollector,
            LocalBinlogRepository localBinlogRepository,
            StringRedisTemplate stringRedisTemplate,
            @Value("${galaxy.stock.local-rate-limit:10000}") int localRateLimit
    ) {
        return new RedisLuaStockBackend(
                stockLedgerRepository,
                metricsCollector,
                localBinlogRepository,
                localRateLimit,
                stringRedisTemplate
        );
    }

    @Bean
    StockService stockService(StockBackend stockBackend) {
        StockService stockService = new StockService(stockBackend);
        stockService.preload("sku-iphone");
        stockService.preload("sku-mouse");
        stockService.preload("sku-seckill");
        return stockService;
    }
}
