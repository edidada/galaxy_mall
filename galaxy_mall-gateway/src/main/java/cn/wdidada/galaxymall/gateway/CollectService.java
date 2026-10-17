package cn.wdidada.galaxymall.gateway;

import cn.wdidada.galaxymall.common.api.CollectAckView;
import cn.wdidada.galaxymall.common.api.CollectBatchRequest;
import cn.wdidada.galaxymall.common.api.CollectEvent;
import cn.wdidada.galaxymall.common.api.CollectStatsView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * 埋点事件接收器：有界环形缓冲 + 维度计数。
 *
 * <p>定位是“看得见行为流”的演示级接收端，不是数仓：
 * 事件只留在内存，容量默认 5000，写满后丢最旧的一条并计数 {@code droppedByOverflow}，
 * 网关重启即清空。要做持久化，把 {@link #accept} 的入缓冲动作换成投递
 * {@code galaxy_mall-message} 的 MQ 即可，接口形状不用变。
 *
 * <p>之所以不做逐条落库：埋点上报是按批来的（SDK 每 20 条或 2 秒 flush 一次），
 * 高峰时 QPS 远高于业务接口，任何同步 IO 都会把网关自己的令牌桶拖住，影响下单链路。
 */
public final class CollectService {

    private static final int MAX_EVENTS_PER_BATCH = 100;

    private final int capacity;
    private final Deque<CollectEvent> buffer;
    private final LongAdder totalBatches = new LongAdder();
    private final LongAdder totalEvents = new LongAdder();
    private final LongAdder droppedByOverflow = new LongAdder();
    private final LongAdder rejectedOversize = new LongAdder();
    private final Map<String, LongAdder> countByType = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> countByPage = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> countByTarget = new ConcurrentHashMap<>();
    private final long sinceEpochMillis = System.currentTimeMillis();

    public CollectService(int capacity) {
        this.capacity = Math.max(100, capacity);
        this.buffer = new ArrayDeque<>(this.capacity);
    }

    public CollectAckView accept(CollectBatchRequest request) {
        totalBatches.increment();
        List<CollectEvent> events = request == null || request.events() == null ? List.of() : request.events();
        if (events.size() > MAX_EVENTS_PER_BATCH) {
            rejectedOversize.increment();
            events = events.subList(0, MAX_EVENTS_PER_BATCH);
        }
        int accepted = 0;
        synchronized (buffer) {
            for (CollectEvent event : events) {
                if (event == null || event.type() == null) {
                    continue;
                }
                if (buffer.size() >= capacity) {
                    buffer.pollFirst();
                    droppedByOverflow.increment();
                }
                buffer.addLast(event);
                countByType.computeIfAbsent(event.type(), key -> new LongAdder()).increment();
                countByPage.computeIfAbsent(nullToDash(event.page()), key -> new LongAdder()).increment();
                countByTarget.computeIfAbsent(nullToDash(event.target()), key -> new LongAdder()).increment();
                accepted++;
            }
            totalEvents.add(accepted);
            return new CollectAckView(events.size(), accepted, droppedByOverflow.sum(), buffer.size(), System.currentTimeMillis());
        }
    }

    /** 最近的事件，按到达顺序倒序（新在前）。 */
    public List<CollectEvent> recent(int limit) {
        int size = Math.max(1, Math.min(limit, 500));
        synchronized (buffer) {
            List<CollectEvent> all = new ArrayList<>(buffer);
            List<CollectEvent> newestFirst = new ArrayList<>(size);
            for (int i = all.size() - 1; i >= 0 && newestFirst.size() < size; i--) {
                newestFirst.add(all.get(i));
            }
            return newestFirst;
        }
    }

    public CollectStatsView stats() {
        return new CollectStatsView(
                totalBatches.sum(),
                totalEvents.sum(),
                droppedByOverflow.sum() + rejectedOversize.sum(),
                bufferSize(),
                capacity,
                sinceEpochMillis,
                snapshot(countByType),
                snapshot(countByPage),
                snapshot(countByTarget)
        );
    }

    public int bufferCapacity() {
        return capacity;
    }

    private int bufferSize() {
        synchronized (buffer) {
            return buffer.size();
        }
    }

    private static Map<String, Long> snapshot(Map<String, LongAdder> source) {
        Map<String, Long> copy = new HashMap<>();
        source.forEach((key, adder) -> copy.put(key, adder.sum()));
        return copy.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(40)
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, java.util.LinkedHashMap::new));
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
