package cn.wdidada.galaxymall.common.domain;

public enum OrderStatus {
    SUBMITTED,
    QUEUED,
    PROCESSING,
    STOCK_RESERVED,
    WAITING_PAYMENT,
    PAID,
    REFUNDED,
    FAILED,
    REJECTED
}
