package cn.wdidada.galaxymall.gateway;

import cn.wdidada.galaxymall.common.client.OrderClient;
import cn.wdidada.galaxymall.common.client.PaymentClient;
import cn.wdidada.galaxymall.common.client.ProductClient;
import cn.wdidada.galaxymall.common.client.UserClient;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(scanBasePackageClasses = GatewayApplication.class)
@EnableFeignClients(clients = {UserClient.class, ProductClient.class, OrderClient.class, PaymentClient.class})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
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
    GatewayWorkflowService gatewayWorkflowService(
            UserClient userClient,
            ProductClient productClient,
            OrderClient orderClient,
            PaymentClient paymentClient,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger
    ) {
        return new GatewayWorkflowService(userClient, productClient, orderClient, paymentClient, metricsCollector, traceLogger);
    }
}
