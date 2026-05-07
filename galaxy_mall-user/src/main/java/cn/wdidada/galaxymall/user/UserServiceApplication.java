package cn.wdidada.galaxymall.user;

import cn.wdidada.galaxymall.common.domain.User;
import cn.wdidada.galaxymall.db.UserRepository;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
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
@EnableDubbo
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
}
