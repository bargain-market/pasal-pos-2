package com.pos.api.dto;

import java.util.List;

/**
 * Response from backend after syncing products
 */
public class BatchProductSyncResponse {
    public int processed;
    public int failed;
    public List<ProductSyncResult> results;
    /** Department IDs referenced by products but not found in backend. POS should re-sync these. */
    public List<String> missingDepartmentIds;
    
    /**
     * Result for each product sync attempt
     */
    public static class ProductSyncResult {
        public String productId;
        public String status; // "created", "updated", "failed"
        public String serverId;
        public String error;
    }
}

