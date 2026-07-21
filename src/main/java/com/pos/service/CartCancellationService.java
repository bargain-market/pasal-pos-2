package com.pos.service;

import com.pos.database.DatabaseManager;
import com.pos.model.SaleItem;
import com.pos.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Service for logging cart cancellations to the database
 */
public class CartCancellationService {
    private static final Logger logger = LoggerFactory.getLogger(CartCancellationService.class);
    private static CartCancellationService instance;

    private final DatabaseManager dbManager;
    private final UserAuthService userAuthService;
    private final ShiftService shiftService;

    private CartCancellationService() {
        this.dbManager = DatabaseManager.getInstance();
        this.userAuthService = UserAuthService.getInstance();
        this.shiftService = ShiftService.getInstance();
    }

    public static synchronized CartCancellationService getInstance() {
        if (instance == null) {
            instance = new CartCancellationService();
        }
        return instance;
    }

    /**
     * Log a cart cancellation to the database
     * 
     * @param cartItems     List of items in the canceled cart
     * @param total         Total value of the cart before cancellation
     * @param subtotal      Subtotal before discounts
     * @param discount      Total discounts applied
     * @param tax           Tax amount
     * @param receiptNumber Receipt number for the canceled transaction
     * @return The cancellation ID, or null if logging failed
     */
    public String logCartCancellation(List<SaleItem> cartItems, BigDecimal total,
            BigDecimal subtotal, BigDecimal discount, BigDecimal tax, String receiptNumber) {
        if (cartItems == null || cartItems.isEmpty()) {
            logger.debug("Cart is empty, skipping cancellation logging");
            return null;
        }

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                // Generate cancellation ID
                String cancellationId = UUID.randomUUID().toString();

                // Get user information
                String userId = userAuthService.getCurrentUserId();
                String userName = userAuthService.getCurrentUserName();
                String posUserId = userAuthService.getCurrentPosUserId();

                // Get active shift
                String shiftId = null;
                try {
                    com.pos.api.dto.ShiftResponse.ShiftData activeShift = shiftService.getActiveShift();
                    if (activeShift != null) {
                        shiftId = activeShift.id;
                    }
                } catch (Exception e) {
                    logger.debug("Could not get active shift for cart cancellation: {}", e.getMessage());
                }

                // Insert cancellation record
                String insertCancellationSql = """
                        INSERT INTO cart_cancellations
                        (id, receipt_number, total_value, subtotal, discount, tax, item_count,
                         cashier_name, cashier_id, pos_user_id, shift_id, timestamp, synced, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """;

                try (PreparedStatement stmt = conn.prepareStatement(insertCancellationSql)) {
                    stmt.setString(1, cancellationId);
                    stmt.setString(2, receiptNumber);
                    stmt.setBigDecimal(3, total != null ? total : BigDecimal.ZERO);
                    stmt.setBigDecimal(4, subtotal != null ? subtotal : BigDecimal.ZERO);
                    stmt.setBigDecimal(5, discount != null ? discount : BigDecimal.ZERO);
                    stmt.setBigDecimal(6, tax != null ? tax : BigDecimal.ZERO);
                    stmt.setInt(7, cartItems.size());
                    stmt.setString(8, userName);
                    stmt.setString(9, userId);
                    stmt.setString(10, posUserId);
                    stmt.setString(11, shiftId);
                    stmt.setTimestamp(12, Timestamp.valueOf(LocalDateTime.now()));
                    stmt.setBoolean(13, false); // Not synced yet
                    stmt.setTimestamp(14, Timestamp.valueOf(LocalDateTime.now()));

                    stmt.executeUpdate();
                }

                // Insert cancellation items
                String insertItemSql = """
                        INSERT INTO cart_cancellation_items
                        (cancellation_id, product_id, product_name, sku, barcode, quantity,
                         unit_price, subtotal, discount, discount_reason, department_id, department_name)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """;

                try (PreparedStatement itemStmt = conn.prepareStatement(insertItemSql)) {
                    for (SaleItem item : cartItems) {
                        Product product = item.getProduct();
                        BigDecimal unitPrice = item.getPrice();
                        BigDecimal itemSubtotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
                        BigDecimal itemDiscount = item.getTotalDiscount();
                        String discountReason = item.getDiscountReason();
                        String departmentId = product.getDepartmentId();

                        // Get department name if department ID is available
                        String departmentName = null;
                        if (departmentId != null && !departmentId.isEmpty()) {
                            departmentName = getDepartmentName(departmentId, conn);
                        }

                        // Check if this is an open department item (name ends with " (Open)")
                        String itemName = product.getName();
                        boolean isOpenDepartmentItem = itemName != null && itemName.endsWith(" (Open)");
                        if (isOpenDepartmentItem && departmentName == null && itemName != null) {
                            // Extract department name from item name for open department items
                            departmentName = itemName.substring(0, itemName.length() - 7);
                        }

                        itemStmt.setString(1, cancellationId);
                        itemStmt.setString(2, product.getId());
                        itemStmt.setString(3, product.getName());
                        itemStmt.setString(4, null); // SKU not available in Product model
                        itemStmt.setString(5, product.getBarcode());
                        itemStmt.setInt(6, item.getQuantity());
                        itemStmt.setBigDecimal(7, unitPrice);
                        itemStmt.setBigDecimal(8, itemSubtotal);
                        itemStmt.setBigDecimal(9, itemDiscount);
                        itemStmt.setString(10, discountReason != null ? discountReason : "");
                        itemStmt.setString(11, departmentId);
                        itemStmt.setString(12, departmentName);

                        itemStmt.addBatch();
                    }

                    itemStmt.executeBatch();
                }

                conn.commit();
                logger.info("Cart cancellation logged: {} items, total: ${}, receipt: {}",
                        cartItems.size(), total, receiptNumber);

                // Trigger outbound sync to sync cancellation to backend
                com.pos.sync.SyncManager.getInstance().triggerOutboundSync();

                return cancellationId;

            } catch (SQLException e) {
                conn.rollback();
                logger.error("Error logging cart cancellation", e);
                return null;
            } finally {
                conn.setAutoCommit(true);
            }

        } catch (SQLException e) {
            logger.error("Error getting database connection for cart cancellation", e);
            return null;
        }
    }

    /**
     * Log a single item cancellation (removal from cart)
     * 
     * @param item          The item being removed
     * @param receiptNumber The current receipt number
     * @return The cancellation ID, or null if logging failed
     */
    public String logItemCancellation(SaleItem item, String receiptNumber) {
        if (item == null || item.getProduct() == null) {
            return null;
        }

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try {
                String cancellationId = UUID.randomUUID().toString();
                String userId = userAuthService.getCurrentUserId();
                String userName = userAuthService.getCurrentUserName();
                String posUserId = userAuthService.getCurrentPosUserId();

                String shiftId = null;
                try {
                    com.pos.api.dto.ShiftResponse.ShiftData activeShift = shiftService.getActiveShift();
                    if (activeShift != null) {
                        shiftId = activeShift.id;
                    }
                } catch (Exception e) {
                    logger.debug("Could not get active shift for item cancellation: {}", e.getMessage());
                }

                // Insert into cart_cancellations with total_value = item subtotal
                // We use 'ITEM_VOID' in receipt_number or a similar flag if we had a type
                // column,
                // but for now we'll stick to the existing schema and just log the item.
                String insertCancellationSql = """
                        INSERT INTO cart_cancellations
                        (id, receipt_number, total_value, subtotal, discount, tax, item_count,
                         cashier_name, cashier_id, pos_user_id, shift_id, timestamp, synced, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """;

                BigDecimal itemSubtotal = item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));

                try (PreparedStatement stmt = conn.prepareStatement(insertCancellationSql)) {
                    stmt.setString(1, cancellationId);
                    stmt.setString(2, "VOID:" + receiptNumber); // Prefix to distinguish from full cart cancel
                    stmt.setBigDecimal(3, itemSubtotal);
                    stmt.setBigDecimal(4, itemSubtotal);
                    stmt.setBigDecimal(5, item.getTotalDiscount());
                    stmt.setBigDecimal(6, BigDecimal.ZERO); // We don't track per-item tax in cart cancellations yet
                    stmt.setInt(7, 1);
                    stmt.setString(8, userName);
                    stmt.setString(9, userId);
                    stmt.setString(10, posUserId);
                    stmt.setString(11, shiftId);
                    stmt.setTimestamp(12, Timestamp.valueOf(LocalDateTime.now()));
                    stmt.setBoolean(13, false);
                    stmt.setTimestamp(14, Timestamp.valueOf(LocalDateTime.now()));
                    stmt.executeUpdate();
                }

                // Insert into cart_cancellation_items
                String insertItemSql = """
                        INSERT INTO cart_cancellation_items
                        (cancellation_id, product_id, product_name, sku, barcode, quantity,
                         unit_price, subtotal, discount, discount_reason, department_id, department_name)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """;

                try (PreparedStatement itemStmt = conn.prepareStatement(insertItemSql)) {
                    Product product = item.getProduct();
                    String departmentId = product.getDepartmentId();
                    String departmentName = null;
                    if (departmentId != null && !departmentId.isEmpty()) {
                        departmentName = getDepartmentName(departmentId, conn);
                    }

                    itemStmt.setString(1, cancellationId);
                    itemStmt.setString(2, product.getId());
                    itemStmt.setString(3, product.getName());
                    itemStmt.setString(4, null);
                    itemStmt.setString(5, product.getBarcode());
                    itemStmt.setInt(6, item.getQuantity());
                    itemStmt.setBigDecimal(7, item.getPrice());
                    itemStmt.setBigDecimal(8, itemSubtotal);
                    itemStmt.setBigDecimal(9, item.getTotalDiscount());
                    itemStmt.setString(10, item.getDiscountReason() != null ? item.getDiscountReason() : "");
                    itemStmt.setString(11, departmentId);
                    itemStmt.setString(12, departmentName);
                    itemStmt.executeUpdate();
                }

                conn.commit();
                logger.info("Item cancellation logged: {}, receipt: {}", item.getProduct().getName(), receiptNumber);
                com.pos.sync.SyncManager.getInstance().triggerOutboundSync();
                return cancellationId;

            } catch (SQLException e) {
                conn.rollback();
                logger.error("Error logging item cancellation", e);
                return null;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Error getting database connection for item cancellation", e);
            return null;
        }
    }

    /**
     * Mark a cart cancellation as synced to backend
     * 
     * @param cancellationId The cancellation ID to mark as synced
     */
    public void markAsSynced(String cancellationId) {
        if (cancellationId == null || cancellationId.isBlank()) {
            return;
        }
        // Connections use autoCommit=false (see DatabaseManager); must commit for other threads
        // (e.g. SyncManager pending count) to see synced=TRUE.
        try (Connection conn = dbManager.getConnection()) {
            String sql = "UPDATE cart_cancellations SET synced = TRUE WHERE id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, cancellationId);
                int updated = stmt.executeUpdate();
                if (updated == 0) {
                    logger.warn("markAsSynced: no cart_cancellations row for id {}", cancellationId);
                }
            }
            conn.commit();
            logger.debug("Cart cancellation {} marked as synced", cancellationId);
        } catch (SQLException e) {
            logger.error("Error marking cart cancellation as synced: {}", cancellationId, e);
        }
    }

    /**
     * Get unsynced cart cancellations for backend sync
     * This method can be used by outbound sync handlers
     * 
     * @return List of unsynced cancellation IDs
     */
    public List<String> getUnsyncedCancellations() {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT id FROM cart_cancellations WHERE synced = FALSE ORDER BY timestamp ASC";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                java.sql.ResultSet rs = stmt.executeQuery();
                java.util.List<String> cancellationIds = new java.util.ArrayList<>();
                while (rs.next()) {
                    cancellationIds.add(rs.getString("id"));
                }
                return cancellationIds;
            }
        } catch (SQLException e) {
            logger.error("Error getting unsynced cart cancellations", e);
            return new java.util.ArrayList<>();
        }
    }

    /**
     * Get department name from department ID
     * 
     * @param departmentId The department ID
     * @param conn         Database connection
     * @return Department name, or null if not found
     */
    private String getDepartmentName(String departmentId, Connection conn) {
        try {
            String sql = "SELECT name FROM departments WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, departmentId);
                java.sql.ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getString("name");
                }
            }
        } catch (SQLException e) {
            logger.debug("Could not get department name for {}: {}", departmentId, e.getMessage());
        }
        return null;
    }
}
