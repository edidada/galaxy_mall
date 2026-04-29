package cn.wdidada.galaxymall.db;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class StockLedgerRepository {

    private final Map<String, AtomicInteger> stocks = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public void seed(String productId, int stock) {
        stocks.put(productId, new AtomicInteger(stock));
        locks.putIfAbsent(productId, new Object());
    }

    public int get(String productId) {
        return stocks.getOrDefault(productId, new AtomicInteger()).get();
    }

    public boolean decreaseWithLock(String productId, int quantity) {
        Object lock = locks.computeIfAbsent(productId, ignored -> new Object());
        synchronized (lock) {
            AtomicInteger current = stocks.computeIfAbsent(productId, ignored -> new AtomicInteger());
            if (current.get() < quantity) {
                return false;
            }
            current.addAndGet(-quantity);
            return true;
        }
    }

    public void increase(String productId, int quantity) {
        stocks.computeIfAbsent(productId, ignored -> new AtomicInteger()).addAndGet(quantity);
    }
}
