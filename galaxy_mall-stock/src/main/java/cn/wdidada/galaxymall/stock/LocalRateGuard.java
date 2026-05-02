package cn.wdidada.galaxymall.stock;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public final class LocalRateGuard {

    private final int maxPerSecond;
    private final LongSupplier currentEpochSecond;
    private final AtomicReference<WindowState> state;

    public LocalRateGuard(int maxPerSecond) {
        this(maxPerSecond, () -> System.currentTimeMillis() / 1000L);
    }

    LocalRateGuard(int maxPerSecond, LongSupplier currentEpochSecond) {
        this.maxPerSecond = maxPerSecond;
        this.currentEpochSecond = currentEpochSecond;
        this.state = new AtomicReference<>(new WindowState(currentEpochSecond.getAsLong(), 0));
    }

    public boolean tryAcquire() {
        long currentSecond = currentEpochSecond.getAsLong();
        while (true) {
            WindowState current = state.get();
            WindowState next = current.second() == currentSecond
                    ? new WindowState(currentSecond, current.counter() + 1)
                    : new WindowState(currentSecond, 1);
            if (state.compareAndSet(current, next)) {
                return next.counter() <= maxPerSecond;
            }
        }
    }

    private record WindowState(long second, int counter) {
    }
}
