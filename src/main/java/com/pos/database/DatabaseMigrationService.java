package com.pos.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;

/**
 * Handles database migration and merging when upgrading from older versions.
 * Merges old database data into new database, with newer records winning on
 * conflict.
 */
public class DatabaseMigrationService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMigrationService.class);

    /**
     * Merge old database into new database if both exist.
     * Old sales/refunds/shifts are imported if they don't exist in new DB.
     * 
     * @param oldDbPath Path to old database (without extension)
     * @param newDbPath Path to new database (without extension)
     * @return true if merge was performed, false otherwise
     */
    public static boolean mergeIfNeeded(Path oldDbPath, Path newDbPath) {
        Path oldDbFile = Paths.get(oldDbPath.toString() + ".mv.db");
        Path newDbFile = Paths.get(newDbPath.toString() + ".mv.db");

        // Only merge if BOTH databases exist
        if (!Files.exists(oldDbFile)) {
            logger.debug("No old database found at {}, skipping merge", oldDbFile);
            return false;
        }

        if (!Files.exists(newDbFile)) {
            // Just copy old to new (simple migration)
            logger.info("New database doesn't exist, performing simple migration");
            return simpleMigration(oldDbPath, newDbPath);
        }

        // Both exist - perform merge
        logger.info("Both old and new databases exist, performing merge...");
        return performMerge(oldDbPath, newDbPath);
    }

    /**
     * Simple migration: copy old database files to new location
     */
    private static boolean simpleMigration(Path oldDbPath, Path newDbPath) {
        String[] extensions = { ".mv.db", ".trace.db" };
        boolean success = true;

        for (String ext : extensions) {
            Path oldFile = Paths.get(oldDbPath.toString() + ext);
            Path newFile = Paths.get(newDbPath.toString() + ext);

            if (Files.exists(oldFile)) {
                try {
                    Files.createDirectories(newFile.getParent());
                    Files.copy(oldFile, newFile);
                    logger.info("Migrated {} to {}", oldFile, newFile);
                } catch (IOException e) {
                    logger.error("Failed to migrate {}", oldFile, e);
                    success = false;
                }
            }
        }
        return success;
    }

    /**
     * Merge old database data into new database.
     * Uses INSERT ... SELECT ... WHERE NOT EXISTS pattern to avoid duplicates.
     */
    private static boolean performMerge(Path oldDbPath, Path newDbPath) {
        String oldUrl = "jdbc:h2:" + oldDbPath.toAbsolutePath().toString().replace("\\", "/") + ";AUTO_SERVER=TRUE";
        String newUrl = "jdbc:h2:" + newDbPath.toAbsolutePath().toString().replace("\\", "/") + ";AUTO_SERVER=TRUE";

        try (Connection oldConn = DriverManager.getConnection(oldUrl, "sa", "");
                Connection newConn = DriverManager.getConnection(newUrl, "sa", "")) {

            oldConn.setAutoCommit(false);
            newConn.setAutoCommit(false);

            int totalMerged = 0;

            // Merge sales (if sale_id doesn't exist in new)
            totalMerged += mergeSales(oldConn, newConn);

            // Merge sale_items for the merged sales
            totalMerged += mergeSaleItems(oldConn, newConn);

            // Merge sale_payments for the merged sales
            totalMerged += mergeSalePayments(oldConn, newConn);

            // Merge refunds
            totalMerged += mergeRefunds(oldConn, newConn);

            // Merge refund_items
            totalMerged += mergeRefundItems(oldConn, newConn);

            // Merge shifts
            totalMerged += mergeShifts(oldConn, newConn);

            // Merge cash_operations
            totalMerged += mergeCashOperations(oldConn, newConn);

            // Merge held_sales
            totalMerged += mergeHeldSales(oldConn, newConn);

            // Merge held_sale_items
            totalMerged += mergeHeldSaleItems(oldConn, newConn);

            // Merge cart_cancellations
            totalMerged += mergeCartCancellations(oldConn, newConn);

            // Merge cart_cancellation_items
            totalMerged += mergeCartCancellationItems(oldConn, newConn);

            // Merge vendor_payouts
            totalMerged += mergeVendorPayouts(oldConn, newConn);

            // Merge expenses
            totalMerged += mergeExpenses(oldConn, newConn);

            // Merge store_settings (for store registration)
            totalMerged += mergeStoreSettings(oldConn, newConn);

            newConn.commit();
            logger.info("Database merge completed. Total records merged: {}", totalMerged);

            // Mark migration as complete by creating a marker file
            Path markerFile = newDbPath.getParent().resolve(".migration_complete");
            Files.writeString(markerFile, "Merged from: " + oldDbPath + "\nAt: " + java.time.LocalDateTime.now());

            return true;

        } catch (SQLException | IOException e) {
            logger.error("Database merge failed", e);
            return false;
        }
    }

    private static int mergeSales(Connection oldConn, Connection newConn) throws SQLException {
        String sql = """
                SELECT id, sale_id, subtotal, discount, tax, total, payment_method,
                       is_split_payment, cashier_name, cashier_id, pos_user_id, timestamp,
                       amount_received, change, gpi, ebt_fee, sync_error, synced,
                       voided, voided_at, voided_by, void_reason, created_at
                FROM sales
                """;

        int count = 0;
        try (Statement oldStmt = oldConn.createStatement();
                ResultSet rs = oldStmt.executeQuery(sql)) {

            String insertSql = """
                    INSERT INTO sales (id, sale_id, subtotal, discount, tax, total, payment_method,
                        is_split_payment, cashier_name, cashier_id, pos_user_id, timestamp,
                        amount_received, change, gpi, ebt_fee, sync_error, synced,
                        voided, voided_at, voided_by, void_reason, created_at)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM sales WHERE sale_id = ?)
                    """;

            try (PreparedStatement insertStmt = newConn.prepareStatement(insertSql)) {
                while (rs.next()) {
                    String saleId = rs.getString("sale_id");
                    insertStmt.setString(1, rs.getString("id"));
                    insertStmt.setString(2, saleId);
                    insertStmt.setBigDecimal(3, rs.getBigDecimal("subtotal"));
                    insertStmt.setBigDecimal(4, rs.getBigDecimal("discount"));
                    insertStmt.setBigDecimal(5, rs.getBigDecimal("tax"));
                    insertStmt.setBigDecimal(6, rs.getBigDecimal("total"));
                    insertStmt.setString(7, rs.getString("payment_method"));
                    insertStmt.setBoolean(8, rs.getBoolean("is_split_payment"));
                    insertStmt.setString(9, rs.getString("cashier_name"));
                    insertStmt.setString(10, rs.getString("cashier_id"));
                    insertStmt.setString(11, rs.getString("pos_user_id"));
                    insertStmt.setString(12, rs.getString("timestamp"));
                    insertStmt.setBigDecimal(13, rs.getBigDecimal("amount_received"));
                    insertStmt.setBigDecimal(14, rs.getBigDecimal("change"));
                    insertStmt.setBigDecimal(15, rs.getBigDecimal("gpi"));
                    insertStmt.setBigDecimal(16, rs.getBigDecimal("ebt_fee"));
                    insertStmt.setString(17, rs.getString("sync_error"));
                    insertStmt.setBoolean(18, rs.getBoolean("synced"));
                    insertStmt.setBoolean(19, rs.getBoolean("voided"));
                    insertStmt.setTimestamp(20, rs.getTimestamp("voided_at"));
                    insertStmt.setString(21, rs.getString("voided_by"));
                    insertStmt.setString(22, rs.getString("void_reason"));
                    insertStmt.setTimestamp(23, rs.getTimestamp("created_at"));
                    insertStmt.setString(24, saleId);

                    count += insertStmt.executeUpdate();
                }
            }
        }
        logger.info("Merged {} sales records", count);
        return count;
    }

    private static int mergeSaleItems(Connection oldConn, Connection newConn) throws SQLException {
        // Only merge items for sales that were merged (exist in old but not originally
        // in new)
        String sql = """
                SELECT si.* FROM sale_items si
                INNER JOIN sales s ON si.sale_id = s.sale_id
                """;

        int count = 0;
        try (Statement oldStmt = oldConn.createStatement();
                ResultSet rs = oldStmt.executeQuery(sql)) {

            String insertSql = """
                    INSERT INTO sale_items (sale_id, product_id, sku, name, price, quantity,
                        subtotal, discount, discount_reason, gpi, department_id, department_name)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE EXISTS (SELECT 1 FROM sales WHERE sale_id = ?)
                    AND NOT EXISTS (SELECT 1 FROM sale_items WHERE sale_id = ? AND product_id = ? AND quantity = ?)
                    """;

            try (PreparedStatement insertStmt = newConn.prepareStatement(insertSql)) {
                while (rs.next()) {
                    String saleId = rs.getString("sale_id");
                    String productId = rs.getString("product_id");
                    int quantity = rs.getInt("quantity");

                    insertStmt.setString(1, saleId);
                    insertStmt.setString(2, productId);
                    insertStmt.setString(3, rs.getString("sku"));
                    insertStmt.setString(4, rs.getString("name"));
                    insertStmt.setBigDecimal(5, rs.getBigDecimal("price"));
                    insertStmt.setInt(6, quantity);
                    insertStmt.setBigDecimal(7, rs.getBigDecimal("subtotal"));
                    insertStmt.setBigDecimal(8, rs.getBigDecimal("discount"));
                    insertStmt.setString(9, rs.getString("discount_reason"));
                    insertStmt.setBigDecimal(10, rs.getBigDecimal("gpi"));
                    insertStmt.setString(11, rs.getString("department_id"));
                    insertStmt.setString(12, rs.getString("department_name"));
                    insertStmt.setString(13, saleId);
                    insertStmt.setString(14, saleId);
                    insertStmt.setString(15, productId);
                    insertStmt.setInt(16, quantity);

                    count += insertStmt.executeUpdate();
                }
            }
        }
        logger.info("Merged {} sale_items records", count);
        return count;
    }

    private static int mergeSalePayments(Connection oldConn, Connection newConn) throws SQLException {
        String sql = "SELECT * FROM sale_payments";
        int count = 0;

        try (Statement oldStmt = oldConn.createStatement();
                ResultSet rs = oldStmt.executeQuery(sql)) {

            String insertSql = """
                    INSERT INTO sale_payments (sale_id, payment_method, amount)
                    SELECT ?, ?, ?
                    WHERE EXISTS (SELECT 1 FROM sales WHERE sale_id = ?)
                    AND NOT EXISTS (SELECT 1 FROM sale_payments WHERE sale_id = ? AND payment_method = ? AND amount = ?)
                    """;

            try (PreparedStatement insertStmt = newConn.prepareStatement(insertSql)) {
                while (rs.next()) {
                    String saleId = rs.getString("sale_id");
                    String method = rs.getString("payment_method");
                    java.math.BigDecimal amount = rs.getBigDecimal("amount");

                    insertStmt.setString(1, saleId);
                    insertStmt.setString(2, method);
                    insertStmt.setBigDecimal(3, amount);
                    insertStmt.setString(4, saleId);
                    insertStmt.setString(5, saleId);
                    insertStmt.setString(6, method);
                    insertStmt.setBigDecimal(7, amount);

                    count += insertStmt.executeUpdate();
                }
            }
        }
        logger.info("Merged {} sale_payments records", count);
        return count;
    }

    private static int mergeRefunds(Connection oldConn, Connection newConn) throws SQLException {
        String sql = "SELECT * FROM refunds";
        int count = 0;

        try (Statement oldStmt = oldConn.createStatement();
                ResultSet rs = oldStmt.executeQuery(sql)) {

            String insertSql = """
                    INSERT INTO refunds (id, refund_id, original_sale_id, refund_amount, refund_tax,
                        total_refund, payment_method, refund_method, reason, cashier_name,
                        pos_user_id, timestamp, synced, created_at)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM refunds WHERE refund_id = ?)
                    """;

            try (PreparedStatement insertStmt = newConn.prepareStatement(insertSql)) {
                while (rs.next()) {
                    String refundId = rs.getString("refund_id");
                    insertStmt.setString(1, rs.getString("id"));
                    insertStmt.setString(2, refundId);
                    insertStmt.setString(3, rs.getString("original_sale_id"));
                    insertStmt.setBigDecimal(4, rs.getBigDecimal("refund_amount"));
                    insertStmt.setBigDecimal(5, rs.getBigDecimal("refund_tax"));
                    insertStmt.setBigDecimal(6, rs.getBigDecimal("total_refund"));
                    insertStmt.setString(7, rs.getString("payment_method"));
                    insertStmt.setString(8, rs.getString("refund_method"));
                    insertStmt.setString(9, rs.getString("reason"));
                    insertStmt.setString(10, rs.getString("cashier_name"));
                    insertStmt.setString(11, rs.getString("pos_user_id"));
                    insertStmt.setString(12, rs.getString("timestamp"));
                    insertStmt.setBoolean(13, rs.getBoolean("synced"));
                    insertStmt.setTimestamp(14, rs.getTimestamp("created_at"));
                    insertStmt.setString(15, refundId);

                    count += insertStmt.executeUpdate();
                }
            }
        }
        logger.info("Merged {} refunds records", count);
        return count;
    }

    private static int mergeRefundItems(Connection oldConn, Connection newConn) throws SQLException {
        String sql = "SELECT * FROM refund_items";
        int count = 0;

        try (Statement oldStmt = oldConn.createStatement();
                ResultSet rs = oldStmt.executeQuery(sql)) {

            String insertSql = """
                    INSERT INTO refund_items (refund_id, product_id, sku, name, original_price,
                        original_quantity, refund_quantity, refund_amount)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE EXISTS (SELECT 1 FROM refunds WHERE refund_id = ?)
                    AND NOT EXISTS (SELECT 1 FROM refund_items WHERE refund_id = ? AND product_id = ?)
                    """;

            try (PreparedStatement insertStmt = newConn.prepareStatement(insertSql)) {
                while (rs.next()) {
                    String refundId = rs.getString("refund_id");
                    String productId = rs.getString("product_id");

                    insertStmt.setString(1, refundId);
                    insertStmt.setString(2, productId);
                    insertStmt.setString(3, rs.getString("sku"));
                    insertStmt.setString(4, rs.getString("name"));
                    insertStmt.setBigDecimal(5, rs.getBigDecimal("original_price"));
                    insertStmt.setInt(6, rs.getInt("original_quantity"));
                    insertStmt.setInt(7, rs.getInt("refund_quantity"));
                    insertStmt.setBigDecimal(8, rs.getBigDecimal("refund_amount"));
                    insertStmt.setString(9, refundId);
                    insertStmt.setString(10, refundId);
                    insertStmt.setString(11, productId);

                    count += insertStmt.executeUpdate();
                }
            }
        }
        logger.info("Merged {} refund_items records", count);
        return count;
    }

    private static int mergeShifts(Connection oldConn, Connection newConn) throws SQLException {
        String sql = "SELECT * FROM shifts";
        int count = 0;

        try (Statement oldStmt = oldConn.createStatement();
                ResultSet rs = oldStmt.executeQuery(sql)) {

            String insertSql = """
                    INSERT INTO shifts (id, shift_id, store_id, cashier_name, cashier_id, register_id,
                        shift_number, shift_started_at, shift_ended_at, status, opening_cash, opening_note,
                        expected_cash, actual_cash, cash_difference, closing_note, total_cash_sales,
                        total_card_sales, total_ebt_sales, total_other_sales, transaction_count,
                        gross_sales, net_sales, total_discounts, total_tax, synced, created_at, updated_at)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM shifts WHERE shift_id = ?)
                    """;

            try (PreparedStatement insertStmt = newConn.prepareStatement(insertSql)) {
                while (rs.next()) {
                    String shiftId = rs.getString("shift_id");
                    insertStmt.setString(1, rs.getString("id"));
                    insertStmt.setString(2, shiftId);
                    insertStmt.setString(3, rs.getString("store_id"));
                    insertStmt.setString(4, rs.getString("cashier_name"));
                    insertStmt.setString(5, rs.getString("cashier_id"));
                    insertStmt.setString(6, rs.getString("register_id"));
                    insertStmt.setInt(7, rs.getInt("shift_number"));
                    insertStmt.setString(8, rs.getString("shift_started_at"));
                    insertStmt.setString(9, rs.getString("shift_ended_at"));
                    insertStmt.setString(10, rs.getString("status"));
                    insertStmt.setBigDecimal(11, rs.getBigDecimal("opening_cash"));
                    insertStmt.setString(12, rs.getString("opening_note"));
                    insertStmt.setBigDecimal(13, rs.getBigDecimal("expected_cash"));
                    insertStmt.setBigDecimal(14, rs.getBigDecimal("actual_cash"));
                    insertStmt.setBigDecimal(15, rs.getBigDecimal("cash_difference"));
                    insertStmt.setString(16, rs.getString("closing_note"));
                    insertStmt.setBigDecimal(17, rs.getBigDecimal("total_cash_sales"));
                    insertStmt.setBigDecimal(18, rs.getBigDecimal("total_card_sales"));
                    insertStmt.setBigDecimal(19, rs.getBigDecimal("total_ebt_sales"));
                    insertStmt.setBigDecimal(20, rs.getBigDecimal("total_other_sales"));
                    insertStmt.setInt(21, rs.getInt("transaction_count"));
                    insertStmt.setBigDecimal(22, rs.getBigDecimal("gross_sales"));
                    insertStmt.setBigDecimal(23, rs.getBigDecimal("net_sales"));
                    insertStmt.setBigDecimal(24, rs.getBigDecimal("total_discounts"));
                    insertStmt.setBigDecimal(25, rs.getBigDecimal("total_tax"));
                    insertStmt.setBoolean(26, rs.getBoolean("synced"));
                    insertStmt.setTimestamp(27, rs.getTimestamp("created_at"));
                    insertStmt.setTimestamp(28, rs.getTimestamp("updated_at"));
                    insertStmt.setString(29, shiftId);

                    count += insertStmt.executeUpdate();
                }
            }
        }
        logger.info("Merged {} shifts records", count);
        return count;
    }

    private static int mergeCashOperations(Connection oldConn, Connection newConn) throws SQLException {
        String sql = "SELECT * FROM cash_operations";
        int count = 0;

        try (Statement oldStmt = oldConn.createStatement();
                ResultSet rs = oldStmt.executeQuery(sql)) {

            String insertSql = """
                    INSERT INTO cash_operations (id, shift_id, type, amount, note, performed_by,
                        verified_by, created_at, synced)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM cash_operations WHERE id = ?)
                    """;

            try (PreparedStatement insertStmt = newConn.prepareStatement(insertSql)) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    insertStmt.setString(1, id);
                    insertStmt.setString(2, rs.getString("shift_id"));
                    insertStmt.setString(3, rs.getString("type"));
                    insertStmt.setBigDecimal(4, rs.getBigDecimal("amount"));
                    insertStmt.setString(5, rs.getString("note"));
                    insertStmt.setString(6, rs.getString("performed_by"));
                    insertStmt.setString(7, rs.getString("verified_by"));
                    insertStmt.setString(8, rs.getString("created_at"));
                    insertStmt.setBoolean(9, rs.getBoolean("synced"));
                    insertStmt.setString(10, id);

                    count += insertStmt.executeUpdate();
                }
            }
        }
        logger.info("Merged {} cash_operations records", count);
        return count;
    }

    private static int mergeHeldSales(Connection oldConn, Connection newConn) throws SQLException {
        String sql = "SELECT * FROM held_sales";
        int count = 0;

        try (Statement oldStmt = oldConn.createStatement();
                ResultSet rs = oldStmt.executeQuery(sql)) {

            String insertSql = """
                    INSERT INTO held_sales (id, hold_id, customer_name, hold_note, subtotal, discount,
                        tax, total, sale_discount, sale_discount_reason, payment_method, cashier_name,
                        cashier_id, pos_user_id, held_at, expires_at, created_at)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM held_sales WHERE hold_id = ?)
                    """;

            try (PreparedStatement insertStmt = newConn.prepareStatement(insertSql)) {
                while (rs.next()) {
                    String holdId = rs.getString("hold_id");
                    insertStmt.setString(1, rs.getString("id"));
                    insertStmt.setString(2, holdId);
                    insertStmt.setString(3, rs.getString("customer_name"));
                    insertStmt.setString(4, rs.getString("hold_note"));
                    insertStmt.setBigDecimal(5, rs.getBigDecimal("subtotal"));
                    insertStmt.setBigDecimal(6, rs.getBigDecimal("discount"));
                    insertStmt.setBigDecimal(7, rs.getBigDecimal("tax"));
                    insertStmt.setBigDecimal(8, rs.getBigDecimal("total"));
                    insertStmt.setBigDecimal(9, rs.getBigDecimal("sale_discount"));
                    insertStmt.setString(10, rs.getString("sale_discount_reason"));
                    insertStmt.setString(11, rs.getString("payment_method"));
                    insertStmt.setString(12, rs.getString("cashier_name"));
                    insertStmt.setString(13, rs.getString("cashier_id"));
                    insertStmt.setString(14, rs.getString("pos_user_id"));
                    insertStmt.setTimestamp(15, rs.getTimestamp("held_at"));
                    insertStmt.setTimestamp(16, rs.getTimestamp("expires_at"));
                    insertStmt.setTimestamp(17, rs.getTimestamp("created_at"));
                    insertStmt.setString(18, holdId);

                    count += insertStmt.executeUpdate();
                }
            }
        }
        logger.info("Merged {} held_sales records", count);
        return count;
    }

    private static int mergeHeldSaleItems(Connection oldConn, Connection newConn) throws SQLException {
        String sql = "SELECT * FROM held_sale_items";
        int count = 0;

        try (Statement oldStmt = oldConn.createStatement();
                ResultSet rs = oldStmt.executeQuery(sql)) {

            String insertSql = """
                    INSERT INTO held_sale_items (hold_id, product_id, product_barcode, product_name, sku,
                        price, list_price, quantity, payment_method, discount_amount, discount_percent, discount_reason)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE EXISTS (SELECT 1 FROM held_sales WHERE hold_id = ?)
                    AND NOT EXISTS (SELECT 1 FROM held_sale_items WHERE hold_id = ? AND product_id = ?)
                    """;

            try (PreparedStatement insertStmt = newConn.prepareStatement(insertSql)) {
                while (rs.next()) {
                    String holdId = rs.getString("hold_id");
                    String productId = rs.getString("product_id");

                    insertStmt.setString(1, holdId);
                    insertStmt.setString(2, productId);
                    insertStmt.setString(3, rs.getString("product_barcode"));
                    insertStmt.setString(4, rs.getString("product_name"));
                    insertStmt.setString(5, rs.getString("sku"));
                    insertStmt.setBigDecimal(6, rs.getBigDecimal("price"));
                    insertStmt.setBigDecimal(7, rs.getBigDecimal("list_price"));
                    insertStmt.setInt(8, rs.getInt("quantity"));
                    insertStmt.setString(9, rs.getString("payment_method"));
                    insertStmt.setBigDecimal(10, rs.getBigDecimal("discount_amount"));
                    insertStmt.setBigDecimal(11, rs.getBigDecimal("discount_percent"));
                    insertStmt.setString(12, rs.getString("discount_reason"));
                    insertStmt.setString(13, holdId);
                    insertStmt.setString(14, holdId);
                    insertStmt.setString(15, productId);

                    count += insertStmt.executeUpdate();
                }
            }
        }
        logger.info("Merged {} held_sale_items records", count);
        return count;
    }

    private static int mergeCartCancellations(Connection oldConn, Connection newConn) throws SQLException {
        String sql = "SELECT * FROM cart_cancellations";
        int count = 0;

        try (Statement oldStmt = oldConn.createStatement();
                ResultSet rs = oldStmt.executeQuery(sql)) {

            String insertSql = """
                    INSERT INTO cart_cancellations (id, receipt_number, total_value, subtotal, discount,
                        tax, item_count, cashier_name, cashier_id, pos_user_id, shift_id, timestamp, synced, created_at)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM cart_cancellations WHERE id = ?)
                    """;

            try (PreparedStatement insertStmt = newConn.prepareStatement(insertSql)) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    insertStmt.setString(1, id);
                    insertStmt.setString(2, rs.getString("receipt_number"));
                    insertStmt.setBigDecimal(3, rs.getBigDecimal("total_value"));
                    insertStmt.setBigDecimal(4, rs.getBigDecimal("subtotal"));
                    insertStmt.setBigDecimal(5, rs.getBigDecimal("discount"));
                    insertStmt.setBigDecimal(6, rs.getBigDecimal("tax"));
                    insertStmt.setInt(7, rs.getInt("item_count"));
                    insertStmt.setString(8, rs.getString("cashier_name"));
                    insertStmt.setString(9, rs.getString("cashier_id"));
                    insertStmt.setString(10, rs.getString("pos_user_id"));
                    insertStmt.setString(11, rs.getString("shift_id"));
                    insertStmt.setTimestamp(12, rs.getTimestamp("timestamp"));
                    insertStmt.setBoolean(13, rs.getBoolean("synced"));
                    insertStmt.setTimestamp(14, rs.getTimestamp("created_at"));
                    insertStmt.setString(15, id);

                    count += insertStmt.executeUpdate();
                }
            }
        }
        logger.info("Merged {} cart_cancellations records", count);
        return count;
    }

    private static int mergeCartCancellationItems(Connection oldConn, Connection newConn) throws SQLException {
        String sql = "SELECT * FROM cart_cancellation_items";
        int count = 0;

        try (Statement oldStmt = oldConn.createStatement();
                ResultSet rs = oldStmt.executeQuery(sql)) {

            String insertSql = """
                    INSERT INTO cart_cancellation_items (cancellation_id, product_id, product_name, sku,
                        barcode, quantity, unit_price, subtotal, discount, discount_reason, department_id, department_name)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE EXISTS (SELECT 1 FROM cart_cancellations WHERE id = ?)
                    AND NOT EXISTS (SELECT 1 FROM cart_cancellation_items WHERE cancellation_id = ? AND product_id = ?)
                    """;

            try (PreparedStatement insertStmt = newConn.prepareStatement(insertSql)) {
                while (rs.next()) {
                    String cancellationId = rs.getString("cancellation_id");
                    String productId = rs.getString("product_id");

                    insertStmt.setString(1, cancellationId);
                    insertStmt.setString(2, productId);
                    insertStmt.setString(3, rs.getString("product_name"));
                    insertStmt.setString(4, rs.getString("sku"));
                    insertStmt.setString(5, rs.getString("barcode"));
                    insertStmt.setInt(6, rs.getInt("quantity"));
                    insertStmt.setBigDecimal(7, rs.getBigDecimal("unit_price"));
                    insertStmt.setBigDecimal(8, rs.getBigDecimal("subtotal"));
                    insertStmt.setBigDecimal(9, rs.getBigDecimal("discount"));
                    insertStmt.setString(10, rs.getString("discount_reason"));
                    insertStmt.setString(11, rs.getString("department_id"));
                    insertStmt.setString(12, rs.getString("department_name"));
                    insertStmt.setString(13, cancellationId);
                    insertStmt.setString(14, cancellationId);
                    insertStmt.setString(15, productId);

                    count += insertStmt.executeUpdate();
                }
            }
        }
        logger.info("Merged {} cart_cancellation_items records", count);
        return count;
    }

    private static int mergeVendorPayouts(Connection oldConn, Connection newConn) throws SQLException {
        String sql = "SELECT * FROM vendor_payouts";
        int count = 0;

        try (Statement oldStmt = oldConn.createStatement();
                ResultSet rs = oldStmt.executeQuery(sql)) {

            String insertSql = """
                    INSERT INTO vendor_payouts (id, vendor_id, vendor_name, period_start, period_end,
                        total_sales, total_cost, total_payout, commission_rate, item_count, transaction_count,
                        status, paid_at, paid_by, payment_method, payment_reference, shift_id, notes, synced, created_at, updated_at)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM vendor_payouts WHERE id = ?)
                    """;

            try (PreparedStatement insertStmt = newConn.prepareStatement(insertSql)) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    insertStmt.setString(1, id);
                    insertStmt.setString(2, rs.getString("vendor_id"));
                    insertStmt.setString(3, rs.getString("vendor_name"));
                    insertStmt.setString(4, rs.getString("period_start"));
                    insertStmt.setString(5, rs.getString("period_end"));
                    insertStmt.setBigDecimal(6, rs.getBigDecimal("total_sales"));
                    insertStmt.setBigDecimal(7, rs.getBigDecimal("total_cost"));
                    insertStmt.setBigDecimal(8, rs.getBigDecimal("total_payout"));
                    insertStmt.setBigDecimal(9, rs.getBigDecimal("commission_rate"));
                    insertStmt.setInt(10, rs.getInt("item_count"));
                    insertStmt.setInt(11, rs.getInt("transaction_count"));
                    insertStmt.setString(12, rs.getString("status"));
                    insertStmt.setString(13, rs.getString("paid_at"));
                    insertStmt.setString(14, rs.getString("paid_by"));
                    insertStmt.setString(15, rs.getString("payment_method"));
                    insertStmt.setString(16, rs.getString("payment_reference"));
                    insertStmt.setString(17, rs.getString("shift_id"));
                    insertStmt.setString(18, rs.getString("notes"));
                    insertStmt.setBoolean(19, rs.getBoolean("synced"));
                    insertStmt.setTimestamp(20, rs.getTimestamp("created_at"));
                    insertStmt.setString(21, rs.getString("updated_at"));
                    insertStmt.setString(22, id);

                    count += insertStmt.executeUpdate();
                }
            }
        }
        logger.info("Merged {} vendor_payouts records", count);
        return count;
    }

    private static int mergeExpenses(Connection oldConn, Connection newConn) throws SQLException {
        String sql = "SELECT * FROM expenses";
        int count = 0;

        try (Statement oldStmt = oldConn.createStatement();
                ResultSet rs = oldStmt.executeQuery(sql)) {

            String insertSql = """
                    INSERT INTO expenses (id, expense_id, category_id, category_name, amount, description,
                        payment_method, shift_id, receipt_number, vendor_name, created_by, created_by_name,
                        timestamp, synced, created_at)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM expenses WHERE expense_id = ?)
                    """;

            try (PreparedStatement insertStmt = newConn.prepareStatement(insertSql)) {
                while (rs.next()) {
                    String expenseId = rs.getString("expense_id");
                    insertStmt.setString(1, rs.getString("id"));
                    insertStmt.setString(2, expenseId);
                    insertStmt.setString(3, rs.getString("category_id"));
                    insertStmt.setString(4, rs.getString("category_name"));
                    insertStmt.setBigDecimal(5, rs.getBigDecimal("amount"));
                    insertStmt.setString(6, rs.getString("description"));
                    insertStmt.setString(7, rs.getString("payment_method"));
                    insertStmt.setString(8, rs.getString("shift_id"));
                    insertStmt.setString(9, rs.getString("receipt_number"));
                    insertStmt.setString(10, rs.getString("vendor_name"));
                    insertStmt.setString(11, rs.getString("created_by"));
                    insertStmt.setString(12, rs.getString("created_by_name"));
                    insertStmt.setString(13, rs.getString("timestamp"));
                    insertStmt.setBoolean(14, rs.getBoolean("synced"));
                    insertStmt.setTimestamp(15, rs.getTimestamp("created_at"));
                    insertStmt.setString(16, expenseId);

                    count += insertStmt.executeUpdate();
                }
            }
        }
        logger.info("Merged {} expenses records", count);
        return count;
    }

    private static int mergeStoreSettings(Connection oldConn, Connection newConn) throws SQLException {
        String sql = "SELECT * FROM store_settings";
        int count = 0;

        try (Statement oldStmt = oldConn.createStatement();
                ResultSet rs = oldStmt.executeQuery(sql)) {

            String insertSql = """
                    INSERT INTO store_settings (id, store_id, store_name, tax_enabled, tax_rate,
                        currency, timezone, receipt_header, receipt_footer, low_stock_threshold,
                        card_surcharge_enabled, card_surcharge_percent, require_manager_approval_voids,
                        require_manager_approval_discounts_over, require_manager_approval_refunds, updated_at)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM store_settings WHERE store_id = ?)
                    """;

            try (PreparedStatement insertStmt = newConn.prepareStatement(insertSql)) {
                while (rs.next()) {
                    String storeId = rs.getString("store_id");
                    insertStmt.setString(1, rs.getString("id"));
                    insertStmt.setString(2, storeId);
                    insertStmt.setString(3, rs.getString("store_name"));
                    insertStmt.setBoolean(4, rs.getBoolean("tax_enabled"));
                    insertStmt.setBigDecimal(5, rs.getBigDecimal("tax_rate"));
                    insertStmt.setString(6, rs.getString("currency"));
                    insertStmt.setString(7, rs.getString("timezone"));
                    insertStmt.setString(8, rs.getString("receipt_header"));
                    insertStmt.setString(9, rs.getString("receipt_footer"));
                    insertStmt.setInt(10, rs.getInt("low_stock_threshold"));
                    insertStmt.setBoolean(11, rs.getBoolean("card_surcharge_enabled"));
                    insertStmt.setBigDecimal(12, rs.getBigDecimal("card_surcharge_percent"));
                    insertStmt.setBoolean(13, rs.getBoolean("require_manager_approval_voids"));
                    insertStmt.setBigDecimal(14, rs.getBigDecimal("require_manager_approval_discounts_over"));
                    insertStmt.setBoolean(15, rs.getBoolean("require_manager_approval_refunds"));
                    insertStmt.setString(16, rs.getString("updated_at"));
                    insertStmt.setString(17, storeId);

                    count += insertStmt.executeUpdate();
                }
            }
        }
        logger.info("Merged {} store_settings records", count);
        return count;
    }
}
