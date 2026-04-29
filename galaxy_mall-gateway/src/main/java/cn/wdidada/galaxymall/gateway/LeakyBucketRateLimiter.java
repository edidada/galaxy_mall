package cn.wdidada.galaxymall.gateway;

import java.time.Instant;

public final class LeakyBucketRateLimiter {

    private final double capacity;
    private final double leakPerSecond;
    private double waterLevel;
    private long lastLeakMillis;

    public LeakyBucketRateLimiter(double capacity, double leakPerSecond) {
        this.capacity = capacity;
        this.leakPerSecond = leakPerSecond;
        this.lastLeakMillis = Instant.now().toEpochMilli();
    }

    public synchronized boolean allow() {
        leak();
        if (waterLevel >= capacity) {
            return false;
        }
        waterLevel += 1;
        return true;
    }

    private void leak() {
        long now = Instant.now().toEpochMilli();
        long elapsed = now - lastLeakMillis;
        if (elapsed <= 0) {
            return;
        }
        double leaked = elapsed * (leakPerSecond / 1000.0d);
        waterLevel = Math.max(0, waterLevel - leaked);
        lastLeakMillis = now;
    }
}
