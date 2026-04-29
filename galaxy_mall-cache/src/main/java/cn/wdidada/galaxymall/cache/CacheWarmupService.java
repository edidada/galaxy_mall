package cn.wdidada.galaxymall.cache;

import java.util.Collection;
import java.util.function.Consumer;

public final class CacheWarmupService<T> {

    public void warmup(Collection<T> hotData, Consumer<T> writer) {
        hotData.forEach(writer);
    }
}
