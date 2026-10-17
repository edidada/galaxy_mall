package cn.wdidada.galaxymall.gateway;

import cn.wdidada.galaxymall.common.TraceContext;
import cn.wdidada.galaxymall.common.tracing.TraceServletFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
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
 *
 * <p><b>响应式陷阱（踩过坑）</b>：WebFlux 里 {@code chain.filter(exchange)} 只是<b>组装</b> Mono，
 * 真正的请求处理要等订阅后才异步执行。早期写法是
 * {@code try { return chain.filter(exchange); } finally { MDC.remove(...); }} —— 那是<strong>错的</strong>：
 * {@code finally} 会在返回的瞬间执行，还没等请求真正处理就把 traceId 清掉了，
 * 结果①网关自身日志没有 traceId，②下游 RestClient 拦截器执行时读到 null，
 * <b>traceId 根本传不出网关</b>。
 * 正确做法是包在 {@code Mono.defer} 里：让「写入 MDC」发生在真正执行时，
 * 清理放到 {@code doFinally}（请求真正结束时），两者都在同一个执行上下文。
 */
@Configuration
public class GatewayTracingConfig {

    private static final Logger TRACE_LOG = LoggerFactory.getLogger(TraceServletFilter.TRACE_LOGGER_NAME);

    @Bean
    @Order(-100)
    public WebFilter traceWebFilter() {
        return (exchange, chain) -> Mono.defer(() -> {
            String traceId = exchange.getRequest().getHeaders().getFirst(TraceContext.HEADER);
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString();
            }
            TraceContext.set(traceId);
            MDC.put("traceId", traceId);
            exchange.getResponse().getHeaders().add(TraceContext.HEADER, traceId);

            long began = System.nanoTime();
            return chain.filter(exchange)
                    .doFinally(signal -> {
                        if (TRACE_LOG.isInfoEnabled()) {
                            HttpStatusCode status = exchange.getResponse().getStatusCode();
                            TRACE_LOG.info("{} {} status={} {}ms",
                                    exchange.getRequest().getMethod(),
                                    exchange.getRequest().getURI().getPath(),
                                    status == null ? "unknown" : Integer.toString(status.value()),
                                    (System.nanoTime() - began) / 1_000_000L);
                        }
                        MDC.remove("traceId");
                        TraceContext.clear();
                    });
        });
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
