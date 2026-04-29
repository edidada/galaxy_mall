package cn.wdidada.galaxymall.order;

import cn.wdidada.galaxymall.message.InMemoryMessageBus;

public final class LocalOrderMessagePublisher implements OrderMessagePublisher {

    private final InMemoryMessageBus messageBus;

    public LocalOrderMessagePublisher(InMemoryMessageBus messageBus) {
        this.messageBus = messageBus;
    }

    @Override
    public void publish(OrderSubmittedEvent event) {
        messageBus.publish(OrderTopics.ORDER_SUBMITTED, event.orderId(), event);
    }
}
