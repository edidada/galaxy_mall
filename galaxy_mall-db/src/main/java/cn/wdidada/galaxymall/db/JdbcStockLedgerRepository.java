package cn.wdidada.galaxymall.db;

import java.util.Collection;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 库存账本 MySQL 实现（表 {@code t_stock_ledger}，库 {@code galaxy_stock}）。
 *
 * <p>扣减用单条条件 {@code update ... where stock >= ?}，靠 InnoDB 行锁保证原子性，
 * 天然多副本一致；{@code seed} 与 {@code increase} 用 {@code insert ... on duplicate key update}，
 * 重启不会回滚运行期账本。
 */
public final class JdbcStockLedgerRepository implements StockLedgerRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcStockLedgerRepository(DataSource dataSource, Collection<Seed> seedStocks) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.execute("""
                create table if not exists t_stock_ledger (
                    product_id varchar(64) primary key,
                    stock int not null default 0,
                    updated_at timestamp not null default current_timestamp on update current_timestamp
                )""");
        if (seedStocks != null && !seedStocks.isEmpty()) {
            this.jdbcTemplate.batchUpdate(
                    "insert ignore into t_stock_ledger (product_id, stock) values (?, ?)",
                    seedStocks,
                    100,
                    (ps, seed) -> {
                        ps.setString(1, seed.productId());
                        ps.setInt(2, seed.stock());
                    });
        }
    }

    public record Seed(String productId, int stock) {
    }

    @Override
    public void seed(String productId, int stock) {
        jdbcTemplate.update(
                "insert ignore into t_stock_ledger (product_id, stock) values (?, ?)",
                productId, stock);
    }

    @Override
    public int get(String productId) {
        Integer stock = jdbcTemplate.query(
                "select stock from t_stock_ledger where product_id = ?",
                (rs, rowNum) -> rs.getInt("stock"),
                productId)
                .stream()
                .findFirst()
                .orElse(0);
        return stock;
    }

    @Override
    public boolean decreaseWithLock(String productId, int quantity) {
        int affected = jdbcTemplate.update(
                "update t_stock_ledger set stock = stock - ? where product_id = ? and stock >= ?",
                quantity, productId, quantity);
        if (affected == 1) {
            return true;
        }
        // 未命中包括两种情况：库存不足行存在；或该商品行还不存在（视为 0，不可扣）。
        // 为与内存实现“get 缺省 0”的对齐语义，这里只按 false 返回，由调用方走失败/补偿链路。
        return false;
    }

    @Override
    public void increase(String productId, int quantity) {
        int affected = jdbcTemplate.update(
                "update t_stock_ledger set stock = stock + ? where product_id = ?",
                quantity, productId);
        if (affected == 0) {
            internNewRow(productId, quantity);
        }
    }

    /** 行不存在时插入；并发竞争由 primary key 冲突兜底后重试一次更新。 */
    private void internNewRow(String productId, int quantity) {
        try {
            jdbcTemplate.update(
                    "insert into t_stock_ledger (product_id, stock) values (?, ?)",
                    productId, quantity);
        } catch (RuntimeException race) {
            jdbcTemplate.update(
                    "update t_stock_ledger set stock = stock + ? where product_id = ?",
                    quantity, productId);
        }
    }
}
