package com.pos.sync.outbound;

import com.pos.api.ApiClient;
import com.pos.database.DatabaseManager;
import com.pos.model.Expense;
import com.pos.sync.SyncDirection;
import com.pos.sync.SyncManager;
import com.pos.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Outbound sync handler for expenses.
 * Pushes pending expenses from local database to backend.
 */
public class ExpenseOutboundSync implements SyncManager.OutboundSyncHandler {
    private static final Logger logger = LoggerFactory.getLogger(ExpenseOutboundSync.class);
    
    private static final int BATCH_SIZE = 20;
    
    private static ExpenseOutboundSync instance;
    
    private final ApiClient apiClient;
    private final DatabaseManager dbManager;
    
    private ExpenseOutboundSync() {
        this.apiClient = ApiClient.getInstance();
        this.dbManager = DatabaseManager.getInstance();
    }
    
    public static synchronized ExpenseOutboundSync getInstance() {
        if (instance == null) {
            instance = new ExpenseOutboundSync();
        }
        return instance;
    }
    
    @Override
    public String getName() {
        return "ExpenseSync";
    }
    
    @Override
    public SyncResult sync() throws Exception {
        int synced = 0;
        int failed = 0;

        while (true) {
            List<Expense> pendingExpenses = getPendingExpenses(BATCH_SIZE);
            if (pendingExpenses.isEmpty()) {
                break;
            }

            logger.info("Syncing {} pending expenses", pendingExpenses.size());

            for (Expense expense : pendingExpenses) {
                try {
                    boolean success = submitExpense(expense);
                    if (success) {
                        markExpenseAsSynced(expense.getExpenseId());
                        synced++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    logger.warn("Failed to sync expense {}: {}", expense.getExpenseId(), e.getMessage());
                    failed++;
                }
            }
        }

        if (synced == 0 && failed == 0) {
            return SyncResult.empty(SyncDirection.OUTBOUND);
        }

        return new SyncResult(SyncDirection.OUTBOUND, synced, failed,
                failed > 0 ? "Some expenses failed to sync" : null);
    }
    
    /**
     * Get pending expenses that need to be synced
     */
    private List<Expense> getPendingExpenses(int limit) throws SQLException {
        List<Expense> expenses = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT * FROM expenses WHERE synced = FALSE ORDER BY created_at ASC LIMIT ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, limit);
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    expenses.add(mapResultSetToExpense(rs));
                }
            }
        }

        return expenses;
    }
    
    /**
     * Submit expense to backend
     */
    private boolean submitExpense(Expense expense) {
        try {
            apiClient.post("/pos/expenses", expense, Object.class);
            return true;
        } catch (ApiClient.ApiException e) {
            logger.debug("Failed to submit expense to backend: {}", e.getMessage());
            return false;
        }
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
