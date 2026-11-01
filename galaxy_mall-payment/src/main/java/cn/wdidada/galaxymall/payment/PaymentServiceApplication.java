package cn.wdidada.galaxymall.payment;

import cn.wdidada.galaxymall.common.client.OrderClient;
import cn.wdidada.galaxymall.common.client.StockClient;
import cn.wdidada.galaxymall.db.JdbcPaymentStore;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
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

    /** {@code galaxy.payment.store-backend=jdbc} 时启用支付流水落库（表 t_payment，库 galaxy_payment）。 */
    @Bean
    @ConditionalOnProperty(name = "galaxy.payment.store-backend", havingValue = "jdbc")
    JdbcPaymentStore jdbcPaymentStore(
            @Value("${galaxy.payment.db.url}") String url,
            @Value("${galaxy.payment.db.username:root}") String username,
            @Value("${galaxy.payment.db.password:}") String password
    ) {
        DataSource dataSource = DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .build();
        return new JdbcPaymentStore(dataSource);
    }

    @Bean
    CloudPaymentService cloudPaymentService(
            OrderClient orderClient,
            MetricsCollector metricsCollector,
            ObjectProvider<JdbcPaymentStore> paymentStoreProvider
    ) {
        return new CloudPaymentService(orderClient, metricsCollector, paymentStoreProvider.getIfAvailable());
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
