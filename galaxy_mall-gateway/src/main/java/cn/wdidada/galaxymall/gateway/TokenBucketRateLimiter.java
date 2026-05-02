package cn.wdidada.galaxymall.gateway;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public final class TokenBucketRateLimiter {

    private static final long TOKEN_SCALE = 1_000L;

    private final long refillPerSecond;
    private final long capacityMicros;
    private final LongSupplier currentTimeMillis;
    private final AtomicReference<BucketState> state;

    public TokenBucketRateLimiter(long capacity, long refillPerSecond) {
        this(capacity, refillPerSecond, System::currentTimeMillis);
    }

    TokenBucketRateLimiter(long capacity, long refillPerSecond, LongSupplier currentTimeMillis) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (refillPerSecond <= 0) {
            throw new IllegalArgumentException("refillPerSecond must be positive");
        }
        this.refillPerSecond = refillPerSecond;
        this.capacityMicros = capacity * TOKEN_SCALE;
        this.currentTimeMillis = currentTimeMillis;
        this.state = new AtomicReference<>(new BucketState(capacityMicros, currentTimeMillis.getAsLong()));
    }

    public boolean tryAcquire() {
        long now = currentTimeMillis.getAsLong();
        while (true) {
            BucketState current = state.get();
            BucketState refilled = refill(current, now);
            if (refilled.availableMicros() < TOKEN_SCALE) {
                if (refilled == current || state.compareAndSet(current, refilled)) {
                    return false;
                }
                continue;
            }
            BucketState updated = new BucketState(refilled.availableMicros() - TOKEN_SCALE, refilled.lastRefillMillis());
            if (state.compareAndSet(current, updated)) {
                return true;
            }
        }
    }

    private BucketState refill(BucketState current, long now) {
        long elapsed = now - current.lastRefillMillis();
        if (elapsed <= 0) {
            return current;
        }
        long refillMicros = elapsed * refillPerSecond * TOKEN_SCALE / 1000L;
        if (refillMicros <= 0) {
            return current;
        }
        long availableMicros = Math.min(capacityMicros, current.availableMicros() + refillMicros);
        return new BucketState(availableMicros, now);
    }

    private record BucketState(long availableMicros, long lastRefillMillis) {
    }
}
