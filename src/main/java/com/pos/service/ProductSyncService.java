package com.pos.service;

import com.pos.database.DatabaseManager;
import com.pos.model.Product;
import com.pos.sync.inbound.ProductInboundSync;
import com.pos.sync.SyncManager;
import com.pos.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;

/**
 * Service for product operations and sync coordination.
 * 
 * <h2>Refactored Architecture</h2>
 * This service now delegates sync operations to the centralized SyncManager
 * and ProductInboundSync handler. It maintains backward compatibility for
 * existing code that uses this service directly.
 * 
 * <h2>Responsibilities</h2>
 * <ul>
 * <li>Product lookup (local database first, then backend)</li>
 * <li>Backward-compatible sync methods (delegate to SyncManager)</li>
 * <li>Sync status reporting</li>
 * </ul>
 * 
 * <h2>Sync Flow</h2>
 * 
 * <pre>
 * ProductSyncService (facade)
 *         │
 *         ▼
 * SyncManager (orchestrator)
 *         │
 *         ▼
 * ProductInboundSync (handler)
 *         │
 *         ▼
 * Local H2 Database
 * </pre>
 */
public class ProductSyncService {
    private static final Logger logger = LoggerFactory.getLogger(ProductSyncService.class);
    private static ProductSyncService instance;

    private final DatabaseManager dbManager;
    private volatile boolean isSyncing = false;
    private volatile String lastSyncError = null;

    private ProductSyncService() {
        this.dbManager = DatabaseManager.getInstance();
    }

    public static synchronized ProductSyncService getInstance() {
        if (instance == null) {
            instance = new ProductSyncService();
        }
        return instance;
    }

    /**
     * Perform full product sync from backend.
     * Delegates to SyncManager for coordinated sync.
     * 
     * @return true if sync was successful
     */
    public boolean performFullSync() {
        if (isSyncing) {
            logger.warn("Sync already in progress, skipping");
            return false;
        }

        isSyncing = true;
        lastSyncError = null;

        try {
            logger.info("Starting full product sync via SyncManager");

            SyncResult result = SyncManager.getInstance().performFullInboundSync();

            if (result.isSuccess()) {
                logger.info("Full sync completed successfully: {} items synced", result.getSynced());
                return true;
            } else {
                lastSyncError = result.getError();
                logger.warn("Full sync completed with errors: {}", lastSyncError);
                return result.getSynced() > 0; // Partial success
            }

        } catch (Exception e) {
            lastSyncError = e.getMessage();
            logger.error("Full sync failed", e);
            return false;
        } finally {
            isSyncing = false;
        }
    }

    /**
     * Perform incremental sync (changes since last sync).
     * Delegates to SyncManager for coordinated sync.
     * 
     * @return true if sync was successful
     */
    public boolean performIncrementalSync() {
        if (isSyncing) {
            logger.warn("Sync already in progress, skipping");
            return false;
        }

        isSyncing = true;
        lastSyncError = null;

        try {
            logger.debug("Starting incremental product sync via SyncManager");

            SyncResult result = SyncManager.getInstance().performInboundSync();

            if (result.isSuccess()) {
                if (result.getSynced() > 0) {
                    logger.info("Incremental sync completed: {} items synced", result.getSynced());
                }
                return true;
            } else {
                lastSyncError = result.getError();
                logger.warn("Incremental sync completed with errors: {}", lastSyncError);
                return result.getSynced() > 0;
            }

        } catch (Exception e) {
            lastSyncError = e.getMessage();
            logger.error("Incremental sync failed", e);
            return false;
        } finally {
            isSyncing = false;
        }
    }

    /**
     * Get product by barcode from local database.
     * This is the primary method for offline-first product lookup.
     * 
     * @param barcode Product barcode
     * @return Product if found, null otherwise
     */
    public Product getLocalProductByBarcode(String barcode) {
        return getLocalProductByBarcode(barcode, null);
    }

    /**
     * Get product by barcode from local database with optional connection reuse.
     *
     * @param barcode Product barcode
     * @param conn Optional existing connection to reuse
     * @return Product if found, null otherwise
     */
    public Product getLocalProductByBarcode(String barcode, Connection conn) {
        Connection connectionToUse = conn;
        boolean shouldClose = false;

        try {
            if (connectionToUse == null) {
                connectionToUse = dbManager.getConnection();
                shouldClose = true;
            }

            String sql = "SELECT * FROM products WHERE LOWER(barcode) = LOWER(?)";

            try (PreparedStatement stmt = connectionToUse.prepareStatement(sql)) {
                stmt.setString(1, barcode == null ? null : barcode.trim());
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    return mapResultSetToProduct(rs);
                }
            }
        } catch (SQLException e) {
            logger.error("Error fetching product from local database", e);
        } finally {
            if (shouldClose && connectionToUse != null) {
                try {
                    connectionToUse.close();
                } catch (SQLException e) {
                    logger.error("Error closing database connection", e);
                }
            }
        }

        return null;
    }

    /**
     * Get product by SKU from local database.
     * 
     * @param sku Product SKU
     * @return Product if found, null otherwise
     */
    public Product getLocalProductBySku(String sku) {
        return getLocalProductBySku(sku, null);
    }

    /**
     * Get product by SKU from local database with optional connection reuse.
     *
     * @param sku Product SKU
     * @param conn Optional existing connection to reuse
     * @return Product if found, null otherwise
     */
    public Product getLocalProductBySku(String sku, Connection conn) {
        Connection connectionToUse = conn;
        boolean shouldClose = false;

        try {
            if (connectionToUse == null) {
                connectionToUse = dbManager.getConnection();
                shouldClose = true;
            }

            String sql = "SELECT * FROM products WHERE LOWER(sku) = LOWER(?)";

            try (PreparedStatement stmt = connectionToUse.prepareStatement(sql)) {
                stmt.setString(1, sku == null ? null : sku.trim());
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    return mapResultSetToProduct(rs);
                }
            }
        } catch (SQLException e) {
            logger.error("Error fetching product by SKU from local database", e);
        } finally {
            if (shouldClose && connectionToUse != null) {
                try {
                    connectionToUse.close();
                } catch (SQLException e) {
                    logger.error("Error closing database connection", e);
                }
            }
        }

        return null;
    }

    /**
     * Get product by barcode - tries local first, then backend.
     * Note: Backend fetch is deprecated. Use getLocalProductByBarcode() for
     * offline-first.
     * 
     * @param barcode Product barcode
     * @return Product if found, null otherwise
     * @deprecated Use getLocalProductByBarcode() for offline-first architecture
     */
    @Deprecated
    public Product getProductByBarcode(String barcode) {
        // Try local first
        Product product = getLocalProductByBarcode(barcode);
        if (product != null) {
            return product;
        }

        // Local not found - in offline-first architecture, we don't fetch from backend
        // The product will be available after next sync
        logger.debug("Product not found locally: {}. Will be available after sync.", barcode);
        return null;
    }

    /**
     * Get product by SKU - tries local first, then backend.
     * Note: Backend fetch is deprecated. Use getLocalProductBySku() for
     * offline-first.
     * 
     * @param sku Product SKU
     * @return Product if found, null otherwise
     * @deprecated Use getLocalProductBySku() for offline-first architecture
     */
    @Deprecated
    public Product getProductBySku(String sku) {
        // Try local first
        Product product = getLocalProductBySku(sku);
        if (product != null) {
            return product;
        }

        // Local not found - in offline-first architecture, we don't fetch from backend
        logger.debug("Product not found locally: {}. Will be available after sync.", sku);
        return null;
    }

    /**
     * Map ResultSet to Product model.
     */
    private Product mapResultSetToProduct(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String sku = rs.getString("sku");
        String barcode = rs.getString("barcode");
        String name = rs.getString("name");
        BigDecimal price = rs.getBigDecimal("price");
        int stock = rs.getInt("stock_quantity");
        String departmentId = rs.getString("department_id");

        return new Product(id, sku, barcode, name, price, stock, departmentId, null, null, null);
    }

    /**
     * Check if sync is currently in progress.
     */
    public boolean isSyncing() {
        return isSyncing || SyncManager.getInstance().getStatus().isInboundSyncing();
    }

    /**
     * Get last sync error message.
     */
    public String getLastSyncError() {
        return lastSyncError;
    }

    /**
     * Get local product count (for diagnostics).
     */
    public int getLocalProductCount() {
        return ProductInboundSync.getInstance().getLocalProductCount();
    }

    /**
     * Shutdown - no longer needed as SyncManager handles scheduling.
     * Kept for backward compatibility.
     */
    public void shutdown() {
        // No-op - SyncManager handles shutdown
        logger.debug("ProductSyncService.shutdown() called - delegated to SyncManager");
    }
}
