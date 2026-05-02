package cn.wdidada.galaxymall.db;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;

public final class LocalBinlogRepository {

    private final AtomicLong nextId = new AtomicLong();
    private final List<BinlogEvent> events = new CopyOnWriteArrayList<>();

    public void append(String event) {
        events.add(new BinlogEvent(nextId.incrementAndGet(), event));
    }

    public List<String> snapshot() {
        List<String> snapshot = new ArrayList<>(events.size());
        for (BinlogEvent event : events) {
            snapshot.add(event.payload());
        }
        return snapshot;
    }

    public Optional<ClaimedEvent> claimFirst(String prefix) {
        for (BinlogEvent event : events) {
            if (event.isClaimable(prefix)) {
                return Optional.of(new ClaimedEvent(event.id(), event.payload()));
            }
        }
        return Optional.empty();
    }

    public void ack(long eventId) {
        events.removeIf(event -> event.id() == eventId);
    }

    public void release(long eventId) {
        for (BinlogEvent event : events) {
            if (event.id() == eventId) {
                event.release();
                return;
            }
        }
    }

    public record ClaimedEvent(long id, String payload) {
    }

    private record BinlogEvent(long id, String payload, AtomicReference<Status> status) {

        private BinlogEvent(long id, String payload) {
            this(id, payload, new AtomicReference<>(Status.PENDING));
        }

        private boolean isClaimable(String prefix) {
            return payload.startsWith(prefix) && status.compareAndSet(Status.PENDING, Status.IN_PROGRESS);
        }

        private void release() {
            status.compareAndSet(Status.IN_PROGRESS, Status.PENDING);
        }
    }

    private enum Status {
        PENDING,
        IN_PROGRESS
    }
}
