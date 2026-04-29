package cn.wdidada.galaxymall.order;

import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "galaxy.order.messaging-backend", havingValue = "rocketmq")
@RocketMQMessageListener(
        topic = "${galaxy.order.rocketmq.topic}",
        consumerGroup = "${galaxy.order.rocketmq.consumer-group}",
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING
)
public class RocketMqOrderSubmittedListener implements RocketMQListener<OrderSubmittedEvent> {

    private final OrderAsyncConsumer orderAsyncConsumer;

    public RocketMqOrderSubmittedListener(OrderAsyncConsumer orderAsyncConsumer) {
        this.orderAsyncConsumer = orderAsyncConsumer;
    }

    @Override
    public void onMessage(OrderSubmittedEvent message) {
        orderAsyncConsumer.handle(message);
    }
}
