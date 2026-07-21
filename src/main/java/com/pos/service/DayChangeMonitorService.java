package com.pos.service;

import com.pos.api.dto.ShiftResponse;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Service to monitor for day changes and automatically close sessions and
 * logout users.
 * 
 * Best Practice Implementation:
 * - If cart is empty: immediately close shift and logout
 * - If cart has items: set pending flag and show warning dialog
 * - After sale completes: check pending flag and trigger logout
 */
public class DayChangeMonitorService {
    private static final Logger logger = LoggerFactory.getLogger(DayChangeMonitorService.class);
    private static DayChangeMonitorService instance;

    private ScheduledExecutorService scheduler;
    private LocalDate lastCheckedDate;
    private boolean isRunning = false;

    // Pending logout flag
    private volatile boolean pendingDayChangeLogout = false;

    // Callback for showing warning dialog (set by UI)
    private Consumer<Runnable> dayChangeWarningCallback;

    // Callback to check if cart has items (set by UI)
    private java.util.function.BooleanSupplier cartHasItemsChecker;

    private DayChangeMonitorService() {
        // Use logical date (now - 2 hours) so day change occurs at 2 AM
        this.lastCheckedDate = LocalDateTime.now().minusHours(2).toLocalDate();
    }

    public static synchronized DayChangeMonitorService getInstance() {
        if (instance == null) {
            instance = new DayChangeMonitorService();
        }
        return instance;
    }

    /**
     * Set callback to check if cart has items.
     * Should return true if there are items in the cart.
     */
    public void setCartHasItemsChecker(java.util.function.BooleanSupplier checker) {
        this.cartHasItemsChecker = checker;
    }

    /**
     * Set callback for showing day change warning dialog.
     * The consumer receives a Runnable that should be called after sale
     * completes/cancels.
     */
    public void setDayChangeWarningCallback(Consumer<Runnable> callback) {
        this.dayChangeWarningCallback = callback;
    }

    /**
     * Check if there's a pending day change logout
     */
    public boolean isPendingDayChangeLogout() {
        return pendingDayChangeLogout;
    }

    /**
     * Clear the pending logout flag
     */
    public void clearPendingLogout() {
        this.pendingDayChangeLogout = false;
    }

    /**
     * Called after sale completion to trigger deferred logout if pending
     */
    public void onSaleCompleted() {
        if (pendingDayChangeLogout) {
            logger.info("Sale completed with pending day change logout. Triggering deferred cleanup...");
            pendingDayChangeLogout = false;
            performDayChangeCleanup(false); // false = don't check cart again
        }
    }

    /**
     * Start the monitoring service
     */
    public void start() {
        if (isRunning) {
            return;
        }

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DayChangeMonitor");
            t.setDaemon(true);
            return t;
        });

        logger.info("Starting DayChangeMonitorService. Initial date: {}", lastCheckedDate);

        // Initial check for stale shifts from previous runs/crashes
        checkStaleShiftsOnStartup();

        // Check every minute
        scheduler.scheduleAtFixedRate(this::checkDateChange, 1, 1, TimeUnit.MINUTES);
        isRunning = true;
    }

    /**
     * Stop the monitoring service
     */
    public void stop() {
        if (!isRunning) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }
        isRunning = false;
        pendingDayChangeLogout = false;
        logger.info("DayChangeMonitorService stopped");
    }

    /**
     * Check if the date has changed and perform cleanup if needed
     */
    private void checkDateChange() {
        try {
            // Use logical date (now - 2 hours) so day change occurs at 2 AM
            LocalDate currentDate = LocalDateTime.now().minusHours(2).toLocalDate();

            // If date has changed (current date is after last checked date)
            if (currentDate.isAfter(lastCheckedDate)) {
                logger.info("Day change detected (at 2 AM boundary)! Previous: {}, Current: {}", lastCheckedDate, currentDate);

                // Update last checked date first to prevent repeated triggers
                lastCheckedDate = currentDate;

                performDayChangeCleanup(true); // true = check cart first
            }
        } catch (Exception e) {
            logger.error("Error in DayChangeMonitorService", e);
        }
    }

    /**
     * Check for stale shifts on startup (e.g. app was closed overnight)
     */
    private void checkStaleShiftsOnStartup() {
        try {
            ShiftService shiftService = ShiftService.getInstance();
            int closedCount = shiftService.closeAllStaleShifts();

            if (closedCount > 0) {
                logger.info("Closed {} stale shift(s) from previous days on startup", closedCount);

                // Also ensure user is logged out to force new login/session
                UserAuthService authService = UserAuthService.getInstance();
                if (authService.isLoggedIn()) {
                    logger.info("Logging out user after closing stale shifts on startup");
                    authService.logout();
                }
            }
        } catch (Exception e) {
            logger.error("Error checking for stale shifts on startup", e);
        }
    }

    /**
     * Perform cleanup operations for day change
     * 
     * @param checkCart if true, checks if cart has items before forcing logout
     */
    private void performDayChangeCleanup(boolean checkCart) {
        logger.info("Performing day change cleanup (checkCart={})...", checkCart);

        try {
            UserAuthService authService = UserAuthService.getInstance();

            // Check if cart has items (deferred logout)
            // Only check cart if a user is actually logged in, otherwise proceed to close
            // shift
            if (authService.isLoggedIn() && checkCart && cartHasItemsChecker != null
                    && cartHasItemsChecker.getAsBoolean()) {
                logger.info("Cart has items. Setting pending logout flag and showing warning...");
                pendingDayChangeLogout = true;

                // Show warning dialog on JavaFX thread
                if (dayChangeWarningCallback != null) {
                    Platform.runLater(() -> {
                        dayChangeWarningCallback.accept(this::onSaleCompleted);
                    });
                }
                return; // Don't logout yet, wait for sale to complete
            }

            // Close shift if active
            closeActiveShiftIfExists();

            // Logout user if logged in
            if (authService.isLoggedIn()) {
                logger.info("Logging out user due to day change");
                authService.logout();
            }

        } catch (Exception e) {
            logger.error("Error performing day change cleanup", e);
        }
    }

    /**
     * Close active shift if one exists
     */
    private void closeActiveShiftIfExists() {
        try {
            ShiftService shiftService = ShiftService.getInstance();
            ShiftResponse.ShiftData activeShift = shiftService.getActiveShiftFromLocal();

            if (activeShift != null && "ACTIVE".equals(activeShift.status)) {
                logger.info("Found active shift {} during day change cleanup. Auto-closing...", activeShift.id);

                try {
                    BigDecimal actualCash = shiftService.calculateExpectedDrawerTotal(activeShift.id);
                    String closingNote = "System auto-closed due to day change";

                    shiftService.endShift(activeShift.id, actualCash, closingNote);
                    logger.info("Shift {} auto-closed successfully", activeShift.id);
                } catch (Exception e) {
                    logger.error("Failed to auto-close shift {}", activeShift.id, e);
                }
            }
        } catch (Exception e) {
            logger.error("Error checking/closing active shift", e);
        }
    }
}
