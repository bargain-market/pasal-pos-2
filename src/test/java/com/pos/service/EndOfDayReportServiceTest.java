package com.pos.service;

import com.pos.database.DatabaseManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class EndOfDayReportServiceTest {

    private DatabaseManager dbManager;
    private EndOfDayReportService service;
    private ReportService reportService;
    private String testKey;
    private String cashierId;
    private LocalDate reportDate;

    @Before
    public void setUp() {
        dbManager = DatabaseManager.getInstance();
        dbManager.initializeSchema();
        service = EndOfDayReportService.getInstance();
        reportService = ReportService.getInstance();

        testKey = UUID.randomUUID().toString().replace("-", "");
        cashierId = "test-eod-cashier-" + testKey;
        reportDate = LocalDate.now();
    }

    @After
    public void tearDown() throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            deleteLike(conn, "DELETE FROM sale_payments WHERE sale_id LIKE ?", "test-eod-sale-%" + testKey + "%");
            deleteLike(conn, "DELETE FROM sale_items WHERE sale_id LIKE ?", "test-eod-sale-%" + testKey + "%");
            deleteLike(conn, "DELETE FROM sales WHERE sale_id LIKE ?", "test-eod-sale-%" + testKey + "%");
            deleteLike(conn, "DELETE FROM vendor_payouts WHERE id LIKE ?", "test-eod-vp-%" + testKey + "%");
            deleteLike(conn, "DELETE FROM vendors WHERE id LIKE ?", "vendor-" + testKey + "%");
            deleteLike(conn, "DELETE FROM expenses WHERE id LIKE ?", "test-eod-expense-%" + testKey + "%");
            deleteLike(conn, "DELETE FROM expense_categories WHERE id LIKE ?", "test-eod-category-%" + testKey + "%");
            deleteLike(conn, "DELETE FROM cash_operations WHERE id LIKE ?", "test-eod-op-%" + testKey + "%");
            deleteLike(conn, "DELETE FROM shifts WHERE id LIKE ?", "test-eod-shift-%" + testKey + "%");
            conn.commit();
        }
    }

    @Test
    public void generateReport_keepsAllSectionsOnNetSalesBasis() throws SQLException {
        String saleId = "test-eod-sale-card-" + testKey;
        insertSale(saleId, null, "CARD", false, new BigDecimal("94.29"), new BigDecimal("10.00"),
                new BigDecimal("7.20"), new BigDecimal("101.49"), new BigDecimal("4.29"), BigDecimal.ZERO,
                reportDate.atTime(10, 15));
        insertSaleItem(saleId, "Clothing", new BigDecimal("104.00"), new BigDecimal("10.00"), new BigDecimal("4.00"),
                1);

        EndOfDayReportService.EndOfDayReport report = service.generateReport(reportDate, "JUnit", cashierId);

        assertMoney("90.00", report.commodityGroups.get(0).revenue);
        assertMoney("10.00", report.commodityGroups.get(0).discount);

        assertMoney("90.00", report.customerGroups.get(0).revenue);
        assertMoney("10.00", report.customerGroups.get(0).discount);

        assertMoney("90.00", report.salesTaxes.get(0).netAmount);
        assertMoney("7.20", report.salesTaxes.get(0).taxAmount);
        assertMoney("111.49", report.salesTaxes.get(0).grossAmount);

        assertEquals(1, report.paymentMethods.size());
        assertMoney("101.49", report.paymentMethods.get(0).totalAmount);
        assertMoney("90.00", report.paymentMethods.get(0).netAmount);
        assertMoney("4.29", report.paymentMethods.get(0).gpi);

        assertMoney("111.49", report.grossSales);
        assertMoney("90.00", report.netSales);
        assertMoney("7.20", report.totalTax);
        assertMoney("4.29", report.totalGpi);
        assertMoney("101.49", report.totalPaymentsReceived);
        assertMoney("101.49", report.finalDeposit);
    }

    @Test
    public void generateReceiptText_showsBothGrossAndNetSalesSummary() throws SQLException {
        String saleId = "test-eod-sale-receipt-" + testKey;
        insertSale(saleId, null, "CARD", false, new BigDecimal("94.29"), new BigDecimal("10.00"),
                new BigDecimal("7.20"), new BigDecimal("101.49"), new BigDecimal("4.29"), BigDecimal.ZERO,
                reportDate.atTime(10, 15));
        insertSaleItem(saleId, "Clothing", new BigDecimal("104.00"), new BigDecimal("10.00"), new BigDecimal("4.00"),
                1);

        EndOfDayReportService.EndOfDayReport report = service.generateReport(reportDate, "JUnit", cashierId);
        String receipt = service.generateReceiptText(report);

        assertNotNull(receipt);
        assertTrue(receipt.contains("Gross Sales:"));
        assertFalse(receipt.contains("Less: Non-Cash Adj:"));
        assertFalse(receipt.contains("Plus: GPI:"));
        assertTrue(receipt.contains("Net Sales:"));
        assertTrue(receipt.contains("Plus: Fees/Surcharge:"));
        assertFalse(receipt.contains("Total Collected:"));
        assertTrue(receipt.contains("Gross"));
        assertFalse(receipt.contains("Taxable"));
        assertTrue(receipt.contains("111.49"));
        assertTrue(receipt.contains("90.00"));
    }

    @Test
    public void generateReport_allocatesSplitPaymentsConsistently() throws SQLException {
        String saleId = "test-eod-sale-split-" + testKey;
        insertSale(saleId, null, "SPLIT", true, new BigDecimal("50.00"), BigDecimal.ZERO,
                new BigDecimal("4.00"), new BigDecimal("54.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                reportDate.atTime(11, 0));
        insertSaleItem(saleId, "Grocery", new BigDecimal("50.00"), BigDecimal.ZERO, BigDecimal.ZERO, 1);
        insertSplitPayment(saleId, "CASH", new BigDecimal("27.00"));
        insertSplitPayment(saleId, "CARD", new BigDecimal("27.00"));

        EndOfDayReportService.EndOfDayReport report = service.generateReport(reportDate, "JUnit", cashierId);

        assertMoney("50.00", report.netSales);
        assertMoney("54.00", report.totalPaymentsReceived);
        assertMoney("54.00", report.finalDeposit);

        PaymentCheck cash = findPayment(report.paymentMethods, "CASH");
        PaymentCheck card = findPayment(report.paymentMethods, "CARD");

        assertMoney("27.00", cash.totalAmount);
        assertMoney("25.00", cash.netAmount);
        assertMoney("27.00", card.totalAmount);
        assertMoney("25.00", card.netAmount);
    }

    @Test
    public void generateReport_appliesEbtFeeOnlyToFinalDeposit() throws SQLException {
        String saleId = "test-eod-sale-ebt-" + testKey;
        insertSale(saleId, null, "EBT", false, new BigDecimal("20.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("20.00"), BigDecimal.ZERO, new BigDecimal("0.10"),
                reportDate.atTime(12, 0));
        insertSaleItem(saleId, "Food", new BigDecimal("20.00"), BigDecimal.ZERO, BigDecimal.ZERO, 1);

        EndOfDayReportService.EndOfDayReport report = service.generateReport(reportDate, "JUnit", cashierId);

        assertMoney("19.90", report.netSales);
        assertMoney("20.00", report.grossSales);
        assertMoney("20.00", report.totalPaymentsReceived);
        assertMoney("0.10", report.totalEbtFees);
        assertMoney("20.00", report.finalDeposit);
    }

    @Test
    public void generateReport_calculatesCashExpectedWithOperationsExpensesAndPayouts() throws SQLException {
        String shiftId = "test-eod-shift-" + testKey;
        insertShift(shiftId, new BigDecimal("100.00"), new BigDecimal("144.00"),
                reportDate.atTime(8, 0), reportDate.atTime(18, 0));

        String saleId = "test-eod-sale-cash-" + testKey;
        insertSale(saleId, shiftId, "CASH", false, new BigDecimal("50.00"), BigDecimal.ZERO,
                new BigDecimal("4.00"), new BigDecimal("54.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                reportDate.atTime(9, 0));
        insertSaleItem(saleId, "General", new BigDecimal("50.00"), BigDecimal.ZERO, BigDecimal.ZERO, 1);

        insertCashOperation(shiftId, "ADD", new BigDecimal("10.00"), reportDate.atTime(10, 0));
        insertCashOperation(shiftId, "DROP", new BigDecimal("5.00"), reportDate.atTime(11, 0));
        insertExpense(shiftId, new BigDecimal("7.00"), "CASH", reportDate.atTime(12, 0));
        insertVendorPayout(shiftId, new BigDecimal("8.00"), "CASH", reportDate.atTime(13, 0));

        EndOfDayReportService.EndOfDayReport report = service.generateReport(reportDate, "JUnit", cashierId);

        assertMoney("54.00", report.totalPaymentsReceived);
        assertMoney("15.00", report.totalExpenses);
        assertMoney("8.00", report.totalVendorPayoutsCash);
        assertMoney("44.00", report.cashExpected);
        assertMoney("100.00", report.startingCash);
        assertMoney("144.00", report.cashActual);
        assertMoney("0.00", report.cashShortOver);
    }

    @Test
    public void generateShiftReport_usesSameNetSalesAndDepositFormulas() throws SQLException {
        String shiftId = "test-eod-shift-report-" + testKey;
        insertShift(shiftId, new BigDecimal("50.00"), new BigDecimal("50.00"),
                reportDate.atTime(8, 0), reportDate.atTime(17, 0));

        String saleId = "test-eod-sale-shift-" + testKey;
        insertSale(saleId, shiftId, "CARD", false, new BigDecimal("31.20"), new BigDecimal("5.00"),
                new BigDecimal("2.40"), new BigDecimal("33.60"), new BigDecimal("1.20"), BigDecimal.ZERO,
                reportDate.atTime(9, 30));
        insertSaleItem(saleId, "Shoes", new BigDecimal("36.20"), new BigDecimal("5.00"), new BigDecimal("1.20"), 1);

        EndOfDayReportService.EndOfDayReport report = service.generateShiftReport(shiftId, "JUnit");

        assertMoney("38.60", report.grossSales);
        assertMoney("30.00", report.netSales);
        assertMoney("2.40", report.totalTax);
        assertMoney("1.20", report.totalGpi);
        assertMoney("33.60", report.totalPaymentsReceived);
        assertMoney("33.60", report.finalDeposit);
    }

    @Test
    public void dailySummary_usesNetSalesExcludingGpiOnDashboardCards() throws SQLException {
        String saleId = "test-eod-sale-dashboard-" + testKey;
        insertSale(saleId, null, "CARD", false, new BigDecimal("94.29"), new BigDecimal("10.00"),
                new BigDecimal("7.20"), new BigDecimal("101.49"), new BigDecimal("4.29"), BigDecimal.ZERO,
                reportDate.atTime(14, 0));
        insertSaleItem(saleId, "Clothing", new BigDecimal("104.00"), new BigDecimal("10.00"), new BigDecimal("4.00"),
                1);

        ReportService.DailySummary summary = reportService.getDailySummary(reportDate, reportDate, cashierId);

        assertMoney("111.49", summary.grossSales);
        assertMoney("90.00", summary.netSales);
        assertMoney("101.49", summary.totalSales);
        assertMoney("4.29", summary.totalGpi);
        assertMoney("101.49", summary.finalDeposit);
        assertMoney("101.49", summary.cardSales);
    }

    private PaymentCheck findPayment(List<EndOfDayReportService.PaymentMethodSales> paymentMethods, String method) {
        return paymentMethods.stream()
                .filter(p -> method.equalsIgnoreCase(p.paymentMethod))
                .findFirst()
                .map(p -> new PaymentCheck(p.totalAmount, p.netAmount))
                .orElseThrow(() -> new AssertionError("Missing payment method " + method));
    }

    private void insertSale(String saleId, String shiftId, String paymentMethod, boolean splitPayment,
            BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal total, BigDecimal gpi,
            BigDecimal ebtFee, LocalDateTime createdAt) throws SQLException {
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement("""
                        INSERT INTO sales (
                            id, sale_id, subtotal, discount, tax, total, payment_method,
                            is_split_payment, cashier_name, cashier_id, pos_user_id, timestamp,
                            gpi, ebt_fee, shift_id, synced, voided, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, FALSE, ?)
                        """)) {
            stmt.setString(1, "test-eod-row-" + saleId);
            stmt.setString(2, saleId);
            stmt.setBigDecimal(3, subtotal);
            stmt.setBigDecimal(4, discount);
            stmt.setBigDecimal(5, tax);
            stmt.setBigDecimal(6, total);
            stmt.setString(7, paymentMethod);
            stmt.setBoolean(8, splitPayment);
            stmt.setString(9, "Test Cashier");
            stmt.setString(10, cashierId);
            stmt.setString(11, cashierId);
            stmt.setString(12, createdAt.toString());
            stmt.setBigDecimal(13, gpi);
            stmt.setBigDecimal(14, ebtFee);
            stmt.setString(15, shiftId);
            stmt.setTimestamp(16, Timestamp.valueOf(createdAt));
            stmt.executeUpdate();
            conn.commit();
        }
    }

    private void insertSaleItem(String saleId, String departmentName, BigDecimal subtotal, BigDecimal discount,
            BigDecimal gpi, int quantity) throws SQLException {
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement("""
                        INSERT INTO sale_items (
                            sale_id, product_id, sku, name, price, quantity, subtotal,
                            discount, discount_reason, gpi, department_id, department_name
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
            stmt.setString(1, saleId);
            stmt.setString(2, "product-" + saleId);
            stmt.setString(3, "sku-" + saleId);
            stmt.setString(4, "Item " + departmentName);
            stmt.setBigDecimal(5, subtotal);
            stmt.setInt(6, quantity);
            stmt.setBigDecimal(7, subtotal);
            stmt.setBigDecimal(8, discount);
            stmt.setString(9, discount.compareTo(BigDecimal.ZERO) > 0 ? "Test discount" : null);
            stmt.setBigDecimal(10, gpi);
            stmt.setString(11, "dept-" + departmentName + "-" + testKey);
            stmt.setString(12, departmentName);
            stmt.executeUpdate();
            conn.commit();
        }
    }

    private void insertSplitPayment(String saleId, String method, BigDecimal amount) throws SQLException {
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement("""
                        INSERT INTO sale_payments (sale_id, payment_method, amount)
                        VALUES (?, ?, ?)
                        """)) {
            stmt.setString(1, saleId);
            stmt.setString(2, method);
            stmt.setBigDecimal(3, amount);
            stmt.executeUpdate();
            conn.commit();
        }
    }

    private void insertShift(String shiftId, BigDecimal openingCash, BigDecimal actualCash,
            LocalDateTime startedAt, LocalDateTime endedAt) throws SQLException {
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement("""
                        INSERT INTO shifts (
                            id, shift_id, store_id, cashier_name, cashier_id, register_id, shift_number,
                            shift_started_at, shift_ended_at, status, opening_cash, actual_cash, synced
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLOSED', ?, ?, TRUE)
                        """)) {
            stmt.setString(1, shiftId);
            stmt.setString(2, shiftId);
            stmt.setString(3, "store-" + testKey);
            stmt.setString(4, "Test Cashier");
            stmt.setString(5, cashierId);
            stmt.setString(6, "register-1");
            stmt.setInt(7, 1);
            stmt.setString(8, startedAt.toString());
            stmt.setString(9, endedAt.toString());
            stmt.setBigDecimal(10, openingCash);
            stmt.setBigDecimal(11, actualCash);
            stmt.executeUpdate();
            conn.commit();
        }
    }

    private void insertCashOperation(String shiftId, String type, BigDecimal amount, LocalDateTime createdAt)
            throws SQLException {
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement("""
                        INSERT INTO cash_operations (
                            id, shift_id, type, amount, note, performed_by, performed_by_name, created_at, synced
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                        """)) {
            stmt.setString(1, "test-eod-op-" + type + "-" + testKey + "-" + createdAt.getHour());
            stmt.setString(2, shiftId);
            stmt.setString(3, type);
            stmt.setBigDecimal(4, amount);
            stmt.setString(5, "Test " + type);
            stmt.setString(6, cashierId);
            stmt.setString(7, "Test Cashier");
            stmt.setString(8, createdAt.toString());
            stmt.executeUpdate();
            conn.commit();
        }
    }

    private void insertExpense(String shiftId, BigDecimal amount, String paymentMethod, LocalDateTime createdAt)
            throws SQLException {
        String categoryId = "test-eod-category-" + testKey;
        try (Connection conn = dbManager.getConnection()) {
            try (PreparedStatement categoryStmt = conn.prepareStatement("""
                    MERGE INTO expense_categories (id, name, description, is_system, is_active, display_order)
                    KEY (id) VALUES (?, ?, ?, FALSE, TRUE, 1)
                    """)) {
                categoryStmt.setString(1, categoryId);
                categoryStmt.setString(2, "Test Category " + testKey);
                categoryStmt.setString(3, "Test category");
                categoryStmt.executeUpdate();
            }

            try (PreparedStatement expenseStmt = conn.prepareStatement("""
                    INSERT INTO expenses (
                        id, expense_id, category_id, category_name, amount, description,
                        payment_method, shift_id, created_by, created_by_name, timestamp, synced, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                    """)) {
                expenseStmt.setString(1, "test-eod-expense-" + testKey);
                expenseStmt.setString(2, "test-eod-expense-ref-" + testKey);
                expenseStmt.setString(3, categoryId);
                expenseStmt.setString(4, "Test Category " + testKey);
                expenseStmt.setBigDecimal(5, amount);
                expenseStmt.setString(6, "Test expense");
                expenseStmt.setString(7, paymentMethod);
                expenseStmt.setString(8, shiftId);
                expenseStmt.setString(9, cashierId);
                expenseStmt.setString(10, "Test Cashier");
                expenseStmt.setString(11, createdAt.toString());
                expenseStmt.setTimestamp(12, Timestamp.valueOf(createdAt));
                expenseStmt.executeUpdate();
            }
            conn.commit();
        }
    }

    private void insertVendorPayout(String shiftId, BigDecimal amountPaid, String paymentMethod, LocalDateTime paidAt)
            throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            try (PreparedStatement vendorStmt = conn.prepareStatement("""
                    MERGE INTO vendors (id, name, is_active, synced)
                    KEY (id) VALUES (?, ?, TRUE, TRUE)
                    """)) {
                vendorStmt.setString(1, "vendor-" + testKey);
                vendorStmt.setString(2, "Vendor " + testKey);
                vendorStmt.executeUpdate();
            }

            try (PreparedStatement stmt = conn.prepareStatement("""
                        INSERT INTO vendor_payouts (
                            id, vendor_id, vendor_name, total_sales, total_cost, total_payout,
                            commission_rate, item_count, transaction_count, status, paid_at, paid_by,
                            payment_method, payment_reference, shift_id, amount_paid, cheque_number, synced
                        ) VALUES (?, ?, ?, 0, 0, ?, 0, 0, 0, 'PAID', ?, ?, ?, ?, ?, ?, ?, TRUE)
                        """)) {
                stmt.setString(1, "test-eod-vp-" + testKey);
                stmt.setString(2, "vendor-" + testKey);
                stmt.setString(3, "Vendor " + testKey);
            stmt.setBigDecimal(4, amountPaid);
            stmt.setString(5, paidAt.toString());
            stmt.setString(6, cashierId);
            stmt.setString(7, paymentMethod);
            stmt.setString(8, "ref-" + testKey);
            stmt.setString(9, shiftId);
                stmt.setBigDecimal(10, amountPaid);
                stmt.setString(11, null);
                stmt.executeUpdate();
            }
            conn.commit();
        }
    }

    private void deleteLike(Connection conn, String sql, String value) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, value);
            stmt.executeUpdate();
        }
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual.setScale(2, RoundingMode.HALF_UP)));
    }

    private static class PaymentCheck {
        private final BigDecimal totalAmount;
        private final BigDecimal netAmount;

        private PaymentCheck(BigDecimal totalAmount, BigDecimal netAmount) {
            this.totalAmount = totalAmount;
            this.netAmount = netAmount;
        }
    }
}
