package com.pos.service;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Store admin validation utilities
 * Ensures each store has exactly one admin user
 */
public class StoreAdminValidationService {
    private static StoreAdminValidationService instance;

    private final DatabaseManager dbManager;
    private final ConfigManager config;

    private StoreAdminValidationService() {
        this.dbManager = DatabaseManager.getInstance();
        this.config = ConfigManager.getInstance();
    }

    public static synchronized StoreAdminValidationService getInstance() {
        if (instance == null) {
            instance = new StoreAdminValidationService();
        }
        return instance;
    }

    /**
     * Check if a role is Admin
     * @param role The role to check
     * @return true if the role is Admin
     */
    public boolean isAdminRole(String role) {
        return role != null && "Admin".equalsIgnoreCase(role);
    }

    /**
     * Count the number of active admin users for the current store
     * @param excludeUserId Optional user ID to exclude from the count
     * @return The number of active admin users
     */
    public int countStoreAdmins(String excludeUserId) throws SQLException {
        String storeId = config.getProperty("store.id");
        if (storeId == null || storeId.isEmpty()) {
            throw new IllegalStateException("Device not registered to a store");
        }

        String sql = "SELECT COUNT(*) FROM pos_users " +
                "WHERE store_id = ? AND role = ? AND is_active = TRUE";

        if (excludeUserId != null && !excludeUserId.isEmpty()) {
            sql += " AND id != ?";
        }

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, storeId);
            stmt.setString(2, "Admin");
            if (excludeUserId != null && !excludeUserId.isEmpty()) {
                stmt.setString(3, excludeUserId);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        }

        return 0;
    }

    /**
     * Validate that the store has exactly one admin
     * @param excludeUserId Optional user ID to exclude from validation (e.g., when updating)
     * @throws IllegalStateException if the store doesn't have exactly one admin
     */
    public void validateStoreHasOneAdmin(String excludeUserId) throws SQLException {
        int adminCount = countStoreAdmins(excludeUserId);

        if (adminCount == 0) {
            throw new IllegalStateException(
                "Store must have exactly one admin user. Currently has 0 admin(s)."
            );
        }

        if (adminCount > 1) {
            throw new IllegalStateException(
                "Store must have exactly one admin user. Currently has " + adminCount + " admin(s)."
            );
        }
    }

    /**
     * Validate that assigning a user as admin won't violate the one-admin-per-store rule
     * @param userId The user ID being assigned as admin
     * @throws IllegalStateException if assigning this admin would result in multiple admins
     */
    public void validateAdminAssignment(String userId) throws SQLException {
        int existingAdminCount = countStoreAdmins(userId);

        if (existingAdminCount >= 1) {
            throw new IllegalStateException(
                "Store already has an admin user. Each store must have exactly one admin."
            );
        }
    }

    /**
     * Validate that removing an admin from a store won't leave the store without an admin
     * @param userId The user ID being removed
     * @throws IllegalStateException if removing this admin would leave the store without an admin
     */
    public void validateAdminRemoval(String userId) throws SQLException {
        int adminCount = countStoreAdmins(null);

        if (adminCount <= 1) {
            throw new IllegalStateException(
                "Cannot remove the admin user. Each store must have exactly one admin."
            );
        }
    }

    /**
     * Validate that deactivating an admin won't leave the store without an admin
     * @param userId The user ID being deactivated
     * @throws IllegalStateException if deactivating this admin would leave the store without an admin
     */
    public void validateAdminDeactivation(String userId) throws SQLException {
        // Count active admins excluding this user
        int activeAdminCount = countStoreAdmins(userId);

        if (activeAdminCount == 0) {
            throw new IllegalStateException(
                "Cannot deactivate the admin user. Each store must have exactly one active admin."
            );
        }
    }
}

