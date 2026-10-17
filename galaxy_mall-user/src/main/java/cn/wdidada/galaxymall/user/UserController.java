package cn.wdidada.galaxymall.user;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.LoginRequest;
import cn.wdidada.galaxymall.common.api.LoginView;
import cn.wdidada.galaxymall.common.api.SessionView;
import cn.wdidada.galaxymall.common.api.UserView;
import cn.wdidada.galaxymall.common.domain.User;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    /** 会话令牌头；网关从入站请求原样透传，前端登录后保存。 */
    public static final String AUTH_TOKEN_HEADER = "X-Auth-Token";

    private final UserService userService;
    private final AccountService accountService;

    public UserController(UserService userService, AccountService accountService) {
        this.userService = userService;
        this.accountService = accountService;
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserView> getUser(@PathVariable String userId) {
        var result = userService.findUser(userId);
        return result.success()
                ? ApiResponse.success("user-found", toView(result.data()))
                : ApiResponse.failure("user-not-found");
    }

    /**
     * 登录。演示级口令校验，见 {@link AccountService}。
     * 401 语义这里刻意用 {@code success=false} 表达，保持与全站统一响应体一致。
     */
    @PostMapping("/login")
    public ApiResponse<LoginView> login(@Valid @RequestBody LoginRequest request) {
        return accountService.login(request.username(), request.password())
                .map(view -> ApiResponse.success("login-success", view))
                .orElseGet(() -> ApiResponse.failure("login-failed:bad-credentials"));
    }

    /** 注册一个新演示账号，注册即登录（直接返回令牌，省去前端二次提交）。 */
    @PostMapping("/register")
    public ApiResponse<LoginView> register(
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam(required = false) String nickname,
            @RequestParam(defaultValue = "1") int level
    ) {
        if (!accountService.register(username, password, nickname, level)) {
            return ApiResponse.failure("register-failed:username-taken-or-password-too-short");
        }
        return accountService.login(username.toLowerCase(Locale.ROOT), password)
                .map(view -> ApiResponse.success("register-success", view))
                .orElseGet(() -> ApiResponse.failure("register-failed:login-after-register"));
    }

    /** 退出登录：令牌不存在也返回成功（幂等），前端无论如何都要清本地态。 */
    @PostMapping("/logout")
    public ApiResponse<Boolean> logout(@RequestHeader(value = AUTH_TOKEN_HEADER, required = false) String token) {
        return ApiResponse.success("logout-success", accountService.logout(token));
    }

    /** 前端启动时用本地令牌换回登录态，避免刷新页面后丢用户名。 */
    @GetMapping("/me")
    public ApiResponse<SessionView> me(@RequestHeader(value = AUTH_TOKEN_HEADER, required = false) String token) {
        return accountService.currentSession(token)
                .map(session -> ApiResponse.success("session-found", session))
                .orElseGet(() -> ApiResponse.failure("session-not-found"));
    }

    private UserView toView(User user) {
        return new UserView(user.userId(), user.nickname(), user.level());
    }
}
