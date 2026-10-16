package cn.wdidada.galaxymall.common.tracing;

import cn.wdidada.galaxymall.common.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 下游 Spring MVC（servlet）服务的统一追踪过滤器：
 * <ol>
 *   <li>从请求头 {@link TraceContext#HEADER} 读取 traceId，缺失则生成；</li>
 *   <li>写入 {@link TraceContext} 与 {@link MDC}，使本进程日志带 traceId；</li>
 *   <li>在响应头回写 traceId（便于前端/网关核对）；</li>
 *   <li>请求结束后清理，避免线程复用串号。</li>
 * </ol>
 *
 * <p>通过 {@code TracingAutoConfiguration} + {@code AutoConfiguration.imports} 自动注册到所有依赖 common 的 servlet 服务。
 */
public class TraceServletFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        String traceId = request.getHeader(TraceContext.HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        TraceContext.set(traceId);
        MDC.put("traceId", traceId);
        response.setHeader(TraceContext.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            TraceContext.clear();
        }
    }
}
