package com.pos.service;

import com.pos.database.DatabaseManager;
import com.pos.model.Product;
import com.pos.model.SaleItem;
import com.pos.sync.SyncManager;
import com.pos.util.InventoryCSVParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Service for managing products locally (CRUD operations)
 */
public class ProductManagementService {
    private static final Logger logger = LoggerFactory.getLogger(ProductManagementService.class);
    private static ProductManagementService instance;

    private final DatabaseManager dbManager;
    private final Map<String, Department> departmentCache = new ConcurrentHashMap<>();

    private ProductManagementService() {
        this.dbManager = DatabaseManager.getInstance();
    }

    public static synchronized ProductManagementService getInstance() {
        if (instance == null) {
            instance = new ProductManagementService();
        }
        return instance;
    }

    public static String calculateProductStatus(int stock, Integer reorderLevel) {
        if (stock < 0) {
            return "STOCK_NOT_UPDATED";
        }
        if (stock == 0) {
            return "OUT_OF_STOCK";
        }
        if (reorderLevel != null && reorderLevel > 0 && stock <= reorderLevel) {
            return "LOW_STOCK";
        }
        return "IN_STOCK";
    }

    /**
     * Get all products from local database
     */
    public List<Product> getAllProducts() throws SQLException {
        String sql = "SELECT * FROM products ORDER BY name";
        List<Product> products = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                products.add(mapResultSetToProduct(rs));
            }
        }

        return products;
    }

    /**
     * Get products with pagination
     */
    public List<Product> getProducts(int offset, int limit) throws SQLException {
        String sql = "SELECT * FROM products ORDER BY name LIMIT ? OFFSET ?";
        List<Product> products = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            stmt.setInt(2, offset);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                products.add(mapResultSetToProduct(rs));
            }
        }

        return products;
    }

    /**
     * Get product count
     */
    public int getProductCount() throws SQLException {
        String sql = "SELECT COUNT(*) FROM products";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        }

        return 0;
    }

    /**
     * Search products by name, SKU, or barcode
     */
    public List<Product> searchProducts(String searchTerm) throws SQLException {
        String sql = """
                SELECT * FROM products
                WHERE LOWER(name) LIKE ?
                   OR LOWER(sku) LIKE ?
                   OR LOWER(barcode) LIKE ?
                ORDER BY name
                LIMIT 100
                """;

        String searchPattern = "%" + (searchTerm == null ? "null" : searchTerm.toLowerCase(java.util.Locale.ROOT)) + "%";
        List<Product> products = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, searchPattern);
            stmt.setString(2, searchPattern);
            stmt.setString(3, searchPattern);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                products.add(mapResultSetToProduct(rs));
            }
        }

        return products;
    }

    /**
     * Get product by ID
     */
    public Product getProductById(String id) throws SQLException {
        ProductInfo info = getProductInfoById(id);
        return info != null ? info.product : null;
    }

    /**
     * Get product by barcode
     */
    public Product getProductByBarcode(String barcode) throws SQLException {
        String sql = "SELECT * FROM products WHERE barcode = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, barcode);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapResultSetToProduct(rs);
            }
        }

        return null;
    }

    /**
     * Check if a barcode already exists for another product
     * 
     * @param barcode          The barcode to check
     * @param excludeProductId Product ID to exclude from the check (for updates).
     *                         Can be null for new products.
     * @return true if barcode exists for another product, false otherwise
     */
    public boolean isBarcodeDuplicate(String barcode, String excludeProductId) throws SQLException {
        // Allow null or empty barcodes (they don't need to be unique)
        if (barcode == null || barcode.trim().isEmpty()) {
            return false;
        }

        String sql;

        if (excludeProductId != null) {
            // For updates: check if barcode exists for a different product
            sql = "SELECT COUNT(*) FROM products WHERE barcode = ? AND id != ?";
        } else {
            // For new products: check if barcode exists for any product
            sql = "SELECT COUNT(*) FROM products WHERE barcode = ?";
        }

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, barcode.trim());
            if (excludeProductId != null) {
                stmt.setString(2, excludeProductId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        }

        return false;
    }

    /**
     * Create a new product
     */
    public void createProduct(Product product, String sku) throws SQLException {
        createProductInternal(product, sku);
        // Trigger outbound sync to sync the new product to backend
        SyncManager.getInstance().triggerOutboundSync();
    }

    /**
     * Create a new product without triggering sync.
     * Use this for batch imports where sync should happen once at the end.
     */
    void createProductInternal(Product product, String sku) throws SQLException {
        // Validate barcode uniqueness
        if (product.getBarcode() != null && !product.getBarcode().trim().isEmpty()) {
            if (isBarcodeDuplicate(product.getBarcode(), null)) {
                throw new SQLException("A product with barcode '" + product.getBarcode()
                        + "' already exists. Barcodes must be unique.");
            }
        }

        String id = UUID.randomUUID().toString();
        String sql = """
                INSERT INTO products
                (id, name, sku, barcode, price, stock_quantity, status, department_id, updated_at, sync_version, created_locally, synced)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, product.getName());
            // Convert empty SKU to NULL to avoid unique constraint violations for products
            // without SKU
            String skuToInsert = (sku != null && !sku.trim().isEmpty()) ? sku.trim() : null;
            stmt.setString(3, skuToInsert);
            // Convert empty barcode to NULL as well
            String barcodeToInsert = (product.getBarcode() != null && !product.getBarcode().trim().isEmpty())
                    ? product.getBarcode().trim()
                    : null;
            stmt.setString(4, barcodeToInsert);
            stmt.setBigDecimal(5, product.getPrice());
            stmt.setInt(6, product.getStock());
            stmt.setString(7, calculateProductStatus(product.getStock(), null));
            stmt.setString(8, product.getDepartmentId());
            stmt.setString(9, new java.util.Date().toString());
            stmt.setLong(10, 0);
            stmt.setBoolean(11, true); // created_locally = true
            stmt.setBoolean(12, false); // synced = false (needs to be synced to backend)

            stmt.executeUpdate();
            conn.commit();
            logger.info("Created product locally: {}", product.getName());
        }
    }

    /**
     * Update an existing product
     */
    public void updateProduct(String id, Product product, String sku) throws SQLException {
        // Validate barcode uniqueness (excluding the current product)
        if (product.getBarcode() != null && !product.getBarcode().trim().isEmpty()) {
            if (isBarcodeDuplicate(product.getBarcode(), id)) {
                throw new SQLException("A product with barcode '" + product.getBarcode()
                        + "' already exists. Barcodes must be unique.");
            }
        }

        String sql = """
                UPDATE products
                SET name = ?, sku = ?, barcode = ?, price = ?,
                    stock_quantity = ?, status = ?, department_id = ?, updated_at = ?, synced = FALSE
                WHERE id = ?
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, product.getName());
            // Convert empty SKU to NULL to avoid unique constraint violations for products
            // without SKU
            String skuToUpdate = (sku != null && !sku.trim().isEmpty()) ? sku.trim() : null;
            stmt.setString(2, skuToUpdate);
            // Convert empty barcode to NULL as well
            String barcodeToUpdate = (product.getBarcode() != null && !product.getBarcode().trim().isEmpty())
                    ? product.getBarcode().trim()
                    : null;
            stmt.setString(3, barcodeToUpdate);
            stmt.setBigDecimal(4, product.getPrice());
            stmt.setInt(5, product.getStock());
            stmt.setString(6, calculateProductStatus(product.getStock(), null));
            stmt.setString(7, product.getDepartmentId());
            stmt.setString(8, new java.util.Date().toString());
            stmt.setString(9, id);

            int rowsUpdated = stmt.executeUpdate();
            conn.commit();

            if (rowsUpdated > 0) {
                logger.info("Updated product: {}", product.getName());
                // Trigger outbound sync to sync the updated product to backend
                SyncManager.getInstance().triggerOutboundSync();
            } else {
                logger.warn("No product found with id: {}", id);
            }
        }
    }

    /**
     * Delete a product
     */
    public void deleteProduct(String id) throws SQLException {
        // Permission check - Manager+ required
        RoleBasedAccessService rbacService = RoleBasedAccessService.getInstance();
        rbacService.requirePermission(RoleBasedAccessService.PERMISSION_MANAGE_PRODUCTS);

        String sql = "DELETE FROM products WHERE id = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            int rowsDeleted = stmt.executeUpdate();
            conn.commit();

            if (rowsDeleted > 0) {
                logger.info("Deleted product with id: {}", id);
            } else {
                logger.warn("No product found with id: {}", id);
            }
        }
    }

    /**
     * Adjust stock for a product
     */
    public void adjustStock(String id, int adjustment, String reason) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            // Get current stock
            Product product = getProductById(id);
            if (product == null) {
                throw new SQLException("Product not found");
            }

            int newStock = product.getStock() + adjustment;
            if (newStock < 0) {
                throw new SQLException("Stock cannot be negative");
            }

            // Update stock
            String sql = """
                    UPDATE products
                    SET stock_quantity = ?, status = ?, updated_at = ?
                    WHERE id = ?
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, newStock);
                stmt.setString(2, calculateProductStatus(newStock, null));
                stmt.setString(3, new java.util.Date().toString());
                stmt.setString(4, id);

                stmt.executeUpdate();
                conn.commit();
                logger.info("Adjusted stock for product {} by {} (reason: {})", id, adjustment, reason);
            }
        }
    }

    /**
     * Get all departments with all fields matching backend schema
     */
    public List<Department> getDepartments() throws SQLException {
        String sql = "SELECT * FROM departments ORDER BY name";
        List<Department> departments = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                departments.add(mapResultSetToDepartment(rs));
            }
        }

        return departments;
    }

    /**
     * Get department by ID (cached after first load).
     */
    public Department getDepartmentById(String id) throws SQLException {
        if (id == null) {
            return null;
        }
        Department cached = departmentCache.get(id);
        if (cached != null) {
            return cached;
        }

        String sql = "SELECT * FROM departments WHERE id = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                Department department = mapResultSetToDepartment(rs);
                departmentCache.put(id, department);
                return department;
            }
        }

        return null;
    }

    /**
     * Clear cached department data (call after department sync/update).
     */
    public void clearDepartmentCache() {
        departmentCache.clear();
    }

    /**
     * Returns true when at least one cart item belongs to an EBT-eligible department.
     */
    public boolean isCartEbtEligible(List<SaleItem> cartItems) {
        if (cartItems == null || cartItems.isEmpty()) {
            return false;
        }
        try {
            for (SaleItem item : cartItems) {
                Product product = item.getProduct();
                if (product == null || product.getDepartmentId() == null) {
                    continue;
                }
                Department dept = getDepartmentById(product.getDepartmentId());
                if (dept != null && dept.ebtEligible) {
                    return true;
                }
            }
        } catch (SQLException e) {
            logger.warn("Error checking EBT eligibility", e);
        }
        return false;
    }

    /**
     * Invalidate a single department in the cache.
     */
    public void invalidateDepartmentCache(String departmentId) {
        if (departmentId != null) {
            departmentCache.remove(departmentId);
        }
    }

    /**
     * Map ResultSet to Department with all fields
     */
    private Department mapResultSetToDepartment(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String name = rs.getString("name");
        String icon = rs.getString("icon");
        String parentId = rs.getString("parent_id");
        String departmentType = rs.getString("department_type");
        boolean taxEnabled = rs.getObject("tax_enabled") != null ? rs.getBoolean("tax_enabled") : true;
        Double taxRate = rs.getObject("tax_rate") != null ? rs.getDouble("tax_rate") : null;
        boolean hideOnRegister = rs.getObject("hide_on_register") != null ? rs.getBoolean("hide_on_register") : false;
        boolean ebtEligible = rs.getObject("ebt_eligible") != null ? rs.getBoolean("ebt_eligible") : false;
        boolean excludeFromGlobalPriceIncrease = rs.getObject("exclude_from_global_price_increase") != null
                ? rs.getBoolean("exclude_from_global_price_increase")
                : false;
        boolean noPointsEarning = rs.getObject("no_points_earning") != null ? rs.getBoolean("no_points_earning")
                : false;
        Integer ageVerification = rs.getObject("age_verification") != null ? rs.getInt("age_verification") : null;

        // Multi-pack discount fields
        boolean multipackEnabled = rs.getObject("multipack_enabled") != null ? rs.getBoolean("multipack_enabled")
                : false;
        String multipackDiscountType = rs.getString("multipack_discount_type");
        Double multipackDiscountValue = rs.getObject("multipack_discount_value") != null
                ? rs.getDouble("multipack_discount_value")
                : null;
        Integer multipackMinQuantity = rs.getObject("multipack_min_quantity") != null
                ? rs.getInt("multipack_min_quantity")
                : null;
        boolean multipackRequiresApproval = rs.getObject("multipack_requires_approval") != null
                ? rs.getBoolean("multipack_requires_approval")
                : false;

        return new Department(id, name, icon, parentId, departmentType, taxEnabled, taxRate,
                hideOnRegister, ebtEligible, excludeFromGlobalPriceIncrease, noPointsEarning, ageVerification,
                multipackEnabled, multipackDiscountType, multipackDiscountValue, multipackMinQuantity,
                multipackRequiresApproval);
    }

    /**
     * Create a department with basic fields (backward compatible)
     */
    public void createDepartment(String name, String parentId) throws SQLException {
        createDepartment(name, null, parentId, "PRODUCT", true, null, false, false, false, false, null,
                false, null, null, null, false);
    }

    /**
     * Create a department with all fields matching backend schema
     */
    public void createDepartment(String name, String icon, String parentId,
            String departmentType, boolean taxEnabled, Double taxRate,
            boolean hideOnRegister, boolean ebtEligible,
            boolean excludeFromGlobalPriceIncrease, boolean noPointsEarning,
            Integer ageVerification, Boolean multipackEnabled, String multipackDiscountType,
            Double multipackDiscountValue, Integer multipackMinQuantity, Boolean multipackRequiresApproval)
            throws SQLException {
        String id = UUID.randomUUID().toString();
        String sql = """
                INSERT INTO departments
                (id, name, icon, parent_id, department_type, tax_enabled, tax_rate,
                 hide_on_register, ebt_eligible, exclude_from_global_price_increase,
                 no_points_earning, age_verification, multipack_enabled, multipack_discount_type,
                 multipack_discount_value, multipack_min_quantity, multipack_requires_approval,
                 updated_at, created_locally, synced)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, name);
            stmt.setString(3, icon);
            stmt.setString(4, parentId);
            stmt.setString(5, departmentType != null ? departmentType : "PRODUCT");
            stmt.setBoolean(6, taxEnabled);
            stmt.setObject(7, taxRate);
            stmt.setBoolean(8, hideOnRegister);
            stmt.setBoolean(9, ebtEligible);
            stmt.setBoolean(10, excludeFromGlobalPriceIncrease);
            stmt.setBoolean(11, noPointsEarning);
            stmt.setObject(12, ageVerification);
            stmt.setBoolean(13, multipackEnabled != null ? multipackEnabled : false);
            stmt.setString(14, multipackDiscountType);
            stmt.setObject(15, multipackDiscountValue);
            stmt.setObject(16, multipackMinQuantity);
            stmt.setBoolean(17, multipackRequiresApproval != null ? multipackRequiresApproval : false);
            stmt.setString(18, new java.util.Date().toString());
            stmt.setBoolean(19, true); // created_locally = true
            stmt.setBoolean(20, false); // synced = false (needs to be synced to backend)

            stmt.executeUpdate();
            conn.commit();
            logger.info("Created department locally: {}", name);
            invalidateDepartmentCache(id);

            // Trigger outbound sync to sync the new department to backend
            SyncManager.getInstance().triggerOutboundSync();
        }
    }

    /**
     * Update an existing department with all fields
     */
    public void updateDepartment(String id, String name, String icon, String parentId,
            String departmentType, boolean taxEnabled, Double taxRate,
            boolean hideOnRegister, boolean ebtEligible,
            boolean excludeFromGlobalPriceIncrease, boolean noPointsEarning,
            Integer ageVerification, Boolean multipackEnabled, String multipackDiscountType,
            Double multipackDiscountValue, Integer multipackMinQuantity, Boolean multipackRequiresApproval)
            throws SQLException {
        String sql = """
                UPDATE departments
                SET name = ?, icon = ?, parent_id = ?, department_type = ?, tax_enabled = ?,
                    tax_rate = ?, hide_on_register = ?, ebt_eligible = ?,
                    exclude_from_global_price_increase = ?, no_points_earning = ?,
                    age_verification = ?, multipack_enabled = ?, multipack_discount_type = ?,
                    multipack_discount_value = ?, multipack_min_quantity = ?, multipack_requires_approval = ?,
                    updated_at = ?, synced = FALSE
                WHERE id = ?
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setString(2, icon);
            stmt.setString(3, parentId);
            stmt.setString(4, departmentType != null ? departmentType : "PRODUCT");
            stmt.setBoolean(5, taxEnabled);
            stmt.setObject(6, taxRate);
            stmt.setBoolean(7, hideOnRegister);
            stmt.setBoolean(8, ebtEligible);
            stmt.setBoolean(9, excludeFromGlobalPriceIncrease);
            stmt.setBoolean(10, noPointsEarning);
            stmt.setObject(11, ageVerification);
            stmt.setBoolean(12, multipackEnabled != null ? multipackEnabled : false);
            stmt.setString(13, multipackDiscountType);
            stmt.setObject(14, multipackDiscountValue);
            stmt.setObject(15, multipackMinQuantity);
            stmt.setBoolean(16, multipackRequiresApproval != null ? multipackRequiresApproval : false);
            stmt.setString(17, new java.util.Date().toString());
            stmt.setString(18, id);

            int rowsUpdated = stmt.executeUpdate();
            conn.commit();

            if (rowsUpdated > 0) {
                logger.info("Updated department: {}", name);
                invalidateDepartmentCache(id);
                // Trigger outbound sync to sync the updated department to backend
                SyncManager.getInstance().triggerOutboundSync();
            } else {
                logger.warn("No department found with id: {}", id);
            }
        }
    }

    /**
     * Delete a department
     */
    public void deleteDepartment(String id) throws SQLException {
        String sql = "DELETE FROM departments WHERE id = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            int rowsDeleted = stmt.executeUpdate();
            conn.commit();

            if (rowsDeleted > 0) {
                logger.info("Deleted department with id: {}", id);
                invalidateDepartmentCache(id);
            } else {
                logger.warn("No department found with id: {}", id);
            }
        }
    }

    /**
     * Get product info with ID and SKU
     */
    public ProductInfo getProductInfoById(String id) throws SQLException {
        String sql = "SELECT * FROM products WHERE id = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapResultSetToProductInfo(rs);
            }
        }

        return null;
    }

    /**
     * Get all product infos with ID and SKU (use paginated version for large
     * datasets)
     */
    public List<ProductInfo> getAllProductInfos() throws SQLException {
        String sql = "SELECT * FROM products ORDER BY name";
        List<ProductInfo> products = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                products.add(mapResultSetToProductInfo(rs));
            }
        }

        return products;
    }

    /**
     * Get product infos with pagination
     * 
     * @param offset Starting index (0-based)
     * @param limit  Maximum number of products to return
     * @return List of product infos
     */
    public List<ProductInfo> getProductInfos(int offset, int limit) throws SQLException {
        String sql = "SELECT * FROM products ORDER BY name LIMIT ? OFFSET ?";
        List<ProductInfo> products = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            stmt.setInt(2, offset);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                products.add(mapResultSetToProductInfo(rs));
            }
        }

        return products;
    }

    /**
     * Get product infos by department with pagination
     * 
     * @param departmentId Department ID (null for all)
     * @param offset       Starting index (0-based)
     * @param limit        Maximum number of products to return
     * @return List of product infos
     */
    public List<ProductInfo> getProductInfosByDepartment(String departmentId, int offset, int limit)
            throws SQLException {
        String sql;
        if (departmentId == null) {
            sql = "SELECT * FROM products ORDER BY name LIMIT ? OFFSET ?";
        } else {
            sql = "SELECT * FROM products WHERE department_id = ? ORDER BY name LIMIT ? OFFSET ?";
        }
        List<ProductInfo> products = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (departmentId != null) {
                stmt.setString(1, departmentId);
                stmt.setInt(2, limit);
                stmt.setInt(3, offset);
            } else {
                stmt.setInt(1, limit);
                stmt.setInt(2, offset);
            }
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                products.add(mapResultSetToProductInfo(rs));
            }
        }

        return products;
    }

    /**
     * Get total product info count
     * 
     * @param departmentId Department ID (null for all)
     * @return Total number of products
     */
    public int getProductInfoCount(String departmentId) throws SQLException {
        String sql;
        if (departmentId == null) {
            sql = "SELECT COUNT(*) FROM products";
        } else {
            sql = "SELECT COUNT(*) FROM products WHERE department_id = ?";
        }

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (departmentId != null) {
                stmt.setString(1, departmentId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    /**
     * Search product infos
     */
    public List<ProductInfo> searchProductInfos(String searchTerm) throws SQLException {
        String sql = """
                SELECT * FROM products
                WHERE LOWER(name) LIKE LOWER(?)
                   OR LOWER(sku) LIKE LOWER(?)
                   OR LOWER(barcode) LIKE LOWER(?)
                ORDER BY name
                LIMIT 100
                """;

        String searchPattern = "%" + searchTerm + "%";
        List<ProductInfo> products = new ArrayList<>();

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, searchPattern);
            stmt.setString(2, searchPattern);
            stmt.setString(3, searchPattern);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                products.add(mapResultSetToProductInfo(rs));
            }
        }

        return products;
    }

    /**
     * Get product info by exact barcode match.
     * Use this for barcode scanner input where exact matching is expected.
     * 
     * @param barcode The barcode to search for
     * @return ProductInfo if found, null otherwise
     */
    public ProductInfo getProductInfoByBarcode(String barcode) throws SQLException {
        if (barcode == null || barcode.trim().isEmpty()) {
            return null;
        }

        String sql = "SELECT * FROM products WHERE LOWER(barcode) = LOWER(?)";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, barcode.trim());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapResultSetToProductInfo(rs);
            }
        }

        return null;
    }

    /**
     * Map ResultSet to Product
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
     * Map ResultSet to ProductInfo
     */
    private ProductInfo mapResultSetToProductInfo(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String sku = rs.getString("sku");
        String barcode = rs.getString("barcode");
        String name = rs.getString("name");
        BigDecimal price = rs.getBigDecimal("price");
        int stock = rs.getInt("stock_quantity");
        String departmentId = rs.getString("department_id");
        Integer reorderLevel = rs.getObject("reorder_level", Integer.class);

        Product product = new Product(id, sku, barcode, name, price, stock, departmentId, null, null, null);
        return new ProductInfo(id, sku, product, reorderLevel != null ? reorderLevel : 0);
    }

    /**
     * Product info wrapper with ID and SKU
     */
    public static class ProductInfo {
        public final String id;
        public final String sku;
        public final Product product;
        /** reorder level read from the same SELECT, so callers need no per-row lookup. */
        public final int reorderLevel;

        public ProductInfo(String id, String sku, Product product) {
            this(id, sku, product, 0);
        }

        public ProductInfo(String id, String sku, Product product, int reorderLevel) {
            this.id = id;
            this.sku = sku;
            this.product = product;
            this.reorderLevel = reorderLevel;
        }
    }

    /**
     * Department model - matching backend schema
     */
    public static class Department {
        public final String id;
        public final String name;
        public final String icon;
        public final String parentId;

        // Department features (matching backend schema)
        public final String departmentType; // PRODUCT or SERVICE
        public final boolean taxEnabled;
        public final Double taxRate; // Tax rate percentage (e.g., 8.00 for 8%)
        public final boolean hideOnRegister;
        public final boolean ebtEligible;
        public final boolean excludeFromGlobalPriceIncrease;
        public final boolean noPointsEarning;
        public final Integer ageVerification; // Minimum age required (e.g., 21 for alcohol)

        // Multi-pack discount settings
        public final boolean multipackEnabled;
        public final String multipackDiscountType; // PERCENT or AMOUNT
        public final Double multipackDiscountValue;
        public final Integer multipackMinQuantity;
        public final boolean multipackRequiresApproval;

        /**
         * Simple constructor for basic department info
         */
        public Department(String id, String name, String parentId) {
            this(id, name, null, parentId, "PRODUCT", true, null, false, false, false, false, null,
                    false, null, null, null, false);
        }

        /**
         * Full constructor with all fields matching backend schema
         */
        public Department(String id, String name, String icon, String parentId,
                String departmentType, boolean taxEnabled, Double taxRate,
                boolean hideOnRegister, boolean ebtEligible,
                boolean excludeFromGlobalPriceIncrease, boolean noPointsEarning,
                Integer ageVerification, boolean multipackEnabled, String multipackDiscountType,
                Double multipackDiscountValue, Integer multipackMinQuantity, boolean multipackRequiresApproval) {
            this.id = id;
            this.name = name;
            this.icon = icon;
            this.parentId = parentId;
            this.departmentType = departmentType != null ? departmentType : "PRODUCT";
            this.taxEnabled = taxEnabled;
            this.taxRate = taxRate;
            this.hideOnRegister = hideOnRegister;
            this.ebtEligible = ebtEligible;
            this.excludeFromGlobalPriceIncrease = excludeFromGlobalPriceIncrease;
            this.noPointsEarning = noPointsEarning;
            this.ageVerification = ageVerification;
            this.multipackEnabled = multipackEnabled;
            this.multipackDiscountType = multipackDiscountType;
            this.multipackDiscountValue = multipackDiscountValue;
            this.multipackMinQuantity = multipackMinQuantity;
            this.multipackRequiresApproval = multipackRequiresApproval;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Import result containing success/failure counts
     */
    public static class ImportResult {
        public int successCount = 0;
        public int failedCount = 0;
        public int skippedCount = 0;
        public List<String> errors = new ArrayList<>();

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public int getTotalProcessed() {
            return successCount + failedCount + skippedCount;
        }
    }

    /**
     * Get department by name
     * 
     * @param name Department name to search for
     * @return Department if found, null otherwise
     */
    public Department getDepartmentByName(String name) throws SQLException {
        String sql = "SELECT * FROM departments WHERE LOWER(name) = LOWER(?)";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapResultSetToDepartment(rs);
            }
        }

        return null;
    }

    /**
     * Get or create department by name
     * 
     * @param name Department name
     * @return Department ID (existing or newly created)
     */
    public String getOrCreateDepartmentByName(String name) throws SQLException {
        // First try to find existing department
        Department existing = getDepartmentByName(name);
        if (existing != null) {
            return existing.id;
        }

        // Create new department with default settings
        String id = UUID.randomUUID().toString();
        String sql = """
                INSERT INTO departments
                (id, name, icon, parent_id, department_type, tax_enabled, tax_rate,
                 hide_on_register, ebt_eligible, exclude_from_global_price_increase,
                 no_points_earning, age_verification, multipack_enabled, multipack_discount_type,
                 multipack_discount_value, multipack_min_quantity, multipack_requires_approval,
                 updated_at, created_locally, synced)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, name);
            stmt.setString(3, "📦"); // Default icon
            stmt.setString(4, null); // No parent
            stmt.setString(5, "PRODUCT");
            stmt.setBoolean(6, true); // Tax enabled
            stmt.setObject(7, null); // Use store default tax rate
            stmt.setBoolean(8, false); // Don't hide on register
            stmt.setBoolean(9, false); // Not EBT eligible by default
            stmt.setBoolean(10, false); // Include in global price increase
            stmt.setBoolean(11, false); // Earn points
            stmt.setObject(12, null); // No age verification
            stmt.setBoolean(13, false); // No multipack
            stmt.setString(14, null);
            stmt.setObject(15, null);
            stmt.setObject(16, null);
            stmt.setBoolean(17, false);
            stmt.setString(18, new java.util.Date().toString());
            stmt.setBoolean(19, true);
            stmt.setBoolean(20, false);

            stmt.executeUpdate();
            conn.commit();
            logger.info("Created department '{}' for import", name);

            // Trigger outbound sync
            SyncManager.getInstance().triggerOutboundSync();
        }

        return id;
    }

    /**
     * Import multiple products from parsed data
     * Handles duplicates by skipping existing barcodes
     * 
     * @param products            List of products to import
     * @param defaultDepartmentId Department ID to assign to all products
     * @param updateExisting      If true, update existing products; if false, skip
     *                            them
     * @return ImportResult with counts and errors
     */
    public ImportResult importProducts(List<Product> products, String defaultDepartmentId, boolean updateExisting) {
        return importProducts(products, defaultDepartmentId, updateExisting, null);
    }

    /**
     * Import multiple products from parsed data with progress tracking.
     * Uses a single transaction for efficiency.
     * 
     * @param products            List of products to import
     * @param defaultDepartmentId Department ID to assign to all products
     * @param updateExisting      If true, update existing products; if false, skip
     *                            them
     * @param progressListener    Optional listener for progress updates (processed,
     *                            total)
     * @return ImportResult with counts and errors
     */
    public ImportResult importProducts(List<Product> products, String defaultDepartmentId, boolean updateExisting,
            BiConsumer<Integer, Integer> progressListener) {
        ImportResult result = new ImportResult();
        int total = products.size();
        int processed = 0;

        try (Connection conn = dbManager.getConnection()) {
            // Ensure auto-commit is off for batch transaction
            conn.setAutoCommit(false);

            for (Product product : products) {
                try {
                    // Check for duplicate barcode
                    // Note: This still does a query per item. For massive imports,
                    // we might need to pre-fetch barcodes into a Set.
                    Product existing = getProductByBarcodeInternal(conn, product.getBarcode());

                    if (existing != null) {
                        if (updateExisting) {
                            // Preserve existing fields that are not in CSV
                            if (product.getDepartmentId() == null) {
                                product.setDepartmentId(existing.getDepartmentId() != null ? existing.getDepartmentId()
                                        : defaultDepartmentId);
                            }

                            // Update existing product within this connection
                            updateProductInternal(conn, existing.getId(), product, product.getSku());
                            result.successCount++;
                        } else {
                            result.skippedCount++;
                        }
                    } else {
                        // Create new product within this connection
                        if (product.getDepartmentId() == null) {
                            product.setDepartmentId(defaultDepartmentId);
                        }
                        createProductInternalWithConn(conn, product, product.getSku());
                        result.successCount++;
                    }
                } catch (Exception e) {
                    result.failedCount++;
                    result.errors.add(String.format("'%s': %s", product.getName(), e.getMessage()));
                    logger.warn("Failed to import product '{}': {}", product.getName(), e.getMessage());
                }

                processed++;
                if (progressListener != null && processed % 10 == 0) {
                    progressListener.accept(processed, total);
                }
            }

            // Commit the entire batch
            conn.commit();
            logger.info("Imported batch of {} products ({} success, {} failed, {} skipped)",
                    total, result.successCount, result.failedCount, result.skippedCount);

        } catch (SQLException e) {
            logger.error("Batch import failed at database level", e);
            result.errors.add("Database error: " + e.getMessage());
        }

        // Final progress update
        if (progressListener != null) {
            progressListener.accept(processed, total);
        }

        // Trigger sync ONCE after all products are imported
        if (result.successCount > 0) {
            SyncManager.getInstance().triggerOutboundSync();
        }

        return result;
    }

    /**
     * Internal version of getProductByBarcode that uses an existing connection
     */
    private Product getProductByBarcodeInternal(Connection conn, String barcode) throws SQLException {
        if (barcode == null || barcode.trim().isEmpty()) {
            return null;
        }

        String sql = "SELECT * FROM products WHERE barcode = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, barcode.trim());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapResultSetToProduct(rs);
            }
        }
        return null;
    }

    private Product getProductBySkuInternal(Connection conn, String sku) throws SQLException {
        if (sku == null || sku.trim().isEmpty()) {
            return null;
        }

        String sql = "SELECT * FROM products WHERE sku = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sku.trim());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapResultSetToProduct(rs);
            }
        }
        return null;
    }

    /**
     * Internal version of updateProduct that uses an existing connection and
     * doesn't
     * commit or sync
     */
    private void updateProductInternal(Connection conn, String id, Product product, String sku) throws SQLException {
        String sql = """
                UPDATE products
                SET name = ?, sku = ?, barcode = ?, price = ?,
                    stock_quantity = ?, status = ?, department_id = ?, updated_at = ?, synced = FALSE
                WHERE id = ?
                """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, product.getName());
            // Convert empty SKU to NULL to avoid unique constraint violations
            String skuToUpdate = (sku != null && !sku.trim().isEmpty()) ? sku.trim() : null;
            stmt.setString(2, skuToUpdate);
            // Convert empty barcode to NULL as well
            String barcodeToUpdate = (product.getBarcode() != null && !product.getBarcode().trim().isEmpty())
                    ? product.getBarcode().trim()
                    : null;
            stmt.setString(3, barcodeToUpdate);
            stmt.setBigDecimal(4, product.getPrice());
            stmt.setInt(5, product.getStock());
            stmt.setString(6, calculateProductStatus(product.getStock(), null));
            stmt.setString(7, product.getDepartmentId());
            stmt.setString(8, new java.util.Date().toString());
            stmt.setString(9, id);
            stmt.executeUpdate();
        }
    }

    /**
     * Internal version of createProduct that uses an existing connection and
     * doesn't
     * commit or sync
     */
    private void createProductInternalWithConn(Connection conn, Product product, String sku) throws SQLException {
        String id = UUID.randomUUID().toString();
        String sql = """
                INSERT INTO products
                (id, name, sku, barcode, price, stock_quantity, status, department_id, updated_at, sync_version, created_locally, synced)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, product.getName());
            // Convert empty SKU to NULL to avoid unique constraint violations
            String skuToInsert = (sku != null && !sku.trim().isEmpty()) ? sku.trim() : null;
            stmt.setString(3, skuToInsert);
            // Convert empty barcode to NULL as well
            String barcodeToInsert = (product.getBarcode() != null && !product.getBarcode().trim().isEmpty())
                    ? product.getBarcode().trim()
                    : null;
            stmt.setString(4, barcodeToInsert);
            stmt.setBigDecimal(5, product.getPrice());
            stmt.setInt(6, product.getStock());
            stmt.setString(7, calculateProductStatus(product.getStock(), null));
            stmt.setString(8, product.getDepartmentId());
            stmt.setString(9, new java.util.Date().toString());
            stmt.setLong(10, 0);
            stmt.setBoolean(11, true);
            stmt.setBoolean(12, false);
            stmt.executeUpdate();
        }
    }

    /**
     * Get or create department by name with specific settings.
     * 
     * @param conn            Database connection
     * @param name            Department name
     * @param taxEnabled      Whether tax is enabled
     * @param taxRate         Tax rate
     * @param ebtEligible     Whether EBT is eligible
     * @param ageVerification Minimum age for verification
     * @return Department ID
     */
    public String getOrCreateDepartmentWithSettings(Connection conn, String name, Boolean taxEnabled,
            BigDecimal taxRate, Boolean ebtEligible, Integer ageVerification) throws SQLException {
        // First try to find existing department
        Department existing = getDepartmentByNameInternal(conn, name);
        if (existing != null) {
            // Update existing department settings if they are provided in CSV
            // This ensures "store transfer" imports can update target store settings if
            // needed
            updateDepartmentSettingsInternal(conn, existing.id, taxEnabled, taxRate, ebtEligible, ageVerification);
            return existing.id;
        }

        // Create new department
        String id = UUID.randomUUID().toString();
        String sql = """
                INSERT INTO departments
                (id, name, icon, parent_id, department_type, tax_enabled, tax_rate,
                 hide_on_register, ebt_eligible, exclude_from_global_price_increase,
                 no_points_earning, age_verification, multipack_enabled, multipack_discount_type,
                 multipack_discount_value, multipack_min_quantity, multipack_requires_approval,
                 updated_at, created_locally, synced)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, name);
            stmt.setString(3, "📦"); // Default icon
            stmt.setObject(4, null); // No parent
            stmt.setString(5, "PRODUCT");
            stmt.setBoolean(6, taxEnabled != null ? taxEnabled : true);
            stmt.setObject(7, taxRate);
            stmt.setBoolean(8, false);
            stmt.setBoolean(9, ebtEligible != null ? ebtEligible : false);
            stmt.setBoolean(10, false);
            stmt.setBoolean(11, false);
            stmt.setObject(12, ageVerification);
            stmt.setBoolean(13, false);
            stmt.setObject(14, null);
            stmt.setObject(15, null);
            stmt.setObject(16, null);
            stmt.setBoolean(17, false);
            stmt.setString(18, new java.util.Date().toString());
            stmt.setBoolean(19, true);
            stmt.setBoolean(20, false);

            stmt.executeUpdate();
            logger.info("Created department '{}' with custom settings during import", name);
        }

        return id;
    }

    private Department getDepartmentByNameInternal(Connection conn, String name) throws SQLException {
        String sql = "SELECT * FROM departments WHERE LOWER(name) = LOWER(?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapResultSetToDepartment(rs);
            }
        }
        return null;
    }

    private void updateDepartmentSettingsInternal(Connection conn, String id, Boolean taxEnabled,
            BigDecimal taxRate, Boolean ebtEligible, Integer ageVerification) throws SQLException {
        if (taxEnabled == null && taxRate == null && ebtEligible == null && ageVerification == null) {
            return;
        }

        StringBuilder sql = new StringBuilder("UPDATE departments SET updated_at = ?, synced = FALSE");
        List<Object> params = new ArrayList<>();
        params.add(new java.util.Date().toString());

        if (taxEnabled != null) {
            sql.append(", tax_enabled = ?");
            params.add(taxEnabled);
        }
        if (taxRate != null) {
            sql.append(", tax_rate = ?");
            params.add(taxRate);
        }
        if (ebtEligible != null) {
            sql.append(", ebt_eligible = ?");
            params.add(ebtEligible);
        }
        if (ageVerification != null) {
            sql.append(", age_verification = ?");
            params.add(ageVerification);
        }

        sql.append(" WHERE id = ?");
        params.add(id);

        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            stmt.executeUpdate();
        }
    }

    /**
     * Import multiple products from parsed data with progress tracking.
     * Uses a single transaction for efficiency.
     */
    public ImportResult importParsedProducts(List<InventoryCSVParser.ParsedProduct> parsedProducts,
            String defaultDepartmentId,
            boolean updateExisting, BiConsumer<Integer, Integer> progressListener) {
        ImportResult result = new ImportResult();
        int total = parsedProducts.size();
        int processed = 0;

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            // Cache for departments created/found during this import to avoid repeated
            // queries
            Map<String, String> departmentCache = new HashMap<>();

            for (InventoryCSVParser.ParsedProduct parsed : parsedProducts) {
                try {
                    // 1. Resolve Department
                    String deptId = defaultDepartmentId;
                    if (parsed.departmentName != null && !parsed.departmentName.trim().isEmpty()) {
                        String cacheKey = parsed.departmentName.toLowerCase();
                        if (departmentCache.containsKey(cacheKey)) {
                            deptId = departmentCache.get(cacheKey);
                        } else {
                            deptId = getOrCreateDepartmentWithSettings(conn, parsed.departmentName, parsed.taxEnabled,
                                    parsed.taxRate, parsed.ebtEligible, parsed.ageVerification);
                            departmentCache.put(cacheKey, deptId);
                        }
                    }

                    // 2. Convert to Product model
                    Product product = new Product(parsed.barcode, parsed.name, parsed.price, parsed.stock);
                    product.setSku(parsed.sku);
                    product.setDepartmentId(deptId);

                    // 3. Perform Import/Update
                    // Dual lookup: first by barcode, then by SKU if needed
                    Product existing = getProductByBarcodeInternal(conn, product.getBarcode());

                    if (existing == null && product.getSku() != null && !product.getSku().trim().isEmpty()) {
                        existing = getProductBySkuInternal(conn, product.getSku());
                    }

                    if (existing != null) {
                        if (updateExisting) {
                            updateProductInternal(conn, existing.getId(), product, product.getSku());
                            result.successCount++;
                        } else {
                            result.skippedCount++;
                        }
                    } else {
                        createProductInternalWithConn(conn, product, product.getSku());
                        result.successCount++;
                    }
                } catch (Exception e) {
                    result.failedCount++;
                    result.errors.add(String.format("'%s': %s", parsed.name, e.getMessage()));
                    logger.warn("Failed to import product '{}': {}", parsed.name, e.getMessage());
                }

                processed++;
                if (progressListener != null && processed % 10 == 0) {
                    progressListener.accept(processed, total);
                }
            }

            conn.commit();
            logger.info("Imported batch of {} products ({} success, {} failed, {} skipped)",
                    total, result.successCount, result.failedCount, result.skippedCount);

        } catch (SQLException e) {
            logger.error("Batch import failed at database level", e);
            result.errors.add("Database error: " + e.getMessage());
        }

        if (progressListener != null) {
            progressListener.accept(processed, total);
        }

        if (result.successCount > 0) {
            SyncManager.getInstance().triggerOutboundSync();
        }

        return result;
    }
}
