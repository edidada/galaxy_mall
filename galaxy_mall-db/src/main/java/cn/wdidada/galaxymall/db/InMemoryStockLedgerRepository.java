package cn.wdidada.galaxymall.db;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** 库存账本内存实现（老 demo 行为）：每进程一份，重启即回种子状态。 */
public final class InMemoryStockLedgerRepository implements StockLedgerRepository {

    private final Map<String, AtomicInteger> stocks = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Override
    public void seed(String productId, int stock) {
        stocks.computeIfAbsent(productId, ignored -> new AtomicInteger(stock));
        locks.putIfAbsent(productId, new Object());
    }

    @Override
    public int get(String productId) {
        return stocks.getOrDefault(productId, new AtomicInteger()).get();
    }

    @Override
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

    @Override
    public void increase(String productId, int quantity) {
        stocks.computeIfAbsent(productId, ignored -> new AtomicInteger()).addAndGet(quantity);
    }
}
