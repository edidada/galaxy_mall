package cn.wdidada.galaxymall.gateway;

import cn.wdidada.galaxymall.common.TraceContext;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 网关（WebFlux）侧的追踪配置。
 *
 * <p>注意：原 {@code TraceGlobalFilter} 是 Spring Cloud Gateway 的 {@code GlobalFilter}，
 * 只对 SCG 路由的请求生效；而本网关实际是用 {@code @RestController} + {@code RestClient} 处理请求，
 * 所以那个过滤器并不在请求链路上。这里改用 WebFlux 的 {@link WebFilter} 才是真正生效的注入点。
 *
 * <ol>
 *   <li>{@link #traceWebFilter()}：从请求头读/生成 traceId，写入 {@link TraceContext} 与 MDC，并回写响应头；</li>
 *   <li>{@link #traceRestClientInterceptor()}：让网关用 {@code RestClient} 调用下游时带上 traceId 头。</li>
 * </ol>
 */
@Configuration
public class GatewayTracingConfig {

    @Bean
    @Order(-100)
    public WebFilter traceWebFilter() {
        return (exchange, chain) -> {
            String traceId = exchange.getRequest().getHeaders().getFirst(TraceContext.HEADER);
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString();
            }
            TraceContext.set(traceId);
            MDC.put("traceId", traceId);
            exchange.getResponse().getHeaders().add(TraceContext.HEADER, traceId);
            try {
                return chain.filter(exchange);
            } finally {
                MDC.remove("traceId");
                TraceContext.clear();
            }
        };
    }

    @Bean
    public ClientHttpRequestInterceptor traceRestClientInterceptor() {
        return (request, body, execution) -> {
            String traceId = TraceContext.current();
            if (traceId == null) {
                traceId = MDC.get("traceId");
            }
            if (traceId != null) {
                request.getHeaders().add(TraceContext.HEADER, traceId);
            }
            return execution.execute(request, body);
        };
    }
}
