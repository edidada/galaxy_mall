package cn.wdidada.galaxymall.common.tracing;

import cn.wdidada.galaxymall.common.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p><b>为什么必须打访问日志</b>：本项目各服务默认不打印任何按请求的日志，
 * 若只有 MDC 而没有日志输出，traceId 就无从观察，链路追踪等于没做。
 * 这里用独立 logger {@value #TRACE_LOGGER_NAME} 输出一条带状态码与耗时的访问日志，
 * 级别可在不重新构建镜像的前提下用环境变量 {@code GALAXY_TRACE_LEVEL} 调整
 * （压测时设为 {@code OFF} 可完全关闭，见 logback-spring.xml）。
 */
public class TraceServletFilter extends OncePerRequestFilter {

    public static final String TRACE_LOGGER_NAME = "galaxy.trace";

    private static final Logger TRACE_LOG = LoggerFactory.getLogger(TRACE_LOGGER_NAME);

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

        long began = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            // 注意：日志必须在清理 MDC 之前输出，否则这条日志里的 trace 字段会退化成 NO
            if (TRACE_LOG.isInfoEnabled()) {
                TRACE_LOG.info("{} {} status={} {}ms",
                        request.getMethod(),
                        request.getRequestURI(),
                        response.getStatus(),
                        elapsedMillis(began));
            }
            MDC.remove("traceId");
            TraceContext.clear();
        }
    }

    private static long elapsedMillis(long beganNanos) {
        return (System.nanoTime() - beganNanos) / 1_000_000L;
    }
}
