package cn.wdidada.galaxymall.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class MultiLevelCache<K, V> implements AutoCloseable {

    private final Map<K, CacheValue<V>> l1 = new ConcurrentHashMap<>();
    private final Map<K, CacheValue<V>> l2 = new ConcurrentHashMap<>();
    private final Map<K, CacheValue<V>> l3 = new ConcurrentHashMap<>();
    private final Map<K, CompletableFuture<Optional<V>>> inFlightLoads = new ConcurrentHashMap<>();
    private final AtomicLong l1Hits = new AtomicLong();
    private final AtomicLong l2Hits = new AtomicLong();
    private final AtomicLong l3Hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final Clock clock;
    private final ScheduledExecutorService cleanupExecutor;

    public MultiLevelCache() {
        this(Duration.ofSeconds(30), Clock.systemUTC());
    }

    MultiLevelCache(Duration cleanupInterval, Clock clock) {
        this.clock = clock;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "galaxy-mall-cache-cleaner");
            thread.setDaemon(true);
            return thread;
        });
        long cleanupMillis = Math.max(1L, cleanupInterval.toMillis());
        this.cleanupExecutor.scheduleWithFixedDelay(
                this::cleanupExpiredEntries,
                cleanupMillis,
                cleanupMillis,
                TimeUnit.MILLISECONDS
        );
    }

    public Optional<V> get(K key) {
        Instant now = clock.instant();
        CacheValue<V> v1 = getAlive(l1, key, now);
        if (isAlive(v1)) {
            l1Hits.incrementAndGet();
            return Optional.of(v1.value());
        }

        CacheValue<V> v2 = getAlive(l2, key, now);
        if (isAlive(v2)) {
            l2Hits.incrementAndGet();
            l1.put(key, v2.shorten(Duration.ofSeconds(15), clock));
            return Optional.of(v2.value());
        }

        CacheValue<V> v3 = getAlive(l3, key, now);
        if (isAlive(v3)) {
            l3Hits.incrementAndGet();
            l2.put(key, v3.shorten(Duration.ofMinutes(5), clock));
            l1.put(key, v3.shorten(Duration.ofSeconds(15), clock));
            return Optional.of(v3.value());
        }

        misses.incrementAndGet();
        return Optional.empty();
    }

    public Optional<V> getOrLoad(K key, Supplier<Optional<V>> loader) {
        Optional<V> cached = get(key);
        if (cached.isPresent()) {
            return cached;
        }
        CompletableFuture<Optional<V>> candidate = new CompletableFuture<>();
        CompletableFuture<Optional<V>> existing = inFlightLoads.putIfAbsent(key, candidate);
        if (existing == null) {
            try {
                Optional<V> secondCheck = get(key);
                if (secondCheck.isPresent()) {
                    candidate.complete(secondCheck);
                    return secondCheck;
                }
                Optional<V> loaded = loader.get();
                loaded.ifPresent(value -> put(key, value));
                candidate.complete(loaded);
                return loaded;
            } catch (RuntimeException ex) {
                candidate.completeExceptionally(ex);
                throw ex;
            } finally {
                inFlightLoads.remove(key, candidate);
            }
        }
        return existing.join();
    }

    public void put(K key, V value) {
        put(key, value, Duration.ofSeconds(15), Duration.ofMinutes(5), Duration.ofHours(1));
    }

    public void put(K key, V value, Duration l1Ttl, Duration l2Ttl, Duration l3Ttl) {
        l1.put(key, CacheValue.of(value, l1Ttl, clock));
        l2.put(key, CacheValue.of(value, l2Ttl, clock));
        l3.put(key, CacheValue.of(value, l3Ttl, clock));
    }

    public void invalidate(K key) {
        l1.remove(key);
        l2.remove(key);
        l3.remove(key);
    }

    public CacheStats stats() {
        return new CacheStats(l1Hits.get(), l2Hits.get(), l3Hits.get(), misses.get());
    }

    @Override
    public void close() {
        cleanupExecutor.shutdownNow();
    }

    private boolean isAlive(CacheValue<V> value) {
        return value != null && value.expiresAt().isAfter(clock.instant());
    }

    private CacheValue<V> getAlive(Map<K, CacheValue<V>> cache, K key, Instant now) {
        CacheValue<V> value = cache.get(key);
        if (value != null && !value.expiresAt().isAfter(now)) {
            cache.remove(key, value);
            return null;
        }
        return value;
    }

    private void cleanupExpiredEntries() {
        Instant now = clock.instant();
        cleanupExpiredEntries(l1, now);
        cleanupExpiredEntries(l2, now);
        cleanupExpiredEntries(l3, now);
    }

    private void cleanupExpiredEntries(Map<K, CacheValue<V>> cache, Instant now) {
        cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private record CacheValue<V>(V value, Instant expiresAt) {

        private static <V> CacheValue<V> of(V value, Duration ttl, Clock clock) {
            return new CacheValue<>(value, clock.instant().plus(ttl));
        }

        private CacheValue<V> shorten(Duration ttl, Clock clock) {
            return of(value, ttl, clock);
        }
    }
}
