package com.pos.service;

import com.pos.api.dto.ShiftResponse;
import com.pos.database.DatabaseManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShiftCashReconciliationTest {

    private static final String PREFIX = "test-cash-recon-";
    private static final String SHIFT_ID = PREFIX + "shift";
    private static final String SALE_ID = PREFIX + "sale";
    private static final String SALE_ROW_ID = PREFIX + "sale-row";
    private static final String VENDOR_ID = PREFIX + "vendor";
    private static final String VENDOR_PAYOUT_ID = PREFIX + "vendor-payout";
    private static final String EXPENSE_CATEGORY_ID = PREFIX + "expense-category";
    private static final String EXPENSE_ID = PREFIX + "expense";
    private static final String SHIFT_ONE_ID = PREFIX + "shift-1";
    private static final String SHIFT_TWO_ID = PREFIX + "shift-2";

    private DatabaseManager dbManager;
    private ShiftService shiftService;
    private EndOfDayReportService reportService;
    private LocalDate today;
    private String isoNow;

    @Before
    public void setUp() throws Exception {
        dbManager = DatabaseManager.getInstance();
        dbManager.initializeSchema();
        shiftService = ShiftService.getInstance();
        reportService = EndOfDayReportService.getInstance();
        today = LocalDate.now();
        isoNow = today + "T10:00:00Z";

        cleanUpTestData();
        seedShiftData();
    }

    @After
    public void tearDown() throws Exception {
        cleanUpTestData();
    }

    @Test
    public void expectedCashExcludesStartingCashAcrossShiftAndReport() throws Exception {
        BigDecimal expectedCash = shiftService.calculateAvailableCash(SHIFT_ID);
        assertEquals(new BigDecimal("45.00"), expectedCash);

        ShiftResponse.ShiftData closedShift = shiftService.endShift(SHIFT_ID, new BigDecimal("145.00"), "test close");
        assertEquals(new BigDecimal("45.00"), closedShift.expectedCash);
        assertEquals(new BigDecimal("0.00"), closedShift.cashDifference);

        EndOfDayReportService.EndOfDayReport shiftReport = reportService.generateShiftReport(SHIFT_ID, "tester");
        assertEquals(new BigDecimal("100.00"), shiftReport.startingCash);
        assertEquals(new BigDecimal("45.00"), shiftReport.cashExpected);
        assertEquals(new BigDecimal("145.00"), shiftReport.cashActual);
        assertEquals(new BigDecimal("0.00"), shiftReport.cashShortOver);

        EndOfDayReportService.EndOfDayReport dailyReport = reportService.generateReport(today, "tester");
        assertEquals(new BigDecimal("100.00"), dailyReport.startingCash);
        assertEquals(new BigDecimal("45.00"), dailyReport.cashExpected);
        assertEquals(new BigDecimal("145.00"), dailyReport.cashActual);
        assertEquals(new BigDecimal("0.00"), dailyReport.cashShortOver);

        String receiptText = reportService.generateReceiptText(shiftReport);
        assertFalse(receiptText.contains("EXPECTED TOTAL CASH:"));
        assertFalse(receiptText.contains("EXPECTED CASH:         145.00"));
        org.junit.Assert.assertTrue(receiptText.contains("EXPECTED CASH:"));
    }

    @Test
    public void sequentialShiftsAcrossTwoUsersKeepStartingCashOutOfDailyMath() throws Exception {
        cleanUpTestData();

        try (Connection conn = dbManager.getConnection()) {
            insertShift(conn, SHIFT_ONE_ID, "cashier-1", "Cashier One", today + "T08:00:00Z",
                    new BigDecimal("100.00"), new BigDecimal("50.00"));
            insertSale(conn, SHIFT_ONE_ID, PREFIX + "sale-1", PREFIX + "sale-row-1", "cashier-1", "Cashier One",
                    today + "T08:15:00Z", new BigDecimal("50.00"));
            insertCashOperation(conn, PREFIX + "shift1-add", SHIFT_ONE_ID, "ADD", new BigDecimal("20.00"),
                    "cashier-1", today + "T08:30:00Z");
            insertCashOperation(conn, PREFIX + "shift1-drop", SHIFT_ONE_ID, "DROP", new BigDecimal("55.00"),
                    "cashier-1", today + "T08:45:00Z");
            insertVendorPayout(conn, PREFIX + "vendor-payout-1", SHIFT_ONE_ID, today + "T09:00:00Z",
                    new BigDecimal("5.00"));
            insertExpense(conn, PREFIX + "expense-1", SHIFT_ONE_ID, "Cashier One", "cashier-1",
                    today + "T09:15:00Z", new BigDecimal("10.00"));

            insertShift(conn, SHIFT_TWO_ID, "cashier-2", "Cashier Two", today + "T10:00:00Z",
                    new BigDecimal("100.00"), new BigDecimal("30.00"));
            insertSale(conn, SHIFT_TWO_ID, PREFIX + "sale-2", PREFIX + "sale-row-2", "cashier-2", "Cashier Two",
                    today + "T10:15:00Z", new BigDecimal("30.00"));
            insertCashOperation(conn, PREFIX + "shift2-add", SHIFT_TWO_ID, "ADD", new BigDecimal("10.00"),
                    "cashier-2", today + "T10:30:00Z");
            insertCashOperation(conn, PREFIX + "shift2-drop", SHIFT_TWO_ID, "DROP", new BigDecimal("5.00"),
                    "cashier-2", today + "T10:45:00Z");
            insertVendorPayout(conn, PREFIX + "vendor-payout-2", SHIFT_TWO_ID, today + "T11:00:00Z",
                    new BigDecimal("10.00"));
            insertExpense(conn, PREFIX + "expense-2", SHIFT_TWO_ID, "Cashier Two", "cashier-2",
                    today + "T11:15:00Z", new BigDecimal("5.00"));

            conn.commit();
        }

        assertEquals(new BigDecimal("0.00"), shiftService.calculateAvailableCash(SHIFT_ONE_ID));
        assertEquals(new BigDecimal("20.00"), shiftService.calculateAvailableCash(SHIFT_TWO_ID));

        ShiftResponse.ShiftData firstClosed = shiftService.endShift(SHIFT_ONE_ID, new BigDecimal("100.00"), "shift one close");
        ShiftResponse.ShiftData secondClosed = shiftService.endShift(SHIFT_TWO_ID, new BigDecimal("120.00"), "shift two close");

        assertEquals(new BigDecimal("0.00"), firstClosed.expectedCash);
        assertEquals(new BigDecimal("0.00"), firstClosed.cashDifference);
        assertEquals(new BigDecimal("20.00"), secondClosed.expectedCash);
        assertEquals(new BigDecimal("0.00"), secondClosed.cashDifference);

        EndOfDayReportService.EndOfDayReport shiftOneReport = reportService.generateShiftReport(SHIFT_ONE_ID, "tester");
        EndOfDayReportService.EndOfDayReport shiftTwoReport = reportService.generateShiftReport(SHIFT_TWO_ID, "tester");
        EndOfDayReportService.EndOfDayReport dailyReport = reportService.generateReport(today, "tester");
        EndOfDayReportService.EndOfDayReport cashierOneReport = reportService.generateReport(today, "tester", "cashier-1");
        EndOfDayReportService.EndOfDayReport cashierTwoReport = reportService.generateReport(today, "tester", "cashier-2");

        assertEquals(new BigDecimal("100.00"), shiftOneReport.startingCash);
        assertEquals(new BigDecimal("0.00"), shiftOneReport.cashExpected);
        assertEquals(new BigDecimal("100.00"), shiftOneReport.cashActual);
        assertEquals(new BigDecimal("0.00"), shiftOneReport.cashShortOver);

        assertEquals(new BigDecimal("100.00"), shiftTwoReport.startingCash);
        assertEquals(new BigDecimal("20.00"), shiftTwoReport.cashExpected);
        assertEquals(new BigDecimal("120.00"), shiftTwoReport.cashActual);
        assertEquals(new BigDecimal("0.00"), shiftTwoReport.cashShortOver);

        assertEquals(new BigDecimal("100.00"), dailyReport.startingCash);
        assertEquals(new BigDecimal("20.00"), dailyReport.cashExpected);
        assertEquals(new BigDecimal("120.00"), dailyReport.cashActual);
        assertEquals(new BigDecimal("0.00"), dailyReport.cashShortOver);

        assertEquals(new BigDecimal("100.00"), cashierOneReport.startingCash);
        assertEquals(new BigDecimal("0.00"), cashierOneReport.cashExpected);
        assertEquals(new BigDecimal("100.00"), cashierOneReport.cashActual);
        assertEquals(new BigDecimal("0.00"), cashierOneReport.cashShortOver);

        assertEquals(new BigDecimal("100.00"), cashierTwoReport.startingCash);
        assertEquals(new BigDecimal("20.00"), cashierTwoReport.cashExpected);
        assertEquals(new BigDecimal("120.00"), cashierTwoReport.cashActual);
        assertEquals(new BigDecimal("0.00"), cashierTwoReport.cashShortOver);

        String dailyReceiptText = reportService.generateReceiptText(dailyReport);
        assertFalse(dailyReceiptText.contains("EXPECTED TOTAL CASH:"));
        assertFalse(dailyReceiptText.contains("EXPECTED CASH:         220.00"));
    }

    @Test
    public void closedShiftIsIncludedWhenUtcStartDateRollsToNextDay() throws Exception {
        // Reproduces the reported bug: an evening shift in a behind-UTC time zone has a
        // shift_started_at (UTC) that lands on tomorrow, while its sales and the shift row
        // are created today (local). The daily report must still pick up the shift's cash
        // by bucketing on local created_at, not on the UTC shift_started_at.
        cleanUpTestData();

        String shiftId = PREFIX + "tz-shift";
        String utcNextDay = today.plusDays(1) + "T01:00:00Z";
        try (Connection conn = dbManager.getConnection()) {
            // created_at defaults to local CURRENT_TIMESTAMP (today); shift_started_at is
            // forced to tomorrow's UTC date to simulate the time-zone rollover.
            insertShift(conn, shiftId, "cashier-tz", "TZ Cashier", utcNextDay,
                    new BigDecimal("100.00"), new BigDecimal("40.00"));
            insertSale(conn, shiftId, PREFIX + "tz-sale", PREFIX + "tz-sale-row", "cashier-tz",
                    "TZ Cashier", utcNextDay, new BigDecimal("40.00"));
            conn.commit();
        }

        shiftService.endShift(shiftId, new BigDecimal("140.00"), "tz close");

        EndOfDayReportService.EndOfDayReport dailyReport = reportService.generateReport(today, "tester");
        assertEquals(new BigDecimal("100.00"), dailyReport.startingCash);
        assertEquals(new BigDecimal("40.00"), dailyReport.cashExpected);
        assertEquals(new BigDecimal("140.00"), dailyReport.cashActual);
        assertEquals(new BigDecimal("0.00"), dailyReport.cashShortOver);
    }

    @Test
    public void cashRefundReducesExpectedCashForShiftAndDay() throws Exception {
        cleanUpTestData();

        String shiftId = PREFIX + "refund-shift";
        try (Connection conn = dbManager.getConnection()) {
            insertShift(conn, shiftId, "cashier-r", "Refund Cashier", today + "T12:00:00Z",
                    new BigDecimal("100.00"), new BigDecimal("50.00"));
            insertSale(conn, shiftId, PREFIX + "refund-sale", PREFIX + "refund-sale-row", "cashier-r",
                    "Refund Cashier", today + "T12:10:00Z", new BigDecimal("50.00"));
            insertRefund(conn, PREFIX + "refund-row", PREFIX + "refund-id", PREFIX + "refund-sale",
                    "cashier-r", "Refund Cashier", today + "T12:20:00Z", "CASH", new BigDecimal("15.00"));
            conn.commit();
        }

        assertEquals(new BigDecimal("35.00"), shiftService.calculateAvailableCash(shiftId));

        ShiftResponse.ShiftData closedShift = shiftService.endShift(shiftId, new BigDecimal("135.00"), "refund close");
        assertEquals(new BigDecimal("35.00"), closedShift.expectedCash);
        assertEquals(new BigDecimal("0.00"), closedShift.cashDifference);

        EndOfDayReportService.EndOfDayReport shiftReport = reportService.generateShiftReport(shiftId, "tester");
        EndOfDayReportService.EndOfDayReport dailyReport = reportService.generateReport(today, "tester", "cashier-r");

        assertEquals(new BigDecimal("35.00"), shiftReport.cashExpected);
        assertEquals(new BigDecimal("0.00"), shiftReport.cashShortOver);
        assertEquals(new BigDecimal("35.00"), dailyReport.cashExpected);
        assertEquals(new BigDecimal("0.00"), dailyReport.cashShortOver);
    }

    @Test
    public void splitPaymentUsesOnlyCashPortionInExpectedCash() throws Exception {
        cleanUpTestData();

        String shiftId = PREFIX + "split-shift";
        try (Connection conn = dbManager.getConnection()) {
            insertShift(conn, shiftId, "cashier-s", "Split Cashier", today + "T13:00:00Z",
                    new BigDecimal("100.00"), new BigDecimal("20.00"));
            insertSplitSale(conn, shiftId, PREFIX + "split-sale", PREFIX + "split-sale-row", "cashier-s",
                    "Split Cashier", today + "T13:10:00Z", new BigDecimal("50.00"),
                    new BigDecimal("20.00"), new BigDecimal("30.00"));
            conn.commit();
        }

        assertEquals(new BigDecimal("20.00"), shiftService.calculateAvailableCash(shiftId));

        ShiftResponse.ShiftData closedShift = shiftService.endShift(shiftId, new BigDecimal("120.00"), "split close");
        EndOfDayReportService.EndOfDayReport shiftReport = reportService.generateShiftReport(shiftId, "tester");

        assertEquals(new BigDecimal("20.00"), closedShift.expectedCash);
        assertEquals(new BigDecimal("0.00"), closedShift.cashDifference);
        assertEquals(new BigDecimal("20.00"), shiftReport.cashExpected);
        assertTrue(shiftReport.paymentMethods.stream()
                .anyMatch(p -> "CASH".equalsIgnoreCase(p.paymentMethod)
                        && new BigDecimal("20.00").compareTo(p.totalAmount) == 0));
    }

    @Test
    public void cashOperationsOnlyShiftAndOverShortRemainConsistent() throws Exception {
        cleanUpTestData();

        String shiftId = PREFIX + "ops-shift";
        try (Connection conn = dbManager.getConnection()) {
            insertShift(conn, shiftId, "cashier-o", "Ops Cashier", today + "T14:00:00Z",
                    new BigDecimal("100.00"), BigDecimal.ZERO);
            insertCashOperation(conn, PREFIX + "ops-add", shiftId, "ADD", new BigDecimal("40.00"),
                    "cashier-o", today + "T14:10:00Z");
            insertCashOperation(conn, PREFIX + "ops-drop", shiftId, "DROP", new BigDecimal("15.00"),
                    "cashier-o", today + "T14:20:00Z");
            conn.commit();
        }

        assertEquals(new BigDecimal("25.00"), shiftService.calculateAvailableCash(shiftId));

        ShiftResponse.ShiftData closedShift = shiftService.endShift(shiftId, new BigDecimal("130.00"), "ops close");
        EndOfDayReportService.EndOfDayReport shiftReport = reportService.generateShiftReport(shiftId, "tester");

        assertEquals(new BigDecimal("25.00"), closedShift.expectedCash);
        assertEquals(new BigDecimal("5.00"), closedShift.cashDifference);
        assertEquals(new BigDecimal("25.00"), shiftReport.cashExpected);
        assertEquals(new BigDecimal("5.00"), shiftReport.cashShortOver);
    }

    private void seedShiftData() throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            insertShift(conn, SHIFT_ID, "cashier-1", "Test Cashier", isoNow, new BigDecimal("100.00"),
                    new BigDecimal("50.00"));
            insertSale(conn, SHIFT_ID, SALE_ID, SALE_ROW_ID, "cashier-1", "Test Cashier", isoNow, new BigDecimal("50.00"));
            insertCashOperation(conn, PREFIX + "cash-add", SHIFT_ID, "ADD", new BigDecimal("20.00"),
                    "cashier-1", isoNow);
            insertCashOperation(conn, PREFIX + "cash-drop", SHIFT_ID, "DROP", new BigDecimal("10.00"),
                    "cashier-1", isoNow);
            insertVendorPayout(conn, VENDOR_PAYOUT_ID, SHIFT_ID, isoNow, new BigDecimal("5.00"));
            insertExpense(conn, EXPENSE_ID, SHIFT_ID, "Test Cashier", "cashier-1", isoNow, new BigDecimal("10.00"));
            conn.commit();
        }
    }

    private void insertShift(Connection conn, String shiftId, String cashierId, String cashierName, String startedAt,
            BigDecimal openingCash, BigDecimal cashSales) throws SQLException {
        String sql = """
                INSERT INTO shifts (
                    id, shift_id, store_id, cashier_name, cashier_id, register_id, shift_started_at, status,
                    opening_cash, opening_note, expected_cash, actual_cash, cash_difference, closing_note,
                    total_cash_sales, total_card_sales, total_ebt_sales, total_other_sales,
                    transaction_count, gross_sales, net_sales, total_discounts, total_tax, synced
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            int i = 1;
            stmt.setString(i++, shiftId);
            stmt.setString(i++, shiftId);
            stmt.setString(i++, "store-1");
            stmt.setString(i++, cashierName);
            stmt.setString(i++, cashierId);
            stmt.setString(i++, "REG-1");
            stmt.setString(i++, startedAt);
            stmt.setString(i++, "ACTIVE");
            stmt.setBigDecimal(i++, openingCash);
            stmt.setString(i++, "opening");
            stmt.setObject(i++, null);
            stmt.setObject(i++, null);
            stmt.setObject(i++, null);
            stmt.setObject(i++, null);
            stmt.setBigDecimal(i++, cashSales);
            stmt.setBigDecimal(i++, BigDecimal.ZERO);
            stmt.setBigDecimal(i++, BigDecimal.ZERO);
            stmt.setBigDecimal(i++, BigDecimal.ZERO);
            stmt.setInt(i++, 1);
            stmt.setBigDecimal(i++, cashSales);
            stmt.setBigDecimal(i++, cashSales);
            stmt.setBigDecimal(i++, BigDecimal.ZERO);
            stmt.setBigDecimal(i++, BigDecimal.ZERO);
            stmt.setBoolean(i++, false);
            stmt.executeUpdate();
        }
    }

    private void insertSale(Connection conn, String shiftId, String saleId, String saleRowId, String cashierId,
            String cashierName, String timestamp, BigDecimal total) throws SQLException {
        try (PreparedStatement saleStmt = conn.prepareStatement("""
                INSERT INTO sales (
                    id, sale_id, subtotal, discount, tax, total, payment_method, cashier_name, cashier_id,
                    pos_user_id, timestamp, amount_received, change, gpi, ebt_fee, synced, voided, shift_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);
                PreparedStatement itemStmt = conn.prepareStatement("""
                        INSERT INTO sale_items (
                            sale_id, product_id, sku, name, price, quantity, subtotal, discount, gpi, department_id, department_name
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            saleStmt.setString(1, saleRowId);
            saleStmt.setString(2, saleId);
            saleStmt.setBigDecimal(3, total);
            saleStmt.setBigDecimal(4, BigDecimal.ZERO);
            saleStmt.setBigDecimal(5, BigDecimal.ZERO);
            saleStmt.setBigDecimal(6, total);
            saleStmt.setString(7, "CASH");
            saleStmt.setString(8, cashierName);
            saleStmt.setString(9, cashierId);
            saleStmt.setString(10, cashierId);
            saleStmt.setString(11, timestamp);
            saleStmt.setBigDecimal(12, total);
            saleStmt.setBigDecimal(13, BigDecimal.ZERO);
            saleStmt.setBigDecimal(14, BigDecimal.ZERO);
            saleStmt.setBigDecimal(15, BigDecimal.ZERO);
            saleStmt.setBoolean(16, false);
            saleStmt.setBoolean(17, false);
            saleStmt.setString(18, shiftId);
            saleStmt.setTimestamp(19, Timestamp.valueOf(LocalDateTime.now()));
            saleStmt.executeUpdate();

            itemStmt.setString(1, saleId);
            itemStmt.setString(2, PREFIX + "product");
            itemStmt.setString(3, "SKU-1");
            itemStmt.setString(4, "Cash Item");
            itemStmt.setBigDecimal(5, total);
            itemStmt.setInt(6, 1);
            itemStmt.setBigDecimal(7, total);
            itemStmt.setBigDecimal(8, BigDecimal.ZERO);
            itemStmt.setBigDecimal(9, BigDecimal.ZERO);
            itemStmt.setString(10, PREFIX + "dept");
            itemStmt.setString(11, "General");
            itemStmt.executeUpdate();
        }
    }

    private void insertCashOperation(Connection conn, String id, String shiftId, String type, BigDecimal amount,
            String performedBy, String createdAt) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO cash_operations (id, shift_id, type, amount, note, performed_by, created_at, synced)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            stmt.setString(1, id);
            stmt.setString(2, shiftId);
            stmt.setString(3, type);
            stmt.setBigDecimal(4, amount);
            stmt.setString(5, type.toLowerCase());
            stmt.setString(6, performedBy);
            stmt.setString(7, createdAt);
            stmt.setBoolean(8, false);
            stmt.executeUpdate();
        }
    }

    private void insertVendorPayout(Connection conn, String payoutId, String shiftId, String paidAt, BigDecimal amount)
            throws SQLException {
        try (PreparedStatement vendorStmt = conn.prepareStatement("""
                MERGE INTO vendors (id, name, synced)
                KEY (id) VALUES (?, ?, ?)
                """);
                PreparedStatement payoutStmt = conn.prepareStatement("""
                        INSERT INTO vendor_payouts (
                            id, vendor_id, vendor_name, status, paid_at, paid_by, payment_method,
                            payment_reference, amount_paid, cheque_number, shift_id, synced
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            vendorStmt.setString(1, VENDOR_ID);
            vendorStmt.setString(2, "Test Vendor");
            vendorStmt.setBoolean(3, false);
            vendorStmt.executeUpdate();

            payoutStmt.setString(1, payoutId);
            payoutStmt.setString(2, VENDOR_ID);
            payoutStmt.setString(3, "Test Vendor");
            payoutStmt.setString(4, "PAID");
            payoutStmt.setString(5, paidAt);
            payoutStmt.setString(6, "Manager");
            payoutStmt.setString(7, "CASH");
            payoutStmt.setString(8, "REF-1");
            payoutStmt.setBigDecimal(9, amount);
            payoutStmt.setString(10, null);
            payoutStmt.setString(11, shiftId);
            payoutStmt.setBoolean(12, false);
            payoutStmt.executeUpdate();
        }
    }

    private void insertExpense(Connection conn, String expenseId, String shiftId, String createdByName,
            String createdById, String timestamp, BigDecimal amount) throws SQLException {
        try (PreparedStatement categoryStmt = conn.prepareStatement("""
                MERGE INTO expense_categories (id, name, is_system, is_active, display_order)
                KEY (id) VALUES (?, ?, ?, ?, ?)
                """);
                PreparedStatement expenseStmt = conn.prepareStatement("""
                        INSERT INTO expenses (
                            id, expense_id, category_id, category_name, amount, description, payment_method,
                            shift_id, created_by, created_by_name, timestamp, synced, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            categoryStmt.setString(1, EXPENSE_CATEGORY_ID);
            categoryStmt.setString(2, "Till Expense");
            categoryStmt.setBoolean(3, false);
            categoryStmt.setBoolean(4, true);
            categoryStmt.setInt(5, 1);
            categoryStmt.executeUpdate();

            expenseStmt.setString(1, expenseId);
            expenseStmt.setString(2, expenseId);
            expenseStmt.setString(3, EXPENSE_CATEGORY_ID);
            expenseStmt.setString(4, "Till Expense");
            expenseStmt.setBigDecimal(5, amount);
            expenseStmt.setString(6, "cash expense");
            expenseStmt.setString(7, "CASH");
            expenseStmt.setString(8, shiftId);
            expenseStmt.setString(9, createdById);
            expenseStmt.setString(10, createdByName);
            expenseStmt.setString(11, timestamp);
            expenseStmt.setBoolean(12, false);
            expenseStmt.setTimestamp(13, Timestamp.valueOf(LocalDateTime.now()));
            expenseStmt.executeUpdate();
        }
    }

    private void insertRefund(Connection conn, String rowId, String refundId, String originalSaleId, String posUserId,
            String cashierName, String timestamp, String refundMethod, BigDecimal totalRefund) throws SQLException {
        try (PreparedStatement refundStmt = conn.prepareStatement("""
                INSERT INTO refunds (
                    id, refund_id, original_sale_id, refund_amount, refund_tax, total_refund,
                    payment_method, refund_method, reason, cashier_name, pos_user_id, timestamp, synced
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            refundStmt.setString(1, rowId);
            refundStmt.setString(2, refundId);
            refundStmt.setString(3, originalSaleId);
            refundStmt.setBigDecimal(4, totalRefund);
            refundStmt.setBigDecimal(5, BigDecimal.ZERO);
            refundStmt.setBigDecimal(6, totalRefund);
            refundStmt.setString(7, refundMethod);
            refundStmt.setString(8, refundMethod);
            refundStmt.setString(9, "test refund");
            refundStmt.setString(10, cashierName);
            refundStmt.setString(11, posUserId);
            refundStmt.setString(12, timestamp);
            refundStmt.setBoolean(13, false);
            refundStmt.executeUpdate();
        }
    }

    private void insertSplitSale(Connection conn, String shiftId, String saleId, String saleRowId, String cashierId,
            String cashierName, String timestamp, BigDecimal total, BigDecimal cashPart, BigDecimal cardPart)
            throws SQLException {
        try (PreparedStatement saleStmt = conn.prepareStatement("""
                INSERT INTO sales (
                    id, sale_id, subtotal, discount, tax, total, payment_method, is_split_payment, cashier_name,
                    cashier_id, pos_user_id, timestamp, amount_received, change, gpi, ebt_fee, synced, voided,
                    shift_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);
                PreparedStatement itemStmt = conn.prepareStatement("""
                        INSERT INTO sale_items (
                            sale_id, product_id, sku, name, price, quantity, subtotal, discount, gpi, department_id, department_name
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """);
                PreparedStatement paymentStmt = conn.prepareStatement("""
                        INSERT INTO sale_payments (sale_id, payment_method, amount)
                        VALUES (?, ?, ?)
                        """)) {
            saleStmt.setString(1, saleRowId);
            saleStmt.setString(2, saleId);
            saleStmt.setBigDecimal(3, total);
            saleStmt.setBigDecimal(4, BigDecimal.ZERO);
            saleStmt.setBigDecimal(5, BigDecimal.ZERO);
            saleStmt.setBigDecimal(6, total);
            saleStmt.setString(7, "SPLIT");
            saleStmt.setBoolean(8, true);
            saleStmt.setString(9, cashierName);
            saleStmt.setString(10, cashierId);
            saleStmt.setString(11, cashierId);
            saleStmt.setString(12, timestamp);
            saleStmt.setBigDecimal(13, total);
            saleStmt.setBigDecimal(14, BigDecimal.ZERO);
            saleStmt.setBigDecimal(15, BigDecimal.ZERO);
            saleStmt.setBigDecimal(16, BigDecimal.ZERO);
            saleStmt.setBoolean(17, false);
            saleStmt.setBoolean(18, false);
            saleStmt.setString(19, shiftId);
            saleStmt.setTimestamp(20, Timestamp.valueOf(LocalDateTime.now()));
            saleStmt.executeUpdate();

            itemStmt.setString(1, saleId);
            itemStmt.setString(2, PREFIX + "split-product");
            itemStmt.setString(3, "SKU-SPLIT");
            itemStmt.setString(4, "Split Item");
            itemStmt.setBigDecimal(5, total);
            itemStmt.setInt(6, 1);
            itemStmt.setBigDecimal(7, total);
            itemStmt.setBigDecimal(8, BigDecimal.ZERO);
            itemStmt.setBigDecimal(9, BigDecimal.ZERO);
            itemStmt.setString(10, PREFIX + "dept");
            itemStmt.setString(11, "General");
            itemStmt.executeUpdate();

            paymentStmt.setString(1, saleId);
            paymentStmt.setString(2, "CASH");
            paymentStmt.setBigDecimal(3, cashPart);
            paymentStmt.executeUpdate();

            paymentStmt.setString(1, saleId);
            paymentStmt.setString(2, "CARD");
            paymentStmt.setBigDecimal(3, cardPart);
            paymentStmt.executeUpdate();
        }
    }

    private void cleanUpTestData() throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            deleteByPrefix(conn, "DELETE FROM sale_items WHERE sale_id LIKE ?", PREFIX + "%");
            deleteByPrefix(conn, "DELETE FROM sale_payments WHERE sale_id LIKE ?", PREFIX + "%");
            deleteByPrefix(conn, "DELETE FROM refunds WHERE id LIKE ? OR refund_id LIKE ? OR original_sale_id LIKE ?", PREFIX + "%");
            deleteByPrefix(conn, "DELETE FROM sales WHERE id LIKE ? OR sale_id LIKE ?", PREFIX + "%");
            deleteByPrefix(conn, "DELETE FROM cash_operations WHERE id LIKE ? OR shift_id LIKE ?", PREFIX + "%");
            deleteByPrefix(conn, "DELETE FROM vendor_payouts WHERE id LIKE ? OR shift_id LIKE ?", PREFIX + "%");
            deleteByPrefix(conn, "DELETE FROM expenses WHERE id LIKE ? OR shift_id LIKE ? OR expense_id LIKE ?", PREFIX + "%");
            deleteByPrefix(conn, "DELETE FROM expense_categories WHERE id LIKE ? OR name LIKE ?", PREFIX + "%");
            deleteByPrefix(conn, "DELETE FROM vendors WHERE id LIKE ? OR name LIKE ?", PREFIX + "%");
            deleteByPrefix(conn, "DELETE FROM shifts WHERE id LIKE ? OR shift_id LIKE ?", PREFIX + "%");
            conn.commit();
        }
    }

    private void deleteByPrefix(Connection conn, String sql, String prefix) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            int placeholders = countPlaceholders(sql);
            for (int i = 1; i <= placeholders; i++) {
                stmt.setString(i, prefix);
            }
            stmt.executeUpdate();
        }
    }

    private int countPlaceholders(String sql) {
        int count = 0;
        for (char ch : sql.toCharArray()) {
            if (ch == '?') {
                count++;
            }
        }
        return count;
    }
}
