package cn.wdidada.galaxymall.stock;

import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.StockDeductRequest;
import cn.wdidada.galaxymall.common.api.StockView;
import cn.wdidada.galaxymall.common.domain.StockReservation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @PostMapping("/reserve")
    public ApiResponse<StockView> reserve(@Valid @RequestBody StockDeductRequest request) {
        OperationResult<StockReservation> result = stockService.reserve(request.productId(), request.quantity());
        if (!result.success()) {
            return ApiResponse.failure(result.message());
        }
        StockReservation reservation = result.data();
        return ApiResponse.success(
                result.message(),
                new StockView(reservation.productId(), reservation.quantity(), reservation.remainingStock())
        );
    }
}
