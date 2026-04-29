package cn.wdidada.galaxymall.stock;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class LocalRateGuard {

    private final int maxPerSecond;
    private final AtomicLong secondWindow = new AtomicLong(Instant.now().getEpochSecond());
    private final AtomicInteger counter = new AtomicInteger();

    public LocalRateGuard(int maxPerSecond) {
        this.maxPerSecond = maxPerSecond;
    }

    public boolean tryAcquire() {
        long currentSecond = Instant.now().getEpochSecond();
        long observed = secondWindow.get();
        if (observed != currentSecond && secondWindow.compareAndSet(observed, currentSecond)) {
            counter.set(0);
        }
        return counter.incrementAndGet() <= maxPerSecond;
    }
}
