package cn.wdidada.galaxymall.web;

import cn.wdidada.galaxymall.gateway.GatewayApplication;
import cn.wdidada.galaxymall.order.OrderServiceApplication;
import cn.wdidada.galaxymall.payment.PaymentServiceApplication;
import cn.wdidada.galaxymall.product.ProductServiceApplication;
import cn.wdidada.galaxymall.stock.StockServiceApplication;
import cn.wdidada.galaxymall.user.UserServiceApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

class SpringCloudSmokeTest {

    private static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final RestClient REST_CLIENT = RestClient.builder().baseUrl("http://localhost:19080").build();

    @BeforeAll
    static void startApplications() {
        CONTEXTS.add(start(UserServiceApplication.class, "19081"));
        CONTEXTS.add(start(ProductServiceApplication.class, "19082"));
        CONTEXTS.add(start(StockServiceApplication.class, "19083"));
        CONTEXTS.add(start(OrderServiceApplication.class, "19084",
                "services.product.url=http://localhost:19082",
                "services.stock.url=http://localhost:19083"));
        CONTEXTS.add(start(PaymentServiceApplication.class, "19085",
                "services.order.url=http://localhost:19084"));
        CONTEXTS.add(start(GatewayApplication.class, "19080",
                "services.user.url=http://localhost:19081",
                "services.product.url=http://localhost:19082",
                "services.stock.url=http://localhost:19083",
                "services.order.url=http://localhost:19084",
                "services.payment.url=http://localhost:19085"));
    }

    @AfterAll
    static void stopApplications() {
        for (int i = CONTEXTS.size() - 1; i >= 0; i--) {
            CONTEXTS.get(i).close();
        }
    }

    @Test
    void gatewayShouldCompleteMainFlow() throws Exception {
        JsonNode productNode = parse(REST_CLIENT.get()
                .uri("/api/gateway/products/sku-iphone?userId=u100&clientIp=10.0.0.1")
                .retrieve()
                .body(String.class));
        Assertions.assertTrue(productNode.get("success").asBoolean());
        Assertions.assertEquals("sku-iphone", productNode.get("data").get("productId").asText());

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
        Assertions.assertEquals("WAITING_PAYMENT", orderNode.get("data").get("status").asText());

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
        Assertions.assertFalse(rejectNode.get("success").asBoolean());
        Assertions.assertEquals("stock-not-enough", rejectNode.get("message").asText());

        Thread.sleep(1100L);
        REST_CLIENT.get().uri("/api/gateway/products/sku-iphone?userId=u300&clientIp=10.0.3.1").retrieve().body(String.class);
        REST_CLIENT.get().uri("/api/gateway/products/sku-iphone?userId=u300&clientIp=10.0.3.1").retrieve().body(String.class);
        JsonNode queuedNode = parse(REST_CLIENT.get()
                .uri("/api/gateway/products/sku-iphone?userId=u300&clientIp=10.0.3.1")
                .retrieve()
                .body(String.class));
        Assertions.assertFalse(queuedNode.get("success").asBoolean());
        Assertions.assertTrue(queuedNode.get("message").asText().contains("Galaxy Mall Queue"));
    }

    private static ConfigurableApplicationContext start(Class<?> applicationClass, String port, String... extraProperties) {
        List<String> properties = new ArrayList<>();
        properties.add("server.port=" + port);
        properties.add("spring.main.lazy-initialization=false");
        properties.add("logging.level.root=ERROR");
        for (String extraProperty : extraProperties) {
            properties.add(extraProperty);
        }
        return new SpringApplicationBuilder(applicationClass)
                .run(properties.stream().map(property -> "--" + property).toArray(String[]::new));
    }

    private static JsonNode parse(String body) throws Exception {
        return OBJECT_MAPPER.readTree(body);
    }
}
