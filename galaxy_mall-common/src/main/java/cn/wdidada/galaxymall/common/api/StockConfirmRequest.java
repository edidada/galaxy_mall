package cn.wdidada.galaxymall.common.api;

import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;

public final class StockConfirmRequest implements Serializable {

    @NotBlank
    private String reservationId;

    public StockConfirmRequest() {
    }

    public StockConfirmRequest(String reservationId) {
        this.reservationId = reservationId;
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
}
