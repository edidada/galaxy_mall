package cn.wdidada.galaxymall.db;

import cn.wdidada.galaxymall.common.domain.User;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 用户 MySQL 实现（表 {@code t_user}，库 {@code galaxy_user}）。
 *
 * <p>构造时建表并按“不存在才插入”{@code insert ignore} 播种种子账号，
 * 重启不会覆盖运行期新增/修改的用户。
 */
public final class JdbcUserRepository implements UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserRepository(DataSource dataSource, Collection<User> seedUsers) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.execute("""
                create table if not exists t_user (
                    user_id varchar(64) primary key,
                    nickname varchar(64) not null,
                    level int not null default 1,
                    created_at timestamp not null default current_timestamp
                )""");
        if (seedUsers != null && !seedUsers.isEmpty()) {
            this.jdbcTemplate.batchUpdate(
                    "insert ignore into t_user (user_id, nickname, level) values (?, ?, ?)",
                    seedUsers,
                    100,
                    (ps, user) -> {
                        ps.setString(1, user.userId());
                        ps.setString(2, user.nickname());
                        ps.setInt(3, user.level());
                    });
        }
    }

    @Override
    public void save(User user) {
        try {
            jdbcTemplate.update(
                    "insert into t_user (user_id, nickname, level) values (?, ?, ?)",
                    user.userId(), user.nickname(), user.level());
        } catch (DuplicateKeyException conflict) {
            jdbcTemplate.update(
                    "update t_user set nickname = ?, level = ? where user_id = ?",
                    user.nickname(), user.level(), user.userId());
        }
    }

    @Override
    public Optional<User> findById(String userId) {
        List<User> found = jdbcTemplate.query(
                "select user_id, nickname, level from t_user where user_id = ?",
                (rs, rowNum) -> new User(rs.getString("user_id"), rs.getString("nickname"), rs.getInt("level")),
                userId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public Collection<User> findAll() {
        return jdbcTemplate.query(
                "select user_id, nickname, level from t_user",
                (rs, rowNum) -> new User(rs.getString("user_id"), rs.getString("nickname"), rs.getInt("level")));
    }
}
