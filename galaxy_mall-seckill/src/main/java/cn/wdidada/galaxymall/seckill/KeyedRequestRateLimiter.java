package cn.wdidada.galaxymall.seckill;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

final class KeyedRequestRateLimiter {

    private final int maxPerSecond;
    private final LongSupplier currentEpochSecond;
    private final ConcurrentHashMap<String, AtomicReference<WindowState>> states = new ConcurrentHashMap<>();
    private final AtomicLong operations = new AtomicLong();

    KeyedRequestRateLimiter(int maxPerSecond) {
        this(maxPerSecond, () -> System.currentTimeMillis() / 1000L);
    }

    KeyedRequestRateLimiter(int maxPerSecond, LongSupplier currentEpochSecond) {
        this.maxPerSecond = maxPerSecond;
        this.currentEpochSecond = currentEpochSecond;
    }

    boolean tryAcquire(String key) {
        long currentSecond = currentEpochSecond.getAsLong();
        AtomicReference<WindowState> state = states.computeIfAbsent(
                key,
                ignored -> new AtomicReference<>(new WindowState(currentSecond, 0))
        );
        while (true) {
            WindowState current = state.get();
            WindowState next = current.second() == currentSecond
                    ? new WindowState(currentSecond, current.counter() + 1)
                    : new WindowState(currentSecond, 1);
            if (state.compareAndSet(current, next)) {
                if ((operations.incrementAndGet() & 1023L) == 0L) {
                    cleanup(currentSecond);
                }
                return next.counter() <= maxPerSecond;
            }
        }
    }

    private void cleanup(long currentSecond) {
        states.entrySet().removeIf(entry -> entry.getValue().get().second() < currentSecond - 2);
    }

    private record WindowState(long second, int counter) {
    }
}
