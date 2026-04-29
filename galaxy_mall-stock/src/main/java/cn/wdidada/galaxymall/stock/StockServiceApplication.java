package cn.wdidada.galaxymall.stock;

import cn.wdidada.galaxymall.db.StockLedgerRepository;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackageClasses = StockServiceApplication.class)
public class StockServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockServiceApplication.class, args);
    }

    @Bean
    MetricsCollector metricsCollector() {
        return new MetricsCollector();
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
    StockService stockService(StockLedgerRepository stockLedgerRepository, MetricsCollector metricsCollector) {
        StockService stockService = new StockService(stockLedgerRepository, metricsCollector, 10000);
        stockService.preload("sku-iphone");
        stockService.preload("sku-mouse");
        stockService.preload("sku-seckill");
        return stockService;
    }
}
