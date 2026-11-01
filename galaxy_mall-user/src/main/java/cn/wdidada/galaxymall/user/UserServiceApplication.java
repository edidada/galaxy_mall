package cn.wdidada.galaxymall.user;

import cn.wdidada.galaxymall.db.UserRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * 演示账号：昵称和 userId 都能作为登录名，口令统一 {@link AccountService#DEFAULT_PASSWORD}。
 * 前端登录页会把这组账号显示成“一键登录”，省掉手输。
 */
@SpringBootApplication(
        scanBasePackageClasses = UserServiceApplication.class,
        excludeName = {
                "org.springframework.cloud.gateway.config.GatewayAutoConfiguration",
                "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration",
                "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration"
        }
)
@Import(UserStoreConfig.class)
public class UserServiceApplication {

    // 种子账号已迁移到 UserStoreConfig.SEED_USERS（local/jdbc 两种后端统一“不存在才插入”，
    // 运行期注册的新账号不再被重启回滚——jdbc 后端落 t_user 表）。

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }

    @Bean
    UserService userService(UserRepository userRepository) {
        return new UserService(userRepository);
    }

    @Bean
    AccountService accountService(UserRepository userRepository) {
        AccountService accountService = new AccountService(userRepository);
        for (String loginName : new String[] {
                "andromeda", "orion", "nova", "lyra",
                "u100", "u101", "u200", "u300"
        }) {
            accountService.bindDefaultPassword(loginName);
        }
        return accountService;
    }
}
