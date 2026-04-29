package cn.wdidada.galaxymall.common.client;

import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.StockDeductRequest;
import cn.wdidada.galaxymall.common.api.StockView;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "stock-service", url = "${services.stock.url}")
public interface StockClient {

    @PostMapping("/api/stocks/reserve")
    ApiResponse<StockView> reserve(@RequestBody StockDeductRequest request);
}
