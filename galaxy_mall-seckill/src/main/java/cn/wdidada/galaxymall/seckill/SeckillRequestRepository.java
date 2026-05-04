package cn.wdidada.galaxymall.seckill;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

final class SeckillRequestRepository {

    private final ConcurrentHashMap<String, SeckillRequestSnapshot> requests = new ConcurrentHashMap<>();

    void saveAccepted(SeckillRequestSnapshot snapshot) {
        requests.put(snapshot.requestId(), snapshot);
    }

    Optional<SeckillRequestSnapshot> findById(String requestId) {
        return Optional.ofNullable(requests.get(requestId));
    }

    List<SeckillRequestSnapshot> findRefreshCandidates() {
        List<SeckillRequestSnapshot> candidates = new ArrayList<>();
        for (SeckillRequestSnapshot snapshot : requests.values()) {
            if (!snapshot.terminal() && snapshot.orderId() != null) {
                candidates.add(snapshot);
            }
        }
        return candidates;
    }

    void cleanupTerminal(long retentionMillis) {
        long now = System.currentTimeMillis();
        requests.entrySet().removeIf(entry ->
                entry.getValue().terminal() && now - entry.getValue().updatedEpochMillis() >= retentionMillis
        );
    }

    void markProcessing(String requestId) {
        transition(requestId, current -> withState(current, SeckillStatus.PROCESSING, current.orderId(), current.orderStatus(), "seckill-processing"));
    }

    void markSubmitted(String requestId, String orderId, String orderStatus, String detail) {
        transition(requestId, current -> withState(current, SeckillStatus.SUBMITTED, orderId, orderStatus, detail));
    }

    void markSuccess(String requestId, String orderId, String orderStatus, String detail) {
        transition(requestId, current -> withState(current, SeckillStatus.SUCCESS, orderId, orderStatus, detail));
    }

    void markFailed(String requestId, String detail) {
        transition(requestId, current -> withState(current, SeckillStatus.FAILED, current.orderId(), current.orderStatus(), detail));
    }

    private void transition(String requestId, UnaryOperator<SeckillRequestSnapshot> transition) {
        requests.computeIfPresent(requestId, (ignored, current) -> transition.apply(current));
    }

    private SeckillRequestSnapshot withState(
            SeckillRequestSnapshot current,
            SeckillStatus status,
            String orderId,
            String orderStatus,
            String detail
    ) {
        return new SeckillRequestSnapshot(
                current.requestId(),
                orderId,
                current.userId(),
                current.productId(),
                current.quantity(),
                current.clientIp(),
                status,
                orderStatus,
                detail,
                current.acceptedEpochMillis(),
                System.currentTimeMillis()
        );
    }
}
