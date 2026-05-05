package cn.wdidada.galaxymall.stock;

import cn.wdidada.galaxymall.common.OperationResult;
import cn.wdidada.galaxymall.common.domain.StockReservation;

public final class StockService {

    private final StockBackend stockBackend;

    public StockService(StockBackend stockBackend) {
        this.stockBackend = stockBackend;
    }

    public void preload(String productId) {
        stockBackend.preload(productId);
    }

    public OperationResult<StockReservation> reserve(String reservationId, String productId, int quantity) {
        return stockBackend.reserve(reservationId, productId, quantity);
    }

    public OperationResult<StockReservation> confirm(String reservationId) {
        return stockBackend.confirm(reservationId);
    }

    public OperationResult<StockReservation> release(String reservationId, String reason) {
        return stockBackend.release(reservationId, reason);
    }

    public OperationResult<StockReservation> restock(String adjustmentId, String productId, int quantity, String reason) {
        return stockBackend.restock(adjustmentId, productId, quantity, reason);
    }

    public OperationResult<StockReservation> compensateRestock(String adjustmentId, String productId, int quantity) {
        return stockBackend.compensateRestock(adjustmentId, productId, quantity);
    }

    public void reconcile(String productId) {
        stockBackend.reconcile(productId);
    }

    public int redisStock(String productId) {
        return stockBackend.redisStock(productId);
    }

    public int databaseStock(String productId) {
        return stockBackend.databaseStock(productId);
    }
}
