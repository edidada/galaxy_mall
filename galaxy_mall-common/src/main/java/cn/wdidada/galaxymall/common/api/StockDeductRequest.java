package cn.wdidada.galaxymall.common.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;

public final class StockDeductRequest implements Serializable {

    @NotBlank
    private String reservationId;

    @NotBlank
    private String productId;

    @Min(1)
    private int quantity;

    public StockDeductRequest() {
    }

    public StockDeductRequest(String reservationId, String productId, int quantity) {
        this.reservationId = reservationId;
        this.productId = productId;
        this.quantity = quantity;
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

    public int quantity() {
        return quantity;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
