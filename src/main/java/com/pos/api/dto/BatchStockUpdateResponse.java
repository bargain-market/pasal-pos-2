package com.pos.api.dto;

import java.util.List;

/**
 * Batch stock update response DTO
 */
public class BatchStockUpdateResponse {
    public Boolean success;
    public String message;
    public Integer adjustmentsProcessed;
    public Integer reconciliationsProcessed;
    public List<String> inventoryLogIds;
}
