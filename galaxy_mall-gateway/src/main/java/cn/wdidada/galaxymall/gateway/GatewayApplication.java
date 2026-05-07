package cn.wdidada.galaxymall.gateway;

import cn.wdidada.galaxymall.common.client.OrderClient;
import cn.wdidada.galaxymall.common.client.PaymentClient;
import cn.wdidada.galaxymall.common.client.ProductClient;
import cn.wdidada.galaxymall.common.client.UserClient;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@SpringBootApplication(
        scanBasePackageClasses = GatewayApplication.class,
        excludeName = "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration"
)
@EnableConfigurationProperties(GatewayTrafficProperties.class)
@EnableDubbo
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
            RemoteClients remoteClients,
            GatewayTrafficProperties trafficProperties,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger
    ) {
        return new GatewayWorkflowService(
                remoteClients.userClient(),
                remoteClients.productClient(),
                remoteClients.orderClient(),
                remoteClients.paymentClient(),
                trafficProperties,
                metricsCollector,
                traceLogger
        );
    }

    @Component
    static final class RemoteClients {

        @DubboReference(check = false, timeout = 2000)
        private UserClient userClient;

        @DubboReference(check = false, timeout = 2000)
        private ProductClient productClient;

        @DubboReference(check = false, timeout = 3000)
        private OrderClient orderClient;

        @DubboReference(check = false, timeout = 3000)
        private PaymentClient paymentClient;

        UserClient userClient() {
            return userClient;
        }

        ProductClient productClient() {
            return productClient;
        }

        OrderClient orderClient() {
            return orderClient;
        }

        PaymentClient paymentClient() {
            return paymentClient;
        }
    }
}
