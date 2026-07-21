package com.pos.sync.inbound;

import com.pos.api.ApiClient;
import com.pos.api.dto.FullSyncResponse;
import com.pos.api.dto.ProductSyncData;
import com.pos.database.DatabaseManager;
import com.pos.service.ProductManagementService;
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
 * Inbound sync handler for products and departments.
 * Pulls product data from backend and stores in local database.
 * 
 * <h2>Sync Flow</h2>
 * 
 * <pre>
 * Backend API ──────────────────────────────────────────────────────────────
 *     │
 *     │ GET /pos/products/sync?full=true (full sync)
 *     │ GET /pos/products/sync?since={timestamp} (incremental sync)
 *     │
 *     ▼
 * ProductInboundSync ───────────────────────────────────────────────────────
 *     │
 *     │ Parse response
 *     │ Validate data
 *     │ Batch insert/update
 *     │
 *     ▼
 * Local H2 Database ────────────────────────────────────────────────────────
 *     • products table
 *     • departments table
 * </pre>
 * 
 * <h2>Conflict Resolution</h2>
 * Backend always wins. Local product data is completely replaced by backend
 * data.
 * This is a one-way sync (backend → local only).
 */
public class ProductInboundSync implements SyncManager.InboundSyncHandler {
    private static final Logger logger = LoggerFactory.getLogger(ProductInboundSync.class);

    private static ProductInboundSync instance;

    private final ApiClient apiClient;
    private final DatabaseManager dbManager;

    private ProductInboundSync() {
        this.apiClient = ApiClient.getInstance();
        this.dbManager = DatabaseManager.getInstance();
    }

    public static synchronized ProductInboundSync getInstance() {
        if (instance == null) {
            instance = new ProductInboundSync();
        }
        return instance;
    }

    @Override
    public String getName() {
        return "ProductSync";
    }

    @Override
    public SyncResult sync(String lastSyncTime) throws Exception {
        // Check if we have any products locally - if not, force full sync
        int localProductCount = getLocalProductCount();
        boolean shouldForceFullSync = (localProductCount == 0);

        boolean isFullSync = (lastSyncTime == null || lastSyncTime.isEmpty() || shouldForceFullSync);

        if (shouldForceFullSync && lastSyncTime != null && !lastSyncTime.isEmpty()) {
            logger.info("No products found locally (count: {}), forcing full sync instead of incremental",
                    localProductCount);
        }

        logger.info("Starting {} product sync (local products: {})", isFullSync ? "full" : "incremental",
                localProductCount);

        try {
            // Build API URL
            String endpoint = isFullSync
                    ? "/pos/products/sync?full=true"
                    : "/pos/products/sync?since=" + lastSyncTime;

            // Call backend API
            logger.debug("Calling backend API: {}", endpoint);
            ApiClient.ApiResponse<FullSyncResponse> response = apiClient.get(
                    endpoint,
                    FullSyncResponse.class);

            FullSyncResponse syncData = response.getData();

            if (syncData == null) {
                logger.warn("Empty sync response from backend");
                return SyncResult.empty(SyncDirection.INBOUND);
            }

            // Log response details for debugging
            logger.debug(
                    "Sync response received - totalProducts: {}, totalDepartments: {}, products list size: {}, departments list size: {}",
                    syncData.totalProducts != null ? syncData.totalProducts : "null",
                    syncData.totalDepartments != null ? syncData.totalDepartments : "null",
                    syncData.products != null ? syncData.products.size() : "null",
                    syncData.departments != null ? syncData.departments.size() : "null");

            int productsStored = 0;
            int departmentsStored = 0;

            // Store products
            if (syncData.products != null && !syncData.products.isEmpty()) {
                logger.debug("Storing {} products from backend", syncData.products.size());
                productsStored = storeProducts(syncData.products);
                logger.info("Synced {} products", productsStored);
            } else {
                logger.warn("No products in sync response. products list is {}",
                        syncData.products == null ? "null" : "empty");
            }

            // Store departments
            if (syncData.departments != null && !syncData.departments.isEmpty()) {
                logger.debug("Storing {} departments from backend", syncData.departments.size());
                departmentsStored = storeDepartments(syncData.departments);
                ProductManagementService.getInstance().clearDepartmentCache();
                logger.info("Synced {} departments", departmentsStored);
            } else {
                logger.warn("No departments in sync response. departments list is {}",
                        syncData.departments == null ? "null" : "empty");
            }

            int totalSynced = productsStored + departmentsStored;

            logger.info("{} sync completed: {} products, {} departments",
                    isFullSync ? "Full" : "Incremental", productsStored, departmentsStored);

            // Trigger outbound sync after successful product sync
            // This ensures pending sales can sync once products are available
            if (totalSynced > 0) {
                logger.info("Product inbound sync completed successfully, triggering outbound sync");
                SyncManager.getInstance().triggerOutboundSync();
            }

            return SyncResult.success(SyncDirection.INBOUND, totalSynced);

        } catch (ApiClient.ApiException e) {
            logger.error("Product sync failed: {}", e.getMessage());
            return SyncResult.failure(SyncDirection.INBOUND, e.getMessage());
        }
    }

    /**
     * Store products in local database using batch insert.
     */
    private int storeProducts(List<ProductSyncData> products) throws SQLException {
        if (products == null || products.isEmpty()) {
            return 0;
        }

        try (Connection conn = dbManager.getConnection()) {
            // H2 uses MERGE INTO for upsert
            // Products from backend are synced, so set synced = TRUE and created_locally =
            // FALSE
            // Include vendor fields for payout tracking
            String sql = "MERGE INTO products " +
                    "(id, name, sku, barcode, price, stock_quantity, status, department_id, " +
                    "vendor_id, vendor_name, cost, updated_at, sync_version, synced, created_locally) " +
                    "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            int successCount = 0;
            List<String> failedIds = new ArrayList<>();

            try {
                conn.setAutoCommit(false);

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    for (ProductSyncData product : products) {
                        try {
                            // Validate required fields
                            if (product.id == null || product.name == null || product.price == null) {
                                logger.warn("Skipping product with missing required fields: id={}, name={}",
                                        product.id, product.name);
                                continue;
                            }

                            stmt.setString(1, product.id);
                            stmt.setString(2, product.name);
                            stmt.setString(3, normalizeNullableString(product.sku));
                            stmt.setString(4, normalizeNullableString(product.barcode));
                            stmt.setBigDecimal(5, BigDecimal.valueOf(product.price));
                            stmt.setInt(6, product.stockQuantity != null ? product.stockQuantity : 0);
                            stmt.setString(7, product.status != null ? product.status : "IN_STOCK");
                            stmt.setString(8, product.departmentId);
                            // Vendor fields for payout tracking
                            stmt.setString(9, product.supplierId);
                            stmt.setString(10, product.supplierName);
                            stmt.setObject(11, product.cost != null ? BigDecimal.valueOf(product.cost) : null);
                            stmt.setString(12, product.updatedAt != null ? product.updatedAt : "");
                            stmt.setLong(13, product.syncVersion != null ? product.syncVersion : 0);
                            stmt.setBoolean(14, true); // synced = TRUE (products from backend are synced)
                            stmt.setBoolean(15, false); // created_locally = FALSE (products from backend)
                            stmt.addBatch();

                        } catch (SQLException e) {
                            failedIds.add(product.id);
                            logger.warn("Error preparing product {} for batch: {}", product.id, e.getMessage());
                        }
                    }

                    // Execute batch
                    int[] results = stmt.executeBatch();
                    conn.commit();

                    // Count successful inserts
                    for (int result : results) {
                        if (result >= 0 || result == Statement.SUCCESS_NO_INFO) {
                            successCount++;
                        }
                    }
                }

            } catch (BatchUpdateException e) {
                conn.rollback();
                logger.error("Batch product insert failed, attempting individual inserts", e);

                // Fallback to individual inserts
                successCount = storeProductsIndividually(products);

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }

            if (!failedIds.isEmpty()) {
                logger.warn("Failed to store {} products: {}", failedIds.size(), failedIds);
            }

            return successCount;
        }
    }

    /**
     * Fallback: Store products individually when batch fails.
     */
    private int storeProductsIndividually(List<ProductSyncData> products) {
        int successCount = 0;
        Connection conn = null;

        try {
            conn = dbManager.getConnection();
            conn.setAutoCommit(false);

            // Include vendor fields for payout tracking
            String sql = "MERGE INTO products " +
                    "(id, name, sku, barcode, price, stock_quantity, status, department_id, " +
                    "vendor_id, vendor_name, cost, updated_at, sync_version, synced, created_locally) " +
                    "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (ProductSyncData product : products) {
                    try {
                        if (product.id == null || product.name == null || product.price == null) {
                            continue;
                        }

                        stmt.setString(1, product.id);
                        stmt.setString(2, product.name);
                        stmt.setString(3, normalizeNullableString(product.sku));
                        stmt.setString(4, normalizeNullableString(product.barcode));
                        stmt.setBigDecimal(5, BigDecimal.valueOf(product.price));
                        stmt.setInt(6, product.stockQuantity != null ? product.stockQuantity : 0);
                        stmt.setString(7, product.status != null ? product.status : "IN_STOCK");
                        stmt.setString(8, product.departmentId);
                        // Vendor fields for payout tracking
                        stmt.setString(9, product.supplierId);
                        stmt.setString(10, product.supplierName);
                        stmt.setObject(11, product.cost != null ? BigDecimal.valueOf(product.cost) : null);
                        stmt.setString(12, product.updatedAt != null ? product.updatedAt : "");
                        stmt.setLong(13, product.syncVersion != null ? product.syncVersion : 0);
                        stmt.setBoolean(14, true); // synced = TRUE (products from backend are synced)
                        stmt.setBoolean(15, false); // created_locally = FALSE (products from backend)

                        stmt.executeUpdate();
                        successCount++;

                    } catch (SQLException e) {
                        logger.warn("Failed to store product {}: {}", product.id, e.getMessage());
                    }
                }

                conn.commit();
            }

        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    logger.error("Error rolling back", rollbackEx);
                }
            }
            logger.error("Error in individual product storage", e);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException e) {
                    logger.error("Error resetting auto-commit", e);
                }
                // Return the connection to the pool — previously leaked on every call,
                // draining the pool during sync and causing UI freezes.
                try {
                    conn.close();
                } catch (SQLException e) {
                    logger.error("Error closing connection", e);
                }
            }
        }

        return successCount;
    }

    /**
     * Store departments in local database with all fields matching backend schema.
     */
    private int storeDepartments(List<FullSyncResponse.DepartmentSyncData> departments) throws SQLException {
        if (departments == null || departments.isEmpty()) {
            return 0;
        }

        try (Connection conn = dbManager.getConnection()) {
            String sql = "MERGE INTO departments " +
                    "(id, name, icon, parent_id, department_type, tax_enabled, tax_rate, " +
                    "hide_on_register, ebt_eligible, exclude_from_global_price_increase, " +
                    "no_points_earning, age_verification, multipack_enabled, multipack_discount_type, " +
                    "multipack_discount_value, multipack_min_quantity, multipack_requires_approval, updated_at, synced, created_locally) "
                    +
                    "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            int successCount = 0;

            try {
                conn.setAutoCommit(false);

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    for (FullSyncResponse.DepartmentSyncData dept : departments) {
                        try {
                            if (dept.id == null || dept.name == null) {
                                logger.warn("Skipping department with missing required fields: id={}, name={}",
                                        dept.id, dept.name);
                                continue;
                            }

                            stmt.setString(1, dept.id);
                            stmt.setString(2, dept.name);
                            stmt.setString(3, dept.icon);
                            stmt.setString(4, dept.parentId);
                            stmt.setString(5, dept.departmentType != null ? dept.departmentType : "PRODUCT");
                            stmt.setBoolean(6, dept.taxEnabled != null ? dept.taxEnabled : true);
                            stmt.setObject(7, dept.taxRate);
                            stmt.setBoolean(8, dept.hideOnRegister != null ? dept.hideOnRegister : false);
                            stmt.setBoolean(9, dept.ebtEligible != null ? dept.ebtEligible : false);
                            stmt.setBoolean(10,
                                    dept.excludeFromGlobalPriceIncrease != null ? dept.excludeFromGlobalPriceIncrease
                                            : false);
                            stmt.setBoolean(11, dept.noPointsEarning != null ? dept.noPointsEarning : false);
                            stmt.setObject(12, dept.ageVerification);
                            stmt.setBoolean(13, dept.multipackEnabled != null ? dept.multipackEnabled : false);
                            stmt.setString(14, dept.multipackDiscountType);
                            stmt.setObject(15, dept.multipackDiscountValue);
                            stmt.setObject(16, dept.multipackMinQuantity);
                            stmt.setBoolean(17,
                                    dept.multipackRequiresApproval != null ? dept.multipackRequiresApproval : false);
                            stmt.setString(18, dept.updatedAt != null ? dept.updatedAt : "");
                            stmt.setBoolean(19, true); // synced = TRUE
                            stmt.setBoolean(20, false); // created_locally = FALSE
                            stmt.addBatch();
                            successCount++;

                        } catch (SQLException e) {
                            logger.warn("Error preparing department {}: {}", dept.id, e.getMessage());
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
     * Convert blank strings to NULL so multiple products without SKU/barcode
     * do not violate UNIQUE constraints.
     */
    private static String normalizeNullableString(String value) {
        return (value != null && !value.trim().isEmpty()) ? value.trim() : null;
    }

    /**
     * Get local product count (for diagnostics).
     */
    public int getLocalProductCount() {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT COUNT(*) FROM products";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting product count", e);
        }
        return 0;
    }
}
