package cn.wdidada.galaxymall.common;

import java.util.UUID;

/**
 * 全链路追踪的 ThreadLocal 载体。
 *
 * <p>约定：HTTP 头 {@value #HEADER} 在「前端 → 网关 → 各微服务 → Feign 下游」之间透传，
 * 每个进程用本类把当前 traceId 暂存到线程上下文，并同步到 SLF4J {@code MDC}（日志 pattern 里用 {@code %X{traceId}} 输出）。
 * WebFlux 网关因为线程切换不保证 MDC 可用，所以网关侧额外用本 ThreadLocal 兜底。
 */
public final class TraceContext {

    public static final String HEADER = "X-Trace-Id";

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    /** 新开一条链路，生成并返回 traceId。 */
    public static String start() {
        String traceId = UUID.randomUUID().toString();
        TRACE_ID.set(traceId);
        return traceId;
    }

    /** 已有则复用，没有则新建。 */
    public static String getOrCreate() {
        String traceId = TRACE_ID.get();
        return traceId == null ? start() : traceId;
    }

    /** 取当前 traceId，不存在返回 {@code null}（不自动创建）。 */
    public static String current() {
        return TRACE_ID.get();
    }

    public static void set(String id) {
        TRACE_ID.set(id);
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
