package com.pos.service;

import com.pos.sync.inbound.UserInboundSync;
import com.pos.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * Service for user sync coordination.
 * 
 * <h2>Refactored Architecture</h2>
 * This service now delegates sync operations to the centralized SyncManager
 * and UserInboundSync handler. It maintains backward compatibility for
 * existing code that uses this service directly.
 * 
 * <h2>Security Note</h2>
 * PIN hashes are NOT synced from backend. They are stored locally only when:
 * 1. User successfully logs in online (PIN verified by backend)
 * 2. PIN hash is then cached locally for offline authentication
 * 
 * <h2>Sync Flow</h2>
 * <pre>
 * UserSyncService (facade)
 *         │
 *         ▼
 * SyncManager (orchestrator)
 *         │
 *         ▼
 * UserInboundSync (handler)
 *         │
 *         ▼
 * Local H2 Database (pos_users table)
 * </pre>
 */
public class UserSyncService {
    private static final Logger logger = LoggerFactory.getLogger(UserSyncService.class);
    private static UserSyncService instance;
    
    private UserSyncService() {
        // Private constructor for singleton
    }
    
    public static synchronized UserSyncService getInstance() {
        if (instance == null) {
            instance = new UserSyncService();
        }
        return instance;
    }
    
    /**
     * Sync POS users from backend.
     * Delegates to SyncManager/UserInboundSync for actual sync.
     * 
     * @throws Exception if sync fails
     */
    public void syncPosUsers() throws Exception {
        logger.info("Starting POS user sync via SyncManager");
        
        try {
            // Delegate to UserInboundSync directly for user-specific sync
            SyncResult result = UserInboundSync.getInstance().sync(null);
            
            if (result.isSuccess()) {
                logger.info("POS user sync completed: {} users synced", result.getSynced());
            } else {
                logger.warn("POS user sync completed with errors: {}", result.getError());
                if (result.getSynced() == 0) {
                    throw new Exception("User sync failed: " + result.getError());
                }
            }
        } catch (Exception e) {
            logger.error("POS user sync failed", e);
            throw e;
        }
    }
    
    /**
     * Update PIN hash for a POS user after successful online login.
     * This is called by UserAuthService after backend authentication succeeds.
     * 
     * @param userId User ID
     * @param pinHash Hashed PIN
     * @throws SQLException if database operation fails
     */
    public void updatePosUserPinHash(String userId, String pinHash) throws SQLException {
        UserInboundSync.getInstance().updatePosUserPinHash(userId, pinHash);
    }
    
    /**
     * Get local user count (for diagnostics).
     */
    public int getLocalUserCount() {
        return UserInboundSync.getInstance().getLocalUserCount();
    }
}
