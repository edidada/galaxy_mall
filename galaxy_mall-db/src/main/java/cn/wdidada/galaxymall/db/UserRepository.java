package cn.wdidada.galaxymall.db;

import cn.wdidada.galaxymall.common.domain.User;
import java.util.Collection;
import java.util.Optional;

/**
 * 用户存储抽象：本地内存实现供单测/单机演示，JDBC 实现落到 MySQL {@code t_user}。
 *
 * <p>选择开关为 {@code galaxy.user.store-backend}（local/jdbc），双实现见
 * {@code InMemoryUserRepository} 与 {@code JdbcUserRepository}。
 *
 * <p>约定：{@code save} 语义为“写入该用户”；JDBC 实现用 {@code INSERT ... ON DUPLICATE KEY UPDATE}。
 * 种子账号一律“不存在才插入”（{@code insert ignore}），重启不能覆盖运行期数据。
 */
public interface UserRepository {

    void save(User user);

    Optional<User> findById(String userId);

    /** 供账号服务按 nickname / userId 反查登录名，只读快照。 */
    Collection<User> findAll();
}
