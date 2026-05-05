package cn.wdidada.galaxymall.stock;

import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.domain.StockReservation;

public interface StockBackend {

    void preload(String productId);

    OperationResult<StockReservation> reserve(String reservationId, String productId, int quantity);

    OperationResult<StockReservation> confirm(String reservationId);

    OperationResult<StockReservation> release(String reservationId, String reason);

    OperationResult<StockReservation> restock(String adjustmentId, String productId, int quantity, String reason);

    OperationResult<StockReservation> compensateRestock(String adjustmentId, String productId, int quantity);

    void reconcile(String productId);

    int redisStock(String productId);

    int databaseStock(String productId);
}
