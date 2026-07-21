package com.pos.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pos.database.DatabaseManager;
import com.pos.api.dto.StoreSettingsResponse;
import com.pos.model.Product;
import com.pos.service.ProductSyncService;
import com.pos.service.SettingsService;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Handler for processing incoming WebSocket messages.
 * 
 * <h2>Responsibilities</h2>
 * <ul>
 * <li>Parse incoming JSON messages</li>
 * <li>Route messages to appropriate handlers</li>
 * <li>Update local database with real-time changes</li>
 * <li>Notify UI components of changes</li>
 * </ul>
 * 
 * <h2>Event Types</h2>
 * <ul>
 * <li>product_created - New product added</li>
 * <li>product_updated - Product details changed</li>
 * <li>product_deleted - Product removed</li>
 * <li>price_changed - Product price updated</li>
 * <li>stock_updated - Inventory level changed</li>
 * <li>department_updated - Department modified</li>
 * <li>force_sync - Server requests full sync</li>
 * <li>notification - General notification</li>
 * </ul>
 */
public class WebSocketMessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketMessageHandler.class);

    private final Gson gson;
    private final DatabaseManager dbManager;

    // Event listeners for UI updates
    private final List<Consumer<ProductEvent>> productEventListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<StockEvent>> stockEventListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<PriceEvent>> priceEventListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SyncEvent>> syncEventListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<NotificationEvent>> notificationListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<VendorEvent>> vendorEventListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<GlobalSettingsEvent>> globalSettingsListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<DepartmentEvent>> departmentEventListeners = new CopyOnWriteArrayList<>();

    public WebSocketMessageHandler() {
        this.gson = new GsonBuilder()
                .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .create();
        this.dbManager = DatabaseManager.getInstance();
    }

    /**
     * Handle incoming WebSocket message.
     * 
     * @param eventType The type of event
     * @param jsonData  The JSON payload
     */
    public void handleMessage(String eventType, String jsonData) {
        try {
            JsonObject message = JsonParser.parseString(jsonData).getAsJsonObject();
            JsonObject data = message.has("data") ? message.getAsJsonObject("data") : message;
            String messageId = message.has("id") ? message.get("id").getAsString() : null;

            logger.info("Processing {} event (id: {})", eventType, messageId);

            switch (eventType) {
                case "product_created":
                    handleProductCreated(data);
                    break;
                case "product_updated":
                    handleProductUpdated(data);
                    break;
                case "product_deleted":
                    handleProductDeleted(data);
                    break;
                case "price_changed":
                    handlePriceChanged(data);
                    break;
                case "stock_updated":
                    handleStockUpdated(data);
                    break;
                case "department_updated":
                    handleDepartmentUpdated(data);
                    break;
                case "vendor_created":
                    handleVendorCreated(data);
                    break;
                case "vendor_updated":
                    handleVendorUpdated(data);
                    break;
                case "vendor_deleted":
                    handleVendorDeleted(data);
                    break;
                case "force_sync":
                    handleForceSync(data);
                    break;
                case "sale_completed":
                    handleSaleCompleted(data);
                    break;
                case "shift_event":
                    handleShiftEvent(data);
                    break;
                case "settings_updated":
                    handleSettingsUpdated(data);
                    break;
                case "global_settings_updated":
                    handleGlobalSettingsUpdated(data);
                    break;
                case "notification":
                    handleNotification(data);
                    break;
                case "stock_alert":
                    handleStockAlert(data);
                    break;
                default:
                    logger.warn("Unknown event type: {}", eventType);
            }
        } catch (Exception e) {
            logger.error("Error processing {} message", eventType, e);
        }
    }

    // === Event Handlers ===

    private void handleProductCreated(JsonObject data) {
        try {
            JsonObject productData = data.has("product") ? data.getAsJsonObject("product") : data;

            String id = getStringOrNull(productData, "id");
            String name = getStringOrNull(productData, "name");
            String barcode = getStringOrNull(productData, "barcode");
            String sku = getStringOrNull(productData, "sku");
            BigDecimal price = getBigDecimalOrNull(productData, "price");
            int stockQuantity = getIntOrDefault(productData, "stockQuantity", 0);
            String status = getStringOrNull(productData, "status");
            String departmentId = getStringOrNull(productData, "departmentId");
            String imageUrl = getStringOrNull(productData, "imageUrl");
            boolean taxable = getBooleanOrDefault(productData, "isTaxable", true);
            boolean ebtEligible = getBooleanOrDefault(productData, "isEbtEligible", false);
            boolean ageRestricted = getBooleanOrDefault(productData, "isAgeRestricted", false);
            Integer minimumAge = getIntOrNull(productData, "minimumAge");

            // Get department name if available
            String departmentName = null;
            if (productData.has("department") && !productData.get("department").isJsonNull()) {
                JsonObject dept = productData.getAsJsonObject("department");
                departmentName = getStringOrNull(dept, "name");
            }

            // Insert into local database
            String sql = """
                        MERGE INTO products (id, name, barcode, sku, price, stock_quantity, status,
                            department_id, updated_at)
                        KEY (id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """;

            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, name);
                stmt.setString(3, barcode);
                stmt.setString(4, sku);
                stmt.setBigDecimal(5, price);
                stmt.setInt(6, stockQuantity);
                stmt.setString(7, status);
                stmt.setString(8, departmentId);
                stmt.executeUpdate();
            }

            logger.info("Product created via WebSocket: {} ({})", name, barcode);

            // Notify listeners
            notifyProductListeners(new ProductEvent("created", id, name, barcode));

        } catch (Exception e) {
            logger.error("Error handling product_created event", e);
        }
    }

    private void handleProductUpdated(JsonObject data) {
        try {
            JsonObject productData = data.has("product") ? data.getAsJsonObject("product") : data;

            String id = getStringOrNull(productData, "id");
            if (id == null) {
                id = getStringOrNull(data, "productId");
            }

            if (id == null) {
                logger.warn("product_updated event missing product ID");
                return;
            }

            String name = getStringOrNull(productData, "name");
            String barcode = getStringOrNull(productData, "barcode");
            String sku = getStringOrNull(productData, "sku");
            BigDecimal price = getBigDecimalOrNull(productData, "price");
            Integer stockQuantity = getIntOrNull(productData, "stockQuantity");
            String status = getStringOrNull(productData, "status");
            String departmentId = getStringOrNull(productData, "departmentId");
            String imageUrl = getStringOrNull(productData, "imageUrl");
            Boolean taxable = getBooleanOrNull(productData, "isTaxable");
            Boolean ebtEligible = getBooleanOrNull(productData, "isEbtEligible");
            Boolean ageRestricted = getBooleanOrNull(productData, "isAgeRestricted");
            Integer minimumAge = getIntOrNull(productData, "minimumAge");

            // Get department name if available
            String departmentName = null;
            if (productData.has("department") && !productData.get("department").isJsonNull()) {
                JsonObject dept = productData.getAsJsonObject("department");
                departmentName = getStringOrNull(dept, "name");
            }

            // Build dynamic update SQL
            StringBuilder sql = new StringBuilder("UPDATE products SET updated_at = CURRENT_TIMESTAMP");
            List<Object> params = new ArrayList<>();

            if (name != null) {
                sql.append(", name = ?");
                params.add(name);
            }
            if (barcode != null) {
                sql.append(", barcode = ?");
                params.add(barcode);
            }
            if (sku != null) {
                sql.append(", sku = ?");
                params.add(sku);
            }
            if (price != null) {
                sql.append(", price = ?");
                params.add(price);
            }
            if (stockQuantity != null) {
                sql.append(", stock_quantity = ?");
                params.add(stockQuantity);
            }
            if (status != null) {
                sql.append(", status = ?");
                params.add(status);
            }
            if (departmentId != null) {
                sql.append(", department_id = ?");
                params.add(departmentId);
            }
            // Removed invalid columns: department_name, image_url, taxable, ebt_eligible,
            // age_restricted, minimum_age

            sql.append(" WHERE id = ?");
            params.add(id);

            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    Object param = params.get(i);
                    if (param instanceof BigDecimal) {
                        stmt.setBigDecimal(i + 1, (BigDecimal) param);
                    } else if (param instanceof Integer) {
                        stmt.setInt(i + 1, (Integer) param);
                    } else if (param instanceof Boolean) {
                        stmt.setBoolean(i + 1, (Boolean) param);
                    } else {
                        stmt.setString(i + 1, (String) param);
                    }
                }
                int updated = stmt.executeUpdate();
                if (updated > 0) {
                    logger.info("Product updated via WebSocket: {}", id);
                }
            }

            // Notify listeners
            notifyProductListeners(new ProductEvent("updated", id, name, barcode));

        } catch (Exception e) {
            logger.error("Error handling product_updated event", e);
        }
    }

    private void handleProductDeleted(JsonObject data) {
        try {
            String productId = getStringOrNull(data, "productId");
            if (productId == null) {
                logger.warn("product_deleted event missing productId");
                return;
            }

            String sql = "DELETE FROM products WHERE id = ?";
            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, productId);
                int deleted = stmt.executeUpdate();
                if (deleted > 0) {
                    logger.info("Product deleted via WebSocket: {}", productId);
                }
            }

            // Notify listeners
            notifyProductListeners(new ProductEvent("deleted", productId, null, null));

        } catch (Exception e) {
            logger.error("Error handling product_deleted event", e);
        }
    }

    private void handlePriceChanged(JsonObject data) {
        try {
            String productId = getStringOrNull(data, "productId");
            BigDecimal oldPrice = getBigDecimalOrNull(data, "oldPrice");
            BigDecimal newPrice = getBigDecimalOrNull(data, "newPrice");

            if (productId == null || newPrice == null) {
                logger.warn("price_changed event missing required fields");
                return;
            }

            String sql = "UPDATE products SET price = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBigDecimal(1, newPrice);
                stmt.setString(2, productId);
                int updated = stmt.executeUpdate();
                if (updated > 0) {
                    logger.info("Price updated via WebSocket: {} ({} -> {})", productId, oldPrice, newPrice);
                }
            }

            // Notify listeners (HIGH PRIORITY - affects active carts!)
            PriceEvent event = new PriceEvent(productId, oldPrice, newPrice);
            notifyPriceListeners(event);

        } catch (Exception e) {
            logger.error("Error handling price_changed event", e);
        }
    }

    private void handleStockUpdated(JsonObject data) {
        try {
            String productId = getStringOrNull(data, "productId");
            Integer previousQuantity = getIntOrNull(data, "previousQuantity");
            Integer newQuantity = getIntOrNull(data, "newQuantity");
            String reason = getStringOrNull(data, "reason");

            if (productId == null || newQuantity == null) {
                logger.warn("stock_updated event missing required fields");
                return;
            }

            // Update stock and status
            String status = "IN_STOCK";
            if (newQuantity <= 0) {
                status = "OUT_OF_STOCK";
            } else {
                // Check reorder level from local database
                try (Connection conn = dbManager.getConnection();
                        PreparedStatement stmt = conn
                                .prepareStatement("SELECT reorder_level FROM products WHERE id = ?")) {
                    stmt.setString(1, productId);
                    var rs = stmt.executeQuery();
                    if (rs.next()) {
                        int reorderLevel = rs.getInt("reorder_level");
                        if (newQuantity <= reorderLevel) {
                            status = "LOW_STOCK";
                        }
                    }
                }
            }

            String sql = "UPDATE products SET stock_quantity = ?, status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, newQuantity);
                stmt.setString(2, status);
                stmt.setString(3, productId);
                int updated = stmt.executeUpdate();
                if (updated > 0) {
                    logger.info("Stock updated via WebSocket: {} ({} -> {}, reason: {})",
                            productId, previousQuantity, newQuantity, reason);
                }
            }

            // Notify listeners
            StockEvent event = new StockEvent(productId, previousQuantity != null ? previousQuantity : 0, newQuantity,
                    reason);
            notifyStockListeners(event);

        } catch (Exception e) {
            logger.error("Error handling stock_updated event", e);
        }
    }

    private void handleDepartmentUpdated(JsonObject data) {
        try {
            String id = getStringOrNull(data, "id");
            String name = getStringOrNull(data, "name");
            String description = getStringOrNull(data, "description");
            Boolean isActive = getBooleanOrNull(data, "isActive");

            if (id == null) {
                logger.warn("department_updated event missing department ID");
                return;
            }

            // Removed invalid columns: description, is_active
            String sql = """
                        MERGE INTO departments (id, name, updated_at)
                        KEY (id)
                        VALUES (?, ?, CURRENT_TIMESTAMP)
                    """;

            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, name);
                stmt.executeUpdate();
            }

            logger.info("Department updated via WebSocket: {} ({})", name, id);

            // Notify listeners for UI refresh
            DepartmentEvent event = new DepartmentEvent("updated", id, name);
            notifyDepartmentListeners(event);

        } catch (Exception e) {
            logger.error("Error handling department_updated event", e);
        }
    }

    private void handleVendorCreated(JsonObject data) {
        try {
            logger.info("🏪 Processing vendor_created event, data: {}", data);
            JsonObject vendorData = data.has("vendor") ? data.getAsJsonObject("vendor") : data;
            logger.info("   Vendor data extracted: {}", vendorData);

            String id = getStringOrNull(vendorData, "id");
            String name = getStringOrNull(vendorData, "name");
            String contactName = getStringOrNull(vendorData, "contactName");
            String email = getStringOrNull(vendorData, "email");
            String phone = getStringOrNull(vendorData, "phone");
            String address = getStringOrNull(vendorData, "address");
            String paymentTerms = getStringOrNull(vendorData, "paymentTerms");
            BigDecimal commissionRate = getBigDecimalOrNull(vendorData, "commissionRate");
            BigDecimal defaultCostMargin = getBigDecimalOrNull(vendorData, "defaultCostMargin");
            String bankAccountInfo = getStringOrNull(vendorData, "bankAccountInfo");
            String notes = getStringOrNull(vendorData, "notes");
            Boolean isActive = getBooleanOrNull(vendorData, "isActive");

            logger.info("   Parsed vendor: id={}, name={}, contactName={}, isActive={}", id, name, contactName,
                    isActive);

            if (id == null || name == null) {
                logger.warn("vendor_created event missing required fields: id={}, name={}", id, name);
                return;
            }

            String sql = """
                        MERGE INTO vendors (id, name, contact_name, email, phone, address, payment_terms,
                            commission_rate, default_cost_margin, bank_account_info, notes, is_active, synced, updated_at)
                        KEY (id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, CURRENT_TIMESTAMP)
                    """;

            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, name);
                stmt.setString(3, contactName);
                stmt.setString(4, email);
                stmt.setString(5, phone);
                stmt.setString(6, address);
                stmt.setString(7, paymentTerms != null ? paymentTerms : "NET30");
                stmt.setBigDecimal(8, commissionRate != null ? commissionRate : BigDecimal.ZERO);
                stmt.setBigDecimal(9, defaultCostMargin != null ? defaultCostMargin : BigDecimal.ZERO);
                stmt.setString(10, bankAccountInfo);
                stmt.setString(11, notes);
                stmt.setBoolean(12, isActive != null ? isActive : true);
                int result = stmt.executeUpdate();
                logger.info("   Database MERGE result: {} row(s) affected", result);
            }

            logger.info("✅ Vendor created/updated via WebSocket: {} ({})", name, id);

            // Notify UI listeners about the new vendor
            VendorEvent event = new VendorEvent("created", id, name);
            notifyVendorListeners(event);

        } catch (Exception e) {
            logger.error("Error handling vendor_created event", e);
        }
    }

    private void handleVendorUpdated(JsonObject data) {
        try {
            logger.info("✏️ Processing vendor_updated event, data: {}", data);
            JsonObject vendorData = data.has("vendor") ? data.getAsJsonObject("vendor") : data;

            String id = getStringOrNull(vendorData, "id");
            if (id == null) {
                logger.warn("vendor_updated event missing vendor ID");
                return;
            }

            String name = getStringOrNull(vendorData, "name");
            String contactName = getStringOrNull(vendorData, "contactName");
            String email = getStringOrNull(vendorData, "email");
            String phone = getStringOrNull(vendorData, "phone");
            String address = getStringOrNull(vendorData, "address");
            String paymentTerms = getStringOrNull(vendorData, "paymentTerms");
            BigDecimal commissionRate = getBigDecimalOrNull(vendorData, "commissionRate");
            BigDecimal defaultCostMargin = getBigDecimalOrNull(vendorData, "defaultCostMargin");
            String bankAccountInfo = getStringOrNull(vendorData, "bankAccountInfo");
            String notes = getStringOrNull(vendorData, "notes");
            Boolean isActive = getBooleanOrNull(vendorData, "isActive");

            logger.info("   Updating vendor: id={}, name={}, isActive={}", id, name, isActive);

            // Build dynamic update SQL
            StringBuilder sql = new StringBuilder("UPDATE vendors SET updated_at = CURRENT_TIMESTAMP, synced = TRUE");
            List<Object> params = new ArrayList<>();

            if (name != null) {
                sql.append(", name = ?");
                params.add(name);
            }
            if (contactName != null) {
                sql.append(", contact_name = ?");
                params.add(contactName);
            }
            if (email != null) {
                sql.append(", email = ?");
                params.add(email);
            }
            if (phone != null) {
                sql.append(", phone = ?");
                params.add(phone);
            }
            if (address != null) {
                sql.append(", address = ?");
                params.add(address);
            }
            if (paymentTerms != null) {
                sql.append(", payment_terms = ?");
                params.add(paymentTerms);
            }
            if (commissionRate != null) {
                sql.append(", commission_rate = ?");
                params.add(commissionRate);
            }
            if (defaultCostMargin != null) {
                sql.append(", default_cost_margin = ?");
                params.add(defaultCostMargin);
            }
            if (bankAccountInfo != null) {
                sql.append(", bank_account_info = ?");
                params.add(bankAccountInfo);
            }
            if (notes != null) {
                sql.append(", notes = ?");
                params.add(notes);
            }
            if (isActive != null) {
                sql.append(", is_active = ?");
                params.add(isActive);
            }

            sql.append(" WHERE id = ?");
            params.add(id);

            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    Object param = params.get(i);
                    if (param instanceof BigDecimal) {
                        stmt.setBigDecimal(i + 1, (BigDecimal) param);
                    } else if (param instanceof Boolean) {
                        stmt.setBoolean(i + 1, (Boolean) param);
                    } else {
                        stmt.setString(i + 1, (String) param);
                    }
                }
                int updated = stmt.executeUpdate();
                if (updated > 0) {
                    logger.info("✅ Vendor updated via WebSocket: {} (name={})", id, name);
                    // Notify UI listeners about the vendor update
                    VendorEvent event = new VendorEvent("updated", id, name);
                    notifyVendorListeners(event);
                } else {
                    logger.warn("⚠️ Vendor not found for update, will insert: {}", id);
                    // If vendor doesn't exist, insert it
                    handleVendorCreated(data);
                }
            }

        } catch (Exception e) {
            logger.error("Error handling vendor_updated event", e);
        }
    }

    private void handleVendorDeleted(JsonObject data) {
        try {
            logger.info("🗑️ Processing vendor_deleted event, data: {}", data);
            String vendorId = getStringOrNull(data, "vendorId");
            if (vendorId == null) {
                logger.warn("vendor_deleted event missing vendorId");
                return;
            }

            logger.info("   Deactivating vendor: {}", vendorId);

            // Mark vendor as inactive rather than deleting (soft delete)
            String sql = "UPDATE vendors SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, vendorId);
                int updated = stmt.executeUpdate();
                if (updated > 0) {
                    logger.info("✅ Vendor deactivated via WebSocket: {}", vendorId);
                    // Notify UI listeners about the vendor deletion
                    VendorEvent event = new VendorEvent("deleted", vendorId, null);
                    notifyVendorListeners(event);
                } else {
                    logger.warn("⚠️ Vendor not found for deactivation: {}", vendorId);
                }
            }

        } catch (Exception e) {
            logger.error("Error handling vendor_deleted event", e);
        }
    }

    private void handleForceSync(JsonObject data) {
        try {
            String syncType = getStringOrNull(data, "syncType");
            String reason = getStringOrNull(data, "reason");

            logger.info("Force sync requested: type={}, reason={}", syncType, reason);

            // Notify listeners to trigger sync
            SyncEvent event = new SyncEvent(syncType, reason);
            notifySyncListeners(event);

            // Trigger appropriate sync based on type
            if ("products".equals(syncType) || "full".equals(syncType)) {
                ProductSyncService.getInstance().performFullSync();
            }

            // Trigger vendor sync if requested
            if ("vendors".equals(syncType) || "full".equals(syncType)) {
                try {
                    com.pos.sync.inbound.VendorInboundSync.getInstance().sync(null);
                    logger.info("Vendor sync triggered via force_sync");
                } catch (Exception e) {
                    logger.error("Failed to trigger vendor sync", e);
                }
            }

        } catch (Exception e) {
            logger.error("Error handling force_sync event", e);
        }
    }

    private void handleSaleCompleted(JsonObject data) {
        // Log sale from other devices (for dashboard/monitoring)
        String saleId = getStringOrNull(data, "saleId");
        Double total = getDoubleOrNull(data, "total");
        String cashierName = getStringOrNull(data, "cashierName");

        logger.info("Sale completed on another device: {} (${}, cashier: {})", saleId, total, cashierName);
    }

    private void handleShiftEvent(JsonObject data) {
        String type = getStringOrNull(data, "type");
        String cashierName = getStringOrNull(data, "cashierName");
        String registerId = getStringOrNull(data, "registerId");

        logger.info("Shift event from another device: {} (cashier: {}, register: {})", type, cashierName, registerId);
    }

    private void handleSettingsUpdated(JsonObject data) {
        logger.info("Settings updated via WebSocket: {}", data);
        try {
            // Parse incoming data
            StoreSettingsResponse response = gson.fromJson(data, StoreSettingsResponse.class);

            // Validate response has meaningful data (either store info or settings info)
            if (response.store == null && response.settings == null) {
                logger.warn("Received settings_updated event with empty payload, triggering full refresh");
                SettingsService.getInstance().refreshSettings();
                return;
            }

            // Convert to internal data structure
            StoreSettingsResponse.StoreSettingsData settingsData = response.getSettings();

            // Update local state
            SettingsService.getInstance().updateSettings(settingsData);

        } catch (Exception e) {
            logger.error("Error handling settings_updated event", e);
            // Fallback to full refresh on error to ensure consistency
            try {
                SettingsService.getInstance().refreshSettings();
            } catch (Exception ex) {
                logger.error("Failed to refresh settings after error", ex);
            }
        }
    }

    private void handleGlobalSettingsUpdated(JsonObject data) {
        try {
            Boolean cardSurchargeEnabled = getBooleanOrNull(data, "cardSurchargeEnabled");
            Double cardSurchargePercent = data.has("cardSurchargePercent")
                    && !data.get("cardSurchargePercent").isJsonNull()
                            ? data.get("cardSurchargePercent").getAsDouble()
                            : null;

            logger.info("🌐 Global settings updated via WebSocket: cardSurchargeEnabled={}, cardSurchargePercent={}",
                    cardSurchargeEnabled, cardSurchargePercent);

            // Update local database
            String sql = "MERGE INTO global_settings (id, card_surcharge_enabled, card_surcharge_percent, updated_at) "
                    +
                    "KEY (id) VALUES ('global', ?, ?, CURRENT_TIMESTAMP)";

            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBoolean(1, cardSurchargeEnabled != null ? cardSurchargeEnabled : false);
                stmt.setObject(2, cardSurchargePercent);
                stmt.executeUpdate();
                logger.info("✅ Global settings saved to local database");
            }

            // Notify listeners
            GlobalSettingsEvent event = new GlobalSettingsEvent(
                    cardSurchargeEnabled != null ? cardSurchargeEnabled : false,
                    cardSurchargePercent);
            notifyGlobalSettingsListeners(event);

        } catch (Exception e) {
            logger.error("Error handling global_settings_updated event", e);
        }
    }

    private void handleNotification(JsonObject data) {
        String title = getStringOrNull(data, "title");
        String message = getStringOrNull(data, "message");
        String type = getStringOrNull(data, "type");

        logger.info("Notification received: [{}] {} - {}", type, title, message);

        // Notify UI
        NotificationEvent event = new NotificationEvent(title, message, type);
        notifyNotificationListeners(event);
    }

    private void handleStockAlert(JsonObject data) {
        String productId = getStringOrNull(data, "productId");
        String productName = getStringOrNull(data, "productName");
        Integer currentStock = getIntOrNull(data, "currentStock");
        String alertType = getStringOrNull(data, "alertType");

        logger.warn("Stock alert from another device: {} ({}) - {} (stock: {})",
                productName, productId, alertType, currentStock);
    }

    // === Listener Management ===

    public void addProductEventListener(Consumer<ProductEvent> listener) {
        productEventListeners.add(listener);
    }

    public void addStockEventListener(Consumer<StockEvent> listener) {
        stockEventListeners.add(listener);
    }

    public void addPriceEventListener(Consumer<PriceEvent> listener) {
        priceEventListeners.add(listener);
    }

    public void addSyncEventListener(Consumer<SyncEvent> listener) {
        syncEventListeners.add(listener);
    }

    public void addNotificationListener(Consumer<NotificationEvent> listener) {
        notificationListeners.add(listener);
    }

    public void addVendorEventListener(Consumer<VendorEvent> listener) {
        vendorEventListeners.add(listener);
    }

    public void removeVendorEventListener(Consumer<VendorEvent> listener) {
        vendorEventListeners.remove(listener);
    }

    public void addGlobalSettingsListener(Consumer<GlobalSettingsEvent> listener) {
        globalSettingsListeners.add(listener);
    }

    public void removeGlobalSettingsListener(Consumer<GlobalSettingsEvent> listener) {
        globalSettingsListeners.remove(listener);
    }

    public void addDepartmentEventListener(Consumer<DepartmentEvent> listener) {
        departmentEventListeners.add(listener);
    }

    public void removeDepartmentEventListener(Consumer<DepartmentEvent> listener) {
        departmentEventListeners.remove(listener);
    }

    private void notifyProductListeners(ProductEvent event) {
        Platform.runLater(() -> {
            for (Consumer<ProductEvent> listener : productEventListeners) {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    logger.error("Error in product event listener", e);
                }
            }
        });
    }

    private void notifyStockListeners(StockEvent event) {
        Platform.runLater(() -> {
            for (Consumer<StockEvent> listener : stockEventListeners) {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    logger.error("Error in stock event listener", e);
                }
            }
        });
    }

    private void notifyPriceListeners(PriceEvent event) {
        Platform.runLater(() -> {
            for (Consumer<PriceEvent> listener : priceEventListeners) {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    logger.error("Error in price event listener", e);
                }
            }
        });
    }

    private void notifySyncListeners(SyncEvent event) {
        for (Consumer<SyncEvent> listener : syncEventListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.error("Error in sync event listener", e);
            }
        }
    }

    private void notifyNotificationListeners(NotificationEvent event) {
        Platform.runLater(() -> {
            for (Consumer<NotificationEvent> listener : notificationListeners) {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    logger.error("Error in notification listener", e);
                }
            }
        });
    }

    private void notifyVendorListeners(VendorEvent event) {
        Platform.runLater(() -> {
            for (Consumer<VendorEvent> listener : vendorEventListeners) {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    logger.error("Error in vendor event listener", e);
                }
            }
        });
    }

    private void notifyGlobalSettingsListeners(GlobalSettingsEvent event) {
        Platform.runLater(() -> {
            for (Consumer<GlobalSettingsEvent> listener : globalSettingsListeners) {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    logger.error("Error in global settings listener", e);
                }
            }
        });
    }

    private void notifyDepartmentListeners(DepartmentEvent event) {
        Platform.runLater(() -> {
            for (Consumer<DepartmentEvent> listener : departmentEventListeners) {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    logger.error("Error in department event listener", e);
                }
            }
        });
    }

    // === JSON Helper Methods ===

    private String getStringOrNull(JsonObject obj, String field) {
        if (obj.has(field) && !obj.get(field).isJsonNull()) {
            return obj.get(field).getAsString();
        }
        return null;
    }

    private Integer getIntOrNull(JsonObject obj, String field) {
        if (obj.has(field) && !obj.get(field).isJsonNull()) {
            return obj.get(field).getAsInt();
        }
        return null;
    }

    private int getIntOrDefault(JsonObject obj, String field, int defaultValue) {
        Integer value = getIntOrNull(obj, field);
        return value != null ? value : defaultValue;
    }

    private Double getDoubleOrNull(JsonObject obj, String field) {
        if (obj.has(field) && !obj.get(field).isJsonNull()) {
            return obj.get(field).getAsDouble();
        }
        return null;
    }

    private BigDecimal getBigDecimalOrNull(JsonObject obj, String field) {
        if (obj.has(field) && !obj.get(field).isJsonNull()) {
            return obj.get(field).getAsBigDecimal();
        }
        return null;
    }

    private Boolean getBooleanOrNull(JsonObject obj, String field) {
        if (obj.has(field) && !obj.get(field).isJsonNull()) {
            return obj.get(field).getAsBoolean();
        }
        return null;
    }

    private boolean getBooleanOrDefault(JsonObject obj, String field, boolean defaultValue) {
        Boolean value = getBooleanOrNull(obj, field);
        return value != null ? value : defaultValue;
    }

    // === Event Classes ===

    public static class ProductEvent {
        private final String action;
        private final String productId;
        private final String productName;
        private final String barcode;

        public ProductEvent(String action, String productId, String productName, String barcode) {
            this.action = action;
            this.productId = productId;
            this.productName = productName;
            this.barcode = barcode;
        }

        public String getAction() {
            return action;
        }

        public String getProductId() {
            return productId;
        }

        public String getProductName() {
            return productName;
        }

        public String getBarcode() {
            return barcode;
        }
    }

    public static class StockEvent {
        private final String productId;
        private final int previousQuantity;
        private final int newQuantity;
        private final String reason;

        public StockEvent(String productId, int previousQuantity, int newQuantity, String reason) {
            this.productId = productId;
            this.previousQuantity = previousQuantity;
            this.newQuantity = newQuantity;
            this.reason = reason;
        }

        public String getProductId() {
            return productId;
        }

        public int getPreviousQuantity() {
            return previousQuantity;
        }

        public int getNewQuantity() {
            return newQuantity;
        }

        public String getReason() {
            return reason;
        }
    }

    public static class PriceEvent {
        private final String productId;
        private final BigDecimal oldPrice;
        private final BigDecimal newPrice;

        public PriceEvent(String productId, BigDecimal oldPrice, BigDecimal newPrice) {
            this.productId = productId;
            this.oldPrice = oldPrice;
            this.newPrice = newPrice;
        }

        public String getProductId() {
            return productId;
        }

        public BigDecimal getOldPrice() {
            return oldPrice;
        }

        public BigDecimal getNewPrice() {
            return newPrice;
        }
    }

    public static class SyncEvent {
        private final String syncType;
        private final String reason;

        public SyncEvent(String syncType, String reason) {
            this.syncType = syncType;
            this.reason = reason;
        }

        public String getSyncType() {
            return syncType;
        }

        public String getReason() {
            return reason;
        }
    }

    public static class NotificationEvent {
        private final String title;
        private final String message;
        private final String type;

        public NotificationEvent(String title, String message, String type) {
            this.title = title;
            this.message = message;
            this.type = type;
        }

        public String getTitle() {
            return title;
        }

        public String getMessage() {
            return message;
        }

        public String getType() {
            return type;
        }
    }

    public static class VendorEvent {
        private final String action;
        private final String vendorId;
        private final String vendorName;

        public VendorEvent(String action, String vendorId, String vendorName) {
            this.action = action;
            this.vendorId = vendorId;
            this.vendorName = vendorName;
        }

        public String getAction() {
            return action;
        }

        public String getVendorId() {
            return vendorId;
        }

        public String getVendorName() {
            return vendorName;
        }
    }

    public static class GlobalSettingsEvent {
        private final boolean cardSurchargeEnabled;
        private final Double cardSurchargePercent;

        public GlobalSettingsEvent(boolean cardSurchargeEnabled, Double cardSurchargePercent) {
            this.cardSurchargeEnabled = cardSurchargeEnabled;
            this.cardSurchargePercent = cardSurchargePercent;
        }

        public boolean isCardSurchargeEnabled() {
            return cardSurchargeEnabled;
        }

        public Double getCardSurchargePercent() {
            return cardSurchargePercent;
        }
    }

    public static class DepartmentEvent {
        private final String action;
        private final String departmentId;
        private final String departmentName;

        public DepartmentEvent(String action, String departmentId, String departmentName) {
            this.action = action;
            this.departmentId = departmentId;
            this.departmentName = departmentName;
        }

        public String getAction() {
            return action;
        }

        public String getDepartmentId() {
            return departmentId;
        }

        public String getDepartmentName() {
            return departmentName;
        }
    }
}
