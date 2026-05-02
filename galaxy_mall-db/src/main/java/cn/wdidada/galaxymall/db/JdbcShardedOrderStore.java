package cn.wdidada.galaxymall.db;

import cn.wdidada.galaxymall.common.domain.Order;
import cn.wdidada.galaxymall.common.domain.OrderStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcShardedOrderStore implements OrderStore {

    private final List<JdbcTemplate> shards;
    private final int tableShards;

    public JdbcShardedOrderStore(List<DataSource> dataSources, int tableShards) {
        this.shards = dataSources.stream().map(JdbcTemplate::new).toList();
        this.tableShards = tableShards;
        initializeSchema();
    }

    @Override
    public void save(Order order) {
        Target target = target(order.orderId());
        target.jdbcTemplate().update("""
                insert into %s (order_id, user_id, product_id, quantity, amount_cent, seckill, created_at, status, async_attempts)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(target.tableName()),
                order.orderId(),
                order.userId(),
                order.productId(),
                order.quantity(),
                order.amountCent(),
                order.seckill(),
                Timestamp.from(order.createdAt()),
                order.status().name(),
                order.asyncAttempts()
        );
    }

    @Override
    public Optional<Order> findById(String orderId) {
        Target target = target(orderId);
        List<Order> orders = target.jdbcTemplate().query(
                "select * from " + target.tableName() + " where order_id = ?",
                new OrderRowMapper(),
                orderId
        );
        return orders.stream().findFirst();
    }

    @Override
    public void updateStatus(String orderId, OrderStatus status) {
        Target target = target(orderId);
        target.jdbcTemplate().update(
                "update " + target.tableName() + " set status = ? where order_id = ?",
                status.name(),
                orderId
        );
    }

    @Override
    public void updateAsyncAttempts(String orderId, int attempts) {
        Target target = target(orderId);
        target.jdbcTemplate().update(
                "update " + target.tableName() + " set async_attempts = ? where order_id = ?",
                attempts,
                orderId
        );
    }

    @Override
    public List<Order> findAll() {
        throw new UnsupportedOperationException("jdbc-sharded findAll is disabled; use paged or shard-scoped queries");
    }

    private void initializeSchema() {
        for (JdbcTemplate jdbcTemplate : shards) {
            for (int table = 0; table < tableShards; table++) {
                jdbcTemplate.execute("""
                        create table if not exists t_order_%d (
                            order_id varchar(64) primary key,
                            user_id varchar(64) not null,
                            product_id varchar(64) not null,
                            quantity int not null,
                            amount_cent int not null,
                            seckill boolean not null,
                            created_at timestamp not null,
                            status varchar(32) not null,
                            async_attempts int not null
                        )
                        """.formatted(table));
            }
        }
    }

    private Target target(String orderId) {
        int hash = orderId.hashCode();
        int shard = Math.floorMod(hash, shards.size());
        int table = Math.floorMod(hash / Math.max(1, shards.size()), tableShards);
        return new Target(shards.get(shard), "t_order_" + table);
    }

    private record Target(JdbcTemplate jdbcTemplate, String tableName) {
    }

    private static final class OrderRowMapper implements RowMapper<Order> {

        @Override
        public Order mapRow(ResultSet rs, int rowNum) throws SQLException {
            return Order.restore(
                    rs.getString("order_id"),
                    rs.getString("user_id"),
                    rs.getString("product_id"),
                    rs.getInt("quantity"),
                    rs.getInt("amount_cent"),
                    rs.getBoolean("seckill"),
                    rs.getTimestamp("created_at").toInstant(),
                    OrderStatus.valueOf(rs.getString("status")),
                    rs.getInt("async_attempts")
            );
        }
    }
}
