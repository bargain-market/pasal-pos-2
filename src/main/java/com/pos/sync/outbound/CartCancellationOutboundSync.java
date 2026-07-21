package com.pos.sync.outbound;

import com.pos.api.ApiClient;
import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.service.CartCancellationService;
import com.pos.sync.SyncDirection;
import com.pos.sync.SyncManager;
import com.pos.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Outbound sync handler for cart cancellations.
 * Pushes pending cart cancellations from local database to backend.
 * 
 * <h2>Sync Flow</h2>
 * <pre>
 * Local H2 Database ────────────────────────────────────────────────────────
 *     • cart_cancellations table (synced = FALSE)
 *     • cart_cancellation_items table
 *     │
 *     ▼
 * CartCancellationOutboundSync ────────────────────────────────────────────
 *     │
 *     │ Get pending cancellations
 *     │ Submit to backend
 *     │ Mark as synced on success
 *     │
 *     ▼
 * Backend API ──────────────────────────────────────────────────────────────
 *     POST /pos/cart-cancellations
 * </pre>
 */
public class CartCancellationOutboundSync implements SyncManager.OutboundSyncHandler {
    private static final Logger logger = LoggerFactory.getLogger(CartCancellationOutboundSync.class);
    
    private static final int BATCH_SIZE = 50;
    
    /** Cooldown: skip sync when endpoint is known to be unavailable (404). */
    private volatile boolean endpointUnavailable = false;
    private volatile long endpointUnavailableSince = 0;
    /** Re-check every 30 minutes in case the backend is updated. */
    private static final long ENDPOINT_RETRY_INTERVAL_MS = 30 * 60 * 1000L;
    
    private static CartCancellationOutboundSync instance;
    
    private final ApiClient apiClient;
    private final DatabaseManager dbManager;
    private final ConfigManager config;
    private final CartCancellationService cancellationService;
    
    private CartCancellationOutboundSync() {
        this.apiClient = ApiClient.getInstance();
        this.dbManager = DatabaseManager.getInstance();
        this.config = ConfigManager.getInstance();
        this.cancellationService = CartCancellationService.getInstance();
    }
    
    public static synchronized CartCancellationOutboundSync getInstance() {
        if (instance == null) {
            instance = new CartCancellationOutboundSync();
        }
        return instance;
    }
    
    @Override
    public String getName() {
        return "CartCancellationSync";
    }
    
    @Override
    public SyncResult sync() throws Exception {
        // If the backend endpoint returned 404 recently, skip until cooldown expires
        if (endpointUnavailable) {
            long elapsed = System.currentTimeMillis() - endpointUnavailableSince;
            if (elapsed < ENDPOINT_RETRY_INTERVAL_MS) {
                logger.debug("Cart cancellation endpoint not available on backend, skipping sync (retry in {} min)",
                    (ENDPOINT_RETRY_INTERVAL_MS - elapsed) / 60000);
                return SyncResult.empty(SyncDirection.OUTBOUND);
            }
            // Cooldown expired — retry
            logger.info("Cart cancellation endpoint cooldown expired, retrying...");
            endpointUnavailable = false;
        }

        int totalSynced = 0;
        int totalFailed = 0;
        StringBuilder allErrors = new StringBuilder();

        while (true) {
            List<Map<String, Object>> pendingCancellations = getPendingCancellations(BATCH_SIZE);

            if (pendingCancellations.isEmpty()) {
                break;
            }

            logger.info("Syncing {} pending cart cancellations", pendingCancellations.size());

            BatchResult batch = processBatch(pendingCancellations);
            totalSynced += batch.synced;
            totalFailed += batch.failed;
            if (batch.errorMessage != null) {
                if (allErrors.length() > 0) {
                    allErrors.append("; ");
                }
                allErrors.append(batch.errorMessage);
            }
            if (batch.stopEntireSync) {
                break;
            }
        }

        if (totalSynced == 0 && totalFailed == 0) {
            logger.debug("No pending cart cancellations to sync");
            return SyncResult.empty(SyncDirection.OUTBOUND);
        }

        if (totalFailed > 0) {
            logger.error("Cart cancellation sync completed with failures: {} synced, {} failed. Errors: {}",
                totalSynced, totalFailed, allErrors);
        } else {
            logger.info("Cart cancellation sync completed: {} synced, {} failed", totalSynced, totalFailed);
        }

        return new SyncResult(SyncDirection.OUTBOUND, totalSynced, totalFailed,
            allErrors.length() > 0 ? allErrors.toString() : null);
    }

    private static final class BatchResult {
        int synced;
        int failed;
        String errorMessage;
        boolean stopEntireSync;
    }

    private BatchResult processBatch(List<Map<String, Object>> pendingCancellations) {
        BatchResult result = new BatchResult();
        StringBuilder errorMessages = new StringBuilder();

        for (Map<String, Object> cancellation : pendingCancellations) {
            try {
                submitCancellation(cancellation);

                String cancellationId = (String) cancellation.get("id");
                cancellationService.markAsSynced(cancellationId);
                result.synced++;

                logger.debug("Cart cancellation {} synced successfully", cancellationId);

            } catch (ApiClient.ApiException e) {
                if (e.getStatusCode() == 404 ||
                    (e.getMessage() != null && e.getMessage().contains("Route") && e.getMessage().contains("not found"))) {
                    logger.warn("Cart cancellation endpoint not found on backend (HTTP 404). " +
                        "Skipping remaining pending cancellations. Will retry in 30 minutes.",
                        pendingCancellations.size());
                    endpointUnavailable = true;
                    endpointUnavailableSince = System.currentTimeMillis();
                    result.failed += pendingCancellations.size() - result.synced;
                    result.errorMessage = "Backend endpoint /pos/cart-cancellations not found (404). Will retry later.";
                    result.stopEntireSync = true;
                    return result;
                }

                result.failed++;
                String cancellationId = (String) cancellation.get("id");
                String errorMsg = String.format("Cancellation %s failed: %s",
                    cancellationId != null ? cancellationId : "unknown", e.getMessage());
                logger.error(errorMsg);

                if (errorMessages.length() > 0) {
                    errorMessages.append("; ");
                }
                errorMessages.append(errorMsg);
            } catch (Exception e) {
                result.failed++;
                String cancellationId = (String) cancellation.get("id");
                String errorMsg = String.format("Cancellation %s failed: %s",
                    cancellationId != null ? cancellationId : "unknown", e.getMessage());
                logger.error(errorMsg, e);

                if (errorMessages.length() > 0) {
                    errorMessages.append("; ");
                }
                errorMessages.append(errorMsg);
            }
        }

        if (errorMessages.length() > 0) {
            result.errorMessage = errorMessages.toString();
        }
        return result;
    }
    
    /**
     * Get pending cart cancellations from local database.
     */
    private List<Map<String, Object>> getPendingCancellations(int limit) throws SQLException {
        String sql = """
            SELECT * FROM cart_cancellations
            WHERE synced = FALSE
            ORDER BY timestamp ASC
            LIMIT ?
            """;

        List<Map<String, Object>> cancellations = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String cancellationId = rs.getString("id");

                // Get cancellation data
                Map<String, Object> cancellation = new HashMap<>();
                cancellation.put("id", cancellationId);
                cancellation.put("receiptNumber", rs.getString("receipt_number"));
                cancellation.put("totalValue", rs.getBigDecimal("total_value"));
                cancellation.put("subtotal", rs.getBigDecimal("subtotal"));
                cancellation.put("discount", rs.getBigDecimal("discount"));
                cancellation.put("tax", rs.getBigDecimal("tax"));
                cancellation.put("itemCount", rs.getInt("item_count"));
                cancellation.put("cashierName", rs.getString("cashier_name"));
                cancellation.put("cashierId", rs.getString("cashier_id"));
                cancellation.put("posUserId", rs.getString("pos_user_id"));
                cancellation.put("shiftId", rs.getString("shift_id"));

                // Event time for the cancellation (required by backend)
                Timestamp timestamp = rs.getTimestamp("timestamp");
                if (timestamp != null) {
                    cancellation.put("timestamp", timestamp.toInstant().toString());
                } else {
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    if (createdAt != null) {
                        cancellation.put("timestamp", createdAt.toInstant().toString());
                    } else {
                        cancellation.put("timestamp", Instant.now().toString());
                    }
                }

                // Get cancellation items
                List<Map<String, Object>> items = getCancellationItems(conn, cancellationId);
                cancellation.put("items", items);

                cancellations.add(cancellation);
            }
        }

        logger.debug("Retrieved {} pending cart cancellations", cancellations.size());
        return cancellations;
    }
    
    /**
     * Get items for a cart cancellation.
     */
    private List<Map<String, Object>> getCancellationItems(Connection conn, String cancellationId) throws SQLException {
        String sql = """
            SELECT * FROM cart_cancellation_items
            WHERE cancellation_id = ?
            """;
        
        List<Map<String, Object>> items = new ArrayList<>();
        
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, cancellationId);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                Map<String, Object> item = new HashMap<>();
                item.put("productId", rs.getString("product_id"));
                item.put("productName", rs.getString("product_name"));
                item.put("sku", rs.getString("sku"));
                item.put("barcode", rs.getString("barcode"));
                item.put("quantity", rs.getInt("quantity"));
                
                BigDecimal unitPrice = rs.getBigDecimal("unit_price");
                if (unitPrice != null) {
                    item.put("unitPrice", unitPrice.doubleValue());
                }
                
                BigDecimal subtotal = rs.getBigDecimal("subtotal");
                if (subtotal != null) {
                    item.put("subtotal", subtotal.doubleValue());
                }
                
                BigDecimal discount = rs.getBigDecimal("discount");
                if (discount != null) {
                    item.put("discount", discount.doubleValue());
                }
                
                item.put("discountReason", rs.getString("discount_reason"));
                item.put("departmentId", rs.getString("department_id"));
                item.put("departmentName", rs.getString("department_name"));
                
                items.add(item);
            }
        }
        
        return items;
    }
    
    /**
     * Submit cart cancellation to backend.
     */
    private void submitCancellation(Map<String, Object> cancellation) throws ApiClient.ApiException {
        String deviceId = config.getProperty("device.id", "");
        
        // Build request payload (JSON numbers for money fields; Gson omits nulls — backend requires totalValue)
        Map<String, Object> request = new HashMap<>();
        request.put("cancellationId", cancellation.get("id"));
        request.put("receiptNumber", cancellation.get("receiptNumber"));
        request.put("totalValue", toJsonNumber(cancellation.get("totalValue"), 0.0));
        request.put("subtotal", toJsonNumber(cancellation.get("subtotal"), 0.0));
        request.put("discount", toJsonNumber(cancellation.get("discount"), 0.0));
        request.put("tax", toJsonNumber(cancellation.get("tax"), 0.0));
        Object itemCountObj = cancellation.get("itemCount");
        int itemCount = itemCountObj instanceof Number
            ? ((Number) itemCountObj).intValue()
            : 0;
        request.put("cashierName", cancellation.get("cashierName"));
        request.put("cashierId", cancellation.get("cashierId"));
        request.put("posUserId", cancellation.get("posUserId"));
        request.put("shiftId", cancellation.get("shiftId"));
        Object ts = cancellation.get("timestamp");
        request.put("timestamp", ts != null && !ts.toString().isEmpty() ? ts.toString() : Instant.now().toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawItems = (List<Map<String, Object>>) cancellation.get("items");
        List<Map<String, Object>> normalizedItems = normalizeCancellationItems(rawItems);
        request.put("items", normalizedItems);
        request.put("itemCount", Math.max(itemCount, normalizedItems.size()));
        request.put("deviceId", deviceId);
        request.put("syncTimestamp", Instant.now().toString());
        
        logger.debug("Submitting cart cancellation {} to backend", cancellation.get("id"));
        
        // Submit to backend API
        // Note: This assumes the backend endpoint exists. If it doesn't, this will fail gracefully.
        // We don't need to process the response, just check if the call succeeded
        apiClient.post(
            "/pos/cart-cancellations",
            request,
            Object.class
        );
        
        logger.debug("Cart cancellation {} submitted successfully", cancellation.get("id"));
    }

    /** Coerce DB BigDecimal / Number to a JSON number so Gson never omits required totals. */
    private static double toJsonNumber(Object value, double defaultVal) {
        if (value == null) {
            return defaultVal;
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).doubleValue();
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    /** Ensure line items always send quantity and numeric amounts the API expects. */
    private static List<Map<String, Object>> normalizeCancellationItems(List<Map<String, Object>> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (Map<String, Object> row : items) {
            if (row == null) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("productId", row.get("productId"));
            item.put("productName", row.get("productName"));
            item.put("sku", row.get("sku"));
            item.put("barcode", row.get("barcode"));
            Object qtyObj = row.get("quantity");
            int qty = qtyObj instanceof Number ? ((Number) qtyObj).intValue() : 0;
            item.put("quantity", qty);
            item.put("unitPrice", toJsonNumber(row.get("unitPrice"), 0.0));
            item.put("subtotal", toJsonNumber(row.get("subtotal"), 0.0));
            item.put("discount", toJsonNumber(row.get("discount"), 0.0));
            item.put("discountReason", row.get("discountReason"));
            item.put("departmentId", row.get("departmentId"));
            item.put("departmentName", row.get("departmentName"));
            out.add(item);
        }
        return out;
    }
}
