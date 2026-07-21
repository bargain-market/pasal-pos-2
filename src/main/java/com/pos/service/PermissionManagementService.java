package com.pos.service;

import com.pos.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Service for managing permissions using role-based access as the source of truth.
 */
public class PermissionManagementService {
    private static final Logger logger = LoggerFactory.getLogger(PermissionManagementService.class);
    private static PermissionManagementService instance;

    private final DatabaseManager dbManager;

    // User type constants
    public static final String USER_TYPE_POS_USER = "POS_USER";
    public static final String USER_TYPE_REGULAR_USER = "REGULAR_USER";

    private PermissionManagementService() {
        this.dbManager = DatabaseManager.getInstance();
    }

    public static synchronized PermissionManagementService getInstance() {
        if (instance == null) {
            instance = new PermissionManagementService();
        }
        return instance;
    }

    /**
     * Get all available permissions in the system.
     */
    public List<String> getAllPermissions() {
        return Arrays.asList(
                RoleBasedAccessService.PERMISSION_MANAGE_EMPLOYEES,
                RoleBasedAccessService.PERMISSION_MANAGE_SETTINGS,
                RoleBasedAccessService.PERMISSION_MANAGE_HARDWARE,
                RoleBasedAccessService.PERMISSION_VOID_TRANSACTION,
                RoleBasedAccessService.PERMISSION_PROCESS_REFUND,
                RoleBasedAccessService.PERMISSION_APPROVE_DISCOUNT,
                RoleBasedAccessService.PERMISSION_ADJUST_STOCK,
                RoleBasedAccessService.PERMISSION_MANAGE_PRODUCTS,
                RoleBasedAccessService.PERMISSION_CASH_OPERATIONS,
                RoleBasedAccessService.PERMISSION_NO_SALE,
                RoleBasedAccessService.PERMISSION_VIEW_REPORTS,
                RoleBasedAccessService.PERMISSION_MANAGE_INVENTORY,
                RoleBasedAccessService.PERMISSION_EXPENSES,
                RoleBasedAccessService.PERMISSION_VIEW_SHIFT_REPORT,
                RoleBasedAccessService.PERMISSION_END_DAY);
    }

    /**
     * Legacy custom per-user permissions are no longer used.
     */
    public Map<String, Boolean> getCustomPermissions(String userId, String userType) throws SQLException {
        return new HashMap<>();
    }

    /**
     * Get effective permissions for a user from their role only.
     */
    public Map<String, Boolean> getEffectivePermissions(String userId, String userType) throws SQLException {
        String role = getUserRole(userId, userType);
        if (role == null) {
            role = RoleBasedAccessService.ROLE_CASHIER;
        }
        return getRoleBasedPermissions(role);
    }

    /**
     * Get role-based permissions for a role (public for use in dialogs)
     */
    public Map<String, Boolean> getRoleBasedPermissions(String role) {
        Map<String, Boolean> permissions = getDefaultRolePermissions(role);

        String sql = "SELECT permission, granted FROM role_permissions WHERE role_name = ?";
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, normalizeRoleName(role));
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                permissions.put(rs.getString("permission"), rs.getBoolean("granted"));
            }
        } catch (SQLException e) {
            logger.warn("Failed to load role permission overrides for role {}: {}", role, e.getMessage());
        }

        return permissions;
    }

    public Map<String, Boolean> getDefaultRolePermissions(String role) {
        Map<String, Boolean> permissions = new HashMap<>();
        List<String> allPermissions = getAllPermissions();

        for (String perm : allPermissions) {
            permissions.put(perm, false);
        }

        if (RoleBasedAccessService.ROLE_ADMIN.equalsIgnoreCase(role)) {
            for (String perm : allPermissions) {
                permissions.put(perm, true);
            }
        } else if (RoleBasedAccessService.ROLE_MANAGER.equalsIgnoreCase(role)) {
            permissions.put(RoleBasedAccessService.PERMISSION_VOID_TRANSACTION, true);
            permissions.put(RoleBasedAccessService.PERMISSION_PROCESS_REFUND, true);
            permissions.put(RoleBasedAccessService.PERMISSION_APPROVE_DISCOUNT, true);
            permissions.put(RoleBasedAccessService.PERMISSION_ADJUST_STOCK, true);
            permissions.put(RoleBasedAccessService.PERMISSION_MANAGE_PRODUCTS, true);
            permissions.put(RoleBasedAccessService.PERMISSION_CASH_OPERATIONS, true);
            permissions.put(RoleBasedAccessService.PERMISSION_NO_SALE, true);
            permissions.put(RoleBasedAccessService.PERMISSION_VIEW_REPORTS, true);
            permissions.put(RoleBasedAccessService.PERMISSION_MANAGE_INVENTORY, true);
            permissions.put(RoleBasedAccessService.PERMISSION_EXPENSES, true);
            permissions.put(RoleBasedAccessService.PERMISSION_VIEW_SHIFT_REPORT, true);
            permissions.put(RoleBasedAccessService.PERMISSION_END_DAY, true);
        }

        return permissions;
    }

    /**
     * Get user role from database
     */
    private String getUserRole(String userId, String userType) throws SQLException {
        String sql;

        if (USER_TYPE_POS_USER.equals(userType)) {
            sql = "SELECT role FROM pos_users WHERE id = ?";
        } else {
            sql = "SELECT role FROM users WHERE id = ?";
        }

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                String role = rs.getString("role");
                String effectiveRole = role != null && !role.isEmpty() ? role : RoleBasedAccessService.ROLE_CASHIER;
                logger.debug("Retrieved role for user {} ({}): {}", userId, userType, effectiveRole);
                return effectiveRole;
            } else {
                logger.debug("User {} ({}) not found in database, defaulting to Cashier role", userId, userType);
            }
        }

        logger.debug("No role found for user {} ({}), defaulting to Cashier", userId, userType);
        return RoleBasedAccessService.ROLE_CASHIER;
    }

    public void grantPermission(String userId, String userType, String permission, String grantedBy)
            throws SQLException {
        logger.info("Ignoring legacy per-user grant for {} on user {} ({}) by {}; permissions are role-based",
                permission, userId, userType, grantedBy);
    }

    public void revokePermission(String userId, String userType, String permission, String revokedBy)
            throws SQLException {
        logger.info("Ignoring legacy per-user revoke for {} on user {} ({}) by {}; permissions are role-based",
                permission, userId, userType, revokedBy);
    }

    /**
     * Update user role
     */
    public void updateUserRole(String userId, String userType, String newRole, String updatedBy) throws SQLException {
        String sql;

        if (USER_TYPE_POS_USER.equals(userType)) {
            sql = "UPDATE pos_users SET role = ? WHERE id = ?";
        } else {
            sql = "UPDATE users SET role = ? WHERE id = ?";
        }

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, newRole);
            stmt.setString(2, userId);
            int rowsUpdated = stmt.executeUpdate();
            clearLegacyUserPermissions(conn, userId, userType);
            conn.commit();

            if (rowsUpdated > 0) {
                logger.info("Updated role to {} for user {} ({}) by {}", newRole, userId, userType, updatedBy);
            }
        }
    }

    /**
     * Reset user to role-based permissions only by clearing legacy per-user overrides.
     */
    public void resetToRolePermissions(String userId, String userType, String resetBy) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            int rowsDeleted = clearLegacyUserPermissions(conn, userId, userType);
            conn.commit();

            logger.info("Reset permissions to role defaults for user {} ({}) by {} (removed {} custom permissions)",
                    userId, userType, resetBy, rowsDeleted);
        }
    }

    public int clearLegacyUserPermissions(String userId, String userType) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            int rowsDeleted = clearLegacyUserPermissions(conn, userId, userType);
            conn.commit();
            return rowsDeleted;
        }
    }

    private int clearLegacyUserPermissions(Connection conn, String userId, String userType) throws SQLException {
        String sql = "DELETE FROM user_permissions WHERE user_id = ? AND user_type = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setString(2, userType);
            return stmt.executeUpdate();
        }
    }

    /**
     * Get count of custom permissions for a user
     */
    public int getCustomPermissionCount(String userId, String userType) throws SQLException {
        return 0;
    }

    public Map<String, Boolean> getRolePermissionOverrides(String role) throws SQLException {
        Map<String, Boolean> overrides = new HashMap<>();
        String sql = "SELECT permission, granted FROM role_permissions WHERE role_name = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, normalizeRoleName(role));
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                overrides.put(rs.getString("permission"), rs.getBoolean("granted"));
            }
        }

        return overrides;
    }

    public void updateRolePermissions(String role, Map<String, Boolean> permissions, String changedBy) throws SQLException {
        createRoleIfMissing(role, changedBy);
        Map<String, Boolean> defaultPermissions = getDefaultRolePermissions(role);

        try (Connection conn = dbManager.getConnection()) {
            clearRolePermissionOverrides(conn, role);

            String sql = "INSERT INTO role_permissions " +
                    "(id, role_name, permission, granted, created_at, created_by) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (String permission : getAllPermissions()) {
                    boolean selected = permissions.getOrDefault(permission, false);
                    boolean defaultValue = defaultPermissions.getOrDefault(permission, false);
                    if (selected == defaultValue) {
                        continue;
                    }

                    stmt.setString(1, UUID.randomUUID().toString());
                    stmt.setString(2, normalizeRoleName(role));
                    stmt.setString(3, permission);
                    stmt.setBoolean(4, selected);
                    stmt.setTimestamp(5, Timestamp.from(Instant.now()));
                    stmt.setString(6, changedBy);
                    stmt.addBatch();
                }

                stmt.executeBatch();
            }

            conn.commit();
            logger.info("Updated role permissions for role {} by {}", role, changedBy);
        }
    }

    public void resetRolePermissions(String role, String resetBy) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            int rowsDeleted = clearRolePermissionOverrides(conn, role);
            conn.commit();
            logger.info("Reset role permissions for role {} by {} (removed {} override rows)", role, resetBy, rowsDeleted);
        }
    }

    public String createRole(String roleName, String createdBy) throws SQLException {
        String normalizedRole = normalizeRoleName(roleName);
        if (normalizedRole.isBlank()) {
            throw new SQLException("Role name cannot be empty");
        }

        String sql = "MERGE INTO roles (id, role_name, created_at, created_by) KEY (role_name) VALUES (?, ?, ?, ?)";
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, UUID.randomUUID().toString());
            stmt.setString(2, normalizedRole);
            stmt.setTimestamp(3, Timestamp.from(Instant.now()));
            stmt.setString(4, createdBy);
            stmt.executeUpdate();
            conn.commit();
        }

        logger.info("Created or ensured role {} by {}", normalizedRole, createdBy);
        return normalizedRole;
    }

    public List<RolePermissionInfo> getAllRolesWithPermissions() throws SQLException {
        List<RolePermissionInfo> roles = new ArrayList<>();
        for (String role : getManageableRoles()) {
            RolePermissionInfo info = new RolePermissionInfo();
            info.roleName = role;
            Map<String, Boolean> effective = getRoleBasedPermissions(role);
            Map<String, Boolean> overrides = getRolePermissionOverrides(role);
            info.grantedPermissionCount = (int) effective.values().stream().filter(Boolean::booleanValue).count();
            info.overrideCount = overrides.size();
            info.memberCount = getRoleMemberCount(role);
            roles.add(info);
        }
        return roles;
    }

    public int getRoleMemberCount(String role) throws SQLException {
        int count = 0;
        String normalizedRole = normalizeRoleName(role);
        count += countUsersForRole("SELECT COUNT(*) FROM pos_users WHERE role = ?", normalizedRole);
        count += countUsersForRole("SELECT COUNT(*) FROM users WHERE role = ?", normalizedRole);
        return count;
    }

    public List<String> getManageableRoles() {
        Set<String> roles = new LinkedHashSet<>();
        roles.add(RoleBasedAccessService.ROLE_ADMIN);
        roles.add(RoleBasedAccessService.ROLE_MANAGER);
        roles.add(RoleBasedAccessService.ROLE_CASHIER);

        try (Connection conn = dbManager.getConnection()) {
            collectRoles(conn, roles, "SELECT role_name FROM roles");
            collectRoles(conn, roles, "SELECT DISTINCT role_name FROM role_permissions");
            collectRoles(conn, roles, "SELECT DISTINCT role FROM pos_users WHERE role IS NOT NULL AND TRIM(role) <> ''");
            collectRoles(conn, roles, "SELECT DISTINCT role FROM users WHERE role IS NOT NULL AND TRIM(role) <> ''");
        } catch (SQLException e) {
            logger.warn("Failed to load dynamic role list: {}", e.getMessage());
        }

        return new ArrayList<>(roles);
    }

    private int countUsersForRole(String sql, String role) throws SQLException {
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, role);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    private int clearRolePermissionOverrides(Connection conn, String role) throws SQLException {
        String sql = "DELETE FROM role_permissions WHERE role_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, normalizeRoleName(role));
            return stmt.executeUpdate();
        }
    }

    private void collectRoles(Connection conn, Set<String> roles, String sql) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String role = normalizeRoleName(rs.getString(1));
                if (!role.isBlank()) {
                    roles.add(role);
                }
            }
        }
    }

    private void createRoleIfMissing(String role, String changedBy) throws SQLException {
        String normalizedRole = normalizeRoleName(role);
        String sql = "SELECT COUNT(*) FROM roles WHERE role_name = ?";
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, normalizedRole);
            ResultSet rs = stmt.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }

        createRole(normalizedRole, changedBy);
    }

    private String normalizeRoleName(String role) {
        if (role == null || role.isBlank()) {
            return RoleBasedAccessService.ROLE_CASHIER;
        }

        String trimmedRole = role.trim();
        if (RoleBasedAccessService.ROLE_ADMIN.equalsIgnoreCase(role)) {
            return RoleBasedAccessService.ROLE_ADMIN;
        }
        if (RoleBasedAccessService.ROLE_MANAGER.equalsIgnoreCase(role)) {
            return RoleBasedAccessService.ROLE_MANAGER;
        }
        if (RoleBasedAccessService.ROLE_CASHIER.equalsIgnoreCase(role)) {
            return RoleBasedAccessService.ROLE_CASHIER;
        }

        String[] words = trimmedRole.replaceAll("\\s+", " ").split(" ");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(word.substring(0, 1).toUpperCase(Locale.ROOT));
            if (word.length() > 1) {
                builder.append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return builder.toString();
    }

    /**
     * Check if user has a specific effective permission
     */
    public boolean hasEffectivePermission(String userId, String userType, String permission) throws SQLException {
        Map<String, Boolean> effectivePermissions = getEffectivePermissions(userId, userType);
        return effectivePermissions.getOrDefault(permission, false);
    }

    /**
     * Get all users with their roles.
     */
    public List<UserPermissionInfo> getAllUsersWithPermissions() throws SQLException {
        List<UserPermissionInfo> users = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {
            String posUsersSql = "SELECT u.id, u.username, u.full_name, u.role, u.is_active " +
                    "FROM pos_users u";

            try (PreparedStatement stmt = conn.prepareStatement(posUsersSql)) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    UserPermissionInfo info = new UserPermissionInfo();
                    info.userId = rs.getString("id");
                    info.username = rs.getString("username");
                    info.fullName = rs.getString("full_name");
                    info.role = rs.getString("role");
                    info.userType = USER_TYPE_POS_USER;
                    info.isActive = rs.getBoolean("is_active");
                    info.customPermissionCount = 0;
                    users.add(info);
                }
            }

            String usersSql = "SELECT u.id, u.email, u.full_name, u.role " +
                    "FROM users u";

            try (PreparedStatement stmt = conn.prepareStatement(usersSql)) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    UserPermissionInfo info = new UserPermissionInfo();
                    info.userId = rs.getString("id");
                    info.username = rs.getString("email");
                    info.fullName = rs.getString("full_name");
                    info.role = rs.getString("role");
                    info.userType = USER_TYPE_REGULAR_USER;
                    info.isActive = true;
                    info.customPermissionCount = 0;
                    users.add(info);
                }
            }
        }

        return users;
    }

    /**
     * Data class for user permission information
     */
    public static class UserPermissionInfo {
        public String userId;
        public String username;
        public String fullName;
        public String role;
        public String userType;
        public boolean isActive;
        public int customPermissionCount;
    }

    public static class RolePermissionInfo {
        public String roleName;
        public int grantedPermissionCount;
        public int overrideCount;
        public int memberCount;
    }
}
