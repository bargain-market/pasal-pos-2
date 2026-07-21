package com.pos.sync;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Centralized Sync Manager for offline-first POS architecture.
 * 
 * <h2>Sync Architecture Overview</h2>
 * 
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                     BACKEND (Source of Truth)                   │
 * │  Products, Departments, Users, Settings, Store Info             │
 * └─────────────────────────────────────────────────────────────────┘
 *                              │
 *            ┌─────────────────┴─────────────────┐
 *            ▼ (WebSocket Real-time)             ▼ (REST API Fallback)
 * ┌─────────────────────────┐     ┌─────────────────────────────────┐
 * │   WebSocket Client      │     │   HTTP Polling (Reduced)        │
 * │   - Real-time updates   │     │   - Full sync every 15 min      │
 * │   - Instant price       │     │   - Fallback when offline       │
 * │   - Stock notifications │     │   - Reconciliation              │
 * └─────────────────────────┘     └─────────────────────────────────┘
 *                              │
 *                              ▼
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                      SYNC MANAGER                               │
 * │  - WebSocket connection management                              │
 * │  - Connectivity monitoring                                      │
 * │  - Sync scheduling (periodic + on-demand)                       │
 * │  - Conflict resolution (backend always wins for inbound)        │
 * │  - Retry logic with exponential backoff                         │
 * └─────────────────────────────────────────────────────────────────┘
 *                              │
 *                              ▼
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                    LOCAL DATABASE (H2)                          │
 * │  - Products (cached from backend)                               │
 * │  - Departments (cached from backend)                            │
 * │  - POS Users (metadata + PIN hash after login)                  │
 * │  - Sales (created locally, synced to backend)                   │
 * │  - Shifts (created locally, synced to backend)                  │
 * │  - Pending Requests Queue                                       │
 * └─────────────────────────────────────────────────────────────────┘
 *                              │
 *                              ▼ (OUTBOUND - Push to backend)
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                     BACKEND                                     │
 * │  Sales, Shifts, Cash Operations                                 │
 * └─────────────────────────────────────────────────────────────────┘
 * </pre>
 * 
 * <h2>Sync Directions</h2>
 * <ul>
 * <li><b>INBOUND (Backend → Local):</b> Products, Departments, Users,
 * Settings - Now primarily via WebSocket</li>
 * <li><b>OUTBOUND (Local → Backend):</b> Sales, Shifts, Cash Operations</li>
 * </ul>
 * 
 * <h2>Offline-First Principles</h2>
 * <ol>
 * <li>All operations work offline using local database</li>
 * <li>WebSocket provides real-time updates when connected</li>
 * <li>REST API polling as fallback (reduced frequency: 15 min)</li>
 * <li>Outbound data queued when offline, synced when online</li>
 * <li>Backend is source of truth for products/users</li>
 * </ol>
 */
public class SyncManager {
    private static final Logger logger = LoggerFactory.getLogger(SyncManager.class);
    private static SyncManager instance;

    // Connectivity and WebSocket configuration
    private static final int CONNECTIVITY_CHECK_INTERVAL_SECONDS = 30;
    private static final int WEBSOCKET_RECONNECT_DELAY_SECONDS = 10;
    private static final int MAX_RETRIES = 3;
    private static final int BASE_RETRY_DELAY_MS = 1000;
    private static final int SYNC_CHECK_INTERVAL_SECONDS = 60; // Check for stuck syncs every minute

    /** Max wait for in-flight outbound sync before forced manual sync. */
    private static final int FORCED_OUTBOUND_WAIT_MS = 300_000;
    private static final int FORCED_OUTBOUND_WAIT_STEP_MS = 250;
    /** Safety cap for forced sync drain loops. */
    private static final int FORCED_OUTBOUND_MAX_ROUNDS = 200;

    private final DatabaseManager dbManager;
    private final ConfigManager config;
    private final ScheduledExecutorService scheduler;
    private final WebSocketClient webSocketClient;

    // Connection state
    private volatile boolean isOnline = false;
    private volatile boolean isWebSocketConnected = false;
    private volatile String lastSyncError = null;
    
    // Sync state tracking
    private final AtomicBoolean isInboundSyncing = new AtomicBoolean(false);
    private final AtomicBoolean isOutboundSyncing = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private volatile Instant lastInboundSync = null;
    private volatile Instant lastOutboundSync = null;
    
    // Sync handlers
    private final List<InboundSyncHandler> inboundHandlers = new ArrayList<>();
    private final List<OutboundSyncHandler> outboundHandlers = new ArrayList<>();

    // Status listeners
    private final List<Consumer<SyncStatus>> statusListeners = new ArrayList<>();

    private SyncManager() {
        this.dbManager = DatabaseManager.getInstance();
        this.config = ConfigManager.getInstance();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "SyncManagerScheduler");
            thread.setDaemon(true);
            return thread;
        };
        this.scheduler = Executors.newScheduledThreadPool(4, threadFactory);
        this.webSocketClient = WebSocketClient.getInstance();
        
        // Register sync handlers
        registerSyncHandlers();
    }
    
    /**
     * Register all sync handlers.
     */
    private void registerSyncHandlers() {
        // Register outbound handlers (Local -> Backend)
        // Products/departments before sales so the backend has rows before sale line items reference them.
        outboundHandlers.add(com.pos.sync.outbound.ProductOutboundSync.getInstance());
        outboundHandlers.add(com.pos.sync.outbound.SalesOutboundSync.getInstance());
        outboundHandlers.add(com.pos.sync.outbound.ShiftOutboundSync.getInstance());
        outboundHandlers.add(com.pos.sync.outbound.VendorOutboundSync.getInstance());
        outboundHandlers.add(com.pos.sync.outbound.VendorPayoutOutboundSync.getInstance());
        outboundHandlers.add(com.pos.sync.outbound.CartCancellationOutboundSync.getInstance());
        outboundHandlers.add(com.pos.sync.outbound.ExpenseOutboundSync.getInstance());
        
        // Register inbound handlers (Backend -> Local)
        inboundHandlers.add(com.pos.sync.inbound.ProductInboundSync.getInstance());
        inboundHandlers.add(com.pos.sync.inbound.VendorInboundSync.getInstance());
        inboundHandlers.add(com.pos.sync.inbound.UserInboundSync.getInstance());
        inboundHandlers.add(com.pos.sync.inbound.SettingsInboundSync.getInstance());
        inboundHandlers.add(com.pos.sync.inbound.AdsInboundSync.getInstance());
        
        logger.info("Registered {} outbound and {} inbound sync handlers", 
            outboundHandlers.size(), inboundHandlers.size());
    }
    
    /**
     * Functional interface for sync operations with retry support.
     */
    @FunctionalInterface
    public interface SyncOperation {
        SyncResult execute() throws Exception;
    }
    
    /**
     * Interface for inbound sync handlers (Backend -> Local).
     */
    public interface InboundSyncHandler {
        String getName();
        SyncResult sync(String lastSyncTime) throws Exception;
    }
    
    /**
     * Interface for outbound sync handlers (Local -> Backend).
     */
    public interface OutboundSyncHandler {
        String getName();
        SyncResult sync() throws Exception;
    }

    public static synchronized SyncManager getInstance() {
        if (instance == null) {
            instance = new SyncManager();
        }
        return instance;
    }

    /**
     * Initialize sync manager and start background sync tasks.
     * Call this after database schema is initialized.
     */
    public void initialize() {
        isShuttingDown.set(false);
        logger.info("🚀 Initializing SyncManager with WebSocket support...");

        // Set up WebSocket connection status listener
        webSocketClient.setConnectionStatusListener(connected -> {
            isWebSocketConnected = connected;
            if (connected) {
                logger.info("✅ WebSocket CONNECTED - Real-time sync enabled");
            } else {
                logger.warn("⚠️ WebSocket DISCONNECTED - Using API polling fallback");
            }
            notifyStatusListeners();
            
            if (!connected && isOnline && !isShuttingDown.get()
                    && !webSocketClient.isReconnectExhausted()) {
                // Schedule reconnection attempt
                safeSchedule(this::attemptWebSocketReconnect,
                        WEBSOCKET_RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
            }
        });

        webSocketClient.setErrorListener(error -> {
            logger.warn("WebSocket error: {}", error);
            lastSyncError = "WebSocket: " + error;
        });

        // Run the first backend reachability probe asynchronously so startup never
        // blocks on an unavailable backend.
        safeExecute(this::checkConnectivity);

        // Connect to WebSocket if online
        if (isOnline && !webSocketClient.isReconnectExhausted()) {
            logger.info("📡 Network is online, attempting WebSocket connection...");
            boolean initiated = webSocketClient.connect();
            if (!initiated) {
                logger.warn("⚠️ WebSocket connection not initiated - check credentials or backend URL");
            }
        } else if (!isOnline) {
            logger.warn("⚠️ Network is offline, WebSocket connection skipped");
        }

        // Schedule periodic connectivity check only
        scheduler.scheduleWithFixedDelay(
                this::checkConnectivity,
                CONNECTIVITY_CHECK_INTERVAL_SECONDS,
                CONNECTIVITY_CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        // Schedule periodic check for pending syncs (failsafe for network errors)
        scheduler.scheduleWithFixedDelay(
                this::checkPendingSyncs,
                SYNC_CHECK_INTERVAL_SECONDS,
                SYNC_CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        logger.info("✅ SyncManager initialized with WebSocket-only real-time sync (failsafe polling enabled)");
    }

    /**
     * Add a listener for sync status changes.
     */
    public void addStatusListener(Consumer<SyncStatus> listener) {
        statusListeners.add(listener);
    }

    /**
     * Check network connectivity.
     */
    public boolean checkConnectivity() {
        if (isShuttingDown.get()) {
            return false;
        }

        boolean wasOnline = isOnline;

        try {
            InetSocketAddress backendAddress = resolveBackendAddress();
            if (backendAddress == null) {
                isOnline = false;
                lastSyncError = "Invalid backend URL";
            } else {
                // Treat backend reachability as the online/offline signal so the POS
                // falls back cleanly when the server is down.
                try (Socket socket = new Socket()) {
                    socket.connect(backendAddress, 3000);
                    isOnline = true;
                }
            }
        } catch (Exception e) {
            isOnline = false;
        }

        // Log state change
        if (wasOnline != isOnline) {
            logger.info("Backend connectivity changed: {}", isOnline ? "ONLINE" : "OFFLINE");
            notifyStatusListeners();

            // If we just came online, connect WebSocket
            if (isOnline && !isShuttingDown.get()) {
                if (!wasOnline) {
                    webSocketClient.resetReconnectState();
                }
                if (!isWebSocketConnected && !webSocketClient.isReconnectExhausted()) {
                    logger.info("🔌 Coming online - initiating WebSocket connection");
                    webSocketClient.connect();
                }
            } else {
                // Going offline - WebSocket will disconnect automatically
                isWebSocketConnected = false;
                logger.warn("⚠️ Going offline - WebSocket will disconnect");
            }
        }

        if (isOnline && !isWebSocketConnected && hasWebSocketCredentials()
                && !isShuttingDown.get() && !webSocketClient.isReconnectExhausted()) {
            logger.debug("Ensuring WebSocket connection while backend is reachable");
            webSocketClient.connect();
        }

        return isOnline;
    }

    private InetSocketAddress resolveBackendAddress() {
        String backendUrl = config.getProperty("backend.api.url", "http://localhost:3000/api/v1");

        try {
            URI uri = URI.create(backendUrl);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }

            int port = uri.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }

            return new InetSocketAddress(host, port);
        } catch (Exception e) {
            logger.warn("Invalid backend URL for connectivity check: {}", backendUrl, e);
            return null;
        }
    }

    private boolean hasWebSocketCredentials() {
        return !config.getProperty("api.key", "").isBlank()
                && !config.getProperty("store.id", "").isBlank()
                && !config.getProperty("device.id", "").isBlank();
    }

    /**
     * Attempt to reconnect WebSocket.
     */
    private void attemptWebSocketReconnect() {
        if (isShuttingDown.get()) {
            logger.debug("Skipping WebSocket reconnection because SyncManager is shutting down");
            return;
        }

        if (isOnline && !isWebSocketConnected && !webSocketClient.isReconnectExhausted()) {
            logger.info("Attempting WebSocket reconnection...");
            webSocketClient.connect();
        }
    }

    /**
     * Retry WebSocket after manual action (e.g. Settings sync) even if auto-reconnect
     * was exhausted.
     */
    public void retryWebSocketConnection() {
        if (isShuttingDown.get() || !isOnline) {
            return;
        }
        webSocketClient.resetReconnectState();
        webSocketClient.connect();
    }

    /**
     * Check if WebSocket is connected.
     */
    public boolean isWebSocketConnected() {
        return isWebSocketConnected;
    }

    /**
     * Get the WebSocket client for event registration.
     */
    public WebSocketClient getWebSocketClient() {
        return webSocketClient;
    }

    /**
     * Check if device is online.
     */
    public boolean isOnline() {
        return isOnline;
    }

    /**
     * Perform full inbound sync (backend → local).
     * This pulls products, departments, users, and settings from backend.
     * NOTE: This is MANUAL/FALLBACK only. WebSocket provides real-time updates.
     */
    public SyncResult performFullInboundSync() {
        logger.info("🔄 MANUAL full inbound sync requested (WebSocket handles real-time updates)");
        return performInboundSync(true);
    }

    /**
     * Perform incremental inbound sync (backend → local).
     * This pulls only changes since last sync.
     * NOTE: This is MANUAL/FALLBACK only. WebSocket provides real-time updates.
     */
    public SyncResult performInboundSync() {
        logger.info("🔄 MANUAL incremental inbound sync requested (WebSocket handles real-time updates)");
        return performInboundSync(false);
    }

    private SyncResult performInboundSync(boolean fullSync) {
        if (!isInboundSyncing.compareAndSet(false, true)) {
            logger.debug("Inbound sync already in progress, skipping");
            return new SyncResult(SyncDirection.INBOUND, 0, 0, "Sync already in progress");
        }

        if (!isOnline) {
            isInboundSyncing.set(false);
            logger.debug("Device offline, skipping inbound sync");
            return new SyncResult(SyncDirection.INBOUND, 0, 0, "Device offline");
        }

        logger.info("Starting {} inbound sync...", fullSync ? "full" : "incremental");

        int totalSynced = 0;
        int totalFailed = 0;
        StringBuilder errors = new StringBuilder();

        try {
            String lastSyncTime = fullSync ? null : getLastInboundSyncTime();

            for (InboundSyncHandler handler : inboundHandlers) {
                try {
                    logger.debug("Running inbound sync handler: {}", handler.getName());

                    SyncResult result = executeWithRetry(() -> handler.sync(lastSyncTime));

                    totalSynced += result.getSynced();
                    totalFailed += result.getFailed();

                    if (result.getError() != null) {
                        errors.append(handler.getName()).append(": ").append(result.getError()).append("; ");
                    }

                    logger.info("Inbound sync handler {} completed: {} synced, {} failed",
                            handler.getName(), result.getSynced(), result.getFailed());

                } catch (Exception e) {
                    totalFailed++;
                    errors.append(handler.getName()).append(": ").append(e.getMessage()).append("; ");
                    logger.error("Inbound sync handler {} failed", handler.getName(), e);
                }
            }

            // Update last sync time
            if (totalFailed == 0 || totalSynced > 0) {
                updateLastInboundSyncTime();
                lastInboundSync = Instant.now();
            }

            lastSyncError = errors.length() > 0 ? errors.toString() : null;

        } finally {
            isInboundSyncing.set(false);
            notifyStatusListeners();
        }

        logger.info("Inbound sync completed: {} synced, {} failed", totalSynced, totalFailed);
        return new SyncResult(SyncDirection.INBOUND, totalSynced, totalFailed, lastSyncError);
    }

    /**
     * Whether an outbound sync is currently running (scheduler or manual).
     * Used so UI-triggered sync can wait instead of returning 0/0 while work is in flight.
     */
    public boolean isOutboundSyncInProgress() {
        return isOutboundSyncing.get();
    }

    /**
     * Perform outbound sync (local → backend).
     * This pushes pending sales, shifts, and other local data to backend.
     * NOTE: This is MANUAL/FALLBACK only. WebSocket provides real-time updates.
     */
    public SyncResult performOutboundSync() {
        logger.info("🔄 MANUAL outbound sync requested (WebSocket handles real-time updates)");
        if (!isOutboundSyncing.compareAndSet(false, true)) {
            logger.debug("Outbound sync already in progress, skipping");
            return new SyncResult(SyncDirection.OUTBOUND, 0, 0, "Sync already in progress");
        }

        if (!isOnline) {
            isOutboundSyncing.set(false);
            logger.debug("Device offline, skipping outbound sync");
            return new SyncResult(SyncDirection.OUTBOUND, 0, 0, "Device offline");
        }

        logger.debug("Processing outbound sync handlers...");

        int totalSynced = 0;
        int totalFailed = 0;
        StringBuilder errors = new StringBuilder();

        try {
            for (OutboundSyncHandler handler : outboundHandlers) {
                try {
                    logger.debug("Running outbound sync handler: {}", handler.getName());

                    // Use executeWithRetry for robust error handling
                    SyncResult result = executeWithRetry(() -> handler.sync());

                    totalSynced += result.getSynced();
                    totalFailed += result.getFailed();

                    if (result.getError() != null) {
                        errors.append(handler.getName()).append(": ").append(result.getError()).append("; ");
                    }

                } catch (Exception e) {
                    totalFailed++;
                    errors.append(handler.getName()).append(": ").append(e.getMessage()).append("; ");
                    logger.error("Outbound sync handler {} failed", handler.getName(), e);
                }
            }

            lastOutboundSync = Instant.now();

        } finally {
            isOutboundSyncing.set(false);
            notifyStatusListeners();
        }

        if (totalSynced > 0 || totalFailed > 0) {
            logger.info("Outbound sync completed: {} synced, {} failed", totalSynced, totalFailed);
        }

        return new SyncResult(SyncDirection.OUTBOUND, totalSynced, totalFailed,
                errors.length() > 0 ? errors.toString() : null);
    }

    /**
     * Wait until no outbound sync is running, or timeout.
     *
     * @return true if idle (safe to start outbound sync), false if still running after timeout
     */
    public boolean waitForOutboundSyncIdle(int maxWaitMs) {
        int waited = 0;
        while (isOutboundSyncing.get() && waited < maxWaitMs) {
            try {
                Thread.sleep(FORCED_OUTBOUND_WAIT_STEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return !isOutboundSyncing.get();
            }
            waited += FORCED_OUTBOUND_WAIT_STEP_MS;
        }
        return !isOutboundSyncing.get();
    }

    /**
     * Force outbound sync from Settings "Sync Now": wait for any in-flight sync, then drain pending
     * queues in repeated passes until empty, stuck, or max rounds.
     */
    public ForcedOutboundSyncResult performForcedOutboundSync() {
        if (!isOnline) {
            return new ForcedOutboundSyncResult(0, 0, 0, "Device offline");
        }

        if (!waitForOutboundSyncIdle(FORCED_OUTBOUND_WAIT_MS)) {
            return new ForcedOutboundSyncResult(0, 0, 0,
                    "Another outbound sync is still running after waiting "
                            + (FORCED_OUTBOUND_WAIT_MS / 1000) + " seconds");
        }

        int totalSynced = 0;
        int totalFailed = 0;
        StringBuilder allErrors = new StringBuilder();
        int rounds = 0;

        while (rounds < FORCED_OUTBOUND_MAX_ROUNDS) {
            int pendingBefore = getPendingOutboundCount();
            if (pendingBefore == 0) {
                break;
            }

            SyncResult roundResult = performOutboundSync();
            rounds++;
            totalSynced += roundResult.getSynced();
            totalFailed += roundResult.getFailed();
            if (roundResult.getError() != null && !roundResult.getError().isBlank()) {
                if (allErrors.length() > 0) {
                    allErrors.append("; ");
                }
                allErrors.append("round ").append(rounds).append(": ").append(roundResult.getError());
            }

            int pendingAfter = getPendingOutboundCount();
            if (pendingAfter == 0) {
                break;
            }
            if (pendingAfter == pendingBefore
                    && roundResult.getSynced() == 0
                    && roundResult.getFailed() == 0) {
                if (allErrors.length() > 0) {
                    allErrors.append("; ");
                }
                allErrors.append("No progress in round ").append(rounds)
                        .append(" (pending still ").append(pendingAfter).append(")");
                break;
            }
        }

        if (rounds > 0) {
            logger.info("Forced outbound sync completed: {} synced, {} failed, {} rounds, {} pending remaining",
                    totalSynced, totalFailed, rounds, getPendingOutboundCount());
        }

        return new ForcedOutboundSyncResult(totalSynced, totalFailed, rounds,
                allErrors.length() > 0 ? allErrors.toString() : null);
    }

    /**
     * Trigger immediate sync (both directions) when coming online.
     */
    public void triggerImmediateSync() {
        safeExecute(() -> {
            logger.info("Triggering immediate sync after coming online");
            performOutboundSync(); // Outbound first to push pending data
            performInboundSync(); // Then inbound to get latest data
        });
    }

    /**
     * Trigger outbound sync asynchronously.
     * Useful for triggering outbound sync after inbound sync completes.
     */
    public void triggerOutboundSync() {
        safeExecute(() -> {
            logger.debug("Triggering outbound sync asynchronously");
            performOutboundSync();
        });
    }

    private void safeExecute(Runnable task) {
        if (isShuttingDown.get() || scheduler.isShutdown() || scheduler.isTerminated()) {
            logger.debug("Skipping task submission because SyncManager is shutting down");
            return;
        }

        try {
            scheduler.execute(task);
        } catch (RejectedExecutionException e) {
            if (!isShuttingDown.get()) {
                logger.debug("Task submission rejected during SyncManager execution", e);
            }
        }
    }

    private void safeSchedule(Runnable task, long delay, TimeUnit unit) {
        if (isShuttingDown.get() || scheduler.isShutdown() || scheduler.isTerminated()) {
            logger.debug("Skipping scheduled task because SyncManager is shutting down");
            return;
        }

        try {
            scheduler.schedule(task, delay, unit);
        } catch (RejectedExecutionException e) {
            if (!isShuttingDown.get()) {
                logger.debug("Scheduled task rejected during SyncManager execution", e);
            }
        }
    }

    /**
     * Execute a sync operation with retry logic.
     */
    private SyncResult executeWithRetry(SyncOperation operation) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return operation.execute();
            } catch (Exception e) {
                lastException = e;
                logger.warn("Sync attempt {} failed: {}", attempt, e.getMessage());

                if (attempt < MAX_RETRIES) {
                    try {
                        // Exponential backoff
                        long delay = (long) Math.pow(2, attempt) * BASE_RETRY_DELAY_MS;
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        return new SyncResult(SyncDirection.INBOUND, 0, 1,
                lastException != null ? lastException.getMessage() : "Unknown error");

    }

    /**
     * Check for pending outbound items and trigger sync if needed.
     * Use this as a failsafe periodic task.
     */
    private void checkPendingSyncs() {
        if (!isOnline) return;
        
        if (isOutboundSyncing.get()) {
            logger.debug("Sync check: Outbound sync already in progress");
            return;
        }

        int pendingCount = getPendingOutboundCount();
        if (pendingCount > 0) {
            logger.info("Sync check: Found {} pending items, triggering outbound sync", pendingCount);
            triggerOutboundSync();
        }
    }

    /**
     * Get current sync status.
     */
    public SyncStatus getStatus() {
        return new SyncStatus(
                isOnline,
                isWebSocketConnected,
                false, // No longer tracking inbound syncing
                false, // No longer tracking outbound syncing
                null,  // No lastInboundSync
                null,  // No lastOutboundSync
                lastSyncError,
                getPendingOutboundCount());
    }

    /**
     * Get count of pending outbound items.
     */
    public int getPendingOutboundCount() {
        return getPendingOutboundBreakdown().total();
    }

    /**
     * Pending outbound counts per table/queue.
     */
    public PendingOutboundBreakdown getPendingOutboundBreakdown() {
        PendingOutboundBreakdown breakdown = new PendingOutboundBreakdown();

        try (Connection conn = dbManager.getConnection()) {
            breakdown.sales = countQuery(conn, "SELECT COUNT(*) FROM sales WHERE synced = FALSE");
            breakdown.shifts = countQuery(conn, "SELECT COUNT(*) FROM shifts WHERE synced = FALSE");
            breakdown.products = countQuery(conn, "SELECT COUNT(*) FROM products WHERE synced = FALSE");
            breakdown.departments = countQuery(conn, "SELECT COUNT(*) FROM departments WHERE synced = FALSE");
            breakdown.pendingRequests = countQuery(conn,
                    "SELECT COUNT(*) FROM pending_requests WHERE retry_count < 5");
            breakdown.cartCancellations = countQuery(conn,
                    "SELECT COUNT(*) FROM cart_cancellations WHERE synced = FALSE");
        } catch (SQLException e) {
            logger.debug("Error getting pending outbound breakdown: {}", e.getMessage());
        }

        return breakdown;
    }

    private static int countQuery(Connection conn, String sql) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    /**
     * Notify all status listeners of state change.
     * Call this after sync operations to update the UI immediately.
     */
    public void notifyStatusListeners() {
        SyncStatus status = getStatus();
        for (Consumer<SyncStatus> listener : statusListeners) {
            try {
                listener.accept(status);
            } catch (Exception e) {
                logger.warn("Error notifying status listener", e);
            }
        }
    }

    /**
     * Get the last inbound sync time from database.
     */
    private String getLastInboundSyncTime() {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT setting_value FROM pos_settings WHERE setting_key = 'last_inbound_sync'";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            logger.debug("Error getting last inbound sync time: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Update the last inbound sync time in database.
     */
    private void updateLastInboundSyncTime() {
        try (Connection conn = dbManager.getConnection()) {
            String timestamp = java.time.Instant.now().toString();
            String sql = "MERGE INTO pos_settings (setting_key, setting_value) KEY(setting_key) VALUES (?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, "last_inbound_sync");
                stmt.setString(2, timestamp);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            logger.debug("Error updating last inbound sync time: {}", e.getMessage());
        }
    }

    /**
     * Batch-mark every locally pending outbound row as synced and clear the offline request queue.
     * Does not call the backend — use only when pending data should be discarded from the sync queue.
     */
    public MarkAllAsSyncedResult markAllPendingAsSynced() throws SQLException {
        MarkAllAsSyncedResult result = new MarkAllAsSyncedResult();
        result.breakdownBefore = getPendingOutboundBreakdown();

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                result.sales = batchMarkSynced(conn,
                        "UPDATE sales SET synced = TRUE, sync_error = NULL WHERE synced = FALSE");
                result.shifts = batchMarkSynced(conn, "UPDATE shifts SET synced = TRUE WHERE synced = FALSE");
                result.products = batchMarkSynced(conn, "UPDATE products SET synced = TRUE WHERE synced = FALSE");
                result.departments = batchMarkSynced(conn,
                        "UPDATE departments SET synced = TRUE WHERE synced = FALSE");
                result.cartCancellations = batchMarkSynced(conn,
                        "UPDATE cart_cancellations SET synced = TRUE WHERE synced = FALSE");
                result.refunds = batchMarkSynced(conn, "UPDATE refunds SET synced = TRUE WHERE synced = FALSE");
                result.cashOperations = batchMarkSynced(conn,
                        "UPDATE cash_operations SET synced = TRUE WHERE synced = FALSE");
                result.vendors = batchMarkSynced(conn, "UPDATE vendors SET synced = TRUE WHERE synced = FALSE");
                result.vendorPayouts = batchMarkSynced(conn,
                        "UPDATE vendor_payouts SET synced = TRUE WHERE synced = FALSE");
                result.expenses = batchMarkSynced(conn, "UPDATE expenses SET synced = TRUE WHERE synced = FALSE");
                result.inventoryLog = batchMarkSynced(conn,
                        "UPDATE inventory_log SET synced = TRUE WHERE synced = FALSE");
                result.employeeShifts = batchMarkSynced(conn,
                        "UPDATE employee_shifts SET synced = TRUE WHERE synced = FALSE");
                result.customers = batchMarkSyncedOptional(conn,
                        "UPDATE customers SET synced = TRUE WHERE synced = FALSE");
                result.pendingRequestsDeleted = batchMarkSynced(conn, "DELETE FROM pending_requests");

                conn.commit();
                logger.warn(
                        "Marked all pending outbound sync as synced locally ({} rows/requests). "
                                + "This data was not pushed to the backend.",
                        result.totalRowsMarked());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }

        result.breakdownAfter = getPendingOutboundBreakdown();
        notifyStatusListeners();
        return result;
    }

    private static int batchMarkSynced(Connection conn, String sql) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            return stmt.executeUpdate();
        }
    }

    /** Customers table is created lazily; skip if it does not exist yet. */
    private static int batchMarkSyncedOptional(Connection conn, String sql) throws SQLException {
        try {
            return batchMarkSynced(conn, sql);
        } catch (SQLException e) {
            if ("42S02".equals(e.getSQLState())) {
                return 0;
            }
            throw e;
        }
    }

    /**
     * Shutdown the sync manager.
     */
    public void shutdown() {
        if (!isShuttingDown.compareAndSet(false, true)) {
            logger.debug("SyncManager shutdown already in progress");
            return;
        }

        logger.info("Shutting down SyncManager...");
        webSocketClient.shutdown();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("SyncManager shutdown complete");
    }


}
