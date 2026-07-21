package com.pos.api.dto;

import java.util.List;

/**
 * Request DTO for batch sale submission
 */
public class BatchSaleRequest {
    public List<SaleSubmission> sales;
    public String deviceId;
    public String syncTimestamp;
    
    public BatchSaleRequest() {}
    
    public BatchSaleRequest(List<SaleSubmission> sales, String deviceId, String syncTimestamp) {
        this.sales = sales;
        this.deviceId = deviceId;
        this.syncTimestamp = syncTimestamp;
    }
}

