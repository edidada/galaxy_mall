package cn.wdidada.galaxymall.order;

import cn.wdidada.galaxymall.common.client.ProductClient;
import cn.wdidada.galaxymall.common.client.StockClient;
import cn.wdidada.galaxymall.db.LocalBinlogRepository;
import cn.wdidada.galaxymall.db.OrderRepository;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackageClasses = OrderServiceApplication.class)
@EnableFeignClients(clients = {ProductClient.class, StockClient.class})
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }

    @Bean
    OrderRepository orderRepository() {
        return new OrderRepository();
    }

    @Bean
    LocalBinlogRepository localBinlogRepository() {
        return new LocalBinlogRepository();
    }

    @Bean
    MetricsCollector metricsCollector() {
        return new MetricsCollector();
    }

    @Bean
    TraceLogger traceLogger() {
        return new TraceLogger();
    }

    @Bean
    CloudOrderService cloudOrderService(
            OrderRepository orderRepository,
            LocalBinlogRepository localBinlogRepository,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger,
            ProductClient productClient,
            StockClient stockClient
    ) {
        return new CloudOrderService(orderRepository, localBinlogRepository, metricsCollector, traceLogger, productClient, stockClient);
    }
}
