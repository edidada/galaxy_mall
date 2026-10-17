package cn.wdidada.galaxymall.common.tracing;

import cn.wdidada.galaxymall.common.TraceContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;

/**
 * Feign 请求拦截器：把当前 traceId（来自 MDC 或 {@link TraceContext}）注入到下游请求的
 * {@link TraceContext#HEADER} 头，从而让「服务 A → 服务 B（Feign）」的链路不断。
 *
 * <p>作为 {@code RequestInterceptor} Bean 自动被 OpenFeign 收集，无需每个服务单独配置。
 */
public class TraceFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String traceId = MDC.get("traceId");
        if (traceId == null) {
            traceId = TraceContext.current();
        }
        if (traceId != null) {
            template.header(TraceContext.HEADER, traceId);
        }
    }
}
