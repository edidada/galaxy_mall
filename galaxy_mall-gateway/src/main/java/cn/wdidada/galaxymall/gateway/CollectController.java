package cn.wdidada.galaxymall.gateway;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.CollectAckView;
import cn.wdidada.galaxymall.common.api.CollectBatchRequest;
import cn.wdidada.galaxymall.common.api.CollectEvent;
import cn.wdidada.galaxymall.common.api.CollectStatsView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端埋点 SDK 的接收端。
 *
 * <p>{@code POST /api/collect} 是 SDK 的唯一出口，页面卸载时走 {@code sendBeacon}，
 * 其余走 {@code fetch(keepalive:true)}；两条路径的报文形状完全一致。
 *
 * <p>这个接口刻意挂在网关根路径而不是 {@code /edge/**}：埋点量级远大于业务量，
 * 走独立前缀才好单独加限流与做压测隔离。
 */
@RestController
@RequestMapping("/api/collect")
public class CollectController {

    private final CollectService collectService;

    public CollectController(CollectService collectService) {
        this.collectService = collectService;
    }

    /** 批量上报；返回体里的 accepted 决定前端要不要把这批事件塞进本地死信队列。 */
    @PostMapping
    public ApiResponse<CollectAckView> collect(@Valid @RequestBody CollectBatchRequest request) {
        return ApiResponse.success("collect-accepted", collectService.accept(request));
    }

    /** 最近上报的事件，埋点看板轮询用。 */
    @GetMapping("/events")
    public ApiResponse<List<CollectEvent>> events(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success("collect-events", collectService.recent(limit));
    }

    /** 聚合计数：按事件类型 / 页面 / 元素语义三个维度。 */
    @GetMapping("/stats")
    public ApiResponse<CollectStatsView> stats() {
        return ApiResponse.success("collect-stats", collectService.stats());
    }
}
