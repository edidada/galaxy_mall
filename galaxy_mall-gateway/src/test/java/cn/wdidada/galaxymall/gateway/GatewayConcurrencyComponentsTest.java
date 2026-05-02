package cn.wdidada.galaxymall.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GatewayConcurrencyComponentsTest {

    @Test
    void tokenBucketRefillsAfterElapsedTime() {
        AtomicLong now = new AtomicLong(0L);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 2, now::get);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        now.addAndGet(500L);
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void windowRateLimiterResetsOnNextSecond() {
        AtomicLong second = new AtomicLong(100L);
        WindowRateLimiter limiter = new WindowRateLimiter(2, second::get);

        assertTrue(limiter.allow("u1"));
        assertTrue(limiter.allow("u1"));
        assertFalse(limiter.allow("u1"));

        second.incrementAndGet();
        assertTrue(limiter.allow("u1"));
        assertTrue(limiter.allow("u1"));
        assertFalse(limiter.allow("u1"));
    }

    @Test
    void waitingRoomCleansExpiredTicketsAndReusesCapacity() {
        AtomicLong now = new AtomicLong(0L);
        try (WaitingRoomService waitingRoom = new WaitingRoomService(
                1,
                1,
                now::get,
                Duration.ofSeconds(1),
                Duration.ofDays(1)
        )) {
            WaitingRoomService.WaitingTicket first = waitingRoom.enter("order-u1");
            assertNotNull(first.token());
            assertTrue(waitingRoom.enter("order-u2").rejected());

            now.set(2_500L);
            WaitingRoomService.WaitingTicket second = waitingRoom.enter("order-u2");
            assertFalse(second.rejected());
            assertNotNull(second.token());
        }
    }
}
