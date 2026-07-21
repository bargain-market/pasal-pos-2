package com.pos.sync.inbound;

import com.pos.api.ApiClient;
import com.pos.api.dto.BackfillResponse;
import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.sync.SyncDirection;
import com.pos.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * On-demand recovery sync that pulls transactional data from the backend back
 * down into the local H2 database.
 *
 * <p>Unlike the scheduled inbound handlers (products, vendors, settings, …),
 * this one is triggered manually from Settings and is parameterised by a number
 * of days. It calls {@code GET /pos/backfill?days=N} and upserts the returned
 * sales, refunds, shifts (+ cash operations) and vendor payouts into their local
 * tables.</p>
 *
 * <p>All backfilled rows are written with {@code synced = TRUE} so they are
 * never re-pushed to the backend by the outbound sync (they already live there
 * — this is a download, not an upload). Upserts use {@code MERGE} on the stable
 * POS identifiers, so running the backfill repeatedly is idempotent.</p>
 *
 * <p>Expenses are intentionally absent: they are not synced to the backend, so
 * there is nothing to pull.</p>
 */
public class BackfillInboundSync {
    private static final Logger logger = LoggerFactory.getLogger(BackfillInboundSync.class);

    private static BackfillInboundSync instance;

    private final ApiClient apiClient;
    private final DatabaseManager dbManager;
    private final ConfigManager config;

    private BackfillInboundSync() {
        this.apiClient = ApiClient.getInstance();
        this.dbManager = DatabaseManager.getInstance();
        this.config = ConfigManager.getInstance();
    }

    public static synchronized BackfillInboundSync getInstance() {
        if (instance == null) {
            instance = new BackfillInboundSync();
        }
        return instance;
    }

    /**
     * Result of a backfill run, with per-entity counts for user feedback.
     */
    public static class BackfillCounts {
        public int sales;
        public int refunds;
        public int shifts;
        public int cashOperations;
        public int vendorPayouts;

        public int total() {
            return sales + refunds + shifts + cashOperations + vendorPayouts;
        }
    }

    /**
     * Pull and store {@code days} days of backend data into the local database.
     *
     * @param days number of calendar days to recover (1..365)
     * @return per-entity counts of rows stored
     */
    public BackfillCounts backfill(int days) throws Exception {
        if (days <= 0) {
            days = 30;
        }
        if (days > 365) {
            days = 365;
        }

        String endpoint = "/pos/backfill?days=" + days;
        logger.info("Starting backfill from backend: {}", endpoint);

        // Backfill can return a large payload; give it a generous call timeout.
        ApiClient.ApiResponse<BackfillResponse> response =
                apiClient.get(endpoint, BackfillResponse.class, 180_000);

        BackfillResponse data = response.getData();
        BackfillCounts counts = new BackfillCounts();

        if (data == null) {
            logger.warn("Empty backfill response from backend");
            return counts;
        }

        counts.sales = storeSales(data.sales);
        counts.refunds = storeRefunds(data.refunds);
        int[] shiftCounts = storeShifts(data.shifts);
        counts.shifts = shiftCounts[0];
        counts.cashOperations = shiftCounts[1];
        counts.vendorPayouts = storeVendorPayouts(data.vendorPayouts);

        logger.info("Backfill complete: {} sales, {} refunds, {} shifts, {} cash operations, {} vendor payouts",
                counts.sales, counts.refunds, counts.shifts, counts.cashOperations, counts.vendorPayouts);

        return counts;
    }

    /** Adapter so callers that want a {@link SyncResult} can use this handler too. */
    public SyncResult backfillAsResult(int days) {
        try {
            BackfillCounts counts = backfill(days);
            return SyncResult.success(SyncDirection.INBOUND, counts.total());
        } catch (ApiClient.ApiException e) {
            logger.error("Backfill failed: {}", e.getMessage());
            return SyncResult.failure(SyncDirection.INBOUND, e.getMessage());
        } catch (Exception e) {
            logger.error("Backfill failed", e);
            return SyncResult.failure(SyncDirection.INBOUND, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Sales (+ items + payments)
    // ------------------------------------------------------------------

    private int storeSales(List<BackfillResponse.Sale> sales) throws SQLException {
        if (sales == null || sales.isEmpty()) {
            return 0;
        }

        String saleSql = "MERGE INTO sales " +
                "(id, sale_id, subtotal, discount, tax, total, payment_method, is_split_payment, " +
                "cashier_name, cashier_id, pos_user_id, timestamp, amount_received, change, gpi, ebt_fee, " +
                "voided, voided_at, voided_by, void_reason, synced, created_at) " +
                "KEY (sale_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)";
        String deleteItemsSql = "DELETE FROM sale_items WHERE sale_id = ?";
        String insertItemSql = "INSERT INTO sale_items " +
                "(sale_id, product_id, sku, name, price, quantity, subtotal, discount, gpi, " +
                "department_id, department_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String deletePaymentsSql = "DELETE FROM sale_payments WHERE sale_id = ?";
        String insertPaymentSql = "INSERT INTO sale_payments (sale_id, payment_method, amount) VALUES (?, ?, ?)";

        int stored = 0;
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement saleStmt = conn.prepareStatement(saleSql);
                 PreparedStatement delItems = conn.prepareStatement(deleteItemsSql);
                 PreparedStatement insItem = conn.prepareStatement(insertItemSql);
                 PreparedStatement delPays = conn.prepareStatement(deletePaymentsSql);
                 PreparedStatement insPay = conn.prepareStatement(insertPaymentSql)) {

                for (BackfillResponse.Sale s : sales) {
                    if (s.saleId == null) {
                        continue;
                    }
                    try {
                        saleStmt.setString(1, s.id != null ? s.id : s.saleId);
                        saleStmt.setString(2, s.saleId);
                        saleStmt.setBigDecimal(3, s.subtotal);
                        saleStmt.setBigDecimal(4, s.discount);
                        saleStmt.setBigDecimal(5, s.tax);
                        saleStmt.setBigDecimal(6, s.total);
                        saleStmt.setString(7, s.paymentMethod);
                        saleStmt.setBoolean(8, Boolean.TRUE.equals(s.isSplitPayment));
                        saleStmt.setString(9, s.cashierName);
                        saleStmt.setString(10, s.cashierId);
                        saleStmt.setString(11, s.posUserId);
                        saleStmt.setString(12, s.timestamp);
                        saleStmt.setBigDecimal(13, s.amountReceived);
                        saleStmt.setBigDecimal(14, s.change);
                        saleStmt.setBigDecimal(15, s.gpi != null ? s.gpi : BigDecimal.ZERO);
                        saleStmt.setBigDecimal(16, s.ebtFee != null ? s.ebtFee : BigDecimal.ZERO);
                        saleStmt.setBoolean(17, Boolean.TRUE.equals(s.voided));
                        saleStmt.setTimestamp(18, toTimestamp(s.voidedAt));
                        saleStmt.setString(19, s.voidedBy);
                        saleStmt.setString(20, s.voidReason);
                        saleStmt.setTimestamp(21, toTimestamp(firstNonNull(s.createdAt, s.timestamp)));
                        saleStmt.executeUpdate();

                        delItems.setString(1, s.saleId);
                        delItems.executeUpdate();
                        if (s.items != null) {
                            for (BackfillResponse.SaleItem it : s.items) {
                                insItem.setString(1, s.saleId);
                                insItem.setString(2, it.productId);
                                insItem.setString(3, it.sku);
                                insItem.setString(4, it.name);
                                insItem.setBigDecimal(5, it.price);
                                insItem.setObject(6, it.quantity);
                                insItem.setBigDecimal(7, it.subtotal);
                                insItem.setBigDecimal(8, it.discount != null ? it.discount : BigDecimal.ZERO);
                                insItem.setBigDecimal(9, BigDecimal.ZERO);
                                insItem.setString(10, it.departmentId);
                                insItem.setString(11, it.departmentName);
                                insItem.addBatch();
                            }
                            insItem.executeBatch();
                        }

                        delPays.setString(1, s.saleId);
                        delPays.executeUpdate();
                        if (s.payments != null) {
                            for (BackfillResponse.SalePayment p : s.payments) {
                                insPay.setString(1, s.saleId);
                                insPay.setString(2, p.paymentMethod);
                                insPay.setBigDecimal(3, p.amount);
                                insPay.addBatch();
                            }
                            insPay.executeBatch();
                        }

                        stored++;
                    } catch (SQLException e) {
                        logger.warn("Failed to store sale {}: {}", s.saleId, e.getMessage());
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        return stored;
    }

    // ------------------------------------------------------------------
    // Refunds (+ items)
    // ------------------------------------------------------------------

    private int storeRefunds(List<BackfillResponse.Refund> refunds) throws SQLException {
        if (refunds == null || refunds.isEmpty()) {
            return 0;
        }

        String refundSql = "MERGE INTO refunds " +
                "(id, refund_id, original_sale_id, refund_amount, refund_tax, total_refund, " +
                "payment_method, refund_method, reason, cashier_name, pos_user_id, timestamp, synced, created_at) " +
                "KEY (refund_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)";
        String deleteItemsSql = "DELETE FROM refund_items WHERE refund_id = ?";
        String insertItemSql = "INSERT INTO refund_items " +
                "(refund_id, product_id, sku, name, original_price, original_quantity, refund_quantity, refund_amount) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        int stored = 0;
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement refundStmt = conn.prepareStatement(refundSql);
                 PreparedStatement delItems = conn.prepareStatement(deleteItemsSql);
                 PreparedStatement insItem = conn.prepareStatement(insertItemSql)) {

                for (BackfillResponse.Refund r : refunds) {
                    if (r.refundId == null) {
                        continue;
                    }
                    try {
                        refundStmt.setString(1, r.id != null ? r.id : r.refundId);
                        refundStmt.setString(2, r.refundId);
                        refundStmt.setString(3, r.originalSaleId != null ? r.originalSaleId : "");
                        refundStmt.setBigDecimal(4, r.refundAmount);
                        refundStmt.setBigDecimal(5, r.refundTax);
                        refundStmt.setBigDecimal(6, r.totalRefund);
                        refundStmt.setString(7, r.paymentMethod);
                        refundStmt.setString(8, r.refundMethod != null ? r.refundMethod : r.paymentMethod);
                        refundStmt.setString(9, null);
                        refundStmt.setString(10, r.cashierName);
                        refundStmt.setString(11, r.posUserId);
                        refundStmt.setString(12, r.timestamp);
                        refundStmt.setTimestamp(13, toTimestamp(firstNonNull(r.createdAt, r.timestamp)));
                        refundStmt.executeUpdate();

                        delItems.setString(1, r.refundId);
                        delItems.executeUpdate();
                        if (r.items != null) {
                            for (BackfillResponse.RefundItem it : r.items) {
                                insItem.setString(1, r.refundId);
                                insItem.setString(2, it.productId);
                                insItem.setString(3, it.sku);
                                insItem.setString(4, it.name);
                                insItem.setBigDecimal(5, it.originalPrice);
                                insItem.setObject(6, it.originalQuantity);
                                insItem.setObject(7, it.refundQuantity);
                                insItem.setBigDecimal(8, it.refundAmount);
                                insItem.addBatch();
                            }
                            insItem.executeBatch();
                        }
                        stored++;
                    } catch (SQLException e) {
                        logger.warn("Failed to store refund {}: {}", r.refundId, e.getMessage());
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        return stored;
    }

    // ------------------------------------------------------------------
    // Shifts (+ cash operations)
    // ------------------------------------------------------------------

    /** @return [shiftsStored, cashOperationsStored] */
    private int[] storeShifts(List<BackfillResponse.Shift> shifts) throws SQLException {
        if (shifts == null || shifts.isEmpty()) {
            return new int[] { 0, 0 };
        }

        String storeId = config.getProperty("store.id");

        String shiftSql = "MERGE INTO shifts " +
                "(id, shift_id, store_id, cashier_name, cashier_id, register_id, shift_number, " +
                "shift_started_at, shift_ended_at, status, opening_cash, opening_note, expected_cash, " +
                "actual_cash, cash_difference, closing_note, total_cash_sales, total_card_sales, " +
                "total_ebt_sales, total_other_sales, transaction_count, gross_sales, net_sales, " +
                "total_discounts, total_tax, synced, created_at, updated_at) " +
                "KEY (shift_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)";
        String opSql = "MERGE INTO cash_operations " +
                "(id, shift_id, type, amount, note, performed_by, verified_by, created_at, synced) " +
                "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE)";

        int shiftsStored = 0;
        int opsStored = 0;
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement shiftStmt = conn.prepareStatement(shiftSql);
                 PreparedStatement opStmt = conn.prepareStatement(opSql)) {

                for (BackfillResponse.Shift sh : shifts) {
                    if (sh.shiftId == null) {
                        continue;
                    }
                    try {
                        shiftStmt.setString(1, sh.id != null ? sh.id : sh.shiftId);
                        shiftStmt.setString(2, sh.shiftId);
                        shiftStmt.setString(3, storeId);
                        shiftStmt.setString(4, sh.cashierName);
                        shiftStmt.setString(5, sh.cashierId);
                        shiftStmt.setString(6, sh.registerId);
                        shiftStmt.setObject(7, sh.shiftNumber);
                        shiftStmt.setString(8, sh.shiftStartedAt);
                        shiftStmt.setString(9, sh.shiftEndedAt);
                        shiftStmt.setString(10, sh.status);
                        shiftStmt.setBigDecimal(11, nz(sh.openingCash));
                        shiftStmt.setString(12, sh.openingNote);
                        shiftStmt.setBigDecimal(13, sh.expectedCash);
                        shiftStmt.setBigDecimal(14, sh.actualCash);
                        shiftStmt.setBigDecimal(15, sh.cashDifference);
                        shiftStmt.setString(16, sh.closingNote);
                        shiftStmt.setBigDecimal(17, nz(sh.totalCashSales));
                        shiftStmt.setBigDecimal(18, nz(sh.totalCardSales));
                        shiftStmt.setBigDecimal(19, nz(sh.totalEbtSales));
                        shiftStmt.setBigDecimal(20, nz(sh.totalOtherSales));
                        shiftStmt.setObject(21, sh.transactionCount != null ? sh.transactionCount : 0);
                        shiftStmt.setBigDecimal(22, nz(sh.grossSales));
                        shiftStmt.setBigDecimal(23, nz(sh.netSales));
                        shiftStmt.setBigDecimal(24, nz(sh.totalDiscounts));
                        shiftStmt.setBigDecimal(25, nz(sh.totalTax));
                        shiftStmt.setTimestamp(26, toTimestamp(sh.createdAt));
                        shiftStmt.setTimestamp(27, toTimestamp(firstNonNull(sh.updatedAt, sh.createdAt)));
                        shiftStmt.executeUpdate();
                        shiftsStored++;

                        if (sh.cashOperations != null) {
                            for (BackfillResponse.CashOperation op : sh.cashOperations) {
                                if (op.id == null) {
                                    continue;
                                }
                                opStmt.setString(1, op.id);
                                opStmt.setString(2, op.shiftId != null ? op.shiftId : sh.shiftId);
                                opStmt.setString(3, op.type);
                                opStmt.setBigDecimal(4, nz(op.amount));
                                opStmt.setString(5, op.note);
                                opStmt.setString(6, op.performedBy);
                                opStmt.setString(7, op.verifiedBy);
                                opStmt.setString(8, op.createdAt);
                                opStmt.executeUpdate();
                                opsStored++;
                            }
                        }
                    } catch (SQLException e) {
                        logger.warn("Failed to store shift {}: {}", sh.shiftId, e.getMessage());
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        return new int[] { shiftsStored, opsStored };
    }

    // ------------------------------------------------------------------
    // Vendor payouts (+ items)
    // ------------------------------------------------------------------

    private int storeVendorPayouts(List<BackfillResponse.VendorPayout> payouts) throws SQLException {
        if (payouts == null || payouts.isEmpty()) {
            return 0;
        }

        String payoutSql = "MERGE INTO vendor_payouts " +
                "(id, vendor_id, vendor_name, period_start, period_end, total_sales, total_cost, " +
                "total_payout, commission_rate, item_count, transaction_count, status, paid_at, paid_by, " +
                "payment_method, payment_reference, notes, synced, created_at, updated_at) " +
                "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)";
        String deleteItemsSql = "DELETE FROM vendor_payout_items WHERE payout_id = ?";
        String insertItemSql = "INSERT INTO vendor_payout_items " +
                "(id, payout_id, sale_id, sale_item_id, product_id, product_name, product_sku, quantity, unit_price) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        int stored = 0;
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement payoutStmt = conn.prepareStatement(payoutSql);
                 PreparedStatement delItems = conn.prepareStatement(deleteItemsSql);
                 PreparedStatement insItem = conn.prepareStatement(insertItemSql)) {

                for (BackfillResponse.VendorPayout p : payouts) {
                    if (p.id == null) {
                        continue;
                    }
                    if (p.vendorId == null || p.vendorId.isBlank()) {
                        // vendor_id is NOT NULL with a FK to vendors(id); a payout without a
                        // resolvable vendor cannot be stored locally.
                        logger.warn("Skipping vendor payout {} with no vendor id", p.id);
                        continue;
                    }
                    try {
                        payoutStmt.setString(1, p.id);
                        payoutStmt.setString(2, p.vendorId);
                        payoutStmt.setString(3, p.vendorName);
                        payoutStmt.setString(4, p.periodStart);
                        payoutStmt.setString(5, p.periodEnd);
                        payoutStmt.setBigDecimal(6, nz(p.totalSales));
                        payoutStmt.setBigDecimal(7, nz(p.totalCost));
                        payoutStmt.setBigDecimal(8, nz(p.totalPayout));
                        payoutStmt.setBigDecimal(9, nz(p.commissionRate));
                        payoutStmt.setObject(10, p.itemCount != null ? p.itemCount : 0);
                        payoutStmt.setObject(11, p.transactionCount != null ? p.transactionCount : 0);
                        payoutStmt.setString(12, p.status != null ? p.status : "PENDING");
                        payoutStmt.setString(13, p.paidAt);
                        payoutStmt.setString(14, p.paidBy);
                        payoutStmt.setString(15, p.paymentMethod);
                        payoutStmt.setString(16, p.paymentReference);
                        payoutStmt.setString(17, p.notes);
                        payoutStmt.setTimestamp(18, toTimestamp(p.createdAt));
                        payoutStmt.setString(19, p.updatedAt);
                        payoutStmt.executeUpdate();

                        delItems.setString(1, p.id);
                        delItems.executeUpdate();
                        if (p.items != null) {
                            for (BackfillResponse.VendorPayoutItem it : p.items) {
                                insItem.setString(1, it.id);
                                insItem.setString(2, p.id);
                                insItem.setString(3, it.saleId);
                                insItem.setObject(4, it.saleItemId);
                                insItem.setString(5, it.productId);
                                insItem.setString(6, it.productName);
                                insItem.setString(7, it.productSku);
                                insItem.setObject(8, it.quantity != null ? it.quantity : 0);
                                insItem.setBigDecimal(9, nz(it.unitPrice));
                                insItem.addBatch();
                            }
                            insItem.executeBatch();
                        }
                        stored++;
                    } catch (SQLException e) {
                        logger.warn("Failed to store vendor payout {}: {}", p.id, e.getMessage());
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        return stored;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    /** Parse a backend ISO-8601 instant ("2026-06-01T12:34:56.789Z") into a SQL timestamp. */
    private static Timestamp toTimestamp(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return new Timestamp(Instant.parse(iso).toEpochMilli());
        } catch (Exception e) {
            logger.debug("Could not parse timestamp '{}': {}", iso, e.getMessage());
            return null;
        }
    }
}
