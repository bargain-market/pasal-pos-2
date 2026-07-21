package com.pos.api.dto;

import java.util.List;

/**
 * Response from backend after syncing departments
 */
public class BatchDepartmentSyncResponse {
    public int processed;
    public int failed;
    public List<DepartmentSyncResult> results;
    
    /**
     * Result for each department sync attempt
     */
    public static class DepartmentSyncResult {
        public String departmentId;
        public String status; // "created", "updated", "failed"
        public String serverId;
        public String error;
    }
}

