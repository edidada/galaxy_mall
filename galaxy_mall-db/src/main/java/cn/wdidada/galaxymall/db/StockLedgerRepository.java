package cn.wdidada.galaxymall.db;

/**
 * 库存“DB 账本”抽象：真正决定 confirm 成败的持久扣减层。
 *
 * <p>内存实现（{@code InMemoryStockLedgerRepository}）是老 demo 行为——每 Pod 份、重启重播种，
 * 多副本会分裂；JDBC 实现（{@code JdbcStockLedgerRepository}，表 {@code t_stock_ledger}）是
 * 根治方案。选择开关 {@code galaxy.stock.ledger-backend}（local/jdbc）。
 *
 * <p>约定：{@code seed} 为“不存在才初始化”，重启不能覆盖运行期扣减结果。
 */
public interface StockLedgerRepository {

    void seed(String productId, int stock);

    int get(String productId);

    boolean decreaseWithLock(String productId, int quantity);

    void increase(String productId, int quantity);
}
