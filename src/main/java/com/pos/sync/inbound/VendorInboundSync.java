package com.pos.sync.inbound;

import com.pos.api.ApiClient;
import com.pos.database.DatabaseManager;
import com.pos.sync.SyncDirection;
import com.pos.sync.SyncManager;
import com.pos.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Inbound sync handler for vendors.
 * Pulls vendor data from backend and stores in local database.
 * 
 * <h2>Sync Flow</h2>
 * <pre>
 * Backend API ──────────────────────────────────────────────────────────────
 *     │
 *     │ GET /pos/vendors/sync?full=true (full sync)
 *     │ GET /pos/vendors/sync?since={timestamp} (incremental sync)
 *     │
 *     ▼
 * VendorInboundSync ────────────────────────────────────────────────────────
 *     │
 *     │ Parse response
 *     │ Validate data
 *     │ Batch insert/update
 *     │
 *     ▼
 * Local H2 Database ────────────────────────────────────────────────────────
 *     • vendors table
 * </pre>
 */
public class VendorInboundSync implements SyncManager.InboundSyncHandler {
    private static final Logger logger = LoggerFactory.getLogger(VendorInboundSync.class);

    private static VendorInboundSync instance;

    private final ApiClient apiClient;
    private final DatabaseManager dbManager;

    private VendorInboundSync() {
        this.apiClient = ApiClient.getInstance();
        this.dbManager = DatabaseManager.getInstance();
    }

    public static synchronized VendorInboundSync getInstance() {
        if (instance == null) {
            instance = new VendorInboundSync();
        }
        return instance;
    }

    @Override
    public String getName() {
        return "VendorSync";
    }

    @Override
    public SyncResult sync(String lastSyncTime) throws Exception {
        boolean isFullSync = (lastSyncTime == null || lastSyncTime.isEmpty());

        logger.info("🏪 Starting {} vendor sync", isFullSync ? "full" : "incremental");

        try {
            // Build API URL
            String endpoint = isFullSync
                    ? "/pos/vendors/sync?full=true"
                    : "/pos/vendors/sync?since=" + lastSyncTime;

            logger.info("🔗 Calling vendor sync endpoint: {}", endpoint);

            // Call backend API
            ApiClient.ApiResponse<VendorSyncResponse> response = apiClient.get(
                    endpoint,
                    VendorSyncResponse.class);

            VendorSyncResponse syncData = response.getData();

            if (syncData == null) {
                logger.warn("⚠️ Vendor sync response data is null");
                return SyncResult.empty(SyncDirection.INBOUND);
            }

            if (syncData.vendors == null) {
                logger.warn("⚠️ Vendor sync response vendors array is null (totalVendors: {})", syncData.totalVendors);
                return SyncResult.empty(SyncDirection.INBOUND);
            }

            if (syncData.vendors.isEmpty()) {
                logger.info("📭 No vendors in sync response (totalVendors: {})", syncData.totalVendors);
                return SyncResult.empty(SyncDirection.INBOUND);
            }

            logger.info("📦 Received {} vendors from backend", syncData.vendors.size());

            // Store vendors
            int vendorsStored = storeVendors(syncData.vendors);
            logger.info("✅ Synced {} vendors to local database", vendorsStored);

            return SyncResult.success(SyncDirection.INBOUND, vendorsStored);

        } catch (ApiClient.ApiException e) {
            logger.error("❌ Vendor sync API error: {} (status: {})", e.getMessage(), e.getStatusCode());
            return SyncResult.failure(SyncDirection.INBOUND, e.getMessage());
        } catch (Exception e) {
            logger.error("❌ Vendor sync failed with unexpected error", e);
            return SyncResult.failure(SyncDirection.INBOUND, e.getMessage());
        }
    }

    /**
     * Store vendors in local database using batch insert.
     */
    private int storeVendors(List<VendorSyncData> vendors) throws SQLException {
        if (vendors == null || vendors.isEmpty()) {
            return 0;
        }

        try (Connection conn = dbManager.getConnection()) {
            String sql = "MERGE INTO vendors " +
                    "(id, name, contact_name, email, phone, address, payment_terms, commission_rate, " +
                    "default_cost_margin, bank_account_info, notes, is_active, synced, updated_at) " +
                    "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            int successCount = 0;

            try {
                conn.setAutoCommit(false);

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    for (VendorSyncData vendor : vendors) {
                        try {
                            if (vendor.id == null || vendor.name == null) {
                                logger.warn("Skipping vendor with missing required fields: id={}, name={}",
                                        vendor.id, vendor.name);
                                continue;
                            }

                            stmt.setString(1, vendor.id);
                            stmt.setString(2, vendor.name);
                            stmt.setString(3, vendor.contactName);
                            stmt.setString(4, vendor.email);
                            stmt.setString(5, vendor.phone);
                            stmt.setString(6, vendor.address);
                            stmt.setString(7, vendor.paymentTerms != null ? vendor.paymentTerms : "NET30");
                            stmt.setBigDecimal(8, vendor.commissionRate != null ? BigDecimal.valueOf(vendor.commissionRate) : BigDecimal.ZERO);
                            stmt.setBigDecimal(9, vendor.defaultCostMargin != null ? BigDecimal.valueOf(vendor.defaultCostMargin) : BigDecimal.ZERO);
                            stmt.setString(10, vendor.bankAccountInfo);
                            stmt.setString(11, vendor.notes);
                            stmt.setBoolean(12, vendor.isActive != null ? vendor.isActive : true);
                            stmt.setBoolean(13, true); // synced = TRUE (vendors from backend are synced)
                            stmt.setString(14, vendor.updatedAt != null ? vendor.updatedAt : "");

                            stmt.addBatch();
                            successCount++;

                        } catch (SQLException e) {
                            logger.warn("Error preparing vendor {} for batch: {}", vendor.id, e.getMessage());
                        }
                    }

                    stmt.executeBatch();
                    conn.commit();
                }

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }

            return successCount;
        }
    }

    /**
     * Get the count of vendors in the local database.
     */
    public int getLocalVendorCount() {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT COUNT(*) FROM vendors WHERE is_active = TRUE";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting local vendor count", e);
        }
        return 0;
    }

    /**
     * Trigger a manual vendor sync.
     * @return SyncResult with the sync outcome
     */
    public SyncResult triggerManualSync() {
        logger.info("🔄 Manual vendor sync triggered");
        try {
            return sync(null); // Full sync
        } catch (Exception e) {
            logger.error("Manual vendor sync failed", e);
            return SyncResult.failure(SyncDirection.INBOUND, e.getMessage());
        }
    }

    // ==================== DTOs ====================

    /**
     * Vendor sync response from backend
     */
    public static class VendorSyncResponse {
        public List<VendorSyncData> vendors;
        public Integer totalVendors;
        public String syncTime;
    }

    /**
     * Vendor sync data from backend
     */
    public static class VendorSyncData {
        public String id;
        public String name;
        public String contactName;
        public String email;
        public String phone;
        public String address;
        public String paymentTerms;
        public Double commissionRate;
        public Double defaultCostMargin;
        public String bankAccountInfo;
        public String notes;
        public Boolean isActive;
        public String updatedAt;
    }
}
