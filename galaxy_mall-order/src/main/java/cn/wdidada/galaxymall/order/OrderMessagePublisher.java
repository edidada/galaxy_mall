package cn.wdidada.galaxymall.order;

public interface OrderMessagePublisher {

    void publish(OrderSubmittedEvent event);
}
