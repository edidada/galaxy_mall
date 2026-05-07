package cn.wdidada.galaxymall.payment;

import cn.wdidada.galaxymall.common.client.OrderClient;
import cn.wdidada.galaxymall.common.client.StockClient;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@SpringBootApplication(
        scanBasePackageClasses = PaymentServiceApplication.class,
        excludeName = {
                "org.springframework.cloud.gateway.config.GatewayAutoConfiguration",
                "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration",
                "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration"
        }
)
@EnableDubbo
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }

    @Bean
    MetricsCollector metricsCollector() {
        return new MetricsCollector();
    }

    @Bean
    CloudPaymentService cloudPaymentService(RemoteClients remoteClients, MetricsCollector metricsCollector) {
        return new CloudPaymentService(remoteClients.orderClient(), metricsCollector);
    }

    @Bean
    RefundRecordRepository refundRecordRepository() {
        return new RefundRecordRepository();
    }

    @Bean
    SeataSagaRefundService seataSagaRefundService(
            RemoteClients remoteClients,
            RefundRecordRepository refundRecordRepository,
            MetricsCollector metricsCollector
    ) {
        return new SeataSagaRefundService(
                remoteClients.orderClient(),
                new SeataSagaPaymentRefundAction(refundRecordRepository, metricsCollector),
                new SeataSagaStockRestoreAction(remoteClients.stockClient(), metricsCollector),
                new SeataSagaOrderRefundAction(remoteClients.orderClient(), metricsCollector),
                metricsCollector
        );
    }

    @Component
    static final class RemoteClients {

        @DubboReference(check = false, timeout = 3000)
        private OrderClient orderClient;

        @DubboReference(check = false, timeout = 3000)
        private StockClient stockClient;

        OrderClient orderClient() {
            return orderClient;
        }

        StockClient stockClient() {
            return stockClient;
        }
    }
}
