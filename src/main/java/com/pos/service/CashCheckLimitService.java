package com.pos.service;

import com.pos.database.DatabaseManager;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Service for enforcing role-based cash/check operation limits.
 *
 * This service:
 * 1. Checks if user's role has cash/check limit enabled
 * 2. Tracks daily usage per user
 * 3. Validates PIN when limit is exceeded
 * 4. Enforces limits for manual cash operations (ADD, DROP, PAYOUT, ADJUSTMENT)
 *
 * Note: Regular sales do NOT count toward the cash/check limit.
 */
public class CashCheckLimitService {
    private static final Logger logger = LoggerFactory.getLogger(CashCheckLimitService.class);
    private static CashCheckLimitService instance;

    private final DatabaseManager dbManager;
    private final UserAuthService userAuthService;

    // Operation types that count toward the daily limit
    private static final String[] LIMITED_OPERATION_TYPES = {"ADD", "DROP", "PAYOUT", "ADJUSTMENT"};

    private CashCheckLimitService() {
        this.dbManager = DatabaseManager.getInstance();
        this.userAuthService = UserAuthService.getInstance();
    }

    public static synchronized CashCheckLimitService getInstance() {
        if (instance == null) {
            instance = new CashCheckLimitService();
        }
        return instance;
    }

    /**
     * Check if a cash operation type counts toward the daily limit
     */
    public boolean isLimitedOperation(String operationType) {
        if (operationType == null) return false;
        String upperType = operationType.toUpperCase();
        for (String limitedType : LIMITED_OPERATION_TYPES) {
            if (limitedType.equals(upperType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get cash/check limit status for current user
     *
     * @return LimitStatus with current usage and limit info
     */
    public LimitStatus getLimitStatus() {
        String userId = getCurrentUserId();
        String role = getCurrentUserRole();

        if (userId == null || role == null) {
            return new LimitStatus(false, 0, 0, false);
        }

        // Get role limit settings
        RoleLimitSettings roleSettings = getRoleLimitSettings(role);
        if (roleSettings == null || !roleSettings.limitEnabled) {
            return new LimitStatus(false, 0, 0, false);
        }

        // Get today's usage
        int todayUsage = getTodayUsage(userId);
        boolean limitExceeded = todayUsage >= roleSettings.dailyLimit;

        return new LimitStatus(
            true,
            roleSettings.dailyLimit,
            todayUsage,
            limitExceeded
        );
    }

    /**
     * Record a cash/check operation and check if limit is exceeded
     *
     * @param operationType The type of cash operation
     * @param overridePin PIN provided by user to override limit (if needed)
     * @return ValidationResult indicating if operation is allowed
     */
    public ValidationResult validateAndRecordOperation(String operationType, String overridePin) {
        // Non-limited operations are always allowed without counting
        if (!isLimitedOperation(operationType)) {
            return new ValidationResult(true, null, false);
        }

        LimitStatus status = getLimitStatus();

        // If limit is not enabled for this role, allow and don't count
        if (!status.limitEnabled) {
            return new ValidationResult(true, null, false);
        }

        // If limit not exceeded, allow and record usage
        if (!status.limitExceeded) {
            recordUsage();
            int remaining = status.dailyLimit - (status.currentUsage + 1);
            return new ValidationResult(true, null, true, remaining);
        }

        // Limit exceeded - require PIN
        if (overridePin == null || overridePin.isEmpty()) {
            return new ValidationResult(false,
                "Daily cash/check limit exceeded (" + status.dailyLimit + "/day). PIN required to proceed.",
                false);
        }

        // Validate PIN
        if (!validatePin(overridePin)) {
            return new ValidationResult(false, "Invalid PIN. Daily cash/check limit exceeded.", false);
        }

        // PIN valid - allow operation and record usage
        recordUsage();
        return new ValidationResult(true, null, true, 0);
    }

    /**
     * Validate the admin PIN for manual cash/check operations
     */
    public boolean validatePin(String pin) {
        String storedHash = getStoredPinHash();
        if (storedHash == null || storedHash.isEmpty()) {
            logger.warn("No PIN hash configured in global settings");
            return false;
        }

        try {
            return BCrypt.checkpw(pin, storedHash);
        } catch (Exception e) {
            logger.error("Error validating PIN: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if a PIN is configured
     */
    public boolean isPinConfigured() {
        String storedHash = getStoredPinHash();
        return storedHash != null && !storedHash.isEmpty();
    }

    /**
     * Get the stored PIN hash from global settings
     */
    private String getStoredPinHash() {
        String sql = "SELECT manual_cash_check_pin_hash FROM global_settings WHERE id = 'global'";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getString("manual_cash_check_pin_hash");
            }
        } catch (SQLException e) {
            logger.error("Error getting stored PIN hash: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Get role limit settings from database
     */
    private RoleLimitSettings getRoleLimitSettings(String roleName) {
        // First try to find by role name (case-insensitive)
        String sql = "SELECT limit_enabled, daily_limit FROM role_cash_check_limits WHERE UPPER(role_name) = UPPER(?)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, roleName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new RoleLimitSettings(
                        rs.getBoolean("limit_enabled"),
                        rs.getInt("daily_limit")
                    );
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting role limit settings: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Get today's usage for a user
     */
    private int getTodayUsage(String userId) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String sql = "SELECT operation_count FROM cash_check_daily_usage WHERE user_id = ? AND usage_date = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setString(2, today);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("operation_count");
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting today's usage: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * Record usage for current user today
     */
    private void recordUsage() {
        String userId = getCurrentUserId();
        if (userId == null) return;

        String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String id = UUID.randomUUID().toString();

        String upsertSql = """
            MERGE INTO cash_check_daily_usage (id, user_id, usage_date, operation_count, created_at, updated_at)
            KEY (user_id, usage_date)
            VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;

        String updateSql = """
            UPDATE cash_check_daily_usage
            SET operation_count = operation_count + 1, updated_at = CURRENT_TIMESTAMP
            WHERE user_id = ? AND usage_date = ?
            """;

        try (Connection conn = dbManager.getConnection()) {
            // First try to update existing record
            try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                updateStmt.setString(1, userId);
                updateStmt.setString(2, today);
                int updated = updateStmt.executeUpdate();

                // If no record updated, insert new one
                if (updated == 0) {
                    try (PreparedStatement insertStmt = conn.prepareStatement(upsertSql)) {
                        insertStmt.setString(1, id);
                        insertStmt.setString(2, userId);
                        insertStmt.setString(3, today);
                        insertStmt.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error recording usage: {}", e.getMessage());
        }
    }

    /**
     * Get current user ID from auth service
     */
    private String getCurrentUserId() {
        if (userAuthService.isPosUser()) {
            return userAuthService.getCurrentPosUserId();
        } else {
            return userAuthService.getCurrentUserId();
        }
    }

    /**
     * Get current user role from auth service
     */
    private String getCurrentUserRole() {
        return userAuthService.getCurrentUserRole();
    }

    /**
     * Reset daily usage for current user (for testing or admin override)
     */
    public boolean resetDailyUsage() {
        String userId = getCurrentUserId();
        if (userId == null) return false;

        String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String sql = "DELETE FROM cash_check_daily_usage WHERE user_id = ? AND usage_date = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setString(2, today);
            stmt.executeUpdate();
            logger.info("Reset daily cash/check usage for user: {}", userId);
            return true;
        } catch (SQLException e) {
            logger.error("Error resetting daily usage: {}", e.getMessage());
            return false;
        }
    }

    // Data classes

    public static class LimitStatus {
        public final boolean limitEnabled;
        public final int dailyLimit;
        public final int currentUsage;
        public final boolean limitExceeded;
        public final int remaining;

        public LimitStatus(boolean limitEnabled, int dailyLimit, int currentUsage, boolean limitExceeded) {
            this.limitEnabled = limitEnabled;
            this.dailyLimit = dailyLimit;
            this.currentUsage = currentUsage;
            this.limitExceeded = limitExceeded;
            this.remaining = Math.max(0, dailyLimit - currentUsage);
        }
    }

    public static class ValidationResult {
        public final boolean allowed;
        public final String errorMessage;
        public final boolean counted;
        public final int remaining;

        public ValidationResult(boolean allowed, String errorMessage, boolean counted) {
            this(allowed, errorMessage, counted, 0);
        }

        public ValidationResult(boolean allowed, String errorMessage, boolean counted, int remaining) {
            this.allowed = allowed;
            this.errorMessage = errorMessage;
            this.counted = counted;
            this.remaining = remaining;
        }
    }

    private static class RoleLimitSettings {
        final boolean limitEnabled;
        final int dailyLimit;

        RoleLimitSettings(boolean limitEnabled, int dailyLimit) {
            this.limitEnabled = limitEnabled;
            this.dailyLimit = dailyLimit;
        }
    }
}
