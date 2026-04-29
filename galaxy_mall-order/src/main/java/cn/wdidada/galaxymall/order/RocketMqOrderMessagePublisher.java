package cn.wdidada.galaxymall.order;

import org.apache.rocketmq.spring.core.RocketMQTemplate;

public final class RocketMqOrderMessagePublisher implements OrderMessagePublisher {

    private final RocketMQTemplate rocketMQTemplate;
    private final String topic;

    public RocketMqOrderMessagePublisher(RocketMQTemplate rocketMQTemplate, String topic) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(OrderSubmittedEvent event) {
        rocketMQTemplate.syncSend(topic, event);
    }
}
