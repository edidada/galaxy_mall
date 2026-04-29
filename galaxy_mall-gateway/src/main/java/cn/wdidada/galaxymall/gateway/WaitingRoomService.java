package cn.wdidada.galaxymall.gateway;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class WaitingRoomService {

    private final int capacity;
    private final int releasePerSecond;
    private final AtomicInteger size = new AtomicInteger();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public WaitingRoomService(int capacity, int releasePerSecond) {
        this.capacity = capacity;
        this.releasePerSecond = releasePerSecond;
    }

    public WaitingTicket enter(String businessKey) {
        Ticket existing = tickets.get(businessKey);
        if (existing != null) {
            return existing.toView(position(existing.readyAtMillis));
        }

        int position = size.incrementAndGet();
        if (position > capacity) {
            size.decrementAndGet();
            return WaitingTicket.rejectedTicket();
        }
        String token = UUID.randomUUID().toString();
        long readyAtMillis = Instant.now().toEpochMilli() + Math.max(1000L, position * 1000L / releasePerSecond);
        Ticket ticket = new Ticket(token, businessKey, readyAtMillis);
        tickets.put(businessKey, ticket);
        return ticket.toView(position - 1);
    }

    public boolean canPass(String businessKey, String queueToken) {
        Ticket ticket = tickets.get(businessKey);
        if (ticket == null || queueToken == null || !ticket.token.equals(queueToken)) {
            return false;
        }
        boolean ready = Instant.now().toEpochMilli() >= ticket.readyAtMillis;
        if (ready) {
            tickets.remove(businessKey);
            size.decrementAndGet();
        }
        return ready;
    }

    private int position(long readyAtMillis) {
        long remaining = Math.max(0L, readyAtMillis - Instant.now().toEpochMilli());
        return (int) Math.ceil(remaining / 1000.0d * releasePerSecond);
    }

    private record Ticket(String token, String businessKey, long readyAtMillis) {

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
