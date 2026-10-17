package cn.wdidada.galaxymall.stock;

import cn.wdidada.galaxymall.db.LocalBinlogRepository;
import cn.wdidada.galaxymall.db.StockLedgerRepository;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import java.time.Duration;
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
        // 2026-10-17 商城前台新增的展示商品：不补库存的话，下单链路会在
        // OrderAsyncConsumer 的 reserve 这一步统一返回 stock-not-enough，
        // 前台「下单 → 待付款 → 支付」就走不通。这里给足量，让演示聚焦在流程而不是缺货。
        // 注意账本按 productId 记库存（不是 skuId），同一商品不同规格共享这一份。
        repository.seed("sku-laptop", 300);
        repository.seed("sku-monitor", 300);
        repository.seed("sku-earphone", 300);
        repository.seed("sku-watch", 300);
        repository.seed("sku-fridge", 300);
        repository.seed("sku-jacket", 300);
        repository.seed("sku-coffee", 300);
        return repository;
    }

    @Bean
    @ConditionalOnProperty(name = "galaxy.stock.backend", havingValue = "in-memory", matchIfMissing = true)
    InMemoryStockBackend inMemoryStockBackend(
            StockLedgerRepository stockLedgerRepository,
            MetricsCollector metricsCollector,
            @Value("${galaxy.stock.local-rate-limit:10000}") int localRateLimit
    ) {
        return new InMemoryStockBackend(stockLedgerRepository, metricsCollector, localRateLimit);
    }

    @Bean
    @ConditionalOnProperty(name = "galaxy.stock.backend", havingValue = "redis")
    RedisLuaStockBackend redisLuaStockBackend(
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

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "galaxy.stock.backend", havingValue = "redis")
    StockCompensationTask stockCompensationTask(
            LocalBinlogRepository localBinlogRepository,
            RedisLuaStockBackend redisLuaStockBackend,
            MetricsCollector metricsCollector,
            @Value("${galaxy.stock.compensation-interval-ms:1000}") long compensationIntervalMs
    ) {
        return new StockCompensationTask(
                localBinlogRepository,
                redisLuaStockBackend,
                metricsCollector,
                Duration.ofMillis(compensationIntervalMs)
        );
    }

    @Bean
    StockService stockService(StockBackend stockBackend) {
        StockService stockService = new StockService(stockBackend);
        stockService.preload("sku-iphone");
        stockService.preload("sku-mouse");
        stockService.preload("sku-seckill");
        stockService.preload("sku-laptop");
        stockService.preload("sku-monitor");
        stockService.preload("sku-earphone");
        stockService.preload("sku-watch");
        stockService.preload("sku-fridge");
        stockService.preload("sku-jacket");
        stockService.preload("sku-coffee");
        return stockService;
    }
}
