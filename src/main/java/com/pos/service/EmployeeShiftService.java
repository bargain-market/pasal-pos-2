package com.pos.service;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.model.EmployeeActivity;
import com.pos.model.EmployeeShift;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing employee shift tracking (clock-in/clock-out)
 * This tracks employee work hours separately from cash session management.
 */
public class EmployeeShiftService {
    private static final Logger logger = LoggerFactory.getLogger(EmployeeShiftService.class);
    private static EmployeeShiftService instance;

    private final DatabaseManager dbManager;
    private final ConfigManager config;

    private EmployeeShiftService() {
        this.dbManager = DatabaseManager.getInstance();
        this.config = ConfigManager.getInstance();
    }

    public static synchronized EmployeeShiftService getInstance() {
        if (instance == null) {
            instance = new EmployeeShiftService();
        }
        return instance;
    }

    /**
     * Clock in an employee - records the start of their shift
     * @param employeeId The employee's ID
     * @param employeeName The employee's display name
     * @return The created EmployeeShift record
     */
    public EmployeeShift clockIn(String employeeId, String employeeName) throws SQLException {
        logger.info("Clocking in employee: {} ({})", employeeName, employeeId);

        // Check if employee already has an active shift
        EmployeeShift existingShift = getActiveShift(employeeId);
        if (existingShift != null) {
            logger.warn("Employee {} already has an active shift, returning existing shift", employeeId);
            return existingShift;
        }

        String storeId = config.getProperty("store.id");
        String id = UUID.randomUUID().toString();
        LocalDateTime clockInTime = LocalDateTime.now();

        try (Connection conn = dbManager.getConnection()) {
            String sql = """
                    INSERT INTO employee_shifts (id, employee_id, employee_name, store_id, clock_in_at, status, synced, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, FALSE, ?)
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, employeeId);
                stmt.setString(3, employeeName);
                stmt.setString(4, storeId);
                stmt.setTimestamp(5, Timestamp.valueOf(clockInTime));
                stmt.setString(6, EmployeeShift.STATUS_ACTIVE);
                stmt.setTimestamp(7, Timestamp.valueOf(clockInTime));
                stmt.executeUpdate();
                conn.commit();
            }
        }

        logger.info("Employee {} clocked in at {}", employeeName, clockInTime);

        return getShiftById(id);
    }

    /**
     * Create a manual shift entry with explicit timestamps.
     * Clock-out may be null to keep the shift active.
     */
    public EmployeeShift createManualShift(String employeeId, String employeeName,
            LocalDateTime clockInAt, LocalDateTime clockOutAt, String notes) throws SQLException {
        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException("Employee is required");
        }
        if (employeeName == null || employeeName.isBlank()) {
            throw new IllegalArgumentException("Employee name is required");
        }
        if (clockInAt == null) {
            throw new IllegalArgumentException("Clock-in date and time are required");
        }
        if (clockOutAt != null && clockOutAt.isBefore(clockInAt)) {
            throw new IllegalArgumentException("Clock-out cannot be earlier than clock-in");
        }

        EmployeeShift existingActiveShift = getActiveShift(employeeId);
        if (clockOutAt == null && existingActiveShift != null) {
            throw new IllegalArgumentException("This user already has an active timesheet");
        }

        String storeId = config.getProperty("store.id");
        String id = UUID.randomUUID().toString();
        String normalizedNotes = notes != null && !notes.isBlank() ? notes.trim() : null;
        String status = clockOutAt == null ? EmployeeShift.STATUS_ACTIVE : EmployeeShift.STATUS_COMPLETED;

        EmployeeShift manualShift = new EmployeeShift();
        manualShift.setId(id);
        manualShift.setEmployeeId(employeeId);
        manualShift.setEmployeeName(employeeName);
        manualShift.setStoreId(storeId);
        manualShift.setClockInAt(clockInAt);
        manualShift.setClockOutAt(clockOutAt);
        manualShift.setStatus(status);
        manualShift.setNotes(normalizedNotes);
        manualShift.setSynced(false);
        manualShift.setCreatedAt(LocalDateTime.now());
        if (clockOutAt != null) {
            manualShift.setTotalHours(manualShift.calculateTotalHours());
        }

        try (Connection conn = dbManager.getConnection()) {
            String sql = """
                    INSERT INTO employee_shifts (
                        id, employee_id, employee_name, store_id, clock_in_at, clock_out_at,
                        status, total_hours, notes, synced, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?)
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, employeeId);
                stmt.setString(3, employeeName);
                stmt.setString(4, storeId);
                stmt.setTimestamp(5, Timestamp.valueOf(clockInAt));
                stmt.setTimestamp(6, clockOutAt != null ? Timestamp.valueOf(clockOutAt) : null);
                stmt.setString(7, status);
                stmt.setBigDecimal(8, manualShift.getTotalHours());
                stmt.setString(9, normalizedNotes);
                stmt.setTimestamp(10, Timestamp.valueOf(manualShift.getCreatedAt()));
                stmt.executeUpdate();
                conn.commit();
            }
        }

        logger.info("Manual employee shift created for {} ({}) from {} to {}",
                employeeName, employeeId, clockInAt, clockOutAt);

        return getShiftById(id);
    }

    /**
     * Update an existing manual shift entry with explicit timestamps.
     * Clock-out may be null to keep the shift active.
     */
    public EmployeeShift updateManualShift(String shiftId, String employeeId, String employeeName,
            LocalDateTime clockInAt, LocalDateTime clockOutAt, String notes) throws SQLException {
        if (shiftId == null || shiftId.isBlank()) {
            throw new IllegalArgumentException("Shift is required");
        }
        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException("Employee is required");
        }
        if (employeeName == null || employeeName.isBlank()) {
            throw new IllegalArgumentException("Employee name is required");
        }
        if (clockInAt == null) {
            throw new IllegalArgumentException("Clock-in date and time are required");
        }
        if (clockOutAt != null && clockOutAt.isBefore(clockInAt)) {
            throw new IllegalArgumentException("Clock-out cannot be earlier than clock-in");
        }

        EmployeeShift existingShift = getShiftById(shiftId);
        if (existingShift == null) {
            throw new IllegalArgumentException("The selected timesheet no longer exists");
        }
        if (!employeeId.equals(existingShift.getEmployeeId())) {
            throw new IllegalArgumentException("Timesheet can only be edited for the same user");
        }

        EmployeeShift existingActiveShift = getActiveShift(employeeId);
        if (clockOutAt == null
                && existingActiveShift != null
                && !shiftId.equals(existingActiveShift.getId())) {
            throw new IllegalArgumentException("This user already has another active timesheet");
        }

        String normalizedNotes = notes != null && !notes.isBlank() ? notes.trim() : null;
        String status = clockOutAt == null ? EmployeeShift.STATUS_ACTIVE : EmployeeShift.STATUS_COMPLETED;
        BigDecimal totalHours = clockOutAt != null
                ? BigDecimal.valueOf(java.time.Duration.between(clockInAt, clockOutAt).toMinutes() / 60.0)
                        .setScale(2, java.math.RoundingMode.HALF_UP)
                : null;

        try (Connection conn = dbManager.getConnection()) {
            String sql = """
                    UPDATE employee_shifts
                    SET employee_name = ?, clock_in_at = ?, clock_out_at = ?, status = ?, total_hours = ?,
                        notes = ?, synced = FALSE
                    WHERE id = ? AND employee_id = ?
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, employeeName);
                stmt.setTimestamp(2, Timestamp.valueOf(clockInAt));
                stmt.setTimestamp(3, clockOutAt != null ? Timestamp.valueOf(clockOutAt) : null);
                stmt.setString(4, status);
                stmt.setBigDecimal(5, totalHours);
                stmt.setString(6, normalizedNotes);
                stmt.setString(7, shiftId);
                stmt.setString(8, employeeId);

                int updatedRows = stmt.executeUpdate();
                if (updatedRows == 0) {
                    throw new IllegalArgumentException("Failed to update the selected timesheet");
                }
                conn.commit();
            }
        }

        logger.info("Manual employee shift updated for {} ({}) from {} to {}",
                employeeName, employeeId, clockInAt, clockOutAt);

        return getShiftById(shiftId);
    }

    /**
     * Clock out an employee - records the end of their shift and calculates hours
     * @param employeeId The employee's ID
     * @param notes Optional notes for the shift
     * @return The updated EmployeeShift record, or null if no active shift found
     */
    public EmployeeShift clockOut(String employeeId, String notes) throws SQLException {
        logger.info("Clocking out employee: {}", employeeId);

        EmployeeShift activeShift = getActiveShift(employeeId);
        if (activeShift == null) {
            logger.warn("No active shift found for employee {}", employeeId);
            return null;
        }

        LocalDateTime clockOutTime = LocalDateTime.now();
        BigDecimal totalHours = activeShift.calculateTotalHours();

        // Update with clock-out time set to now
        activeShift.setClockOutAt(clockOutTime);
        totalHours = activeShift.calculateTotalHours();

        try (Connection conn = dbManager.getConnection()) {
            String sql = """
                    UPDATE employee_shifts
                    SET clock_out_at = ?, status = ?, total_hours = ?, notes = ?, synced = FALSE
                    WHERE id = ?
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setTimestamp(1, Timestamp.valueOf(clockOutTime));
                stmt.setString(2, EmployeeShift.STATUS_COMPLETED);
                stmt.setBigDecimal(3, totalHours);
                stmt.setString(4, notes);
                stmt.setString(5, activeShift.getId());
                stmt.executeUpdate();
                conn.commit();
            }
        }

        logger.info("Employee {} clocked out at {}. Total hours: {}", 
                activeShift.getEmployeeName(), clockOutTime, totalHours);

        return getShiftById(activeShift.getId());
    }

    /**
     * Clock out an employee without notes
     */
    public EmployeeShift clockOut(String employeeId) throws SQLException {
        return clockOut(employeeId, null);
    }

    /**
     * Get the currently active shift for an employee
     * @param employeeId The employee's ID
     * @return The active EmployeeShift, or null if none exists
     */
    public EmployeeShift getActiveShift(String employeeId) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT * FROM employee_shifts WHERE employee_id = ? AND status = ? ORDER BY clock_in_at DESC LIMIT 1";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, employeeId);
                stmt.setString(2, EmployeeShift.STATUS_ACTIVE);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return mapResultSetToEmployeeShift(rs);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Get a shift by its ID
     */
    public EmployeeShift getShiftById(String shiftId) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT * FROM employee_shifts WHERE id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, shiftId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return mapResultSetToEmployeeShift(rs);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Get shift history for an employee within a date range
     * @param employeeId The employee's ID (null for all employees)
     * @param startDate Start of date range
     * @param endDate End of date range
     * @return List of EmployeeShift records
     */
    public List<EmployeeShift> getShiftHistory(String employeeName, LocalDate startDate, LocalDate endDate) throws SQLException {
        List<EmployeeShift> shifts = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {
            StringBuilder sql = new StringBuilder(
                    "SELECT * FROM employee_shifts WHERE clock_in_at >= ? AND clock_in_at < ?");
            
            if (employeeName != null && !employeeName.isEmpty()) {
                sql.append(" AND employee_name = ?");
            }
            sql.append(" ORDER BY clock_in_at DESC");

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                stmt.setTimestamp(1, Timestamp.valueOf(startDate.atStartOfDay()));
                stmt.setTimestamp(2, Timestamp.valueOf(endDate.plusDays(1).atStartOfDay()));
                
                if (employeeName != null && !employeeName.isEmpty()) {
                    stmt.setString(3, employeeName);
                }

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        shifts.add(mapResultSetToEmployeeShift(rs));
                    }
                }
            }
        }

        return shifts;
    }

    /**
     * Get all shifts for an employee
     */
    public List<EmployeeShift> getAllShiftsForEmployee(String employeeId) throws SQLException {
        List<EmployeeShift> shifts = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT * FROM employee_shifts WHERE employee_id = ? ORDER BY clock_in_at DESC";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, employeeId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        shifts.add(mapResultSetToEmployeeShift(rs));
                    }
                }
            }
        }

        return shifts;
    }

    /**
     * Get recent shifts for an employee (last N shifts)
     */
    public List<EmployeeShift> getRecentShifts(String employeeId, int limit) throws SQLException {
        List<EmployeeShift> shifts = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT * FROM employee_shifts WHERE employee_id = ? ORDER BY clock_in_at DESC LIMIT ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, employeeId);
                stmt.setInt(2, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        shifts.add(mapResultSetToEmployeeShift(rs));
                    }
                }
            }
        }

        return shifts;
    }

    /**
     * Calculate total hours worked for an employee within a date range
     * @param employeeId The employee's ID
     * @param startDate Start of date range
     * @param endDate End of date range
     * @return Total hours worked as BigDecimal
     */
    public BigDecimal getTotalHoursForPeriod(String employeeId, LocalDate startDate, LocalDate endDate) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = """
                    SELECT COALESCE(SUM(total_hours), 0) as total
                    FROM employee_shifts
                    WHERE employee_id = ?
                    AND clock_in_at >= ?
                    AND clock_in_at < ?
                    AND status = ?
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, employeeId);
                stmt.setTimestamp(2, Timestamp.valueOf(startDate.atStartOfDay()));
                stmt.setTimestamp(3, Timestamp.valueOf(endDate.plusDays(1).atStartOfDay()));
                stmt.setString(4, EmployeeShift.STATUS_COMPLETED);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getBigDecimal("total");
                    }
                }
            }
        }

        return BigDecimal.ZERO;
    }

    /**
     * Get total hours worked today for an employee
     */
    public BigDecimal getTotalHoursToday(String employeeId) throws SQLException {
        LocalDate today = LocalDate.now();
        return getTotalHoursForPeriod(employeeId, today, today);
    }

    /**
     * Get total hours worked this week for an employee
     */
    public BigDecimal getTotalHoursThisWeek(String employeeId) throws SQLException {
        LocalDate today = LocalDate.now();
        LocalDate startOfWeek = today.minusDays(today.getDayOfWeek().getValue() - 1);
        return getTotalHoursForPeriod(employeeId, startOfWeek, today);
    }

    /**
     * Check if an employee is currently clocked in
     */
    private LocalDateTime parseTimestamp(String tsStr) {
        if (tsStr == null || tsStr.isEmpty())
            return null;
        try {
            // Try ISO_INSTANT first (e.g. 2024-05-20T10:00:00Z)
            return java.time.OffsetDateTime.parse(tsStr).toLocalDateTime();
        } catch (Exception e) {
            try {
                // Try ISO_LOCAL_DATE_TIME (e.g. 2024-05-20T10:00:00)
                return LocalDateTime.parse(tsStr);
            } catch (Exception e2) {
                logger.warn("Could not parse timestamp: {}", tsStr);
                return null;
            }
        }
    }

    /**
     * Check if an employee is currently clocked in
     */
    public boolean isClockedIn(String employeeId) {
        try {
            EmployeeShift activeShift = getActiveShift(employeeId);
            return activeShift != null;
        } catch (SQLException e) {
            logger.error("Error checking if employee is clocked in", e);
            return false;
        }
    }

    /**
     * Get all active shifts (employees currently clocked in)
     */
    public List<EmployeeShift> getAllActiveShifts() throws SQLException {
        List<EmployeeShift> shifts = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT * FROM employee_shifts WHERE status = ? ORDER BY clock_in_at DESC";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, EmployeeShift.STATUS_ACTIVE);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        shifts.add(mapResultSetToEmployeeShift(rs));
                    }
                }
            }
        }

        return shifts;
    }

    /**
     * Get unsynced shifts (for backend synchronization)
     */
    public List<EmployeeShift> getUnsyncedShifts() throws SQLException {
        List<EmployeeShift> shifts = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT * FROM employee_shifts WHERE synced = FALSE ORDER BY clock_in_at ASC";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        shifts.add(mapResultSetToEmployeeShift(rs));
                    }
                }
            }
        }

        return shifts;
    }

    /**
     * Mark a shift as synced
     */
    public void markAsSynced(String shiftId) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "UPDATE employee_shifts SET synced = TRUE WHERE id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, shiftId);
                stmt.executeUpdate();
                conn.commit();
            }
        }
    }

    /**
     * Get all activities performed by an employee during a specific shift.
     * Aggregates data from sales, cash operations, expenses, vendor payouts, and refunds.
     * 
     * @param shift The EmployeeShift to get activities for
     * @return List of EmployeeActivity objects
     */
    public List<EmployeeActivity> getActivitiesForShift(EmployeeShift shift) throws SQLException {
        if (shift == null) return Collections.emptyList();

        List<EmployeeActivity> activities = new ArrayList<>();
        String employeeId = shift.getEmployeeId();
        LocalDateTime start = shift.getClockInAt();
        LocalDateTime end = shift.getClockOutAt() != null ? shift.getClockOutAt() : LocalDateTime.now();

        // Formatter for ISO timestamps used in VARCHAR columns
        // Use SQL-safe date comparison or robust parsing
        // Since sqlite/h2 can compare ISO strings lexicographically
        String startStr = start.atOffset(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
        String endStr = end.atOffset(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
        try (Connection conn = dbManager.getConnection()) {
            // 1. Sales & Voids
            String salesSql = """
                    SELECT s.sale_id, s.total, s.timestamp, s.voided, s.void_reason, s.voided_at, s.voided_by,
                           s.is_split_payment, s.payment_method, sp.payment_method as split_method, sp.amount as split_amount
                    FROM sales s
                    LEFT JOIN sale_payments sp ON s.sale_id = sp.sale_id AND s.is_split_payment = TRUE
                    WHERE (s.cashier_id = ? OR s.pos_user_id = ?) 
                    AND s.timestamp BETWEEN ? AND ?
                    """;
            try (PreparedStatement stmt = conn.prepareStatement(salesSql)) {
                stmt.setString(1, employeeId);
                stmt.setString(2, employeeId);
                stmt.setString(3, startStr);
                stmt.setString(4, endStr);
                try (ResultSet rs = stmt.executeQuery()) {
                    java.util.Set<String> processedVoids = new java.util.HashSet<>();
                    while (rs.next()) {
                        String saleId = rs.getString("sale_id");
                        boolean isSplit = rs.getBoolean("is_split_payment");
                        
                        java.math.BigDecimal amount;
                        String method;
                        
                        if (isSplit && rs.getString("split_method") != null) {
                            amount = rs.getBigDecimal("split_amount");
                            method = rs.getString("split_method");
                        } else {
                            amount = rs.getBigDecimal("total");
                            method = rs.getString("payment_method");
                        }
                        
                        String tsStr = rs.getString("timestamp");
                        LocalDateTime ts = parseTimestamp(tsStr);
                        
                        String label = "Sale #" + saleId;
                        if (method != null && !method.equals("UNKNOWN") && !method.equals("SPLIT")) {
                            label += " (" + method + ")";
                        }
                        
                        activities.add(new EmployeeActivity(
                            EmployeeActivity.Type.SALE,
                            label,
                            amount, ts, saleId
                        ));

                        if (rs.getBoolean("voided") && !processedVoids.contains(saleId)) {
                            processedVoids.add(saleId);
                            Timestamp voidedAtTs = rs.getTimestamp("voided_at");
                            LocalDateTime voidedAt = voidedAtTs != null ? voidedAtTs.toLocalDateTime() : ts;
                            java.math.BigDecimal total = rs.getBigDecimal("total");
                            
                            activities.add(new EmployeeActivity(
                                EmployeeActivity.Type.VOID,
                                "Voided Sale #" + saleId + (rs.getString("void_reason") != null ? ": " + rs.getString("void_reason") : ""),
                                total.negate(), voidedAt, saleId
                            ));
                        }
                    }
                }
            }

            // 2. Cash Operations (Drops, Adds, No Sales)
            String cashOpsSql = """
                    SELECT type, amount, note, created_at, id
                    FROM cash_operations
                    WHERE performed_by = ?
                    AND created_at BETWEEN ? AND ?
                    """;
            try (PreparedStatement stmt = conn.prepareStatement(cashOpsSql)) {
                stmt.setString(1, employeeId);
                stmt.setString(2, startStr);
                stmt.setString(3, endStr);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String typeStr = rs.getString("type");
                        BigDecimal amount = rs.getBigDecimal("amount");
                        String note = rs.getString("note");
                        String tsStr = rs.getString("created_at");
                        LocalDateTime ts = parseTimestamp(tsStr);
                        String refId = rs.getString("id");

                        EmployeeActivity.Type type = switch (typeStr) {
                            case "DROP" -> EmployeeActivity.Type.CASH_DROP;
                            case "ADD" -> EmployeeActivity.Type.CASH_ADD;
                            case "NO_SALE" -> EmployeeActivity.Type.NO_SALE;
                            default -> EmployeeActivity.Type.CASH_ADD;
                        };

                        activities.add(new EmployeeActivity(
                            type,
                            (note != null && !note.isEmpty()) ? note : type.getLabel(),
                            amount, ts, refId
                        ));
                    }
                }
            }

            // 3. Expenses
            String expensesSql = """
                    SELECT expense_id, amount, description, timestamp
                    FROM expenses
                    WHERE created_by = ?
                    AND timestamp BETWEEN ? AND ?
                    """;
            try (PreparedStatement stmt = conn.prepareStatement(expensesSql)) {
                stmt.setString(1, employeeId);
                stmt.setString(2, startStr);
                stmt.setString(3, endStr);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String expId = rs.getString("expense_id");
                        BigDecimal amount = rs.getBigDecimal("amount");
                        String desc = rs.getString("description");
                        String tsStr = rs.getString("timestamp");
                        LocalDateTime ts = parseTimestamp(tsStr);

                        activities.add(new EmployeeActivity(
                            EmployeeActivity.Type.EXPENSE,
                            desc != null ? desc : "Expense",
                            amount.negate(), ts, expId
                        ));
                    }
                }
            }

            // 4. Vendor Payouts
            String payoutsSql = """
                    SELECT id, total_payout, vendor_name, paid_at
                    FROM vendor_payouts
                    WHERE paid_by = ?
                    AND paid_at BETWEEN ? AND ?
                    """;
            try (PreparedStatement stmt = conn.prepareStatement(payoutsSql)) {
                stmt.setString(1, employeeId);
                stmt.setString(2, startStr);
                stmt.setString(3, endStr);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString("id");
                        BigDecimal amount = rs.getBigDecimal("total_payout");
                        String vendor = rs.getString("vendor_name");
                        String tsStr = rs.getString("paid_at");
                        LocalDateTime ts = parseTimestamp(tsStr);

                        activities.add(new EmployeeActivity(
                            EmployeeActivity.Type.VENDOR_PAYOUT,
                            "Payout to " + vendor,
                            amount.negate(), ts, id
                        ));
                    }
                }
            }

            // 5. Refunds
            String refundsSql = """
                    SELECT refund_id, total_refund, reason, timestamp
                    FROM refunds
                    WHERE pos_user_id = ?
                    AND timestamp BETWEEN ? AND ?
                    """;
            try (PreparedStatement stmt = conn.prepareStatement(refundsSql)) {
                stmt.setString(1, employeeId);
                stmt.setString(2, startStr);
                stmt.setString(3, endStr);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String refId = rs.getString("refund_id");
                        BigDecimal amount = rs.getBigDecimal("total_refund");
                        String reason = rs.getString("reason");
                        String tsStr = rs.getString("timestamp");
                        LocalDateTime ts = parseTimestamp(tsStr);

                        activities.add(new EmployeeActivity(
                            EmployeeActivity.Type.REFUND,
                            "Refund: " + (reason != null ? reason : "Standard Refund"),
                            amount.negate(), ts, refId
                        ));
                    }
                }
            }
        }

        // Sort by timestamp
        activities.sort(Comparator.comparing(
                EmployeeActivity::getTimestamp,
                Comparator.nullsLast(Comparator.reverseOrder())
        ));

        return activities;
    }

    /**
     * Get distinct employee names who have recorded shifts
     */
    public List<String> getDistinctEmployeeNames() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT DISTINCT employee_name FROM employee_shifts ORDER BY employee_name ASC";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString("employee_name");
                        if (name != null) {
                            names.add(name);
                        }
                    }
                }
            }
        }
        return names;
    }

    /**
     * Map ResultSet to EmployeeShift model
     */
    private EmployeeShift mapResultSetToEmployeeShift(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String employeeId = rs.getString("employee_id");
        String employeeName = rs.getString("employee_name");
        String storeId = rs.getString("store_id");
        
        Timestamp clockInTs = rs.getTimestamp("clock_in_at");
        LocalDateTime clockInAt = clockInTs != null ? clockInTs.toLocalDateTime() : null;
        
        Timestamp clockOutTs = rs.getTimestamp("clock_out_at");
        LocalDateTime clockOutAt = clockOutTs != null ? clockOutTs.toLocalDateTime() : null;
        
        String status = rs.getString("status");
        BigDecimal totalHours = rs.getBigDecimal("total_hours");
        String notes = rs.getString("notes");
        boolean synced = rs.getBoolean("synced");
        
        Timestamp createdAtTs = rs.getTimestamp("created_at");
        LocalDateTime createdAt = createdAtTs != null ? createdAtTs.toLocalDateTime() : null;

        return new EmployeeShift(id, employeeId, employeeName, storeId, clockInAt, clockOutAt,
                status, totalHours, notes, synced, createdAt);
    }
}
