package cn.wdidada.galaxymall.monitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class MetricsCollector {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> latencies = new ConcurrentHashMap<>();

    public void increment(String metric) {
        counters.computeIfAbsent(metric, ignored -> new AtomicLong()).incrementAndGet();
    }

    public void recordLatency(String metric, long millis) {
        latencies.computeIfAbsent(metric, ignored -> new CopyOnWriteArrayList<>()).add(millis);
    }

    public long counter(String metric) {
        return counters.getOrDefault(metric, new AtomicLong()).get();
    }

    public List<Long> latencySnapshot(String metric) {
        return Collections.unmodifiableList(new ArrayList<>(latencies.getOrDefault(metric, List.of())));
    }

    public Map<String, Long> counterSnapshot() {
        Map<String, Long> snapshot = new ConcurrentHashMap<>();
        counters.forEach((key, value) -> snapshot.put(key, value.get()));
        return snapshot;
    }
}
