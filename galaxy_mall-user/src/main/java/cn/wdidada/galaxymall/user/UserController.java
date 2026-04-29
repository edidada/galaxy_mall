package cn.wdidada.galaxymall.user;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.UserView;
import cn.wdidada.galaxymall.common.domain.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserView> getUser(@PathVariable String userId) {
        var result = userService.findUser(userId);
        return result.success()
                ? ApiResponse.success("user-found", toView(result.data()))
                : ApiResponse.failure("user-not-found");
    }

    private UserView toView(User user) {
        return new UserView(user.userId(), user.nickname(), user.level());
    }
}
