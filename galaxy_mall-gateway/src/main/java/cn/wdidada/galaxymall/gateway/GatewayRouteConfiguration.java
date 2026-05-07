package cn.wdidada.galaxymall.gateway;

import cn.wdidada.galaxymall.common.client.GalaxyServiceNames;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRouteConfiguration {

    @Bean
    RouteLocator gatewayRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("user-edge", r -> r.path("/edge/users/**")
                        .filters(f -> f.rewritePath("/edge/users/(?<path>.*)", "/api/users/${path}"))
                        .uri(GalaxyServiceNames.USER_LB_URI))
                .route("product-edge", r -> r.path("/edge/products/**")
                        .filters(f -> f.rewritePath("/edge/products/(?<path>.*)", "/api/products/${path}"))
                        .uri(GalaxyServiceNames.PRODUCT_LB_URI))
                .route("seckill-edge", r -> r.path("/edge/seckill/**")
                        .filters(f -> f.rewritePath("/edge/seckill/(?<path>.*)", "/api/seckill/${path}"))
                        .uri(GalaxyServiceNames.SECKILL_LB_URI))
                .route("stock-edge", r -> r.path("/edge/stocks/**")
                        .filters(f -> f.rewritePath("/edge/stocks/(?<path>.*)", "/api/stocks/${path}"))
                        .uri(GalaxyServiceNames.STOCK_LB_URI))
                .route("order-edge", r -> r.path("/edge/orders/**")
                        .filters(f -> f.rewritePath("/edge/orders/(?<path>.*)", "/api/orders/${path}"))
                        .uri(GalaxyServiceNames.ORDER_LB_URI))
                .route("payment-edge", r -> r.path("/edge/payments/**")
                        .filters(f -> f.rewritePath("/edge/payments/(?<path>.*)", "/api/payments/${path}"))
                        .uri(GalaxyServiceNames.PAYMENT_LB_URI))
                .build();
    }
}
