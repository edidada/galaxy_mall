package cn.wdidada.galaxymall.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRouteConfiguration {

    @Bean
    RouteLocator gatewayRouteLocator(
            RouteLocatorBuilder builder,
            @Value("${services.user.url}") String userUrl,
            @Value("${services.product.url}") String productUrl,
            @Value("${services.seckill.url}") String seckillUrl,
            @Value("${services.stock.url}") String stockUrl,
            @Value("${services.order.url}") String orderUrl,
            @Value("${services.payment.url}") String paymentUrl
    ) {
        return builder.routes()
                .route("user-edge", r -> r.path("/edge/users/**")
                        .filters(f -> f.rewritePath("/edge/users/(?<path>.*)", "/api/users/${path}"))
                        .uri(userUrl))
                .route("product-edge", r -> r.path("/edge/products/**")
                        .filters(f -> f.rewritePath("/edge/products/(?<path>.*)", "/api/products/${path}"))
                        .uri(productUrl))
                .route("seckill-edge", r -> r.path("/edge/seckill/**")
                        .filters(f -> f.rewritePath("/edge/seckill/(?<path>.*)", "/api/seckill/${path}"))
                        .uri(seckillUrl))
                .route("stock-edge", r -> r.path("/edge/stocks/**")
                        .filters(f -> f.rewritePath("/edge/stocks/(?<path>.*)", "/api/stocks/${path}"))
                        .uri(stockUrl))
                .route("order-edge", r -> r.path("/edge/orders/**")
                        .filters(f -> f.rewritePath("/edge/orders/(?<path>.*)", "/api/orders/${path}"))
                        .uri(orderUrl))
                .route("payment-edge", r -> r.path("/edge/payments/**")
                        .filters(f -> f.rewritePath("/edge/payments/(?<path>.*)", "/api/payments/${path}"))
                        .uri(paymentUrl))
                .build();
    }
}
