package com.pos.sync.outbound;

import com.pos.api.ApiClient;
import com.pos.database.DatabaseManager;
import com.pos.sync.SyncDirection;
import com.pos.sync.SyncManager;
import com.pos.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Outbound sync handler for cash/check limit settings.
 * Pushes local settings (PIN hash, role limits) from POS to backend.
 *
 * <h2>Sync Flow</h2>
 * <pre>
 * Local H2 Database ────────────────────────────────────────────────────────
 *     • global_settings table (manual_cash_check_pin_hash)
 *     • role_cash_check_limits table
 *     │
 *     ▼
 * CashCheckSettingsOutboundSync ──────────────────────────────────────────────
 *     │
 *     │ Get local settings
 *     │ Submit to backend
 *     │
 *     ▼
 * Backend API ──────────────────────────────────────────────────────────────
 *     POST /pos/settings/cash-check-limits
 * </pre>
 */
public class CashCheckSettingsOutboundSync implements SyncManager.OutboundSyncHandler {
    private static final Logger logger = LoggerFactory.getLogger(CashCheckSettingsOutboundSync.class);

    private static CashCheckSettingsOutboundSync instance;

    private final ApiClient apiClient;
    private final DatabaseManager dbManager;

    private CashCheckSettingsOutboundSync() {
        this.apiClient = ApiClient.getInstance();
        this.dbManager = DatabaseManager.getInstance();
    }

    public static synchronized CashCheckSettingsOutboundSync getInstance() {
        if (instance == null) {
            instance = new CashCheckSettingsOutboundSync();
        }
        return instance;
    }

    @Override
    public String getName() {
        return "CashCheckSettingsSync";
    }

    @Override
    public SyncResult sync() throws Exception {
        try {
            // Get local settings
            String pinHash = getPinHash();
            List<RoleLimitData> roleLimits = getRoleLimits();

            if (pinHash == null && roleLimits.isEmpty()) {
                logger.debug("No local cash/check settings to sync");
                return SyncResult.empty(SyncDirection.OUTBOUND);
            }

            logger.info("Syncing cash/check settings: {} roles with limits, PIN configured: {}",
                roleLimits.size(), pinHash != null);

            // Build request
            CashCheckSettingsRequest request = new CashCheckSettingsRequest();
            request.manualCashCheckPinHash = pinHash;
            request.roleLimits = roleLimits;

            // Submit to backend
            ApiClient.ApiResponse<SyncResponse> response = apiClient.post(
                "/pos/settings/cash-check-limits",
                request,
                SyncResponse.class
            );

            SyncResponse body = response.getData();
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300
                    && body != null && body.success) {
                logger.info("Cash/check settings synced successfully");
                return SyncResult.success(SyncDirection.OUTBOUND, roleLimits.size() + (pinHash != null ? 1 : 0));
            } else {
                logger.warn("Failed to sync cash/check settings: HTTP {} or unsuccessful body", response.getStatusCode());
                return SyncResult.failure(SyncDirection.OUTBOUND, "Cash/check settings sync rejected");
            }

        } catch (Exception e) {
            logger.error("Error syncing cash/check settings", e);
            return SyncResult.failure(SyncDirection.OUTBOUND, e.getMessage());
        }
    }

    private String getPinHash() throws SQLException {
        String sql = "SELECT manual_cash_check_pin_hash FROM global_settings WHERE id = 'global'";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getString("manual_cash_check_pin_hash");
            }
        }
        return null;
    }

    private List<RoleLimitData> getRoleLimits() throws SQLException {
        List<RoleLimitData> limits = new ArrayList<>();
        String sql = "SELECT role_id, role_name, limit_enabled, daily_limit FROM role_cash_check_limits";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                RoleLimitData limit = new RoleLimitData();
                limit.roleId = rs.getString("role_id");
                limit.roleName = rs.getString("role_name");
                limit.limitEnabled = rs.getBoolean("limit_enabled");
                limit.dailyLimit = rs.getInt("daily_limit");
                limits.add(limit);
            }
        }
        return limits;
    }

    /**
     * Request DTO for syncing cash/check settings
     */
    public static class CashCheckSettingsRequest {
        public String manualCashCheckPinHash;
        public List<RoleLimitData> roleLimits;
    }

    /**
     * Role limit data
     */
    public static class RoleLimitData {
        public String roleId;
        public String roleName;
        public boolean limitEnabled;
        public int dailyLimit;
    }

    /**
     * Response from backend
     */
    public static class SyncResponse {
        public boolean success;
        public String message;
    }
}
