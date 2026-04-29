package cn.wdidada.galaxymall.common;

import java.util.UUID;

public final class TraceContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    public static String start() {
        String traceId = UUID.randomUUID().toString();
        TRACE_ID.set(traceId);
        return traceId;
    }

    public static String getOrCreate() {
        String traceId = TRACE_ID.get();
        return traceId == null ? start() : traceId;
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
