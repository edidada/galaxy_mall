package cn.wdidada.galaxymall.gateway;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class WindowRateLimiter {

    private final int threshold;
    private final Map<String, AtomicLong> seconds = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    public WindowRateLimiter(int threshold) {
        this.threshold = threshold;
    }

    public boolean allow(String key) {
        long now = Instant.now().getEpochSecond();
        AtomicLong second = seconds.computeIfAbsent(key, ignored -> new AtomicLong(now));
        AtomicInteger counter = counters.computeIfAbsent(key, ignored -> new AtomicInteger());
        long previous = second.get();
        if (previous != now && second.compareAndSet(previous, now)) {
            counter.set(0);
        }
        return counter.incrementAndGet() <= threshold;
    }
}
