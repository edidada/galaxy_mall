package cn.wdidada.galaxymall.payment;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class RefundRecordRepository {

    private final ConcurrentHashMap<String, RefundRecord> records = new ConcurrentHashMap<>();

    RefundRecord saveInitiated(String refundId, String orderId, int amountCent) {
        RefundRecord record = new RefundRecord(refundId, orderId, amountCent, RefundStatus.INITIATED);
        records.put(refundId, record);
        return record;
    }

    Optional<RefundRecord> findByRefundId(String refundId) {
        return Optional.ofNullable(records.get(refundId));
    }

    Optional<RefundRecord> latest() {
        return records.values().stream().findFirst();
    }

    void markCompleted(String refundId) {
        records.computeIfPresent(refundId, (ignored, current) -> current.withStatus(RefundStatus.COMPLETED));
    }

    void markCompensated(String refundId) {
        records.computeIfPresent(refundId, (ignored, current) -> current.withStatus(RefundStatus.COMPENSATED));
    }

    record RefundRecord(String refundId, String orderId, int amountCent, RefundStatus status) {
        RefundRecord withStatus(RefundStatus nextStatus) {
            return new RefundRecord(refundId, orderId, amountCent, nextStatus);
        }
    }

    enum RefundStatus {
        INITIATED,
        COMPLETED,
        COMPENSATED
    }
}
