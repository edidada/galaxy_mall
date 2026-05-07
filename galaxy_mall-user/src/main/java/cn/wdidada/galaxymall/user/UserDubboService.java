package cn.wdidada.galaxymall.user;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.UserView;
import cn.wdidada.galaxymall.common.client.UserClient;
import cn.wdidada.galaxymall.common.domain.User;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class UserDubboService implements UserClient {

    private final UserService userService;

    public UserDubboService(UserService userService) {
        this.userService = userService;
    }

    @Override
    public ApiResponse<UserView> getUser(String userId) {
        var result = userService.findUser(userId);
        return result.success()
                ? ApiResponse.success("user-found", toView(result.data()))
                : ApiResponse.failure("user-not-found");
    }

    private UserView toView(User user) {
        return new UserView(user.userId(), user.nickname(), user.level());
    }
}
