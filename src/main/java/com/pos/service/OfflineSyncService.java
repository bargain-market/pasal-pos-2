package com.pos.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.pos.database.DatabaseManager;
import com.pos.sync.ForcedOutboundSyncResult;
import com.pos.sync.MarkAllAsSyncedResult;
import com.pos.sync.PendingOutboundBreakdown;
import com.pos.sync.SyncManager;
import com.pos.sync.SyncStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.UUID;

/**
 * Service for handling offline operations and request queuing.
 * 
 * <h2>Refactored Architecture</h2>
 * This service now focuses on:
 * 1. Request queuing for offline operations
 * 2. Connectivity status (delegates to SyncManager)
 * 3. Pending request management
 * 
 * Actual sync operations are handled by SyncManager and its handlers.
 * 
 * <h2>Offline Queue</h2>
 * When the device is offline, requests are queued in the local database.
 * The SyncManager processes these queued requests when connectivity is restored.
 */
public class OfflineSyncService {
    private static final Logger logger = LoggerFactory.getLogger(OfflineSyncService.class);
    private static OfflineSyncService instance;
    
    private final DatabaseManager dbManager;
    private final Gson gson;
    
    private OfflineSyncService() {
        this.dbManager = DatabaseManager.getInstance();
        this.gson = new GsonBuilder().create();
    }
    
    public static synchronized OfflineSyncService getInstance() {
        if (instance == null) {
            instance = new OfflineSyncService();
        }
        return instance;
    }
    
    /**
     * Check if device is online.
     * Delegates to SyncManager for centralized connectivity tracking.
     */
    public boolean isOnline() {
        return SyncManager.getInstance().isOnline();
    }
    
    /**
     * Check network connectivity.
     * Delegates to SyncManager for centralized connectivity tracking.
     */
    public boolean checkConnectivity() {
        return SyncManager.getInstance().checkConnectivity();
    }
    
    /**
     * Queue a request for later execution when online.
     * Used when operations fail due to offline status.
     * 
     * @param endpoint API endpoint
     * @param method HTTP method (POST, PUT, PATCH)
     * @param payload Request payload
     * @param priority Priority (higher = more important)
     */
    public void queueRequest(String endpoint, String method, Object payload, int priority) {
        try (Connection conn = dbManager.getConnection()) {
            String sql = """
                INSERT INTO pending_requests (id, endpoint, method, payload, priority, retry_count, created_at)
                VALUES (?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP)
                """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, UUID.randomUUID().toString());
                stmt.setString(2, endpoint);
                stmt.setString(3, method);
                stmt.setString(4, gson.toJson(payload));
                stmt.setInt(5, priority);
                stmt.executeUpdate();
                conn.commit();
            }

            logger.info("Request queued for offline sync: {} {}", method, endpoint);
        } catch (SQLException e) {
            logger.error("Failed to queue request", e);
        }
    }

    /**
     * Sync pending outbound operations (single pass).
     */
    public SyncResult syncPendingOperations() {
        return syncPendingOperations(false);
    }

    /**
     * Sync pending outbound operations.
     *
     * @param force when true (Settings Sync Now), drain queues in repeated passes and write failure report
     */
    public SyncResult syncPendingOperations(boolean force) {
        SyncManager syncManager = SyncManager.getInstance();
        PendingOutboundBreakdown breakdownBefore = syncManager.getPendingOutboundBreakdown();
        int remainingBefore = breakdownBefore.total();

        if (!isOnline()) {
            logger.debug("Device is offline, skipping sync");
            String reportPath = null;
            if (remainingBefore > 0) {
                SyncPendingReportService.SyncRunSummary summary = new SyncPendingReportService.SyncRunSummary();
                summary.online = false;
                summary.breakdownBefore = breakdownBefore;
                summary.breakdownAfter = breakdownBefore;
                summary.note = "Device is offline — connect before syncing.";
                reportPath = SyncPendingReportService.getInstance().writeReport(summary);
            }
            return buildResult(0, 0, remainingBefore, 0, breakdownBefore, breakdownBefore,
                    "Device is offline — connect before syncing.", reportPath);
        }

        int synced;
        int failed;
        int rounds = 0;
        String handlerErrors = null;
        String note = null;

        if (force) {
            ForcedOutboundSyncResult forced = syncManager.performForcedOutboundSync();
            synced = forced.getSynced();
            failed = forced.getFailed();
            rounds = forced.getRoundsExecuted();
            handlerErrors = forced.getError();
            note = handlerErrors;
        } else {
            if (!syncManager.waitForOutboundSyncIdle(120_000)) {
                PendingOutboundBreakdown after = syncManager.getPendingOutboundBreakdown();
                return buildResult(0, 0, after.total(), 0, breakdownBefore, after,
                        "Another sync is still running; try Sync Now again.", null);
            }
            com.pos.sync.SyncResult outbound = syncManager.performOutboundSync();
            synced = outbound.getSynced();
            failed = outbound.getFailed();
            handlerErrors = outbound.getError();
            note = handlerErrors;
            if (note == null && synced == 0 && failed == 0 && remainingBefore > 0) {
                note = "No items were processed this run.";
            }
        }

        PendingOutboundBreakdown breakdownAfter = syncManager.getPendingOutboundBreakdown();
        int remainingAfter = breakdownAfter.total();

        String reportPath = null;
        if (remainingAfter > 0 || failed > 0) {
            SyncPendingReportService.SyncRunSummary summary = new SyncPendingReportService.SyncRunSummary();
            summary.online = true;
            summary.synced = synced;
            summary.failed = failed;
            summary.roundsExecuted = rounds;
            summary.breakdownBefore = breakdownBefore;
            summary.breakdownAfter = breakdownAfter;
            summary.handlerErrors = handlerErrors;
            summary.note = note;
            reportPath = SyncPendingReportService.getInstance().writeReport(summary);
        }

        return buildResult(synced, failed, remainingAfter, rounds, breakdownBefore, breakdownAfter, note, reportPath);
    }
    
    /**
     * Get count of pending requests.
     */
    public int getPendingRequestCount() {
        return SyncManager.getInstance().getPendingOutboundCount();
    }

    /**
     * Batch-mark all pending outbound sync rows as synced and clear the offline request queue.
     * Does not contact the backend.
     */
    public MarkAllAsSyncedResult markAllPendingAsSynced() throws SQLException {
        return SyncManager.getInstance().markAllPendingAsSynced();
    }
    
    /**
     * Get current sync status.
     */
    public SyncStatus getSyncStatus() {
        return SyncManager.getInstance().getStatus();
    }

    private static SyncResult buildResult(int synced, int failed, int remaining, int rounds,
            PendingOutboundBreakdown before, PendingOutboundBreakdown after,
            String note, String reportPath) {
        return new SyncResult(synced, failed, remaining, rounds, before, after, note, reportPath);
    }
    
    /**
     * Sync result for Settings / manual sync.
     */
    public static class SyncResult {
        public int synced;
        public int failed;
        public int remaining;
        public int roundsExecuted;
        public PendingOutboundBreakdown breakdownBefore;
        public PendingOutboundBreakdown breakdownAfter;
        /** Optional detail (offline, handler errors, etc.). */
        public String note;
        /** Path to sync-failure-report.txt in Documents/Pasal POS 2/sync logs when written. */
        public String reportPath;

        public SyncResult(int synced, int failed, int remaining) {
            this(synced, failed, remaining, 0, null, null, null, null);
        }

        public SyncResult(int synced, int failed, int remaining, int roundsExecuted,
                PendingOutboundBreakdown breakdownBefore, PendingOutboundBreakdown breakdownAfter,
                String note, String reportPath) {
            this.synced = synced;
            this.failed = failed;
            this.remaining = remaining;
            this.roundsExecuted = roundsExecuted;
            this.breakdownBefore = breakdownBefore;
            this.breakdownAfter = breakdownAfter;
            this.note = note;
            this.reportPath = reportPath;
        }
    }
}
