package cn.wdidada.galaxymall.stock;

import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.StockConfirmRequest;
import cn.wdidada.galaxymall.common.api.StockDeductRequest;
import cn.wdidada.galaxymall.common.api.StockReleaseRequest;
import cn.wdidada.galaxymall.common.api.StockRestockCompensateRequest;
import cn.wdidada.galaxymall.common.api.StockRestockRequest;
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
        OperationResult<StockReservation> result = stockService.reserve(request.reservationId(), request.productId(), request.quantity());
        if (!result.success()) {
            return ApiResponse.failure(result.message());
        }
        return ApiResponse.success(result.message(), toView(result.data(), "RESERVED"));
    }

    @PostMapping("/confirm")
    public ApiResponse<StockView> confirm(@Valid @RequestBody StockConfirmRequest request) {
        OperationResult<StockReservation> result = stockService.confirm(request.reservationId());
        if (!result.success()) {
            return ApiResponse.failure(result.message());
        }
        return ApiResponse.success(result.message(), toView(result.data(), "CONFIRMED"));
    }

    @PostMapping("/release")
    public ApiResponse<StockView> release(@Valid @RequestBody StockReleaseRequest request) {
        OperationResult<StockReservation> result = stockService.release(request.reservationId(), request.reason());
        if (!result.success()) {
            return ApiResponse.failure(result.message());
        }
        return ApiResponse.success(result.message(), toView(result.data(), "RELEASED"));
    }

    @PostMapping("/restock")
    public ApiResponse<StockView> restock(@Valid @RequestBody StockRestockRequest request) {
        OperationResult<StockReservation> result = stockService.restock(
                request.adjustmentId(),
                request.productId(),
                request.quantity(),
                request.reason()
        );
        if (!result.success()) {
            return ApiResponse.failure(result.message());
        }
        return ApiResponse.success(result.message(), toView(result.data(), "RESTOCKED"));
    }

    @PostMapping("/restock/compensate")
    public ApiResponse<StockView> compensateRestock(@Valid @RequestBody StockRestockCompensateRequest request) {
        OperationResult<StockReservation> result = stockService.compensateRestock(
                request.adjustmentId(),
                request.productId(),
                request.quantity()
        );
        if (!result.success()) {
            return ApiResponse.failure(result.message());
        }
        return ApiResponse.success(result.message(), toView(result.data(), "RESTOCK_COMPENSATED"));
    }

    private StockView toView(StockReservation reservation, String status) {
        return new StockView(
                reservation.reservationId(),
                reservation.productId(),
                reservation.quantity(),
                reservation.remainingStock(),
                status
        );
    }
}
