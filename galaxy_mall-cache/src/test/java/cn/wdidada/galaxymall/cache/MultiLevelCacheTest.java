package cn.wdidada.galaxymall.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MultiLevelCacheTest {

    @Test
    void getOrLoadUsesSingleFlightForConcurrentMisses() throws Exception {
        MutableClock clock = new MutableClock();
        try (MultiLevelCache<String, String> cache = new MultiLevelCache<>(Duration.ofDays(1), clock)) {
            AtomicInteger loads = new AtomicInteger();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Optional<String>> first = executor.submit(() -> cache.getOrLoad("sku-1", () -> {
                    loads.incrementAndGet();
                    started.countDown();
                    await(release);
                    return Optional.of("value");
                }));
                assertTrue(started.await(1, TimeUnit.SECONDS));
                Future<Optional<String>> second = executor.submit(() -> cache.getOrLoad("sku-1", Optional::empty));

                release.countDown();

                assertEquals(Optional.of("value"), first.get(1, TimeUnit.SECONDS));
                assertEquals(Optional.of("value"), second.get(1, TimeUnit.SECONDS));
                assertEquals(1, loads.get());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void expiredEntriesAreRemovedOnRead() {
        MutableClock clock = new MutableClock();
        try (MultiLevelCache<String, String> cache = new MultiLevelCache<>(Duration.ofDays(1), clock)) {
            cache.put(
                    "sku-1",
                    "value",
                    Duration.ofMillis(100),
                    Duration.ofMillis(100),
                    Duration.ofMillis(100)
            );
            assertTrue(cache.get("sku-1").isPresent());

            clock.plusMillis(150L);
            assertFalse(cache.get("sku-1").isPresent());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timeout");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant = Instant.EPOCH;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void plusMillis(long millis) {
            instant = instant.plusMillis(millis);
        }
    }
}
