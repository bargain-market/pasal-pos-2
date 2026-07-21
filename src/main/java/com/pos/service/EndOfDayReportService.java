package com.pos.service;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Service for generating End of Day (Z-Report) receipts
 * Aggregates daily sales data and formats it for receipt printing
 */
public class EndOfDayReportService {
    private static final Logger logger = LoggerFactory.getLogger(EndOfDayReportService.class);
    private static EndOfDayReportService instance;

    private final DatabaseManager dbManager;
    private final ConfigManager config;
    private final SettingsService settingsService;

    /** Uppercase trimmed key so CASH/cash/Cash aggregate together for reconciliation. */
    private static String canonicalPaymentMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    /** Sum gross cash tender (totalAmount) across all rows that are cash, for drawer math. */
    private static BigDecimal sumCashPaymentTotals(List<PaymentMethodSales> paymentMethods) {
        BigDecimal sum = BigDecimal.ZERO;
        if (paymentMethods == null) {
            return sum;
        }
        for (PaymentMethodSales pms : paymentMethods) {
            if (pms != null && pms.paymentMethod != null && "CASH".equalsIgnoreCase(pms.paymentMethod)) {
                sum = sum.add(pms.totalAmount != null ? pms.totalAmount : BigDecimal.ZERO);
            }
        }
        return sum;
    }

    // Receipt width in characters (standard thermal receipt printer)
    private static final int RECEIPT_WIDTH = 48;

    private EndOfDayReportService() {
        this.dbManager = DatabaseManager.getInstance();
        this.config = ConfigManager.getInstance();
        this.settingsService = SettingsService.getInstance();
    }

    public static synchronized EndOfDayReportService getInstance() {
        if (instance == null) {
            instance = new EndOfDayReportService();
        }
        return instance;
    }

    // ==================== Data Classes ====================

    /**
     * Complete End of Day report data
     */
    public static class EndOfDayReport {
        public String posId;
        public String cashier;
        public String cashDrawer;
        public int zCountFrom;
        public int zCountTo;
        public LocalDate startDate;
        public LocalDate endDate;
        public LocalDateTime creationDate;
        public String createdBy;

        public List<CommodityGroupRevenue> commodityGroups = new ArrayList<>();
        public List<CustomerGroupRevenue> customerGroups = new ArrayList<>();
        public List<IncomeExpense> incomeExpenses = new ArrayList<>();
        public List<SalesTaxBreakdown> salesTaxes = new ArrayList<>();
        public List<PaymentMethodSales> paymentMethods = new ArrayList<>();
        public List<VendorPayoutEntry> vendorPayouts = new ArrayList<>();
        public List<ExpenseEntry> expenses = new ArrayList<>();
        public List<CashierAction> cashierActions = new ArrayList<>();

        // Totals
        public int totalQuantity;
        public BigDecimal totalRevenue = BigDecimal.ZERO;
        public BigDecimal totalDiscount = BigDecimal.ZERO;
        public BigDecimal totalTax = BigDecimal.ZERO;
        public BigDecimal totalGross = BigDecimal.ZERO;
        public BigDecimal totalNet = BigDecimal.ZERO;

        // Summary totals
        public BigDecimal grossSales = BigDecimal.ZERO;
        public BigDecimal netSales = BigDecimal.ZERO;
        public BigDecimal totalPaymentsReceived = BigDecimal.ZERO;
        public BigDecimal startingCash = BigDecimal.ZERO;
        public BigDecimal cashExpected = BigDecimal.ZERO;
        public BigDecimal cashActual = BigDecimal.ZERO;
        public BigDecimal cashShortOver = BigDecimal.ZERO;
        public BigDecimal totalIncomeExpense = BigDecimal.ZERO;

        // Vendor payout totals
        public BigDecimal totalVendorPayoutsCash = BigDecimal.ZERO;
        public BigDecimal totalVendorPayoutsCheque = BigDecimal.ZERO;
        public BigDecimal totalVendorPayoutsInvoice = BigDecimal.ZERO;
        public BigDecimal totalVendorPayoutsCredit = BigDecimal.ZERO;
        public BigDecimal totalVendorPayouts = BigDecimal.ZERO;

        // Expense totals
        public BigDecimal totalExpenses = BigDecimal.ZERO;
        public BigDecimal totalGpi = BigDecimal.ZERO;
        public BigDecimal totalEbtFees = BigDecimal.ZERO;
        public BigDecimal finalDeposit = BigDecimal.ZERO;
        /** Cash refunds in period (reduces expected cash); shown on receipt for transparency */
        public BigDecimal cashRefundsTotal = BigDecimal.ZERO;
        public String shiftId; // ID of the specific shift if this is a shift report
        public String shiftNumber; // External shift number
    }

    /**
     * Expense entry for EOD report
     */
    public static class ExpenseEntry {
        public String categoryName;
        public BigDecimal amount;
        public String description;
        public String paymentMethod;

        public ExpenseEntry(String categoryName, BigDecimal amount, String description, String paymentMethod) {
            this.categoryName = categoryName;
            this.amount = amount != null ? amount : BigDecimal.ZERO;
            this.description = description;
            this.paymentMethod = paymentMethod;
        }
    }

    /**
     * Vendor payout entry for EOD report
     */
    public static class VendorPayoutEntry {
        public String vendorName;
        public BigDecimal amount;
        public String paymentMethod;
        public String chequeNumber;
        public String paymentReference;
        public String paidAt;
        public String paidBy;

        public VendorPayoutEntry(String vendorName, BigDecimal amount, String paymentMethod,
                String chequeNumber, String paymentReference, String paidAt, String paidBy) {
            this.vendorName = vendorName;
            this.amount = amount != null ? amount : BigDecimal.ZERO;
            this.paymentMethod = paymentMethod;
            this.chequeNumber = chequeNumber;
            this.paymentReference = paymentReference;
            this.paidAt = paidAt;
            this.paidBy = paidBy;
        }
    }

    /**
     * Commodity (Department/Category) group revenue
     */
    public static class CommodityGroupRevenue {
        public int groupNumber;
        public String groupName;
        public int quantity;
        public BigDecimal revenue;
        public BigDecimal discount;

        public CommodityGroupRevenue(int groupNumber, String groupName, int quantity, BigDecimal revenue,
                BigDecimal discount) {
            this.groupNumber = groupNumber;
            this.groupName = groupName;
            this.quantity = quantity;
            this.revenue = revenue != null ? revenue : BigDecimal.ZERO;
            this.discount = discount != null ? discount : BigDecimal.ZERO;
        }
    }

    /**
     * Customer group revenue
     */
    public static class CustomerGroupRevenue {
        public int groupNumber;
        public String groupName;
        public int quantity;
        public BigDecimal revenue;
        public BigDecimal discount;

        public CustomerGroupRevenue(int groupNumber, String groupName, int quantity, BigDecimal revenue,
                BigDecimal discount) {
            this.groupNumber = groupNumber;
            this.groupName = groupName;
            this.quantity = quantity;
            this.revenue = revenue != null ? revenue : BigDecimal.ZERO;
            this.discount = discount != null ? discount : BigDecimal.ZERO;
        }
    }

    /**
     * Income/Expense entry (fees, cash operations, etc.)
     */
    public static class IncomeExpense {
        public int accountNumber;
        public String accountName;
        public int quantity;
        public BigDecimal amount;

        public IncomeExpense(int accountNumber, String accountName, int quantity, BigDecimal amount) {
            this.accountNumber = accountNumber;
            this.accountName = accountName;
            this.quantity = quantity;
            this.amount = amount != null ? amount : BigDecimal.ZERO;
        }
    }

    /**
     * Sales tax breakdown by rate
     */
    public static class SalesTaxBreakdown {
        public String taxName;
        public String taxDescription;
        public BigDecimal taxRate;
        public BigDecimal taxAmount;
        public BigDecimal grossAmount;
        public BigDecimal netAmount;

        public SalesTaxBreakdown(String taxName, String taxDescription, BigDecimal taxRate,
                BigDecimal taxAmount, BigDecimal grossAmount, BigDecimal netAmount) {
            this.taxName = taxName;
            this.taxDescription = taxDescription;
            this.taxRate = taxRate != null ? taxRate : BigDecimal.ZERO;
            this.taxAmount = taxAmount != null ? taxAmount : BigDecimal.ZERO;
            this.grossAmount = grossAmount != null ? grossAmount : BigDecimal.ZERO;
            this.netAmount = netAmount != null ? netAmount : BigDecimal.ZERO;
        }
    }

    /**
     * Payment method sales breakdown
     */
    public static class PaymentMethodSales {
        public String paymentMethod;
        public int transactionCount;
        public BigDecimal totalAmount; // Gross amount (includes tax & GPI)
        public BigDecimal netAmount; // Net amount (excludes tax & GPI)
        public BigDecimal gpi = BigDecimal.ZERO;

        public PaymentMethodSales(String paymentMethod, int transactionCount, BigDecimal totalAmount,
                BigDecimal netAmount) {
            this(paymentMethod, transactionCount, totalAmount, netAmount, BigDecimal.ZERO);
        }

        public PaymentMethodSales(String paymentMethod, int transactionCount, BigDecimal totalAmount,
                BigDecimal netAmount, BigDecimal gpi) {
            this.paymentMethod = paymentMethod;
            this.transactionCount = transactionCount;
            this.totalAmount = totalAmount != null ? totalAmount : BigDecimal.ZERO;
            this.netAmount = netAmount != null ? netAmount : BigDecimal.ZERO;
            this.gpi = gpi != null ? gpi : BigDecimal.ZERO;
        }
    }

    /**
     * Cashier Action entry
     */
    public static class CashierAction {
        public String actionName;
        public int quantity;
        public BigDecimal amount;

        public CashierAction(String actionName, int quantity, BigDecimal amount) {
            this.actionName = actionName;
            this.quantity = quantity;
            this.amount = amount != null ? amount : BigDecimal.ZERO;
        }
    }

    // ==================== Data Aggregation Methods ====================

    /**
     * Generate End of Day report for a specific date
     */
    public EndOfDayReport generateReport(LocalDate date, String createdBy) throws SQLException {
        return generateReport(date, date, createdBy, null);
    }

    public EndOfDayReport generateReport(LocalDate date, String createdBy, String cashierId) throws SQLException {
        return generateReport(date, date, createdBy, cashierId);
    }

    /**
     * Generate End of Day report for a date range
     */
    public EndOfDayReport generateReport(LocalDate startDate, LocalDate endDate, String createdBy) throws SQLException {
        return generateReport(startDate, endDate, createdBy, null);
    }

    public EndOfDayReport generateReport(LocalDate startDate, LocalDate endDate, String createdBy, String cashierId)
            throws SQLException {
        logger.info("Generating End of Day report for date range: {} to {}", startDate, endDate);

        EndOfDayReport report = new EndOfDayReport();

        // Set metadata
        report.posId = config.getProperty("device.name", "POS 01");
        report.cashier = "<all>";
        report.cashDrawer = "<all>";
        report.startDate = startDate;
        report.endDate = endDate;
        report.creationDate = LocalDateTime.now();
        report.createdBy = createdBy != null ? createdBy : "System";

        // 1. Transaction counts (Z-Count)
        int[] counts = getZCountRange(startDate, endDate, null, cashierId);
        report.zCountFrom = counts[0];
        report.zCountTo = counts[1];

        // 2. Revenue by Commodity Group (Departments)
        report.commodityGroups = getCommodityGroupRevenue(startDate, endDate, null, cashierId);

        // 3. Revenue by Customer Group
        report.customerGroups = getCustomerGroupRevenue(startDate, endDate, null, cashierId);

        // 4. Income and Expenses (Cash Adds/Drops, Fees)
        report.incomeExpenses = getIncomeExpenses(startDate, endDate, null, cashierId);

        // 5. Sales Tax Breakdown
        report.salesTaxes = getSalesTaxBreakdown(startDate, endDate, null, cashierId);

        // 6. Payment Method Sales
        report.paymentMethods = getPaymentMethodSales(startDate, endDate, null, cashierId);

        // 7. Vendor Payouts
        report.vendorPayouts = getVendorPayouts(startDate, endDate, null, cashierId);

        // 8. Expenses
        report.expenses = getExpenses(startDate, endDate, null, cashierId);

        // 9. Cashier Actions
        report.cashierActions = getCashierActions(startDate, endDate, null, cashierId);

        // Calculate totals
        calculateTotals(report);

        // Calculate summary totals
        calculateSummaryTotals(report, startDate, endDate, null, cashierId);

        // Align the tax rows after summary totals are known so every printed total
        // matches the same exact source values.
        alignSalesTaxWithSummaryTotals(report);

        logger.info("End of Day report generated successfully");
        return report;
    }

    /**
     * Get Z-Count range (first and last transaction numbers for the day)
     */
    private int[] getZCountRange(LocalDate startDate, LocalDate endDate, String shiftId, String cashierId)
            throws SQLException {
        // Count transactions
        String countSql;
        if (shiftId != null) {
            countSql = "SELECT COUNT(*) as transaction_count FROM sales WHERE (shift_id = ? OR id = ?) AND (voided = FALSE OR voided IS NULL) AND (pos_user_id = ? OR ? IS NULL)";
        } else {
            countSql = "SELECT COUNT(*) as transaction_count FROM sales WHERE substr(created_at, 1, 10) BETWEEN ? AND ? AND (voided = FALSE OR voided IS NULL) AND (pos_user_id = ? OR ? IS NULL)";
        }

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(countSql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, shiftId);
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                int count = rs.getInt("transaction_count");
                return new int[] { count > 0 ? 1 : 0, count };
            }
        }
        return new int[] { 0, 0 };
    }

    /**
     * Get revenue by commodity group (department/category)
     * Calculates revenue as a portion of the Net Sales to ensure totals match.
     * Item Revenue = (Item Subtotal / Sale Subtotal) * (Sale Net Sales)
     * Where Sale Net Sales = Sale Subtotal - Sale GPI + Sale Tax
     * 
     * Note: A rounding adjustment is applied to the largest revenue group to ensure
     * the sum of commodity group revenues exactly matches the net sales total.
     */
    private List<CommodityGroupRevenue> getCommodityGroupRevenue(LocalDate startDate, LocalDate endDate, String shiftId,
            String cashierId)
            throws SQLException {
        String sql;
        if (shiftId != null) {
            sql = """
                    SELECT
                        1 as group_number,
                        COALESCE(si.department_name, d.name, 'Uncategorized') as group_name,
                        SUM(si.quantity) as total_quantity,
                        SUM(si.subtotal) as total_revenue,
                        SUM(si.discount) as total_discount,
                        SUM(si.gpi) as total_gpi
                    FROM sales s
                    JOIN sale_items si ON s.sale_id = si.sale_id
                    LEFT JOIN departments d ON si.department_id = d.id
                    WHERE (s.shift_id = ? OR s.id = ?) AND (s.voided = FALSE OR s.voided IS NULL)
                      AND (s.pos_user_id = ? OR ? IS NULL)
                    GROUP BY group_number, group_name
                    ORDER BY group_number, group_name
                    """;
        } else {
            sql = """
                    SELECT
                        1 as group_number,
                        COALESCE(si.department_name, d.name, 'Uncategorized') as group_name,
                        SUM(si.quantity) as total_quantity,
                        SUM(si.subtotal) as total_revenue,
                        SUM(si.discount) as total_discount,
                        SUM(si.gpi) as total_gpi
                    FROM sales s
                    JOIN sale_items si ON s.sale_id = si.sale_id
                    LEFT JOIN departments d ON si.department_id = d.id
                    WHERE substr(s.created_at, 1, 10) BETWEEN ? AND ? AND (s.voided = FALSE OR s.voided IS NULL)
                      AND (s.pos_user_id = ? OR ? IS NULL)
                    GROUP BY group_number, group_name
                    ORDER BY group_number, group_name
                    """;
        }

        List<CommodityGroupRevenue> groups = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, shiftId);
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String deptName = rs.getString("group_name");
                int quantity = rs.getInt("total_quantity");
                BigDecimal revenue = rs.getBigDecimal("total_revenue");
                BigDecimal discount = rs.getBigDecimal("total_discount");
                BigDecimal gpi = rs.getBigDecimal("total_gpi");

                // Revenue should reflect merchandise net sales only.
                if (revenue != null) {
                    if (discount != null) {
                        revenue = revenue.subtract(discount);
                    }
                    if (gpi != null) {
                        revenue = revenue.subtract(gpi);
                    }
                }

                groups.add(new CommodityGroupRevenue(
                        rs.getInt("group_number"),
                        deptName,
                        quantity,
                        revenue,
                        discount));
            }
        }

        // If no groups found, add a default entry with zeros
        if (groups.isEmpty()) {
            groups.add(new CommodityGroupRevenue(1, "No Sales", 0, BigDecimal.ZERO, BigDecimal.ZERO));
        }
        // Apply rounding adjustment to ensure commodity group totals match net sales
        if (!groups.isEmpty() && groups.get(0).revenue.compareTo(BigDecimal.ZERO) > 0) {
            applyRoundingAdjustment(groups, startDate, endDate, shiftId, cashierId);
        }

        return groups;
    }

    /**
     * Apply rounding adjustment to commodity group revenues to match net sales.
     * The adjustment is applied to the largest revenue group to minimize impact.
     */
    private void applyRoundingAdjustment(List<CommodityGroupRevenue> groups, LocalDate startDate, LocalDate endDate,
            String shiftId, String cashierId) {
        try {
            // Get actual net sales from sales table
            String netSalesSql;
            if (shiftId != null) {
                netSalesSql = """
                        SELECT
                            COALESCE(SUM(subtotal), 0) - COALESCE(SUM(gpi), 0) as net_sales
                        FROM sales
                        WHERE (shift_id = ? OR id = ?) AND (voided = FALSE OR voided IS NULL)
                          AND (pos_user_id = ? OR ? IS NULL)
                        """;
            } else {
                netSalesSql = """
                        SELECT
                            COALESCE(SUM(subtotal), 0) - COALESCE(SUM(gpi), 0) as net_sales
                        FROM sales
                        WHERE substr(created_at, 1, 10) BETWEEN ? AND ? AND (voided = FALSE OR voided IS NULL)
                          AND (pos_user_id = ? OR ? IS NULL)
                        """;
            }

            BigDecimal actualNetSales = BigDecimal.ZERO;
            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(netSalesSql)) {
                if (shiftId != null) {
                    stmt.setString(1, shiftId);
                    stmt.setString(2, shiftId);
                    stmt.setString(3, cashierId);
                    stmt.setString(4, cashierId);
                } else {
                    stmt.setDate(1, java.sql.Date.valueOf(startDate));
                    stmt.setDate(2, java.sql.Date.valueOf(endDate));
                    stmt.setString(3, cashierId);
                    stmt.setString(4, cashierId);
                }
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    actualNetSales = rs.getBigDecimal("net_sales");
                    if (actualNetSales == null) {
                        actualNetSales = BigDecimal.ZERO;
                    }
                }
            }

            // Calculate current sum of commodity group revenues
            BigDecimal currentSum = groups.stream()
                    .map(g -> g.revenue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Calculate difference (rounding error)
            BigDecimal difference = actualNetSales.subtract(currentSum);

            // If there's a difference, apply it to the first (largest) group
            if (difference.compareTo(BigDecimal.ZERO) != 0 && !groups.isEmpty()) {
                CommodityGroupRevenue largestGroup = groups.get(0);
                largestGroup.revenue = largestGroup.revenue.add(difference);
                logger.debug("Applied rounding adjustment of {} to group '{}' to match net sales",
                        difference, largestGroup.groupName);
            }
        } catch (SQLException e) {
            logger.warn("Error applying rounding adjustment to commodity groups: {}", e.getMessage());
        }
    }

    /**
     * Get revenue by customer group
     * For now, all customers are in "Default" group since we don't have customer
     * management
     */
    private List<CustomerGroupRevenue> getCustomerGroupRevenue(LocalDate startDate, LocalDate endDate, String shiftId,
            String cashierId)
            throws SQLException {
        // Customer groups not fully implemented in DB yet, return default "All
        // Customers"
        // This is a placeholder that aggregates all sales into one group
        String sql;
        if (shiftId != null) {
            sql = """
                    SELECT
                        1 as group_number,
                        'Standard' as group_name,
                        SUM(si.quantity) as total_quantity,
                        SUM(si.subtotal) as total_revenue,
                        SUM(si.discount) as total_discount,
                        SUM(si.gpi) as total_gpi
                    FROM sales s
                    JOIN sale_items si ON s.sale_id = si.sale_id
                    WHERE (s.shift_id = ? OR s.id = ?)
                      AND (s.voided = FALSE OR s.voided IS NULL)
                      AND (s.pos_user_id = ? OR ? IS NULL)
                    """;
        } else {
            sql = """
                    SELECT
                        1 as group_number,
                        'Standard' as group_name,
                        SUM(si.quantity) as total_quantity,
                        SUM(si.subtotal) as total_revenue,
                        SUM(si.discount) as total_discount,
                        SUM(si.gpi) as total_gpi
                    FROM sales s
                    JOIN sale_items si ON s.sale_id = si.sale_id
                    WHERE substr(s.created_at, 1, 10) BETWEEN ? AND ?
                      AND (s.voided = FALSE OR s.voided IS NULL)
                      AND (s.pos_user_id = ? OR ? IS NULL)
                    """;
        }

        List<CustomerGroupRevenue> groups = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, shiftId);
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                int quantity = rs.getInt("total_quantity");
                BigDecimal revenue = rs.getBigDecimal("total_revenue");
                BigDecimal discount = rs.getBigDecimal("total_discount");
                BigDecimal gpi = rs.getBigDecimal("total_gpi");

                if (revenue != null) {
                    if (discount != null) {
                        revenue = revenue.subtract(discount);
                    }
                    if (gpi != null) {
                        revenue = revenue.subtract(gpi);
                    }
                }

                if (revenue != null && (quantity > 0 || revenue.compareTo(BigDecimal.ZERO) > 0)) {
                    groups.add(new CustomerGroupRevenue(
                            rs.getInt("group_number"),
                            rs.getString("group_name"),
                            quantity,
                            revenue,
                            discount));
                }
            }
        }

        // If no groups found, add a default entry with zeros
        if (groups.isEmpty()) {
            groups.add(new CustomerGroupRevenue(1, "Default", 0, BigDecimal.ZERO, BigDecimal.ZERO));
        }

        if (!groups.isEmpty() && groups.get(0).revenue.compareTo(BigDecimal.ZERO) > 0) {
            applyCustomerGroupRoundingAdjustment(groups, startDate, endDate, shiftId, cashierId);
        }

        return groups;
    }

    private void applyCustomerGroupRoundingAdjustment(List<CustomerGroupRevenue> groups, LocalDate startDate,
            LocalDate endDate, String shiftId, String cashierId) {
        try {
            BigDecimal actualNetSales = getActualNetSales(startDate, endDate, shiftId, cashierId);
            BigDecimal currentSum = groups.stream()
                    .map(g -> g.revenue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal difference = actualNetSales.subtract(currentSum);

            if (difference.compareTo(BigDecimal.ZERO) != 0) {
                groups.get(0).revenue = groups.get(0).revenue.add(difference);
                logger.debug("Applied rounding adjustment of {} to customer group '{}' to match net sales",
                        difference, groups.get(0).groupName);
            }
        } catch (SQLException e) {
            logger.warn("Error applying rounding adjustment to customer groups: {}", e.getMessage());
        }
    }

    private BigDecimal getActualNetSales(LocalDate startDate, LocalDate endDate, String shiftId, String cashierId)
            throws SQLException {
        String netSalesSql;
        if (shiftId != null) {
            netSalesSql = """
                    SELECT
                        COALESCE(SUM(subtotal), 0) - COALESCE(SUM(gpi), 0) as net_sales
                    FROM sales
                    WHERE (shift_id = ? OR id = ?) AND (voided = FALSE OR voided IS NULL)
                      AND (pos_user_id = ? OR ? IS NULL)
                    """;
        } else {
            netSalesSql = """
                    SELECT
                        COALESCE(SUM(subtotal), 0) - COALESCE(SUM(gpi), 0) as net_sales
                    FROM sales
                    WHERE substr(created_at, 1, 10) BETWEEN ? AND ? AND (voided = FALSE OR voided IS NULL)
                      AND (pos_user_id = ? OR ? IS NULL)
                    """;
        }

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(netSalesSql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, shiftId);
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                BigDecimal actualNetSales = rs.getBigDecimal("net_sales");
                return actualNetSales != null ? actualNetSales : BigDecimal.ZERO;
            }
        }

        return BigDecimal.ZERO;
    }

    /**
     * Get income/expenses (fees, cash operations, etc.)
     */
    private List<IncomeExpense> getIncomeExpenses(LocalDate startDate, LocalDate endDate, String shiftId,
            String cashierId) throws SQLException {
        List<IncomeExpense> result = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {

        // 1. Calculate GPI (Gross Profit Increase) from sales
        String gpiSql;
        if (shiftId != null) {
            gpiSql = "SELECT SUM(gpi) as total_gpi, COUNT(*) as count FROM sales WHERE (shift_id = ? OR id = ?) AND (voided = FALSE OR voided IS NULL) AND (pos_user_id = ? OR ? IS NULL)";
        } else {
            gpiSql = "SELECT SUM(gpi) as total_gpi, COUNT(*) as count FROM sales WHERE substr(created_at, 1, 10) BETWEEN ? AND ? AND (voided = FALSE OR voided IS NULL) AND (pos_user_id = ? OR ? IS NULL)";
        }

        try (PreparedStatement stmt = conn.prepareStatement(gpiSql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, shiftId);
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                BigDecimal totalGpi = rs.getBigDecimal("total_gpi");
                if (totalGpi != null && totalGpi.compareTo(BigDecimal.ZERO) != 0) {
                    result.add(new IncomeExpense(101, "FEE / SURCHARGE", rs.getInt("count"), totalGpi));
                }
            }
        }

        // 2. Calculate EBT Fees from sales
        String ebtSql;
        if (shiftId != null) {
            ebtSql = "SELECT SUM(ebt_fee) as total_ebt, COUNT(*) as count FROM sales WHERE (shift_id = ? OR id = ?) AND (voided = FALSE OR voided IS NULL) AND ebt_fee > 0 AND (pos_user_id = ? OR ? IS NULL)";
        } else {
            ebtSql = "SELECT SUM(ebt_fee) as total_ebt, COUNT(*) as count FROM sales WHERE substr(created_at, 1, 10) BETWEEN ? AND ? AND (voided = FALSE OR voided IS NULL) AND ebt_fee > 0 AND (pos_user_id = ? OR ? IS NULL)";
        }

        try (PreparedStatement stmt = conn.prepareStatement(ebtSql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, shiftId);
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                BigDecimal totalEbt = rs.getBigDecimal("total_ebt");
                if (totalEbt != null && totalEbt.compareTo(BigDecimal.ZERO) != 0) {
                    result.add(new IncomeExpense(102, "EBT FEE", rs.getInt("count"), totalEbt));
                }
            }
        }

        // 3. Cash Operations (Adds/Drops)
        if (shiftId != null) {
            String opsSql = """
                    SELECT type, SUM(amount) as total_amount, COUNT(*) as count
                    FROM cash_operations
                    WHERE shift_id = ? AND (performed_by = ? OR ? IS NULL)
                    GROUP BY type
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(opsSql)) {
                stmt.setString(1, shiftId);
                stmt.setString(2, cashierId);
                stmt.setString(3, cashierId);

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    String type = rs.getString("type");
                    BigDecimal amount = rs.getBigDecimal("total_amount");
                    int count = rs.getInt("count");

                    if ("ADD".equals(type)) {
                        result.add(new IncomeExpense(201, "CASH IN (ADD)", count, amount));
                    } else if ("DROP".equals(type)) {
                        result.add(new IncomeExpense(202, "CASH OUT (DROP)", count, amount.negate()));
                    }
                }
            }
        } else {
            // Cash operations are stored as UTC instants. Convert each operation to the
            // workstation's local business day before applying the EOD date filter.
            String opsSql = """
                    SELECT type, amount, created_at
                    FROM cash_operations
                    WHERE (performed_by = ? OR ? IS NULL)
                    """;

            BigDecimal addAmount = BigDecimal.ZERO;
            BigDecimal dropAmount = BigDecimal.ZERO;
            int addCount = 0;
            int dropCount = 0;

            try (PreparedStatement stmt = conn.prepareStatement(opsSql)) {
                stmt.setString(1, cashierId);
                stmt.setString(2, cashierId);

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    LocalDate operationDate = resolveLocalBusinessDate(rs.getString("created_at"));
                    if (operationDate == null
                            || operationDate.isBefore(startDate)
                            || operationDate.isAfter(endDate)) {
                        continue;
                    }

                    String type = rs.getString("type");
                    BigDecimal amount = rs.getBigDecimal("amount");
                    if (amount == null) {
                        amount = BigDecimal.ZERO;
                    }

                    if ("ADD".equals(type)) {
                        addAmount = addAmount.add(amount);
                        addCount++;
                    } else if ("DROP".equals(type)) {
                        dropAmount = dropAmount.add(amount);
                        dropCount++;
                    }
                }
            }

            if (addCount > 0) {
                result.add(new IncomeExpense(201, "CASH IN (ADD)", addCount, addAmount));
            }
            if (dropCount > 0) {
                result.add(new IncomeExpense(202, "CASH OUT (DROP)", dropCount, dropAmount.negate()));
            }
        }

        }

        return result;
    }

    private LocalDate resolveLocalBusinessDate(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }

        ZoneId zoneId = ZoneId.systemDefault();

        try {
            return Instant.parse(timestamp).atZone(zoneId).toLocalDate();
        } catch (Exception ignored) {
            // Fall back for legacy/non-UTC rows below.
        }

        try {
            return OffsetDateTime.parse(timestamp).atZoneSameInstant(zoneId).toLocalDate();
        } catch (Exception ignored) {
            // Fall back to legacy yyyy-MM-dd extraction below.
        }

        if (timestamp.length() >= 10) {
            try {
                return LocalDate.parse(timestamp.substring(0, 10));
            } catch (Exception e) {
                logger.debug("Could not parse local business date from cash operation timestamp '{}'", timestamp, e);
            }
        }

        return null;
    }

    /**
     * Get expenses for the day
     */
    private List<ExpenseEntry> getExpenses(LocalDate startDate, LocalDate endDate, String shiftId, String cashierId)
            throws SQLException {
        List<ExpenseEntry> expenseEntries = new ArrayList<>();

        String sql;
        if (shiftId != null) {
            if (cashierId != null) {
                sql = """
                        SELECT e.category_name, e.amount, e.description, e.payment_method
                        FROM expenses e
                        WHERE e.shift_id = ?
                          AND EXISTS (
                              SELECT 1
                              FROM shifts sh
                              WHERE sh.id = e.shift_id
                                AND sh.cashier_id = ?
                          )
                        ORDER BY e.created_at DESC
                        """;
            } else {
                sql = "SELECT category_name, amount, description, payment_method FROM expenses WHERE shift_id = ? ORDER BY created_at DESC";
            }
        } else {
            if (cashierId != null) {
                sql = """
                        SELECT e.category_name, e.amount, e.description, e.payment_method
                        FROM expenses e
                        WHERE CAST(e.created_at AS DATE) BETWEEN ? AND ?
                          AND EXISTS (
                              SELECT 1
                              FROM shifts sh
                              WHERE sh.id = e.shift_id
                                AND sh.cashier_id = ?
                          )
                        ORDER BY e.created_at DESC
                        """;
            } else {
                sql = "SELECT category_name, amount, description, payment_method FROM expenses WHERE CAST(created_at AS DATE) BETWEEN ? AND ? ORDER BY created_at DESC";
            }
        }

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                if (cashierId != null) {
                    stmt.setString(2, cashierId);
                }
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                if (cashierId != null) {
                    stmt.setString(3, cashierId);
                }
            }
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                expenseEntries.add(new ExpenseEntry(
                        rs.getString("category_name"),
                        rs.getBigDecimal("amount"),
                        rs.getString("description"),
                        rs.getString("payment_method")));
            }
        } catch (Exception e) {
            logger.warn("Error getting expenses for EOD report: {}", e.getMessage());
        }

        return expenseEntries;
    }

    /**
     * Get sales tax breakdown by department
     * Uses the actual stored tax values (proportionally distributed) to ensure
     * totals match the Commodity Group Revenue section exactly.
     */
    private List<SalesTaxBreakdown> getSalesTaxBreakdown(LocalDate startDate, LocalDate endDate, String shiftId,
            String cashierId) throws SQLException {
        List<SalesTaxBreakdown> taxes = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {
        BigDecimal defaultTaxRate = settingsService.getDefaultTaxRate();

        // Query uses the same merchandise net-sales basis as the commodity and
        // customer sections. Tax is allocated proportionally using each item's share
        // of sale net sales.
        String sql;
        if (shiftId != null) {
            sql = """
                    SELECT
                        COALESCE(si.department_name, d.name, pd.name, 'General') as department_name,
                        COALESCE(d.tax_enabled, pd.tax_enabled, TRUE) as tax_enabled,
                        SUM(COALESCE(si.subtotal, 0) - COALESCE(si.discount, 0) - COALESCE(si.gpi, 0)) as net_sales_amount,
                        SUM(COALESCE(si.discount, 0)) as discount_amount,
                        SUM(
                            CASE
                                WHEN (s.subtotal - s.gpi) = 0 THEN 0
                                ELSE ((COALESCE(si.subtotal, 0) - COALESCE(si.discount, 0) - COALESCE(si.gpi, 0)) * s.gpi) / (s.subtotal - s.gpi)
                            END
                        ) as gpi_portion,
                        SUM(
                            CASE
                                WHEN (s.subtotal - s.gpi) = 0 THEN 0
                                ELSE ((COALESCE(si.subtotal, 0) - COALESCE(si.discount, 0) - COALESCE(si.gpi, 0)) * COALESCE(s.ebt_fee, 0)) / (s.subtotal - s.gpi)
                            END
                        ) as ebt_portion,
                        SUM(
                            CASE
                                WHEN (s.subtotal - s.gpi) = 0 THEN 0
                                ELSE ((COALESCE(si.subtotal, 0) - COALESCE(si.discount, 0) - COALESCE(si.gpi, 0)) * s.tax) / (s.subtotal - s.gpi)
                            END
                        ) as tax_portion
                    FROM sale_items si
                    INNER JOIN sales s ON si.sale_id = s.sale_id
                    LEFT JOIN departments d ON si.department_id = d.id
                    LEFT JOIN products p ON si.product_id = p.id
                    LEFT JOIN departments pd ON p.department_id = pd.id
                    WHERE (s.shift_id = ? OR s.id = ?)
                      AND (s.voided = FALSE OR s.voided IS NULL)
                      AND (s.pos_user_id = ? OR ? IS NULL)
                    GROUP BY COALESCE(si.department_name, d.name, pd.name, 'General'),
                             COALESCE(d.tax_enabled, pd.tax_enabled, TRUE)
                    ORDER BY net_sales_amount DESC
                    """;
        } else {
            sql = """
                    SELECT
                        COALESCE(si.department_name, d.name, pd.name, 'General') as department_name,
                        COALESCE(d.tax_enabled, pd.tax_enabled, TRUE) as tax_enabled,
                        SUM(COALESCE(si.subtotal, 0) - COALESCE(si.discount, 0) - COALESCE(si.gpi, 0)) as net_sales_amount,
                        SUM(COALESCE(si.discount, 0)) as discount_amount,
                        SUM(
                            CASE
                                WHEN (s.subtotal - s.gpi) = 0 THEN 0
                                ELSE ((COALESCE(si.subtotal, 0) - COALESCE(si.discount, 0) - COALESCE(si.gpi, 0)) * s.gpi) / (s.subtotal - s.gpi)
                            END
                        ) as gpi_portion,
                        SUM(
                            CASE
                                WHEN (s.subtotal - s.gpi) = 0 THEN 0
                                ELSE ((COALESCE(si.subtotal, 0) - COALESCE(si.discount, 0) - COALESCE(si.gpi, 0)) * COALESCE(s.ebt_fee, 0)) / (s.subtotal - s.gpi)
                            END
                        ) as ebt_portion,
                        SUM(
                            CASE
                                WHEN (s.subtotal - s.gpi) = 0 THEN 0
                                ELSE ((COALESCE(si.subtotal, 0) - COALESCE(si.discount, 0) - COALESCE(si.gpi, 0)) * s.tax) / (s.subtotal - s.gpi)
                            END
                        ) as tax_portion
                    FROM sale_items si
                    INNER JOIN sales s ON si.sale_id = s.sale_id
                    LEFT JOIN departments d ON si.department_id = d.id
                    LEFT JOIN products p ON si.product_id = p.id
                    LEFT JOIN departments pd ON p.department_id = pd.id
                    WHERE substr(s.created_at, 1, 10) BETWEEN ? AND ?
                      AND (s.voided = FALSE OR s.voided IS NULL)
                      AND (s.pos_user_id = ? OR ? IS NULL)
                    GROUP BY COALESCE(si.department_name, d.name, pd.name, 'General'),
                             COALESCE(d.tax_enabled, pd.tax_enabled, TRUE)
                    ORDER BY net_sales_amount DESC
                    """;
        }

        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, shiftId);
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String deptName = rs.getString("department_name");
                boolean taxEnabled = rs.getBoolean("tax_enabled");
                BigDecimal netSalesAmount = rs.getBigDecimal("net_sales_amount");
                BigDecimal discountAmount = rs.getBigDecimal("discount_amount");
                BigDecimal gpiPortion = rs.getBigDecimal("gpi_portion");
                BigDecimal ebtPortion = rs.getBigDecimal("ebt_portion");
                BigDecimal taxPortion = rs.getBigDecimal("tax_portion");

                if (netSalesAmount == null)
                    netSalesAmount = BigDecimal.ZERO;
                if (discountAmount == null)
                    discountAmount = BigDecimal.ZERO;
                if (gpiPortion == null)
                    gpiPortion = BigDecimal.ZERO;
                if (ebtPortion == null)
                    ebtPortion = BigDecimal.ZERO;
                if (taxPortion == null)
                    taxPortion = BigDecimal.ZERO;

                BigDecimal netAmount = netSalesAmount;

                // Use actual proportionally distributed tax from sales table
                // If tax is not enabled for this department, the actual s.tax should already
                // reflect zero tax for these items, but we zero it out defensively
                BigDecimal taxAmount = taxEnabled ? taxPortion : BigDecimal.ZERO;

                // Gross (taxable basis): net + discounts + surcharge + allocated EBT fees; tax column separate.
                BigDecimal grossAmount = netAmount.add(discountAmount).add(gpiPortion).add(ebtPortion);

                BigDecimal effectiveTaxRate = defaultTaxRate;

                // Format tax rate for display
                String taxRateStr = effectiveTaxRate.multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";

                taxes.add(new SalesTaxBreakdown(
                        taxRateStr,
                        deptName,
                        effectiveTaxRate,
                        taxAmount,
                        grossAmount,
                        netAmount));

                totalTax = totalTax.add(taxAmount);
                totalGross = totalGross.add(grossAmount);
                totalNet = totalNet.add(netAmount);
            }
        }

        // If no department-specific taxes found, fall back to overall totals
        if (taxes.isEmpty()) {
            String fallbackSql;
            if (shiftId != null) {
                fallbackSql = """
                        SELECT
                            COALESCE(SUM(tax), 0) as total_tax,
                            COALESCE(SUM(subtotal), 0) - COALESCE(SUM(gpi), 0) as total_net,
                            COALESCE(SUM(subtotal), 0) + COALESCE(SUM(discount), 0) + COALESCE(SUM(ebt_fee), 0) as total_gross
                        FROM sales
                        WHERE (shift_id = ? OR id = ?)
                          AND (voided = FALSE OR voided IS NULL)
                          AND (pos_user_id = ? OR ? IS NULL)
                        """;
            } else {
                fallbackSql = """
                        SELECT
                            COALESCE(SUM(tax), 0) as total_tax,
                            COALESCE(SUM(subtotal), 0) - COALESCE(SUM(gpi), 0) as total_net,
                            COALESCE(SUM(subtotal), 0) + COALESCE(SUM(discount), 0) + COALESCE(SUM(ebt_fee), 0) as total_gross
                        FROM sales
                        WHERE substr(created_at, 1, 10) BETWEEN ? AND ?
                          AND (voided = FALSE OR voided IS NULL)
                          AND (pos_user_id = ? OR ? IS NULL)
                        """;
            }

            try (PreparedStatement stmt = conn.prepareStatement(fallbackSql)) {
                if (shiftId != null) {
                    stmt.setString(1, shiftId);
                    stmt.setString(2, shiftId);
                    stmt.setString(3, cashierId);
                    stmt.setString(4, cashierId);
                } else {
                    stmt.setDate(1, java.sql.Date.valueOf(startDate));
                    stmt.setDate(2, java.sql.Date.valueOf(endDate));
                    stmt.setString(3, cashierId);
                    stmt.setString(4, cashierId);
                }
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    BigDecimal tax = rs.getBigDecimal("total_tax");
                    BigDecimal net = rs.getBigDecimal("total_net");
                    BigDecimal gross = rs.getBigDecimal("total_gross");

                    String taxRateStr = defaultTaxRate.multiply(BigDecimal.valueOf(100))
                            .setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";

                    taxes.add(new SalesTaxBreakdown(
                            taxRateStr,
                            "General\nSales Tax",
                            defaultTaxRate,
                            tax != null ? tax : BigDecimal.ZERO,
                            gross != null ? gross : BigDecimal.ZERO,
                            net != null ? net : BigDecimal.ZERO));
                }
            }
        }

        // If still no taxes, add a zero entry
        if (taxes.isEmpty()) {
            String taxRateStr = defaultTaxRate.multiply(BigDecimal.valueOf(100))
                    .setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
            taxes.add(new SalesTaxBreakdown(taxRateStr, "General\nSales Tax", defaultTaxRate,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        }

        }

        return taxes;
    }

    /**
     * Get payment method sales breakdown
     * Aggregates sales by payment method including split payments
     */
    private List<PaymentMethodSales> getPaymentMethodSales(LocalDate startDate, LocalDate endDate, String shiftId,
            String cashierId) throws SQLException {
        java.util.Map<String, PaymentMethodSales> paymentMap = new java.util.LinkedHashMap<>();
        try (Connection conn = dbManager.getConnection()) {

        // First, get non-split payment sales
        String nonSplitSql;
        if (shiftId != null) {
            nonSplitSql = """
                    SELECT
                        COALESCE(payment_method, 'UNKNOWN') as payment_method,
                        COUNT(*) as transaction_count,
                        COALESCE(SUM(total), 0) as total_amount,
                        COALESCE(SUM(tax), 0) as total_tax,
                        COALESCE(SUM(gpi), 0) as total_gpi
                    FROM sales
                    WHERE (shift_id = ? OR id = ?)
                      AND (voided = FALSE OR voided IS NULL)
                      AND (is_split_payment = FALSE OR is_split_payment IS NULL)
                      AND (pos_user_id = ? OR ? IS NULL)
                    GROUP BY payment_method
                    ORDER BY total_amount DESC
                    """;
        } else {
            nonSplitSql = """
                    SELECT
                        COALESCE(payment_method, 'UNKNOWN') as payment_method,
                        COUNT(*) as transaction_count,
                        COALESCE(SUM(total), 0) as total_amount,
                        COALESCE(SUM(tax), 0) as total_tax,
                        COALESCE(SUM(gpi), 0) as total_gpi
                    FROM sales
                    WHERE substr(created_at, 1, 10) BETWEEN ? AND ?
                      AND (voided = FALSE OR voided IS NULL)
                      AND (is_split_payment = FALSE OR is_split_payment IS NULL)
                      AND (pos_user_id = ? OR ? IS NULL)
                    GROUP BY payment_method
                    ORDER BY total_amount DESC
                    """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(nonSplitSql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, shiftId);
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String method = canonicalPaymentMethod(rs.getString("payment_method"));
                int count = rs.getInt("transaction_count");
                BigDecimal amount = rs.getBigDecimal("total_amount");
                BigDecimal tax = rs.getBigDecimal("total_tax");
                BigDecimal gpi = rs.getBigDecimal("total_gpi");

                BigDecimal netAmount = amount.subtract(tax).subtract(gpi);

                PaymentMethodSales pms = paymentMap.get(method);
                if (pms == null) {
                    pms = new PaymentMethodSales(method, count, amount, netAmount, gpi);
                    paymentMap.put(method, pms);
                } else {
                    pms.transactionCount += count;
                    pms.totalAmount = pms.totalAmount.add(amount);
                    pms.netAmount = pms.netAmount.add(netAmount);
                    pms.gpi = pms.gpi.add(gpi);
                }
            }
        }

        // Then, get split payment components from sale_payments table
        String splitSql;
        if (shiftId != null) {
            splitSql = """
                    SELECT
                        sp.payment_method,
                        COUNT(*) as payment_count,
                        COALESCE(SUM(sp.amount), 0) as total_amount,
                        COALESCE(SUM(s.tax * (sp.amount / s.total)), 0) as total_tax,
                        COALESCE(SUM(s.gpi * (sp.amount / s.total)), 0) as total_gpi
                    FROM sale_payments sp
                    INNER JOIN sales s ON sp.sale_id = s.sale_id
                    WHERE (s.shift_id = ? OR s.id = ?)
                      AND (s.voided = FALSE OR s.voided IS NULL)
                      AND s.is_split_payment = TRUE
                      AND (s.pos_user_id = ? OR ? IS NULL)
                    GROUP BY sp.payment_method
                    """;
        } else {
            splitSql = """
                    SELECT
                        sp.payment_method,
                        COUNT(*) as payment_count,
                        COALESCE(SUM(sp.amount), 0) as total_amount,
                        COALESCE(SUM(s.tax * (sp.amount / s.total)), 0) as total_tax,
                        COALESCE(SUM(s.gpi * (sp.amount / s.total)), 0) as total_gpi
                    FROM sale_payments sp
                    INNER JOIN sales s ON sp.sale_id = s.sale_id
                    WHERE substr(s.created_at, 1, 10) BETWEEN ? AND ?
                      AND (s.voided = FALSE OR s.voided IS NULL)
                      AND s.is_split_payment = TRUE
                      AND (s.pos_user_id = ? OR ? IS NULL)
                    GROUP BY sp.payment_method
                    """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(splitSql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, shiftId);
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String method = canonicalPaymentMethod(rs.getString("payment_method"));
                int count = rs.getInt("payment_count");
                BigDecimal amount = rs.getBigDecimal("total_amount");
                BigDecimal tax = rs.getBigDecimal("total_tax");
                BigDecimal gpi = rs.getBigDecimal("total_gpi");

                BigDecimal netAmount = amount.subtract(tax).subtract(gpi);

                PaymentMethodSales pms = paymentMap.get(method);
                if (pms == null) {
                    pms = new PaymentMethodSales(method, count, amount, netAmount, gpi);
                    paymentMap.put(method, pms);
                } else {
                    pms.transactionCount += count;
                    pms.totalAmount = pms.totalAmount.add(amount);
                    pms.netAmount = pms.netAmount.add(netAmount);
                    pms.gpi = pms.gpi.add(gpi);
                }
            }
        }

        }

        return new ArrayList<>(paymentMap.values());
    }

    /**
     * Get cashier actions summary
     */
    private List<CashierAction> getCashierActions(LocalDate startDate, LocalDate endDate, String shiftId,
            String cashierId) throws SQLException {
        List<CashierAction> actions = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {

        // 1. Item cancellations (from cart_cancellations - prefixed with VOID:)
        String itemCancelSql;
        if (shiftId != null) {
            itemCancelSql = """
                    SELECT COUNT(*) as qty, COALESCE(SUM(total_value), 0) as amount
                    FROM cart_cancellations cc
                    WHERE cc.shift_id = ?
                      AND (cc.receipt_number LIKE 'VOID:%')
                      AND (cc.pos_user_id = ? OR ? IS NULL)
                    """;
        } else {
            itemCancelSql = """
                    SELECT COUNT(*) as qty, COALESCE(SUM(total_value), 0) as amount
                    FROM cart_cancellations
                    WHERE CAST(timestamp AS DATE) BETWEEN ? AND ?
                      AND (receipt_number LIKE 'VOID:%')
                      AND (pos_user_id = ? OR ? IS NULL)
                    """;
        }
        try (PreparedStatement stmt = conn.prepareStatement(itemCancelSql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, cashierId);
                stmt.setString(3, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                actions.add(new CashierAction("Item cancellations", rs.getInt("qty"), rs.getBigDecimal("amount")));
            }
        }

        // 2. Receipt cancellations (from cart_cancellations - NOT prefixed with VOID:)
        String receiptCancelSql;
        if (shiftId != null) {
            receiptCancelSql = """
                    SELECT COUNT(*) as qty, COALESCE(SUM(total_value), 0) as amount
                    FROM cart_cancellations cc
                    WHERE cc.shift_id = ?
                      AND (cc.receipt_number NOT LIKE 'VOID:%')
                      AND (cc.pos_user_id = ? OR ? IS NULL)
                    """;
        } else {
            receiptCancelSql = """
                    SELECT COUNT(*) as qty, COALESCE(SUM(total_value), 0) as amount
                    FROM cart_cancellations
                    WHERE CAST(timestamp AS DATE) BETWEEN ? AND ?
                      AND (receipt_number NOT LIKE 'VOID:%')
                      AND (pos_user_id = ? OR ? IS NULL)
                    """;
        }
        try (PreparedStatement stmt = conn.prepareStatement(receiptCancelSql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, cashierId);
                stmt.setString(3, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                actions.add(new CashierAction("Receipt cancellations", rs.getInt("qty"), rs.getBigDecimal("amount")));
            }
        }

        // 3. Receipt discounts
        String discountSql;
        if (shiftId != null) {
            discountSql = """
                    SELECT COUNT(*) as qty, COALESCE(SUM(discount), 0) as amount
                    FROM sales
                    WHERE (shift_id = ? OR id = ?)
                    AND (voided = FALSE OR voided IS NULL)
                    AND discount > 0
                    AND (pos_user_id = ? OR ? IS NULL)
                    """;
        } else {
            discountSql = """
                    SELECT COUNT(*) as qty, COALESCE(SUM(discount), 0) as amount
                    FROM sales
                    WHERE CAST(created_at AS DATE) BETWEEN ? AND ?
                    AND (voided = FALSE OR voided IS NULL)
                    AND discount > 0
                    AND (pos_user_id = ? OR ? IS NULL)
                    """;
        }
        try (PreparedStatement stmt = conn.prepareStatement(discountSql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, shiftId);
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                actions.add(new CashierAction("receipt discount", rs.getInt("qty"), rs.getBigDecimal("amount")));
            }
        }

        // 4. Receipts (removed/voided)
        String voidedSql;
        if (shiftId != null) {
            voidedSql = """
                    SELECT COUNT(*) as qty, COALESCE(SUM(total), 0) as amount
                    FROM sales
                    WHERE (shift_id = ? OR id = ?) AND voided = TRUE
                      AND (pos_user_id = ? OR ? IS NULL)
                    """;
        } else {
            voidedSql = """
                    SELECT COUNT(*) as qty, COALESCE(SUM(total), 0) as amount
                    FROM sales
                    WHERE CAST(created_at AS DATE) BETWEEN ? AND ? AND voided = TRUE
                      AND (pos_user_id = ? OR ? IS NULL)
                    """;
        }
        try (PreparedStatement stmt = conn.prepareStatement(voidedSql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, shiftId);
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                actions.add(new CashierAction("Receipts (removed)", rs.getInt("qty"), rs.getBigDecimal("amount")));
            }
        }

        // 5. Receipts (finished)
        String finishedSql;
        if (shiftId != null) {
            finishedSql = """
                    SELECT COUNT(*) as qty, COALESCE(SUM(total), 0) as amount
                    FROM sales
                    WHERE (shift_id = ? OR id = ?) AND (voided = FALSE OR voided IS NULL)
                      AND (pos_user_id = ? OR ? IS NULL)
                    """;
        } else {
            finishedSql = """
                    SELECT COUNT(*) as qty, COALESCE(SUM(total), 0) as amount
                    FROM sales
                    WHERE CAST(created_at AS DATE) BETWEEN ? AND ? AND (voided = FALSE OR voided IS NULL)
                      AND (pos_user_id = ? OR ? IS NULL)
                    """;
        }
        try (PreparedStatement stmt = conn.prepareStatement(finishedSql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, shiftId);
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                actions.add(new CashierAction("Receipts (finished)", rs.getInt("qty"), rs.getBigDecimal("amount")));
            }
        }

        // 6. Returns (Refunds)
        String refundSql;
        if (shiftId != null) {
            refundSql = """
                    SELECT COUNT(*) as qty, COALESCE(SUM(total_refund), 0) as amount
                    FROM refunds r
                    INNER JOIN sales s ON r.original_sale_id = s.sale_id
                    WHERE (s.shift_id = ? OR s.id = ?)
                      AND (s.pos_user_id = ? OR ? IS NULL)
                    """;
        } else {
            refundSql = """
                    SELECT COUNT(*) as qty, COALESCE(SUM(total_refund), 0) as amount
                    FROM refunds r
                    INNER JOIN sales s ON r.original_sale_id = s.sale_id
                    WHERE CAST(s.created_at AS DATE) BETWEEN ? AND ?
                      AND (s.pos_user_id = ? OR ? IS NULL)
                    """;
        }
        try (PreparedStatement stmt = conn.prepareStatement(refundSql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, shiftId);
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                actions.add(new CashierAction("Returns", rs.getInt("qty"), rs.getBigDecimal("amount")));
            }
        }

        // 7. POS cash checks (No Sale) - join with shifts to tie to shift's business
        // day
        String noSaleSql;
        if (shiftId != null) {
            noSaleSql = """
                    SELECT COUNT(*) as qty, 0 as amount
                    FROM cash_operations co
                    WHERE co.shift_id = ? AND co.type = 'NO_SALE'
                      AND (co.performed_by = ? OR ? IS NULL)
                    """;
        } else {
            noSaleSql = """
                    SELECT COUNT(*) as qty, 0 as amount
                    FROM cash_operations co
                    INNER JOIN shifts sh ON co.shift_id = sh.id
                    WHERE sh.shift_started_at >= ? AND sh.shift_started_at <= ? AND co.type = 'NO_SALE'
                      AND (co.performed_by = ? OR ? IS NULL)
                    """;
        }
        try (PreparedStatement stmt = conn.prepareStatement(noSaleSql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, cashierId);
                stmt.setString(3, cashierId);
            } else {
                String noSaleStartStr = startDate.atStartOfDay().toString() + "Z";
                String noSaleEndStr = endDate.atTime(23, 59, 59).toString() + "Z";
                stmt.setString(1, noSaleStartStr);
                stmt.setString(2, noSaleEndStr);
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                actions.add(new CashierAction("POS cash checks", rs.getInt("qty"), BigDecimal.ZERO));
            }
        }

        // Return goods (Items returned)
        String returnGoodsSql;
        if (shiftId != null) {
            returnGoodsSql = """
                    SELECT COUNT(*) as qty, COALESCE(SUM(ri.refund_amount), 0) as amount
                    FROM refund_items ri
                    INNER JOIN refunds r ON ri.refund_id = r.refund_id
                    INNER JOIN sales s ON r.original_sale_id = s.sale_id
                    WHERE (s.shift_id = ? OR s.id = ?)
                      AND (s.pos_user_id = ? OR ? IS NULL)
                    """;
        } else {
            returnGoodsSql = """
                    SELECT COUNT(*) as qty, COALESCE(SUM(ri.refund_amount), 0) as amount
                    FROM refund_items ri
                    INNER JOIN refunds r ON ri.refund_id = r.refund_id
                    INNER JOIN sales s ON r.original_sale_id = s.sale_id
                    WHERE CAST(r.created_at AS DATE) BETWEEN ? AND ?
                      AND (s.pos_user_id = ? OR ? IS NULL)
                    """;
        }
        try (PreparedStatement stmt = conn.prepareStatement(returnGoodsSql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, shiftId);
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                // Add at start to match screenshot order roughly
                actions.add(0, new CashierAction("Return goods", rs.getInt("qty"), rs.getBigDecimal("amount")));
            }
        }

        }

        return actions;
    }

    /**
     * Get vendor payouts made during the day
     */
    private List<VendorPayoutEntry> getVendorPayouts(LocalDate startDate, LocalDate endDate, String shiftId,
            String cashierId) throws SQLException {
        String sql;
        if (shiftId != null) {
            if (cashierId != null) {
                sql = """
                        SELECT vp.*
                        FROM vendor_payouts vp
                        WHERE vp.shift_id = ?
                          AND EXISTS (
                              SELECT 1
                              FROM shifts sh
                              WHERE sh.id = vp.shift_id
                                AND sh.cashier_id = ?
                          )
                        ORDER BY vp.paid_at DESC
                        """;
            } else {
                sql = """
                        SELECT vp.*
                        FROM vendor_payouts vp
                        WHERE vp.shift_id = ?
                        ORDER BY vp.paid_at DESC
                        """;
            }
        } else {
            if (cashierId != null) {
                sql = """
                        SELECT vp.*
                        FROM vendor_payouts vp
                        WHERE substr(vp.paid_at, 1, 10) BETWEEN ? AND ?
                          AND EXISTS (
                              SELECT 1
                              FROM shifts sh
                              WHERE sh.id = vp.shift_id
                                AND sh.cashier_id = ?
                          )
                        ORDER BY vp.paid_at DESC
                        """;
            } else {
                sql = """
                        SELECT vp.*
                        FROM vendor_payouts vp
                        WHERE substr(vp.paid_at, 1, 10) BETWEEN ? AND ?
                        ORDER BY vp.paid_at DESC
                        """;
            }
        }

        List<VendorPayoutEntry> result = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                if (cashierId != null) {
                    stmt.setString(2, cashierId);
                }
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                if (cashierId != null) {
                    stmt.setString(3, cashierId);
                }
            }
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                result.add(new VendorPayoutEntry(
                        rs.getString("vendor_name"),
                        rs.getBigDecimal("amount_paid"),
                        rs.getString("payment_method"),
                        rs.getString("cheque_number"),
                        rs.getString("payment_reference"),
                        rs.getString("paid_at"),
                        rs.getString("paid_by")));
            }
        }
        return result;
    }

    /**
     * Calculate summary totals including gross/net sales and cash short/over
     * 
     * Terminology (consistent across all POS reports):
     * - Gross Sales = Net Sales + discounts + fees/surcharge + EBT fees
     *   (equivalently subtotal + discounts + tax; EBT fees count in gross, not in net)
     * - Net Sales = customer subtotal less fees/surcharge and EBT fees
     * - Total Tax = Tax collected
     * - Total Revenue = Net Sales + Tax + GPI (what customer pays)
     */
    private void calculateSummaryTotals(EndOfDayReport report, LocalDate startDate, LocalDate endDate, String shiftId,
            String cashierId)
            throws SQLException {
        // discount = discount applied
        String salesSql;
        if (shiftId != null) {
            salesSql = """
                    SELECT
                        COALESCE(SUM(subtotal), 0) as subtotal,
                        COALESCE(SUM(total), 0) as total_revenue,
                        COALESCE(SUM(tax), 0) as total_tax,
                        COALESCE(SUM(discount), 0) as total_discount,
                        COALESCE(SUM(gpi), 0) as total_gpi,
                        COALESCE(SUM(ebt_fee), 0) as total_ebt_fee
                    FROM sales
                    WHERE (shift_id = ? OR id = ?)
                      AND (voided = FALSE OR voided IS NULL)
                      AND (pos_user_id = ? OR ? IS NULL)
                    """;
        } else {
            salesSql = """
                    SELECT
                        COALESCE(SUM(subtotal), 0) as subtotal,
                        COALESCE(SUM(total), 0) as total_revenue,
                        COALESCE(SUM(tax), 0) as total_tax,
                        COALESCE(SUM(discount), 0) as total_discount,
                        COALESCE(SUM(gpi), 0) as total_gpi,
                        COALESCE(SUM(ebt_fee), 0) as total_ebt_fee
                    FROM sales
                    WHERE substr(created_at, 1, 10) BETWEEN ? AND ?
                      AND (voided = FALSE OR voided IS NULL)
                      AND (pos_user_id = ? OR ? IS NULL)
                    """;
        }

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(salesSql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, shiftId);
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                BigDecimal subtotal = rs.getBigDecimal("subtotal");
                BigDecimal totalRevenue = rs.getBigDecimal("total_revenue");
                BigDecimal totalTax = rs.getBigDecimal("total_tax");
                BigDecimal discount = rs.getBigDecimal("total_discount");
                BigDecimal gpi = rs.getBigDecimal("total_gpi");
                BigDecimal ebtFee = rs.getBigDecimal("total_ebt_fee");

                subtotal = subtotal != null ? subtotal : BigDecimal.ZERO;
                totalRevenue = totalRevenue != null ? totalRevenue : BigDecimal.ZERO;
                totalTax = totalTax != null ? totalTax : BigDecimal.ZERO;
                discount = discount != null ? discount : BigDecimal.ZERO;
                gpi = gpi != null ? gpi : BigDecimal.ZERO;
                ebtFee = ebtFee != null ? ebtFee : BigDecimal.ZERO;

                report.totalGpi = gpi;
                report.totalEbtFees = ebtFee;
                report.totalPaymentsReceived = totalRevenue;
                report.totalTax = report.totalPaymentsReceived
                        .subtract(report.totalRevenue)
                        .subtract(report.totalGpi);
                if (report.totalTax.compareTo(BigDecimal.ZERO) < 0) {
                    report.totalTax = totalTax;
                }
                // sales.subtotal is post-discount and includes surcharge/GPI.
                // Net sales deduct surcharge/GPI and EBT fees.
                // Gross sales adds back discounts, surcharge/GPI, tax, and EBT fees.
                report.netSales = subtotal.subtract(gpi).subtract(ebtFee);
                report.grossSales = report.netSales.add(discount).add(gpi).add(report.totalTax).add(ebtFee);
                report.finalDeposit = report.totalPaymentsReceived;
            }
        }

        // Calculate total payments received from payment methods
        // We'll trust the totalRevenue from the sales table as the source of truth
        // to match the Dashboard's "Total Sales" card.

        // Calculate total income/expense
        report.totalIncomeExpense = BigDecimal.ZERO;
        for (IncomeExpense ie : report.incomeExpenses) {
            // Cash Add is positive (income), Cash Drop is negative (expense)
            if ("CASH IN (ADD)".equals(ie.accountName)) {
                report.totalIncomeExpense = report.totalIncomeExpense.add(ie.amount);
            } else if ("CASH OUT (DROP)".equals(ie.accountName)) {
                report.totalIncomeExpense = report.totalIncomeExpense.add(ie.amount); // amount is already negative for
                                                                                      // drops
            }
        }

        // Calculate expense totals
        report.totalExpenses = BigDecimal.ZERO;
        for (ExpenseEntry exp : report.expenses) {
            report.totalExpenses = report.totalExpenses.add(exp.amount);
        }

        // Calculate vendor payout totals
        report.totalVendorPayoutsCash = BigDecimal.ZERO;
        report.totalVendorPayoutsCheque = BigDecimal.ZERO;
        report.totalVendorPayoutsInvoice = BigDecimal.ZERO;
        report.totalVendorPayoutsCredit = BigDecimal.ZERO;
        report.totalVendorPayouts = BigDecimal.ZERO;

        for (VendorPayoutEntry vp : report.vendorPayouts) {
            report.totalVendorPayouts = report.totalVendorPayouts.add(vp.amount);
            if (VendorPayoutService.isDrawerCashPayoutMethod(vp.paymentMethod)) {
                report.totalVendorPayoutsCash = report.totalVendorPayoutsCash.add(vp.amount);
            } else if ("CHEQUE".equalsIgnoreCase(vp.paymentMethod)) {
                report.totalVendorPayoutsCheque = report.totalVendorPayoutsCheque.add(vp.amount);
            } else if ("INVOICE".equalsIgnoreCase(vp.paymentMethod)) {
                report.totalVendorPayoutsInvoice = report.totalVendorPayoutsInvoice.add(vp.amount);
            } else if ("CREDIT".equalsIgnoreCase(vp.paymentMethod)) {
                report.totalVendorPayoutsCredit = report.totalVendorPayoutsCredit.add(vp.amount);
            }
        }

        // Add vendor payouts to total expenses
        report.totalExpenses = report.totalExpenses.add(report.totalVendorPayouts);

        // Calculate cash expected (cash sales + cash adds - cash drops - cash vendor
        // payouts - cash expenses)
        BigDecimal cashSales = sumCashPaymentTotals(report.paymentMethods);

        // Get cash drawer starting amount and actual count from shift data
        BigDecimal startingCash = getStartingCashAmount(startDate, endDate, shiftId, cashierId);
        report.startingCash = startingCash;
        BigDecimal cashDrops = BigDecimal.ZERO;
        BigDecimal cashAdds = BigDecimal.ZERO;
        BigDecimal cashExpenses = BigDecimal.ZERO;

        for (IncomeExpense ie : report.incomeExpenses) {
            if ("CASH OUT (DROP)".equals(ie.accountName)) {
                cashDrops = cashDrops.add(ie.amount.abs()); // Use absolute value as amount is negative
            } else if ("CASH IN (ADD)".equals(ie.accountName)) {
                cashAdds = cashAdds.add(ie.amount);
            }
        }

        // Calculate cash expenses
        for (ExpenseEntry exp : report.expenses) {
            if ("CASH".equalsIgnoreCase(exp.paymentMethod)) {
                cashExpenses = cashExpenses.add(exp.amount);
            }
        }

        BigDecimal cashRefunds = getCashRefundsTotal(startDate, endDate, shiftId, cashierId);
        report.cashRefundsTotal = cashRefunds;

        // Cash expected is net activity only. Starting cash stays separate in the
        // drawer and must not be folded into expected cash.
        report.cashExpected = cashSales.add(cashAdds).subtract(cashDrops)
                .subtract(report.totalVendorPayoutsCash).subtract(cashExpenses).subtract(cashRefunds);

        // Get actual cash count from closed shifts
        report.cashActual = getActualCashCount(startDate, endDate, shiftId, cashierId);

        // Calculate variance as (Actual - Start) - ExpectedFlow
        // This isolates the performance while allowing the carry-over float to exist in
        // the drawer.
        report.cashShortOver = report.cashActual.subtract(report.startingCash).subtract(report.cashExpected);
    }

    private BigDecimal getStartingCashAmount(LocalDate startDate, LocalDate endDate, String shiftId, String cashierId)
            throws SQLException {
        String sql;
        if (shiftId != null) {
            sql = "SELECT opening_cash FROM shifts WHERE id = ? AND (cashier_id = ? OR ? IS NULL)";
        } else {
            // Bucket the shift by its local created_at, the same basis the rest of this
            // report uses for sales (sales.created_at). shift_started_at is a UTC string,
            // so bucketing on it drops shifts whose UTC calendar date differs from the
            // local report date (e.g. evening shifts in US time zones rolling to the next
            // UTC day), making a closed shift disappear from the day's reconciliation.
            sql = "SELECT opening_cash FROM shifts WHERE substr(created_at, 1, 10) BETWEEN ? AND ? AND (cashier_id = ? OR ? IS NULL) ORDER BY shift_started_at ASC LIMIT 1";
        }

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, cashierId);
                stmt.setString(3, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                BigDecimal openingCash = rs.getBigDecimal("opening_cash");
                return openingCash != null ? openingCash : BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal getActualCashCount(LocalDate startDate, LocalDate endDate, String shiftId, String cashierId)
            throws SQLException {
        String sql;
        if (shiftId != null) {
            sql = "SELECT actual_cash FROM shifts WHERE id = ? AND status = 'CLOSED' AND (cashier_id = ? OR ? IS NULL)";
        } else {
            // Bucket by local created_at to match the sales side of the report (see
            // getStartingCashAmount). Returns the last closed shift's count, which under
            // the shared-drawer model is the final drawer total for the day.
            sql = "SELECT actual_cash FROM shifts WHERE substr(created_at, 1, 10) BETWEEN ? AND ? AND status = 'CLOSED' AND (cashier_id = ? OR ? IS NULL) ORDER BY shift_started_at DESC LIMIT 1";
        }

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
                stmt.setString(2, cashierId);
                stmt.setString(3, cashierId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                BigDecimal actualCash = rs.getBigDecimal("actual_cash");
                return actualCash != null ? actualCash : BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal getCashRefundsTotal(LocalDate startDate, LocalDate endDate, String shiftId, String cashierId)
            throws SQLException {
        String sql;
        if (shiftId != null) {
            sql = """
                    SELECT COALESCE(SUM(r.total_refund), 0) AS total_cash_refunds
                    FROM refunds r
                    INNER JOIN shifts sh ON sh.id = ?
                    WHERE UPPER(COALESCE(r.refund_method, '')) = 'CASH'
                      AND (r.pos_user_id = sh.cashier_id OR sh.cashier_id IS NULL)
                      AND r.timestamp >= sh.shift_started_at
                      AND (sh.shift_ended_at IS NULL OR r.timestamp <= sh.shift_ended_at)
                    """;
        } else {
            sql = """
                    SELECT COALESCE(SUM(r.total_refund), 0) AS total_cash_refunds
                    FROM refunds r
                    WHERE substr(r.timestamp, 1, 10) BETWEEN ? AND ?
                      AND UPPER(COALESCE(r.refund_method, '')) = 'CASH'
                      AND (r.pos_user_id = ? OR ? IS NULL)
                    """;
        }

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (shiftId != null) {
                stmt.setString(1, shiftId);
            } else {
                stmt.setDate(1, java.sql.Date.valueOf(startDate));
                stmt.setDate(2, java.sql.Date.valueOf(endDate));
                stmt.setString(3, cashierId);
                stmt.setString(4, cashierId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                BigDecimal total = rs.getBigDecimal("total_cash_refunds");
                return total != null ? total : BigDecimal.ZERO;
            }
        }

        return BigDecimal.ZERO;
    }

    /**
     * Calculate report totals
     */
    private void calculateTotals(EndOfDayReport report) {
        // Commodity group totals
        for (CommodityGroupRevenue group : report.commodityGroups) {
            report.totalQuantity += group.quantity;
            report.totalRevenue = report.totalRevenue.add(group.revenue);
            report.totalDiscount = report.totalDiscount.add(group.discount);
        }

        // Tax totals
        for (SalesTaxBreakdown tax : report.salesTaxes) {
            report.totalTax = report.totalTax.add(tax.taxAmount);
            report.totalGross = report.totalGross.add(tax.grossAmount);
            report.totalNet = report.totalNet.add(tax.netAmount);
        }
    }

    /**
     * Align Sales Tax rows with the exact summary totals after all calculations are
     * complete.
     */
    private void alignSalesTaxWithSummaryTotals(EndOfDayReport report) {
        if (report.salesTaxes.isEmpty()) {
            return;
        }

        SalesTaxBreakdown largest = report.salesTaxes.get(0);
        for (SalesTaxBreakdown tax : report.salesTaxes) {
            if (tax.grossAmount.compareTo(largest.grossAmount) > 0) {
                largest = tax;
            }
        }

        BigDecimal targetNet = report.totalRevenue;
        BigDecimal targetGross = report.grossSales;
        BigDecimal targetTax = report.totalTax;

        BigDecimal currentNet = report.salesTaxes.stream()
                .map(tax -> tax.netAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal currentGross = report.salesTaxes.stream()
                .map(tax -> tax.grossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal currentTax = report.salesTaxes.stream()
                .map(tax -> tax.taxAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal netDifference = targetNet.subtract(currentNet);
        BigDecimal grossDifference = targetGross.subtract(currentGross);
        BigDecimal taxDifference = targetTax.subtract(currentTax);

        if (netDifference.compareTo(BigDecimal.ZERO) != 0) {
            largest.netAmount = largest.netAmount.add(netDifference);
        }
        if (grossDifference.compareTo(BigDecimal.ZERO) != 0) {
            largest.grossAmount = largest.grossAmount.add(grossDifference);
        }
        if (taxDifference.compareTo(BigDecimal.ZERO) != 0) {
            largest.taxAmount = largest.taxAmount.add(taxDifference);
        }

        report.totalNet = targetNet;
        report.totalGross = targetGross;
        report.totalTax = targetTax;

        logger.debug("Aligned Sales Tax totals with summary values for '{}': net {}, gross {}, tax {}",
                largest.taxDescription, netDifference, grossDifference, taxDifference);
    }

    // ==================== Receipt Text Generation ====================

    /**
     * Generate formatted receipt text for printing
     */
    public String generateReceiptText(EndOfDayReport report) {
        StringBuilder sb = new StringBuilder();

        // Get store info
        String storeName = settingsService.getStoreName();
        String storeAddress = settingsService.getStoreAddress();
        String storePhone = settingsService.getStorePhone();
        String storeTagline = settingsService.getReceiptHeader();

        // Store Info
        sb.append(centerText(storeName)).append("\n");
        if (!storeAddress.isEmpty())
            sb.append(centerText(storeAddress)).append("\n");
        if (!storePhone.isEmpty())
            sb.append(centerText(storePhone)).append("\n");
        sb.append("\n");

        if (storeTagline != null && !storeTagline.isEmpty()) {
            sb.append(centerText(storeTagline)).append("\n");
        }
        sb.append(centerText("*** END OF DAY REPORT ***")).append("\n");
        sb.append("-".repeat(RECEIPT_WIDTH)).append("\n");

        // POS Info
        sb.append("POS: ").append(report.posId).append("\n");
        sb.append("Cashier: ").append(report.cashier).append("\n");
        sb.append("Cash Drawer: ").append(report.cashDrawer).append("\n");
        sb.append("Z-Count from: ").append(report.zCountFrom).append("\n");
        sb.append("Z-Count to: ").append(report.zCountTo).append("\n");

        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy, h:mm:ss a");
        DateTimeFormatter reportDateFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy");

        // Add Report Date range
        String reportDayLabel = "Report Day: ";
        if (report.startDate != null) {
            sb.append(reportDayLabel).append(report.startDate.format(reportDateFormatter));
            if (report.endDate != null && !report.startDate.equals(report.endDate)) {
                sb.append(" to ").append(report.endDate.format(reportDateFormatter));
            }
            sb.append("\n");
        }

        sb.append("Creation date: ").append(report.creationDate.format(dateFormatter)).append("\n");
        sb.append("Created by: ").append(report.createdBy).append("\n");
        sb.append("-".repeat(RECEIPT_WIDTH)).append("\n\n");

        // Commodity Group Revenue
        sb.append("Commodity Group Revenue ($)\n\n");
        sb.append(String.format("%-18s %6s %10s %10s\n", "Commodity group", "Qty.", "Revenue", "Discount"));
        sb.append("-".repeat(RECEIPT_WIDTH)).append("\n");

        for (CommodityGroupRevenue group : report.commodityGroups) {
            sb.append(formatCommodityRow(group)).append("\n");
        }
        sb.append("\n");

        // Commodity totals
        int totalQty = report.commodityGroups.stream().mapToInt(g -> g.quantity).sum();
        BigDecimal totalRev = report.commodityGroups.stream().map(g -> g.revenue).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        BigDecimal totalDisc = report.commodityGroups.stream().map(g -> g.discount).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        sb.append("-".repeat(RECEIPT_WIDTH)).append("\n");
        sb.append(formatTotalRow("Total:", totalQty, totalRev, totalDisc)).append("\n\n");

        // Customer Group Revenue
        sb.append("Customer Group Revenue ($)\n\n");
        sb.append(String.format("%-18s %6s %10s %10s\n", "Customer group", "Qty.", "Revenue", "Discount"));
        sb.append("-".repeat(RECEIPT_WIDTH)).append("\n");

        for (CustomerGroupRevenue group : report.customerGroups) {
            sb.append(formatCustomerRow(group)).append("\n");
        }
        sb.append("\n");

        // Customer totals
        totalQty = report.customerGroups.stream().mapToInt(g -> g.quantity).sum();
        totalRev = report.customerGroups.stream().map(g -> g.revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        totalDisc = report.customerGroups.stream().map(g -> g.discount).reduce(BigDecimal.ZERO, BigDecimal::add);
        sb.append("-".repeat(RECEIPT_WIDTH)).append("\n");
        sb.append(formatTotalRow("Total:", totalQty, totalRev, totalDisc)).append("\n\n");

        // Income/Expenses
        sb.append("Income/Expenses ($)\n\n");
        sb.append(String.format("%-18s %6s %16s\n", "Account", "Qty.", "Amount"));
        sb.append("-".repeat(RECEIPT_WIDTH)).append("\n");

        BigDecimal totalExpenseAmount = BigDecimal.ZERO;
        int totalExpenseQty = 0;
        for (IncomeExpense expense : report.incomeExpenses) {
            String accountLabel = expense.accountNumber + "      " + truncate(expense.accountName, 10);
            sb.append(String.format("%-18s %6d %16.2f", accountLabel, expense.quantity, expense.amount)).append("\n");
            totalExpenseQty += expense.quantity;
            totalExpenseAmount = totalExpenseAmount.add(expense.amount);
        }

        if (report.incomeExpenses.isEmpty()) {
            sb.append("No income/expenses recorded").append("\n");
        }
        sb.append("\n");

        // Income totals
        if (!report.incomeExpenses.isEmpty()) {
            sb.append("--------------------------------\n");
            sb.append(String.format("%-18s %6d %16.2f", "Total:", totalExpenseQty, totalExpenseAmount)).append("\n\n");
        }

        // Sales Tax
        sb.append("Sales Tax \n\n");
        sb.append(String.format("%-8s %-12s %8s %9s %9s", "Name", "", "Tax", "Gross", "Net")).append("\n");
        sb.append("--------------------------------\n");

        BigDecimal totalTaxAmount = BigDecimal.ZERO;
        for (SalesTaxBreakdown tax : report.salesTaxes) {
            // Handle multi-line tax description
            String[] descLines = tax.taxDescription.split("\n");
            String firstDesc = descLines.length > 0 ? descLines[0] : "";
            StringBuilder result = new StringBuilder();

            // First line with all values
            result.append(String.format("%-8s %-12s %8.2f %9.2f %9.2f",
                    tax.taxName,
                    truncate(firstDesc, 12),
                    tax.taxAmount,
                    tax.grossAmount,
                    tax.netAmount));

            // Additional description lines
            for (int i = 1; i < descLines.length; i++) {
                result.append("\n").append(String.format("%-8s %-12s", "", truncate(descLines[i], 12)));
            }
            sb.append(result.toString()).append("\n");
            totalTaxAmount = totalTaxAmount.add(tax.taxAmount);
        }
        sb.append("\n");

        // Tax totals
        sb.append("--------------------------------\n");
        sb.append(String.format("%-21s %8.2f %9s %9s", "total:", totalTaxAmount,
                String.format("%,.2f", report.totalGross),
                String.format("%,.2f", report.totalNet))).append("\n");
        sb.append("\n");

        // Cashier Actions
        if (!report.cashierActions.isEmpty()) {
            sb.append("Cashier Actions (US-Dollar)\n\n");
            sb.append(String.format("%-24s %-6s %16s\n", "Action", "Qty.", "amount"));

            for (CashierAction action : report.cashierActions) {
                sb.append(String.format("%-24s %-6d %16.2f\n",
                        action.actionName,
                        action.quantity,
                        action.amount));
            }
            sb.append("\n");
        }

        // Payment Method Sales — use gross collected (totalAmount) so Cash line matches
        // "(+) Cash Sales" in reconciliation (net would exclude tax/fees on that tender).
        sb.append("Payment Method Sales ($)\n\n");
        sb.append(String.format("%-18s %6s %16s\n", "Payment Method", "Qty.", "Collected"));
        sb.append("-".repeat(RECEIPT_WIDTH)).append("\n");

        BigDecimal totalPaymentAmount = BigDecimal.ZERO;
        int totalPaymentCount = 0;
        for (PaymentMethodSales pms : report.paymentMethods) {
            sb.append(formatPaymentMethodRow(pms)).append("\n");
            totalPaymentCount += pms.transactionCount;
            totalPaymentAmount = totalPaymentAmount.add(
                    pms.totalAmount != null ? pms.totalAmount : BigDecimal.ZERO);
        }

        if (report.paymentMethods.isEmpty()) {
            sb.append("No payments recorded").append("\n");
        }
        sb.append("\n");

        // Payment totals
        sb.append("-".repeat(RECEIPT_WIDTH)).append("\n");
        sb.append(formatPaymentTotalRow("Total:", totalPaymentCount, totalPaymentAmount)).append("\n\n");

        // Vendor Payouts Section
        if (!report.vendorPayouts.isEmpty()) {
            sb.append("=".repeat(RECEIPT_WIDTH)).append("\n");
            sb.append(centerText("*** VENDOR PAYOUTS ***")).append("\n");
            sb.append("=".repeat(RECEIPT_WIDTH)).append("\n\n");

            sb.append(String.format("%-18s %-10s %8s %8s\n", "Vendor", "Method", "By", "Amount"));
            sb.append("-".repeat(RECEIPT_WIDTH)).append("\n");

            for (VendorPayoutEntry vp : report.vendorPayouts) {
                String methodDisplay = formatVendorPayoutMethodDisplay(vp);
                String paidByDisplay = vp.paidBy != null ? truncate(vp.paidBy, 8) : "-";
                sb.append(String.format("%-18s %-10s %8s %8.2f\n",
                        truncate(vp.vendorName, 18),
                        truncate(methodDisplay, 10),
                        paidByDisplay,
                        vp.amount));
            }
            sb.append("\n");

            // Vendor payout totals by method
            sb.append("-".repeat(RECEIPT_WIDTH)).append("\n");
            if (report.totalVendorPayoutsCash.compareTo(BigDecimal.ZERO) > 0) {
                sb.append(
                        String.format("%-28s %14.2f\n", "Cash Payouts (from drawer):", report.totalVendorPayoutsCash));
            }
            if (report.totalVendorPayoutsCheque.compareTo(BigDecimal.ZERO) > 0) {
                sb.append(String.format("%-28s %14.2f\n", "Cheque Payouts:", report.totalVendorPayoutsCheque));
            }
            if (report.totalVendorPayoutsInvoice.compareTo(BigDecimal.ZERO) > 0) {
                sb.append(String.format("%-28s %14.2f\n", "Invoice Payouts:", report.totalVendorPayoutsInvoice));
            }
            if (report.totalVendorPayoutsCredit.compareTo(BigDecimal.ZERO) > 0) {
                sb.append(String.format("%-28s %14.2f\n", "Credit Payouts:", report.totalVendorPayoutsCredit));
            }
            sb.append("-".repeat(RECEIPT_WIDTH)).append("\n");
            sb.append(String.format("%-28s %14.2f\n", "TOTAL VENDOR PAYOUTS:", report.totalVendorPayouts));
            sb.append("\n");
        }

        // Expenses Section
        if (!report.expenses.isEmpty()) {
            sb.append("=".repeat(RECEIPT_WIDTH)).append("\n");
            sb.append(centerText("*** EXPENSES ***")).append("\n");
            sb.append("=".repeat(RECEIPT_WIDTH)).append("\n\n");

            sb.append(String.format("%-18s %-10s %8s %8s\n", "Category", "Method", "Desc", "Amount"));
            sb.append("-".repeat(RECEIPT_WIDTH)).append("\n");

            for (ExpenseEntry exp : report.expenses) {
                String methodDisplay = exp.paymentMethod != null ? truncate(exp.paymentMethod, 10) : "-";
                String descDisplay = exp.description != null ? truncate(exp.description, 8) : "-";
                sb.append(String.format("%-18s %-10s %8s %8.2f\n",
                        truncate(exp.categoryName, 18),
                        methodDisplay,
                        descDisplay,
                        exp.amount));
            }
            sb.append("\n");

            // Expense totals
            sb.append("-".repeat(RECEIPT_WIDTH)).append("\n");
            sb.append(String.format("%-28s %14.2f\n", "TOTAL EXPENSES:", report.totalExpenses));
            sb.append("\n");
        }

        // Summary Section
        sb.append("=".repeat(RECEIPT_WIDTH)).append("\n");
        sb.append(centerText("*** SALES SUMMARY ***")).append("\n");
        sb.append("=".repeat(RECEIPT_WIDTH)).append("\n\n");

        sb.append(String.format("%-24s %18.2f\n", "Gross Sales:", report.grossSales));
        sb.append(String.format("%-24s %18.2f\n", "Net Sales:", report.netSales));
        if (report.totalDiscount.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("%-24s %18.2f\n", "Discounts:", report.totalDiscount));
        }
        sb.append(String.format("%-24s %18.2f\n", "Plus: Sales Tax:", report.totalTax));
        if (report.totalGpi.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("%-24s %18.2f\n", "Plus: Fees/Surcharge:", report.totalGpi));
        }
        sb.append("-".repeat(RECEIPT_WIDTH)).append("\n");
        sb.append(String.format("%-24s %18.2f\n", "Final Deposit:", report.finalDeposit));
        if (report.totalExpenses.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("%-24s %18.2f\n", "Expenses/Payouts:", report.totalExpenses));
        }
        if (report.totalEbtFees.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("%-24s %18.2f\n", "EBT Fees in Net Sales:", report.totalEbtFees));
        }
        sb.append("\n");

        // Cash Reconciliation
        sb.append(centerText("*** CASH RECONCILIATION ***")).append("\n");
        sb.append("-".repeat(RECEIPT_WIDTH)).append("\n\n");

        sb.append(String.format("%-28s %14.2f\n", "Starting Cash:", report.startingCash));

        // Show cash flow breakdown (sum all cash buckets — casing / legacy duplicates)
        BigDecimal cashSalesAmount = sumCashPaymentTotals(report.paymentMethods);
        sb.append(String.format("%-28s %14.2f\n", "(+) Cash Sales:", cashSalesAmount));

        // Show cash operations breakdown
        BigDecimal cashAddsAmount = BigDecimal.ZERO;
        BigDecimal cashDropsAmount = BigDecimal.ZERO;
        for (IncomeExpense ie : report.incomeExpenses) {
            if ("CASH IN (ADD)".equals(ie.accountName)) {
                cashAddsAmount = cashAddsAmount.add(ie.amount);
            } else if ("CASH OUT (DROP)".equals(ie.accountName)) {
                cashDropsAmount = cashDropsAmount.add(ie.amount.abs());
            }
        }
        if (cashAddsAmount.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("%-28s %14.2f\n", "(+) Cash Adds:", cashAddsAmount));
        }
        if (cashDropsAmount.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("%-28s %14.2f\n", "(-) Cash Drops:", cashDropsAmount));
        }

        // Show vendor cash payouts deduction prominently
        if (report.totalVendorPayoutsCash.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("%-28s %14.2f\n", "(-) Vendor Cash Payouts:", report.totalVendorPayoutsCash));
        }

        // Show cash expenses deduction
        BigDecimal cashExpenses = BigDecimal.ZERO;
        for (ExpenseEntry exp : report.expenses) {
            if ("CASH".equalsIgnoreCase(exp.paymentMethod)) {
                cashExpenses = cashExpenses.add(exp.amount);
            }
        }
        if (cashExpenses.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("%-28s %14.2f\n", "(-) Cash Expenses:", cashExpenses));
        }
        if (report.cashRefundsTotal != null && report.cashRefundsTotal.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("%-28s %14.2f\n", "(-) Cash Refunds:", report.cashRefundsTotal));
        }

        sb.append("-".repeat(RECEIPT_WIDTH)).append("\n");
        sb.append(String.format("%-28s %14.2f\n", "EXPECTED CASH:", report.cashExpected));

        sb.append(String.format("%-28s %14.2f\n", "ACTUAL TOTAL CASH:", report.cashActual));
        sb.append("=".repeat(RECEIPT_WIDTH)).append("\n");

        // Format cash short/over with clear indicator
        if (report.cashShortOver.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("%-28s %14.2f\n", "*** CASH OVER:", report.cashShortOver));
        } else if (report.cashShortOver.compareTo(BigDecimal.ZERO) < 0) {
            sb.append(String.format("%-28s %14.2f\n", "*** CASH SHORT:", report.cashShortOver.abs()));
        } else {
            sb.append(String.format("%-28s %14.2f\n", "CASH BALANCED:", BigDecimal.ZERO));
        }
        sb.append("\n");

        // Show breakdown of what caused the short/over if vendor payouts were made
        if (report.totalVendorPayoutsCash.compareTo(BigDecimal.ZERO) > 0) {
            sb.append("Note: Cash expected includes deduction\n");
            sb.append("for vendor cash payouts made today.\n");
            sb.append("\n");
        }

        sb.append("-".repeat(RECEIPT_WIDTH)).append("\n");
        sb.append(centerText("End of Report")).append("\n");
        sb.append("=".repeat(RECEIPT_WIDTH)).append("\n");

        return sb.toString();
    }

    // ==================== Formatting Helpers ====================

    private String centerText(String text) {
        if (text == null || text.isEmpty())
            return "";
        int padding = (RECEIPT_WIDTH - text.length()) / 2;
        if (padding <= 0)
            return text;
        return " ".repeat(padding) + text;
    }

    private String formatCommodityRow(CommodityGroupRevenue group) {
        // Format: "1 Drinks 13 6.11 0.00"
        String groupLabel = group.groupNumber + "      " + truncate(group.groupName, 10);
        return String.format("%-18s %6d %10.2f %10.2f",
                groupLabel, group.quantity, group.revenue, group.discount);
    }

    private String formatCustomerRow(CustomerGroupRevenue group) {
        String groupLabel = group.groupNumber + "      " + truncate(group.groupName, 10);
        return String.format("%-18s %6d %10.2f %10.2f",
                groupLabel, group.quantity, group.revenue, group.discount);
    }

    private String formatTotalRow(String label, int qty, BigDecimal revenue, BigDecimal discount) {
        return String.format("%-18s %6d %10.2f %10.2f", label, qty, revenue, discount);
    }

    private String formatPaymentMethodRow(PaymentMethodSales pms) {
        String methodName = formatPaymentMethodName(pms.paymentMethod);
        BigDecimal collected = pms.totalAmount != null ? pms.totalAmount : BigDecimal.ZERO;
        return String.format("%-18s %6d %16.2f", methodName, pms.transactionCount, collected);
    }

    private String formatPaymentTotalRow(String label, int count, BigDecimal amount) {
        return String.format("%-18s %6d %16.2f", label, count, amount);
    }

    private String formatVendorPayoutMethodDisplay(VendorPayoutEntry payout) {
        if (payout == null || payout.paymentMethod == null) {
            return "-";
        }

        String reference = payout.paymentReference;
        return switch (payout.paymentMethod.toUpperCase()) {
            case "CASH", "CASHIER" -> "CASH";
            case "CHEQUE" -> payout.chequeNumber != null && !payout.chequeNumber.isBlank()
                    ? "Chq#" + truncate(payout.chequeNumber, 6)
                    : reference != null && !reference.isBlank() ? "Inv#" + truncate(reference, 6) : "CHEQUE";
            case "INVOICE" -> reference != null && !reference.isBlank() ? "Inv#" + truncate(reference, 6) : "INVOICE";
            case "CREDIT" -> reference != null && !reference.isBlank() ? "Cr#" + truncate(reference, 7) : "CREDIT";
            default -> payout.paymentMethod;
        };
    }

    private String formatPaymentMethodName(String method) {
        if (method == null)
            return "Unknown";
        return switch (method.toUpperCase()) {
            case "CASH" -> "Cash";
            case "CARD" -> "Credit/Debit Card";
            case "DIGITAL" -> "Digital Wallet";
            case "EBT" -> "EBT";
            case "CHECK" -> "Check";
            case "SPLIT" -> "Split Payment";
            default -> method;
        };
    }

    private String truncate(String text, int maxLength) {
        if (text == null)
            return "";
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    /**
     * Generate End of Day report for today and return formatted receipt text
     */
    public String generateTodayReceiptText(String createdBy) throws SQLException {
        EndOfDayReport report = generateReport(LocalDate.now(), createdBy);
        return generateReceiptText(report);
    }

    /**
     * Generate End of Day report for a specific date and return formatted receipt
     * text
     */
    public String generateReceiptTextForDate(LocalDate date, String createdBy) throws SQLException {
        return generateReceiptTextForDateRange(date, date, createdBy);
    }

    /**
     * Generate End of Day report for a date range and return formatted receipt
     * text
     */
    public String generateReceiptTextForDateRange(LocalDate startDate, LocalDate endDate, String createdBy)
            throws SQLException {
        EndOfDayReport report = generateReport(startDate, endDate, createdBy);
        return generateReceiptText(report);
    }

    /**
     * Generate End of Day report for a specific shift and return formatted receipt
     * text
     */
    public String generateReceiptTextForShift(String shiftId, String createdBy) throws SQLException {
        EndOfDayReport report = generateShiftReport(shiftId, createdBy);
        return generateReceiptText(report);
    }

    /**
     * Generate financial report for a specific shift
     */
    public EndOfDayReport generateShiftReport(String shiftId, String createdBy) throws SQLException {
        logger.info("Generating shift report for shift ID: {}", shiftId);

        EndOfDayReport report = new EndOfDayReport();
        report.shiftId = shiftId;

        // Set metadata
        report.posId = config.getProperty("device.name", "POS 01");
        report.creationDate = LocalDateTime.now();
        report.createdBy = createdBy != null ? createdBy : "System";

        // Get shift details to set cashier name, shift number, and shift dates
        getShiftMetadata(report, shiftId);

        // Aggregate data filtered by shift_id
        report.zCountFrom = 0; // Not applicable for single shift usually, but could be calculated
        report.zCountTo = 0;

        report.commodityGroups = getCommodityGroupRevenue((LocalDate) null, (LocalDate) null, (String) shiftId,
                (String) null);
        report.customerGroups = getCustomerGroupRevenue((LocalDate) null, (LocalDate) null, (String) shiftId,
                (String) null);
        report.incomeExpenses = getIncomeExpenses((LocalDate) null, (LocalDate) null, (String) shiftId, (String) null);
        report.salesTaxes = getSalesTaxBreakdown((LocalDate) null, (LocalDate) null, (String) shiftId, (String) null);
        report.paymentMethods = getPaymentMethodSales((LocalDate) null, (LocalDate) null, (String) shiftId,
                (String) null);
        report.vendorPayouts = getVendorPayouts((LocalDate) null, (LocalDate) null, (String) shiftId, (String) null);
        report.expenses = getExpenses((LocalDate) null, (LocalDate) null, (String) shiftId, (String) null);
        report.cashierActions = getCashierActions((LocalDate) null, (LocalDate) null, (String) shiftId, (String) null);

        // Calculate totals
        calculateTotals(report);
        calculateSummaryTotalsForShift(report, shiftId, null);
        alignSalesTaxWithSummaryTotals(report);

        logger.info("Shift report generated successfully");
        return report;
    }

    private void getShiftMetadata(EndOfDayReport report, String shiftId) throws SQLException {
        String sql = "SELECT cashier_name, shift_number, shift_started_at, created_at FROM shifts WHERE id = ? OR shift_id = ?";
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, shiftId);
            stmt.setString(2, shiftId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                report.cashier = rs.getString("cashier_name");
                report.shiftNumber = rs.getString("shift_number");

                // Get shift date from shift_started_at or created_at
                String startedAt = rs.getString("shift_started_at");
                if (startedAt != null && startedAt.length() >= 10) {
                    try {
                        report.startDate = LocalDate.parse(startedAt.substring(0, 10));
                        report.endDate = report.startDate;
                    } catch (Exception e) {
                        logger.warn("Could not parse shift_started_at: {}", startedAt);
                    }
                }

                if (report.startDate == null) {
                    java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
                    if (createdAt != null) {
                        report.startDate = createdAt.toLocalDateTime().toLocalDate();
                        report.endDate = report.startDate;
                    }
                }
            }
        }
    }

    private void calculateSummaryTotalsForShift(EndOfDayReport report, String shiftId, String cashierId)
            throws SQLException {
        calculateSummaryTotals(report, null, null, shiftId, cashierId);
    }
}

