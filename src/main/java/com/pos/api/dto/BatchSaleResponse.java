package com.pos.api.dto;

import java.util.List;

/**
 * Response DTO for batch sale submission
 */
public class BatchSaleResponse {
    public int processed;
    public int failed;
    public List<BatchSaleResult> results;
    
    public static class BatchSaleResult {
        public String saleId;
        public String status; // "created", "duplicate", "failed"
        public String serverId;
        public String error;
    }
}

