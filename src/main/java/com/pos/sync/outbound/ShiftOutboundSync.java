package com.pos.sync.outbound;

import com.pos.api.ApiClient;
import com.pos.api.dto.CashOperationRequest;
import com.pos.database.DatabaseManager;
import com.pos.sync.SyncDirection;
import com.pos.sync.SyncManager;
import com.pos.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Outbound sync handler for shifts and cash operations.
 * Pushes pending shifts from local database to backend.
 * 
 * <h2>Sync Flow</h2>
 * <pre>
 * Local H2 Database ────────────────────────────────────────────────────────
 *     • shifts table (synced = FALSE)
 *     • cash_operations table (synced = FALSE)
 *     │
 *     ▼
 * ShiftOutboundSync ────────────────────────────────────────────────────────
 *     │
 *     │ Get pending shifts
 *     │ Submit to backend
 *     │ Mark as synced on success
 *     │
 *     ▼
 * Backend API ──────────────────────────────────────────────────────────────
 *     POST /pos/shifts/sync
 * </pre>
 */
public class ShiftOutboundSync implements SyncManager.OutboundSyncHandler {
    private static final Logger logger = LoggerFactory.getLogger(ShiftOutboundSync.class);
    
    private static final int BATCH_SIZE = 10;
    
    private static ShiftOutboundSync instance;
    
    private final ApiClient apiClient;
    private final DatabaseManager dbManager;
    
    private ShiftOutboundSync() {
        this.apiClient = ApiClient.getInstance();
        this.dbManager = DatabaseManager.getInstance();
    }
    
    public static synchronized ShiftOutboundSync getInstance() {
        if (instance == null) {
            instance = new ShiftOutboundSync();
        }
        return instance;
    }
    
    @Override
    public String getName() {
        return "ShiftSync";
    }
    
    @Override
    public SyncResult sync() throws Exception {
        int synced = 0;
        int failed = 0;

        while (true) {
            List<ShiftData> pendingShifts = getPendingShifts(BATCH_SIZE);
            if (pendingShifts.isEmpty()) {
                break;
            }

            logger.info("Syncing {} pending shifts", pendingShifts.size());

            for (ShiftData shift : pendingShifts) {
                try {
                    boolean success = submitShift(shift);
                    if (success) {
                        markShiftAsSynced(shift.id);
                        synced++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    logger.warn("Failed to sync shift {}: {}", shift.id, e.getMessage());
                    failed++;
                }
            }
        }

        int cashOpsSynced = syncPendingCashOperations();
        synced += cashOpsSynced;

        if (synced > 0 || failed > 0) {
            logger.info("Shift sync completed: {} synced, {} failed", synced, failed);
        }

        if (synced == 0 && failed == 0) {
            return SyncResult.empty(SyncDirection.OUTBOUND);
        }

        return new SyncResult(SyncDirection.OUTBOUND, synced, failed,
            failed > 0 ? "Some shifts failed to sync" : null);
    }
    
    /**
     * Get pending shifts from local database.
     */
    private List<ShiftData> getPendingShifts(int limit) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = """
                SELECT * FROM shifts
                WHERE synced = FALSE
                ORDER BY created_at ASC
                LIMIT ?
                """;

            List<ShiftData> pendingShifts = new ArrayList<>();

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, limit);
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    ShiftData shift = new ShiftData();
                    shift.id = rs.getString("id");
                    shift.shiftId = rs.getString("shift_id");
                    shift.storeId = rs.getString("store_id");
                    shift.cashierName = rs.getString("cashier_name");
                    shift.cashierId = rs.getString("cashier_id");
                    shift.registerId = rs.getString("register_id");
                    shift.shiftNumber = rs.getInt("shift_number");
                    shift.shiftStartedAt = rs.getString("shift_started_at");
                    shift.shiftEndedAt = rs.getString("shift_ended_at");
                    shift.status = rs.getString("status");
                    shift.openingCash = rs.getBigDecimal("opening_cash");
                    shift.openingNote = rs.getString("opening_note");
                    shift.expectedCash = rs.getBigDecimal("expected_cash");
                    shift.actualCash = rs.getBigDecimal("actual_cash");
                    shift.cashDifference = rs.getBigDecimal("cash_difference");
                    shift.closingNote = rs.getString("closing_note");
                    shift.totalCashSales = rs.getBigDecimal("total_cash_sales");
                    shift.totalCardSales = rs.getBigDecimal("total_card_sales");
                    shift.totalEbtSales = rs.getBigDecimal("total_ebt_sales");
                    shift.totalOtherSales = rs.getBigDecimal("total_other_sales");
                    shift.transactionCount = rs.getInt("transaction_count");
                    shift.grossSales = rs.getBigDecimal("gross_sales");
                    shift.netSales = rs.getBigDecimal("net_sales");
                    shift.totalDiscounts = rs.getBigDecimal("total_discounts");
                    shift.totalTax = rs.getBigDecimal("total_tax");

                    // Read created_at as fallback for shift_started_at
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    if (createdAt != null) {
                        shift.createdAt = createdAt.toInstant();
                    }

                    pendingShifts.add(shift);
                }
            }

            return pendingShifts;
        }
    }
    
    /**
     * Submit shift to backend.
     */
    private boolean submitShift(ShiftData shift) {
        try {
            // Ensure shiftStartedAt is set - use fallback if null
            String shiftStartedAt = shift.shiftStartedAt;
            if (shiftStartedAt == null || shiftStartedAt.trim().isEmpty()) {
                // Use createdAt as fallback, or current time if createdAt is also null
                if (shift.createdAt != null) {
                    shiftStartedAt = shift.createdAt.atOffset(ZoneOffset.UTC)
                            .format(DateTimeFormatter.ISO_INSTANT);
                    logger.warn("Shift {} missing shift_started_at, using created_at as fallback: {}", 
                            shift.id, shiftStartedAt);
                } else {
                    shiftStartedAt = Instant.now().atOffset(ZoneOffset.UTC)
                            .format(DateTimeFormatter.ISO_INSTANT);
                    logger.warn("Shift {} missing shift_started_at and created_at, using current time: {}", 
                            shift.id, shiftStartedAt);
                }
            }
            
            // Build request object
            ShiftSyncRequest request = new ShiftSyncRequest();
            request.shiftId = shift.shiftId;
            request.storeId = shift.storeId;
            request.cashierName = shift.cashierName;
            request.cashierId = shift.cashierId;
            request.registerId = shift.registerId;
            request.shiftNumber = shift.shiftNumber;
            request.shiftStartedAt = shiftStartedAt;
            request.shiftEndedAt = shift.shiftEndedAt;
            request.status = shift.status;
            request.openingCash = shift.openingCash != null ? shift.openingCash.doubleValue() : 0;
            request.expectedCash = shift.expectedCash != null ? shift.expectedCash.doubleValue() : 0;
            request.actualCash = shift.actualCash != null ? shift.actualCash.doubleValue() : 0;
            request.cashDifference = shift.cashDifference != null ? shift.cashDifference.doubleValue() : 0;
            request.totalCashSales = shift.totalCashSales != null ? shift.totalCashSales.doubleValue() : 0;
            request.totalCardSales = shift.totalCardSales != null ? shift.totalCardSales.doubleValue() : 0;
            request.totalEbtSales = shift.totalEbtSales != null ? shift.totalEbtSales.doubleValue() : 0;
            request.totalOtherSales = shift.totalOtherSales != null ? shift.totalOtherSales.doubleValue() : 0;
            request.transactionCount = shift.transactionCount;
            request.grossSales = shift.grossSales != null ? shift.grossSales.doubleValue() : 0;
            request.netSales = shift.netSales != null ? shift.netSales.doubleValue() : 0;
            request.totalDiscounts = shift.totalDiscounts != null ? shift.totalDiscounts.doubleValue() : 0;
            request.totalTax = shift.totalTax != null ? shift.totalTax.doubleValue() : 0;
            
            // Call backend API - response is ignored, success is indicated by no exception
            apiClient.post("/pos/shifts/sync", request, Object.class);
            logger.debug("Shift {} synced successfully to backend", shift.shiftId);
            return true;
            
        } catch (ApiClient.ApiException e) {
            // If it's a duplicate error, consider it synced
            if (e.getMessage() != null && (e.getMessage().contains("duplicate") || 
                e.getMessage().contains("already exists"))) {
                logger.info("Shift {} already exists in backend", shift.shiftId);
                return true;
            }
            logger.error("Failed to submit shift {} to backend: {}", shift.shiftId, e.getMessage());
            return false;
        }
    }
    
    /**
     * Mark shift as synced.
     */
    private void markShiftAsSynced(String shiftId) {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "UPDATE shifts SET synced = TRUE WHERE id = ? OR shift_id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, shiftId);
                stmt.setString(2, shiftId);
                stmt.executeUpdate();
                conn.commit();
            }
        } catch (SQLException e) {
            logger.warn("Failed to mark shift as synced: {}", shiftId, e);
        }
    }
    
    /**
     * Sync pending cash operations.
     */
    private int syncPendingCashOperations() {
        int synced = 0;
        int failed = 0;
        
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT * FROM cash_operations WHERE synced = FALSE ORDER BY created_at ASC LIMIT 20";

            List<CashOperationData> pendingOperations = new ArrayList<>();

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    CashOperationData op = new CashOperationData();
                    op.id = rs.getString("id");
                    op.shiftId = rs.getString("shift_id");
                    op.type = rs.getString("type");
                    op.amount = rs.getBigDecimal("amount");
                    op.note = rs.getString("note");
                    op.performedBy = rs.getString("performed_by");
                    op.verifiedBy = rs.getString("verified_by");
                    pendingOperations.add(op);
                }
            }

            // Sync each operation to backend
            for (CashOperationData op : pendingOperations) {
                try {
                    // Build request object
                    CashOperationRequest request = new CashOperationRequest();
                    request.type = op.type;
                    request.amount = op.amount;
                    request.note = op.note;
                    request.performedBy = op.performedBy;
                    request.verifiedBy = op.verifiedBy;
                    
                    // Call backend API
                    String endpoint = "/pos/shifts/" + op.shiftId + "/cash-operations";
                    apiClient.post(endpoint, request, Object.class);
                    
                    // Mark as synced only on success
                    markCashOperationAsSynced(op.id);
                    synced++;
                    logger.debug("Synced cash operation {} to backend", op.id);
                    
                } catch (ApiClient.ApiException e) {
                    // If it's a duplicate error, consider it synced
                    if (e.getMessage() != null && (e.getMessage().contains("duplicate") || 
                        e.getMessage().contains("already exists"))) {
                        logger.info("Cash operation {} already exists in backend", op.id);
                        markCashOperationAsSynced(op.id);
                        synced++;
                    } else {
                        logger.warn("Failed to sync cash operation {}: {}", op.id, e.getMessage());
                        failed++;
                    }
                } catch (Exception e) {
                    logger.error("Unexpected error syncing cash operation {}: {}", op.id, e.getMessage());
                    failed++;
                }
            }
            
            if (synced > 0 || failed > 0) {
                logger.info("Cash operations sync completed: {} synced, {} failed", synced, failed);
            }
            
        } catch (SQLException e) {
            logger.error("Error syncing cash operations", e);
        }
        
        return synced;
    }
    
    /**
     * Mark cash operation as synced.
     */
    private void markCashOperationAsSynced(String operationId) {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "UPDATE cash_operations SET synced = TRUE WHERE id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, operationId);
                stmt.executeUpdate();
                conn.commit();
            }
        } catch (SQLException e) {
            logger.warn("Failed to mark cash operation as synced: {}", operationId, e);
        }
    }
    
    /**
     * Get count of pending shifts (for status display).
     */
    public int getPendingShiftsCount() {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT COUNT(*) FROM shifts WHERE synced = FALSE";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting pending shifts count", e);
        }
        return 0;
    }
    
    /**
     * Local shift data structure.
     */
    private static class ShiftData {
        String id;
        String shiftId;
        String storeId;
        String cashierName;
        String cashierId;
        String registerId;
        int shiftNumber;
        String shiftStartedAt;
        String shiftEndedAt;
        String status;
        java.math.BigDecimal openingCash;
        String openingNote;
        java.math.BigDecimal expectedCash;
        java.math.BigDecimal actualCash;
        java.math.BigDecimal cashDifference;
        String closingNote;
        java.math.BigDecimal totalCashSales;
        java.math.BigDecimal totalCardSales;
        java.math.BigDecimal totalEbtSales;
        java.math.BigDecimal totalOtherSales;
        int transactionCount;
        java.math.BigDecimal grossSales;
        java.math.BigDecimal netSales;
        java.math.BigDecimal totalDiscounts;
        java.math.BigDecimal totalTax;
        Instant createdAt; // Fallback for shift_started_at
    }
    
    /**
     * Shift sync request for backend.
     */
    private static class ShiftSyncRequest {
        public String shiftId;
        public String storeId;
        public String cashierName;
        public String cashierId;
        public String registerId;
        public int shiftNumber;
        public String shiftStartedAt;
        public String shiftEndedAt;
        public String status;
        public double openingCash;
        public double expectedCash;
        public double actualCash;
        public double cashDifference;
        public double totalCashSales;
        public double totalCardSales;
        public double totalEbtSales;
        public double totalOtherSales;
        public int transactionCount;
        public double grossSales;
        public double netSales;
        public double totalDiscounts;
        public double totalTax;
    }
    
    /**
     * Cash operation data structure.
     */
    private static class CashOperationData {
        String id;
        String shiftId;
        String type;
        BigDecimal amount;
        String note;
        String performedBy;
        String verifiedBy;
    }
}

