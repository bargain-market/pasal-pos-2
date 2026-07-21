package com.pos.sync.outbound;

import com.pos.api.ApiClient;
import com.pos.api.dto.BatchProductSyncRequest;
import com.pos.api.dto.BatchProductSyncResponse;
import com.pos.api.dto.BatchDepartmentSyncRequest;
import com.pos.api.dto.BatchDepartmentSyncResponse;
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
import java.util.Collection;
import java.util.List;

/**
 * Outbound sync handler for products and departments.
 * Pushes locally created/updated products and departments to backend.
 * 
 * <h2>Sync Flow</h2>
 * 
 * <pre>
 * Local H2 Database ────────────────────────────────────────────────────────
 *     • products table (synced = FALSE)
 *     • departments table (synced = FALSE)
 *     │
 *     ▼
 * ProductOutboundSync ──────────────────────────────────────────────────────
 *     │
 *     │ Batch pending products/departments (up to 50)
 *     │ Submit to backend
 *     │ Mark as synced on success
 *     │
 *     ▼
 * Backend API ──────────────────────────────────────────────────────────────
 *     POST /pos/products/sync
 *     POST /pos/departments/sync
 * </pre>
 * 
 * <h2>Offline Resilience</h2>
 * - Products/Departments are always stored locally first
 * - Sync happens in background when online
 * - Backend handles duplicates gracefully (upsert)
 * - Batch submission for efficiency
 */
public class ProductOutboundSync implements SyncManager.OutboundSyncHandler {
    private static final Logger logger = LoggerFactory.getLogger(ProductOutboundSync.class);

    private static final int BATCH_SIZE = 50;

    private static ProductOutboundSync instance;

    private final ApiClient apiClient;
    private final DatabaseManager dbManager;
    private final ConfigManager config;

    private ProductOutboundSync() {
        this.apiClient = ApiClient.getInstance();
        this.dbManager = DatabaseManager.getInstance();
        this.config = ConfigManager.getInstance();
    }

    public static synchronized ProductOutboundSync getInstance() {
        if (instance == null) {
            instance = new ProductOutboundSync();
        }
        return instance;
    }

    @Override
    public String getName() {
        return "ProductSync";
    }

    @Override
    public SyncResult sync() throws Exception {
        // Log pending counts for visibility
        int pendingProducts = getPendingProductsCount();
        int pendingDepartments = getPendingDepartmentsCount();
        logger.info("Starting Product/Department outbound sync: {} pending products, {} pending departments",
                pendingProducts, pendingDepartments);

        int totalSynced = 0;
        int totalFailed = 0;
        StringBuilder errors = new StringBuilder();

        // Sync departments first (products may depend on them)
        try {
            SyncResult deptResult = syncDepartments();
            totalSynced += deptResult.getSynced();
            totalFailed += deptResult.getFailed();
            if (deptResult.getError() != null) {
                errors.append(deptResult.getError()).append("; ");
            }
        } catch (Exception e) {
            logger.error("Error syncing departments", e);
            errors.append("Departments: ").append(e.getMessage()).append("; ");
        }

        // Sync products
        try {
            SyncResult productResult = syncProducts();
            totalSynced += productResult.getSynced();
            totalFailed += productResult.getFailed();
            if (productResult.getError() != null) {
                errors.append(productResult.getError()).append("; ");
            }
        } catch (Exception e) {
            logger.error("Error syncing products", e);
            errors.append("Products: ").append(e.getMessage()).append("; ");
        }

        // Always log completion, even if nothing was synced
        if (totalSynced > 0 || totalFailed > 0) {
            logger.info("Product/Department outbound sync completed: {} synced, {} failed", totalSynced, totalFailed);
        } else if (pendingProducts > 0 || pendingDepartments > 0) {
            logger.info("Product/Department outbound sync completed: 0 synced (items may be queued for next sync)");
        } else {
            logger.debug("Product/Department outbound sync completed: no pending items to sync");
        }

        return new SyncResult(SyncDirection.OUTBOUND, totalSynced, totalFailed,
                errors.length() > 0 ? errors.toString() : null);
    }

    /**
     * Sync pending products to backend.
     * Processes ALL pending products in batches, continuing even if some batches
     * fail.
     */
    private SyncResult syncProducts() throws Exception {
        int totalSynced = 0;
        int totalFailed = 0;
        StringBuilder allErrors = new StringBuilder();

        // Keep processing batches until no more pending products
        while (true) {
            List<BatchProductSyncRequest.ProductSyncToBackend> pendingProducts = getPendingProducts(BATCH_SIZE);

            if (pendingProducts.isEmpty()) {
                break; // No more pending products
            }

            logger.info("Syncing batch of {} pending products to backend (total pending: {})",
                    pendingProducts.size(), getPendingProductsCount());

            try {
                BatchProductSyncResponse response = submitProductBatch(pendingProducts);

                int batchSynced = response.processed;
                int batchFailed = response.failed;

                // Mark successfully synced products
                if (response.results != null) {
                    for (BatchProductSyncResponse.ProductSyncResult result : response.results) {
                        if ("created".equals(result.status) || "updated".equals(result.status)) {
                            String productIdToMark = result.productId;
                            if (result.serverId != null && result.productId != null
                                    && !result.serverId.equals(result.productId)) {
                                reconcileLocalProductIdWithServer(result.productId, result.serverId);
                                productIdToMark = result.serverId;
                            }
                            markProductAsSynced(productIdToMark);
                        } else if ("failed".equals(result.status)) {
                            String errorMsg = String.format("Product %s failed: %s",
                                    result.productId != null ? result.productId : "unknown",
                                    result.error != null ? result.error : "Unknown error");
                            logger.error(errorMsg);
                            if (allErrors.length() > 0) {
                                allErrors.append("; ");
                            }
                            allErrors.append(errorMsg);
                        }
                    }
                }

                // Handle missing departments - mark them for re-sync
                if (response.missingDepartmentIds != null && !response.missingDepartmentIds.isEmpty()) {
                    logger.warn("Backend reported {} missing departments - marking for re-sync: {}",
                            response.missingDepartmentIds.size(), response.missingDepartmentIds);
                    for (String deptId : response.missingDepartmentIds) {
                        markDepartmentForResync(deptId);
                    }
                }

                totalSynced += batchSynced;
                totalFailed += batchFailed;

                logger.debug("Batch completed: {} synced, {} failed", batchSynced, batchFailed);

                // Avoid tight retry loop on the same permanently-failing row within one sync run
                if (batchSynced == 0 && batchFailed > 0) {
                    logger.warn(
                            "Product batch had {} failure(s) with no successes; deferring remaining pending products to next sync run",
                            batchFailed);
                    break;
                }

            } catch (ApiClient.ApiException e) {
                logger.error("Product batch sync failed: {}", e.getMessage());
                // Don't stop entirely - record the failure and continue with next batch
                // But if it's a network error, we should stop to avoid hammering a down server
                if (e.getMessage() != null &&
                        (e.getMessage().contains("Network error") ||
                                e.getMessage().contains("timeout") ||
                                e.getMessage().contains("Connection refused"))) {
                    totalFailed += pendingProducts.size();
                    if (allErrors.length() > 0) {
                        allErrors.append("; ");
                    }
                    allErrors.append("Network error, stopping sync: ").append(e.getMessage());
                    break; // Stop on network errors
                }
                totalFailed += pendingProducts.size();
                if (allErrors.length() > 0) {
                    allErrors.append("; ");
                }
                allErrors.append(e.getMessage());
            }
        }

        if (totalSynced == 0 && totalFailed == 0) {
            logger.debug("No pending products to sync");
            return SyncResult.empty(SyncDirection.OUTBOUND);
        }

        return new SyncResult(SyncDirection.OUTBOUND, totalSynced, totalFailed,
                allErrors.length() > 0 ? allErrors.toString() : null);
    }

    /**
     * Sync pending departments to backend.
     * Processes ALL pending departments in batches, continuing even if some batches
     * fail.
     */
    private SyncResult syncDepartments() throws Exception {
        int totalSynced = 0;
        int totalFailed = 0;
        StringBuilder allErrors = new StringBuilder();

        // Keep processing batches until no more pending departments
        while (true) {
            List<BatchDepartmentSyncRequest.DepartmentSyncToBackend> pendingDepartments = getPendingDepartments(
                    BATCH_SIZE);

            if (pendingDepartments.isEmpty()) {
                break; // No more pending departments
            }

            logger.info("Syncing batch of {} pending departments to backend (total pending: {})",
                    pendingDepartments.size(), getPendingDepartmentsCount());

            try {
                BatchDepartmentSyncResponse response = submitDepartmentBatch(pendingDepartments);

                int batchSynced = response.processed;
                int batchFailed = response.failed;

                // Mark successfully synced departments
                if (response.results != null) {
                    for (BatchDepartmentSyncResponse.DepartmentSyncResult result : response.results) {
                        if ("created".equals(result.status) || "updated".equals(result.status)) {
                            markDepartmentAsSynced(result.departmentId);
                        } else if ("failed".equals(result.status)) {
                            String errorMsg = String.format("Department %s failed: %s",
                                    result.departmentId != null ? result.departmentId : "unknown",
                                    result.error != null ? result.error : "Unknown error");
                            logger.error(errorMsg);
                            if (allErrors.length() > 0) {
                                allErrors.append("; ");
                            }
                            allErrors.append(errorMsg);
                        }
                    }
                }

                totalSynced += batchSynced;
                totalFailed += batchFailed;

                logger.debug("Batch completed: {} synced, {} failed", batchSynced, batchFailed);

            } catch (ApiClient.ApiException e) {
                logger.error("Department batch sync failed: {}", e.getMessage());
                // Don't stop entirely - record the failure and continue with next batch
                // But if it's a network error, we should stop to avoid hammering a down server
                if (e.getMessage() != null &&
                        (e.getMessage().contains("Network error") ||
                                e.getMessage().contains("timeout") ||
                                e.getMessage().contains("Connection refused"))) {
                    totalFailed += pendingDepartments.size();
                    if (allErrors.length() > 0) {
                        allErrors.append("; ");
                    }
                    allErrors.append("Network error, stopping sync: ").append(e.getMessage());
                    break; // Stop on network errors
                }
                totalFailed += pendingDepartments.size();
                if (allErrors.length() > 0) {
                    allErrors.append("; ");
                }
                allErrors.append(e.getMessage());
            }
        }

        if (totalSynced == 0 && totalFailed == 0) {
            logger.debug("No pending departments to sync");
            return SyncResult.empty(SyncDirection.OUTBOUND);
        }

        return new SyncResult(SyncDirection.OUTBOUND, totalSynced, totalFailed,
                allErrors.length() > 0 ? allErrors.toString() : null);
    }

    /**
     * Get pending products from local database.
     */
    private List<BatchProductSyncRequest.ProductSyncToBackend> getPendingProducts(int limit) throws SQLException {
        String sql = """
                SELECT id, name, sku, barcode, price, stock_quantity,
                       status, department_id, updated_at
                FROM products
                WHERE synced = FALSE
                ORDER BY updated_at ASC
                LIMIT ?
                """;

        List<BatchProductSyncRequest.ProductSyncToBackend> pendingProducts = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                BatchProductSyncRequest.ProductSyncToBackend product = new BatchProductSyncRequest.ProductSyncToBackend();
                product.id = rs.getString("id");
                product.name = rs.getString("name");
                product.sku = rs.getString("sku");
                product.barcode = rs.getString("barcode");

                BigDecimal price = rs.getBigDecimal("price");
                product.price = price != null ? price.doubleValue() : 0.0;

                product.listPrice = null;

                product.stockQuantity = rs.getInt("stock_quantity");
                product.status = rs.getString("status");
                product.departmentId = rs.getString("department_id");
                product.updatedAt = rs.getString("updated_at");

                pendingProducts.add(product);
            }
        }

        return pendingProducts;
    }

    /**
     * Get pending departments from local database.
     */
    private List<BatchDepartmentSyncRequest.DepartmentSyncToBackend> getPendingDepartments(int limit)
            throws SQLException {
        String sql = """
                SELECT id, name, icon, parent_id, department_type, tax_enabled, tax_rate,
                       hide_on_register, ebt_eligible, exclude_from_global_price_increase,
                       no_points_earning, age_verification, multipack_enabled, multipack_discount_type,
                       multipack_discount_value, multipack_min_quantity, multipack_requires_approval, updated_at
                FROM departments
                WHERE synced = FALSE
                ORDER BY updated_at ASC
                LIMIT ?
                """;

        List<BatchDepartmentSyncRequest.DepartmentSyncToBackend> pendingDepartments = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                BatchDepartmentSyncRequest.DepartmentSyncToBackend dept = new BatchDepartmentSyncRequest.DepartmentSyncToBackend();
                dept.id = rs.getString("id");
                dept.name = rs.getString("name");
                dept.icon = rs.getString("icon");
                dept.parentId = rs.getString("parent_id");
                dept.departmentType = rs.getString("department_type");
                dept.taxEnabled = rs.getObject("tax_enabled") != null ? rs.getBoolean("tax_enabled") : true;

                BigDecimal taxRate = rs.getBigDecimal("tax_rate");
                dept.taxRate = taxRate != null ? taxRate.doubleValue() : null;

                dept.hideOnRegister = rs.getObject("hide_on_register") != null ? rs.getBoolean("hide_on_register")
                        : false;
                dept.ebtEligible = rs.getObject("ebt_eligible") != null ? rs.getBoolean("ebt_eligible") : false;
                dept.excludeFromGlobalPriceIncrease = rs.getObject("exclude_from_global_price_increase") != null
                        ? rs.getBoolean("exclude_from_global_price_increase")
                        : false;
                dept.noPointsEarning = rs.getObject("no_points_earning") != null ? rs.getBoolean("no_points_earning")
                        : false;
                dept.ageVerification = rs.getObject("age_verification") != null ? rs.getInt("age_verification") : null;

                // Multi-pack discount fields
                dept.multipackEnabled = rs.getObject("multipack_enabled") != null ? rs.getBoolean("multipack_enabled")
                        : false;
                dept.multipackDiscountType = rs.getString("multipack_discount_type");
                BigDecimal multipackDiscountValue = rs.getBigDecimal("multipack_discount_value");
                dept.multipackDiscountValue = multipackDiscountValue != null ? multipackDiscountValue.doubleValue()
                        : null;
                dept.multipackMinQuantity = rs.getObject("multipack_min_quantity") != null
                        ? rs.getInt("multipack_min_quantity")
                        : null;
                dept.multipackRequiresApproval = rs.getObject("multipack_requires_approval") != null
                        ? rs.getBoolean("multipack_requires_approval")
                        : false;

                dept.updatedAt = rs.getString("updated_at");

                pendingDepartments.add(dept);
            }
        }

        return pendingDepartments;
    }

    /**
     * Submit batch of products to backend.
     */
    private BatchProductSyncResponse submitProductBatch(List<BatchProductSyncRequest.ProductSyncToBackend> products)
            throws ApiClient.ApiException {
        String deviceId = config.getProperty("device.id", "");
        String syncTimestamp = Instant.now().atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);

        BatchProductSyncRequest request = new BatchProductSyncRequest(products, deviceId, syncTimestamp);

        ApiClient.ApiResponse<BatchProductSyncResponse> response = apiClient.post(
                "/pos/products/sync",
                request,
                BatchProductSyncResponse.class);

        return response.getData();
    }

    /**
     * Submit batch of departments to backend.
     */
    private BatchDepartmentSyncResponse submitDepartmentBatch(
            List<BatchDepartmentSyncRequest.DepartmentSyncToBackend> departments)
            throws ApiClient.ApiException {
        String deviceId = config.getProperty("device.id", "");
        String syncTimestamp = Instant.now().atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);

        BatchDepartmentSyncRequest request = new BatchDepartmentSyncRequest(departments, deviceId, syncTimestamp);

        ApiClient.ApiResponse<BatchDepartmentSyncResponse> response = apiClient.post(
                "/pos/departments/sync",
                request,
                BatchDepartmentSyncResponse.class);

        return response.getData();
    }

    /**
     * Mark products so they are picked up by outbound product sync again.
     * Used when the backend rejects a sale with "Product not found" even though the row exists locally
     * (e.g. product was never pushed or inbound sync marked it synced incorrectly).
     */
    public void markProductIdsForResync(Collection<String> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return;
        }
        try (Connection conn = dbManager.getConnection()) {
            String sql = "UPDATE products SET synced = FALSE WHERE id = ?";
            int marked = 0;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (String id : productIds) {
                    if (id == null || id.isBlank()) {
                        continue;
                    }
                    stmt.setString(1, id.trim());
                    stmt.addBatch();
                    marked++;
                }
                if (marked > 0) {
                    stmt.executeBatch();
                }
                conn.commit();
            }
            logger.info("Marked {} product id(s) for outbound resync (synced=FALSE)", marked);
        } catch (SQLException e) {
            logger.warn("Failed to mark products for resync: {}", e.getMessage());
        }
    }

    /**
     * When the backend already had this barcode under a different id, align the local catalog id
     * with the server canonical id so future sales and inbound sync stay consistent.
     */
    private void reconcileLocalProductIdWithServer(String localProductId, String serverProductId) {
        if (localProductId == null || serverProductId == null || localProductId.equals(serverProductId)) {
            return;
        }
        try (Connection conn = dbManager.getConnection()) {
            boolean serverRowExists = productRowExists(conn, serverProductId);
            if (serverRowExists) {
                reassignProductReferences(conn, localProductId, serverProductId);
                try (PreparedStatement delete = conn.prepareStatement("DELETE FROM products WHERE id = ?")) {
                    delete.setString(1, localProductId);
                    delete.executeUpdate();
                }
                logger.info("Reconciled duplicate local product {} with existing server id {}", localProductId,
                        serverProductId);
            } else {
                reassignProductReferences(conn, localProductId, serverProductId);
                try (PreparedStatement update = conn.prepareStatement("UPDATE products SET id = ? WHERE id = ?")) {
                    update.setString(1, serverProductId);
                    update.setString(2, localProductId);
                    update.executeUpdate();
                }
                logger.info("Reconciled local product id {} to server id {}", localProductId, serverProductId);
            }
            conn.commit();
        } catch (SQLException e) {
            logger.warn("Failed to reconcile product id {} -> {}: {}", localProductId, serverProductId,
                    e.getMessage());
        }
    }

    private boolean productRowExists(Connection conn, String productId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM products WHERE id = ? LIMIT 1")) {
            stmt.setString(1, productId);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        }
    }

    private void reassignProductReferences(Connection conn, String fromProductId, String toProductId)
            throws SQLException {
        String[] tables = {
                "sale_items",
                "refund_items",
                "held_sale_items",
                "cart_cancellation_items",
                "inventory_log",
                "vendor_payout_items",
                "favorite_products"
        };
        for (String table : tables) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE " + table + " SET product_id = ? WHERE product_id = ?")) {
                stmt.setString(1, toProductId);
                stmt.setString(2, fromProductId);
                stmt.executeUpdate();
            } catch (SQLException e) {
                // Table may not exist on older DB schemas
                logger.debug("Skipped product_id reassignment on {}: {}", table, e.getMessage());
            }
        }
    }

    /**
     * Mark product as synced.
     */
    private void markProductAsSynced(String productId) {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "UPDATE products SET synced = TRUE WHERE id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, productId);
                stmt.executeUpdate();
                conn.commit();
            }
        } catch (SQLException e) {
            logger.warn("Failed to mark product as synced: {}", productId, e);
        }
    }

    /**
     * Mark department as synced.
     */
    private void markDepartmentAsSynced(String departmentId) {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "UPDATE departments SET synced = TRUE WHERE id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, departmentId);
                stmt.executeUpdate();
                conn.commit();
            }
        } catch (SQLException e) {
            logger.warn("Failed to mark department as synced: {}", departmentId, e);
        }
    }

    /**
     * Mark department for re-sync (set synced = FALSE).
     * Called when backend reports a missing department that needs to be
     * re-submitted.
     */
    private void markDepartmentForResync(String departmentId) {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "UPDATE departments SET synced = FALSE WHERE id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, departmentId);
                int updated = stmt.executeUpdate();
                conn.commit();
                if (updated > 0) {
                    logger.info("Marked department {} for re-sync", departmentId);
                } else {
                    logger.warn("Department {} not found in local database for re-sync", departmentId);
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to mark department for re-sync: {}", departmentId, e);
        }
    }

    /**
     * Get count of pending products (for status display).
     */
    public int getPendingProductsCount() {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT COUNT(*) FROM products WHERE synced = FALSE";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting pending products count", e);
        }
        return 0;
    }

    /**
     * Get count of pending departments (for status display).
     */
    public int getPendingDepartmentsCount() {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT COUNT(*) FROM departments WHERE synced = FALSE";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting pending departments count", e);
        }
        return 0;
    }

    /**
     * Get total count of pending items (products + departments).
     */
    public int getPendingCount() {
        return getPendingProductsCount() + getPendingDepartmentsCount();
    }
}
