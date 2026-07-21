package com.pos.service;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.model.Employee;
import com.pos.util.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing employees (POS Users)
 */
public class EmployeeService {
    private static final Logger logger = LoggerFactory.getLogger(EmployeeService.class);
    private static EmployeeService instance;

    private final DatabaseManager dbManager;
    private final ConfigManager config;

    private EmployeeService() {
        this.dbManager = DatabaseManager.getInstance();
        this.config = ConfigManager.getInstance();
    }

    public static synchronized EmployeeService getInstance() {
        if (instance == null) {
            instance = new EmployeeService();
        }
        return instance;
    }

    /**
     * Get all employees from local database
     */
    public List<Employee> getAllEmployees() throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT id, username, full_name, store_id, is_active, role, last_login_at, created_at " +
                    "FROM pos_users ORDER BY full_name";
            List<Employee> employees = new ArrayList<>();

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    employees.add(mapResultSetToEmployee(rs));
                }
            }

            return employees;
        }
    }

    /**
     * Get active employees only
     */
    public List<Employee> getActiveEmployees() throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT id, username, full_name, store_id, is_active, role, last_login_at, created_at " +
                    "FROM pos_users WHERE is_active = TRUE ORDER BY full_name";
            List<Employee> employees = new ArrayList<>();

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    employees.add(mapResultSetToEmployee(rs));
                }
            }

            return employees;
        }
    }

    /**
     * Search employees by name or username
     */
    public List<Employee> searchEmployees(String searchTerm) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = """
                    SELECT id, username, full_name, store_id, is_active, role, last_login_at, created_at
                    FROM pos_users
                    WHERE LOWER(full_name) LIKE LOWER(?)
                       OR LOWER(username) LIKE LOWER(?)
                    ORDER BY full_name
                    LIMIT 100
                    """;

            List<Employee> employees = new ArrayList<>();
            String searchPattern = "%" + searchTerm + "%";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, searchPattern);
                stmt.setString(2, searchPattern);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    employees.add(mapResultSetToEmployee(rs));
                }
            }

            return employees;
        }
    }

    /**
     * Get employee by ID
     */
    public Employee getEmployeeById(String employeeId) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT id, username, full_name, store_id, is_active, role, last_login_at, created_at " +
                    "FROM pos_users WHERE id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, employeeId);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return mapResultSetToEmployee(rs);
                }
            }

            return null;
        }
    }

    /**
     * Get employee by username
     */
    public Employee getEmployeeByUsername(String username) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT id, username, full_name, store_id, is_active, role, last_login_at, created_at " +
                    "FROM pos_users WHERE username = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, username);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return mapResultSetToEmployee(rs);
                }
            }

            return null;
        }
    }

    /**
     * Create new employee locally (will sync to backend)
     */
    public Employee createEmployee(String username, String fullName, String role) throws SQLException {
        return createEmployee(username, fullName, role, null);
    }

    /**
     * Create new employee locally with an initial PIN
     */
    public Employee createEmployee(String username, String fullName, String role, String pin) throws SQLException {
        String storeId = config.getProperty("store.id");
        if (storeId == null || storeId.isEmpty()) {
            throw new IllegalStateException("Device not registered to a store");
        }

        // Normalize role
        String normalizedRole = (role != null && !role.isEmpty()) ? role : "Cashier";

        // Validate admin assignment if creating an admin
        StoreAdminValidationService validationService = StoreAdminValidationService.getInstance();
        if (validationService.isAdminRole(normalizedRole)) {
            // Check if store already has an admin (no need to exclude since this is a new user)
            int existingAdminCount = validationService.countStoreAdmins(null);
            if (existingAdminCount >= 1) {
                throw new IllegalStateException(
                    "Store already has an admin user. Each store must have exactly one admin."
                );
            }
        }

        // Hash the PIN if provided
        String pinHash = (pin != null && !pin.isEmpty()) ? PasswordHasher.hashPassword(pin) : "";

        // Generate ID
        String id = UUID.randomUUID().toString();

        try (Connection conn = dbManager.getConnection()) {
            String sql = """
                    INSERT INTO pos_users (id, username, pin_hash, full_name, store_id, is_active, role, synced_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, username);
                stmt.setString(3, pinHash);
                stmt.setString(4, fullName);
                stmt.setString(5, storeId);
                stmt.setBoolean(6, true);
                stmt.setString(7, normalizedRole);
                stmt.setString(8, Instant.now().toString());
                stmt.executeUpdate();
                conn.commit();
            }
        }

        logger.info("Created employee: {} ({})", fullName, username);
        return getEmployeeById(id);
    }

    /**
     * Update employee details
     */
    public void updateEmployee(String employeeId, String fullName, String role, Boolean isActive) throws SQLException {
        StoreAdminValidationService validationService = StoreAdminValidationService.getInstance();
        
        // Get current employee data to check role changes
        Employee currentEmployee = getEmployeeById(employeeId);
        if (currentEmployee == null) {
            throw new SQLException("Employee not found: " + employeeId);
        }

        String currentRole = currentEmployee.getRole();
        boolean currentIsActive = currentEmployee.isActive();
        String newRole = role != null ? role : currentRole;
        boolean newIsActive = isActive != null ? isActive : currentIsActive;

        // Validate role changes
        boolean wasAdmin = validationService.isAdminRole(currentRole);
        boolean willBeAdmin = validationService.isAdminRole(newRole);

        // If changing to admin, validate that store doesn't already have an admin
        if (!wasAdmin && willBeAdmin) {
            validationService.validateAdminAssignment(employeeId);
        }

        // If changing from admin to non-admin, validate that store will still have an admin
        if (wasAdmin && !willBeAdmin) {
            validationService.validateStoreHasOneAdmin(employeeId);
        }

        // If deactivating an admin, validate that store will still have an active admin
        if (wasAdmin && currentIsActive && !newIsActive) {
            validationService.validateAdminDeactivation(employeeId);
        }

        try (Connection conn = dbManager.getConnection()) {
            // Build update query dynamically based on what's provided
            StringBuilder sql = new StringBuilder("UPDATE pos_users SET ");
            List<Object> params = new ArrayList<>();
            boolean hasUpdates = false;

            if (fullName != null) {
                sql.append("full_name = ?");
                params.add(fullName);
                hasUpdates = true;
            }

            if (role != null) {
                if (hasUpdates)
                    sql.append(", ");
                sql.append("role = ?");
                params.add(role);
                hasUpdates = true;
            }

            if (isActive != null) {
                if (hasUpdates)
                    sql.append(", ");
                sql.append("is_active = ?");
                params.add(isActive);
                hasUpdates = true;
            }

            if (!hasUpdates) {
                logger.warn("No updates provided for employee: {}", employeeId);
                return;
            }

            sql.append(" WHERE id = ?");
            params.add(employeeId);

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    Object param = params.get(i);
                    if (param instanceof String) {
                        stmt.setString(i + 1, (String) param);
                    } else if (param instanceof Boolean) {
                        stmt.setBoolean(i + 1, (Boolean) param);
                    }
                }
                int updated = stmt.executeUpdate();
                conn.commit();

                if (updated > 0) {
                    logger.info("Updated employee: {}", employeeId);
                } else {
                    logger.warn("Employee not found for update: {}", employeeId);
                }
            }
        }
    }

    /**
     * Reset PIN for an employee
     * Note: This only updates local database. Backend PIN reset should be done via
     * API.
     */
    public void resetPin(String employeeId, String newPin) throws SQLException {
        String pinHash = PasswordHasher.hashPassword(newPin);

        try (Connection conn = dbManager.getConnection()) {
            String sql = "UPDATE pos_users SET pin_hash = ? WHERE id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, pinHash);
                stmt.setString(2, employeeId);
                int updated = stmt.executeUpdate();
                conn.commit();

                if (updated > 0) {
                    logger.info("PIN reset for employee: {}", employeeId);
                } else {
                    logger.warn("Employee not found for PIN reset: {}", employeeId);
                    throw new SQLException("Employee not found");
                }
            }
        }
    }

    /**
     * Deactivate employee
     */
    public void deactivateEmployee(String employeeId) throws SQLException {
        // Validation is handled in updateEmployee method
        updateEmployee(employeeId, null, null, false);
    }

    /**
     * Activate employee
     */
    public void activateEmployee(String employeeId) throws SQLException {
        updateEmployee(employeeId, null, null, true);
    }

    /**
     * Delete employee (soft delete by deactivating)
     */
    public void deleteEmployee(String employeeId) throws SQLException {
        deactivateEmployee(employeeId);
    }

    /**
     * Sync employees from backend
     */
    public void syncFromBackend() throws Exception {
        UserSyncService.getInstance().syncPosUsers();
    }

    /**
     * Map ResultSet to Employee
     */
    private Employee mapResultSetToEmployee(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String username = rs.getString("username");
        String fullName = rs.getString("full_name");
        String storeId = rs.getString("store_id");
        boolean isActive = rs.getBoolean("is_active");
        String role = rs.getString("role");
        String lastLoginAt = rs.getString("last_login_at");
        String createdAt = rs.getString("created_at");

        // Handle null role
        if (role == null || role.isEmpty()) {
            role = "Cashier";
        }

        return new Employee(id, username, fullName, storeId, isActive, role, lastLoginAt, createdAt);
    }

    /**
     * Check if username already exists
     */
    public boolean usernameExists(String username) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT COUNT(*) FROM pos_users WHERE username = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, username);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }

            return false;
        }
    }

    /**
     * Get employee count
     */
    public int getEmployeeCount() throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT COUNT(*) FROM pos_users";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }

            return 0;
        }
    }

    /**
     * Get active employee count
     */
    public int getActiveEmployeeCount() throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT COUNT(*) FROM pos_users WHERE is_active = TRUE";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }

            return 0;
        }
    }
}
