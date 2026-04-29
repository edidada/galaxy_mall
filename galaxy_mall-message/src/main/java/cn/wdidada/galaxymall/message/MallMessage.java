package cn.wdidada.galaxymall.message;

public record MallMessage(String topic, String key, Object payload, int attempt) {

    public MallMessage nextAttempt() {
        return new MallMessage(topic, key, payload, attempt + 1);
    }
}
