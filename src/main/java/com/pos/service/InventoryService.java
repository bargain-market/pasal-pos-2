package com.pos.service;

import com.pos.api.ApiClient;
import com.pos.api.dto.BatchStockUpdateRequest;
import com.pos.api.dto.StockAdjustmentRequest;
import com.pos.api.dto.StockReconciliationRequest;
import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.model.InventoryLog;
import com.pos.model.Product;
import com.pos.service.RoleBasedAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for inventory management operations
 */
public class InventoryService {
    private static final Logger logger = LoggerFactory.getLogger(InventoryService.class);
    private static InventoryService instance;
    private static final AtomicInteger inFlightStockSync = new AtomicInteger(0);

    private final DatabaseManager dbManager;
    private final ProductManagementService productService;
    private final UserAuthService userAuthService;
    private final ApiClient apiClient;
    private final OfflineSyncService offlineSyncService;

    private InventoryService() {
        this.dbManager = DatabaseManager.getInstance();
        this.productService = ProductManagementService.getInstance();
        this.userAuthService = UserAuthService.getInstance();
        this.apiClient = ApiClient.getInstance();
        this.offlineSyncService = OfflineSyncService.getInstance();
    }

    public static synchronized InventoryService getInstance() {
        if (instance == null) {
            instance = new InventoryService();
        }
        return instance;
    }

    /**
     * Calculate stock status based on quantity and reorder level
     */
    private String calculateStockStatus(int stock, Integer reorderLevel) {
        return ProductManagementService.calculateProductStatus(stock, reorderLevel);
    }

    /**
     * Get reorder level for a product
     */
    public Integer getReorderLevel(String productId) throws SQLException {
        String sql = "SELECT reorder_level FROM products WHERE id = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, productId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                Integer reorderLevel = rs.getObject("reorder_level", Integer.class);
                return reorderLevel != null ? reorderLevel : 0;
            }
        }
        return 0;
    }

    /**
     * Set reorder level for a product
     */
    public void setReorderLevel(String productId, int reorderLevel) throws SQLException {
        String sql = "UPDATE products SET reorder_level = ? WHERE id = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, reorderLevel);
            stmt.setString(2, productId);
            stmt.executeUpdate();
            conn.commit();
            logger.info("Set reorder level for product {} to {}", productId, reorderLevel);
        }
    }

    /**
     * Adjust stock with change type and reason
     */
    public void adjustStock(String productId, int adjustment, String changeType, String reason) throws SQLException {
        adjustStockInternal(productId, adjustment, changeType, reason, true);
    }
    
    /**
     * Adjust stock for sale transactions - bypasses permission check since sales should always update stock
     * This is an internal operation triggered by the sale process, not a manual stock adjustment
     */
    public void adjustStockForSale(String productId, int adjustment, String reason) throws SQLException {
        adjustStockInternal(productId, adjustment, "SALE", reason, false);
    }

    /**
     * Data class for batch stock adjustment
     */
    public static class BatchAdjustmentItem {
        public String productId;
        public int adjustment;

        public BatchAdjustmentItem(String productId, int adjustment) {
            this.productId = productId;
            this.adjustment = adjustment;
        }
    }

    /**
     * Batch adjust stock for multiple products
     */
    public void adjustStockBatch(List<BatchAdjustmentItem> items, String changeType, String reason) throws SQLException {
        if (items == null || items.isEmpty()) {
            return;
        }

        try (Connection conn = dbManager.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                pendingStockSyncRequests.remove();
                adjustStockBatch(conn, items, changeType, reason);
                conn.commit();
                flushPendingStockSync();
            } catch (Exception e) {
                conn.rollback();
                logger.error("Batch stock adjustment failed", e);
                throw new SQLException("Batch stock adjustment failed", e);
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        }
    }

    /**
     * Batch adjust stock using an existing connection (caller manages transaction).
     * Backend sync is deferred until after commit by the caller.
     */
    public void adjustStockBatch(Connection conn, List<BatchAdjustmentItem> items, String changeType, String reason)
            throws SQLException {
        if (items == null || items.isEmpty()) {
            return;
        }

        boolean originalAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        pendingStockSyncRequests.remove();

        try {
            // Collect all product IDs
            List<String> productIds = items.stream()
                    .map(item -> item.productId)
                    .distinct()
                    .collect(java.util.stream.Collectors.toList());

            // Map product ID to adjustment (sum up if multiple items for same product)
            java.util.Map<String, Integer> adjustmentMap = new java.util.HashMap<>();
            for (BatchAdjustmentItem item : items) {
                adjustmentMap.put(item.productId, adjustmentMap.getOrDefault(item.productId, 0) + item.adjustment);
            }

            // Fetch current product info for all items
            // Build IN clause
            StringBuilder inClause = new StringBuilder();
            for (int i = 0; i < productIds.size(); i++) {
                if (i > 0) inClause.append(",");
                inClause.append("?");
            }

            String selectSql = "SELECT id, name, sku, stock_quantity, reorder_level FROM products WHERE id IN (" + inClause.toString() + ")";
            java.util.Map<String, ProductData> productDataMap = new java.util.HashMap<>();

            try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
                for (int i = 0; i < productIds.size(); i++) {
                    stmt.setString(i + 1, productIds.get(i));
                }

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString("id");
                        ProductData data = new ProductData();
                        data.id = id;
                        data.name = rs.getString("name");
                        data.sku = rs.getString("sku");
                        data.stockQuantity = rs.getInt("stock_quantity");
                        data.reorderLevel = rs.getObject("reorder_level", Integer.class);
                        if (data.reorderLevel == null) data.reorderLevel = 0;
                        productDataMap.put(id, data);
                    }
                }
            }

            // Prepare batch updates
            String updateSql = "UPDATE products SET stock_quantity = ?, status = ?, updated_at = ? WHERE id = ?";
            String logSql = "INSERT INTO inventory_log (id, product_id, product_name, change_type, previous_quantity, new_quantity, change_amount, reason, user_id, user_name, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            List<StockAdjustmentRequest> syncRequests = new ArrayList<>();
            List<String> logIds = new ArrayList<>();
            String userId = userAuthService.getCurrentUserId();
            String userName = userAuthService.getCurrentUserName();
            Timestamp now = Timestamp.valueOf(LocalDateTime.now());
            String nowStr = now.toString();

            try (PreparedStatement updateStmt = conn.prepareStatement(updateSql);
                 PreparedStatement logStmt = conn.prepareStatement(logSql)) {

                for (String productId : productIds) {
                    ProductData currentData = productDataMap.get(productId);
                    if (currentData == null) {
                        logger.warn("Product not found during batch adjustment: {}", productId);
                        continue;
                    }

                    int adjustment = adjustmentMap.get(productId);
                    int previousQuantity = currentData.stockQuantity;
                    int newQuantity = previousQuantity + adjustment;

                    // Calculate status
                    String status = calculateStockStatus(newQuantity, currentData.reorderLevel);

                    // Add to update batch
                    updateStmt.setInt(1, newQuantity);
                    updateStmt.setString(2, status);
                    updateStmt.setString(3, nowStr);
                    updateStmt.setString(4, productId);
                    updateStmt.addBatch();

                    // Add to log batch
                    String logId = UUID.randomUUID().toString();
                    logIds.add(logId);
                    logStmt.setString(1, logId);
                    logStmt.setString(2, productId);
                    logStmt.setString(3, currentData.name);
                    logStmt.setString(4, changeType);
                    logStmt.setInt(5, previousQuantity);
                    logStmt.setInt(6, newQuantity);
                    logStmt.setInt(7, adjustment);
                    logStmt.setString(8, reason);
                    logStmt.setString(9, userId);
                    logStmt.setString(10, userName);
                    logStmt.setTimestamp(11, now);
                    logStmt.addBatch();

                    // Prepare sync request
                    StockAdjustmentRequest req = new StockAdjustmentRequest();
                    req.productId = productId;
                    req.productName = currentData.name;
                    req.sku = currentData.sku;
                    req.changeType = changeType;
                    req.previousQuantity = previousQuantity;
                    req.newQuantity = newQuantity;
                    req.adjustment = adjustment;
                    req.reason = reason;
                    req.userId = userId;
                    req.userName = userName;
                    req.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
                    syncRequests.add(req);

                    logger.info("Batch adjusting stock for product {} by {} (new: {})", productId, adjustment, newQuantity);
                }

                updateStmt.executeBatch();
                logStmt.executeBatch();
            }

            pendingStockSyncRequests.set(new StockSyncPayload(syncRequests, logIds));
        } catch (Exception e) {
            pendingStockSyncRequests.remove();
            throw new SQLException("Batch stock adjustment failed", e);
        } finally {
            conn.setAutoCommit(originalAutoCommit);
        }
    }

    /** Holds pending stock sync data until the outer transaction commits. */
    private static final ThreadLocal<StockSyncPayload> pendingStockSyncRequests = new ThreadLocal<>();

    private static final class StockSyncPayload {
        final List<StockAdjustmentRequest> syncRequests;
        final List<String> logIds;

        StockSyncPayload(List<StockAdjustmentRequest> syncRequests, List<String> logIds) {
            this.syncRequests = syncRequests;
            this.logIds = logIds;
        }
    }

    /**
     * Discard pending stock sync without sending (e.g. after transaction rollback).
     */
    public void clearPendingStockSync() {
        pendingStockSyncRequests.remove();
    }

    /**
     * Flush any stock sync queued by {@link #adjustStockBatch(Connection, List, String, String)}.
     * Call after the surrounding transaction has committed.
     */
    public void flushPendingStockSync() {
        StockSyncPayload payload = pendingStockSyncRequests.get();
        pendingStockSyncRequests.remove();
        if (payload != null && !payload.syncRequests.isEmpty()) {
            syncBatchStockAdjustmentToBackend(payload.syncRequests, payload.logIds);
        }
    }

    /**
     * Wait until background batch stock sync threads finish (used by tests before DB teardown).
     */
    public void awaitPendingStockSync(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (inFlightStockSync.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static class ProductData {
        String id;
        String name;
        String sku;
        int stockQuantity;
        Integer reorderLevel;
    }

    /**
     * Sync batch stock adjustment to backend (async)
     */
    private void syncBatchStockAdjustmentToBackend(List<StockAdjustmentRequest> requests, List<String> logIds) {
        inFlightStockSync.incrementAndGet();
        Thread thread = new Thread(() -> {
            try {
                if (ConfigManager.isTestMode()) {
                    markInventoryLogsAsSynced(logIds);
                    return;
                }

                BatchStockUpdateRequest batchRequest = new BatchStockUpdateRequest();
                batchRequest.adjustments = requests;

                apiClient.submitBatchStockUpdates(batchRequest);

                markInventoryLogsAsSynced(logIds);

                logger.info("Batch stock adjustment synced to backend: {} items", requests.size());
            } catch (ApiClient.ApiException e) {
                logger.warn("Failed to sync batch stock adjustment: {}", e.getMessage());
                queueBatchStockAdjustmentForSync(requests);
            } catch (Exception e) {
                logger.error("Error syncing batch stock adjustment", e);
            } finally {
                inFlightStockSync.decrementAndGet();
            }
        }, "StockBatchSync");
        thread.start();
    }
    
    /**
     * Mark multiple inventory logs as synced
     */
    private void markInventoryLogsAsSynced(List<String> logIds) {
        if (logIds == null || logIds.isEmpty()) return;
        try {
            StringBuilder sql = new StringBuilder("UPDATE inventory_log SET synced = TRUE WHERE id IN (");
            for (int i = 0; i < logIds.size(); i++) {
                if (i > 0) sql.append(",");
                sql.append("?");
            }
            sql.append(")");

            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < logIds.size(); i++) {
                    stmt.setString(i + 1, logIds.get(i));
                }
                stmt.executeUpdate();
                conn.commit();
            }
        } catch (SQLException e) {
            logger.error("Error marking inventory logs as synced", e);
        }
    }

    /**
     * Internal method to adjust stock with optional permission check
     */
    private void adjustStockInternal(String productId, int adjustment, String changeType, String reason, boolean checkPermission) throws SQLException {
        // Permission check - Manager+ required (skip for internal sale operations)
        if (checkPermission) {
            RoleBasedAccessService rbacService = RoleBasedAccessService.getInstance();
            rbacService.requirePermission(RoleBasedAccessService.PERMISSION_ADJUST_STOCK);
        }
        
        try (Connection conn = dbManager.getConnection()) {
            ProductManagementService.ProductInfo productInfo = productService.getProductInfoById(productId);
            if (productInfo == null) {
                throw new SQLException("Product not found");
            }

            Product product = productInfo.product;
            int previousQuantity = product.getStock();
            int newQuantity = previousQuantity + adjustment;

            if (newQuantity < 0) {
                throw new SQLException("Stock cannot be negative");
            }

            Integer reorderLevel = getReorderLevel(productId);
            String status = calculateStockStatus(newQuantity, reorderLevel);

            String updateSql = """
                    UPDATE products
                    SET stock_quantity = ?, status = ?, updated_at = ?
                    WHERE id = ?
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setInt(1, newQuantity);
                stmt.setString(2, status);
                stmt.setString(3, new java.util.Date().toString());
                stmt.setString(4, productId);
                stmt.executeUpdate();
            }

            String logId = createInventoryLog(conn, productId, product.getName(), changeType, previousQuantity,
                    newQuantity, adjustment, reason);

            conn.commit();
            logger.info("Adjusted stock for product {} by {} (type: {}, reason: {})",
                    productId, adjustment, changeType, reason);

            syncStockAdjustmentToBackend(productId, product, changeType, previousQuantity, newQuantity, adjustment, reason,
                    logId);
        }
    }

    /**
     * Reconcile stock (set to actual quantity)
     */
    public void reconcileStock(String productId, int actualQuantity, String reason) throws SQLException {
        // Permission check - Manager+ required
        RoleBasedAccessService rbacService = RoleBasedAccessService.getInstance();
        rbacService.requirePermission(RoleBasedAccessService.PERMISSION_ADJUST_STOCK);
        
        try (Connection conn = dbManager.getConnection()) {
            ProductManagementService.ProductInfo productInfo = productService.getProductInfoById(productId);
            if (productInfo == null) {
                throw new SQLException("Product not found");
            }

            if (actualQuantity < 0) {
                throw new SQLException("Stock quantity cannot be negative");
            }

            Product product = productInfo.product;
            int previousQuantity = product.getStock();
            int difference = actualQuantity - previousQuantity;

            if (difference == 0) {
                logger.info("Stock already matches actual quantity for product {}", productId);
                return;
            }

            Integer reorderLevel = getReorderLevel(productId);
            String status = calculateStockStatus(actualQuantity, reorderLevel);

            String updateSql = """
                    UPDATE products
                    SET stock_quantity = ?, status = ?, updated_at = ?
                    WHERE id = ?
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setInt(1, actualQuantity);
                stmt.setString(2, status);
                stmt.setString(3, new java.util.Date().toString());
                stmt.setString(4, productId);
                stmt.executeUpdate();
            }

            String logId = createInventoryLog(conn, productId, product.getName(), "RECONCILIATION", previousQuantity,
                    actualQuantity, difference, reason);

            conn.commit();
            logger.info("Reconciled stock for product {} from {} to {} (reason: {})",
                    productId, previousQuantity, actualQuantity, reason);

            syncStockReconciliationToBackend(productId, product, previousQuantity, actualQuantity, difference, reason,
                    logId);
        }
    }

    /**
     * Create inventory log entry
     * 
     * @return The log ID
     */
    private String createInventoryLog(Connection conn, String productId, String productName, String changeType,
            int previousQuantity, int newQuantity, int change,
            String reason) throws SQLException {
        String id = UUID.randomUUID().toString();
        String userId = userAuthService.getCurrentUserId();
        String userName = userAuthService.getCurrentUserName();

        String sql = """
                INSERT INTO inventory_log
                (id, product_id, product_name, change_type, previous_quantity, new_quantity,
                 change_amount, reason, user_id, user_name, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, productId);
            stmt.setString(3, productName);
            stmt.setString(4, changeType);
            stmt.setInt(5, previousQuantity);
            stmt.setInt(6, newQuantity);
            stmt.setInt(7, change);
            stmt.setString(8, reason);
            stmt.setString(9, userId);
            stmt.setString(10, userName);
            stmt.setTimestamp(11, Timestamp.valueOf(LocalDateTime.now()));

            stmt.executeUpdate();
        }

        return id;
    }

    /**
     * Sync stock adjustment to backend (async, non-blocking)
     */
    private void syncStockAdjustmentToBackend(String productId, Product product, String changeType,
            int previousQuantity, int newQuantity, int adjustment,
            String reason, String logId) {
        new Thread(() -> {
            try {
                ProductManagementService.ProductInfo productInfo = productService.getProductInfoById(productId);
                String sku = productInfo != null ? productInfo.sku : null;

                StockAdjustmentRequest request = new StockAdjustmentRequest();
                request.productId = productId;
                request.productName = product.getName();
                request.sku = sku;
                request.changeType = changeType;
                request.previousQuantity = previousQuantity;
                request.newQuantity = newQuantity;
                request.adjustment = adjustment;
                request.reason = reason;
                request.userId = userAuthService.getCurrentUserId();
                request.userName = userAuthService.getCurrentUserName();
                request.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);

                apiClient.submitStockAdjustment(request);

                // Mark log as synced
                markInventoryLogAsSynced(logId);

                logger.info("Stock adjustment synced to backend for product: {}", productId);
            } catch (ApiClient.ApiException e) {
                logger.warn("Failed to sync stock adjustment to backend: {}", e.getMessage());
                // Queue for offline sync
                queueStockAdjustmentForSync(productId, product, changeType, previousQuantity, newQuantity, adjustment,
                        reason, logId);
            } catch (Exception e) {
                logger.error("Error syncing stock adjustment", e);
                // Queue for offline sync
                queueStockAdjustmentForSync(productId, product, changeType, previousQuantity, newQuantity, adjustment,
                        reason, logId);
            }
        }).start();
    }

    /**
     * Sync stock reconciliation to backend (async, non-blocking)
     */
    private void syncStockReconciliationToBackend(String productId, Product product,
            int previousQuantity, int actualQuantity, int difference,
            String reason, String logId) {
        new Thread(() -> {
            try {
                ProductManagementService.ProductInfo productInfo = productService.getProductInfoById(productId);
                String sku = productInfo != null ? productInfo.sku : null;

                StockReconciliationRequest request = new StockReconciliationRequest();
                request.productId = productId;
                request.productName = product.getName();
                request.sku = sku;
                request.previousQuantity = previousQuantity;
                request.actualQuantity = actualQuantity;
                request.difference = difference;
                request.reason = reason;
                request.userId = userAuthService.getCurrentUserId();
                request.userName = userAuthService.getCurrentUserName();
                request.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);

                apiClient.submitStockReconciliation(request);

                // Mark log as synced
                markInventoryLogAsSynced(logId);

                logger.info("Stock reconciliation synced to backend for product: {}", productId);
            } catch (ApiClient.ApiException e) {
                logger.warn("Failed to sync stock reconciliation to backend: {}", e.getMessage());
                // Queue for offline sync
                queueStockReconciliationForSync(productId, product, previousQuantity, actualQuantity, difference,
                        reason, logId);
            } catch (Exception e) {
                logger.error("Error syncing stock reconciliation", e);
                // Queue for offline sync
                queueStockReconciliationForSync(productId, product, previousQuantity, actualQuantity, difference,
                        reason, logId);
            }
        }).start();
    }

    /**
     * Queue stock adjustment for offline sync
     */
    private void queueStockAdjustmentForSync(String productId, Product product, String changeType,
            int previousQuantity, int newQuantity, int adjustment,
            String reason, String logId) {
        try {
            StockAdjustmentRequest request = new StockAdjustmentRequest();
            request.productId = productId;
            request.productName = product.getName();
            request.changeType = changeType;
            request.previousQuantity = previousQuantity;
            request.newQuantity = newQuantity;
            request.adjustment = adjustment;
            request.reason = reason;
            request.userId = userAuthService.getCurrentUserId();
            request.userName = userAuthService.getCurrentUserName();
            request.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);

            offlineSyncService.queueRequest(
                    "/pos/inventory/adjustments",
                    "POST",
                    request,
                    5 // Priority for stock adjustments
            );
        } catch (Exception e) {
            logger.error("Error queueing stock adjustment for sync", e);
        }
    }

    /**
     * Queue a failed batch stock sync as a single offline request.
     */
    private void queueBatchStockAdjustmentForSync(List<StockAdjustmentRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        try {
            BatchStockUpdateRequest batchRequest = new BatchStockUpdateRequest();
            batchRequest.adjustments = requests;
            offlineSyncService.queueRequest(
                    "/pos/inventory/batch-updates",
                    "POST",
                    batchRequest,
                    5);
        } catch (Exception e) {
            logger.error("Error queueing batch stock adjustment for sync", e);
        }
    }

    /**
     * Queue stock reconciliation for offline sync
     */
    private void queueStockReconciliationForSync(String productId, Product product,
            int previousQuantity, int actualQuantity, int difference,
            String reason, String logId) {
        try {
            StockReconciliationRequest request = new StockReconciliationRequest();
            request.productId = productId;
            request.productName = product.getName();
            request.previousQuantity = previousQuantity;
            request.actualQuantity = actualQuantity;
            request.difference = difference;
            request.reason = reason;
            request.userId = userAuthService.getCurrentUserId();
            request.userName = userAuthService.getCurrentUserName();
            request.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);

            offlineSyncService.queueRequest(
                    "/pos/inventory/reconciliations",
                    "POST",
                    request,
                    5 // Priority for stock reconciliations
            );
        } catch (Exception e) {
            logger.error("Error queueing stock reconciliation for sync", e);
        }
    }

    /**
     * Mark inventory log as synced
     */
    private void markInventoryLogAsSynced(String logId) {
        try {
            String sql = "UPDATE inventory_log SET synced = TRUE WHERE id = ?";
            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, logId);
                stmt.executeUpdate();
                conn.commit();
            }
        } catch (SQLException e) {
            logger.error("Error marking inventory log as synced", e);
        }
    }

    /**
     * Get inventory history for a product
     */
    public List<InventoryLog> getInventoryHistory(String productId, int limit) throws SQLException {
        String sql = """
                SELECT * FROM inventory_log
                WHERE product_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """;

        List<InventoryLog> logs = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, productId);
            stmt.setInt(2, limit);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                logs.add(mapResultSetToInventoryLog(rs));
            }
        }

        return logs;
    }

    /**
     * Get all inventory history with filters
     */
    public List<InventoryLog> getAllInventoryHistory(String productId, String changeType,
            int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM inventory_log WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (productId != null && !productId.isEmpty()) {
            sql.append(" AND product_id = ?");
            params.add(productId);
        }

        if (changeType != null && !changeType.isEmpty()) {
            sql.append(" AND change_type = ?");
            params.add(changeType);
        }

        sql.append(" ORDER BY created_at DESC LIMIT ?");
        params.add(limit);

        List<InventoryLog> logs = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof String) {
                    stmt.setString(i + 1, (String) param);
                } else if (param instanceof Integer) {
                    stmt.setInt(i + 1, (Integer) param);
                }
            }

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                logs.add(mapResultSetToInventoryLog(rs));
            }
        }

        return logs;
    }

    /**
     * Get inventory statistics
     */
    public InventoryStatistics getStatistics() throws SQLException {
        InventoryStatistics stats = new InventoryStatistics();

        try (Connection conn = dbManager.getConnection()) {
            // Total products
            String countSql = "SELECT COUNT(*) FROM products";
            try (PreparedStatement stmt = conn.prepareStatement(countSql);
                    ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.totalProducts = rs.getInt(1);
                }
            }

            // Out of stock count
            String outOfStockSql = "SELECT COUNT(*) FROM products WHERE status = 'OUT_OF_STOCK'";
            try (PreparedStatement stmt = conn.prepareStatement(outOfStockSql);
                    ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.outOfStockCount = rs.getInt(1);
                }
            }

            // Low stock count
            String lowStockSql = "SELECT COUNT(*) FROM products WHERE status = 'LOW_STOCK'";
            try (PreparedStatement stmt = conn.prepareStatement(lowStockSql);
                    ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.lowStockCount = rs.getInt(1);
                }
            }

            // Total inventory value (using cash price)
            String valueSql = """
                    SELECT SUM(stock_quantity * price) FROM products
                    WHERE stock_quantity > 0 AND price IS NOT NULL
                    """;
            try (PreparedStatement stmt = conn.prepareStatement(valueSql);
                    ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Double value = rs.getObject(1, Double.class);
                    stats.totalValue = value != null ? value : 0.0;
                }
            }
        }

        return stats;
    }

    /**
     * Map ResultSet to InventoryLog
     */
    private InventoryLog mapResultSetToInventoryLog(ResultSet rs) throws SQLException {
        InventoryLog log = new InventoryLog();
        log.setId(rs.getString("id"));
        log.setProductId(rs.getString("product_id"));
        log.setProductName(rs.getString("product_name"));
        log.setChangeType(rs.getString("change_type"));
        log.setPreviousQuantity(rs.getInt("previous_quantity"));
        log.setNewQuantity(rs.getInt("new_quantity"));
        log.setChange(rs.getInt("change_amount"));
        log.setReason(rs.getString("reason"));
        log.setUserId(rs.getString("user_id"));
        log.setUserName(rs.getString("user_name"));

        Timestamp timestamp = rs.getTimestamp("created_at");
        if (timestamp != null) {
            log.setCreatedAt(timestamp.toLocalDateTime());
        }

        // Get synced status (default to false if column doesn't exist or is null)
        try {
            boolean synced = rs.getBoolean("synced");
            log.setSynced(synced);
        } catch (SQLException e) {
            // Column might not exist in older databases, default to false
            log.setSynced(false);
        }

        return log;
    }

    /**
     * Inventory statistics
     */
    public static class InventoryStatistics {
        public int totalProducts;
        public int outOfStockCount;
        public int lowStockCount;
        public double totalValue;
    }
}
