package com.pos.sync.inbound;

import com.pos.api.ApiClient;
import com.pos.api.dto.StoreSettingsResponse;
import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.sync.SyncDirection;
import com.pos.sync.SyncManager;
import com.pos.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.Instant;

/**
 * Inbound sync handler for store settings.
 * Pulls store settings from backend and stores in local database.
 * 
 * <h2>Sync Flow</h2>
 * <pre>
 * Backend API ──────────────────────────────────────────────────────────────
 *     │
 *     │ GET /pos/settings
 *     │
 *     ▼
 * SettingsInboundSync ──────────────────────────────────────────────────────
 *     │
 *     │ Parse response
 *     │ Store settings locally
 *     │
 *     ▼
 * Local H2 Database ────────────────────────────────────────────────────────
 *     • store_settings table
 * </pre>
 * 
 * <h2>Conflict Resolution</h2>
 * Backend always wins. Local settings are completely replaced by backend settings.
 * This is a one-way sync (backend → local only).
 */
public class SettingsInboundSync implements SyncManager.InboundSyncHandler {
    private static final Logger logger = LoggerFactory.getLogger(SettingsInboundSync.class);
    
    private static SettingsInboundSync instance;
    
    private final ApiClient apiClient;
    private final DatabaseManager dbManager;
    private final ConfigManager config;
    
    private SettingsInboundSync() {
        this.apiClient = ApiClient.getInstance();
        this.dbManager = DatabaseManager.getInstance();
        this.config = ConfigManager.getInstance();
    }
    
    public static synchronized SettingsInboundSync getInstance() {
        if (instance == null) {
            instance = new SettingsInboundSync();
        }
        return instance;
    }
    
    @Override
    public String getName() {
        return "SettingsSync";
    }
    
    @Override
    public SyncResult sync(String lastSyncTime) throws Exception {
        logger.info("Starting store settings sync");
        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter("/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
            String lastSync = lastSyncTime != null ? lastSyncTime : "null";
            fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis() + ",\"location\":\"SettingsInboundSync.java:68\",\"message\":\"sync entry\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"C\",\"lastSyncTime\":\"" + lastSync + "\"}}\n");
            fw.close();
        } catch (Exception e) {}
        // #endregion
        
        // Get device store ID
        String deviceStoreId = config.getProperty("store.id");
        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter("/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
            String storeId = deviceStoreId != null ? deviceStoreId : "null";
            fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis() + ",\"location\":\"SettingsInboundSync.java:72\",\"message\":\"Checking store.id\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"D\",\"deviceStoreId\":\"" + storeId + "\",\"isEmpty\":" + (deviceStoreId == null || deviceStoreId.isEmpty()) + "}}\n");
            fw.close();
        } catch (Exception e) {}
        // #endregion
        if (deviceStoreId == null || deviceStoreId.isEmpty()) {
            logger.warn("Device not registered to a store, skipping settings sync");
            return SyncResult.empty(SyncDirection.INBOUND);
        }
        
        try {
            // Call backend API to get store settings
            ApiClient.ApiResponse<StoreSettingsResponse> response = apiClient.get(
                "/pos/settings",
                StoreSettingsResponse.class
            );
            
            StoreSettingsResponse settingsData = response.getData();
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter("/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
                String storeId = settingsData != null && settingsData.store != null ? settingsData.store.id : "null";
                String storeName = settingsData != null && settingsData.store != null ? settingsData.store.name : "null";
                String currency = settingsData != null && settingsData.settings != null ? settingsData.settings.currency : "null";
                fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis() + ",\"location\":\"SettingsInboundSync.java:85\",\"message\":\"API response received\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"C\",\"settingsDataNull\":" + (settingsData == null) + ",\"storeId\":\"" + storeId + "\",\"storeName\":\"" + storeName + "\",\"currency\":\"" + currency + "\"}}\n");
                fw.close();
            } catch (Exception e) {}
            // #endregion
            
            if (settingsData == null) {
                logger.warn("Empty settings response from backend");
                return SyncResult.empty(SyncDirection.INBOUND);
            }
            
            // Store settings in local database
            int synced = storeSettings(settingsData);
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter("/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
                fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis() + ",\"location\":\"SettingsInboundSync.java:93\",\"message\":\"Settings stored\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"C\",\"synced\":" + synced + "}}\n");
                fw.close();
            } catch (Exception e) {}
            // #endregion
            
            logger.info("Synced store settings");
            return SyncResult.success(SyncDirection.INBOUND, synced);
            
        } catch (ApiClient.ApiException e) {
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter("/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
                String errorMsg = e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "null";
                fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis() + ",\"location\":\"SettingsInboundSync.java:99\",\"message\":\"Sync failed\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"C\",\"error\":\"" + errorMsg + "\"}}\n");
                fw.close();
            } catch (Exception ex) {}
            // #endregion
            logger.error("Store settings sync failed: {}", e.getMessage());
            return SyncResult.failure(SyncDirection.INBOUND, e.getMessage());
        }
    }
    
    /**
     * Store settings in local database.
     */
    private int storeSettings(StoreSettingsResponse settingsData) throws SQLException {
        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter("/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
            String storeId = settingsData.store != null ? settingsData.store.id : "null";
            String storeName = settingsData.store != null ? settingsData.store.name : "null";
            fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis() + ",\"location\":\"SettingsInboundSync.java:107\",\"message\":\"storeSettings entry\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"C\",\"storeId\":\"" + storeId + "\",\"storeName\":\"" + storeName + "\"}}\n");
            fw.close();
        } catch (Exception e) {}
        // #endregion
        try (Connection conn = dbManager.getConnection()) {
        // H2 uses MERGE INTO for upsert
        String sql = "MERGE INTO store_settings " +
            "(id, store_id, store_name, store_phone, store_address, tax_enabled, tax_rate, currency, timezone, " +
            "receipt_header, receipt_footer, low_stock_threshold, minimum_sale_amount, card_surcharge_enabled, " +
            "card_surcharge_percent, require_manager_approval_voids, require_manager_approval_discounts_over, " +
            "require_manager_approval_refunds, updated_at) " +
            "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        // SQL for global settings (card surcharge is now global, not per-store)
        String globalSettingsSql = "MERGE INTO global_settings " +
            "(id, card_surcharge_enabled, card_surcharge_percent, updated_at) " +
            "KEY (id) VALUES ('global', ?, ?, ?)";

        try {
            conn.setAutoCommit(false);
            String storeIdBeforeSettings = config.getProperty("store.id");
            
            // Save store settings (without card surcharge - that's now global)
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                String settingsId = "1"; // Single row for settings
                String syncedAt = Instant.now().toString();
                
                stmt.setString(1, settingsId);
                stmt.setString(2, settingsData.store.id);
                stmt.setString(3, settingsData.store.name);
                stmt.setString(4, settingsData.store.phone);
                stmt.setString(5, settingsData.store.address);

                // Update configuration and in-memory cache
                config.setProperty("store.id", settingsData.store.id);
                config.setProperty("store.name", settingsData.store.name);
                com.pos.service.StoreService.getInstance().updateStoreInfo(settingsData.store.id, settingsData.store.name);
                if (settingsData.store.id != null
                        && (storeIdBeforeSettings == null
                        || !settingsData.store.id.equals(storeIdBeforeSettings))) {
                    try {
                        com.pos.hardware.HardwareManager.getInstance().reinitialize();
                    } catch (Exception e) {
                        logger.warn("Could not reinitialize hardware after store id change in settings sync: {}",
                                e.getMessage());
                    }
                }
                stmt.setBoolean(6, settingsData.settings.taxEnabled);
                stmt.setDouble(7, settingsData.settings.taxRate);
                stmt.setString(8, settingsData.settings.currency);
                stmt.setString(9, settingsData.settings.timezone);
                stmt.setString(10, settingsData.settings.receiptHeader);
                stmt.setString(11, settingsData.settings.receiptFooter);
                stmt.setInt(12, settingsData.settings.lowStockThreshold);
                BigDecimal minSaleToStore = resolveMinimumSaleAmountForSync(conn, settingsData.settings.minimumSaleAmount);
                if (minSaleToStore != null && minSaleToStore.compareTo(BigDecimal.ZERO) > 0) {
                    stmt.setBigDecimal(13, minSaleToStore);
                } else {
                    stmt.setNull(13, java.sql.Types.DECIMAL);
                }
                // Card surcharge fields kept for table compatibility but not used (now in global_settings)
                stmt.setBoolean(14, false);
                stmt.setObject(15, null);
                stmt.setBoolean(16, settingsData.settings.requireManagerApproval.voids);
                stmt.setDouble(17, settingsData.settings.requireManagerApproval.discountsOver);
                stmt.setBoolean(18, settingsData.settings.requireManagerApproval.refunds);
                stmt.setString(19, syncedAt);
                
                stmt.executeUpdate();
            }
            
            // Save global card surcharge settings
            try (PreparedStatement globalStmt = conn.prepareStatement(globalSettingsSql)) {
                String syncedAt = Instant.now().toString();

                globalStmt.setBoolean(1, settingsData.settings.cardSurchargeEnabled);
                globalStmt.setObject(2, settingsData.settings.cardSurchargePercent);
                globalStmt.setString(3, syncedAt);

                globalStmt.executeUpdate();
                logger.info("Synced global card surcharge settings: enabled={}, percent={}",
                    settingsData.settings.cardSurchargeEnabled,
                    settingsData.settings.cardSurchargePercent);
            }

            // Save cash/check limit settings if present
            if (settingsData.settings.cashCheckLimits != null) {
                // Store the PIN hash in global_settings
                if (settingsData.settings.cashCheckLimits.manualCashCheckPinHash != null) {
                    try (PreparedStatement pinStmt = conn.prepareStatement(
                            "MERGE INTO global_settings (id, manual_cash_check_pin_hash, updated_at) KEY (id) VALUES ('global', ?, ?)")) {
                        pinStmt.setString(1, settingsData.settings.cashCheckLimits.manualCashCheckPinHash);
                        pinStmt.setString(2, Instant.now().toString());
                        pinStmt.executeUpdate();
                        logger.info("Synced manual cash/check PIN hash");
                    }
                }

                // Store role cash/check limits
                if (settingsData.settings.cashCheckLimits.roles != null) {
                    String roleLimitSql = "MERGE INTO role_cash_check_limits (role_id, role_name, limit_enabled, daily_limit, updated_at) KEY (role_id) VALUES (?, ?, ?, ?, ?)";
                    try (PreparedStatement roleStmt = conn.prepareStatement(roleLimitSql)) {
                        for (StoreSettingsResponse.SettingsInfo.CashCheckLimits.RoleCashCheckLimit role : settingsData.settings.cashCheckLimits.roles) {
                            roleStmt.setString(1, role.roleId);
                            roleStmt.setString(2, role.roleName);
                            roleStmt.setBoolean(3, role.limitEnabled);
                            roleStmt.setInt(4, role.dailyLimit);
                            roleStmt.setString(5, Instant.now().toString());
                            roleStmt.executeUpdate();
                        }
                        logger.info("Synced {} role cash/check limits", settingsData.settings.cashCheckLimits.roles.size());
                    }
                }
            }
            
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter("/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
                fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis() + ",\"location\":\"SettingsInboundSync.java:143\",\"message\":\"Database update executed\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"C\",\"rowsUpdated\":1}}\n");
                fw.close();
            } catch (Exception e) {}
            // #endregion
            conn.commit();
            
        } catch (SQLException e) {
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter("/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
                String errorMsg = e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "null";
                fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis() + ",\"location\":\"SettingsInboundSync.java:148\",\"message\":\"Database error\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"C\",\"error\":\"" + errorMsg + "\"}}\n");
                fw.close();
            } catch (Exception ex) {}
            // #endregion
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
        } // end try-with-resources (connection)

        return 1; // Settings are stored as a single record
    }

    /**
     * When the API omits minimum sale (null), keep the value already stored on this register.
     * When the API sends 0 or negative, clear. When it sends a positive value, use it.
     */
    private BigDecimal resolveMinimumSaleAmountForSync(Connection conn, Double apiMinimumSaleAmount)
            throws SQLException {
        if (apiMinimumSaleAmount != null && apiMinimumSaleAmount > 0) {
            return BigDecimal.valueOf(apiMinimumSaleAmount).setScale(2, RoundingMode.HALF_UP);
        }
        if (apiMinimumSaleAmount != null && apiMinimumSaleAmount <= 0) {
            return null;
        }
        return readExistingMinimumSaleAmount(conn);
    }

    private BigDecimal readExistingMinimumSaleAmount(Connection conn) throws SQLException {
        try (PreparedStatement q = conn.prepareStatement(
                "SELECT minimum_sale_amount FROM store_settings WHERE id = '1'")) {
            try (ResultSet rs = q.executeQuery()) {
                if (rs.next()) {
                    BigDecimal m = rs.getBigDecimal(1);
                    if (m != null && m.compareTo(BigDecimal.ZERO) > 0) {
                        return m.setScale(2, RoundingMode.HALF_UP);
                    }
                }
            }
        }
        return null;
    }
}

