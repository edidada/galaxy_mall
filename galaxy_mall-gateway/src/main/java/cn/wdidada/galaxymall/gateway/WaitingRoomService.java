package cn.wdidada.galaxymall.gateway;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

public final class WaitingRoomService implements AutoCloseable {

    private final int capacity;
    private final int releasePerSecond;
    private final long ticketRetentionMillis;
    private final LongSupplier currentTimeMillis;
    private final AtomicInteger size = new AtomicInteger();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledExecutorService cleanupExecutor;

    public WaitingRoomService(int capacity, int releasePerSecond) {
        this(capacity, releasePerSecond, System::currentTimeMillis, Duration.ofSeconds(30), Duration.ofSeconds(5));
    }

    WaitingRoomService(
            int capacity,
            int releasePerSecond,
            LongSupplier currentTimeMillis,
            Duration ticketRetention,
            Duration cleanupInterval
    ) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (releasePerSecond <= 0) {
            throw new IllegalArgumentException("releasePerSecond must be positive");
        }
        this.capacity = capacity;
        this.releasePerSecond = releasePerSecond;
        this.ticketRetentionMillis = Math.max(1000L, ticketRetention.toMillis());
        this.currentTimeMillis = currentTimeMillis;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "galaxy-mall-waiting-room-cleaner");
            thread.setDaemon(true);
            return thread;
        });
        long cleanupMillis = Math.max(1L, cleanupInterval.toMillis());
        this.cleanupExecutor.scheduleWithFixedDelay(
                this::cleanupExpiredTickets,
                cleanupMillis,
                cleanupMillis,
                TimeUnit.MILLISECONDS
        );
    }

    public WaitingTicket enter(String businessKey) {
        lock.lock();
        try {
            long now = currentTimeMillis.getAsLong();
            cleanupExpiredTickets(now);
            Ticket existing = tickets.get(businessKey);
            if (existing != null) {
                return existing.toView(position(existing.readyAtMillis, now));
            }

            int nextPosition = size.get() + 1;
            if (nextPosition > capacity) {
                return WaitingTicket.rejectedTicket();
            }
            String token = UUID.randomUUID().toString();
            long readyAtMillis = now + Math.max(1000L, nextPosition * 1000L / releasePerSecond);
            Ticket ticket = new Ticket(token, businessKey, readyAtMillis, readyAtMillis + ticketRetentionMillis);
            tickets.put(businessKey, ticket);
            size.incrementAndGet();
            return ticket.toView(nextPosition - 1);
        } finally {
            lock.unlock();
        }
    }

    public boolean canPass(String businessKey, String queueToken) {
        lock.lock();
        try {
            long now = currentTimeMillis.getAsLong();
            cleanupExpiredTickets(now);
            Ticket ticket = tickets.get(businessKey);
            if (ticket == null || queueToken == null || !ticket.token.equals(queueToken)) {
                return false;
            }
            boolean ready = now >= ticket.readyAtMillis;
            if (ready) {
                removeTicket(businessKey, ticket);
            }
            return ready;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        cleanupExecutor.shutdownNow();
    }

    private void cleanupExpiredTickets() {
        lock.lock();
        try {
            cleanupExpiredTickets(currentTimeMillis.getAsLong());
        } finally {
            lock.unlock();
        }
    }

    private void cleanupExpiredTickets(long now) {
        tickets.forEach((businessKey, ticket) -> {
            if (ticket.expireAtMillis <= now) {
                removeTicket(businessKey, ticket);
            }
        });
    }

    private void removeTicket(String businessKey, Ticket ticket) {
        if (tickets.remove(businessKey, ticket)) {
            size.decrementAndGet();
        }
    }

    private int position(long readyAtMillis, long now) {
        long remaining = Math.max(0L, readyAtMillis - now);
        return (int) Math.ceil(remaining / 1000.0d * releasePerSecond);
    }

    private record Ticket(String token, String businessKey, long readyAtMillis, long expireAtMillis) {

        private WaitingTicket toView(int ahead) {
            return new WaitingTicket(token, ahead, readyAtMillis, false);
        }
    }

    public record WaitingTicket(String token, int ahead, long readyAtMillis, boolean rejected) {

        private static WaitingTicket rejectedTicket() {
            return new WaitingTicket(null, -1, 0L, true);
        }
    }
}
