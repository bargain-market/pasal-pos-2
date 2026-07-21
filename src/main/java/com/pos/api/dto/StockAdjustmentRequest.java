package com.pos.api.dto;

/**
 * Stock adjustment request DTO
 */
public class StockAdjustmentRequest {
    public String productId;
    public String productName;
    public String sku;
    public String changeType; // ADJUSTMENT, RECEIVE, TRANSFER
    public Integer previousQuantity;
    public Integer newQuantity;
    public Integer adjustment; // Change amount (can be positive or negative)
    public String reason;
    public String userId;
    public String userName;
    public String timestamp;
}
