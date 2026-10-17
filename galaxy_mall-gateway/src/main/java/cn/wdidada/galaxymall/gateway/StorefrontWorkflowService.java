package cn.wdidada.galaxymall.gateway;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.LoginRequest;
import cn.wdidada.galaxymall.common.api.LoginView;
import cn.wdidada.galaxymall.common.api.ProductDetail;
import cn.wdidada.galaxymall.common.api.ProductListItem;
import cn.wdidada.galaxymall.common.api.SessionView;
import cn.wdidada.galaxymall.monitor.MetricsCollector;
import cn.wdidada.galaxymall.monitor.TraceLogger;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 商城前台编排：登录态 + 图文商品。
 *
 * <p>与 {@code GatewayWorkflowService} 分开放的原因：那个类是 100w TPS 压测链路的编排入口，
 * 上面挂着查询/下单令牌桶与排队大厅；这里的接口只在人工浏览时调用，
 * 掺在一起会让压测数据混进无人访问的展示接口，也会让限流配额被列表页吃掉。
 *
 * <p>下游不可用时统一返回 {@code success=false}，不把 500 抛给前端 —— 前端页面会区分
 * “接口挂了”和“没有数据”，靠的就是这个响应体里的 message。
 */
public final class StorefrontWorkflowService {

    private static final ParameterizedTypeReference<ApiResponse<List<ProductListItem>>> PRODUCT_LIST_RESPONSE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<ProductDetail>> PRODUCT_DETAIL_RESPONSE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<LoginView>> LOGIN_RESPONSE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<SessionView>> SESSION_RESPONSE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<Boolean>> PLAIN_RESPONSE =
            new ParameterizedTypeReference<>() {};

    private static final String AUTH_TOKEN_HEADER = "X-Auth-Token";

    private final RestClient userServiceRestClient;
    private final RestClient productServiceRestClient;
    private final MetricsCollector metricsCollector;
    private final TraceLogger traceLogger;

    public StorefrontWorkflowService(
            RestClient userServiceRestClient,
            RestClient productServiceRestClient,
            MetricsCollector metricsCollector,
            TraceLogger traceLogger
    ) {
        this.userServiceRestClient = userServiceRestClient;
        this.productServiceRestClient = productServiceRestClient;
        this.metricsCollector = metricsCollector;
        this.traceLogger = traceLogger;
    }

    public ApiResponse<List<ProductListItem>> listProducts(String category, String keyword) {
        try {
            return productServiceRestClient.get()
                    .uri(builder -> builder.path("/api/products")
                            .queryParamIfPresent("category", java.util.Optional.ofNullable(blankToNull(category)))
                            .queryParamIfPresent("keyword", java.util.Optional.ofNullable(blankToNull(keyword)))
                            .build())
                    .retrieve()
                    .body(PRODUCT_LIST_RESPONSE);
        } catch (RestClientException e) {
            return downstreamFailure("product", e);
        }
    }

    public ApiResponse<ProductDetail> productDetail(String productId) {
        try {
            return productServiceRestClient.get()
                    .uri("/api/products/{productId}/detail", productId)
                    .retrieve()
                    .body(PRODUCT_DETAIL_RESPONSE);
        } catch (RestClientException e) {
            return downstreamFailure("product", e);
        }
    }

    public ApiResponse<LoginView> login(LoginRequest request) {
        try {
            ApiResponse<LoginView> response = userServiceRestClient.post()
                    .uri("/api/users/login")
                    .body(request)
                    .retrieve()
                    .body(LOGIN_RESPONSE);
            metricsCollector.increment(response != null && response.success()
                    ? "mall.login.success" : "mall.login.failure");
            traceLogger.log("gateway", "mall.login", request.username());
            return response;
        } catch (RestClientException e) {
            return downstreamFailure("user", e);
        }
    }

    public ApiResponse<LoginView> register(String username, String password, String nickname) {
        try {
            return userServiceRestClient.post()
                    .uri(builder -> builder.path("/api/users/register")
                            .queryParam("username", username)
                            .queryParam("password", password)
                            .queryParamIfPresent("nickname", java.util.Optional.ofNullable(blankToNull(nickname)))
                            .build())
                    .retrieve()
                    .body(LOGIN_RESPONSE);
        } catch (RestClientException e) {
            return downstreamFailure("user", e);
        }
    }

    public ApiResponse<Boolean> logout(String token) {
        try {
            return userServiceRestClient.post()
                    .uri("/api/users/logout")
                    .header(AUTH_TOKEN_HEADER, token == null ? "" : token)
                    .retrieve()
                    .body(PLAIN_RESPONSE);
        } catch (RestClientException e) {
            return downstreamFailure("user", e);
        }
    }

    public ApiResponse<SessionView> currentSession(String token) {
        try {
            return userServiceRestClient.get()
                    .uri("/api/users/me")
                    .header(AUTH_TOKEN_HEADER, token == null ? "" : token)
                    .retrieve()
                    .body(SESSION_RESPONSE);
        } catch (RestClientException e) {
            return downstreamFailure("user", e);
        }
    }

    private <T> ApiResponse<T> downstreamFailure(String service, RestClientException e) {
        metricsCollector.increment("mall.downstream." + service);
        traceLogger.log("gateway", "mall.downstream", service + " " + e.getMessage());
        return ApiResponse.failure("downstream-unavailable:" + service);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
