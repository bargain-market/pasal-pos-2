package com.pos.api.dto;

import java.util.List;

/**
 * Batch stock update request DTO
 */
public class BatchStockUpdateRequest {
    public List<StockAdjustmentRequest> adjustments;
    public List<StockReconciliationRequest> reconciliations;
}
