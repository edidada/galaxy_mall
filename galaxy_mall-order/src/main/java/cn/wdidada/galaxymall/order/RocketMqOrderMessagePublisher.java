package cn.wdidada.galaxymall.order;

import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;

public final class RocketMqOrderMessagePublisher implements OrderMessagePublisher {

    private final DefaultMQProducer producer;
    private final String topic;

    public RocketMqOrderMessagePublisher(DefaultMQProducer producer, String topic) {
        this.producer = producer;
        this.topic = topic;
    }

    @Override
    public void publish(OrderSubmittedEvent event) {
        try {
            Message message = new Message(topic, event.orderId().getBytes(StandardCharsets.UTF_8));
            SendResult sendResult = producer.send(message);
            if (sendResult == null) {
                throw new IllegalStateException("rocketmq-send-result-null");
            }
        }
        catch (Exception e) {
            throw new IllegalStateException("rocketmq-send-failed", e);
        }
    }
}
