package cn.wdidada.galaxymall.common;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

public final class IdGenerator {

    private static final AtomicLong SEQUENCE = new AtomicLong(1000);

    private IdGenerator() {
    }

    public static String nextOrderId() {
        return nextBusinessId("ORD");
    }

    public static String nextSeckillRequestId() {
        return nextBusinessId("SKQ");
    }

    public static String nextBusinessId(String prefix) {
        return prefix + "-" + Instant.now().toEpochMilli() + "-" + SEQUENCE.incrementAndGet();
    }
}
