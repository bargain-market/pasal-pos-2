package com.pos.sync.inbound;

import com.pos.api.ApiClient;
import com.pos.api.dto.PosUserSyncData;
import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.service.PermissionManagementService;
import com.pos.service.RoleBasedAccessService;
import com.pos.service.UserAuthService;
import com.pos.sync.SyncDirection;
import com.pos.sync.SyncManager;
import com.pos.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Inbound sync handler for POS users.
 * Pulls user metadata from backend and stores in local database.
 * 
 * <h2>Security Note</h2>
 * PIN hashes are NOT synced from backend. They are stored locally only when:
 * 1. User successfully logs in online (PIN verified by backend)
 * 2. PIN hash is then cached locally for offline authentication
 * 
 * This ensures:
 * - Backend is source of truth for user authentication
 * - Offline login only works after at least one successful online login
 * - PIN hashes never leave the backend (security best practice)
 * 
 * <h2>Sync Flow</h2>
 * <pre>
 * Backend API ──────────────────────────────────────────────────────────────
 *     │
 *     │ GET /pos/users
 *     │
 *     ▼
 * UserInboundSync ──────────────────────────────────────────────────────────
 *     │
 *     │ Parse response
 *     │ Preserve existing PIN hashes (from previous logins)
 *     │ Update metadata only
 *     │
 *     ▼
 * Local H2 Database ────────────────────────────────────────────────────────
 *     • pos_users table (id, username, full_name, store_id, role, is_active)
 *     • PIN hash preserved from previous online logins
 *     • Role synced from backend (Cashier, Manager, or Admin)
 * </pre>
 */
public class UserInboundSync implements SyncManager.InboundSyncHandler {
    private static final Logger logger = LoggerFactory.getLogger(UserInboundSync.class);
    
    private static UserInboundSync instance;
    
    private final ApiClient apiClient;
    private final DatabaseManager dbManager;
    private final ConfigManager config;
    
    private UserInboundSync() {
        this.apiClient = ApiClient.getInstance();
        this.dbManager = DatabaseManager.getInstance();
        this.config = ConfigManager.getInstance();
    }
    
    public static synchronized UserInboundSync getInstance() {
        if (instance == null) {
            instance = new UserInboundSync();
        }
        return instance;
    }
    
    @Override
    public String getName() {
        return "UserSync";
    }
    
    @Override
    public SyncResult sync(String lastSyncTime) throws Exception {
        logger.info("Starting POS user sync");
        
        // Get device store ID
        String deviceStoreId = config.getProperty("store.id");
        if (deviceStoreId == null || deviceStoreId.isEmpty()) {
            logger.warn("Device not registered to a store, skipping user sync");
            return SyncResult.empty(SyncDirection.INBOUND);
        }
        
        try {
            // First, clear any users that don't belong to the current store
            // This handles cases where store was changed but old data wasn't properly cleared
            clearUsersFromOtherStores(deviceStoreId);
            
            // Call backend API to get POS users
            // Backend returns array directly in data field: { success: true, data: [...] }
            // ApiClient extracts data field, so we receive the array directly
            ApiClient.ApiResponse<PosUserSyncData[]> response = apiClient.get(
                "/pos/users",
                PosUserSyncData[].class
            );
            
            PosUserSyncData[] usersArray = response.getData();
            
            if (usersArray == null || usersArray.length == 0) {
                logger.info("No POS users returned from backend");
                return SyncResult.empty(SyncDirection.INBOUND);
            }
            
            List<PosUserSyncData> users = Arrays.asList(usersArray);
            
            // Store users in local database (preserving PIN hashes)
            int synced = storePosUsers(users, deviceStoreId);
            
            // Deactivate users that are no longer in the backend response
            List<String> activeUserIds = users.stream()
                .filter(u -> deviceStoreId.equals(u.storeId))
                .map(u -> u.id)
                .toList();
            deactivateRemovedUsers(activeUserIds, deviceStoreId);
            
            // If the currently logged-in user was synced, reload their data and clear permission cache
            UserAuthService userAuthService = UserAuthService.getInstance();
            if (userAuthService.isLoggedIn() && userAuthService.isPosUser()) {
                String currentUserId = userAuthService.getCurrentPosUserId();
                // Check if current user was in the sync
                boolean currentUserUpdated = users.stream()
                    .anyMatch(u -> u.id.equals(currentUserId) && deviceStoreId.equals(u.storeId));
                
                if (currentUserUpdated) {
                    logger.info("Current logged-in user was synced, reloading user data and clearing permission cache");
                    // Clear permission cache to force recalculation with new role
                    RoleBasedAccessService.getInstance().clearPermissionCache();
                    // Reload current user from database to update in-memory object
                    userAuthService.reloadCurrentPosUserFromDB();
                }
            }
            
            logger.info("Synced {} POS users", synced);
            return SyncResult.success(SyncDirection.INBOUND, synced);
            
        } catch (ApiClient.ApiException e) {
            logger.error("POS user sync failed: {}", e.getMessage());
            return SyncResult.failure(SyncDirection.INBOUND, e.getMessage());
        }
    }
    
    /**
     * Clear users that belong to a different store.
     * This ensures clean data when store ID changes.
     */
    private void clearUsersFromOtherStores(String currentStoreId) {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "DELETE FROM pos_users WHERE store_id != ? AND store_id IS NOT NULL";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, currentStoreId);
                int deleted = stmt.executeUpdate();
                conn.commit();

                if (deleted > 0) {
                    logger.info("Cleared {} users from other stores before sync", deleted);
                }
            }
        } catch (SQLException e) {
            logger.warn("Error clearing users from other stores: {}", e.getMessage());
            // Continue with sync even if this fails
        }
    }
    
    /**
     * Store POS users in local database.
     * Preserves existing PIN hashes from previous online logins.
     */
    private int storePosUsers(List<PosUserSyncData> users, String storeId) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            // H2 uses MERGE INTO for upsert
            // Include role field to sync from backend
            String sql = "MERGE INTO pos_users " +
                "(id, username, pin_hash, full_name, store_id, role, is_active, synced_at) " +
                "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

            String syncedAt = Instant.now().toString();
            int successCount = 0;

            try {
                conn.setAutoCommit(false);

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    for (PosUserSyncData user : users) {
                        // Only sync users that belong to this store
                        if (!storeId.equals(user.storeId)) {
                            logger.debug("Skipping user {} - store mismatch (expected: {}, got: {})",
                                user.username, storeId, user.storeId);
                            continue;
                        }

                        // Preserve existing PIN hash (from previous online login)
                        String existingPinHash = getExistingPinHash(user.id);

                        stmt.setString(1, user.id);
                        stmt.setString(2, user.username);
                        // Keep existing PIN hash if available, otherwise empty
                        // PIN hash will be set when user logs in online
                        stmt.setString(3, existingPinHash != null ? existingPinHash : "");
                        stmt.setString(4, user.fullName);
                        stmt.setString(5, user.storeId);
                        // Use role from backend, default to "Cashier" if null/empty
                        String role = (user.role != null && !user.role.isEmpty()) ? user.role : "Cashier";
                        stmt.setString(6, role);

                        // SHIELD: Do not deactivate Cashiers during sync (requested to ensure login after End Day)
                        boolean isActive = user.isActive;
                        if ("Cashier".equals(role) && !isActive) {
                            logger.info("Shielding Cashier {} from backend deactivation", user.username);
                            isActive = true;
                        }
                        stmt.setBoolean(7, isActive);
                        stmt.setString(8, syncedAt);
                        stmt.addBatch();
                        successCount++;
                    }

                    stmt.executeBatch();
                    conn.commit();
                }

                if (users != null && !users.isEmpty()) {
                    clearLegacyUserPermissionOverrides(users, storeId);
                }

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }

            return successCount;
        }
    }
    
    /**
     * Get existing PIN hash for a user (from previous online login).
     */
    private String getExistingPinHash(String userId) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT pin_hash FROM pos_users WHERE id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, userId);
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    String pinHash = rs.getString("pin_hash");
                    // Return only if it's a valid hash (not empty)
                    if (pinHash != null && !pinHash.isEmpty()) {
                        return pinHash;
                    }
                }
            }
        }

        return null;
    }
    
    /**
     * Update PIN hash for a POS user after successful online login.
     * Called by UserAuthService after backend authentication succeeds.
     */
    public void updatePosUserPinHash(String userId, String pinHash) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "UPDATE pos_users SET pin_hash = ? WHERE id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, pinHash);
                stmt.setString(2, userId);
                int updated = stmt.executeUpdate();
                conn.commit();

                if (updated > 0) {
                    logger.info("Updated PIN hash for POS user: {}", userId);
                } else {
                    logger.warn("POS user not found for PIN hash update: {}", userId);
                }
            }
        }
    }
    
    /**
     * Deactivate users that are no longer in the backend.
     * Called during full sync to handle deleted users.
     */
    public void deactivateRemovedUsers(List<String> activeUserIds, String storeId) throws SQLException {
        if (activeUserIds == null || activeUserIds.isEmpty()) {
            return;
        }
        
        try (Connection conn = dbManager.getConnection()) {
            // Build placeholders for IN clause
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < activeUserIds.size(); i++) {
                if (i > 0) placeholders.append(",");
                placeholders.append("?");
            }

            String sql = "UPDATE pos_users SET is_active = FALSE " +
                "WHERE store_id = ? AND role != 'Cashier' AND id NOT IN (" + placeholders + ")";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, storeId);
                for (int i = 0; i < activeUserIds.size(); i++) {
                    stmt.setString(i + 2, activeUserIds.get(i));
                }
                int deactivated = stmt.executeUpdate();
                conn.commit();

                if (deactivated > 0) {
                    logger.info("Deactivated {} users no longer in backend", deactivated);
                }
            }
        }
    }
    
    /**
     * Clear old per-user permission overrides so synced users follow their role only.
     */
    private void clearLegacyUserPermissionOverrides(List<PosUserSyncData> users, String storeId) {
        if (users == null || users.isEmpty()) {
            return;
        }

        try {
            PermissionManagementService permissionService = PermissionManagementService.getInstance();
            int clearedCount = 0;

            for (PosUserSyncData user : users) {
                if (!storeId.equals(user.storeId)) {
                    continue;
                }

                clearedCount += permissionService.clearLegacyUserPermissions(
                        user.id,
                        PermissionManagementService.USER_TYPE_POS_USER);
            }

            if (clearedCount > 0) {
                logger.info("Cleared {} legacy per-user permission override rows during POS user sync", clearedCount);
            }
        } catch (SQLException e) {
            logger.warn("Failed to clear legacy per-user permission overrides during sync: {}", e.getMessage());
        } finally {
            RoleBasedAccessService.getInstance().clearPermissionCache();
        }
    }
    
    /**
     * Get local user count (for diagnostics).
     */
    public int getLocalUserCount() {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT COUNT(*) FROM pos_users WHERE is_active = TRUE";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting user count", e);
        }
        return 0;
    }
}

