package com.pos.sync.outbound;

import com.pos.api.ApiClient;
import com.pos.api.dto.BatchSaleRequest;
import com.pos.api.dto.BatchSaleResponse;
import com.pos.api.dto.SaleSubmission;
import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.sync.SyncDirection;
import com.pos.sync.SyncManager;
import com.pos.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Outbound sync handler for sales transactions.
 * Pushes pending sales from local database to backend.
 * 
 * <h2>Sync Flow</h2>
 * 
 * <pre>
 * Local H2 Database ────────────────────────────────────────────────────────
 *     • sales table (synced = FALSE)
 *     • sale_items table
 *     │
 *     ▼
 * SalesOutboundSync ────────────────────────────────────────────────────────
 *     │
 *     │ Batch pending sales (up to 50)
 *     │ Submit to backend
 *     │ Mark as synced on success
 *     │
 *     ▼
 * Backend API ──────────────────────────────────────────────────────────────
 *     POST /pos/sales/batch
 * </pre>
 * 
 * <h2>Offline Resilience</h2>
 * - Sales are always stored locally first
 * - Sync happens in background when online
 * - Duplicate detection by saleId (backend handles duplicates gracefully)
 * - Batch submission for efficiency
 */
public class SalesOutboundSync implements SyncManager.OutboundSyncHandler {
    private static final Logger logger = LoggerFactory.getLogger(SalesOutboundSync.class);

    private static final int BATCH_SIZE = 50;

    /** Backend error text from {@code posSync.service.ts} submitSale validation. */
    private static final Pattern PRODUCT_NOT_FOUND_IN_ERROR = Pattern.compile(
            "Product not found:\\s*([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})",
            Pattern.CASE_INSENSITIVE);

    private static final int MAX_PRODUCT_RECOVERY_PASSES_PER_SYNC = 2;

    private static SalesOutboundSync instance;

    private final ApiClient apiClient;
    private final DatabaseManager dbManager;
    private final ConfigManager config;

    private SalesOutboundSync() {
        this.apiClient = ApiClient.getInstance();
        this.dbManager = DatabaseManager.getInstance();
        this.config = ConfigManager.getInstance();
    }

    public static synchronized SalesOutboundSync getInstance() {
        if (instance == null) {
            instance = new SalesOutboundSync();
        }
        return instance;
    }

    @Override
    public String getName() {
        return "SalesSync";
    }

    @Override
    public SyncResult sync() throws Exception {
        int totalSynced = 0;
        int totalFailed = 0;
        StringBuilder allErrors = new StringBuilder();

        // Keep processing batches until no more pending sales or a batch fails
        int productRecoveryPasses = 0;
        while (true) {
            // Get pending sales
            List<SaleSubmission> pendingSales = getPendingSales(BATCH_SIZE);

            if (pendingSales.isEmpty()) {
                break; // No more pending sales
            }

            logger.info("Syncing {} pending sales", pendingSales.size());
            logger.debug("Sale IDs to sync: {}", pendingSales.stream()
                    .map(s -> s.saleId != null ? s.saleId : "null")
                    .collect(java.util.stream.Collectors.joining(", ")));

            try {
                // Submit batch to backend
                BatchSaleResponse response = submitBatch(pendingSales);

                int batchSynced = response.processed;
                int batchFailed = response.failed;

                // Mark successfully synced sales and log errors
                if (response.results != null) {
                    for (BatchSaleResponse.BatchSaleResult result : response.results) {
                        if ("created".equals(result.status) || "duplicate".equals(result.status)) {
                            markSaleAsSynced(result.saleId);
                        } else if ("failed".equals(result.status)) {
                            String errorMsg = String.format("Sale %s failed: %s",
                                    result.saleId != null ? result.saleId : "unknown",
                                    result.error != null ? result.error : "Unknown error");
                            logger.error(errorMsg);
                            if (allErrors.length() > 0) {
                                allErrors.append("; ");
                            }
                            allErrors.append(errorMsg);

                            // Save error to database so it can be displayed in UI
                            if (result.saleId != null) {
                                markSaleAsFailed(result.saleId, result.error != null ? result.error : "Unknown error");
                            }
                        }
                    }
                }

                totalSynced += batchSynced;

                if (batchFailed > 0) {
                    Set<String> missingProductIds = extractProductNotFoundIdsFromResults(response.results);
                    if (!missingProductIds.isEmpty() && productRecoveryPasses < MAX_PRODUCT_RECOVERY_PASSES_PER_SYNC) {
                        productRecoveryPasses++;
                        logger.warn(
                                "Sales batch reported missing backend product(s) {}. Marking for outbound product sync and retrying batch (attempt {}/{})",
                                missingProductIds, productRecoveryPasses, MAX_PRODUCT_RECOVERY_PASSES_PER_SYNC);
                        ProductOutboundSync.getInstance().markProductIdsForResync(missingProductIds);
                        try {
                            ProductOutboundSync.getInstance().sync();
                        } catch (Exception ex) {
                            logger.error("Product outbound sync after product-not-found errors failed: {}", ex.getMessage(),
                                    ex);
                        }
                        continue;
                    }
                    totalFailed += batchFailed;
                    logger.error("Sales sync batch failed: {} synced, {} failed. Stopping sync for remaining items.",
                            batchSynced, batchFailed);
                    break; // STOP here as per requirement: do not proceed to next batch if any failed
                } else {
                    productRecoveryPasses = 0;
                    logger.info("Sales sync batch completed: {} synced, {} failed", batchSynced, batchFailed);
                }

            } catch (ApiClient.ApiException e) {
                logger.error("Sales batch sync failed: {}", e.getMessage());
                totalFailed += pendingSales.size();
                if (allErrors.length() > 0) {
                    allErrors.append("; ");
                }
                allErrors.append(e.getMessage());
                break; // Stop on API/Network errors
            }
        }

        if (totalSynced == 0 && totalFailed == 0) {
            logger.debug("No pending sales to sync");
            return SyncResult.empty(SyncDirection.OUTBOUND);
        }

        return new SyncResult(SyncDirection.OUTBOUND, totalSynced, totalFailed,
                totalFailed > 0 ? allErrors.toString() : null);
    }

    /**
     * Get pending sales from local database.
     */
    private List<SaleSubmission> getPendingSales(int limit) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            // First, get count of pending sales
            String countSql = "SELECT COUNT(*) FROM sales WHERE synced = FALSE";
            int totalPending = 0;
            try (PreparedStatement countStmt = conn.prepareStatement(countSql)) {
                ResultSet countRs = countStmt.executeQuery();
                if (countRs.next()) {
                    totalPending = countRs.getInt(1);
                }
            }
            logger.debug("Found {} total pending sales in database", totalPending);

            String sql = """
                    SELECT * FROM sales
                    WHERE synced = FALSE
                    ORDER BY created_at ASC
                    LIMIT ?
                    """;

            List<SaleSubmission> pendingSales = new ArrayList<>();

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, limit);
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    String saleId = rs.getString("sale_id");
                    try {
                        SaleSubmission submission = reconstructSaleSubmission(conn, saleId, rs);
                        if (submission != null && validateSaleSubmission(submission, conn)) {
                            pendingSales.add(submission);
                        } else {
                            logger.warn("Skipping invalid sale {}: missing required fields or products not synced", saleId);
                        }
                    } catch (Exception e) {
                        logger.error("Error reconstructing sale {}: {}", saleId, e.getMessage(), e);
                    }
                }
            }

            logger.debug("Retrieved {} valid pending sales out of {} total pending", pendingSales.size(), totalPending);
            return pendingSales;
        }
    }

    /**
     * Validate sale submission has required fields and all products exist in local
     * database.
     * Products must be synced from backend before sales can be synced.
     */
    private boolean validateSaleSubmission(SaleSubmission submission, Connection conn) {
        if (submission.saleId == null || submission.saleId.isEmpty()) {
            logger.warn("Sale submission missing saleId");
            return false;
        }
        if (submission.cashierName == null || submission.cashierName.isEmpty()) {
            logger.warn("Sale submission {} missing cashierName", submission.saleId);
            return false;
        }
        if (submission.items == null || submission.items.isEmpty()) {
            logger.warn("Sale submission {} missing items", submission.saleId);
            return false;
        }

        // Validate all products exist in local database
        for (SaleSubmission.SaleItemInput item : submission.items) {
            // Skip validation for Open Department items (they don't have a fixed product
            // ID)
            if (Boolean.TRUE.equals(item.isOpenDepartmentItem)) {
                continue;
            }

            if (item.productId == null || item.productId.isEmpty()) {
                logger.warn("Sale submission {} has item missing productId", submission.saleId);
                return false;
            }

            // Check if product exists in local database
            if (!productExists(conn, item.productId)) {
                logger.warn("Sale {} cannot be synced: product {} not found in local database. " +
                        "Product may not be synced from backend yet.", submission.saleId, item.productId);
                return false;
            }
        }
        return true;
    }

    /**
     * Check if a product exists in the local database.
     * This ensures products are synced from backend before sales can be synced.
     */
    private boolean productExists(Connection conn, String productId) {
        try {
            String sql = "SELECT COUNT(*) FROM products WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, productId);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            logger.error("Error checking if product {} exists", productId, e);
        }
        return false;
    }

    /**
     * Reconstruct SaleSubmission from database.
     */
    private SaleSubmission reconstructSaleSubmission(Connection conn, String saleId, ResultSet saleRs)
            throws SQLException {
        SaleSubmission submission = new SaleSubmission();

        submission.saleId = saleRs.getString("sale_id");
        submission.subtotal = saleRs.getBigDecimal("subtotal").doubleValue();
        submission.discount = saleRs.getBigDecimal("discount").doubleValue();
        submission.tax = saleRs.getBigDecimal("tax").doubleValue();
        submission.total = saleRs.getBigDecimal("total").doubleValue();
        submission.paymentMethod = saleRs.getString("payment_method");
        submission.cashierName = saleRs.getString("cashier_name");
        submission.cashierId = saleRs.getString("cashier_id");
        submission.posUserId = saleRs.getString("pos_user_id");
        submission.timestamp = saleRs.getString("timestamp");
        // Set localDate from created_at (which is in local timezone) for proper
        // date-based filtering on backend
        Timestamp createdAt = saleRs.getTimestamp("created_at");
        if (createdAt != null) {
            submission.localDate = createdAt.toLocalDateTime().toLocalDate().toString();
        } else {
            // Fallback to current date if created_at is null
            submission.localDate = java.time.LocalDate.now().toString();
        }
        // Read cash payment details
        BigDecimal amountReceived = saleRs.getBigDecimal("amount_received");
        if (amountReceived != null) {
            submission.amountReceived = amountReceived.doubleValue();
        }
        BigDecimal change = saleRs.getBigDecimal("change");
        if (change != null) {
            submission.change = change.doubleValue();
        }
        // Read GPI (Gross Profit Increase / surcharge) field
        BigDecimal gpi = saleRs.getBigDecimal("gpi");
        if (gpi != null) {
            submission.gpi = gpi.doubleValue();
        }
        // Read EBT fee field
        BigDecimal ebtFee = saleRs.getBigDecimal("ebt_fee");
        if (ebtFee != null) {
            submission.ebtFee = ebtFee.doubleValue();
        }

        // Read void details
        submission.voided = saleRs.getBoolean("voided");
        if (submission.voided) {
            Timestamp voidedAt = saleRs.getTimestamp("voided_at");
            if (voidedAt != null) {
                submission.voidedAt = voidedAt.toInstant().toString();
            }
            submission.voidedBy = saleRs.getString("voided_by");
            submission.voidReason = saleRs.getString("void_reason");
        }

        // Ensure cashierName is not null (required field)
        if (submission.cashierName == null || submission.cashierName.isEmpty()) {
            // Try to get from posUserId or use default
            String defaultCashier = submission.posUserId != null ? submission.posUserId : "Cashier";
            logger.warn("Sale {} has null cashierName, using default: {}", saleId, defaultCashier);
            submission.cashierName = defaultCashier;
        }

        // Get sale items
        String itemSql = "SELECT * FROM sale_items WHERE sale_id = ?";
        List<SaleSubmission.SaleItemInput> items = new ArrayList<>();

        try (PreparedStatement itemStmt = conn.prepareStatement(itemSql)) {
            itemStmt.setString(1, saleId);
            ResultSet itemRs = itemStmt.executeQuery();

            while (itemRs.next()) {
                SaleSubmission.SaleItemInput item = new SaleSubmission.SaleItemInput();
                String productId = itemRs.getString("product_id");
                String sku = itemRs.getString("sku");

                // Resolve productId: if it's a barcode (not a UUID), look up the actual UUID
                if (productId != null && !isUUID(productId)) {
                    String resolvedProductId = resolveProductIdByBarcode(conn, productId);
                    if (resolvedProductId != null) {
                        item.productId = resolvedProductId;
                        logger.debug("Resolved barcode {} to product UUID {}", productId, resolvedProductId);
                    } else {
                        item.productId = productId;
                        logger.warn("Could not resolve barcode {} to product UUID for sale {}", productId, saleId);
                    }
                } else if ((productId == null || productId.isEmpty()) && sku != null && !sku.isEmpty()) {
                    // Try to resolve missing product ID from SKU
                    String resolvedProductId = resolveProductIdBySku(conn, sku);
                    if (resolvedProductId != null) {
                        item.productId = resolvedProductId;
                        logger.debug("Resolved missing product ID for sale {} using SKU {}", saleId, sku);
                    } else {
                        item.productId = productId;
                    }
                } else {
                    item.productId = productId;
                }

                item.sku = sku;
                item.name = itemRs.getString("name");

                // Read department info
                item.departmentId = itemRs.getString("department_id");
                item.departmentName = itemRs.getString("department_name");

                // Identify open department items
                if (item.sku != null && item.sku.startsWith("OPEN-")) {
                    item.isOpenDepartmentItem = true;
                }

                item.price = itemRs.getBigDecimal("price").doubleValue();
                item.quantity = itemRs.getInt("quantity");
                item.subtotal = itemRs.getBigDecimal("subtotal").doubleValue();

                // Include discount fields if available
                BigDecimal discount = itemRs.getBigDecimal("discount");
                if (discount != null) {
                    item.discount = discount.doubleValue();
                }
                String discountReason = itemRs.getString("discount_reason");
                if (discountReason != null && !discountReason.isEmpty()) {
                    item.discountReason = discountReason;
                }
                // Include item-level GPI if available
                BigDecimal itemGpi = itemRs.getBigDecimal("gpi");
                if (itemGpi != null) {
                    item.gpi = itemGpi.doubleValue();
                }

                items.add(item);
            }
        }

        submission.items = items;
        return submission;
    }

    /**
     * Submit batch of sales to backend.
     */
    private BatchSaleResponse submitBatch(List<SaleSubmission> sales) throws ApiClient.ApiException {
        String deviceId = config.getProperty("device.id", "");
        String syncTimestamp = Instant.now().atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);

        // Log sale IDs being synced for debugging
        List<String> saleIds = sales.stream()
                .map(s -> s.saleId != null ? s.saleId : "null")
                .collect(Collectors.toList());
        logger.debug("Submitting batch of {} sales: {}", sales.size(), saleIds);

        BatchSaleRequest request = new BatchSaleRequest(sales, deviceId, syncTimestamp);

        ApiClient.ApiResponse<BatchSaleResponse> response = apiClient.post(
                "/pos/sales/batch",
                request,
                BatchSaleResponse.class);

        return response.getData();
    }

    /**
     * Mark sale as synced and clear any previous errors.
     */
    private void markSaleAsSynced(String saleId) {
        try (Connection conn = dbManager.getConnection()) {
            // Clear sync_error when successfully synced
            String sql = "UPDATE sales SET synced = TRUE, sync_error = NULL WHERE sale_id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, saleId);
                stmt.executeUpdate();
                conn.commit();
            }
        } catch (SQLException e) {
            logger.warn("Failed to mark sale as synced: {}", saleId, e);
        }
    }

    /**
     * Mark sale as failed and save the error message.
     */
    private void markSaleAsFailed(String saleId, String error) {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "UPDATE sales SET sync_error = ? WHERE sale_id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, error);
                stmt.setString(2, saleId);
                stmt.executeUpdate();
                conn.commit();
            }
        } catch (SQLException e) {
            logger.warn("Failed to mark sale as failed: {}", saleId, e);
        }
    }

    /**
     * Get count of pending sales (for status display).
     */
    public int getPendingSalesCount() {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT COUNT(*) FROM sales WHERE synced = FALSE";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting pending sales count", e);
        }
        return 0;
    }

    /**
     * Check if a string is a valid UUID format.
     * UUID format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx (8-4-4-4-12 hex characters)
     */
    private boolean isUUID(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        // UUID pattern: 8-4-4-4-12 hex characters separated by hyphens
        return str.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    }

    /**
     * Resolve a barcode to a product UUID by looking it up in the local products
     * table.
     * The backend expects productId to be a UUID, not a barcode.
     * 
     * @param conn    Database connection
     * @param barcode Product barcode
     * @return Product UUID if found, null otherwise
     */
    private String resolveProductIdByBarcode(Connection conn, String barcode) {
        try {
            String sql = "SELECT id FROM products WHERE barcode = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, barcode);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getString("id");
                }
            }
        } catch (SQLException e) {
            logger.error("Error resolving barcode {} to product UUID", barcode, e);
        }
        return null;
    }

    /**
     * Resolve a SKU to a product UUID by looking it up in the local products
     * table.
     * This is used as a fallback when productId is missing from sale_items.
     * 
     * @param conn Database connection
     * @param sku  Product SKU
     * @return Product UUID if found, null otherwise
     */
    private String resolveProductIdBySku(Connection conn, String sku) {
        try {
            String sql = "SELECT id FROM products WHERE sku = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, sku);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getString("id");
                }
            }
        } catch (SQLException e) {
            logger.error("Error resolving SKU {} to product UUID", sku, e);
        }
        return null;
    }

    /**
     * Collect product UUIDs from batch sale errors such as
     * {@code Product not found: <uuid>} (see backend {@code submitSale} validation).
     */
    private static Set<String> extractProductNotFoundIdsFromResults(List<BatchSaleResponse.BatchSaleResult> results) {
        Set<String> ids = new LinkedHashSet<>();
        if (results == null) {
            return ids;
        }
        for (BatchSaleResponse.BatchSaleResult result : results) {
            if (!"failed".equals(result.status) || result.error == null || result.error.isEmpty()) {
                continue;
            }
            Matcher m = PRODUCT_NOT_FOUND_IN_ERROR.matcher(result.error);
            if (m.find()) {
                ids.add(m.group(1));
            }
        }
        return ids;
    }
}
