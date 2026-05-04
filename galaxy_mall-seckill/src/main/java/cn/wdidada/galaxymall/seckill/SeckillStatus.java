package cn.wdidada.galaxymall.seckill;

enum SeckillStatus {
    ACCEPTED,
    PROCESSING,
    SUBMITTED,
    SUCCESS,
    FAILED;

    boolean terminal() {
        return this == SUCCESS || this == FAILED;
    }
}
