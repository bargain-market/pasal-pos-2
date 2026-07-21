package com.pos.service;

import com.pos.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for handling returns and exchanges
 */
public class ReturnService {

    private static final Logger logger = LoggerFactory.getLogger(ReturnService.class);
    private static ReturnService instance;
    private final DatabaseManager dbManager;
    private final InventoryService inventoryService;

    // Return policy configuration
    private static final int RETURN_WINDOW_DAYS = 30; // Days allowed for returns

    private ReturnService() {
        this.dbManager = DatabaseManager.getInstance();
        this.inventoryService = InventoryService.getInstance();
    }

    public static synchronized ReturnService getInstance() {
        if (instance == null) {
            instance = new ReturnService();
        }
        return instance;
    }

    /**
     * Search for sales by sale ID or date range
     */
    public List<SaleInfo> searchSales(String query) {
        List<SaleInfo> sales = new ArrayList<>();
        
        if (query == null || query.trim().isEmpty()) {
            return sales;
        }
        
        try (Connection conn = dbManager.getConnection()) {

            String sql = """
                SELECT s.sale_id, s.total, s.payment_method, s.cashier_name, s.timestamp, s.voided,
                       COUNT(si.id) as item_count
                FROM sales s
                LEFT JOIN sale_items si ON s.sale_id = si.sale_id
                WHERE s.sale_id LIKE ?
                   OR s.timestamp LIKE ?
                GROUP BY s.sale_id, s.total, s.payment_method, s.cashier_name, s.timestamp, s.voided
                ORDER BY s.created_at DESC
                LIMIT 20
            """;

            String searchPattern = "%" + query.trim() + "%";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, searchPattern);
                stmt.setString(2, searchPattern);

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    SaleInfo sale = new SaleInfo();
                    sale.saleId = rs.getString("sale_id");
                    sale.total = rs.getBigDecimal("total");
                    sale.paymentMethod = rs.getString("payment_method");
                    sale.cashierName = rs.getString("cashier_name");
                    sale.timestamp = rs.getString("timestamp");
                    sale.itemCount = rs.getInt("item_count");
                    sale.voided = rs.getBoolean("voided");
                    sales.add(sale);
                }
            }

            logger.debug("Found {} sales matching '{}'", sales.size(), query);
        } catch (SQLException e) {
            logger.error("Error searching sales", e);
        }
        
        return sales;
    }

    /**
     * Get items from a specific sale
     */
    public List<SaleItemInfo> getSaleItems(String saleId) {
        List<SaleItemInfo> items = new ArrayList<>();
        
        try (Connection conn = dbManager.getConnection()) {

            String sql = """
                SELECT si.product_id, si.sku, si.name, si.price, si.quantity, si.subtotal, si.discount
                FROM sale_items si
                WHERE si.sale_id = ?
            """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, saleId);

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    SaleItemInfo item = new SaleItemInfo();
                    item.productId = rs.getString("product_id");
                    item.sku = rs.getString("sku");
                    item.name = rs.getString("name");
                    item.price = rs.getBigDecimal("price");
                    item.quantity = rs.getInt("quantity");
                    item.subtotal = rs.getBigDecimal("subtotal");
                    item.discount = rs.getBigDecimal("discount");
                    item.returnedQuantity = getReturnedQuantity(saleId, item.productId);
                    item.returnableQuantity = item.quantity - item.returnedQuantity;
                    items.add(item);
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting sale items", e);
        }
        
        return items;
    }

    /**
     * Get quantity already returned for a product in a sale
     */
    private int getReturnedQuantity(String saleId, String productId) {
        try (Connection conn = dbManager.getConnection()) {

            String sql = """
                SELECT COALESCE(SUM(ri.refund_quantity), 0) as returned
                FROM refund_items ri
                JOIN refunds r ON ri.refund_id = r.refund_id
                WHERE r.original_sale_id = ? AND ri.product_id = ?
            """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, saleId);
                stmt.setString(2, productId);

                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt("returned");
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting returned quantity", e);
        }
        
        return 0;
    }

    /**
     * Process a return
     */
    public ReturnResult processReturn(String originalSaleId, List<ReturnItem> items, 
                                       String reason, String refundMethod, String cashierName) {
        ReturnResult result = new ReturnResult();
        result.success = false;
        
        if (items == null || items.isEmpty()) {
            result.errorMessage = "No items to return";
            return result;
        }
        
        try (Connection conn = dbManager.getConnection()) {

            // Calculate refund amounts
            BigDecimal refundSubtotal = BigDecimal.ZERO;
            for (ReturnItem item : items) {
                refundSubtotal = refundSubtotal.add(item.refundAmount);
            }
            
            // Calculate tax (use same rate as original sale)
            BigDecimal taxRate = SettingsService.getInstance().getDefaultTaxRate();
            BigDecimal refundTax = refundSubtotal.multiply(taxRate).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal totalRefund = refundSubtotal.add(refundTax);
            
            // Generate refund ID
            String refundId = "REFUND-" + System.currentTimeMillis() + "-" + 
                              UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            
            // Insert refund record
            String refundSql = """
                INSERT INTO refunds (id, refund_id, original_sale_id, refund_amount, refund_tax, 
                                     total_refund, refund_method, reason, cashier_name, timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            
            try (PreparedStatement stmt = conn.prepareStatement(refundSql)) {
                stmt.setString(1, UUID.randomUUID().toString());
                stmt.setString(2, refundId);
                stmt.setString(3, originalSaleId);
                stmt.setBigDecimal(4, refundSubtotal);
                stmt.setBigDecimal(5, refundTax);
                stmt.setBigDecimal(6, totalRefund);
                stmt.setString(7, refundMethod);
                stmt.setString(8, reason);
                stmt.setString(9, cashierName);
                stmt.setString(10, LocalDateTime.now().toString());
                stmt.executeUpdate();
            }
            
            // Insert refund items and restore inventory
            String itemSql = """
                INSERT INTO refund_items (refund_id, product_id, sku, name, original_price, 
                                          original_quantity, refund_quantity, refund_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
            
            try (PreparedStatement stmt = conn.prepareStatement(itemSql)) {
                for (ReturnItem item : items) {
                    stmt.setString(1, refundId);
                    stmt.setString(2, item.productId);
                    stmt.setString(3, item.sku);
                    stmt.setString(4, item.name);
                    stmt.setBigDecimal(5, item.originalPrice);
                    stmt.setInt(6, item.originalQuantity);
                    stmt.setInt(7, item.returnQuantity);
                    stmt.setBigDecimal(8, item.refundAmount);
                    stmt.addBatch();
                    
                    // Restore inventory
                    if (item.restoreToInventory && item.productId != null) {
                        inventoryService.adjustStock(item.productId, item.returnQuantity, 
                                                     "RETURN", "Return: " + refundId);
                    }
                }
                stmt.executeBatch();
            }
            
            conn.commit();
            
            result.success = true;
            result.refundId = refundId;
            result.refundAmount = totalRefund;
            result.refundTax = refundTax;
            
            logger.info("Processed return {}: {} items, ${}", refundId, items.size(), totalRefund);
            
        } catch (SQLException e) {
            logger.error("Error processing return", e);
            result.errorMessage = "Database error: " + e.getMessage();
        }
        
        return result;
    }

    /**
     * Check if a sale is within the return window
     */
    public boolean isWithinReturnWindow(String saleTimestamp) {
        if (saleTimestamp == null) return false;
        
        try {
            LocalDateTime saleDate = LocalDateTime.parse(saleTimestamp.replace("Z", ""));
            LocalDateTime cutoffDate = LocalDateTime.now().minusDays(RETURN_WINDOW_DAYS);
            return saleDate.isAfter(cutoffDate);
        } catch (Exception e) {
            logger.error("Error parsing sale timestamp", e);
            return false;
        }
    }

    /**
     * Get return window in days
     */
    public int getReturnWindowDays() {
        return RETURN_WINDOW_DAYS;
    }

    // Data classes
    public static class SaleInfo {
        public String saleId;
        public BigDecimal total;
        public String paymentMethod;
        public String cashierName;
        public String timestamp;
        public int itemCount;
        public boolean voided;
    }

    public static class SaleItemInfo {
        public String productId;
        public String sku;
        public String name;
        public BigDecimal price;
        public int quantity;
        public BigDecimal subtotal;
        public BigDecimal discount;
        public int returnedQuantity;
        public int returnableQuantity;
    }

    public static class ReturnItem {
        public String productId;
        public String sku;
        public String name;
        public BigDecimal originalPrice;
        public int originalQuantity;
        public int returnQuantity;
        public BigDecimal refundAmount;
        public boolean restoreToInventory = true;
    }

    public static class ReturnResult {
        public boolean success;
        public String refundId;
        public BigDecimal refundAmount;
        public BigDecimal refundTax;
        public String errorMessage;
    }
}

