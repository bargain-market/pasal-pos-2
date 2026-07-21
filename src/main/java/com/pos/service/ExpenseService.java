package com.pos.service;

import com.pos.api.ApiClient;
import com.pos.database.DatabaseManager;
import com.pos.model.Expense;
import com.pos.model.ExpenseCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for managing expenses.
 * Handles CRUD operations for expense records with permission checks.
 */
public class ExpenseService {

    private static final Logger logger = LoggerFactory.getLogger(ExpenseService.class);
    private static ExpenseService instance;
    private final DatabaseManager dbManager;
    private final ExpenseCategoryService categoryService;
    private final UserAuthService userAuthService;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private ExpenseService() {
        this.dbManager = DatabaseManager.getInstance();
        this.categoryService = ExpenseCategoryService.getInstance();
        this.userAuthService = UserAuthService.getInstance();
    }

    public static synchronized ExpenseService getInstance() {
        if (instance == null) {
            instance = new ExpenseService();
        }
        return instance;
    }

    /**
     * Create a new expense (offline-first: stores locally first, syncs to backend afterwards)
     */
    public Expense createExpense(Expense expense) throws SQLException {
        // Permission check - Manager+ required
        RoleBasedAccessService rbacService = RoleBasedAccessService.getInstance();
        rbacService.requirePermission(RoleBasedAccessService.PERMISSION_EXPENSES);
        
        // Validate required fields
        if (expense.getCategoryId() == null || expense.getCategoryId().isEmpty()) {
            throw new IllegalArgumentException("Expense category is required");
        }
        
        if (expense.getAmount() == null || expense.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Expense amount must be greater than zero");
        }
        
        // Get category name
        ExpenseCategory category = categoryService.getCategoryById(expense.getCategoryId());
        if (category == null) {
            throw new IllegalArgumentException("Expense category not found");
        }
        expense.setCategoryName(category.getName());
        
        // Set IDs and timestamps
        if (expense.getId() == null || expense.getId().isEmpty()) {
            expense.setId(UUID.randomUUID().toString());
        }
        
        if (expense.getExpenseId() == null || expense.getExpenseId().isEmpty()) {
            expense.setExpenseId("EXP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }
        
        String now = LocalDateTime.now().format(DATE_FORMATTER);
        if (expense.getTimestamp() == null || expense.getTimestamp().isEmpty()) {
            expense.setTimestamp(now);
        }
        expense.setCreatedAt(now);
        expense.setSynced(false);
        
        // Set created by info
        if (expense.getCreatedBy() == null || expense.getCreatedBy().isEmpty()) {
            expense.setCreatedBy(userAuthService.getCurrentPosUserId());
        }
        if (expense.getCreatedByName() == null || expense.getCreatedByName().isEmpty()) {
            expense.setCreatedByName(userAuthService.getCurrentUserName());
        }
        
        // Store locally first (offline-first)
        storeExpenseLocally(expense);
        
        logger.info("Expense created: {} - ${} ({})", expense.getCategoryName(), expense.getAmount(), expense.getExpenseId());
        
        // Trigger outbound sync
        com.pos.sync.SyncManager.getInstance().triggerOutboundSync();
        
        // Try to sync to backend in background (non-blocking)
        syncExpenseToBackendAsync(expense);
        
        return expense;
    }

    /**
     * Store expense locally in database
     */
    private void storeExpenseLocally(Expense expense) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = """
                INSERT INTO expenses (id, expense_id, category_id, category_name, amount, description,
                                    payment_method, shift_id, receipt_number, vendor_name, created_by,
                                    created_by_name, timestamp, synced, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, expense.getId());
                stmt.setString(2, expense.getExpenseId());
                stmt.setString(3, expense.getCategoryId());
                stmt.setString(4, expense.getCategoryName());
                stmt.setBigDecimal(5, expense.getAmount());
                stmt.setString(6, expense.getDescription());
                stmt.setString(7, expense.getPaymentMethod());
                stmt.setString(8, expense.getShiftId());
                stmt.setString(9, expense.getReceiptNumber());
                stmt.setString(10, expense.getVendorName());
                stmt.setString(11, expense.getCreatedBy());
                stmt.setString(12, expense.getCreatedByName());
                stmt.setString(13, expense.getTimestamp());
                stmt.setBoolean(14, expense.isSynced());
                stmt.setString(15, expense.getCreatedAt());

                stmt.executeUpdate();
                conn.commit();
            }
        }
    }

    /**
     * Sync expense to backend asynchronously (non-blocking)
     */
    private void syncExpenseToBackendAsync(Expense expense) {
        new Thread(() -> {
            try {
                ApiClient apiClient = ApiClient.getInstance();
                apiClient.post("/pos/expenses", expense, Object.class);
                
                // Mark as synced
                markExpenseAsSynced(expense.getExpenseId());
                logger.info("Expense synced to backend successfully: {}", expense.getExpenseId());
            } catch (ApiClient.ApiException e) {
                logger.debug("Failed to sync expense to backend (will retry later): {}", e.getMessage());
                // Expense remains unsynced, will be synced by ExpenseOutboundSync later
            } catch (Exception e) {
                logger.error("Unexpected error syncing expense to backend", e);
            }
        }, "ExpenseSyncThread").start();
    }

    /**
     * Mark expense as synced
     */
    private void markExpenseAsSynced(String expenseId) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                "UPDATE expenses SET synced = TRUE WHERE expense_id = ?")) {
            stmt.setString(1, expenseId);
            stmt.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            logger.warn("Failed to mark expense as synced: {}", e.getMessage());
        }
    }

    /**
     * Get expenses for a date range
     */
    public List<Expense> getExpensesForDateRange(LocalDate startDate, LocalDate endDate) throws SQLException {
        List<Expense> expenses = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {
            String startDateStr = startDate.atStartOfDay().format(DATE_FORMATTER) + "Z";
            String endDateStr = endDate.atTime(23, 59, 59).format(DATE_FORMATTER) + "Z";

            String sql = """
                SELECT * FROM expenses
                WHERE timestamp >= ? AND timestamp <= ?
                ORDER BY timestamp DESC
            """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, startDateStr);
                stmt.setString(2, endDateStr);

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    expenses.add(mapResultSetToExpense(rs));
                }
            }
        }

        return expenses;
    }

    /**
     * Get expenses for a specific shift
     */
    public List<Expense> getExpensesForShift(String shiftId) throws SQLException {
        List<Expense> expenses = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {
            String sql = """
                SELECT * FROM expenses
                WHERE shift_id = ?
                ORDER BY timestamp DESC
            """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, shiftId);

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    expenses.add(mapResultSetToExpense(rs));
                }
            }
        }

        return expenses;
    }

    /**
     * Get expenses grouped by category for a date range
     */
    public Map<String, BigDecimal> getExpensesByCategory(LocalDate startDate, LocalDate endDate) throws SQLException {
        Map<String, BigDecimal> expensesByCategory = new HashMap<>();
        try (Connection conn = dbManager.getConnection()) {
            String startDateStr = startDate.atStartOfDay().format(DATE_FORMATTER) + "Z";
            String endDateStr = endDate.atTime(23, 59, 59).format(DATE_FORMATTER) + "Z";

            String sql = """
                SELECT category_name, SUM(amount) as total_amount
                FROM expenses
                WHERE timestamp >= ? AND timestamp <= ?
                GROUP BY category_name
                ORDER BY total_amount DESC
            """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, startDateStr);
                stmt.setString(2, endDateStr);

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    String categoryName = rs.getString("category_name");
                    BigDecimal totalAmount = rs.getBigDecimal("total_amount");
                    expensesByCategory.put(categoryName, totalAmount);
                }
            }
        }

        return expensesByCategory;
    }

    /**
     * Get total expenses for a date range
     */
    public BigDecimal getTotalExpenses(LocalDate startDate, LocalDate endDate) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String startDateStr = startDate.atStartOfDay().format(DATE_FORMATTER) + "Z";
            String endDateStr = endDate.atTime(23, 59, 59).format(DATE_FORMATTER) + "Z";

            String sql = """
                SELECT COALESCE(SUM(amount), 0) as total
                FROM expenses
                WHERE timestamp >= ? AND timestamp <= ?
            """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, startDateStr);
                stmt.setString(2, endDateStr);

                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getBigDecimal("total");
                }
            }
        }

        return BigDecimal.ZERO;
    }

    /**
     * Get total expenses for a shift
     */
    public BigDecimal getTotalExpensesForShift(String shiftId) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = """
                SELECT COALESCE(SUM(amount), 0) as total
                FROM expenses
                WHERE shift_id = ?
            """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, shiftId);

                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getBigDecimal("total");
                }
            }
        }

        return BigDecimal.ZERO;
    }

    /**
     * Get total cash expenses for a date range (expenses paid in cash)
     */
    public BigDecimal getTotalCashExpenses(LocalDate startDate, LocalDate endDate) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String startDateStr = startDate.atStartOfDay().format(DATE_FORMATTER) + "Z";
            String endDateStr = endDate.atTime(23, 59, 59).format(DATE_FORMATTER) + "Z";

            String sql = """
                SELECT COALESCE(SUM(amount), 0) as total
                FROM expenses
                WHERE timestamp >= ? AND timestamp <= ?
                AND payment_method = 'CASH'
            """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, startDateStr);
                stmt.setString(2, endDateStr);

                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getBigDecimal("total");
                }
            }
        }

        return BigDecimal.ZERO;
    }

    /**
     * Delete an expense (soft delete or hard delete)
     */
    public boolean deleteExpense(String expenseId) throws SQLException {
        // Permission check - Manager+ required
        RoleBasedAccessService rbacService = RoleBasedAccessService.getInstance();
        rbacService.requirePermission(RoleBasedAccessService.PERMISSION_EXPENSES);

        try (Connection conn = dbManager.getConnection()) {
            String sql = "DELETE FROM expenses WHERE expense_id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, expenseId);

                int deleted = stmt.executeUpdate();
                conn.commit();

                if (deleted > 0) {
                    logger.info("Deleted expense: {}", expenseId);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Map ResultSet to Expense
     */
    private Expense mapResultSetToExpense(ResultSet rs) throws SQLException {
        Expense expense = new Expense();
        expense.setId(rs.getString("id"));
        expense.setExpenseId(rs.getString("expense_id"));
        expense.setCategoryId(rs.getString("category_id"));
        expense.setCategoryName(rs.getString("category_name"));
        expense.setAmount(rs.getBigDecimal("amount"));
        expense.setDescription(rs.getString("description"));
        expense.setPaymentMethod(rs.getString("payment_method"));
        expense.setShiftId(rs.getString("shift_id"));
        expense.setReceiptNumber(rs.getString("receipt_number"));
        expense.setVendorName(rs.getString("vendor_name"));
        expense.setCreatedBy(rs.getString("created_by"));
        expense.setCreatedByName(rs.getString("created_by_name"));
        expense.setTimestamp(rs.getString("timestamp"));
        expense.setSynced(rs.getBoolean("synced"));
        expense.setCreatedAt(rs.getString("created_at"));
        return expense;
    }
}
