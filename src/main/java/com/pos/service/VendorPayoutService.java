package com.pos.service;

import com.pos.database.DatabaseManager;
import com.pos.sync.SyncManager;
import com.pos.model.Vendor;
import com.pos.service.UserAuthService;
import com.pos.model.VendorPayout;
import com.pos.model.VendorPayout.PayoutStatus;
import com.pos.model.VendorPayoutItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Service for calculating and managing vendor payouts based on sales.
 * Tracks what is owed to vendors from product sales.
 */
public class VendorPayoutService {

    /**
     * True when a paid vendor payout came out of the register cash drawer.
     * Matches EOD reconciliation. "CASHIER" is included because some stores mis-save
     * the payer role as the payment method; CHEQUE/INVOICE/CREDIT are not drawer cash.
     */
    public static boolean isDrawerCashPayoutMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return false;
        }
        String m = paymentMethod.trim().toUpperCase(Locale.ROOT);
        return "CASH".equals(m) || "CASHIER".equals(m);
    }

    private static final Logger logger = LoggerFactory.getLogger(VendorPayoutService.class);
    private static VendorPayoutService instance;
    private final DatabaseManager dbManager;
    private final VendorService vendorService;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_ONLY_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private VendorPayoutService() {
        this.dbManager = DatabaseManager.getInstance();
        this.vendorService = VendorService.getInstance();
    }

    public static synchronized VendorPayoutService getInstance() {
        if (instance == null) {
            instance = new VendorPayoutService();
        }
        return instance;
    }

    /**
     * Calculate payout for a specific vendor for a date range.
     * This aggregates all sales of the vendor's products within the period.
     * 
     * @param vendorId  The vendor ID
     * @param startDate Start date of the period
     * @param endDate   End date of the period
     * @return VendorPayout with calculated amounts and line items
     */
    public VendorPayout calculatePayout(String vendorId, LocalDate startDate, LocalDate endDate) {
        Vendor vendor = vendorService.getVendorById(vendorId);
        if (vendor == null) {
            logger.warn("Vendor not found: {}", vendorId);
            return null;
        }

        try (Connection conn = dbManager.getConnection()) {
            return calculatePayout(conn, vendor, startDate, endDate);
        } catch (SQLException e) {
            logger.error("Error calculating vendor payout", e);
            throw new RuntimeException("Failed to calculate vendor payout", e);
        }
    }

    private VendorPayout calculatePayout(Connection conn, Vendor vendor, LocalDate startDate, LocalDate endDate) {
        String vendorId = vendor.getId();
        VendorPayout payout = new VendorPayout();
        payout.setId(UUID.randomUUID().toString());
        payout.setVendorId(vendorId);
        payout.setVendorName(vendor.getName());
        payout.setPeriodStart(startDate.format(DATE_ONLY_FORMATTER));
        payout.setPeriodEnd(endDate.format(DATE_ONLY_FORMATTER));
        payout.setCommissionRate(vendor.getCommissionRate());
        payout.setCreatedAt(LocalDateTime.now().format(DATE_FORMATTER));
        payout.setStatus(PayoutStatus.PENDING);

        // Query sales items for this vendor's products within the date range
        String sql = """
                    SELECT
                        si.id as sale_item_id,
                        s.sale_id,
                        s.timestamp as sale_date,
                        s.payment_method,
                        si.product_id,
                        si.name as product_name,
                        si.sku as product_sku,
                        si.quantity,
                        si.price as unit_price,
                        COALESCE(si.cost, p.cost, 0) as unit_cost,
                        si.subtotal as sale_amount,
                        COALESCE(si.discount, 0) as discount_amount
                    FROM sale_items si
                    INNER JOIN sales s ON si.sale_id = s.sale_id
                    LEFT JOIN products p ON si.product_id = p.id
                    WHERE (si.vendor_id = ? OR p.vendor_id = ?)
                    AND s.timestamp >= ? AND s.timestamp < ?
                    AND s.voided = FALSE
                    ORDER BY s.timestamp
                """;

        List<VendorPayoutItem> items = new ArrayList<>();
        Set<String> uniqueSaleIds = new HashSet<>();
        BigDecimal totalSales = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalPayout = BigDecimal.ZERO;
        int totalItemCount = 0;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, vendorId);
            stmt.setString(2, vendorId);
            stmt.setString(3, startDate.atStartOfDay().format(DATE_FORMATTER));
            stmt.setString(4, endDate.plusDays(1).atStartOfDay().format(DATE_FORMATTER));

            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                VendorPayoutItem item = new VendorPayoutItem();
                item.setId(UUID.randomUUID().toString());
                item.setPayoutId(payout.getId());
                item.setSaleId(rs.getString("sale_id"));
                item.setSaleItemId(rs.getInt("sale_item_id"));
                item.setProductId(rs.getString("product_id"));
                item.setProductName(rs.getString("product_name"));
                item.setProductSku(rs.getString("product_sku"));
                item.setQuantity(rs.getInt("quantity"));
                item.setUnitPrice(rs.getBigDecimal("unit_price"));
                item.setUnitCost(rs.getBigDecimal("unit_cost"));
                item.setSaleAmount(rs.getBigDecimal("sale_amount"));
                item.setDiscountAmount(rs.getBigDecimal("discount_amount"));
                item.setSaleDate(rs.getString("sale_date"));
                item.setPaymentMethod(rs.getString("payment_method"));
                item.setCommissionRate(vendor.getCommissionRate());

                // Calculate cost amount and payout amount
                BigDecimal costAmount = item.getUnitCost()
                        .multiply(BigDecimal.valueOf(item.getQuantity()));
                item.setCostAmount(costAmount);

                // Payout is based on cost price (what vendor should receive for goods)
                // This is the most common model: vendor gets paid their cost price
                item.setPayoutAmount(costAmount);

                items.add(item);
                uniqueSaleIds.add(item.getSaleId());

                totalSales = totalSales.add(item.getSaleAmount());
                totalCost = totalCost.add(costAmount);
                totalPayout = totalPayout.add(costAmount);
                totalItemCount += item.getQuantity();
            }

        } catch (SQLException e) {
            logger.error("Error calculating vendor payout", e);
            throw new RuntimeException("Failed to calculate vendor payout", e);
        }

        payout.setItems(items);
        payout.setTotalSales(totalSales);
        payout.setTotalCost(totalCost);
        payout.setTotalPayout(totalPayout);
        payout.setItemCount(totalItemCount);
        payout.setTransactionCount(uniqueSaleIds.size());

        logger.info("Calculated payout for vendor {}: {} items, {} transactions, ${} payout",
                vendor.getName(), totalItemCount, uniqueSaleIds.size(), totalPayout);

        return payout;
    }

    /**
     * Calculate payouts for all vendors with sales in the date range
     */
    public List<VendorPayout> calculateAllVendorPayouts(LocalDate startDate, LocalDate endDate) {
        List<VendorPayout> payouts = new ArrayList<>();

        // Optimized single query to fetch all relevant sales data ordered by vendor
        String sql = """
                    SELECT
                        COALESCE(si.vendor_id, p.vendor_id) as vendor_id,
                        v.name as vendor_name,
                        v.commission_rate,
                        si.id as sale_item_id,
                        s.sale_id,
                        s.timestamp as sale_date,
                        s.payment_method,
                        si.product_id,
                        si.name as product_name,
                        si.sku as product_sku,
                        si.quantity,
                        si.price as unit_price,
                        COALESCE(si.cost, p.cost, 0) as unit_cost,
                        si.subtotal as sale_amount,
                        COALESCE(si.discount, 0) as discount_amount
                    FROM sale_items si
                    INNER JOIN sales s ON si.sale_id = s.sale_id
                    LEFT JOIN products p ON si.product_id = p.id
                    INNER JOIN vendors v ON COALESCE(si.vendor_id, p.vendor_id) = v.id
                    WHERE (si.vendor_id IS NOT NULL OR p.vendor_id IS NOT NULL)
                    AND s.timestamp >= ? AND s.timestamp < ?
                    AND s.voided = FALSE
                    ORDER BY vendor_id, s.timestamp
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, startDate.atStartOfDay().format(DATE_FORMATTER));
            stmt.setString(2, endDate.plusDays(1).atStartOfDay().format(DATE_FORMATTER));

            ResultSet rs = stmt.executeQuery();

            String currentVendorId = null;
            VendorPayout currentPayout = null;
            List<VendorPayoutItem> currentItems = null;
            Set<String> currentUniqueSaleIds = null;

            while (rs.next()) {
                String vendorId = rs.getString("vendor_id");

                // If vendor changed, finalize current payout and start new one
                if (currentVendorId == null || !currentVendorId.equals(vendorId)) {
                    if (currentPayout != null) {
                        finalizePayout(currentPayout, currentItems, currentUniqueSaleIds);
                        if (currentPayout.getTotalPayout().compareTo(BigDecimal.ZERO) > 0) {
                            payouts.add(currentPayout);
                        }
                    }

                    currentVendorId = vendorId;
                    currentPayout = new VendorPayout();
                    currentPayout.setId(UUID.randomUUID().toString());
                    currentPayout.setVendorId(vendorId);
                    currentPayout.setVendorName(rs.getString("vendor_name"));
                    currentPayout.setPeriodStart(startDate.format(DATE_ONLY_FORMATTER));
                    currentPayout.setPeriodEnd(endDate.format(DATE_ONLY_FORMATTER));
                    currentPayout.setCommissionRate(rs.getBigDecimal("commission_rate"));
                    currentPayout.setCreatedAt(LocalDateTime.now().format(DATE_FORMATTER));
                    currentPayout.setStatus(PayoutStatus.PENDING);

                    if (currentPayout.getCommissionRate() == null) {
                        currentPayout.setCommissionRate(BigDecimal.ZERO);
                    }

                    currentItems = new ArrayList<>();
                    currentUniqueSaleIds = new HashSet<>();
                }

                // Process item
                VendorPayoutItem item = new VendorPayoutItem();
                item.setId(UUID.randomUUID().toString());
                item.setPayoutId(currentPayout.getId());
                item.setSaleId(rs.getString("sale_id"));
                item.setSaleItemId(rs.getInt("sale_item_id"));
                item.setProductId(rs.getString("product_id"));
                item.setProductName(rs.getString("product_name"));
                item.setProductSku(rs.getString("product_sku"));
                item.setQuantity(rs.getInt("quantity"));
                item.setUnitPrice(rs.getBigDecimal("unit_price"));
                item.setUnitCost(rs.getBigDecimal("unit_cost"));
                item.setSaleAmount(rs.getBigDecimal("sale_amount"));
                item.setDiscountAmount(rs.getBigDecimal("discount_amount"));
                item.setSaleDate(rs.getString("sale_date"));
                item.setPaymentMethod(rs.getString("payment_method"));
                item.setCommissionRate(currentPayout.getCommissionRate());

                // Calculate cost amount and payout amount
                BigDecimal costAmount = item.getUnitCost()
                        .multiply(BigDecimal.valueOf(item.getQuantity()));
                item.setCostAmount(costAmount);
                item.setPayoutAmount(costAmount);

                currentItems.add(item);
                currentUniqueSaleIds.add(item.getSaleId());
            }

            // Add last payout
            if (currentPayout != null) {
                finalizePayout(currentPayout, currentItems, currentUniqueSaleIds);
                if (currentPayout.getTotalPayout().compareTo(BigDecimal.ZERO) > 0) {
                    payouts.add(currentPayout);
                }
            }

        } catch (SQLException e) {
            logger.error("Error calculating all vendor payouts", e);
        }

        logger.info("Calculated payouts for {} vendors", payouts.size());
        return payouts;
    }

    private void finalizePayout(VendorPayout payout, List<VendorPayoutItem> items, Set<String> uniqueSaleIds) {
        BigDecimal totalSales = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalPayout = BigDecimal.ZERO;
        int totalItemCount = 0;

        for (VendorPayoutItem item : items) {
            totalSales = totalSales.add(item.getSaleAmount());
            totalCost = totalCost.add(item.getCostAmount());
            totalPayout = totalPayout.add(item.getPayoutAmount());
            totalItemCount += item.getQuantity();
        }

        payout.setItems(items);
        payout.setTotalSales(totalSales);
        payout.setTotalCost(totalCost);
        payout.setTotalPayout(totalPayout);
        payout.setItemCount(totalItemCount);
        payout.setTransactionCount(uniqueSaleIds.size());
    }

    /**
     * Save a calculated payout to the database
     */
    public VendorPayout savePayout(VendorPayout payout) {
        if (payout.getId() == null || payout.getId().isEmpty()) {
            payout.setId(UUID.randomUUID().toString());
        }

        String now = LocalDateTime.now().format(DATE_FORMATTER);
        payout.setCreatedAt(now);
        payout.setUpdatedAt(now);

        String payoutSql = """
                    INSERT INTO vendor_payouts (id, vendor_id, vendor_name, period_start, period_end,
                        total_sales, total_cost, total_payout, commission_rate, item_count, transaction_count,
                        status, paid_at, paid_by, payment_method, payment_reference, notes, synced, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        String itemSql = """
                    INSERT INTO vendor_payout_items (id, payout_id, sale_id, sale_item_id, product_id,
                        product_name, product_sku, quantity, unit_price, unit_cost, sale_amount, cost_amount,
                        discount_amount, commission_rate, payout_amount, sale_date, payment_method)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement payoutStmt = conn.prepareStatement(payoutSql)) {
                payoutStmt.setString(1, payout.getId());
                payoutStmt.setString(2, payout.getVendorId());
                payoutStmt.setString(3, payout.getVendorName());
                payoutStmt.setString(4, payout.getPeriodStart());
                payoutStmt.setString(5, payout.getPeriodEnd());
                payoutStmt.setBigDecimal(6, payout.getTotalSales());
                payoutStmt.setBigDecimal(7, payout.getTotalCost());
                payoutStmt.setBigDecimal(8, payout.getTotalPayout());
                payoutStmt.setBigDecimal(9, payout.getCommissionRate());
                payoutStmt.setInt(10, payout.getItemCount());
                payoutStmt.setInt(11, payout.getTransactionCount());
                payoutStmt.setString(12, payout.getStatus().name());
                payoutStmt.setString(13, payout.getPaidAt());
                payoutStmt.setString(14, payout.getPaidBy());
                payoutStmt.setString(15, payout.getPaymentMethod());
                payoutStmt.setString(16, payout.getPaymentReference());
                payoutStmt.setString(17, payout.getNotes());
                payoutStmt.setBoolean(18, false);
                payoutStmt.setString(19, payout.getCreatedAt());
                payoutStmt.setString(20, payout.getUpdatedAt());

                payoutStmt.executeUpdate();
            }

            // Save payout items
            try (PreparedStatement itemStmt = conn.prepareStatement(itemSql)) {
                for (VendorPayoutItem item : payout.getItems()) {
                    if (item.getId() == null || item.getId().isEmpty()) {
                        item.setId(UUID.randomUUID().toString());
                    }
                    item.setPayoutId(payout.getId());

                    itemStmt.setString(1, item.getId());
                    itemStmt.setString(2, item.getPayoutId());
                    itemStmt.setString(3, item.getSaleId());
                    itemStmt.setInt(4, item.getSaleItemId());
                    itemStmt.setString(5, item.getProductId());
                    itemStmt.setString(6, item.getProductName());
                    itemStmt.setString(7, item.getProductSku());
                    itemStmt.setInt(8, item.getQuantity());
                    itemStmt.setBigDecimal(9, item.getUnitPrice());
                    itemStmt.setBigDecimal(10, item.getUnitCost());
                    itemStmt.setBigDecimal(11, item.getSaleAmount());
                    itemStmt.setBigDecimal(12, item.getCostAmount());
                    itemStmt.setBigDecimal(13, item.getDiscountAmount());
                    itemStmt.setBigDecimal(14, item.getCommissionRate());
                    itemStmt.setBigDecimal(15, item.getPayoutAmount());
                    itemStmt.setString(16, item.getSaleDate());
                    itemStmt.setString(17, item.getPaymentMethod());

                    itemStmt.addBatch();
                }
                itemStmt.executeBatch();
            }

            conn.commit();
            logger.info("Saved payout {} with {} items", payout.getId(), payout.getItems().size());
            return payout;

        } catch (SQLException e) {
            logger.error("Error saving payout", e);
            throw new RuntimeException("Failed to save payout", e);
        }
    }

    /**
     * Mark a payout as paid with full payment details
     */
    public VendorPayout markPayoutAsPaid(String payoutId, String paidBy, String paymentMethod,
            String paymentReference, BigDecimal amountPaid,
            String chequeNumber, String notes) {
        String now = LocalDateTime.now().format(DATE_FORMATTER);

        String sql = """
                    UPDATE vendor_payouts SET
                        status = ?, paid_at = ?, paid_by = ?, payment_method = ?,
                        payment_reference = ?, amount_paid = ?, cheque_number = ?,
                        notes = ?, synced = FALSE, updated_at = ?
                    WHERE id = ?
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, PayoutStatus.PAID.name());
            stmt.setString(2, now);
            stmt.setString(3, paidBy);
            stmt.setString(4, paymentMethod);
            stmt.setString(5, paymentReference);
            stmt.setBigDecimal(6, amountPaid);
            stmt.setString(7, chequeNumber);
            stmt.setString(8, notes);
            stmt.setString(9, now);
            stmt.setString(10, payoutId);

            int updated = stmt.executeUpdate();
            conn.commit();

            if (updated > 0) {
                logger.info("Marked payout {} as paid by {} via {} - Amount: {}, Cheque#: {}",
                        payoutId, paidBy, paymentMethod, amountPaid, chequeNumber);
                return getPayoutById(payoutId);
            }

        } catch (SQLException e) {
            logger.error("Error marking payout as paid", e);
        }

        return null;
    }

    /**
     * Get payout by ID with items
     */
    public VendorPayout getPayoutById(String payoutId) {
        String payoutSql = "SELECT * FROM vendor_payouts WHERE id = ?";
        String itemsSql = "SELECT * FROM vendor_payout_items WHERE payout_id = ? ORDER BY sale_date";

        try (Connection conn = dbManager.getConnection()) {
            VendorPayout payout = null;

            try (PreparedStatement stmt = conn.prepareStatement(payoutSql)) {
                stmt.setString(1, payoutId);
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    payout = mapResultSetToPayout(rs);
                }
            }

            if (payout != null) {
                List<VendorPayoutItem> items = new ArrayList<>();
                try (PreparedStatement stmt = conn.prepareStatement(itemsSql)) {
                    stmt.setString(1, payoutId);
                    ResultSet rs = stmt.executeQuery();

                    while (rs.next()) {
                        items.add(mapResultSetToPayoutItem(rs));
                    }
                }
                payout.setItems(items);
            }

            return payout;

        } catch (SQLException e) {
            logger.error("Error getting payout by ID", e);
        }

        return null;
    }

    /**
     * Get paid vendor payouts for a specific date (based on paid_at timestamp).
     * This is used for EOD reports to show vendor payouts made during the day.
     * 
     * @param date The date to query payouts for
     * @return List of paid payouts for that date
     */
    public List<VendorPayout> getPaidPayoutsForDate(LocalDate date) {
        return getPaidPayoutsForDateRange(date, date);
    }

    /**
     * Get paid vendor payouts for a date range (based on paid_at timestamp).
     * 
     * @param startDate The start date to query
     * @param endDate   The end date to query
     * @return List of paid payouts for that range
     */
    public List<VendorPayout> getPaidPayoutsForDateRange(LocalDate startDate, LocalDate endDate) {
        List<VendorPayout> payouts = new ArrayList<>();

        String startDateStr = startDate.atStartOfDay().format(DATE_FORMATTER);
        String endDateStr = endDate.plusDays(1).atStartOfDay().format(DATE_FORMATTER);

        String sql = """
                    SELECT * FROM vendor_payouts
                    WHERE status = 'PAID'
                    AND paid_at >= ? AND paid_at < ?
                    ORDER BY paid_at ASC
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, startDateStr);
            stmt.setString(2, endDateStr);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                payouts.add(mapResultSetToPayout(rs));
            }

        } catch (SQLException e) {
            logger.error("Error getting paid payouts for range: {} to {}", startDate, endDate, e);
        }

        logger.debug("Found {} paid payouts for range {} to {}", payouts.size(), startDate, endDate);
        return payouts;
    }

    /**
     * Get total cash vendor payouts for a specific date.
     * Only includes payouts made via CASH method (not cheque).
     * 
     * @param date The date to query
     * @return Total cash paid to vendors for that date
     */
    public BigDecimal getTotalCashPayoutsForDate(LocalDate date) {
        return getTotalCashPayoutsForDateRange(date, date);
    }

    /**
     * Get total paid vendor payouts for a date range (all payment methods).
     * 
     * @param startDate The start date to query
     * @param endDate   The end date to query
     * @return Total amount paid to vendors for that range
     */
    public BigDecimal getTotalPaidPayoutsForDateRange(LocalDate startDate, LocalDate endDate) {
        String startDateStr = startDate.atStartOfDay().format(DATE_FORMATTER);
        String endDateStr = endDate.plusDays(1).atStartOfDay().format(DATE_FORMATTER);

        String sql = """
                    SELECT COALESCE(SUM(amount_paid), 0) as total
                    FROM vendor_payouts
                    WHERE status = 'PAID'
                    AND paid_at >= ? AND paid_at < ?
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, startDateStr);
            stmt.setString(2, endDateStr);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getBigDecimal("total");
            }

        } catch (SQLException e) {
            logger.error("Error getting total paid payouts for range: {} to {}", startDate, endDate, e);
        }

        return BigDecimal.ZERO;
    }

    /**
     * Get total cash vendor payouts for a date range.
     * 
     * @param startDate The start date to query
     * @param endDate   The end date to query
     * @return Total cash paid to vendors for that range
     */
    public BigDecimal getTotalCashPayoutsForDateRange(LocalDate startDate, LocalDate endDate) {
        String startDateStr = startDate.atStartOfDay().format(DATE_FORMATTER);
        String endDateStr = endDate.plusDays(1).atStartOfDay().format(DATE_FORMATTER);

        String sql = """
                    SELECT COALESCE(SUM(amount_paid), 0) as total
                    FROM vendor_payouts
                    WHERE status = 'PAID'
                    AND UPPER(TRIM(COALESCE(payment_method, ''))) IN ('CASH', 'CASHIER')
                    AND paid_at >= ? AND paid_at < ?
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, startDateStr);
            stmt.setString(2, endDateStr);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getBigDecimal("total");
            }

        } catch (SQLException e) {
            logger.error("Error getting total cash payouts for range: {} to {}", startDate, endDate, e);
        }

        return BigDecimal.ZERO;
    }



    /**
     * Get paid vendor payouts for a specific shift.
     * 
     * @param shiftId The shift ID
     * @return List of paid payouts for that shift
     */
    public List<VendorPayout> getPayoutsForShift(String shiftId) {
        List<VendorPayout> payouts = new ArrayList<>();

        if (shiftId == null || shiftId.isEmpty()) {
            return payouts;
        }

        String sql = "SELECT * FROM vendor_payouts WHERE shift_id = ? ORDER BY paid_at ASC";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, shiftId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                payouts.add(mapResultSetToPayout(rs));
            }

        } catch (SQLException e) {
            logger.error("Error getting payouts for shift: {}", shiftId, e);
        }

        logger.debug("Found {} payouts for shift {}", payouts.size(), shiftId);
        return payouts;
    }

    /**
     * Get total cash vendor payouts for a specific shift.
     * Only includes payouts made via CASH method (not cheque).
     * 
     * @param shiftId The shift ID
     * @return Total cash paid to vendors during that shift
     */
    public BigDecimal getTotalCashPayoutsForShift(String shiftId) {
        if (shiftId == null || shiftId.isEmpty()) {
            return BigDecimal.ZERO;
        }

        String sql = """
                    SELECT COALESCE(SUM(amount_paid), 0) as total
                    FROM vendor_payouts
                    WHERE status = 'PAID'
                    AND UPPER(TRIM(COALESCE(payment_method, ''))) IN ('CASH', 'CASHIER')
                    AND shift_id = ?
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, shiftId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return rs.getBigDecimal("total");
            }

        } catch (SQLException e) {
            logger.error("Error getting total cash payouts for shift: {}", shiftId, e);
        }

        return BigDecimal.ZERO;
    }

    /**
     * Get all payouts for a vendor
     */
    public List<VendorPayout> getPayoutsForVendor(String vendorId) {
        List<VendorPayout> payouts = new ArrayList<>();
        String sql = "SELECT * FROM vendor_payouts WHERE vendor_id = ? ORDER BY created_at DESC";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, vendorId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                payouts.add(mapResultSetToPayout(rs));
            }

        } catch (SQLException e) {
            logger.error("Error getting payouts for vendor", e);
        }

        return payouts;
    }

    /**
     * Get payouts by status
     */
    public List<VendorPayout> getPayoutsByStatus(PayoutStatus status) {
        List<VendorPayout> payouts = new ArrayList<>();

        String sql = "SELECT * FROM vendor_payouts WHERE status = ? ORDER BY created_at DESC";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, status.name());
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                payouts.add(mapResultSetToPayout(rs));
            }

        } catch (SQLException e) {
            logger.error("Error getting payouts by status", e);
        }

        return payouts;
    }

    /**
     * Get pending payouts (for payment processing)
     */
    public List<VendorPayout> getPendingPayouts() {
        return getPayoutsByStatus(PayoutStatus.PENDING);
    }

    /**
     * Get total pending payout amount
     */
    public BigDecimal getTotalPendingPayoutAmount() {
        String sql = "SELECT COALESCE(SUM(total_payout), 0) FROM vendor_payouts WHERE status = 'PENDING'";

        try (Connection conn = dbManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getBigDecimal(1);
            }

        } catch (SQLException e) {
            logger.error("Error getting total pending payout amount", e);
        }

        return BigDecimal.ZERO;
    }

    /**
     * Get vendor sales summary for a date range (without creating a payout)
     */
    public Map<String, Object> getVendorSalesSummary(String vendorId, LocalDate startDate, LocalDate endDate) {
        Map<String, Object> summary = new HashMap<>();

        String sql = """
                    SELECT
                        COUNT(DISTINCT s.sale_id) as transaction_count,
                        SUM(si.quantity) as total_items,
                        COALESCE(SUM(si.subtotal), 0) as total_sales,
                        COALESCE(SUM(si.quantity * COALESCE(si.cost, p.cost, 0)), 0) as total_cost
                    FROM sale_items si
                    INNER JOIN sales s ON si.sale_id = s.sale_id
                    LEFT JOIN products p ON si.product_id = p.id
                    WHERE (si.vendor_id = ? OR p.vendor_id = ?)
                    AND s.timestamp >= ? AND s.timestamp < ?
                    AND s.voided = FALSE
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, vendorId);
            stmt.setString(2, vendorId);
            stmt.setString(3, startDate.atStartOfDay().format(DATE_FORMATTER));
            stmt.setString(4, endDate.plusDays(1).atStartOfDay().format(DATE_FORMATTER));

            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                summary.put("transactionCount", rs.getInt("transaction_count"));
                summary.put("totalItems", rs.getInt("total_items"));
                summary.put("totalSales", rs.getBigDecimal("total_sales"));
                summary.put("totalCost", rs.getBigDecimal("total_cost"));
            }

        } catch (SQLException e) {
            logger.error("Error getting vendor sales summary", e);
        }

        return summary;
    }

    /**
     * Mark a payout as paid with amount and optional cheque details.
     * 
     * @param payoutId      The payout ID
     * @param paymentMethod The payment method used (CASH, CHEQUE)
     * @param amountPaid    The actual amount paid to vendor
     * @param chequeNumber  The cheque number (required if paymentMethod is CHEQUE)
     * @param notes         Optional notes
     * @return true if successfully marked as paid
     */
    public boolean markAsPaid(String payoutId, String paymentMethod, BigDecimal amountPaid,
            String chequeNumber, String notes) {
        // Get current user's name
        UserAuthService authService = UserAuthService.getInstance();
        String paidBy = authService.isLoggedIn() ? authService.getCurrentUserName() : "POS User";
        String sql = """
                    UPDATE vendor_payouts
                    SET status = ?,
                        paid_at = ?,
                        paid_by = ?,
                        payment_method = ?,
                        amount_paid = ?,
                        cheque_number = ?,
                        notes = ?,
                        synced = FALSE,
                        updated_at = ?
                    WHERE id = ? AND status = 'PENDING'
                """;

        String now = LocalDateTime.now().format(DATE_FORMATTER);

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, PayoutStatus.PAID.name());
            stmt.setString(2, now);
            stmt.setString(3, paidBy);
            stmt.setString(4, paymentMethod);
            stmt.setBigDecimal(5, amountPaid);
            stmt.setString(6, chequeNumber);
            stmt.setString(7, notes);
            stmt.setString(8, now);
            stmt.setString(9, payoutId);

            int updated = stmt.executeUpdate();
            conn.commit();

            if (updated > 0) {
                logger.info("Marked payout {} as PAID (method: {}, amount: {}, cheque#: {})",
                        payoutId, paymentMethod, amountPaid, chequeNumber);
                return true;
            } else {
                logger.warn("Could not mark payout {} as paid - not found or not pending", payoutId);
                return false;
            }

        } catch (SQLException e) {
            logger.error("Error marking payout as paid", e);
            throw new RuntimeException("Failed to mark payout as paid", e);
        }
    }

    /**
     * Save a manual vendor payout entry.
     * This is used when cashier manually enters a payout (not calculated from
     * sales).
     * 
     * @param payout The payout to save with payment details already set
     * @return The saved payout
     */
    public VendorPayout saveManualPayout(VendorPayout payout) {
        if (payout.getId() == null || payout.getId().isEmpty()) {
            payout.setId(UUID.randomUUID().toString());
        }

        if (payout.getShiftId() == null || payout.getShiftId().isBlank()) {
            try {
                com.pos.api.dto.ShiftResponse.ShiftData activeShift = ShiftService.getInstance().getActiveShift();
                if (activeShift != null && activeShift.id != null && !activeShift.id.isBlank()) {
                    payout.setShiftId(activeShift.id);
                }
            } catch (Exception e) {
                logger.debug("Could not attach active shift to manual vendor payout", e);
            }
        }

        String now = LocalDateTime.now().format(DATE_FORMATTER);
        if (payout.getCreatedAt() == null) {
            payout.setCreatedAt(now);
        }
        payout.setUpdatedAt(now);

        String sql = """
                    INSERT INTO vendor_payouts (id, vendor_id, vendor_name, period_start, period_end,
                        total_sales, total_cost, total_payout, commission_rate, item_count, transaction_count,
                        status, paid_at, paid_by, payment_method, payment_reference, amount_paid, cheque_number,
                        shift_id, notes, synced, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, payout.getId());
            stmt.setString(2, payout.getVendorId());
            stmt.setString(3, payout.getVendorName());
            stmt.setString(4, now.substring(0, 10)); // period_start = today
            stmt.setString(5, now.substring(0, 10)); // period_end = today
            stmt.setBigDecimal(6, BigDecimal.ZERO); // total_sales
            stmt.setBigDecimal(7, BigDecimal.ZERO); // total_cost
            stmt.setBigDecimal(8, payout.getAmountPaid()); // total_payout = amount paid
            stmt.setBigDecimal(9, BigDecimal.ZERO); // commission_rate
            stmt.setInt(10, 0); // item_count
            stmt.setInt(11, 0); // transaction_count
            stmt.setString(12, PayoutStatus.PAID.name());
            stmt.setString(13, payout.getPaidAt() != null ? payout.getPaidAt() : now);
            stmt.setString(14, payout.getPaidBy());
            stmt.setString(15, payout.getPaymentMethod());
            stmt.setString(16, payout.getPaymentReference());
            stmt.setBigDecimal(17, payout.getAmountPaid());
            stmt.setString(18, payout.getChequeNumber());
            stmt.setString(19, payout.getShiftId());
            stmt.setString(20, payout.getNotes());
            stmt.setBoolean(21, false);
            stmt.setString(22, payout.getCreatedAt());
            stmt.setString(23, payout.getUpdatedAt());

            stmt.executeUpdate();
            conn.commit();

            logger.info("Saved manual payout {} for vendor {} - {} via {} (cheque#: {})",
                    payout.getId(), payout.getVendorName(), payout.getAmountPaid(),
                    payout.getPaymentMethod(), payout.getChequeNumber());

            return payout;

        } catch (SQLException e) {
            logger.error("Error saving manual payout", e);
            throw new RuntimeException("Failed to save manual payout", e);
        }
    }

    /**
     * Update payment details for an already-paid vendor payout (corrections).
     * Marks the row unsynced for outbound sync.
     */
    public VendorPayout updatePaidPayoutDetails(String payoutId, String vendorId, String vendorName,
            BigDecimal amountPaid, String paymentMethod, String paymentReference, String chequeNumber,
            String notes, String paidAt) {
        if (payoutId == null || payoutId.isBlank()) {
            throw new IllegalArgumentException("payoutId required");
        }
        VendorPayout existing = getPayoutById(payoutId);
        if (existing == null) {
            throw new IllegalStateException("Payout not found");
        }
        if (existing.getStatus() != PayoutStatus.PAID) {
            throw new IllegalStateException("Only paid payouts can be updated this way");
        }
        if (isCashPaymentMethod(existing.getPaymentMethod())) {
            throw new IllegalStateException("Cash vendor payouts cannot be edited; they affect shift drawer cash and reports.");
        }
        if (isCashPaymentMethod(paymentMethod)) {
            throw new IllegalArgumentException("A payout cannot be changed to cash from this screen.");
        }

        String now = LocalDateTime.now().format(DATE_FORMATTER);
        String paidAtValue = paidAt != null && !paidAt.isBlank() ? paidAt : existing.getPaidAt();

        String sql = """
                    UPDATE vendor_payouts SET
                        vendor_id = ?,
                        vendor_name = ?,
                        total_payout = ?,
                        amount_paid = ?,
                        payment_method = ?,
                        payment_reference = ?,
                        cheque_number = ?,
                        notes = ?,
                        paid_at = ?,
                        synced = FALSE,
                        updated_at = ?
                    WHERE id = ? AND status = 'PAID'
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, vendorId);
            stmt.setString(2, vendorName);
            stmt.setBigDecimal(3, amountPaid);
            stmt.setBigDecimal(4, amountPaid);
            stmt.setString(5, paymentMethod);
            stmt.setString(6, paymentReference);
            stmt.setString(7, chequeNumber);
            stmt.setString(8, notes);
            stmt.setString(9, paidAtValue);
            stmt.setString(10, now);
            stmt.setString(11, payoutId);

            int updated = stmt.executeUpdate();
            conn.commit();

            if (updated == 0) {
                throw new IllegalStateException("Payout was not updated (missing or not paid)");
            }

            logger.info("Updated paid payout {} for vendor {}", payoutId, vendorName);
            return getPayoutById(payoutId);

        } catch (SQLException e) {
            logger.error("Error updating paid payout", e);
            throw new RuntimeException("Failed to update payout", e);
        }
    }

    /**
     * Cancel a pending payout.
     * 
     * @param payoutId The payout ID
     * @return true if successfully cancelled
     */
    public boolean cancelPayout(String payoutId) {
        String sql = """
                    UPDATE vendor_payouts
                    SET status = ?,
                        synced = FALSE,
                        updated_at = ?
                    WHERE id = ? AND status = 'PENDING'
                """;

        String now = LocalDateTime.now().format(DATE_FORMATTER);

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, PayoutStatus.CANCELLED.name());
            stmt.setString(2, now);
            stmt.setString(3, payoutId);

            int updated = stmt.executeUpdate();
            conn.commit();

            if (updated > 0) {
                logger.info("Cancelled payout: {}", payoutId);
                return true;
            } else {
                logger.warn("Could not cancel payout {} - not found or not pending", payoutId);
                return false;
            }

        } catch (SQLException e) {
            logger.error("Error cancelling payout", e);
            throw new RuntimeException("Failed to cancel payout", e);
        }
    }

    /**
     * Delete a payout (only if pending)
     */
    public boolean deletePayout(String payoutId) {
        // First check if payout is pending
        VendorPayout payout = getPayoutById(payoutId);
        if (payout == null) {
            logger.warn("Payout not found: {}", payoutId);
            return false;
        }

        if (payout.getStatus() != PayoutStatus.PENDING) {
            logger.warn("Cannot delete non-pending payout: {} (status: {})", payoutId, payout.getStatus());
            return false;
        }

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            // Items will be deleted automatically due to ON DELETE CASCADE
            String sql = "DELETE FROM vendor_payouts WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, payoutId);
                int deleted = stmt.executeUpdate();
                conn.commit();

                if (deleted > 0) {
                    logger.info("Deleted payout: {}", payoutId);
                    return true;
                }
            }

        } catch (SQLException e) {
            logger.error("Error deleting payout", e);
        }

        return false;
    }

    /**
     * Whether a paid payout may be deleted: only if {@code paid_at} falls on today's local calendar date.
     * Same-day removal matches the idea of correcting mistakes before the business day closes.
     */
    public boolean isPaidPayoutDeletableToday(VendorPayout payout) {
        if (payout == null || payout.getStatus() != PayoutStatus.PAID) {
            return false;
        }
        return isPaidAtLocalDateToday(payout.getPaidAt());
    }

    /**
     * Permanently delete a paid vendor payout only when it was recorded today (local date of {@code paid_at}).
     * Cascades line items. Use for same-day correction only; affects shift cash totals when the payout was CASH.
     *
     * @return true if a row was deleted
     */
    public boolean deletePaidPayoutIfSameDay(String payoutId) {
        VendorPayout payout = getPayoutById(payoutId);
        if (payout == null || payout.getStatus() != PayoutStatus.PAID) {
            logger.warn("Cannot delete: payout missing or not PAID: {}", payoutId);
            return false;
        }
        if (!isPaidAtLocalDateToday(payout.getPaidAt())) {
            logger.warn("Cannot delete: payout {} is not from today (paid_at={})", payoutId, payout.getPaidAt());
            return false;
        }

        String sql = "DELETE FROM vendor_payouts WHERE id = ? AND status = 'PAID'";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, payoutId);
            int deleted = stmt.executeUpdate();
            conn.commit();

            if (deleted > 0) {
                logger.info("Deleted same-day paid vendor payout {}", payoutId);
                try {
                    SyncManager.getInstance().triggerOutboundSync();
                } catch (Exception e) {
                    logger.debug("Outbound sync after vendor payout delete", e);
                }
                return true;
            }

        } catch (SQLException e) {
            logger.error("Error deleting paid vendor payout", e);
        }

        return false;
    }

    private static boolean isPaidAtLocalDateToday(String paidAt) {
        LocalDate d = parsePaidAtLocalDate(paidAt);
        return d != null && d.equals(LocalDate.now());
    }

    /**
     * Parse {@code paid_at} from DB (supports "yyyy-MM-dd HH:mm:ss" and ISO local date-time).
     */
    private static LocalDate parsePaidAtLocalDate(String paidAt) {
        if (paidAt == null || paidAt.isBlank()) {
            return null;
        }
        String s = paidAt.trim();
        if (s.length() >= 10) {
            try {
                return LocalDate.parse(s.substring(0, 10), DATE_ONLY_FORMATTER);
            } catch (DateTimeParseException ignored) {
                // fall through
            }
        }
        try {
            return LocalDateTime.parse(s, DATE_FORMATTER).toLocalDate();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate();
        } catch (DateTimeParseException e) {
            logger.debug("Could not parse paid_at as date: {}", paidAt);
            return null;
        }
    }

    /**
     * Get unsynced payouts for backend sync
     */
    public List<VendorPayout> getUnsyncedPayouts() {
        List<VendorPayout> payouts = new ArrayList<>();

        String sql = "SELECT * FROM vendor_payouts WHERE synced = FALSE";

        try (Connection conn = dbManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                VendorPayout payout = mapResultSetToPayout(rs);
                payouts.add(payout);
            }

            // Load items for all payouts in batches to avoid N+1 query
            loadPayoutItemsBatch(payouts);

        } catch (SQLException | RuntimeException e) {
            logger.error("Error getting unsynced payouts", e);
            // Return empty list on error to prevent processing incomplete data
            return new ArrayList<>();
        }

        return payouts;
    }

    /**
     * Load items for a list of payouts in batches
     */
    private void loadPayoutItemsBatch(List<VendorPayout> payouts) {
        if (payouts == null || payouts.isEmpty()) {
            return;
        }

        Map<String, VendorPayout> payoutMap = new HashMap<>();
        List<String> payoutIds = new ArrayList<>();

        for (VendorPayout payout : payouts) {
            payoutMap.put(payout.getId(), payout);
            payoutIds.add(payout.getId());
        }

        // Process in batches of 500
        int batchSize = 500;
        for (int i = 0; i < payoutIds.size(); i += batchSize) {
            int end = Math.min(i + batchSize, payoutIds.size());
            List<String> batchIds = payoutIds.subList(i, end);

            if (batchIds.isEmpty())
                continue;

            StringBuilder sql = new StringBuilder("SELECT * FROM vendor_payout_items WHERE payout_id IN (");
            for (int j = 0; j < batchIds.size(); j++) {
                if (j > 0)
                    sql.append(",");
                sql.append("?");
            }
            sql.append(")");

            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

                for (int j = 0; j < batchIds.size(); j++) {
                    stmt.setString(j + 1, batchIds.get(j));
                }

                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    VendorPayoutItem item = mapResultSetToPayoutItem(rs);
                    VendorPayout payout = payoutMap.get(item.getPayoutId());
                    if (payout != null) {
                        payout.addItem(item);
                    }
                }

            } catch (SQLException e) {
                // Propagate error to avoid silent data corruption
                throw new RuntimeException("Error loading payout items batch", e);
            }
        }
    }

    /**
     * Mark payouts as synced
     */
    public void markPayoutsAsSynced(List<String> payoutIds) {
        if (payoutIds == null || payoutIds.isEmpty()) {
            return;
        }

        String sql = "UPDATE vendor_payouts SET synced = TRUE WHERE id = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (String id : payoutIds) {
                stmt.setString(1, id);
                stmt.addBatch();
            }

            stmt.executeBatch();
            conn.commit();

            logger.info("Marked {} payouts as synced", payoutIds.size());

        } catch (SQLException e) {
            logger.error("Error marking payouts as synced", e);
        }
    }

    private static boolean isCashPaymentMethod(String method) {
        return method != null && "CASH".equalsIgnoreCase(method.trim());
    }

    /**
     * Map ResultSet to VendorPayout
     */
    private VendorPayout mapResultSetToPayout(ResultSet rs) throws SQLException {
        VendorPayout payout = new VendorPayout();
        payout.setId(rs.getString("id"));
        payout.setVendorId(rs.getString("vendor_id"));
        payout.setVendorName(rs.getString("vendor_name"));
        payout.setPeriodStart(rs.getString("period_start"));
        payout.setPeriodEnd(rs.getString("period_end"));
        payout.setTotalSales(rs.getBigDecimal("total_sales"));
        payout.setTotalCost(rs.getBigDecimal("total_cost"));
        payout.setTotalPayout(rs.getBigDecimal("total_payout"));
        payout.setCommissionRate(rs.getBigDecimal("commission_rate"));
        payout.setItemCount(rs.getInt("item_count"));
        payout.setTransactionCount(rs.getInt("transaction_count"));

        String status = rs.getString("status");
        payout.setStatus(status != null ? PayoutStatus.valueOf(status) : PayoutStatus.PENDING);

        payout.setPaidAt(rs.getString("paid_at"));
        payout.setPaidBy(rs.getString("paid_by"));
        payout.setPaymentMethod(rs.getString("payment_method"));
        payout.setPaymentReference(rs.getString("payment_reference"));

        // Read amount_paid and cheque_number with null safety
        BigDecimal amountPaid = rs.getBigDecimal("amount_paid");
        payout.setAmountPaid(amountPaid != null ? amountPaid : BigDecimal.ZERO);
        payout.setChequeNumber(rs.getString("cheque_number"));

        // Read shift_id - may be null for older payouts
        try {
            payout.setShiftId(rs.getString("shift_id"));
        } catch (SQLException e) {
            // Column may not exist in older databases
            logger.debug("shift_id column not found in result set");
        }

        payout.setNotes(rs.getString("notes"));
        payout.setSynced(rs.getBoolean("synced"));
        payout.setCreatedAt(rs.getString("created_at"));
        payout.setUpdatedAt(rs.getString("updated_at"));

        return payout;
    }

    /**
     * Map ResultSet to VendorPayoutItem
     */
    private VendorPayoutItem mapResultSetToPayoutItem(ResultSet rs) throws SQLException {
        VendorPayoutItem item = new VendorPayoutItem();
        item.setId(rs.getString("id"));
        item.setPayoutId(rs.getString("payout_id"));
        item.setSaleId(rs.getString("sale_id"));
        item.setSaleItemId(rs.getInt("sale_item_id"));
        item.setProductId(rs.getString("product_id"));
        item.setProductName(rs.getString("product_name"));
        item.setProductSku(rs.getString("product_sku"));
        item.setQuantity(rs.getInt("quantity"));
        item.setUnitPrice(rs.getBigDecimal("unit_price"));
        item.setUnitCost(rs.getBigDecimal("unit_cost"));
        item.setSaleAmount(rs.getBigDecimal("sale_amount"));
        item.setCostAmount(rs.getBigDecimal("cost_amount"));
        item.setDiscountAmount(rs.getBigDecimal("discount_amount"));
        item.setCommissionRate(rs.getBigDecimal("commission_rate"));
        item.setPayoutAmount(rs.getBigDecimal("payout_amount"));
        item.setSaleDate(rs.getString("sale_date"));
        item.setPaymentMethod(rs.getString("payment_method"));

        return item;
    }
}
