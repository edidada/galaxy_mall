package cn.wdidada.galaxymall.gateway;

import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;

@SpringBootApplication(
        scanBasePackageClasses = GatewayApplication.class,
        excludeName = "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration"
)
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
    RestClient userServiceRestClient(@Value("${services.user.url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    RestClient productServiceRestClient(@Value("${services.product.url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    RestClient orderServiceRestClient(@Value("${services.order.url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    RestClient paymentServiceRestClient(@Value("${services.payment.url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    GatewayWorkflowService gatewayWorkflowService(
            @Qualifier("userServiceRestClient") RestClient userServiceRestClient,
            @Qualifier("productServiceRestClient") RestClient productServiceRestClient,
            @Qualifier("orderServiceRestClient") RestClient orderServiceRestClient,
            @Qualifier("paymentServiceRestClient") RestClient paymentServiceRestClient,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger
    ) {
        return new GatewayWorkflowService(
                userServiceRestClient,
                productServiceRestClient,
                orderServiceRestClient,
                paymentServiceRestClient,
                metricsCollector,
                traceLogger
        );
    }
}
