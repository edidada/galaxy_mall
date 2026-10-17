package cn.wdidada.galaxymall.common.api;

/**
 * 埋点上报回执：SDK 据此决定是否把这批事件写进本地死信队列重试。
 *
 * @param accepted 本批被接收的条数
 * @param dropped  网关侧因环形缓冲满而被丢弃的历史事件条数（不影响本批 accepted）
 */
public record CollectAckView(
        int received,
        int accepted,
        long dropped,
        int bufferSize,
        long serverTs
) {
}
