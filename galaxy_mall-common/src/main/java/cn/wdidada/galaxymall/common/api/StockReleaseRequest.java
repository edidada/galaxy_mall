package cn.wdidada.galaxymall.common.api;

import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;

public final class StockReleaseRequest implements Serializable {

    @NotBlank
    private String reservationId;

    @NotBlank
    private String reason;

    public StockReleaseRequest() {
    }

    public StockReleaseRequest(String reservationId, String reason) {
        this.reservationId = reservationId;
        this.reason = reason;
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

    public String reason() {
        return reason;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
