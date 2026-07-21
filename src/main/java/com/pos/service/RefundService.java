package com.pos.service;

import com.pos.api.ApiClient;
import com.pos.database.DatabaseManager;
import com.pos.model.Refund;
import com.pos.service.SaleHistoryService.SaleRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Service for processing refunds
 */
public class RefundService {
    private static final Logger logger = LoggerFactory.getLogger(RefundService.class);
    private static RefundService instance;
    
    private final DatabaseManager dbManager;
    private final ApiClient apiClient;
    private final ProductSyncService productSyncService;
    
    private RefundService() {
        this.dbManager = DatabaseManager.getInstance();
        this.apiClient = ApiClient.getInstance();
        this.productSyncService = ProductSyncService.getInstance();
    }
    
    public static synchronized RefundService getInstance() {
        if (instance == null) {
            instance = new RefundService();
        }
        return instance;
    }
    
    /**
     * Process a refund transaction
     */
    public boolean processRefund(Refund refund) {
        try {
            logger.info("Processing refund: {} for sale: {}", refund.getRefundId(), refund.getOriginalSaleId());
            
            // Validate original sale exists
            SaleHistoryService saleHistoryService = SaleHistoryService.getInstance();
            SaleRecord originalSale = saleHistoryService.getSaleBySaleId(refund.getOriginalSaleId());
            if (originalSale == null) {
                logger.error("Original sale not found: {}", refund.getOriginalSaleId());
                return false;
            }
            
            // Calculate refund totals
            calculateRefundTotals(refund, originalSale);
            
            // Store refund locally
            storeRefundLocally(refund);
            
            // Update stock for refunded items
            updateStockForRefund(refund);
            
            // Submit to backend
            boolean success = submitRefundToBackend(refund);
            
            if (success) {
                markRefundAsSynced(refund.getRefundId());
                logger.info("Refund {} processed and submitted successfully", refund.getRefundId());
            } else {
                logger.warn("Refund {} stored locally for later sync", refund.getRefundId());
            }
            
            return true;
        } catch (Exception e) {
            logger.error("Error processing refund", e);
            return false;
        }
    }
    
    /**
     * Calculate refund totals based on items being refunded
     */
    private void calculateRefundTotals(Refund refund, SaleRecord originalSale) {
        BigDecimal refundSubtotal = BigDecimal.ZERO;
        
        // Calculate subtotal from refunded items
        for (Refund.RefundItem item : refund.getItems()) {
            BigDecimal itemRefundAmount = item.getOriginalPrice()
                .multiply(BigDecimal.valueOf(item.getRefundQuantity()));
            item.setRefundAmount(itemRefundAmount);
            refundSubtotal = refundSubtotal.add(itemRefundAmount);
        }
        
        // Calculate proportional tax refund
        // If partial refund, calculate proportional tax
        BigDecimal originalSubtotal = originalSale.subtotal;
        
        if (refundSubtotal.compareTo(originalSubtotal) == 0) {
            // Full refund - refund all tax
            refund.setRefundTax(originalSale.tax);
        } else {
            // Partial refund - calculate proportional tax
            BigDecimal proportion = refundSubtotal.divide(originalSubtotal, 4, java.math.RoundingMode.HALF_UP);
            refund.setRefundTax(originalSale.tax.multiply(proportion).setScale(2, java.math.RoundingMode.HALF_UP));
        }
        
        refund.setRefundAmount(refundSubtotal);
        refund.setTotalRefund(refundSubtotal.add(refund.getRefundTax()));
    }
    
    /**
     * Update stock for refunded items
     */
    private void updateStockForRefund(Refund refund) {
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            // Use a single query to update all products
            String sql = "UPDATE products SET stock_quantity = stock_quantity + ? WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (Refund.RefundItem item : refund.getItems()) {
                    stmt.setInt(1, item.getRefundQuantity());
                    stmt.setString(2, item.getProductId());
                    stmt.addBatch();

                    logger.info("Updating stock for product {}: +{}",
                        item.getName(), item.getRefundQuantity());
                }
                stmt.executeBatch();
            }
            conn.commit();
        } catch (Exception e) {
            logger.error("Error updating stock for refund", e);
            // Don't fail refund if stock update fails
        }
    }
    
    /**
     * Store refund locally for offline sync
     */
    private void storeRefundLocally(Refund refund) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {

            // Insert refund
            String refundSql = """
                INSERT INTO refunds (id, refund_id, original_sale_id, refund_amount, refund_tax, total_refund,
                                    payment_method, refund_method, reason, cashier_name, pos_user_id, timestamp, synced)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE)
                """;

            try (PreparedStatement refundStmt = conn.prepareStatement(refundSql)) {
                refundStmt.setString(1, UUID.randomUUID().toString());
                refundStmt.setString(2, refund.getRefundId());
                refundStmt.setString(3, refund.getOriginalSaleId());
                refundStmt.setBigDecimal(4, refund.getRefundAmount());
                refundStmt.setBigDecimal(5, refund.getRefundTax());
                refundStmt.setBigDecimal(6, refund.getTotalRefund());
                refundStmt.setString(7, refund.getPaymentMethod());
                refundStmt.setString(8, refund.getRefundMethod());
                refundStmt.setString(9, refund.getReason());
                refundStmt.setString(10, refund.getCashierName());
                refundStmt.setString(11, refund.getPosUserId());
                refundStmt.setString(12, refund.getTimestamp());
                refundStmt.executeUpdate();
            }

            // Insert refund items
            String itemSql = """
                INSERT INTO refund_items (refund_id, product_id, sku, name, original_price, original_quantity, refund_quantity, refund_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

            try (PreparedStatement itemStmt = conn.prepareStatement(itemSql)) {
                for (Refund.RefundItem item : refund.getItems()) {
                    itemStmt.setString(1, refund.getRefundId());
                    itemStmt.setString(2, item.getProductId());
                    itemStmt.setString(3, item.getSku());
                    itemStmt.setString(4, item.getName());
                    itemStmt.setBigDecimal(5, item.getOriginalPrice());
                    itemStmt.setInt(6, item.getOriginalQuantity());
                    itemStmt.setInt(7, item.getRefundQuantity());
                    itemStmt.setBigDecimal(8, item.getRefundAmount());
                    itemStmt.addBatch();
                }
                itemStmt.executeBatch();
            }

            conn.commit();
        }
    }
    
    /**
     * Submit refund to backend API
     */
    private boolean submitRefundToBackend(Refund refund) {
        try {
            // Create refund submission DTO (similar to sale submission)
            // Note: Backend API endpoint may need to be created
            apiClient.post(
                "/pos/refunds",
                createRefundSubmission(refund),
                Object.class
            );
            
            logger.info("Refund submitted: {}", refund.getRefundId());
            return true;
        } catch (ApiClient.ApiException e) {
            logger.error("Failed to submit refund to backend", e);
            return false;
        }
    }
    
    /**
     * Create refund submission DTO for backend
     */
    private Object createRefundSubmission(Refund refund) {
        // Create a simple map/DTO for refund submission
        java.util.Map<String, Object> submission = new java.util.HashMap<>();
        submission.put("refundId", refund.getRefundId());
        submission.put("originalSaleId", refund.getOriginalSaleId());
        submission.put("refundAmount", refund.getRefundAmount().doubleValue());
        submission.put("refundTax", refund.getRefundTax().doubleValue());
        submission.put("totalRefund", refund.getTotalRefund().doubleValue());
        submission.put("paymentMethod", refund.getPaymentMethod());
        submission.put("refundMethod", refund.getRefundMethod());
        submission.put("reason", refund.getReason());
        submission.put("cashierName", refund.getCashierName());
        submission.put("posUserId", refund.getPosUserId());
        submission.put("timestamp", refund.getTimestamp());
        
        // Add items
        java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
        for (Refund.RefundItem item : refund.getItems()) {
            java.util.Map<String, Object> itemMap = new java.util.HashMap<>();
            itemMap.put("productId", item.getProductId());
            itemMap.put("sku", item.getSku());
            itemMap.put("name", item.getName());
            itemMap.put("originalPrice", item.getOriginalPrice().doubleValue());
            itemMap.put("originalQuantity", item.getOriginalQuantity());
            itemMap.put("refundQuantity", item.getRefundQuantity());
            itemMap.put("refundAmount", item.getRefundAmount().doubleValue());
            items.add(itemMap);
        }
        submission.put("items", items);
        
        return submission;
    }
    
    /**
     * Mark refund as synced
     */
    private void markRefundAsSynced(String refundId) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "UPDATE refunds SET synced = TRUE WHERE refund_id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, refundId);
                stmt.executeUpdate();
                conn.commit();
            }
        }
    }
    
    /**
     * Generate unique refund ID
     */
    public String generateRefundId() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String random = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return "REFUND-" + timestamp + "-" + random;
    }
}

