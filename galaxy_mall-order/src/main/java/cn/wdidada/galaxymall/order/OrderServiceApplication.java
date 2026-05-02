package cn.wdidada.galaxymall.order;

import cn.wdidada.galaxymall.common.client.ProductClient;
import cn.wdidada.galaxymall.common.client.StockClient;
import cn.wdidada.galaxymall.db.JdbcShardedOrderStore;
import cn.wdidada.galaxymall.db.LocalBinlogRepository;
import cn.wdidada.galaxymall.db.OrderStore;
import cn.wdidada.galaxymall.db.OrderRepository;
import cn.wdidada.galaxymall.message.InMemoryMessageBus;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(
        scanBasePackageClasses = OrderServiceApplication.class,
        excludeName = {
                "org.springframework.cloud.gateway.config.GatewayAutoConfiguration",
                "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration",
                "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration"
        }
)
@EnableFeignClients(clients = {ProductClient.class, StockClient.class})
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }

    @Bean
    @ConditionalOnProperty(name = "galaxy.order.store-backend", havingValue = "local", matchIfMissing = true)
    OrderStore orderRepository() {
        return new OrderRepository();
    }

    @Bean
    @ConditionalOnProperty(name = "galaxy.order.store-backend", havingValue = "jdbc-sharded")
    OrderStore jdbcShardedOrderStore(
            @Value("${galaxy.order.sharding.shard-count:2}") int shardCount,
            @Value("${galaxy.order.sharding.table-shards:4}") int tableShards,
            @Value("${galaxy.order.sharding.shards[0].url:jdbc:h2:mem:order_shard_0;MODE=MySQL;DB_CLOSE_DELAY=-1}") String shard0Url,
            @Value("${galaxy.order.sharding.shards[0].username:sa}") String shard0Username,
            @Value("${galaxy.order.sharding.shards[0].password:}") String shard0Password,
            @Value("${galaxy.order.sharding.shards[1].url:jdbc:h2:mem:order_shard_1;MODE=MySQL;DB_CLOSE_DELAY=-1}") String shard1Url,
            @Value("${galaxy.order.sharding.shards[1].username:sa}") String shard1Username,
            @Value("${galaxy.order.sharding.shards[1].password:}") String shard1Password
    ) {
        if (shardCount != 2) {
            throw new IllegalArgumentException("current demo jdbc-sharded backend expects shard-count=2");
        }
        List<DataSource> dataSources = List.of(
                DataSourceBuilder.create().url(shard0Url).username(shard0Username).password(shard0Password).build(),
                DataSourceBuilder.create().url(shard1Url).username(shard1Username).password(shard1Password).build()
        );
        return new JdbcShardedOrderStore(dataSources, tableShards);
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
    InMemoryMessageBus inMemoryMessageBus() {
        InMemoryMessageBus messageBus = new InMemoryMessageBus(3);
        messageBus.start();
        return messageBus;
    }

    @Bean
    @ConditionalOnProperty(name = "galaxy.order.messaging-backend", havingValue = "local", matchIfMissing = true)
    OrderMessagePublisher localOrderMessagePublisher(InMemoryMessageBus messageBus) {
        return new LocalOrderMessagePublisher(messageBus);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "galaxy.order.messaging-backend", havingValue = "rocketmq")
    DefaultMQProducer rocketMqProducer(
            @Value("${rocketmq.name-server}") String namesrvAddr,
            @Value("${rocketmq.producer.group}") String producerGroup
    ) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(namesrvAddr);
        producer.start();
        return producer;
    }

    @Bean
    @ConditionalOnProperty(name = "galaxy.order.messaging-backend", havingValue = "rocketmq")
    OrderMessagePublisher rocketMqOrderMessagePublisher(
            DefaultMQProducer rocketMqProducer,
            @Value("${galaxy.order.rocketmq.topic}") String topic
    ) {
        return new RocketMqOrderMessagePublisher(rocketMqProducer, topic);
    }

    @Bean
    @ConditionalOnProperty(name = "galaxy.order.messaging-backend", havingValue = "local", matchIfMissing = true)
    OrderAsyncConsumer orderAsyncConsumer(
            OrderStore orderStore,
            LocalBinlogRepository localBinlogRepository,
            ProductClient productClient,
            StockClient stockClient,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger,
            InMemoryMessageBus messageBus
    ) {
        OrderAsyncConsumer consumer = new OrderAsyncConsumer(
                orderStore,
                localBinlogRepository,
                productClient,
                stockClient,
                metricsCollector,
                traceLogger
        );
        messageBus.register(OrderTopics.ORDER_SUBMITTED, message -> consumer.handle((OrderSubmittedEvent) message.payload()));
        return consumer;
    }

    @Bean
    @ConditionalOnProperty(name = "galaxy.order.messaging-backend", havingValue = "rocketmq")
    OrderAsyncConsumer rocketMqOrderAsyncConsumer(
            OrderStore orderStore,
            LocalBinlogRepository localBinlogRepository,
            ProductClient productClient,
            StockClient stockClient,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger
    ) {
        OrderAsyncConsumer consumer = new OrderAsyncConsumer(
                orderStore,
                localBinlogRepository,
                productClient,
                stockClient,
                metricsCollector,
                traceLogger
        );
        return consumer;
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "galaxy.order.messaging-backend", havingValue = "rocketmq")
    DefaultMQPushConsumer rocketMqOrderSubmittedConsumer(
            @Value("${rocketmq.name-server}") String namesrvAddr,
            @Value("${galaxy.order.rocketmq.topic}") String topic,
            @Value("${galaxy.order.rocketmq.consumer-group}") String consumerGroup,
            OrderAsyncConsumer rocketMqOrderAsyncConsumer
    ) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(consumerGroup);
        consumer.setNamesrvAddr(namesrvAddr);
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(
                    List<org.apache.rocketmq.common.message.MessageExt> messages,
                    ConsumeConcurrentlyContext context
            ) {
                for (org.apache.rocketmq.common.message.MessageExt message : messages) {
                    String orderId = new String(message.getBody(), StandardCharsets.UTF_8);
                    rocketMqOrderAsyncConsumer.handle(new OrderSubmittedEvent(orderId));
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });
        consumer.start();
        return consumer;
    }

    @Bean
    CloudOrderService cloudOrderService(
            OrderStore orderStore,
            LocalBinlogRepository localBinlogRepository,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger,
            ProductClient productClient,
            OrderMessagePublisher orderMessagePublisher
    ) {
        return new CloudOrderService(orderStore, localBinlogRepository, metricsCollector, traceLogger, productClient, orderMessagePublisher);
    }

    @Bean(destroyMethod = "close")
    OrderStockReleaseCompensationTask orderStockReleaseCompensationTask(
            LocalBinlogRepository localBinlogRepository,
            StockClient stockClient,
            MetricsCollector metricsCollector,
            @Value("${galaxy.order.compensation-interval-ms:1000}") long compensationIntervalMs
    ) {
        return new OrderStockReleaseCompensationTask(
                localBinlogRepository,
                stockClient,
                metricsCollector,
                Duration.ofMillis(compensationIntervalMs)
        );
    }
}
