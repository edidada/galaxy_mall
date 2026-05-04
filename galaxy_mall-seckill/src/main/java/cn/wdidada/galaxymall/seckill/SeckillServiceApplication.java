package cn.wdidada.galaxymall.seckill;

import cn.wdidada.galaxymall.common.client.OrderClient;
import cn.wdidada.galaxymall.common.client.ProductClient;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(
        scanBasePackageClasses = SeckillServiceApplication.class,
        excludeName = {
                "org.springframework.cloud.gateway.config.GatewayAutoConfiguration",
                "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration",
                "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration"
        }
)
@EnableFeignClients(clients = {OrderClient.class, ProductClient.class})
public class SeckillServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillServiceApplication.class, args);
    }

    @Bean
    MetricsCollector metricsCollector() {
        return new MetricsCollector();
    }

    @Bean
    TraceLogger traceLogger() {
        return new TraceLogger();
    }

    @Bean(destroyMethod = "close")
    SeckillService seckillService(
            OrderClient orderClient,
            ProductClient productClient,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger,
            @Value("${galaxy.seckill.global-rate-limit:20000}") int globalRateLimit,
            @Value("${galaxy.seckill.user-rate-limit:1}") int userRateLimit,
            @Value("${galaxy.seckill.ip-rate-limit:10}") int ipRateLimit,
            @Value("${galaxy.seckill.worker-count:8}") int workerCount,
            @Value("${galaxy.seckill.queue-capacity:4096}") int queueCapacity,
            @Value("${galaxy.seckill.refresh-interval-ms:100}") long refreshIntervalMs,
            @Value("${galaxy.seckill.product-cache-ttl-ms:30000}") long productCacheTtlMs,
            @Value("${galaxy.seckill.terminal-retention-ms:300000}") long terminalRetentionMs
    ) {
        return new SeckillService(
                orderClient,
                productClient,
                metricsCollector,
                traceLogger,
                globalRateLimit,
                userRateLimit,
                ipRateLimit,
                workerCount,
                queueCapacity,
                Duration.ofMillis(refreshIntervalMs),
                Duration.ofMillis(productCacheTtlMs),
                Duration.ofMillis(terminalRetentionMs)
        );
    }
}
