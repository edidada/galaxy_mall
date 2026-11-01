package cn.wdidada.galaxymall.user;

import cn.wdidada.galaxymall.common.domain.User;
import cn.wdidada.galaxymall.db.InMemoryUserRepository;
import cn.wdidada.galaxymall.db.JdbcUserRepository;
import cn.wdidada.galaxymall.db.UserRepository;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 用户存储装配：{@code galaxy.user.store-backend=local}（默认，内存）或 {@code jdbc}（MySQL t_user，库 galaxy_user）。
 *
 * <p>种子账号 {@link #SEED_USERS} 在两种后端下都走“不存在才插入”，重启不覆盖运行期数据。
 * 连接参数 {@code USER_DB_URL / USER_DB_USERNAME / USER_DB_PASSWORD} 只在 jdbc 后端下生效。
 */
@Configuration
public class UserStoreConfig {

    /** 播种账号：与 UserServiceApplication 历史种子保持一致（u100/u101/u200/u300）。 */
    public static final List<User> SEED_USERS = List.of(
            new User("u100", "andromeda", 3),
            new User("u101", "orion", 2),
            new User("u200", "nova", 5),
            new User("u300", "lyra", 1));

    @Bean
    @ConditionalOnProperty(name = "galaxy.user.store-backend", havingValue = "local", matchIfMissing = true)
    UserRepository inMemoryUserRepository() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        for (User seed : SEED_USERS) {
            if (repository.findById(seed.userId()).isEmpty()) {
                repository.save(seed);
            }
        }
        return repository;
    }

    @Bean
    @ConditionalOnProperty(name = "galaxy.user.store-backend", havingValue = "jdbc")
    UserRepository jdbcUserRepository(
            @Value("${galaxy.user.db.url}") String url,
            @Value("${galaxy.user.db.username:root}") String username,
            @Value("${galaxy.user.db.password:}") String password
    ) {
        DataSource dataSource = DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .build();
        return new JdbcUserRepository(dataSource, SEED_USERS);
    }
}
