package cn.wdidada.galaxymall.gateway;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.LoginRequest;
import cn.wdidada.galaxymall.common.api.LoginView;
import cn.wdidada.galaxymall.common.api.ProductDetail;
import cn.wdidada.galaxymall.common.api.ProductListItem;
import cn.wdidada.galaxymall.common.api.SessionView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商城前台的编排入口（{@code /api/mall/*}）。
 *
 * <p>前端只连这一组地址 + {@code /api/collect}，其余 {@code /api/gateway/*}（压测编排）、
 * {@code /edge/*}（透明代理）保留原样，方便压测脚本和人工浏览各走各的路。
 *
 * <p>登录态用 {@code X-Auth-Token} 头透传给 user 服务，网关自身不缓存会话，
 * 所以网关重启不会把已登录用户踢下线（踢人是 user 服务的 TTL 决定的）。
 */
@RestController
@RequestMapping("/api/mall")
public class StorefrontController {

    private static final String AUTH_TOKEN_HEADER = "X-Auth-Token";

    private final StorefrontWorkflowService storefrontWorkflowService;

    public StorefrontController(StorefrontWorkflowService storefrontWorkflowService) {
        this.storefrontWorkflowService = storefrontWorkflowService;
    }

    @GetMapping("/products")
    public ApiResponse<List<ProductListItem>> listProducts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword
    ) {
        return storefrontWorkflowService.listProducts(category, keyword);
    }

    @GetMapping("/products/{productId}")
    public ApiResponse<ProductDetail> productDetail(@PathVariable String productId) {
        return storefrontWorkflowService.productDetail(productId);
    }

    @PostMapping("/login")
    public ApiResponse<LoginView> login(@Valid @RequestBody LoginRequest request) {
        return storefrontWorkflowService.login(request);
    }

    @PostMapping("/register")
    public ApiResponse<LoginView> register(
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam(required = false) String nickname
    ) {
        return storefrontWorkflowService.register(username, password, nickname);
    }

    @PostMapping("/logout")
    public ApiResponse<Boolean> logout(@RequestHeader(value = AUTH_TOKEN_HEADER, required = false) String token) {
        return storefrontWorkflowService.logout(token);
    }

    @GetMapping("/me")
    public ApiResponse<SessionView> me(@RequestHeader(value = AUTH_TOKEN_HEADER, required = false) String token) {
        return storefrontWorkflowService.currentSession(token);
    }
}
