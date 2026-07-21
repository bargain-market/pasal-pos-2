package com.pos.api.dto;

/**
 * Stock reconciliation request DTO
 */
public class StockReconciliationRequest {
    public String productId;
    public String productName;
    public String sku;
    public Integer previousQuantity;
    public Integer actualQuantity;
    public Integer difference;
    public String reason;
    public String userId;
    public String userName;
    public String timestamp;
}
