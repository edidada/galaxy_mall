package cn.wdidada.galaxymall.web;

import cn.wdidada.galaxymall.common.client.GalaxyServiceNames;
import cn.wdidada.galaxymall.gateway.GatewayApplication;
import cn.wdidada.galaxymall.order.OrderServiceApplication;
import cn.wdidada.galaxymall.payment.PaymentServiceApplication;
import cn.wdidada.galaxymall.product.ProductServiceApplication;
import cn.wdidada.galaxymall.seckill.SeckillServiceApplication;
import cn.wdidada.galaxymall.stock.StockServiceApplication;
import cn.wdidada.galaxymall.user.UserServiceApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

class SpringCloudSmokeTest {

    private static final boolean REAL_INFRA = Boolean.parseBoolean(System.getProperty("galaxy.test.realInfra", "false"));
    private static final String MYSQL_HOST = System.getProperty("galaxy.test.mysql.host", "127.0.0.1");
    private static final String MYSQL_PORT = System.getProperty("galaxy.test.mysql.port", "3306");
    private static final String MYSQL_USERNAME = System.getProperty("galaxy.test.mysql.username", "root");
    private static final String MYSQL_PASSWORD = System.getProperty("galaxy.test.mysql.password", "5%Edidada");
    private static final String REDIS_HOST = System.getProperty("galaxy.test.redis.host", "127.0.0.1");
    private static final String REDIS_PORT = System.getProperty("galaxy.test.redis.port", "6379");
    private static final String REDIS_PASSWORD = System.getProperty("galaxy.test.redis.password", "");
    private static final String ROCKETMQ_NAMESRV = System.getProperty("galaxy.test.rocketmq.namesrv", "127.0.0.1:9876");
    private static final String ROCKETMQ_TOPIC = System.getProperty("galaxy.test.rocketmq.topic", "galaxy-order-submitted-it");
    private static final String ROCKETMQ_CONSUMER_GROUP = "galaxy-order-consumer-it-" + UUID.randomUUID();
    private static final String ROCKETMQ_PRODUCER_GROUP = "galaxy-order-producer-it-" + UUID.randomUUID();
    private static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final RestClient REST_CLIENT = RestClient.builder().baseUrl("http://localhost:19080").build();

    @BeforeAll
    static void startApplications() throws Exception {
        if (REAL_INFRA) {
            prepareMysqlSchemas();
        }
        CONTEXTS.add(start(UserServiceApplication.class, "19081"));
        CONTEXTS.add(start(ProductServiceApplication.class, "19082"));
        CONTEXTS.add(start(StockServiceApplication.class, "19083", stockProperties()));
        CONTEXTS.add(start(OrderServiceApplication.class, "19084", orderProperties()));
        CONTEXTS.add(start(PaymentServiceApplication.class, "19085", paymentProperties()));
        CONTEXTS.add(start(SeckillServiceApplication.class, "19086", seckillProperties()));
        CONTEXTS.add(start(GatewayApplication.class, "19080", gatewayProperties()));
        awaitServiceReady("http://localhost:19080/actuator/health/readiness");
    }

    @AfterAll
    static void stopApplications() {
        for (int i = CONTEXTS.size() - 1; i >= 0; i--) {
            CONTEXTS.get(i).close();
        }
    }

    private static String[] stockProperties() {
        List<String> properties = new ArrayList<>(localDiscoveryProperties());
        if (!REAL_INFRA) {
            return properties.toArray(String[]::new);
        }
        properties.add("galaxy.stock.backend=redis");
        properties.add("spring.data.redis.host=" + REDIS_HOST);
        properties.add("spring.data.redis.port=" + REDIS_PORT);
        properties.add("galaxy.stock.local-rate-limit=100000");
        if (!REDIS_PASSWORD.isBlank()) {
            properties.add("spring.data.redis.password=" + REDIS_PASSWORD);
        }
        return properties.toArray(String[]::new);
    }

    private static String[] orderProperties() {
        List<String> properties = new ArrayList<>(localDiscoveryProperties());
        properties.add("galaxy.order.store-backend=jdbc-sharded");
        if (REAL_INFRA) {
            properties.add("galaxy.order.messaging-backend=rocketmq");
            properties.add("rocketmq.name-server=" + ROCKETMQ_NAMESRV);
            properties.add("rocketmq.producer.group=" + ROCKETMQ_PRODUCER_GROUP);
            properties.add("galaxy.order.rocketmq.topic=" + ROCKETMQ_TOPIC);
            properties.add("galaxy.order.rocketmq.consumer-group=" + ROCKETMQ_CONSUMER_GROUP);
            properties.add("galaxy.order.sharding.shards[0].url=" + mysqlJdbcUrl("galaxy_order_0"));
            properties.add("galaxy.order.sharding.shards[0].username=" + MYSQL_USERNAME);
            properties.add("galaxy.order.sharding.shards[0].password=" + MYSQL_PASSWORD);
            properties.add("galaxy.order.sharding.shards[1].url=" + mysqlJdbcUrl("galaxy_order_1"));
            properties.add("galaxy.order.sharding.shards[1].username=" + MYSQL_USERNAME);
            properties.add("galaxy.order.sharding.shards[1].password=" + MYSQL_PASSWORD);
        }
        else {
            properties.add("galaxy.order.sharding.shards[0].url=jdbc:h2:mem:order_shard_0;MODE=MySQL;DB_CLOSE_DELAY=-1");
            properties.add("galaxy.order.sharding.shards[0].username=sa");
            properties.add("galaxy.order.sharding.shards[0].password=");
            properties.add("galaxy.order.sharding.shards[1].url=jdbc:h2:mem:order_shard_1;MODE=MySQL;DB_CLOSE_DELAY=-1");
            properties.add("galaxy.order.sharding.shards[1].username=sa");
            properties.add("galaxy.order.sharding.shards[1].password=");
        }
        return properties.toArray(String[]::new);
    }

    private static String[] paymentProperties() {
        return localDiscoveryProperties().toArray(String[]::new);
    }

    private static String[] seckillProperties() {
        return localDiscoveryProperties().toArray(String[]::new);
    }

    private static String[] gatewayProperties() {
        List<String> properties = new ArrayList<>(localDiscoveryProperties());
        properties.add("spring.cloud.gateway.filter.json-to-grpc.enabled=false");
        properties.add("spring.autoconfigure.exclude=org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration");
        return properties.toArray(String[]::new);
    }

    @Test
    void gatewayShouldCompleteMainFlow() throws Exception {
        JsonNode healthNode = parse(REST_CLIENT.get()
                .uri("/actuator/health/readiness")
                .retrieve()
                .body(String.class));
        Assertions.assertEquals("UP", healthNode.get("status").asText());

        String prometheusMetrics = REST_CLIENT.get()
                .uri("/actuator/prometheus")
                .retrieve()
                .body(String.class);
        Assertions.assertTrue(prometheusMetrics.contains("jvm_threads_live_threads"));

        JsonNode productNode = parse(REST_CLIENT.get()
                .uri("/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1")
                .retrieve()
                .body(String.class));
        Assertions.assertTrue(productNode.get("success").asBoolean());
        Assertions.assertEquals("sku-iphone", productNode.get("data").get("productId").asText());

        JsonNode edgeProductNode = parse(REST_CLIENT.get()
                .uri("/edge/products/sku-iphone")
                .retrieve()
                .body(String.class));
        Assertions.assertTrue(edgeProductNode.get("success").asBoolean());
        Assertions.assertEquals("sku-iphone", edgeProductNode.get("data").get("productId").asText());

        String staticPage = REST_CLIENT.get()
                .uri("http://localhost:19082/api/products/sku-iphone/page")
                .retrieve()
                .body(String.class);
        Assertions.assertTrue(staticPage.contains("iPhone Galaxy Edition"));

        String orderPayload = """
                {
                  "userId": "u100",
                  "productId": "sku-iphone",
                  "quantity": 1,
                  "clientIp": "10.0.1.1"
                }
                """;
        JsonNode orderNode = parse(REST_CLIENT.post()
                .uri("/api/gateway/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(orderPayload)
                .retrieve()
                .body(String.class));
        Assertions.assertTrue(orderNode.get("success").asBoolean());
        String orderId = orderNode.get("data").get("orderId").asText();
        Assertions.assertEquals("SUBMITTED", orderNode.get("data").get("status").asText());

        JsonNode readyOrder = awaitOrderStatus(orderId, "WAITING_PAYMENT");
        Assertions.assertEquals("WAITING_PAYMENT", readyOrder.get("data").get("status").asText());

        JsonNode payNode = parse(REST_CLIENT.post()
                .uri("/api/gateway/payments/{orderId}", orderId)
                .retrieve()
                .body(String.class));
        Assertions.assertTrue(payNode.get("success").asBoolean());
        Assertions.assertEquals("SUCCESS", payNode.get("data").get("status").asText());

        String rejectPayload = """
                {
                  "userId": "u101",
                  "productId": "sku-mouse",
                  "quantity": 2,
                  "clientIp": "10.0.2.1"
                }
                """;
        JsonNode rejectNode = parse(REST_CLIENT.post()
                .uri("/api/gateway/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(rejectPayload)
                .retrieve()
                .body(String.class));
        Assertions.assertTrue(rejectNode.get("success").asBoolean());
        String rejectedOrderId = rejectNode.get("data").get("orderId").asText();
        JsonNode rejectedOrder = awaitOrderStatus(rejectedOrderId, "FAILED");
        Assertions.assertEquals("FAILED", rejectedOrder.get("data").get("status").asText());

        String queuedPayload = """
                {
                  "userId": "u300",
                  "productId": "sku-iphone",
                  "quantity": 1,
                  "clientIp": "10.0.3.1"
                }
                """;
        JsonNode queuedNode = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            JsonNode candidate = parse(REST_CLIENT.post()
                    .uri("/api/gateway/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(queuedPayload)
                    .retrieve()
                    .body(String.class));
            if ("QUEUED".equals(candidate.get("data").get("status").asText())) {
                queuedNode = candidate;
                break;
            }
        }
        Assertions.assertNotNull(queuedNode, "queue flow did not return QUEUED within retry budget");
        Assertions.assertTrue(queuedNode.get("success").asBoolean());
        Assertions.assertEquals("QUEUED", queuedNode.get("data").get("status").asText());
        String queueToken = queuedNode.get("data").get("queueToken").asText();
        Assertions.assertTrue(queuedNode.get("message").asText().contains("Galaxy Mall Queue"));

        Thread.sleep(1100L);
        String admittedPayload = """
                {
                  "userId": "u300",
                  "productId": "sku-iphone",
                  "quantity": 1,
                  "clientIp": "10.0.3.1",
                  "queueToken": "%s"
                }
                """.formatted(queueToken);
        JsonNode admittedNode = parse(REST_CLIENT.post()
                .uri("/api/gateway/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(admittedPayload)
                .retrieve()
                .body(String.class));
        Assertions.assertTrue(admittedNode.get("success").asBoolean());
        String admittedOrderId = admittedNode.get("data").get("orderId").asText();
        JsonNode admittedOrder = awaitOrderStatus(admittedOrderId, "WAITING_PAYMENT");
        Assertions.assertEquals("WAITING_PAYMENT", admittedOrder.get("data").get("status").asText());
    }

    private static ConfigurableApplicationContext start(Class<?> applicationClass, String port, String... extraProperties) {
        List<String> properties = new ArrayList<>();
        properties.add("spring.config.location=" + configLocation(applicationClass));
        properties.add("server.port=" + port);
        properties.add("spring.main.lazy-initialization=false");
        properties.add("logging.level.root=ERROR");
        properties.add("spring.cloud.nacos.discovery.enabled=false");
        properties.add("spring.cloud.nacos.discovery.register-enabled=false");
        if (REAL_INFRA) {
            properties.add("spring.profiles.active=uat");
        }
        if (!GatewayApplication.class.equals(applicationClass)) {
            properties.add("spring.cloud.gateway.enabled=false");
            properties.add("spring.cloud.gateway.filter.json-to-grpc.enabled=false");
            properties.add("spring.autoconfigure.exclude=" + defaultExcludes(applicationClass));
        }
        for (String extraProperty : extraProperties) {
            properties.add(extraProperty);
        }
        return new SpringApplicationBuilder(applicationClass)
                .run(properties.stream().map(property -> "--" + property).toArray(String[]::new));
    }

    private static String defaultExcludes(Class<?> applicationClass) {
        String gatewayExcludes =
                "org.springframework.cloud.gateway.config.GatewayAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration";
        if (REAL_INFRA) {
            return gatewayExcludes;
        }
        if (OrderServiceApplication.class.equals(applicationClass) || PaymentServiceApplication.class.equals(applicationClass)) {
            return gatewayExcludes + ",org.apache.seata.spring.boot.autoconfigure.SeataAutoConfiguration";
        }
        return gatewayExcludes;
    }

    private static List<String> localDiscoveryProperties() {
        return List.of(
                instanceProperty(GalaxyServiceNames.USER, "19081"),
                instanceProperty(GalaxyServiceNames.PRODUCT, "19082"),
                instanceProperty(GalaxyServiceNames.STOCK, "19083"),
                instanceProperty(GalaxyServiceNames.ORDER, "19084"),
                instanceProperty(GalaxyServiceNames.PAYMENT, "19085"),
                instanceProperty(GalaxyServiceNames.SECKILL, "19086")
        );
    }

    private static String instanceProperty(String serviceId, String port) {
        return "spring.cloud.discovery.client.simple.instances." + serviceId + "[0].uri=http://localhost:" + port;
    }

    private static void prepareMysqlSchemas() throws Exception {
        try (Connection connection = DriverManager.getConnection(adminJdbcUrl(), MYSQL_USERNAME, MYSQL_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("create database if not exists galaxy_order_0 character set utf8mb4 collate utf8mb4_unicode_ci");
            statement.execute("create database if not exists galaxy_order_1 character set utf8mb4 collate utf8mb4_unicode_ci");
        }
        resetShardTables("galaxy_order_0");
        resetShardTables("galaxy_order_1");
    }

    private static void resetShardTables(String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(mysqlJdbcUrl(schema), MYSQL_USERNAME, MYSQL_PASSWORD);
             Statement statement = connection.createStatement()) {
            for (int table = 0; table < 4; table++) {
                statement.execute("drop table if exists t_order_" + table);
            }
        }
    }

    private static String configLocation(Class<?> applicationClass) {
        try {
            Path classesDirectory = Path.of(applicationClass.getProtectionDomain().getCodeSource().getLocation().toURI());
            return classesDirectory.toUri().toString();
        }
        catch (URISyntaxException e) {
            throw new IllegalStateException("failed to resolve config location for " + applicationClass.getName(), e);
        }
    }

    private static String adminJdbcUrl() {
        return "jdbc:mysql://" + MYSQL_HOST + ":" + MYSQL_PORT
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8";
    }

    private static String mysqlJdbcUrl(String database) {
        return "jdbc:mysql://" + MYSQL_HOST + ":" + MYSQL_PORT + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8";
    }

    private static JsonNode parse(String body) throws Exception {
        return OBJECT_MAPPER.readTree(body);
    }

    private static JsonNode awaitOrderStatus(String orderId, String expectedStatus) throws Exception {
        long timeoutMillis = REAL_INFRA ? 30000L : 5000L;
        long deadline = System.currentTimeMillis() + timeoutMillis;
        JsonNode latest = null;
        while (System.currentTimeMillis() < deadline) {
            latest = parse(REST_CLIENT.get()
                    .uri("/api/gateway/orders/{orderId}", orderId)
                    .retrieve()
                    .body(String.class));
            if (latest.get("success").asBoolean() && expectedStatus.equals(latest.get("data").get("status").asText())) {
                return latest;
            }
            Thread.sleep(100L);
        }
        Assertions.fail("order status did not reach " + expectedStatus + ": " + latest);
        return latest;
    }

    private static void awaitServiceReady(String url) throws Exception {
        long deadline = System.currentTimeMillis() + 30000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                String body = RestClient.create().get().uri(url).retrieve().body(String.class);
                if (body != null && body.contains("\"status\":\"UP\"")) {
                    return;
                }
            }
            catch (Exception ignored) {
                // Wait until the service is fully ready before the actual smoke assertions start.
            }
            Thread.sleep(200L);
        }
        Assertions.fail("service not ready: " + url);
    }
}
