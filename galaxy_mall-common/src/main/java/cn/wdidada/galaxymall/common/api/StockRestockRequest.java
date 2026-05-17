package cn.wdidada.galaxymall.common.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.io.Serializable;

public final class StockRestockRequest implements Serializable {

    @NotBlank
    private String adjustmentId;

    @NotBlank
    private String productId;

    @Min(1)
    private int quantity;

    @NotBlank
    private String reason;

    public StockRestockRequest() {
    }

    public StockRestockRequest(String adjustmentId, String productId, int quantity, String reason) {
        this.adjustmentId = adjustmentId;
        this.productId = productId;
        this.quantity = quantity;
        this.reason = reason;
    }

    public String adjustmentId() {
        return adjustmentId;
    }

    public String getAdjustmentId() {
        return adjustmentId;
    }

    public void setAdjustmentId(String adjustmentId) {
        this.adjustmentId = adjustmentId;
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
