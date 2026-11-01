package cn.wdidada.galaxymall.db;

import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 支付流水 MySQL 实现（表 {@code t_payment}，库 {@code galaxy_payment}）。
 *
 * <p>幂等键是 {@code order_id}（同一订单重复支付回调只保留最后一次成功记录）；
 * 服务重启不丢，供对账与审计查询。支付主链路不依赖本表——写入失败不阻断支付，
 * 只打 {@code payment.store.write-failed} 计数（{@code recordFiveSafe} 的调用方处理）。
 */
public final class JdbcPaymentStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcPaymentStore(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.execute("""
                create table if not exists t_payment (
                    payment_id varchar(64) primary key,
                    order_id varchar(64) not null unique,
                    amount_cent int not null default 0,
                    status varchar(32) not null,
                    message varchar(255) not null default '',
                    created_at timestamp not null default current_timestamp,
                    updated_at timestamp not null default current_timestamp on update current_timestamp
                )""");
    }

    public record PaymentRow(
            String paymentId,
            String orderId,
            int amountCent,
            String status,
            String message
    ) {
    }

    /**
     * 记一条支付流水；同一订单再次支付时覆盖（保留最新 payment_id/status/message）。
     */
    public void record(String paymentId, String orderId, int amountCent, String status, String message) {
        jdbcTemplate.update("""
                insert into t_payment (payment_id, order_id, amount_cent, status, message)
                values (?, ?, ?, ?, ?)
                on duplicate key update
                    payment_id = values(payment_id),
                    amount_cent = values(amount_cent),
                    status = values(status),
                    message = values(message)
                """, paymentId, orderId, amountCent, status, message);
    }

    public Optional<PaymentRow> findByOrderId(String orderId) {
        return jdbcTemplate.query(
                "select payment_id, order_id, amount_cent, status, message from t_payment where order_id = ?",
                (rs, rowNum) -> new PaymentRow(
                        rs.getString("payment_id"),
                        rs.getString("order_id"),
                        rs.getInt("amount_cent"),
                        rs.getString("status"),
                        rs.getString("message")),
                orderId)
                .stream()
                .findFirst();
    }
}
