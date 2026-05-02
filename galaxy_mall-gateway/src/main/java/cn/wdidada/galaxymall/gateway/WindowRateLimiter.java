package cn.wdidada.galaxymall.gateway;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public final class WindowRateLimiter {

    private final int threshold;
    private final LongSupplier currentEpochSecond;
    private final Map<String, AtomicReference<WindowState>> windows = new ConcurrentHashMap<>();

    public WindowRateLimiter(int threshold) {
        this(threshold, () -> System.currentTimeMillis() / 1000L);
    }

    WindowRateLimiter(int threshold, LongSupplier currentEpochSecond) {
        this.threshold = threshold;
        this.currentEpochSecond = currentEpochSecond;
    }

    public boolean allow(String key) {
        long now = currentEpochSecond.getAsLong();
        AtomicReference<WindowState> window = windows.computeIfAbsent(
                key,
                ignored -> new AtomicReference<>(new WindowState(now, 0))
        );
        while (true) {
            WindowState current = window.get();
            WindowState next = current.second() == now
                    ? new WindowState(now, current.counter() + 1)
                    : new WindowState(now, 1);
            if (window.compareAndSet(current, next)) {
                return next.counter() <= threshold;
            }
        }
    }

    private record WindowState(long second, int counter) {
    }
}
