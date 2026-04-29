package cn.wdidada.galaxymall.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryMessageBus {

    private final Map<String, List<MessageConsumer>> consumers = new ConcurrentHashMap<>();
    private final Queue<MallMessage> queue = new ConcurrentLinkedQueue<>();
    private final List<MallMessage> deadLetters = new CopyOnWriteArrayList<>();
    private final int maxRetry;

    public InMemoryMessageBus(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    public void register(String topic, MessageConsumer consumer) {
        consumers.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>()).add(consumer);
    }

    public void publish(String topic, String key, Object payload) {
        queue.offer(new MallMessage(topic, key, payload, 0));
    }

    public void drain() {
        while (!queue.isEmpty()) {
            MallMessage message = queue.poll();
            if (message == null) {
                continue;
            }
            try {
                for (MessageConsumer consumer : consumers.getOrDefault(message.topic(), List.of())) {
                    consumer.consume(message);
                }
            } catch (RuntimeException ex) {
                if (message.attempt() >= maxRetry) {
                    deadLetters.add(message);
                } else {
                    queue.offer(message.nextAttempt());
                }
            }
        }
    }

    public List<MallMessage> deadLetters() {
        return new ArrayList<>(deadLetters);
    }
}
