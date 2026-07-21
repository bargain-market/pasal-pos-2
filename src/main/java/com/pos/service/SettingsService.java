package com.pos.service;

import com.pos.api.ApiClient;
import com.pos.api.dto.StoreSettingsResponse;
import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Arrays;
import java.util.List;

/**
 * Service for managing store settings
 * Reads from local database first, falls back to backend API if needed
 */
public class SettingsService {
    private static final Logger logger = LoggerFactory.getLogger(SettingsService.class);
    private static SettingsService instance;

    private final ApiClient apiClient;
    private final ConfigManager config;
    private final DatabaseManager dbManager;
    private StoreSettingsResponse.StoreSettingsData cachedSettings;
    private CardSurchargeSettings cachedCardSurchargeSettings;
    private final java.util.Map<String, MultiPackDiscountSettings> multiPackSettingsCache = new ConcurrentHashMap<>();

    private SettingsService() {
        this.apiClient = ApiClient.getInstance();
        this.config = ConfigManager.getInstance();
        this.dbManager = DatabaseManager.getInstance();
    }

    public static synchronized SettingsService getInstance() {
        if (instance == null) {
            instance = new SettingsService();
        }
        return instance;
    }

    /**
     * Fetch and cache store settings from backend
     */
    public StoreSettingsResponse.StoreSettingsData fetchSettings() throws ApiClient.ApiException {
        logger.info("Fetching store settings from backend");
        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter(
                    "/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
            fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis()
                    + ",\"location\":\"SettingsService.java:37\",\"message\":\"fetchSettings entry\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"A\"}}\n");
            fw.close();
        } catch (Exception e) {
        }
        // #endregion

        try {
            ApiClient.ApiResponse<StoreSettingsResponse> response = apiClient.get(
                    "/pos/settings",
                    StoreSettingsResponse.class);

            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter(
                        "/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
                String dataClass = response.getData() != null ? response.getData().getClass().getName() : "null";
                String storeName = response.getData() != null && response.getData().store != null
                        ? response.getData().store.name
                        : "null";
                fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis()
                        + ",\"location\":\"SettingsService.java:45\",\"message\":\"API response received\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"A\",\"responseDataNull\":"
                        + (response.getData() == null) + ",\"responseDataClass\":\"" + dataClass + "\",\"storeName\":\""
                        + storeName + "\"}}\n");
                fw.close();
            } catch (Exception e) {
            }
            // #endregion

            cachedSettings = response.getData().getSettings();

            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter(
                        "/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
                String storeName = cachedSettings != null
                        ? (cachedSettings.storeName != null ? cachedSettings.storeName : "null")
                        : "null";
                String currency = cachedSettings != null
                        ? (cachedSettings.currency != null ? cachedSettings.currency : "null")
                        : "null";
                fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis()
                        + ",\"location\":\"SettingsService.java:63\",\"message\":\"After parsing settings\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run2\",\"hypothesisId\":\"A\",\"cachedSettingsNull\":"
                        + (cachedSettings == null) + ",\"storeName\":\"" + storeName + "\",\"currency\":\"" + currency
                        + "\"}}\n");
                fw.close();
            } catch (Exception e) {
            }
            // #endregion

            // Store settings locally in config and database
            if (cachedSettings != null) {
                applySettings(cachedSettings);
                saveSettingsToDatabase(cachedSettings);
            }

            logger.info("Store settings fetched successfully");
            return cachedSettings;
        } catch (ApiClient.ApiException e) {
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter(
                        "/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
                String errorMsg = e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "null";
                fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis()
                        + ",\"location\":\"SettingsService.java:56\",\"message\":\"API exception\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"A\",\"error\":\""
                        + errorMsg + "\"}}\n");
                fw.close();
            } catch (Exception ex) {
            }
            // #endregion
            logger.error("Failed to fetch store settings", e);
            throw e;
        }
    }

    /**
     * Get cached settings (reads from local database first, falls back to backend
     * if needed)
     */
    public StoreSettingsResponse.StoreSettingsData getSettings() {

        // Return cached if available
        if (cachedSettings != null) {
            return cachedSettings;
        }

        // Try to load from local database first
        try {
            StoreSettingsResponse.StoreSettingsData dbSettings = loadSettingsFromDatabase();
            if (dbSettings != null) {
                cachedSettings = dbSettings;
                // #region agent log
                try {
                    java.io.FileWriter fw = new java.io.FileWriter(
                            "/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
                    String storeName = dbSettings.storeName != null ? dbSettings.storeName : "null";
                    String currency = dbSettings.currency != null ? dbSettings.currency : "null";
                    fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":"
                            + System.currentTimeMillis()
                            + ",\"location\":\"SettingsService.java:118\",\"message\":\"getSettings from database\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run2\",\"hypothesisId\":\"B\",\"storeName\":\""
                            + storeName + "\",\"currency\":\"" + currency + "\"}}\n");
                    fw.close();
                } catch (Exception e) {
                }
                // #endregion
                applySettings(dbSettings);
                return dbSettings;
            }
        } catch (Exception e) {
            logger.warn("Failed to load settings from database", e);
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter(
                        "/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
                String errorMsg = e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "null";
                fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis()
                        + ",\"location\":\"SettingsService.java:125\",\"message\":\"Database load failed\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run2\",\"hypothesisId\":\"B\",\"error\":\""
                        + errorMsg + "\"}}\n");
                fw.close();
            } catch (Exception ex) {
            }
            // #endregion
        }

        // Fall back to backend API if database is empty
        try {
            fetchSettings();
            // Save to database after fetching
            if (cachedSettings != null) {
                saveSettingsToDatabase(cachedSettings);
            }
        } catch (ApiClient.ApiException e) {
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter(
                        "/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
                String errorMsg = e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "null";
                fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis()
                        + ",\"location\":\"SettingsService.java:135\",\"message\":\"Using defaults\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run2\",\"hypothesisId\":\"B\",\"error\":\""
                        + errorMsg + "\"}}\n");
                fw.close();
            } catch (Exception ex) {
            }
            // #endregion
            logger.warn("Could not fetch settings, using defaults", e);
            try {
                StoreSettingsResponse.StoreSettingsData dbRetry = loadSettingsFromDatabase();
                if (dbRetry != null) {
                    cachedSettings = dbRetry;
                    applySettings(dbRetry);
                    return dbRetry;
                }
            } catch (SQLException sqlEx) {
                logger.warn("Could not load local store_settings after fetch failure", sqlEx);
            }
            StoreSettingsResponse.StoreSettingsData defaults = getDefaultSettings();
            mergeMinimumSaleFromDatabase(defaults);
            return defaults;
        }

        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter(
                    "/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
            String storeName = cachedSettings != null
                    ? (cachedSettings.storeName != null ? cachedSettings.storeName : "null")
                    : "null";
            String currency = cachedSettings != null
                    ? (cachedSettings.currency != null ? cachedSettings.currency : "null")
                    : "null";
            fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis()
                    + ",\"location\":\"SettingsService.java:143\",\"message\":\"getSettings returning\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run2\",\"hypothesisId\":\"B\",\"storeName\":\""
                    + storeName + "\",\"currency\":\"" + currency + "\"}}\n");
            fw.close();
        } catch (Exception e) {
        }
        // #endregion
        return cachedSettings;
    }

    /**
     * Load settings from local database
     */
    private StoreSettingsResponse.StoreSettingsData loadSettingsFromDatabase() throws SQLException {
        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter(
                    "/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
            fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis()
                    + ",\"location\":\"SettingsService.java:151\",\"message\":\"loadSettingsFromDatabase entry\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run2\",\"hypothesisId\":\"B\"}}\n");
            fw.close();
        } catch (Exception e) {
        }
        // #endregion

        String sql = "SELECT store_id, store_name, store_phone, store_address, tax_enabled, tax_rate, currency, timezone, "
                + "receipt_header, receipt_footer, low_stock_threshold, minimum_sale_amount, card_surcharge_enabled, "
                + "card_surcharge_percent, require_manager_approval_voids, "
                + "require_manager_approval_discounts_over, require_manager_approval_refunds "
                + "FROM store_settings WHERE id = '1'";

        try (Connection conn = dbManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery()) {

                if (rs.next()) {
                    StoreSettingsResponse.StoreSettingsData settings = new StoreSettingsResponse.StoreSettingsData();
                    settings.storeId = rs.getString("store_id");
                    settings.storeName = rs.getString("store_name");
                    settings.storePhone = rs.getString("store_phone");
                    settings.storeAddress = rs.getString("store_address");
                    settings.currency = rs.getString("currency");
                    settings.timezone = rs.getString("timezone");
                    settings.dateFormat = "MM/dd/yyyy"; // Default
                    settings.timeFormat = "HH:mm"; // Default

                    // Map tax settings
                    boolean taxEnabled = rs.getBoolean("tax_enabled");
                    double taxRate = rs.getDouble("tax_rate");
                    if (taxEnabled && taxRate > 0) {
                        settings.taxSettings = new StoreSettingsResponse.TaxSettings();
                        settings.taxSettings.defaultTaxRate = BigDecimal.valueOf(taxRate / 100.0); // Convert percentage to
                                                                                                   // decimal
                        settings.taxSettings.taxInclusive = false; // Default
                    }

                    // Map receipt settings
                    String receiptHeader = rs.getString("receipt_header");
                    String receiptFooter = rs.getString("receipt_footer");
                    if (receiptHeader != null || receiptFooter != null) {
                        settings.receiptSettings = new StoreSettingsResponse.ReceiptSettings();
                        settings.receiptSettings.headerText = receiptHeader;
                        settings.receiptSettings.footerText = receiptFooter;
                    }

                    java.math.BigDecimal minSale = rs.getBigDecimal("minimum_sale_amount");
                    if (minSale != null && minSale.compareTo(BigDecimal.ZERO) > 0) {
                        settings.minimumSaleAmount = minSale.setScale(2, RoundingMode.HALF_UP);
                    }

                    // #region agent log
                    try {
                        java.io.FileWriter fw = new java.io.FileWriter(
                                "/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
                        String storeName = settings.storeName != null ? settings.storeName : "null";
                        String currency = settings.currency != null ? settings.currency : "null";
                        fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":"
                                + System.currentTimeMillis()
                                + ",\"location\":\"SettingsService.java:195\",\"message\":\"loadSettingsFromDatabase success\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run2\",\"hypothesisId\":\"B\",\"storeName\":\""
                                + storeName + "\",\"currency\":\"" + currency + "\"}}\n");
                        fw.close();
                    } catch (Exception e) {
                    }
                    // #endregion

                    conn.commit();
                    return settings;
                }
            }
            conn.commit();
        }

        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter(
                    "/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
            fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis()
                    + ",\"location\":\"SettingsService.java:203\",\"message\":\"loadSettingsFromDatabase empty\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run2\",\"hypothesisId\":\"B\"}}\n");
            fw.close();
        } catch (Exception e) {
        }
        // #endregion

        return null; // No settings found in database
    }

    /**
     * Reads {@code minimum_sale_amount} for the primary settings row, if present.
     */
    private BigDecimal readExistingMinimumSaleAmount(Connection conn) throws SQLException {
        String sql = "SELECT minimum_sale_amount FROM store_settings WHERE id = '1'";
        try (PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                BigDecimal m = rs.getBigDecimal(1);
                if (m != null && m.compareTo(BigDecimal.ZERO) > 0) {
                    return m.setScale(2, RoundingMode.HALF_UP);
                }
            }
        }
        return null;
    }

    /**
     * Fills {@code target.minimumSaleAmount} from local DB when the row exists (offline / defaults path).
     */
    private void mergeMinimumSaleFromDatabase(StoreSettingsResponse.StoreSettingsData target) {
        try (Connection conn = dbManager.getConnection()) {
            BigDecimal m = readExistingMinimumSaleAmount(conn);
            if (m != null) {
                target.minimumSaleAmount = m;
            }
            conn.commit();
        } catch (SQLException e) {
            logger.debug("mergeMinimumSaleFromDatabase: {}", e.getMessage());
        }
    }

    /**
     * Reloads in-memory settings from {@code store_settings} after a local DB write (e.g. minimum sale).
     */
    private void reloadCachedSettingsFromLocalDbAfterWrite() {
        try {
            StoreSettingsResponse.StoreSettingsData fromDb = loadSettingsFromDatabase();
            if (fromDb != null) {
                cachedSettings = fromDb;
                applySettings(fromDb);
            }
        } catch (SQLException e) {
            logger.warn("Could not reload settings cache from local DB", e);
        }
    }

    /**
     * Save settings to local database
     */
    private void saveSettingsToDatabase(StoreSettingsResponse.StoreSettingsData settings) {
        // #region agent log
        try {
            java.io.FileWriter fw = new java.io.FileWriter(
                    "/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
            String storeName = settings.storeName != null ? settings.storeName : "null";
            fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis()
                    + ",\"location\":\"SettingsService.java:212\",\"message\":\"saveSettingsToDatabase entry\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run2\",\"hypothesisId\":\"B\",\"storeName\":\""
                    + storeName + "\"}}\n");
            fw.close();
        } catch (Exception e) {
        }
        // #endregion

        Connection conn = null;
        try {
            conn = dbManager.getConnection();
            String sql = "MERGE INTO store_settings " +
                    "(id, store_id, store_name, tax_enabled, tax_rate, currency, timezone, " +
                    "receipt_header, receipt_footer, low_stock_threshold, minimum_sale_amount, card_surcharge_enabled, " +
                    "card_surcharge_percent, require_manager_approval_voids, require_manager_approval_discounts_over, "
                    +
                    "require_manager_approval_refunds, updated_at) " +
                    "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                String settingsId = "1";
                String syncedAt = Instant.now().toString();

                BigDecimal minToWrite = settings.minimumSaleAmount;
                if (minToWrite == null || minToWrite.compareTo(BigDecimal.ZERO) <= 0) {
                    BigDecimal existingMin = readExistingMinimumSaleAmount(conn);
                    if (existingMin != null && existingMin.compareTo(BigDecimal.ZERO) > 0) {
                        minToWrite = existingMin;
                    }
                }

                stmt.setString(1, settingsId);
                stmt.setString(2, settings.storeId);
                stmt.setString(3, settings.storeName);
                stmt.setBoolean(4, settings.taxSettings != null && settings.taxSettings.defaultTaxRate != null);
                stmt.setDouble(5,
                        settings.taxSettings != null && settings.taxSettings.defaultTaxRate != null
                                ? settings.taxSettings.defaultTaxRate.multiply(BigDecimal.valueOf(100)).doubleValue()
                                : 0.0);
                stmt.setString(6, settings.currency);
                stmt.setString(7, settings.timezone);
                stmt.setString(8, settings.receiptSettings != null ? settings.receiptSettings.headerText : null);
                stmt.setString(9, settings.receiptSettings != null ? settings.receiptSettings.footerText : null);
                stmt.setInt(10, 10); // Default low stock threshold
                if (minToWrite != null && minToWrite.compareTo(BigDecimal.ZERO) > 0) {
                    stmt.setBigDecimal(11, minToWrite.setScale(2, RoundingMode.HALF_UP));
                } else {
                    stmt.setNull(11, Types.DECIMAL);
                }
                stmt.setBoolean(12, false); // Default card surcharge disabled
                stmt.setObject(13, null); // card_surcharge_percent
                stmt.setBoolean(14, true); // Default require manager approval for voids
                stmt.setDouble(15, 20.0); // Default require manager approval for discounts over
                stmt.setBoolean(16, true); // Default require manager approval for refunds
                stmt.setString(17, syncedAt);

                int rowsUpdated = stmt.executeUpdate();
                conn.commit();

                // #region agent log
                try {
                    java.io.FileWriter fw = new java.io.FileWriter(
                            "/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
                    fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":"
                            + System.currentTimeMillis()
                            + ",\"location\":\"SettingsService.java:245\",\"message\":\"saveSettingsToDatabase success\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run2\",\"hypothesisId\":\"B\",\"rowsUpdated\":"
                            + rowsUpdated + "}}\n");
                    fw.close();
                } catch (Exception e) {
                }
                // #endregion

                logger.info("Settings saved to local database");
            }
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    logger.error("Failed to rollback transaction", rollbackEx);
                }
            }
            // #region agent log
            try {
                java.io.FileWriter fw = new java.io.FileWriter(
                        "/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
                String errorMsg = e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "null";
                fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":" + System.currentTimeMillis()
                        + ",\"location\":\"SettingsService.java:253\",\"message\":\"saveSettingsToDatabase error\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run2\",\"hypothesisId\":\"B\",\"error\":\""
                        + errorMsg + "\"}}\n");
                fw.close();
            } catch (Exception ex) {
            }
            // #endregion
            logger.error("Failed to save settings to database", e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException e) {
                    logger.error("Failed to reset autocommit", e);
                }
                // Return the connection to the pool (previously leaked here).
                try {
                    conn.close();
                } catch (SQLException e) {
                    logger.error("Failed to close connection", e);
                }
            }
        }
    }

    /**
     * Apply settings to local configuration
     */
    private void applySettings(StoreSettingsResponse.StoreSettingsData settings) {
        if (settings.currency != null) {
            config.setProperty("store.currency", settings.currency);
        }
        if (settings.timezone != null) {
            config.setProperty("store.timezone", settings.timezone);
        }
        if (settings.dateFormat != null) {
            config.setProperty("store.dateFormat", settings.dateFormat);
        }
        if (settings.timeFormat != null) {
            config.setProperty("store.timeFormat", settings.timeFormat);
        }

        // Apply tax settings
        if (settings.taxSettings != null) {
            if (settings.taxSettings.defaultTaxRate != null) {
                config.setProperty("tax.defaultRate", settings.taxSettings.defaultTaxRate.toString());
            }
            if (settings.taxSettings.taxInclusive != null) {
                config.setProperty("tax.inclusive", settings.taxSettings.taxInclusive.toString());
            }
        }

        // Apply receipt settings
        if (settings.receiptSettings != null) {
            if (settings.receiptSettings.headerText != null) {
                config.setProperty("receipt.header", settings.receiptSettings.headerText);
            }
            if (settings.receiptSettings.footerText != null) {
                config.setProperty("receipt.footer", settings.receiptSettings.footerText);
            }
        }

        // Update StoreService cache
        if (settings.storeId != null && settings.storeName != null) {
            StoreService.getInstance().updateStoreInfo(settings.storeId, settings.storeName);
        }
    }

    /**
     * Get default tax rate
     */
    public BigDecimal getDefaultTaxRate() {
        StoreSettingsResponse.StoreSettingsData settings = getSettings();
        if (settings != null && settings.taxSettings != null && settings.taxSettings.defaultTaxRate != null) {
            return settings.taxSettings.defaultTaxRate;
        }
        return new BigDecimal("0.08"); // 8% default
    }

    /**
     * Check if tax is inclusive
     */
    public boolean isTaxInclusive() {
        StoreSettingsResponse.StoreSettingsData settings = getSettings();
        if (settings != null && settings.taxSettings != null && settings.taxSettings.taxInclusive != null) {
            return settings.taxSettings.taxInclusive;
        }
        return false; // Default to exclusive
    }

    /**
     * Get store name
     */
    public String getStoreName() {
        StoreSettingsResponse.StoreSettingsData settings = getSettings();
        if (settings != null && settings.storeName != null) {
            return settings.storeName;
        }
        return "POS Store";
    }

    /**
     * Get store address
     */
    public String getStoreAddress() {
        StoreSettingsResponse.StoreSettingsData settings = getSettings();
        if (settings != null && settings.storeAddress != null) {
            return settings.storeAddress;
        }
        return "";
    }

    /**
     * Get store phone
     */
    public String getStorePhone() {
        StoreSettingsResponse.StoreSettingsData settings = getSettings();
        if (settings != null && settings.storePhone != null) {
            return settings.storePhone;
        }
        return "";
    }

    /**
     * Get receipt header text
     */
    public String getReceiptHeader() {
        StoreSettingsResponse.StoreSettingsData settings = getSettings();
        if (settings != null && settings.receiptSettings != null && settings.receiptSettings.headerText != null) {
            return settings.receiptSettings.headerText;
        }
        return "Thank you for your purchase!";
    }

    /**
     * Get receipt footer text
     */
    public String getReceiptFooter() {
        StoreSettingsResponse.StoreSettingsData settings = getSettings();
        if (settings != null && settings.receiptSettings != null && settings.receiptSettings.footerText != null) {
            return settings.receiptSettings.footerText;
        }
        return "Have a great day!";
    }

    /**
     * Get currency symbol
     */
    public String getCurrency() {
        StoreSettingsResponse.StoreSettingsData settings = getSettings();
        if (settings != null && settings.currency != null) {
            return settings.currency;
        }
        return "USD";
    }

    /**
     * Get default settings
     */
    private StoreSettingsResponse.StoreSettingsData getDefaultSettings() {
        StoreSettingsResponse.StoreSettingsData settings = new StoreSettingsResponse.StoreSettingsData();
        settings.currency = "USD";
        settings.timezone = "UTC";
        settings.dateFormat = "MM/dd/yyyy";
        settings.timeFormat = "HH:mm";

        StoreSettingsResponse.TaxSettings taxSettings = new StoreSettingsResponse.TaxSettings();
        taxSettings.defaultTaxRate = new BigDecimal("0.08");
        taxSettings.taxInclusive = false;
        settings.taxSettings = taxSettings;

        StoreSettingsResponse.ReceiptSettings receiptSettings = new StoreSettingsResponse.ReceiptSettings();
        receiptSettings.headerText = "Thank you for your purchase!";
        receiptSettings.footerText = "Have a great day!";
        settings.receiptSettings = receiptSettings;

        return settings;
    }

    /**
     * Update local settings from WebSocket event or other source
     */
    public void updateSettings(StoreSettingsResponse.StoreSettingsData settingsData) {
        if (settingsData == null) {
            logger.warn("Attempted to update settings with null data");
            return;
        }

        this.cachedSettings = settingsData;
        applySettings(settingsData);
        saveSettingsToDatabase(settingsData);
        logger.info("Local settings updated from external source");
    }

    /**
     * Refresh settings from backend (forces fetch and updates database)
     */
    public void refreshSettings() throws ApiClient.ApiException {
        cachedSettings = null;
        fetchSettings();
        // fetchSettings() already saves to database, so we're done
    }

    /**
     * Clears the in-memory settings cache so the next read reloads from the database or API.
     */
    public void clearSettingsCache() {
        cachedSettings = null;
        cachedCardSurchargeSettings = null;
        multiPackSettingsCache.clear();
    }

    /**
     * Persists minimum sale amount on the local {@code store_settings} row so this POS enforces it immediately.
     * Inserts a minimal row if none exists yet (e.g. before first full settings sync).
     */
    public void updateMinimumSaleAmountLocally(BigDecimal amount) throws SQLException {
        String syncedAt = Instant.now().toString();
        try (Connection conn = dbManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE store_settings SET minimum_sale_amount = ?, updated_at = ? WHERE id = '1'")) {
                if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                    stmt.setNull(1, Types.DECIMAL);
                } else {
                    stmt.setBigDecimal(1, amount.setScale(2, RoundingMode.HALF_UP));
                }
                stmt.setString(2, syncedAt);
                int updated = stmt.executeUpdate();
                if (updated == 0) {
                    insertLocalStoreSettingsStubForMinimumSale(conn, amount, syncedAt);
                }
            }
            conn.commit();
            logger.info("Committed minimum_sale_amount to local store_settings (value={})",
                    amount == null || amount.compareTo(BigDecimal.ZERO) <= 0 ? "none" : amount.toPlainString());
        }
        reloadCachedSettingsFromLocalDbAfterWrite();
    }

    private void insertLocalStoreSettingsStubForMinimumSale(Connection conn, BigDecimal amount, String syncedAt)
            throws SQLException {
        String storeId = config.getProperty("store.id");
        if (storeId == null || storeId.isBlank()) {
            storeId = "local";
        }
        String storeName = config.getProperty("store.name");
        if (storeName == null || storeName.isBlank()) {
            storeName = "Store";
        }
        String sql = "INSERT INTO store_settings (id, store_id, store_name, minimum_sale_amount, updated_at) "
                + "VALUES ('1', ?, ?, ?, ?)";
        try (PreparedStatement ins = conn.prepareStatement(sql)) {
            ins.setString(1, storeId);
            ins.setString(2, storeName);
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                ins.setNull(3, Types.DECIMAL);
            } else {
                ins.setBigDecimal(3, amount.setScale(2, RoundingMode.HALF_UP));
            }
            ins.setString(4, syncedAt);
            ins.executeUpdate();
        }
        logger.info("Created local store_settings row for minimum sale amount");
    }

    /**
     * Get global card surcharge settings from local database
     * These are system-wide settings that apply to all stores
     */
    public CardSurchargeSettings getCardSurchargeSettings() {
        if (cachedCardSurchargeSettings != null) {
            return cachedCardSurchargeSettings;
        }

        Connection conn = null;
        try {
            conn = dbManager.getConnection();
            String sql = "SELECT card_surcharge_enabled, card_surcharge_percent FROM global_settings WHERE id = 'global'";

            try (PreparedStatement stmt = conn.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    boolean enabled = rs.getBoolean("card_surcharge_enabled");
                    Double percent = rs.getObject("card_surcharge_percent") != null
                            ? rs.getDouble("card_surcharge_percent")
                            : null;
                    cachedCardSurchargeSettings = new CardSurchargeSettings(enabled, percent);
                    return cachedCardSurchargeSettings;
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to load global card surcharge settings from database", e);
        } finally {
            // Return the pooled connection (this path previously leaked it on every cache miss).
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException closeEx) {
                    logger.warn("Failed to close connection", closeEx);
                }
            }
        }

        cachedCardSurchargeSettings = new CardSurchargeSettings(false, null);
        return cachedCardSurchargeSettings;
    }

    /**
     * Save global card surcharge settings to local database
     */
    public void saveCardSurchargeSettings(boolean enabled, Double percent) {
        Connection conn = null;
        try {
            conn = dbManager.getConnection();
            String sql = "MERGE INTO global_settings (id, card_surcharge_enabled, card_surcharge_percent, updated_at) "
                    +
                    "KEY (id) VALUES ('global', ?, ?, ?)";

            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBoolean(1, enabled);
                stmt.setObject(2, percent);
                stmt.setString(3, java.time.Instant.now().toString());
                stmt.executeUpdate();
                conn.commit();
                cachedCardSurchargeSettings = new CardSurchargeSettings(enabled, percent);
                logger.info("Global card surcharge settings saved: enabled={}, percent={}", enabled, percent);
            }
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    logger.error("Failed to rollback transaction", rollbackEx);
                }
            }
            logger.error("Failed to save global card surcharge settings", e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException e) {
                    logger.error("Failed to reset autocommit", e);
                }
                // Return the connection to the pool (previously leaked here).
                try {
                    conn.close();
                } catch (SQLException e) {
                    logger.error("Failed to close connection", e);
                }
            }
        }
    }

    /**
     * Card surcharge settings container
     */
    public static class CardSurchargeSettings {
        public final boolean enabled;
        public final Double percent;

        public CardSurchargeSettings(boolean enabled, Double percent) {
            this.enabled = enabled;
            this.percent = percent;
        }

        public double getPercentOrDefault() {
            return percent != null ? percent : 0.0;
        }
    }

    // ==================== MULTI-PACK DISCOUNT SETTINGS ====================

    /**
     * Multi-pack discount settings container
     * Used for "Buy X, Get Y% off" type discounts on configured departments
     */
    public static class MultiPackDiscountSettings {
        public boolean enabled;
        public DiscountType discountType;
        public BigDecimal discountValue;
        public int minimumQuantity;
        public List<String> eligibleDepartmentIds;
        public boolean requiresApproval;

        public enum DiscountType {
            PERCENT,
            AMOUNT
        }

        public MultiPackDiscountSettings() {
            this.enabled = false;
            this.discountType = DiscountType.PERCENT;
            this.discountValue = BigDecimal.TEN; // Default 10%
            this.minimumQuantity = 2;
            this.eligibleDepartmentIds = new ArrayList<>();
            this.requiresApproval = false;
        }

        public MultiPackDiscountSettings(boolean enabled, DiscountType discountType,
                BigDecimal discountValue, int minimumQuantity,
                List<String> eligibleDepartmentIds, boolean requiresApproval) {
            this.enabled = enabled;
            this.discountType = discountType;
            this.discountValue = discountValue;
            this.minimumQuantity = minimumQuantity;
            this.eligibleDepartmentIds = eligibleDepartmentIds != null ? eligibleDepartmentIds : new ArrayList<>();
            this.requiresApproval = requiresApproval;
        }

        /**
         * Check if a department is eligible for multi-pack discount
         */
        public boolean isDepartmentEligible(String departmentId) {
            if (departmentId == null || eligibleDepartmentIds.isEmpty()) {
                return false;
            }
            return eligibleDepartmentIds.contains(departmentId);
        }

        /**
         * Get discount display string (e.g., "10%" or "$1.00")
         */
        public String getDiscountDisplayString() {
            if (discountType == DiscountType.PERCENT) {
                return discountValue.stripTrailingZeros().toPlainString() + "%";
            } else {
                return "$" + discountValue.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
            }
        }
    }

    /**
     * Get multi-pack discount settings for a specific department
     * 
     * @param departmentId The department ID
     * @return MultiPackDiscountSettings for the department, or default settings if
     *         not found/not enabled
     */
    public MultiPackDiscountSettings getMultiPackDiscountSettingsForDepartment(String departmentId) {
        if (departmentId == null) {
            return new MultiPackDiscountSettings();
        }

        MultiPackDiscountSettings cached = multiPackSettingsCache.get(departmentId);
        if (cached != null) {
            return cached;
        }

        Connection conn = null;
        try {
            conn = dbManager.getConnection();
            String sql = "SELECT multipack_enabled, multipack_discount_type, multipack_discount_value, " +
                    "multipack_min_quantity, multipack_requires_approval " +
                    "FROM departments WHERE id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, departmentId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        boolean enabled = rs.getObject("multipack_enabled") != null
                                && rs.getBoolean("multipack_enabled");
                        if (!enabled) {
                            return cacheMultiPackSettings(departmentId, new MultiPackDiscountSettings());
                        }

                        String discountTypeStr = rs.getString("multipack_discount_type");
                        BigDecimal discountValue = rs.getObject("multipack_discount_value") != null
                                ? rs.getBigDecimal("multipack_discount_value")
                                : null;
                        Integer minQuantity = rs.getObject("multipack_min_quantity") != null
                                ? rs.getInt("multipack_min_quantity")
                                : null;
                        boolean requiresApproval = rs.getObject("multipack_requires_approval") != null &&
                                rs.getBoolean("multipack_requires_approval");

                        MultiPackDiscountSettings.DiscountType discountType = "AMOUNT".equalsIgnoreCase(discountTypeStr)
                                ? MultiPackDiscountSettings.DiscountType.AMOUNT
                                : MultiPackDiscountSettings.DiscountType.PERCENT;

                        if (discountValue == null || minQuantity == null || minQuantity <= 0) {
                            return cacheMultiPackSettings(departmentId, new MultiPackDiscountSettings());
                        }

                        // Create settings with only this department as eligible (for compatibility)
                        List<String> deptIds = new ArrayList<>();
                        deptIds.add(departmentId);

                        return cacheMultiPackSettings(departmentId, new MultiPackDiscountSettings(
                                true,
                                discountType,
                                discountValue,
                                minQuantity,
                                deptIds,
                                requiresApproval));
                    }
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to load multi-pack discount settings for department {}: {}", departmentId,
                    e.getMessage());
        } finally {
            // Return the connection to the pool. Without this, every cache miss leaked a
            // pooled connection (and an open transaction), eventually draining the pool and
            // making getConnection() block for the full timeout — a recurring UI freeze.
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException closeEx) {
                    logger.warn("Failed to close connection", closeEx);
                }
            }
        }

        return cacheMultiPackSettings(departmentId, new MultiPackDiscountSettings());
    }

    private MultiPackDiscountSettings cacheMultiPackSettings(String departmentId, MultiPackDiscountSettings settings) {
        multiPackSettingsCache.put(departmentId, settings);
        return settings;
    }

    /**
     * Invalidate cached multi-pack settings for a department (after sync/update).
     */
    public void invalidateMultiPackCache(String departmentId) {
        if (departmentId != null) {
            multiPackSettingsCache.remove(departmentId);
        }
    }

    /**
     * Get multi-pack discount settings from local database (deprecated - use
     * getMultiPackDiscountSettingsForDepartment)
     * 
     * @deprecated Use getMultiPackDiscountSettingsForDepartment instead
     */
    @Deprecated
    public MultiPackDiscountSettings getMultiPackDiscountSettings() {
        Connection conn = null;
        try {
            conn = dbManager.getConnection();
            String sql = "SELECT multipack_enabled, multipack_discount_type, multipack_discount_value, " +
                    "multipack_min_quantity, multipack_eligible_departments, multipack_requires_approval " +
                    "FROM global_settings WHERE id = 'global'";

            try (PreparedStatement stmt = conn.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    boolean enabled = rs.getBoolean("multipack_enabled");
                    String discountTypeStr = rs.getString("multipack_discount_type");
                    BigDecimal discountValue = rs.getBigDecimal("multipack_discount_value");
                    int minQuantity = rs.getInt("multipack_min_quantity");
                    String eligibleDepts = rs.getString("multipack_eligible_departments");
                    boolean requiresApproval = rs.getBoolean("multipack_requires_approval");

                    MultiPackDiscountSettings.DiscountType discountType = "AMOUNT".equalsIgnoreCase(discountTypeStr)
                            ? MultiPackDiscountSettings.DiscountType.AMOUNT
                            : MultiPackDiscountSettings.DiscountType.PERCENT;

                    List<String> deptIds = new ArrayList<>();
                    if (eligibleDepts != null && !eligibleDepts.isEmpty()) {
                        deptIds = new ArrayList<>(Arrays.asList(eligibleDepts.split(",")));
                    }

                    return new MultiPackDiscountSettings(
                            enabled,
                            discountType,
                            discountValue != null ? discountValue : BigDecimal.TEN,
                            minQuantity > 0 ? minQuantity : 2,
                            deptIds,
                            requiresApproval);
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to load multi-pack discount settings from database", e);
        } finally {
            // Return the pooled connection (this path previously leaked it on every call).
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException closeEx) {
                    logger.warn("Failed to close connection", closeEx);
                }
            }
        }

        // Return defaults if not found
        return new MultiPackDiscountSettings();
    }

    /**
     * Save multi-pack discount settings to local database
     */
    public void saveMultiPackDiscountSettings(MultiPackDiscountSettings settings) {
        Connection conn = null;
        try {
            conn = dbManager.getConnection();

            // First ensure the global settings row exists
            String checkSql = "SELECT COUNT(*) FROM global_settings WHERE id = 'global'";
            boolean exists = false;
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql);
                    ResultSet rs = checkStmt.executeQuery()) {
                if (rs.next()) {
                    exists = rs.getInt(1) > 0;
                }
            }

            String sql;
            if (exists) {
                sql = "UPDATE global_settings SET " +
                        "multipack_enabled = ?, multipack_discount_type = ?, multipack_discount_value = ?, " +
                        "multipack_min_quantity = ?, multipack_eligible_departments = ?, " +
                        "multipack_requires_approval = ?, updated_at = ? " +
                        "WHERE id = 'global'";
            } else {
                sql = "INSERT INTO global_settings (multipack_enabled, multipack_discount_type, " +
                        "multipack_discount_value, multipack_min_quantity, multipack_eligible_departments, " +
                        "multipack_requires_approval, updated_at, id) VALUES (?, ?, ?, ?, ?, ?, ?, 'global')";
            }

            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBoolean(1, settings.enabled);
                stmt.setString(2, settings.discountType.name());
                stmt.setBigDecimal(3, settings.discountValue);
                stmt.setInt(4, settings.minimumQuantity);
                stmt.setString(5, String.join(",", settings.eligibleDepartmentIds));
                stmt.setBoolean(6, settings.requiresApproval);
                stmt.setString(7, java.time.Instant.now().toString());
                stmt.executeUpdate();
                conn.commit();
                logger.info("Multi-pack discount settings saved: enabled={}, type={}, value={}, minQty={}",
                        settings.enabled, settings.discountType, settings.discountValue, settings.minimumQuantity);
            }
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    logger.error("Failed to rollback transaction", rollbackEx);
                }
            }
            logger.error("Failed to save multi-pack discount settings", e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException e) {
                    logger.error("Failed to reset autocommit", e);
                }
                // Return the connection to the pool (previously leaked here).
                try {
                    conn.close();
                } catch (SQLException e) {
                    logger.error("Failed to close connection", e);
                }
            }
        }
    }

    /**
     * Get the receipt print mode (AUTO/MANUAL) from global settings
     */
    public String getReceiptPrintMode() {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT receipt_print_mode FROM global_settings WHERE id = 'global'";
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String mode = rs.getString("receipt_print_mode");
                    return mode != null ? mode : "AUTO";
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to load receipt print mode from database", e);
        }
        return "AUTO"; // Default
    }

    /**
     * Save the receipt print mode to global settings
     */
    public void setReceiptPrintMode(String mode) {
        Connection conn = null;
        try {
            conn = dbManager.getConnection();
            String sql = "MERGE INTO global_settings (id, receipt_print_mode, updated_at) " +
                    "KEY (id) VALUES ('global', ?, ?)";

            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, mode);
                stmt.setString(2, java.time.Instant.now().toString());
                stmt.executeUpdate();
                conn.commit();
                logger.info("Receipt print mode saved: {}", mode);
            }
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    logger.error("Failed to rollback transaction", rollbackEx);
                }
            }
            logger.error("Failed to save receipt print mode", e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException e) {
                    logger.error("Failed to reset autocommit", e);
                }
                // Return the connection to the pool (previously leaked here).
                try {
                    conn.close();
                } catch (SQLException e) {
                    logger.error("Failed to close connection", e);
                }
            }
        }
    }
}
