package cn.wdidada.galaxymall.user;

import cn.wdidada.galaxymall.common.domain.User;
import cn.wdidada.galaxymall.db.UserRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(
        scanBasePackageClasses = UserServiceApplication.class,
        excludeName = {
                "org.springframework.cloud.gateway.config.GatewayAutoConfiguration",
                "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration",
                "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration"
        }
)
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }

    @Bean
    UserRepository userRepository() {
        UserRepository repository = new UserRepository();
        repository.save(new User("u100", "andromeda", 3));
        repository.save(new User("u101", "orion", 2));
        repository.save(new User("u200", "nova", 5));
        repository.save(new User("u300", "lyra", 1));
        return repository;
    }

    @Bean
    UserService userService(UserRepository userRepository) {
        return new UserService(userRepository);
    }

    /**
     * 演示账号：昵称和 userId 都能作为登录名，口令统一 {@link AccountService#DEFAULT_PASSWORD}。
     * 前端登录页会把这组账号显示成“一键登录”，省掉手输。
     */
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
