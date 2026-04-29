package cn.wdidada.galaxymall.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class MultiLevelCache<K, V> {

    private final Map<K, CacheValue<V>> l1 = new ConcurrentHashMap<>();
    private final Map<K, CacheValue<V>> l2 = new ConcurrentHashMap<>();
    private final Map<K, CacheValue<V>> l3 = new ConcurrentHashMap<>();
    private final AtomicLong l1Hits = new AtomicLong();
    private final AtomicLong l2Hits = new AtomicLong();
    private final AtomicLong l3Hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public Optional<V> get(K key) {
        CacheValue<V> v1 = l1.get(key);
        if (isAlive(v1)) {
            l1Hits.incrementAndGet();
            return Optional.of(v1.value());
        }

        CacheValue<V> v2 = l2.get(key);
        if (isAlive(v2)) {
            l2Hits.incrementAndGet();
            l1.put(key, v2.shorten(Duration.ofSeconds(15)));
            return Optional.of(v2.value());
        }

        CacheValue<V> v3 = l3.get(key);
        if (isAlive(v3)) {
            l3Hits.incrementAndGet();
            l2.put(key, v3.shorten(Duration.ofMinutes(5)));
            l1.put(key, v3.shorten(Duration.ofSeconds(15)));
            return Optional.of(v3.value());
        }

        misses.incrementAndGet();
        return Optional.empty();
    }

    public void put(K key, V value) {
        put(key, value, Duration.ofSeconds(15), Duration.ofMinutes(5), Duration.ofHours(1));
    }

    public void put(K key, V value, Duration l1Ttl, Duration l2Ttl, Duration l3Ttl) {
        l1.put(key, CacheValue.of(value, l1Ttl));
        l2.put(key, CacheValue.of(value, l2Ttl));
        l3.put(key, CacheValue.of(value, l3Ttl));
    }

    public void invalidate(K key) {
        l1.remove(key);
        l2.remove(key);
        l3.remove(key);
    }

    public CacheStats stats() {
        return new CacheStats(l1Hits.get(), l2Hits.get(), l3Hits.get(), misses.get());
    }

    private boolean isAlive(CacheValue<V> value) {
        return value != null && value.expiresAt().isAfter(Instant.now());
    }

    private record CacheValue<V>(V value, Instant expiresAt) {

        private static <V> CacheValue<V> of(V value, Duration ttl) {
            return new CacheValue<>(value, Instant.now().plus(ttl));
        }

        private CacheValue<V> shorten(Duration ttl) {
            return of(value, ttl);
        }
    }
}
