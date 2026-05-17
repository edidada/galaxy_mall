package cn.wdidada.galaxymall.common.api;

import java.io.Serializable;

public final class StockView implements Serializable {

    private String reservationId;
    private String productId;
    private int reservedQuantity;
    private int remainingStock;
    private String status;

    public StockView() {
    }

    public StockView(String reservationId, String productId, int reservedQuantity, int remainingStock, String status) {
        this.reservationId = reservationId;
        this.productId = productId;
        this.reservedQuantity = reservedQuantity;
        this.remainingStock = remainingStock;
        this.status = status;
    }

    public String reservationId() {
        return reservationId;
    }

    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
    }

    public String productId() {
        return productId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public int reservedQuantity() {
        return reservedQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    public void setReservedQuantity(int reservedQuantity) {
        this.reservedQuantity = reservedQuantity;
    }

    public int remainingStock() {
        return remainingStock;
    }

    public int getRemainingStock() {
        return remainingStock;
    }

    public void setRemainingStock(int remainingStock) {
        this.remainingStock = remainingStock;
    }

    public String status() {
        return status;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
