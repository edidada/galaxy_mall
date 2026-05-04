package cn.wdidada.galaxymall.seckill;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.SeckillRequest;
import cn.wdidada.galaxymall.common.api.SeckillView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/seckill/requests")
public class SeckillController {

    private final SeckillService seckillService;

    public SeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    @PostMapping
    public ApiResponse<SeckillView> submit(@Valid @RequestBody SeckillRequest request) {
        return seckillService.submit(request);
    }

    @GetMapping("/{requestId}")
    public ApiResponse<SeckillView> getRequest(@PathVariable String requestId) {
        return seckillService.getRequest(requestId);
    }
}
