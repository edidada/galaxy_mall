package cn.wdidada.galaxymall.stock;

import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.api.ApiResponse;
import cn.wdidada.galaxymall.common.api.StockConfirmRequest;
import cn.wdidada.galaxymall.common.api.StockDeductRequest;
import cn.wdidada.galaxymall.common.api.StockReleaseRequest;
import cn.wdidada.galaxymall.common.api.StockRestockCompensateRequest;
import cn.wdidada.galaxymall.common.api.StockRestockRequest;
import cn.wdidada.galaxymall.common.api.StockView;
import cn.wdidada.galaxymall.common.client.StockClient;
import cn.wdidada.galaxymall.common.domain.StockReservation;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class StockDubboService implements StockClient {

    private final StockService stockService;

    public StockDubboService(StockService stockService) {
        this.stockService = stockService;
    }

    @Override
    public ApiResponse<StockView> reserve(StockDeductRequest request) {
        OperationResult<StockReservation> result = stockService.reserve(request.reservationId(), request.productId(), request.quantity());
        if (!result.success()) {
            return ApiResponse.failure(result.message());
        }
        return ApiResponse.success(result.message(), toView(result.data(), "RESERVED"));
    }

    @Override
    public ApiResponse<StockView> confirm(StockConfirmRequest request) {
        OperationResult<StockReservation> result = stockService.confirm(request.reservationId());
        if (!result.success()) {
            return ApiResponse.failure(result.message());
        }
        return ApiResponse.success(result.message(), toView(result.data(), "CONFIRMED"));
    }

    @Override
    public ApiResponse<StockView> release(StockReleaseRequest request) {
        OperationResult<StockReservation> result = stockService.release(request.reservationId(), request.reason());
        if (!result.success()) {
            return ApiResponse.failure(result.message());
        }
        return ApiResponse.success(result.message(), toView(result.data(), "RELEASED"));
    }

    @Override
    public ApiResponse<StockView> restock(StockRestockRequest request) {
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

    @Override
    public ApiResponse<StockView> compensateRestock(StockRestockCompensateRequest request) {
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
