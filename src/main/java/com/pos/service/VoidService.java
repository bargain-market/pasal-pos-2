package com.pos.service;

import com.pos.api.ApiClient;
import com.pos.database.DatabaseManager;
import com.pos.service.SaleHistoryService.SaleRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Service for voiding transactions
 */
public class VoidService {
    private static final Logger logger = LoggerFactory.getLogger(VoidService.class);
    private static VoidService instance;

    private final DatabaseManager dbManager;
    private final ApiClient apiClient;
    private final InventoryService inventoryService;
    private final UserAuthService userAuthService;
    private final ShiftService shiftService;

    private VoidService() {
        this.dbManager = DatabaseManager.getInstance();
        this.apiClient = ApiClient.getInstance();
        this.inventoryService = InventoryService.getInstance();
        this.userAuthService = UserAuthService.getInstance();
        this.shiftService = ShiftService.getInstance();
    }

    public static synchronized VoidService getInstance() {
        if (instance == null) {
            instance = new VoidService();
        }
        return instance;
    }

    /**
     * Check if a sale can be voided
     * Rules:
     * - Sale must not already be voided
     * - Sale must be from today (same day)
     * - Sale must not have been refunded
     */
    public boolean canVoidSale(String saleId) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {

            // Check if sale exists and is not voided
            String checkSql = """
                    SELECT s.voided, s.created_at,
                           (SELECT COUNT(*) FROM refunds WHERE original_sale_id = s.sale_id) as refund_count
                    FROM sales s
                    WHERE s.sale_id = ?
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(checkSql)) {
                stmt.setString(1, saleId);
                ResultSet rs = stmt.executeQuery();

                if (!rs.next()) {
                    logger.warn("Sale not found: {}", saleId);
                    return false;
                }

                boolean voided = rs.getBoolean("voided");
                if (voided) {
                    logger.warn("Sale already voided: {}", saleId);
                    return false;
                }

                // Check if sale has been refunded
                int refundCount = rs.getInt("refund_count");
                if (refundCount > 0) {
                    logger.warn("Sale has been refunded, cannot void: {}", saleId);
                    return false;
                }

                // Check if sale is from today
                java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
                if (createdAt != null) {
                    LocalDate saleDate = createdAt.toLocalDateTime().toLocalDate();
                    LocalDate today = LocalDate.now();
                    if (!saleDate.equals(today)) {
                        logger.warn("Sale is not from today, cannot void: {} (date: {})", saleId, saleDate);
                        return false;
                    }
                }

                return true;
            }
        }
    }

    /**
     * Void a transaction
     * 
     * @param saleId The sale ID to void
     * @param reason Reason for voiding
     * @return true if successful, false otherwise
     */
    public boolean voidTransaction(String saleId, String reason) {
        try {
            logger.info("Voiding transaction: {} - Reason: {}", saleId, reason);

            // Validate sale can be voided
            if (!canVoidSale(saleId)) {
                logger.error("Sale cannot be voided: {}", saleId);
                return false;
            }

            // Get sale record
            SaleHistoryService saleHistoryService = SaleHistoryService.getInstance();
            SaleRecord sale = saleHistoryService.getSaleBySaleId(saleId);
            if (sale == null) {
                logger.error("Sale not found: {}", saleId);
                return false;
            }

            // Restore stock for all items
            restoreStockForVoid(sale);

            // Mark sale as voided in database
            markSaleAsVoided(saleId, reason);

            // Reverse shift totals if sale was part of an active shift
            reverseShiftTotals(sale);

            // Submit void to backend
            boolean success = submitVoidToBackend(saleId, reason);

            if (success) {
                logger.info("Transaction voided successfully: {}", saleId);
            } else {
                logger.warn("Void stored locally, will sync to backend later: {}", saleId);
            }

            return true;
        } catch (Exception e) {
            logger.error("Error voiding transaction", e);
            return false;
        }
    }

    /**
     * Restore stock for all items in the voided sale
     */
    private void restoreStockForVoid(SaleRecord sale) throws SQLException {
        for (SaleHistoryService.SaleItemRecord item : sale.items) {
            try {
                String productId = item.productId;
                if (productId == null || productId.isEmpty()) {
                    logger.warn("Cannot restore stock: Product ID is null for item: {}", item.name);
                    continue;
                }

                int quantityToRestore = item.quantity;

                // Restore stock (positive adjustment) and create inventory log
                String logReason = "Void Transaction: " + sale.saleId;
                inventoryService.adjustStock(productId, quantityToRestore, "VOID", logReason);

                logger.info("Restored {} units for product {} (ID: {}) for voided sale {}",
                        quantityToRestore, item.name, productId, sale.saleId);

            } catch (SQLException e) {
                logger.error("Failed to restore stock for product {}: {}",
                        item.name, e.getMessage());
                // Continue processing other items even if one fails
            }
        }
    }

    /**
     * Mark sale as voided in database
     */
    private void markSaleAsVoided(String saleId, String reason) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {

            String userId = userAuthService.getCurrentUserId();
            String userName = userAuthService.getCurrentUserName();
            String voidedBy = userName != null ? userName : (userId != null ? userId : "System");

            String sql = """
                    UPDATE sales
                    SET voided = TRUE,
                        voided_at = CURRENT_TIMESTAMP,
                        voided_by = ?,
                        void_reason = ?,
                        synced = FALSE
                    WHERE sale_id = ?
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, voidedBy);
                stmt.setString(2, reason != null ? reason : "");
                stmt.setString(3, saleId);
                stmt.executeUpdate();
                conn.commit();
            }

            logger.info("Sale marked as voided: {}", saleId);
        }
    }

    /**
     * Reverse shift totals for the voided sale
     */
    private void reverseShiftTotals(SaleRecord sale) {
        try {
            // Find the shift that contains this sale
            // We need to check if there's an active shift or find the shift by date
            com.pos.api.dto.ShiftResponse.ShiftData activeShift = shiftService.getActiveShift();

            if (activeShift != null) {
                // Reverse the totals by subtracting
                reverseShiftTotalsForShift(activeShift.id, sale.total, sale.paymentMethod);
                logger.info("Reversed shift totals for voided sale: {}", sale.saleId);
            } else {
                logger.debug("No active shift found, skipping shift total reversal");
            }
        } catch (Exception e) {
            logger.warn("Failed to reverse shift totals for voided sale: {}", sale.saleId, e);
            // Don't fail the void operation if shift reversal fails
        }
    }

    /**
     * Reverse shift totals by subtracting the sale amount
     */
    private void reverseShiftTotalsForShift(String shiftId, BigDecimal amount, String paymentMethod)
            throws SQLException {
        try (Connection conn = dbManager.getConnection()) {

            String updateField = switch (paymentMethod) {
                case "CASH" -> "total_cash_sales";
                case "CARD" -> "total_card_sales";
                case "EBT" -> "total_ebt_sales";
                default -> "total_other_sales";
            };

            // Use CASE statement instead of GREATEST for H2 compatibility
            // Reverse both gross_sales and net_sales
            String sql = String.format("""
                    UPDATE shifts SET
                        %s = CASE WHEN %s - ? < 0 THEN 0 ELSE %s - ? END,
                        transaction_count = CASE WHEN transaction_count - 1 < 0 THEN 0 ELSE transaction_count - 1 END,
                        gross_sales = CASE WHEN gross_sales - ? < 0 THEN 0 ELSE gross_sales - ? END,
                        net_sales = CASE WHEN net_sales - ? < 0 THEN 0 ELSE net_sales - ? END,
                        synced = FALSE
                    WHERE id = ? OR shift_id = ?
                    """, updateField, updateField, updateField);

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBigDecimal(1, amount);
                stmt.setBigDecimal(2, amount);
                stmt.setBigDecimal(3, amount);
                stmt.setBigDecimal(4, amount);
                stmt.setBigDecimal(5, amount);
                stmt.setBigDecimal(6, amount);
                stmt.setString(7, shiftId);
                stmt.setString(8, shiftId);
                stmt.executeUpdate();
                conn.commit();
            }
        }
    }

    /**
     * Submit void to backend API
     */
    private boolean submitVoidToBackend(String saleId, String reason) {
        try {
            // Create void request
            VoidRequest request = new VoidRequest();
            request.saleId = saleId;
            request.reason = reason;
            request.voidedBy = userAuthService.getCurrentUserName();
            request.voidedAt = Instant.now().atOffset(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_INSTANT);

            apiClient.post(
                    "/pos/sales/" + saleId + "/void",
                    request,
                    VoidResponse.class);

            logger.info("Void submitted to backend successfully: {}", saleId);
            return true;
        } catch (ApiClient.ApiException e) {
            logger.warn("Failed to submit void to backend: {}", e.getMessage());
            // Queue for offline sync if needed
            try {
                OfflineSyncService offlineSyncService = OfflineSyncService.getInstance();
                if (!offlineSyncService.isOnline()) {
                    VoidRequest request = new VoidRequest();
                    request.saleId = saleId;
                    request.reason = reason;
                    request.voidedBy = userAuthService.getCurrentUserName();
                    request.voidedAt = Instant.now().atOffset(ZoneOffset.UTC)
                            .format(DateTimeFormatter.ISO_INSTANT);
                    offlineSyncService.queueRequest("/pos/sales/" + saleId + "/void", "POST", request, 1);
                }
            } catch (Exception ex) {
                logger.error("Error queueing void for offline sync", ex);
            }
            return false;
        }
    }

    /**
     * Void request DTO
     */
    public static class VoidRequest {
        public String saleId;
        public String reason;
        public String voidedBy;
        public String voidedAt;
    }

    /**
     * Void response DTO
     */
    public static class VoidResponse {
        public String saleId;
        public boolean success;
        public String message;
    }
}
