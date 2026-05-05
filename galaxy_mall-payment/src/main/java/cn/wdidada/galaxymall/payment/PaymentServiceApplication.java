package cn.wdidada.galaxymall.payment;

import cn.wdidada.galaxymall.common.client.OrderClient;
import cn.wdidada.galaxymall.common.client.StockClient;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(
        scanBasePackageClasses = PaymentServiceApplication.class,
        excludeName = {
                "org.springframework.cloud.gateway.config.GatewayAutoConfiguration",
                "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration",
                "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration"
        }
)
@EnableFeignClients(clients = {OrderClient.class, StockClient.class})
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }

    @Bean
    MetricsCollector metricsCollector() {
        return new MetricsCollector();
    }

    @Bean
    CloudPaymentService cloudPaymentService(OrderClient orderClient, MetricsCollector metricsCollector) {
        return new CloudPaymentService(orderClient, metricsCollector);
    }

    @Bean
    RefundRecordRepository refundRecordRepository() {
        return new RefundRecordRepository();
    }

    @Bean
    SeataSagaRefundService seataSagaRefundService(
            OrderClient orderClient,
            StockClient stockClient,
            RefundRecordRepository refundRecordRepository,
            MetricsCollector metricsCollector
    ) {
        return new SeataSagaRefundService(
                orderClient,
                new SeataSagaPaymentRefundAction(refundRecordRepository, metricsCollector),
                new SeataSagaStockRestoreAction(stockClient, metricsCollector),
                new SeataSagaOrderRefundAction(orderClient, metricsCollector),
                metricsCollector
        );
    }
}
