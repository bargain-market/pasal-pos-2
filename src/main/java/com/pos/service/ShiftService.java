package com.pos.service;

import com.pos.api.dto.*;
import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.service.RoleBasedAccessService;
import com.pos.service.UserAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Service for managing cashier shifts
 */
public class ShiftService {
    private static final Logger logger = LoggerFactory.getLogger(ShiftService.class);
    private static ShiftService instance;

    private final DatabaseManager dbManager;
    private final ConfigManager config;

    private ShiftService() {
        this.dbManager = DatabaseManager.getInstance();
        this.config = ConfigManager.getInstance();
    }

    public static synchronized ShiftService getInstance() {
        if (instance == null) {
            instance = new ShiftService();
        }
        return instance;
    }

    /**
     * Start a new shift (offline-first: stores locally first, syncs to backend
     * afterwards)
     */
    public ShiftResponse.ShiftData startShift(
            String cashierName,
            String registerId,
            BigDecimal openingCash,
            String openingNote) throws SQLException {
        logger.info("Starting shift for cashier: {}", cashierName);

        // Get current user info
        UserAuthService authService = UserAuthService.getInstance();
        String currentUserName = authService.getCurrentUserName();

        // Use current user name if cashierName not provided
        if (cashierName == null || cashierName.isEmpty()) {
            cashierName = currentUserName != null ? currentUserName : "Cashier";
        }

        String storeId = config.getProperty("store.id");
        if (storeId == null || storeId.isEmpty()) {
            throw new IllegalStateException("Store ID not configured");
        }

        // OFFLINE-FIRST: Create and store shift locally first
        ShiftResponse.ShiftData shift = createLocalShift(cashierName, registerId, openingCash, openingNote);
        storeShiftLocally(shift, false); // Mark as not synced

        logger.info("Shift started locally: {}", shift.id);

        // Trigger outbound sync to ensure shift is synced to backend
        // This is non-blocking and will sync when device comes online if currently
        // offline
        com.pos.sync.SyncManager.getInstance().triggerOutboundSync();

        return shift;
    }

    /**
     * Sync shift to backend asynchronously (non-blocking)
     */

    /**
     * Get active shift (offline-first: checks local first, syncs from backend
     * afterwards)
     */
    public ShiftResponse.ShiftData getActiveShift() throws SQLException {
        // OFFLINE-FIRST: Check local database first
        return getActiveShiftFromLocal();
    }

    /**
     * Sync active shift from backend asynchronously (non-blocking)
     */

    /**
     * End a shift (offline-first: updates local first, syncs to backend afterwards)
     */
    public ShiftResponse.ShiftData endShift(String shiftId, BigDecimal actualCash, String closingNote)
            throws SQLException {
        logger.info("Ending shift: {}", shiftId);

        // OFFLINE-FIRST: Get and update local shift first
        ShiftResponse.ShiftData shift = getShiftFromLocal(shiftId);
        if (shift == null) {
            logger.error("Could not end shift: shift not found locally: {}", shiftId);
            throw new IllegalArgumentException("Shift not found: " + shiftId);
        }

        // Update local shift
        shift.actualCash = actualCash;
        shift.closingNote = closingNote;
        shift.status = "CLOSED";
        shift.expectedCash = calculateAvailableCash(shiftId);
        if (shift.actualCash != null && shift.expectedCash != null) {
            BigDecimal openingCash = shift.openingCash != null ? shift.openingCash : BigDecimal.ZERO;
            // Difference = (Actual drawer - starting cash) - net expected activity
            shift.cashDifference = shift.actualCash.subtract(openingCash).subtract(shift.expectedCash);
        }
        shift.shiftEndedAt = Instant.now().atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);

        updateShiftLocally(shift, false); // Mark as not synced

        // Trigger outbound sync to ensure shift update is synced to backend
        // This is non-blocking and will sync when device comes online if currently
        // offline
        com.pos.sync.SyncManager.getInstance().triggerOutboundSync();

        logger.info("Shift ended locally: {}", shiftId);

        return shift;
    }

    /**
     * Sync end shift to backend asynchronously (non-blocking)
     */

    /**
     * Calculate current available cash for a shift
     * Available cash = Cash Sales + Cash Adds - Cash Drops - Vendor Cash Payouts -
     * Cash Expenses
     */
    public BigDecimal calculateAvailableCash(String shiftId) throws SQLException {
        ShiftResponse.ShiftData shift = getShiftFromLocal(shiftId);
        if (shift == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal cashSales = shift.totalCashSales != null ? shift.totalCashSales : BigDecimal.ZERO;

        // Calculate net cash operations (drops subtract, adds add)
        java.util.List<CashOperationInfo> operations = getCashOperationsForShift(shiftId);
        BigDecimal netCashOperations = BigDecimal.ZERO;

        for (CashOperationInfo op : operations) {
            if ("DROP".equals(op.type)) {
                netCashOperations = netCashOperations.subtract(op.amount);
            } else if ("ADD".equals(op.type)) {
                netCashOperations = netCashOperations.add(op.amount);
            }
        }

        // Subtract vendor cash payouts made during this shift
        BigDecimal vendorCashPayouts = BigDecimal.ZERO;
        try {
            VendorPayoutService vendorPayoutService = VendorPayoutService.getInstance();
            vendorCashPayouts = vendorPayoutService.getTotalCashPayoutsForShift(shiftId);
        } catch (Exception e) {
            logger.warn("Could not get vendor payouts for shift {}: {}", shiftId, e.getMessage());
        }

        BigDecimal cashExpenses = getCashExpensesForShift(shiftId);
        BigDecimal cashRefunds = getCashRefundsForShift(shift);

        return cashSales.add(netCashOperations)
                .subtract(vendorCashPayouts)
                .subtract(cashExpenses)
                .subtract(cashRefunds);
    }

    public BigDecimal calculateExpectedDrawerTotal(String shiftId) throws SQLException {
        ShiftResponse.ShiftData shift = getShiftFromLocal(shiftId);
        if (shift == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal openingCash = shift.openingCash != null ? shift.openingCash : BigDecimal.ZERO;
        return openingCash.add(calculateAvailableCash(shiftId));
    }

    private BigDecimal getCashExpensesForShift(String shiftId) throws SQLException {
        String sql = """
                SELECT COALESCE(SUM(amount), 0) AS total_cash_expenses
                FROM expenses
                WHERE shift_id = ?
                  AND UPPER(COALESCE(payment_method, '')) = 'CASH'
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, shiftId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                BigDecimal total = rs.getBigDecimal("total_cash_expenses");
                return total != null ? total : BigDecimal.ZERO;
            }
        }

        return BigDecimal.ZERO;
    }

    private BigDecimal getCashRefundsForShift(ShiftResponse.ShiftData shift) throws SQLException {
        if (shift == null || shift.id == null || shift.shiftStartedAt == null) {
            return BigDecimal.ZERO;
        }

        String sql = """
                SELECT COALESCE(SUM(r.total_refund), 0) AS total_cash_refunds
                FROM refunds r
                WHERE UPPER(COALESCE(r.refund_method, '')) = 'CASH'
                  AND (r.pos_user_id = ? OR ? IS NULL)
                  AND r.timestamp >= ?
                  AND (? IS NULL OR r.timestamp <= ?)
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, shift.cashierId);
            stmt.setString(2, shift.cashierId);
            stmt.setString(3, shift.shiftStartedAt);
            stmt.setString(4, shift.shiftEndedAt);
            stmt.setString(5, shift.shiftEndedAt);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                BigDecimal total = rs.getBigDecimal("total_cash_refunds");
                return total != null ? total : BigDecimal.ZERO;
            }
        }

        return BigDecimal.ZERO;
    }

    /**
     * Get actual cash count from the most recently closed shift for carry-over
     */
    public BigDecimal getPreviousShiftActualCash() {
        String sql = "SELECT actual_cash FROM shifts WHERE status = 'CLOSED' ORDER BY shift_ended_at DESC LIMIT 1";
        try (Connection conn = DatabaseManager.getInstance().getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                BigDecimal actualCash = rs.getBigDecimal("actual_cash");
                return actualCash != null ? actualCash : BigDecimal.ZERO;
            }
        } catch (SQLException e) {
            logger.error("Error getting previous shift actual cash", e);
        }
        return BigDecimal.ZERO;
    }

    /**
     * Record a cash operation (offline-first: stores locally first, syncs to
     * backend afterwards)
     */
    public void recordCashOperation(
            String shiftId,
            String type,
            BigDecimal amount,
            String note,
            String performedById,
            String performedByName) throws SQLException {
        recordCashOperation(shiftId, type, amount, note, performedById, performedByName, null);
    }

    /**
     * Record a cash operation with PIN override for limit validation (offline-first)
     */
    public void recordCashOperation(
            String shiftId,
            String type,
            BigDecimal amount,
            String note,
            String performedById,
            String performedByName,
            String overridePin) throws SQLException {
        // Permission check - Manager+ required
        RoleBasedAccessService rbacService = RoleBasedAccessService.getInstance();
        rbacService.requirePermission(RoleBasedAccessService.PERMISSION_CASH_OPERATIONS);

        logger.info("Recording cash operation: {} - {} for shift {}", type, amount, shiftId);

        // Validate amount is positive (except for NO_SALE which has zero amount)
        if (!"NO_SALE".equals(type) && amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Cash operation amount must be greater than zero");
        }

        // Check cash/check limit for limited operation types
        CashCheckLimitService limitService = CashCheckLimitService.getInstance();
        CashCheckLimitService.ValidationResult validation = limitService.validateAndRecordOperation(type, overridePin);

        if (!validation.allowed) {
            throw new SecurityException(validation.errorMessage);
        }

        // OFFLINE-FIRST: Store locally first
        storeCashOperationLocally(shiftId, type, amount, note, performedById, performedByName);

        logger.info("Cash operation recorded locally (limit check passed, remaining: {})", validation.remaining);

        // Trigger outbound sync to ensure cash operation is synced to backend
        // This is non-blocking and will sync when device comes online if currently
        // offline
        com.pos.sync.SyncManager.getInstance().triggerOutboundSync();
    }

    /**
     * Sync cash operation to backend asynchronously (non-blocking)
     */

    /**
     * Create local shift (for offline mode)
     */
    private ShiftResponse.ShiftData createLocalShift(
            String cashierName,
            String registerId,
            BigDecimal openingCash,
            String openingNote) {
        ShiftResponse.ShiftData shift = new ShiftResponse.ShiftData();
        shift.id = UUID.randomUUID().toString();
        shift.storeId = config.getProperty("store.id");
        shift.cashierName = cashierName;
        shift.registerId = registerId;
        shift.openingCash = openingCash;
        shift.openingNote = openingNote;
        shift.status = "ACTIVE";
        shift.shiftStartedAt = Instant.now().atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);
        shift.totalCashSales = BigDecimal.ZERO;
        shift.totalCardSales = BigDecimal.ZERO;
        shift.transactionCount = 0;
        shift.grossSales = BigDecimal.ZERO;
        shift.netSales = BigDecimal.ZERO;

        // Set cashierId to link shift to current user
        UserAuthService authService = UserAuthService.getInstance();
        shift.cashierId = authService.getCurrentPosUserId();

        return shift;
    }

    /**
     * Store shift in local database
     * 
     * @param shift  The shift data to store
     * @param synced Whether the shift is already synced to backend
     */
    private void storeShiftLocally(ShiftResponse.ShiftData shift, boolean synced) throws SQLException {
        if (shift == null) {
            logger.error("Attempted to store null shift, skipping");
            throw new IllegalArgumentException("Cannot store null shift");
        }

        String sql = "MERGE INTO shifts " +
                "(id, shift_id, store_id, cashier_name, cashier_id, register_id, shift_number, " +
                "shift_started_at, shift_ended_at, status, opening_cash, opening_note, " +
                "expected_cash, actual_cash, cash_difference, closing_note, " +
                "total_cash_sales, total_card_sales, total_ebt_sales, total_other_sales, " +
                "transaction_count, gross_sales, net_sales, total_discounts, total_tax, synced) " +
                "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            int idx = 1;
            stmt.setString(idx++, shift.id);
            stmt.setString(idx++, shift.id);
            stmt.setString(idx++, shift.storeId);
            stmt.setString(idx++, shift.cashierName);
            stmt.setString(idx++, shift.cashierId);
            stmt.setString(idx++, shift.registerId);
            stmt.setObject(idx++, shift.shiftNumber);
            stmt.setString(idx++, shift.shiftStartedAt);
            stmt.setString(idx++, shift.shiftEndedAt);
            stmt.setString(idx++, shift.status);
            stmt.setBigDecimal(idx++, shift.openingCash);
            stmt.setString(idx++, shift.openingNote);
            stmt.setObject(idx++, shift.expectedCash);
            stmt.setObject(idx++, shift.actualCash);
            stmt.setObject(idx++, shift.cashDifference);
            stmt.setString(idx++, shift.closingNote);
            stmt.setBigDecimal(idx++, shift.totalCashSales != null ? shift.totalCashSales : BigDecimal.ZERO);
            stmt.setBigDecimal(idx++, shift.totalCardSales != null ? shift.totalCardSales : BigDecimal.ZERO);
            stmt.setBigDecimal(idx++, shift.totalEbtSales != null ? shift.totalEbtSales : BigDecimal.ZERO);
            stmt.setBigDecimal(idx++, shift.totalOtherSales != null ? shift.totalOtherSales : BigDecimal.ZERO);
            stmt.setInt(idx++, shift.transactionCount != null ? shift.transactionCount : 0);
            stmt.setBigDecimal(idx++, shift.grossSales != null ? shift.grossSales : BigDecimal.ZERO);
            stmt.setBigDecimal(idx++, shift.netSales != null ? shift.netSales : BigDecimal.ZERO);
            stmt.setBigDecimal(idx++, shift.totalDiscounts != null ? shift.totalDiscounts : BigDecimal.ZERO);
            stmt.setBigDecimal(idx++, shift.totalTax != null ? shift.totalTax : BigDecimal.ZERO);
            stmt.setBoolean(idx++, synced);

            stmt.executeUpdate();
            conn.commit();
        }
    }

    /**
     * Store shift in local database (defaults to synced=false for offline-first)
     */
    private void storeShiftLocally(ShiftResponse.ShiftData shift) throws SQLException {
        storeShiftLocally(shift, false);
    }

    /**
     * Update shift in local database
     * 
     * @param shift  The shift data to update
     * @param synced Whether the shift is already synced to backend
     */
    private void updateShiftLocally(ShiftResponse.ShiftData shift, boolean synced) throws SQLException {
        storeShiftLocally(shift, synced); // INSERT OR REPLACE handles update
    }

    /**
     * Update shift in local database (defaults to synced=false for offline-first)
     */
    private void updateShiftLocally(ShiftResponse.ShiftData shift) throws SQLException {
        updateShiftLocally(shift, false);
    }

    /**
     * Get active shift from local database for the current user
     */
    public ShiftResponse.ShiftData getActiveShiftFromLocal() throws SQLException {
        UserAuthService authService = UserAuthService.getInstance();
        String currentUserId = authService.getCurrentPosUserId();

        String sql;

        try (Connection conn = dbManager.getConnection()) {
            if (currentUserId != null) {
                sql = "SELECT * FROM shifts WHERE status = 'ACTIVE' AND cashier_id = ? ORDER BY shift_started_at DESC LIMIT 1";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, currentUserId);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        return mapResultSetToShift(rs);
                    }
                }
            } else {
                sql = "SELECT * FROM shifts WHERE status = 'ACTIVE' ORDER BY shift_started_at DESC LIMIT 1";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        return mapResultSetToShift(rs);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Get shift from local database by ID
     */
    private ShiftResponse.ShiftData getShiftFromLocal(String shiftId) throws SQLException {
        String sql = "SELECT * FROM shifts WHERE id = ? OR shift_id = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, shiftId);
            stmt.setString(2, shiftId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return mapResultSetToShift(rs);
            }
        }

        return null;
    }

    /**
     * Store cash operation locally
     */
    private void storeCashOperationLocally(
            String shiftId,
            String type,
            BigDecimal amount,
            String note,
            String performedById,
            String performedByName) throws SQLException {
        String sql = """
                INSERT INTO cash_operations (id, shift_id, type, amount, note, performed_by, performed_by_name, created_at, synced)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE)
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, UUID.randomUUID().toString());
            stmt.setString(2, shiftId);
            stmt.setString(3, type);
            stmt.setBigDecimal(4, amount);
            stmt.setString(5, note);
            stmt.setString(6, performedById);
            stmt.setString(7, performedByName);
            stmt.setString(8, Instant.now().atOffset(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_INSTANT));
            stmt.executeUpdate();
            conn.commit();
        }
    }

    /**
     * Map ResultSet to ShiftData
     */
    private ShiftResponse.ShiftData mapResultSetToShift(ResultSet rs) throws SQLException {
        ShiftResponse.ShiftData shift = new ShiftResponse.ShiftData();
        shift.id = rs.getString("id");
        shift.storeId = rs.getString("store_id");
        shift.cashierName = rs.getString("cashier_name");
        shift.cashierId = rs.getString("cashier_id");
        shift.registerId = rs.getString("register_id");
        shift.shiftNumber = rs.getObject("shift_number", Integer.class);
        shift.shiftStartedAt = rs.getString("shift_started_at");
        shift.shiftEndedAt = rs.getString("shift_ended_at");
        shift.status = rs.getString("status");
        shift.openingCash = rs.getBigDecimal("opening_cash");
        shift.openingNote = rs.getString("opening_note");
        shift.expectedCash = rs.getObject("expected_cash", BigDecimal.class);
        shift.actualCash = rs.getObject("actual_cash", BigDecimal.class);
        shift.cashDifference = rs.getObject("cash_difference", BigDecimal.class);
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
        return shift;
    }

    /**
     * Update shift totals after a sale
     * 
     * @param shiftId       The shift ID
     * @param amount        The sale total amount
     * @param paymentMethod The payment method used
     * @param itemCount     Number of items in the sale
     */
    public void updateShiftTotals(String shiftId, BigDecimal amount, String paymentMethod, int itemCount)
            throws SQLException {
        updateShiftTotals(shiftId, amount, paymentMethod, itemCount, BigDecimal.ZERO);
    }

    /**
     * Update shift totals after a sale with GPI
     * 
     * @param shiftId       The shift ID
     * @param amount        The sale total amount
     * @param paymentMethod The payment method used
     * @param itemCount     Number of items in the sale
     * @param gpi           The GPI (Gross Profit Increase) for this sale
     */
    public void updateShiftTotals(String shiftId, BigDecimal amount, String paymentMethod, int itemCount,
            BigDecimal gpi)
            throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            updateShiftTotals(conn, shiftId, amount, paymentMethod, gpi);
            conn.commit();
        }
    }

    /**
     * Update shift totals using an existing connection (caller manages transaction).
     */
    public void updateShiftTotals(Connection conn, String shiftId, BigDecimal amount, String paymentMethod,
            BigDecimal gpi) throws SQLException {
        String updateField = switch (paymentMethod) {
            case "CASH" -> "total_cash_sales";
            case "CARD" -> "total_card_sales";
            case "EBT" -> "total_ebt_sales";
            default -> "total_other_sales";
        };

        BigDecimal netAmount = amount.subtract(gpi != null ? gpi : BigDecimal.ZERO);

        String sql = String.format("""
                UPDATE shifts SET
                    %s = %s + ?,
                    transaction_count = transaction_count + 1,
                    gross_sales = gross_sales + ?,
                    net_sales = net_sales + ?,
                    synced = FALSE
                WHERE id = ? OR shift_id = ?
                """, updateField, updateField);

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBigDecimal(1, amount);
            stmt.setBigDecimal(2, amount);
            stmt.setBigDecimal(3, netAmount);
            stmt.setString(4, shiftId);
            stmt.setString(5, shiftId);
            stmt.executeUpdate();
        }
    }

    /**
     * Get cash operations for a shift
     */
    public java.util.List<CashOperationInfo> getCashOperationsForShift(String shiftId) throws SQLException {
        String sql = "SELECT * FROM cash_operations WHERE shift_id = ? ORDER BY created_at DESC";

        java.util.List<CashOperationInfo> operations = new java.util.ArrayList<>();
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, shiftId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                CashOperationInfo op = new CashOperationInfo();
                op.id = rs.getString("id");
                op.shiftId = rs.getString("shift_id");
                op.type = rs.getString("type");
                op.amount = rs.getBigDecimal("amount");
                op.note = rs.getString("note");
                op.performedBy = rs.getString("performed_by");
                op.performedByName = rs.getString("performed_by_name");
                op.createdAt = rs.getString("created_at");
                op.synced = rs.getBoolean("synced");
                operations.add(op);
            }
        }
        return operations;
    }

    /**
     * Record a No Sale operation (opens cash drawer without a transaction)
     * This is tracked for audit purposes
     */
    public void recordNoSale(String shiftId, String performedById, String performedByName) throws SQLException {
        logger.info("Recording No Sale operation for shift {} by {} ({})", shiftId, performedByName, performedById);

        // Store locally with type "NO_SALE" and zero amount
        storeCashOperationLocally(shiftId, "NO_SALE", BigDecimal.ZERO, "Cash drawer opened without sale", performedById,
                performedByName);

        logger.info("No Sale operation recorded locally");

        // Trigger outbound sync
        com.pos.sync.SyncManager.getInstance().triggerOutboundSync();
    }

    /**
     * Data class for cash operation information
     */
    public static class CashOperationInfo {
        public String id;
        public String shiftId;
        public String type;
        public BigDecimal amount;
        public String note;
        public String performedBy;
        public String performedByName;
        public String createdAt;
        public boolean synced;
    }

    /**
     * Get all active shifts across all users.
     */
    public java.util.List<ShiftResponse.ShiftData> getAllActiveShifts() throws SQLException {
        String sql = "SELECT * FROM shifts WHERE status = 'ACTIVE' ORDER BY shift_started_at ASC";
        java.util.List<ShiftResponse.ShiftData> activeShifts = new java.util.ArrayList<>();
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                activeShifts.add(mapResultSetToShift(rs));
            }
        }
        return activeShifts;
    }

    /**
     * Close ALL stale shifts from previous days regardless of which user they
     * belong to.
     * This should be called on login to ensure no old shifts are left open.
     * 
     * @return the number of stale shifts that were closed
     */
    public int closeAllStaleShifts() {
        int closedCount = 0;
        try (Connection conn = dbManager.getConnection()) {
            // Find ALL active shifts (no user filter)
            String sql = "SELECT * FROM shifts WHERE status = 'ACTIVE' ORDER BY shift_started_at ASC";

            java.util.List<ShiftResponse.ShiftData> staleShifts = new java.util.ArrayList<>();

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    ShiftResponse.ShiftData shift = mapResultSetToShift(rs);
                    // Check if the shift belongs to a previous day
                    if (shift.shiftStartedAt != null) {
                        try {
                            java.time.ZonedDateTime startedAt = java.time.ZonedDateTime.parse(shift.shiftStartedAt);
                            java.time.LocalDate shiftDate = startedAt.withZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate();
                            // Use logical date (now - 2 hours) for staleness check
                            java.time.LocalDate logicalToday = java.time.LocalDateTime.now().minusHours(2).toLocalDate();

                            if (shiftDate.isBefore(logicalToday)) {
                                staleShifts.add(shift);
                            }
                        } catch (java.time.format.DateTimeParseException e) {
                            logger.warn("Could not parse shift start time for shift {}: {}", shift.id,
                                    shift.shiftStartedAt);
                        }
                    }
                }
            }

            // Close each stale shift
            for (ShiftResponse.ShiftData staleShift : staleShifts) {
                try {
                    BigDecimal actualCash = calculateExpectedDrawerTotal(staleShift.id);
                    String closingNote = "System auto-closed: shift from previous day (" + staleShift.shiftStartedAt
                            + ")";
                    endShift(staleShift.id, actualCash, closingNote);
                    closedCount++;
                    logger.info("Auto-closed stale shift {} (started: {}, cashier: {})",
                            staleShift.id, staleShift.shiftStartedAt, staleShift.cashierName);
                } catch (Exception e) {
                    logger.error("Failed to auto-close stale shift {}: {}", staleShift.id, e.getMessage());
                }
            }

            if (closedCount > 0) {
                logger.info("Closed {} stale shift(s) from previous days", closedCount);
            }
        } catch (Exception e) {
            logger.error("Error closing stale shifts", e);
        }
        return closedCount;
    }

    /**
     * Check if there is an active shift for the current user.
     * This is a quick check that does not throw exceptions.
     * 
     * @return true if an active shift exists, false otherwise
     */
    public boolean hasActiveShift() {
        try {
            ShiftResponse.ShiftData shift = getActiveShiftFromLocal();
            return shift != null && "ACTIVE".equals(shift.status);
        } catch (Exception e) {
            logger.warn("Error checking active shift", e);
            return false;
        }
    }

    /**
     * Require an active shift, throw exception if none exists.
     * Use this before processing any transaction that requires an active shift.
     * 
     * @return the active shift data
     * @throws IllegalStateException if no active shift exists
     */
    public ShiftResponse.ShiftData requireActiveShift() throws IllegalStateException {
        try {
            ShiftResponse.ShiftData shift = getActiveShiftFromLocal();
            if (shift == null || !"ACTIVE".equals(shift.status)) {
                throw new IllegalStateException(
                        "No active shift. Please start a shift before processing transactions.");
            }

            // STALE CHECK: Ensure shift belongs to today
            if (shift.shiftStartedAt != null) {
                try {
                    // Parse ISO timestamp (e.g., 2023-10-25T10:15:30Z)
                    java.time.ZonedDateTime startedAt = java.time.ZonedDateTime.parse(shift.shiftStartedAt);
                    java.time.LocalDate shiftDate = startedAt.withZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate();
                    // Use logical date (now - 2 hours) for staleness check
                    java.time.LocalDate logicalToday = java.time.LocalDateTime.now().minusHours(2).toLocalDate();

                    if (shiftDate.isBefore(logicalToday)) {
                        logger.warn("Blocking access to stale shift {} from {}", shift.id, shiftDate);
                        throw new IllegalStateException(
                                "Active shift belongs to a previous day (" + shiftDate
                                        + "). Please close and restart shift.");
                    }
                } catch (java.time.format.DateTimeParseException e) {
                    logger.warn("Could not parse shift start time: {}", shift.shiftStartedAt);
                    // Continue, assume valid if parsing fails to avoid blocking on corrupted data
                }
            }

            return shift;
        } catch (SQLException e) {
            logger.error("Error checking shift status", e);
            throw new IllegalStateException("Cannot verify shift status: " + e.getMessage());
        }
    }
}
