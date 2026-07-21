package com.pos.service;

import com.pos.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for generating sales reports and analytics
 */
public class ReportService {
    private static final Logger logger = LoggerFactory.getLogger(ReportService.class);
    private static ReportService instance;

    private final DatabaseManager dbManager;

    private ReportService() {
        this.dbManager = DatabaseManager.getInstance();
    }

    public static synchronized ReportService getInstance() {
        if (instance == null) {
            instance = new ReportService();
        }
        return instance;
    }

    /**
     * Daily sales summary data
     */
    public static class DailySummary {
        public BigDecimal totalSales;
        public BigDecimal cashSales;
        public BigDecimal cardSales;
        public int transactionCount;
        public BigDecimal averageTransaction;
        public BigDecimal totalTax;
        public BigDecimal totalDiscount;
        public BigDecimal totalGpi;
        public BigDecimal totalEbtFees;
        public BigDecimal finalDeposit;
        public BigDecimal totalExpenses;

        // NRS Standard Metrics
        public BigDecimal grossSales;
        public BigDecimal netSales;

        public DailySummary() {
            this.totalSales = BigDecimal.ZERO;
            this.cashSales = BigDecimal.ZERO;
            this.cardSales = BigDecimal.ZERO;
            this.transactionCount = 0;
            this.averageTransaction = BigDecimal.ZERO;
            this.totalTax = BigDecimal.ZERO;
            this.totalDiscount = BigDecimal.ZERO;
            this.totalGpi = BigDecimal.ZERO;
            this.totalEbtFees = BigDecimal.ZERO;
            this.finalDeposit = BigDecimal.ZERO;
            this.totalExpenses = BigDecimal.ZERO;
            this.grossSales = BigDecimal.ZERO;
            this.netSales = BigDecimal.ZERO;
        }
    }

    /**
     * Sales by employee data
     */
    public static class EmployeeSales {
        public String employeeName;
        public String employeeId;
        public int transactionCount;
        public BigDecimal totalSales;
        public BigDecimal cashSales;
        public BigDecimal cardSales;

        public EmployeeSales(String employeeName, String employeeId) {
            this.employeeName = employeeName;
            this.employeeId = employeeId;
            this.transactionCount = 0;
            this.totalSales = BigDecimal.ZERO;
            this.cashSales = BigDecimal.ZERO;
            this.cardSales = BigDecimal.ZERO;
        }
    }

    /**
     * Payment method breakdown
     */
    public static class PaymentMethodBreakdown {
        public String paymentMethod;
        public int transactionCount;
        public BigDecimal totalAmount;
        public double percentage;

        public PaymentMethodBreakdown(String paymentMethod) {
            this.paymentMethod = paymentMethod;
            this.transactionCount = 0;
            this.totalAmount = BigDecimal.ZERO;
            this.percentage = 0.0;
        }
    }

    /**
     * Top product data
     */
    public static class TopProduct {
        public String productName;
        public String sku;
        public String productId;
        public int quantitySold;
        public BigDecimal totalRevenue;

        public TopProduct(String productName, String sku, String productId) {
            this.productName = productName;
            this.sku = sku;
            this.productId = productId;
            this.quantitySold = 0;
            this.totalRevenue = BigDecimal.ZERO;
        }
    }

    /**
     * Get daily sales summary for a date range
     */
    public DailySummary getDailySummary(LocalDate startDate, LocalDate endDate, String cashierId) throws SQLException {
        logger.info("DEBUG: getDailySummary requested for: {} to {} for cashier: {}", startDate, endDate, cashierId);
        DailySummary summary = new DailySummary();
        try (Connection conn = dbManager.getConnection()) {

            String sql = """
                    SELECT
                        COUNT(*) as transaction_count,
                        COALESCE(SUM(total), 0) as total_sales,
                        COALESCE(SUM(subtotal), 0) as total_subtotal,
                        COALESCE(SUM(tax), 0) as total_tax,
                        COALESCE(SUM(discount), 0) as total_discount,
                        COALESCE(SUM(gpi), 0) as total_gpi,
                        COALESCE(SUM(ebt_fee), 0) as total_ebt_fee,
                        COALESCE(SUM(CASE WHEN payment_method = 'CASH' AND (is_split_payment = FALSE OR is_split_payment IS NULL) THEN total ELSE 0 END), 0) as cash_sales,
                        COALESCE(SUM(CASE WHEN payment_method = 'CARD' AND (is_split_payment = FALSE OR is_split_payment IS NULL) THEN total ELSE 0 END), 0) as card_sales
                    FROM sales
                    WHERE substr(created_at, 1, 10) >= ? AND substr(created_at, 1, 10) <= ?
                      AND (voided = FALSE OR voided IS NULL)
                      AND (pos_user_id = ? OR ? IS NULL)
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                summary.transactionCount = rs.getInt("transaction_count");
                summary.totalSales = rs.getBigDecimal("total_sales");
                BigDecimal subtotal = rs.getBigDecimal("total_subtotal");
                summary.cashSales = rs.getBigDecimal("cash_sales");
                summary.cardSales = rs.getBigDecimal("card_sales");
                summary.totalTax = rs.getBigDecimal("total_tax");
                summary.totalDiscount = rs.getBigDecimal("total_discount");
                summary.totalGpi = rs.getBigDecimal("total_gpi");
                summary.totalEbtFees = rs.getBigDecimal("total_ebt_fee");

                // sales.subtotal is post-discount and includes surcharge/GPI.
                // Net sales deduct surcharge/GPI and EBT fees.
                // Gross sales adds back discounts, surcharge/GPI, tax, and EBT fees.
                summary.netSales = subtotal.subtract(summary.totalGpi).subtract(summary.totalEbtFees);
                summary.grossSales = summary.netSales
                        .add(summary.totalDiscount)
                        .add(summary.totalGpi)
                        .add(summary.totalTax)
                        .add(summary.totalEbtFees);
                summary.totalSales = rs.getBigDecimal("total_sales");
                summary.finalDeposit = summary.totalSales;
            }
            }

            String splitSql = """
                    SELECT
                        sp.payment_method,
                        COALESCE(SUM(sp.amount), 0) as total_amount
                    FROM sale_payments sp
                    INNER JOIN sales s ON sp.sale_id = s.sale_id
                    WHERE substr(s.created_at, 1, 10) >= ? AND substr(s.created_at, 1, 10) <= ?
                      AND (s.voided = FALSE OR s.voided IS NULL)
                      AND s.is_split_payment = TRUE
                      AND (s.pos_user_id = ? OR ? IS NULL)
                    GROUP BY sp.payment_method
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(splitSql)) {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String method = rs.getString("payment_method");
                BigDecimal amount = rs.getBigDecimal("total_amount");

                if ("CASH".equalsIgnoreCase(method)) {
                    summary.cashSales = summary.cashSales.add(amount);
                } else if ("CARD".equalsIgnoreCase(method)) {
                    summary.cardSales = summary.cardSales.add(amount);
                }
            }
            }

            if (summary.transactionCount > 0 && summary.totalSales != null) {
                summary.averageTransaction = summary.totalSales.divide(
                        BigDecimal.valueOf(summary.transactionCount), 2, java.math.RoundingMode.HALF_UP);
            }

            try {
                ExpenseService expenseService = ExpenseService.getInstance();
                BigDecimal baseExpenses = expenseService.getTotalExpenses(startDate, endDate);
                VendorPayoutService vpService = VendorPayoutService.getInstance();
                BigDecimal vendorPayouts = vpService.getTotalPaidPayoutsForDateRange(startDate, endDate);
                summary.totalExpenses = baseExpenses.add(vendorPayouts);
            } catch (Exception e) {
                logger.warn("Error getting expenses for daily summary: {}", e.getMessage());
                summary.totalExpenses = BigDecimal.ZERO;
            }
        }

        return summary;
    }

    public DailySummary getDailySummary(LocalDate startDate, LocalDate endDate) throws SQLException {
        return getDailySummary(startDate, endDate, null);
    }

    /**
     * Get sales by employee for a date range
     */
    public List<EmployeeSales> getSalesByEmployee(LocalDate startDate, LocalDate endDate, String cashierId) throws SQLException {
        List<EmployeeSales> employeeSalesList = new ArrayList<>();

        String sql = """
                SELECT
                    COALESCE(cashier_name, 'Unknown') as employee_name,
                    MAX(COALESCE(pos_user_id, cashier_id, '')) as employee_id,
                    COUNT(*) as transaction_count,
                    COALESCE(SUM(total), 0) as total_sales,
                    COALESCE(SUM(CASE WHEN payment_method = 'CASH' THEN total ELSE 0 END), 0) as cash_sales,
                    COALESCE(SUM(CASE WHEN payment_method = 'CARD' THEN total ELSE 0 END), 0) as card_sales
                FROM sales
                WHERE substr(created_at, 1, 10) >= ? AND substr(created_at, 1, 10) <= ?
                  AND (voided = FALSE OR voided IS NULL)
                  AND (pos_user_id = ? OR ? IS NULL)
                GROUP BY cashier_name
                ORDER BY total_sales DESC
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setDate(1, java.sql.Date.valueOf(startDate));
            stmt.setDate(2, java.sql.Date.valueOf(endDate));
            stmt.setString(3, cashierId);
            stmt.setString(4, cashierId);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                EmployeeSales empSales = new EmployeeSales(
                    rs.getString("employee_name"),
                    rs.getString("employee_id")
                );
                empSales.transactionCount = rs.getInt("transaction_count");
                empSales.totalSales = rs.getBigDecimal("total_sales");
                empSales.cashSales = rs.getBigDecimal("cash_sales");
                empSales.cardSales = rs.getBigDecimal("card_sales");
                employeeSalesList.add(empSales);
            }
        }
        return employeeSalesList;
    }

    public List<EmployeeSales> getSalesByEmployee(LocalDate startDate, LocalDate endDate) throws SQLException {
        return getSalesByEmployee(startDate, endDate, null);
    }

    /**
     * Get payment method breakdown for a date range
     */
    public List<PaymentMethodBreakdown> getPaymentMethodBreakdown(LocalDate startDate, LocalDate endDate, String cashierId)
            throws SQLException {
        Map<String, PaymentMethodBreakdown> breakdownMap = new HashMap<>();

        String nonSplitSql = """
                SELECT
                    COALESCE(payment_method, 'UNKNOWN') as payment_method,
                    COUNT(*) as transaction_count,
                    COALESCE(SUM(total), 0) as total_amount
                FROM sales
                WHERE substr(created_at, 1, 10) >= ? AND substr(created_at, 1, 10) <= ?
                  AND (voided = FALSE OR voided IS NULL)
                  AND (is_split_payment = FALSE OR is_split_payment IS NULL)
                  AND (pos_user_id = ? OR ? IS NULL)
                GROUP BY payment_method
                """;

        try (Connection conn = dbManager.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(nonSplitSql)) {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String method = rs.getString("payment_method");
                PaymentMethodBreakdown pmb = breakdownMap.computeIfAbsent(method, PaymentMethodBreakdown::new);
                pmb.transactionCount += rs.getInt("transaction_count");
                pmb.totalAmount = pmb.totalAmount.add(rs.getBigDecimal("total_amount"));
            }
            }

            String splitSql = """
                SELECT
                    sp.payment_method,
                    COUNT(*) as payment_count,
                    COALESCE(SUM(sp.amount), 0) as total_amount
                FROM sale_payments sp
                INNER JOIN sales s ON sp.sale_id = s.sale_id
                WHERE substr(s.created_at, 1, 10) >= ? AND substr(s.created_at, 1, 10) <= ?
                  AND (s.voided = FALSE OR s.voided IS NULL)
                  AND s.is_split_payment = TRUE
                  AND (s.pos_user_id = ? OR ? IS NULL)
                GROUP BY sp.payment_method
                """;

            try (PreparedStatement stmt = conn.prepareStatement(splitSql)) {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String method = rs.getString("payment_method");
                PaymentMethodBreakdown pmb = breakdownMap.computeIfAbsent(method, PaymentMethodBreakdown::new);
                pmb.transactionCount += rs.getInt("payment_count");
                pmb.totalAmount = pmb.totalAmount.add(rs.getBigDecimal("total_amount"));
            }
            }
        }

        List<PaymentMethodBreakdown> breakdown = new ArrayList<>(breakdownMap.values());
        BigDecimal totalSales = breakdown.stream()
                .map(pmb -> pmb.totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalSales.compareTo(BigDecimal.ZERO) > 0) {
            for (PaymentMethodBreakdown pmb : breakdown) {
                pmb.percentage = pmb.totalAmount.divide(totalSales, 4, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).doubleValue();
            }
        }
        breakdown.sort((a, b) -> b.totalAmount.compareTo(a.totalAmount));
        return breakdown;
    }

    public List<PaymentMethodBreakdown> getPaymentMethodBreakdown(LocalDate startDate, LocalDate endDate) throws SQLException {
        return getPaymentMethodBreakdown(startDate, endDate, null);
    }

    /**
     * Get top products by quantity sold
     */
    public List<TopProduct> getTopProducts(LocalDate startDate, LocalDate endDate, int limit, String cashierId) throws SQLException {
        List<TopProduct> topProducts = new ArrayList<>();

        String sql = """
                SELECT
                    si.name as product_name,
                    si.sku,
                    si.product_id,
                    SUM(si.quantity) as quantity_sold,
                    SUM(si.subtotal) as total_revenue
                FROM sale_items si
                INNER JOIN sales s ON si.sale_id = s.sale_id
                WHERE substr(s.created_at, 1, 10) >= ? AND substr(s.created_at, 1, 10) <= ?
                  AND (s.voided = FALSE OR s.voided IS NULL)
                  AND (s.pos_user_id = ? OR ? IS NULL)
                GROUP BY si.name, si.sku, si.product_id
                ORDER BY quantity_sold DESC
                LIMIT ?
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setDate(1, java.sql.Date.valueOf(startDate));
            stmt.setDate(2, java.sql.Date.valueOf(endDate));
            stmt.setString(3, cashierId);
            stmt.setString(4, cashierId);
            stmt.setInt(5, limit);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                TopProduct product = new TopProduct(
                        rs.getString("product_name"),
                        rs.getString("sku"),
                        rs.getString("product_id"));
                product.quantitySold = rs.getInt("quantity_sold");
                product.totalRevenue = rs.getBigDecimal("total_revenue");
                topProducts.add(product);
            }
        }
        return topProducts;
    }

    public List<TopProduct> getTopProducts(LocalDate startDate, LocalDate endDate, int limit) throws SQLException {
        return getTopProducts(startDate, endDate, limit, null);
    }

    /**
     * Get all sales for export
     */
    public List<Map<String, Object>> getSalesForExport(LocalDate startDate, LocalDate endDate, String cashierId) throws SQLException {
        List<Map<String, Object>> sales = new ArrayList<>();

        String sql = """
                SELECT
                    s.sale_id,
                    s.timestamp,
                    s.cashier_name,
                    s.payment_method,
                    s.subtotal,
                    s.discount,
                    s.tax,
                    s.total,
                    s.synced
                FROM sales s
                WHERE substr(s.created_at, 1, 10) >= ? AND substr(s.created_at, 1, 10) <= ?
                  AND (s.voided = FALSE OR s.voided IS NULL)
                  AND (s.pos_user_id = ? OR ? IS NULL)
                ORDER BY s.timestamp DESC
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setDate(1, java.sql.Date.valueOf(startDate));
            stmt.setDate(2, java.sql.Date.valueOf(endDate));
            stmt.setString(3, cashierId);
            stmt.setString(4, cashierId);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Map<String, Object> sale = new HashMap<>();
                sale.put("sale_id", rs.getString("sale_id"));
                sale.put("timestamp", rs.getString("timestamp"));
                sale.put("cashier_name", rs.getString("cashier_name"));
                sale.put("payment_method", rs.getString("payment_method"));
                sale.put("subtotal", rs.getBigDecimal("subtotal"));
                sale.put("discount", rs.getBigDecimal("discount"));
                sale.put("tax", rs.getBigDecimal("tax"));
                sale.put("total", rs.getBigDecimal("total"));
                sale.put("synced", rs.getBoolean("synced"));
                sales.add(sale);
            }
        }
        return sales;
    }

    public List<Map<String, Object>> getSalesForExport(LocalDate startDate, LocalDate endDate) throws SQLException {
        return getSalesForExport(startDate, endDate, null);
    }

    public Map<String, BigDecimal> getExpensesByCategory(LocalDate startDate, LocalDate endDate) throws SQLException {
        try {
            ExpenseService expenseService = ExpenseService.getInstance();
            return expenseService.getExpensesByCategory(startDate, endDate);
        } catch (Exception e) {
            logger.error("Error getting expenses by category", e);
            return new HashMap<>();
        }
    }

    public List<Map<String, Object>> getExpensesForDateRange(LocalDate startDate, LocalDate endDate) throws SQLException {
        List<Map<String, Object>> expenses = new ArrayList<>();
        ExpenseService expenseService = ExpenseService.getInstance();
        try {
            List<com.pos.model.Expense> list = expenseService.getExpensesForDateRange(startDate, endDate);
            for (com.pos.model.Expense exp : list) {
                Map<String, Object> map = new HashMap<>();
                map.put("expense_id", exp.getExpenseId());
                map.put("timestamp", exp.getTimestamp());
                map.put("category_name", exp.getCategoryName());
                map.put("description", exp.getDescription());
                map.put("amount", exp.getAmount());
                map.put("payment_method", exp.getPaymentMethod());
                map.put("created_by_name", exp.getCreatedByName());
                expenses.add(map);
            }
        } catch (Exception e) {
            logger.error("Error getting expenses", e);
        }
        return expenses;
    }
}
