package cn.wdidada.galaxymall.gateway;

import java.time.Instant;

public final class TokenBucketRateLimiter {

    private final long capacity;
    private final long refillPerSecond;
    private double tokens;
    private long lastRefillMillis;

    public TokenBucketRateLimiter(long capacity, long refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokens = capacity;
        this.lastRefillMillis = Instant.now().toEpochMilli();
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens < 1) {
            return false;
        }
        tokens -= 1;
        return true;
    }

    private void refill() {
        long now = Instant.now().toEpochMilli();
        long elapsed = now - lastRefillMillis;
        if (elapsed <= 0) {
            return;
        }
        double refillTokens = elapsed * (refillPerSecond / 1000.0d);
        tokens = Math.min(capacity, tokens + refillTokens);
        lastRefillMillis = now;
    }
}
