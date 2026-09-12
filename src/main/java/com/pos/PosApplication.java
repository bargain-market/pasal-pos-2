package com.pos;

import javafx.application.Application;
import javafx.animation.PauseTransition;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.geometry.Rectangle2D;
import javafx.util.Duration;
import com.pos.ui.MainWindow;
import com.pos.ui.DeviceRegistrationScreen;
import com.pos.ui.keyboard.KeyboardManager;
import com.pos.ui.LoginScreen;
import com.pos.hardware.HardwareManager;
import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.service.DeviceRegistrationService;
import com.pos.service.SubscriptionLeaseService;
import com.pos.service.UserAuthService;
import com.pos.service.SettingsService;
import com.pos.service.StoreService;
import com.pos.service.SalesService;
import com.pos.service.CustomerDisplayService;
import com.pos.service.BackupService;
import com.pos.sync.SyncManager;
import javafx.collections.FXCollections;
import com.pos.sync.inbound.ProductInboundSync;
import com.pos.sync.inbound.UserInboundSync;
import com.pos.sync.inbound.SettingsInboundSync;
import com.pos.sync.inbound.AdsInboundSync;
import com.pos.sync.inbound.VendorInboundSync;
import com.pos.sync.outbound.SalesOutboundSync;
import com.pos.sync.outbound.ShiftOutboundSync;
import com.pos.sync.outbound.ProductOutboundSync;
import com.pos.sync.outbound.VendorPayoutOutboundSync;
import com.pos.service.UpdateService;
import com.pos.util.DialogHelper;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.net.URL;

/**
 * Main application class for Pasal POS 2.
 * 
 * <h2>Offline-First Architecture</h2>
 * 
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │                         POS APPLICATION                                 │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │                                                                         │
 * │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐                 │
 * │  │   UI Layer  │    │  Services   │    │  Hardware   │                 │
 * │  │  (JavaFX)   │◄──►│   Layer     │◄──►│   Manager   │                 │
 * │  └─────────────┘    └─────────────┘    └─────────────┘                 │
 * │         │                  │                                           │
 * │         │                  ▼                                           │
 * │         │          ┌─────────────┐                                     │
 * │         │          │   Sync      │                                     │
 * │         └─────────►│  Manager    │                                     │
 * │                    └─────────────┘                                     │
 * │                           │                                            │
 * │         ┌─────────────────┼─────────────────┐                         │
 * │         ▼                 ▼                 ▼                         │
 * │  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐                 │
 * │  │  Inbound    │   │  Outbound   │   │  Offline    │                 │
 * │  │  Sync       │   │  Sync       │   │  Queue      │                 │
 * │  │ (Backend→DB)│   │ (DB→Backend)│   │             │                 │
 * │  └─────────────┘   └─────────────┘   └─────────────┘                 │
 * │         │                 │                 │                         │
 * │         └─────────────────┼─────────────────┘                         │
 * │                           ▼                                           │
 * │                    ┌─────────────┐                                     │
 * │                    │  Local H2   │                                     │
 * │                    │  Database   │                                     │
 * │                    └─────────────┘                                     │
 * │                                                                         │
 * └─────────────────────────────────────────────────────────────────────────┘
 * </pre>
 * 
 * <h2>Data Flow</h2>
 * <ul>
 * <li><b>Inbound (Backend → Local):</b> Products, Departments, Users,
 * Settings</li>
 * <li><b>Outbound (Local → Backend):</b> Sales, Shifts, Cash Operations,
 * Locally Created Products/Departments</li>
 * <li><b>Local Only:</b> Session data, cached credentials</li>
 * </ul>
 */
public class PosApplication extends Application {

    private static final Logger logger = LoggerFactory.getLogger(PosApplication.class);
    private HardwareManager hardwareManager;
    private SyncManager syncManager;
    private Stage primaryStage;
    private boolean databaseHealthy = true;
    private boolean databaseRecoveredAutomatically = false;
    private String databaseStatusMessage;
    private volatile boolean runtimeCorruptionHandled = false;

    @Override
    public void init() {
        // Force US Locale for consistent currency formatting ($ instead of NPR)
        Locale.setDefault(Locale.US);

        // Initialize configuration
        ConfigManager config = ConfigManager.getInstance();
        config.getSyncLogsDirectory();
        logger.info("Configuration loaded");

        BackupService backupService = BackupService.getInstance();
        try {
            if (backupService.applyPendingRestoreIfNeeded()) {
                logger.info("Applied queued database restore before initialization");
            }
        } catch (Exception e) {
            logger.error("Failed to apply queued database restore", e);
        }

        initializeDatabase(backupService);

        // Initialize hardware manager (hardware is optional for development/testing)
        hardwareManager = HardwareManager.getInstance();
        hardwareManager.initialize();
        logger.info("Hardware initialization completed");

        // SyncManager is initialized after the UI is shown so WebSocket/network issues
        // never block application startup.
    }

    private void initializeDatabase(BackupService backupService) {
        DatabaseManager dbManager = DatabaseManager.getInstance();

        try {
            completeDatabaseStartup(dbManager, backupService);
            databaseHealthy = true;
        } catch (Exception e) {
            if (!DatabaseManager.isCorruptionError(e)) {
                logger.error("Database initialization failed", e);
                databaseHealthy = false;
                databaseStatusMessage = "The local database could not be initialized.\n\n" + e.getMessage();
                return;
            }

            logger.error("Database corruption detected, attempting automatic recovery from latest backup", e);
            BackupService.RecoveryResult recoveryResult = backupService.attemptRecoveryFromLatestBackup();
            if (!recoveryResult.isSuccess()) {
                databaseHealthy = false;
                databaseStatusMessage = recoveryResult.getUserMessage();
                return;
            }

            try {
                completeDatabaseStartup(dbManager, backupService);
                databaseHealthy = true;
                databaseRecoveredAutomatically = true;
                databaseStatusMessage = recoveryResult.getUserMessage();
            } catch (Exception retryError) {
                logger.error("Database is still unavailable after automatic recovery", retryError);
                databaseHealthy = false;
                databaseStatusMessage = "The database was corrupted and automatic recovery did not fully succeed.\n\n"
                        + retryError.getMessage();
            }
        }
    }

    private void completeDatabaseStartup(DatabaseManager dbManager, BackupService backupService) throws Exception {
        dbManager.initializeSchema();
        logger.info("Database schema initialized");

        backupService.initialize();
        logger.info("Database backup service initialized");

        // The database is now confirmed open. From here on, any corruption can only
        // surface on a live/background thread (e.g. the scheduled backup or sync),
        // where it would otherwise be silently logged and swallowed. Route it to a
        // blocking prompt so the user restarts and the startup recovery above runs.
        dbManager.setCorruptionListener(this::handleRuntimeDatabaseCorruption);
    }

    /**
     * Invoked (at most once) when the database is found corrupt after a successful
     * open. We do not attempt live in-place recovery while the connection pool and
     * UI may hold in-flight transactions; instead we inform the user with a blocking
     * dialog and close the app, so the next launch restores from the latest backup
     * via {@link #initializeDatabase(BackupService)}.
     */
    private void handleRuntimeDatabaseCorruption(Throwable error) {
        logger.error("Runtime database corruption detected; prompting user to restart for recovery", error);
        databaseHealthy = false;
        javafx.application.Platform.runLater(this::showRuntimeCorruptionDialog);
    }

    private void showRuntimeCorruptionDialog() {
        if (runtimeCorruptionHandled) {
            return;
        }
        runtimeCorruptionHandled = true;

        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Database Error");
        alert.setHeaderText("The local database has become corrupted.");
        alert.setContentText(
                "Pasal POS 2 must close to repair the database. Your data is not lost — "
                        + "the most recent backup will be restored automatically the next time you "
                        + "open Pasal POS 2.\n\nPlease reopen the application to continue.");

        ButtonType closeButton = new ButtonType("Close Pasal POS 2", ButtonBar.ButtonData.OK_DONE);
        ButtonType openBackupsButton = new ButtonType("Open Backup Folder", ButtonBar.ButtonData.HELP);
        alert.getButtonTypes().setAll(openBackupsButton, closeButton);

        // Keep the dialog open when the user just wants to inspect their backups.
        Button openBackupsControl = (Button) alert.getDialogPane().lookupButton(openBackupsButton);
        openBackupsControl.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume();
            openBackupFolder();
        });

        Window ownerWindow = primaryStage != null && primaryStage.isShowing()
                ? primaryStage
                : DialogHelper.getCurrentWindow();
        DialogHelper.setAlertOwner(alert, ownerWindow);
        alert.initModality(Modality.APPLICATION_MODAL);

        alert.showAndWait();

        stop();
        System.exit(1);
    }

    /**
     * Initialize the sync manager with WebSocket-only real-time sync.
     */
    private void initializeSyncManager() {
        syncManager = SyncManager.getInstance();

        // Initialize sync manager (starts WebSocket connection and connectivity
        // monitoring)
        syncManager.initialize();

        logger.info("SyncManager initialized with {} inbound and {} outbound handlers",
                5, 4);
    }

    private void startSyncManagerInBackground(boolean runInitialSync) {
        Thread syncInitThread = new Thread(() -> {
            try {
                initializeSyncManager();
                if (runInitialSync) {
                    triggerInitialSync();
                }
            } catch (Exception e) {
                logger.error("Failed to initialize SyncManager in background", e);
            }
        }, "SyncManagerInit");
        syncInitThread.setDaemon(true);
        syncInitThread.start();
    }

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;

        // If the local database could not be opened (for example corruption that
        // automatic recovery could not fix, or the file being locked by another
        // instance), do NOT try to build the normal UI. The registered/logged-in
        // screens (MainWindow, LoginScreen) query the database while they are being
        // constructed and would throw, which previously left the app running with no
        // window ever shown. Show a dedicated safe-mode error screen instead so the
        // window always opens and the user can recover, retry, or quit.
        if (!databaseHealthy) {
            showDatabaseErrorScreen(primaryStage);
            return;
        }

        startNormalUi(primaryStage);
    }

    private void startNormalUi(Stage primaryStage) {
        try {
            // Initialize customer display early (even before login) to show ads
            initializeCustomerDisplay();

            // Check if device is registered
            DeviceRegistrationService registrationService = DeviceRegistrationService.getInstance();

            // CRITICAL: Check if database file is missing while credentials still exist.
            // If the database was deleted, require fresh registration even if credentials exist.
            // This prevents auto-syncing to old backend setup when database is cleared.
            DatabaseManager dbManager = DatabaseManager.getInstance();
            boolean databaseFileMissing = !dbManager.databaseFileExists();

            if (databaseFileMissing && registrationService.isDeviceRegistered()) {
                // Database file is gone but credentials exist - this means database was deleted
                logger.warn("Database file is missing but device credentials exist. " +
                        "This indicates database was deleted. Clearing credentials to force fresh registration.");
                registrationService.clearRegistrationCredentials();
            }

            boolean isSimulating = "13-inch".equalsIgnoreCase(ConfigManager.getInstance().getProperty("app.screen.simulation"));
            Rectangle2D screenBounds = com.pos.util.ResponsiveHelper.getScreenBounds();

            Scene scene;
            boolean shouldVerifyRegisteredDevice = false;
            boolean shouldRunInitialSync = false;
            if (!registrationService.isDeviceRegistered()) {
                // Show registration screen
                logger.info("Device not registered, showing registration screen");
                DeviceRegistrationScreen registrationScreen = new DeviceRegistrationScreen(primaryStage);
                scene = new Scene(registrationScreen, screenBounds.getWidth(), screenBounds.getHeight());
                primaryStage.setTitle("Pasal POS 2 - Device Registration");
            } else {
                shouldVerifyRegisteredDevice = true;

                // Device has local credentials - choose startup UI from local state first so
                // the app opens immediately even if the backend is unavailable.
                UserAuthService authService = UserAuthService.getInstance();

                if (!authService.isLoggedIn() || !authService.isSessionValid()) {
                    logger.info("Device registered locally, showing login screen");

                    LoginScreen loginScreen = new LoginScreen(primaryStage);
                    scene = new Scene(loginScreen, screenBounds.getWidth(), screenBounds.getHeight());
                    primaryStage.setTitle("Pasal POS 2 - Login");
                } else {
                    logger.info("Device registered locally and user session restored, showing main window");
                    MainWindow mainWindow = new MainWindow(hardwareManager, primaryStage);
                    scene = new Scene(mainWindow, screenBounds.getWidth(), screenBounds.getHeight());
                    primaryStage.setTitle("Pasal POS 2 - Point of Sale");

                    // Trigger initial sync after SyncManager starts (see below)
                    shouldRunInitialSync = true;

                    // Clean up expired held sales on startup
                    cleanupExpiredHeldSales();

                    // Start day change monitor
                    com.pos.service.DayChangeMonitorService.getInstance().start();
                }
            }

            applySceneStyles(scene);

            // Set application icon
            try {
                primaryStage.getIcons().add(new javafx.scene.image.Image(
                        getClass().getResourceAsStream("/images/app_icon.png")));

                // macOS Dock Icon Support
                setDockIcon();
            } catch (Exception e) {
                logger.warn("Failed to load application icon", e);
            }

            primaryStage.setScene(scene);
            KeyboardManager.initialize(primaryStage);

            // Configure for fullscreen mode or simulation mode
            if (isSimulating) {
                primaryStage.setFullScreen(false);
                primaryStage.setResizable(true);
                primaryStage.setWidth(1440);
                primaryStage.setHeight(900);
                primaryStage.centerOnScreen();
            } else {
                primaryStage.setFullScreenExitKeyCombination(javafx.scene.input.KeyCombination.NO_MATCH);
                primaryStage.setFullScreenExitHint(""); // Remove exit hint
            }

            // Prevent fullscreen exit by listening to fullscreen property changes (if not simulating)
            if (!isSimulating) {
                primaryStage.fullScreenProperty().addListener((obs, wasFullScreen, isNowFullScreen) -> {
                    if (!isNowFullScreen && wasFullScreen) {
                        // Force back to fullscreen if user tries to exit
                        javafx.application.Platform.runLater(() -> {
                            primaryStage.setFullScreen(true);
                        });
                    }
                });
            }

            primaryStage.setOnCloseRequest(e -> {
                stop();
                System.exit(0);
            });

            // Maximize window first to get proper dimensions (if not simulating)
            if (!isSimulating) {
                primaryStage.setMaximized(true);
            }

            // Show stage first, then set fullscreen (required for macOS)
            primaryStage.show();
            primaryStage.toFront();

            startSyncManagerInBackground(shouldRunInitialSync);

            showDatabaseStatusIfNeeded();

            // Check for updates
            checkForUpdates();

            if (shouldVerifyRegisteredDevice) {
                verifyRegisteredDeviceInBackground(registrationService, screenBounds);
            }

            // Set fullscreen after showing (required for proper fullscreen on macOS)
            // Use a small delay to ensure the stage is fully shown and no dialogs are
            // active
            if (!isSimulating) {
                javafx.application.Platform.runLater(() -> {
                    PauseTransition delay = new PauseTransition(Duration.millis(100));
                    delay.setOnFinished(e -> {
                        try {
                            if (primaryStage.isShowing() && !primaryStage.isFullScreen()) {
                                primaryStage.setFullScreen(true);
                                primaryStage.toFront();
                                primaryStage.requestFocus();
                            }
                        } catch (IllegalStateException ex) {
                            logger.warn("Could not set fullscreen immediately, will retry", ex);
                            // Retry once more after a longer delay
                            javafx.application.Platform.runLater(() -> {
                                try {
                                    if (primaryStage.isShowing() && !primaryStage.isFullScreen()) {
                                        primaryStage.setFullScreen(true);
                                    }
                                } catch (Exception ex2) {
                                    logger.warn("Failed to set fullscreen after retry, continuing without fullscreen",
                                            ex2);
                                }
                            });
                        }
                    });
                    delay.play();
                });
            }

            logger.info("Application started successfully");
        } catch (Exception e) {
            logger.error("Failed to start application", e);
            e.printStackTrace();
        }
    }

    @Override
    public void stop() {
        // Shutdown Customer Display
        try {
            CustomerDisplayService.getInstance().shutdown();
            logger.info("Customer display shutdown completed");
        } catch (Exception e) {
            logger.error("Error during Customer display shutdown", e);
        }

        // Shutdown sync manager
        if (syncManager != null) {
            try {
                syncManager.shutdown();
                logger.info("SyncManager shutdown completed");
            } catch (Exception e) {
                logger.error("Error during SyncManager shutdown", e);
            }
        }

        // Shutdown backup scheduler
        try {
            BackupService.getInstance().shutdown();
            logger.info("Backup service shutdown completed");
        } catch (Exception e) {
            logger.error("Error during backup service shutdown", e);
        }

        // Cleanup hardware on exit
        if (hardwareManager != null) {
            try {
                hardwareManager.cleanup();
                logger.info("Hardware cleaned up successfully");
            } catch (Exception e) {
                logger.error("Error during hardware cleanup", e);
            }
        }

        // Stop day change monitor
        com.pos.service.DayChangeMonitorService.getInstance().stop();
    }

    /**
     * Initialize customer display early in application startup.
     * This allows ads to be displayed even when user is not logged in.
     */
    private void initializeCustomerDisplay() {
        try {
            // Initialize with empty cart items - will show ads when cart is empty
            CustomerDisplayService displayService = CustomerDisplayService.getInstance();
            displayService.initialize(FXCollections.observableArrayList());
            logger.info("Customer display initialized for ad display");
        } catch (Exception e) {
            logger.warn("Failed to initialize customer display on startup", e);
            // Don't fail startup if customer display initialization fails
        }
    }

    private void verifyRegisteredDeviceInBackground(DeviceRegistrationService registrationService, Rectangle2D screenBounds) {
        Thread verificationThread = new Thread(() -> {
            try {
                int startupTimeoutMs = ConfigManager.getInstance()
                        .getIntProperty("backend.api.timeout.startup", 4000);
                DeviceRegistrationService.VerificationResult verificationResult = registrationService
                        .verifyDeviceRegistrationWithBackend(startupTimeoutMs);

                switch (verificationResult) {
                    case VERIFIED:
                        logger.info("Device registration verified in background");
                        // The device is online — refresh the subscription lease so
                        // offline access tracks paidThrough, and block startup when
                        // the server explicitly reports the subscription unpaid.
                        SubscriptionLeaseService.RefreshResult leaseRefresh = SubscriptionLeaseService
                                .getInstance().refreshLease();
                        if (leaseRefresh == SubscriptionLeaseService.RefreshResult.NO_ACCESS) {
                            logger.warn("Store subscription inactive per backend — blocking startup");
                            javafx.application.Platform.runLater(() -> showSubscriptionRequiredScreen(
                                    screenBounds, "The store subscription is not active."));
                            break;
                        }
                        refreshStoreInfoInBackground(startupTimeoutMs);
                        break;
                    case NETWORK_ERROR:
                        logger.warn("Backend unavailable during startup verification. Continuing in offline mode.");
                        // Offline boot is only allowed while a verified signed
                        // subscription lease permits offline access.
                        SubscriptionLeaseService.LeaseCheckResult leaseCheck = SubscriptionLeaseService
                                .getInstance().isOfflineAccessAllowed();
                        if (leaseCheck != SubscriptionLeaseService.LeaseCheckResult.ALLOWED) {
                            logger.warn("Offline startup blocked — lease check result: {}", leaseCheck);
                            javafx.application.Platform.runLater(() -> showSubscriptionRequiredScreen(
                                    screenBounds,
                                    SubscriptionLeaseService.describeBlockReason(leaseCheck)));
                        }
                        break;
                    case INVALID_CREDENTIALS:
                    case NOT_REGISTERED:
                    default:
                        logger.warn("Device registration invalid. Returning to registration screen.");
                        handleInvalidDeviceRegistration(registrationService, screenBounds);
                        break;
                }
            } catch (Exception e) {
                logger.warn("Background device verification failed, continuing with local startup", e);
            }
        }, "StartupDeviceVerification");
        verificationThread.setDaemon(true);
        verificationThread.start();
    }

    private void refreshStoreInfoInBackground(int timeoutMs) {
        Thread storeInfoThread = new Thread(() -> {
            try {
                StoreService.getInstance().fetchStoreInfo(timeoutMs);

                javafx.application.Platform.runLater(() -> {
                    if (primaryStage == null || primaryStage.getScene() == null) {
                        return;
                    }

                    if (primaryStage.getScene().getRoot() instanceof LoginScreen loginScreen) {
                        loginScreen.refreshStoreName();
                    }
                });
            } catch (Exception e) {
                logger.debug("Store information refresh skipped during startup: {}", e.getMessage());
            }
        }, "StartupStoreInfoRefresh");
        storeInfoThread.setDaemon(true);
        storeInfoThread.start();
    }

    private void handleInvalidDeviceRegistration(DeviceRegistrationService registrationService, Rectangle2D screenBounds) {
        try {
            UserAuthService authService = UserAuthService.getInstance();
            if (authService.isLoggedIn()) {
                authService.logout();
            }
        } catch (Exception e) {
            logger.warn("Failed to clear user session while handling invalid device registration", e);
        }

        registrationService.clearRegistrationCredentials();
        com.pos.service.DayChangeMonitorService.getInstance().stop();

        javafx.application.Platform.runLater(() -> {
            DeviceRegistrationScreen registrationScreen = new DeviceRegistrationScreen(primaryStage);
            Scene registrationScene = new Scene(registrationScreen, screenBounds.getWidth(), screenBounds.getHeight());
            applySceneStyles(registrationScene);
            primaryStage.setScene(registrationScene);
            primaryStage.setTitle("Pasal POS 2 - Device Registration");
            primaryStage.show();
            primaryStage.toFront();
        });
    }

    /**
     * Blocking screen shown when the POS may not operate: the backend reported the
     * store subscription as inactive, or the device booted offline without a valid
     * signed subscription lease. The only way forward is to connect to the
     * internet and renew/verify the subscription (Retry), or quit.
     */
    private void showSubscriptionRequiredScreen(Rectangle2D screenBounds, String reason) {
        Label title = new Label("Store Subscription Required");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #b00020;");

        Label subtitle = new Label(
                "This device cannot start selling because the store subscription is not active "
                        + "or the offline access lease could not be verified.");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(640);
        subtitle.setStyle("-fx-font-size: 14px;");

        StringBuilder detailsText = new StringBuilder();
        if (reason != null && !reason.isBlank()) {
            detailsText.append(reason).append('\n');
        }
        try {
            detailsText.append(SubscriptionLeaseService.getInstance().statusSummary());
        } catch (Exception ignored) {
        }
        detailsText.append("\nConnect this device to the internet and press Retry once the "
                + "subscription has been renewed.");

        TextArea details = new TextArea(detailsText.toString());
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefRowCount(6);
        details.setMaxWidth(640);

        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #555555;");

        Button retryButton = new Button("Retry");
        Button quitButton = new Button("Quit");

        retryButton.setOnAction(e -> {
            retryButton.setDisable(true);
            statusLabel.setText("Checking subscription status...");
            Thread retryThread = new Thread(() -> {
                DeviceRegistrationService registrationService = DeviceRegistrationService.getInstance();
                int timeoutMs = ConfigManager.getInstance()
                        .getIntProperty("backend.api.timeout.startup", 4000);
                boolean canProceed = false;
                String failureMessage = "Still unable to confirm an active subscription.";
                try {
                    DeviceRegistrationService.VerificationResult verificationResult = registrationService
                            .verifyDeviceRegistrationWithBackend(timeoutMs);
                    SubscriptionLeaseService leaseService = SubscriptionLeaseService.getInstance();
                    if (verificationResult == DeviceRegistrationService.VerificationResult.VERIFIED) {
                        SubscriptionLeaseService.RefreshResult refreshResult = leaseService.refreshLease();
                        canProceed = refreshResult == SubscriptionLeaseService.RefreshResult.REFRESHED
                                || refreshResult == SubscriptionLeaseService.RefreshResult.ONLINE_ONLY;
                        if (!canProceed) {
                            failureMessage = "The store subscription is still not active. "
                                    + "Renew it, then press Retry.";
                        }
                    } else if (verificationResult == DeviceRegistrationService.VerificationResult.NETWORK_ERROR) {
                        canProceed = leaseService.isOfflineAccessAllowed()
                                == SubscriptionLeaseService.LeaseCheckResult.ALLOWED;
                        if (!canProceed) {
                            failureMessage = "Still offline and no valid subscription lease. "
                                    + "Connect to the internet to renew, then press Retry.";
                        }
                    } else {
                        failureMessage = "Device registration is no longer valid — restart the app to re-register.";
                    }
                } catch (Exception ex) {
                    logger.warn("Subscription retry check failed", ex);
                }

                final boolean proceed = canProceed;
                final String message = failureMessage;
                javafx.application.Platform.runLater(() -> {
                    if (proceed) {
                        logger.info("Subscription confirmed after retry — loading application UI");
                        startNormalUi(primaryStage);
                    } else {
                        statusLabel.setText(message);
                        retryButton.setDisable(false);
                    }
                });
            }, "SubscriptionRetry");
            retryThread.setDaemon(true);
            retryThread.start();
        });

        quitButton.setOnAction(e -> {
            stop();
            System.exit(1);
        });

        HBox buttons = new HBox(12, retryButton, quitButton);
        buttons.setAlignment(Pos.CENTER);

        VBox root = new VBox(16, title, subtitle, details, buttons, statusLabel);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(40));
        root.setStyle("-fx-background-color: white;");

        Rectangle2D bounds = screenBounds != null
                ? screenBounds
                : com.pos.util.ResponsiveHelper.getScreenBounds();
        Scene scene = new Scene(root, bounds.getWidth(), bounds.getHeight());
        applySceneStyles(scene);

        primaryStage.setTitle("Pasal POS 2 - Subscription Required");
        primaryStage.setScene(scene);
        primaryStage.show();
        primaryStage.toFront();
    }

    private void applySceneStyles(Scene scene) {
        try {
            scene.getStylesheets().add(
                    getClass().getResource("/styles/application.css").toExternalForm());
        } catch (Exception e) {
            logger.warn("Stylesheet not found, using default styles");
        }
    }

    /**
     * Trigger initial sync when application starts with logged-in user.
     * Uses the new SyncManager for coordinated sync.
     */
    private void triggerInitialSync() {
        Thread syncThread = new Thread(() -> {
            try {
                logger.info("Starting initial sync...");

                // Fetch store settings first (lightweight)
                try {
                    SettingsService settingsService = SettingsService.getInstance();
                    settingsService.fetchSettings();
                    logger.info("Store settings loaded");
                } catch (Exception e) {
                    logger.warn("Failed to load store settings, continuing anyway", e);
                }

                // Perform full inbound sync (products, users from backend)
                // This uses the SyncManager's coordinated sync with retry logic
                var inboundResult = syncManager.performFullInboundSync();

                if (inboundResult.isSuccess()) {
                    logger.info("Initial inbound sync completed: {} items synced", inboundResult.getSynced());
                } else {
                    logger.warn("Initial inbound sync had issues: {}", inboundResult.getError());
                    logger.info("Background sync will retry automatically");
                }

                // Perform outbound sync (push any pending local data)
                var outboundResult = syncManager.performOutboundSync();

                if (outboundResult.hasItems()) {
                    logger.info("Initial outbound sync completed: {} items synced", outboundResult.getSynced());
                }

            } catch (Exception e) {
                logger.error("Initial sync failed with exception", e);
            } finally {
                logger.info("Initial sync thread completed");
            }
        });

        syncThread.setName("InitialSyncThread");
        syncThread.setDaemon(true); // Don't prevent JVM shutdown
        syncThread.start();
    }

    /**
     * Clean up expired held sales on startup
     */
    private void cleanupExpiredHeldSales() {
        Thread cleanupThread = new Thread(() -> {
            try {
                SalesService salesService = SalesService.getInstance();
                int cleaned = salesService.cleanupExpiredHeldSales();
                if (cleaned > 0) {
                    logger.info("Cleaned up {} expired held sales on startup", cleaned);
                }
            } catch (Exception e) {
                logger.warn("Error cleaning up expired held sales", e);
            }
        }, "HeldSalesCleanupThread");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    /**
     * Check for application updates
     */
    private void checkForUpdates() {
        Thread updateThread = new Thread(() -> {
            try {
                UpdateService updateService = UpdateService.getInstance();
                UpdateService.UpdateInfo updateInfo = updateService.checkForUpdates();

                if (updateInfo != null) {
                    // Defer the dialog until after any animations complete
                    // Use a background thread to wait, then show dialog on JavaFX thread
                    new Thread(() -> {
                        try {
                            Thread.sleep(1500); // Wait for any animations to complete
                            attemptShowUpdateDialog(updateInfo, 0);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                }
            } catch (Exception e) {
                logger.error("Failed to check for updates", e);
            }
        });
        updateThread.setName("UpdateCheckThread");
        updateThread.setDaemon(true);
        updateThread.start();
    }

    private void attemptShowUpdateDialog(UpdateService.UpdateInfo updateInfo, int retryCount) {
        javafx.application.Platform.runLater(() -> {
            try {
                promptForUpdate(updateInfo);
            } catch (IllegalStateException e) {
                if (retryCount < 5) {
                    logger.warn("Dialog blocked by animation (attempt {}), will retry: {}", retryCount + 1,
                            e.getMessage());
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000); // Wait 1 second before retry
                            attemptShowUpdateDialog(updateInfo, retryCount + 1);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                } else {
                    logger.warn(
                            "Failed to show update dialog after 5 retries due to animation/layout processing blocking.");
                }
            }
        });
    }

    private void promptForUpdate(UpdateService.UpdateInfo updateInfo) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Update Available");
        alert.setHeaderText("A new version of Pasal POS 2 is available: " + updateInfo.version);
        alert.setContentText(
                "Release Notes:\n" + updateInfo.releaseNotes + "\n\nDo you want to download and install it now?");

        ButtonType updateButton = new ButtonType("Update Now");
        ButtonType laterButton = new ButtonType("Later");

        alert.getButtonTypes().setAll(updateButton, laterButton);

        // Set owner window to ensure dialog appears within the main application window
        Window ownerWindow = primaryStage != null && primaryStage.isShowing()
                ? primaryStage
                : DialogHelper.getCurrentWindow();
        DialogHelper.setAlertOwner(alert, ownerWindow);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == updateButton) {
            downloadAndInstallUpdate(updateInfo);
        }
    }

    private void downloadAndInstallUpdate(UpdateService.UpdateInfo updateInfo) {
        Dialog<Void> progressDialog = new Dialog<>();
        progressDialog.setTitle("Downloading Update");
        progressDialog.initModality(Modality.APPLICATION_MODAL);
        progressDialog.initStyle(StageStyle.UTILITY);

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        Label statusLabel = new Label("Starting download...");

        VBox content = new VBox(10, statusLabel, progressBar);
        content.setPadding(new javafx.geometry.Insets(20));
        progressDialog.getDialogPane().setContent(content);
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        // Set owner window to ensure dialog appears within the main application window
        Window ownerWindow = primaryStage != null && primaryStage.isShowing()
                ? primaryStage
                : DialogHelper.getCurrentWindow();
        DialogHelper.setDialogOwner(progressDialog, ownerWindow);

        progressDialog.show();

        Thread downloadThread = new Thread(() -> {
            try {
                Path filePath = UpdateService.getInstance().downloadUpdate(updateInfo, progress -> {
                    javafx.application.Platform.runLater(() -> {
                        progressBar.setProgress(progress);
                        statusLabel.setText(String.format("Downloading... %.0f%%", progress * 100));
                    });
                });

                javafx.application.Platform.runLater(() -> {
                    progressDialog.close();
                    try {
                        logger.info("Launching installer: " + filePath);
                        UpdateService.getInstance().installUpdate(filePath);

                        // Wait briefly to ensure installer starts, then exit to allow replacement
                        // This gives the "auto restart" feel as the installer will take over
                        new Thread(() -> {
                            try {
                                Thread.sleep(2000); // 2 seconds delay
                                logger.info("Exiting application for update...");
                                javafx.application.Platform.runLater(() -> {
                                    stop();
                                    System.exit(0);
                                });
                            } catch (Exception ignored) {
                            }
                        }).start();

                    } catch (Exception e) {
                        logger.error("Failed to install update", e);
                        showError("Update Failed", "Failed to launch installer: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                logger.error("Update preparation failed", e);
                javafx.application.Platform.runLater(() -> {
                    progressDialog.close();
                    showError("Update Failed", "Failed to prepare update: " + e.getMessage());
                });
            }
        });
        downloadThread.setName("UpdateDownloadThread");
        downloadThread.start();
    }

    private void showError(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setContentText(content);

        // Set owner window to ensure dialog appears within the main application window
        Window ownerWindow = primaryStage != null && primaryStage.isShowing()
                ? primaryStage
                : DialogHelper.getCurrentWindow();
        DialogHelper.setAlertOwner(alert, ownerWindow);

        alert.showAndWait();
    }

    /**
     * Shows a dedicated full-screen error UI when the local database could not be
     * opened. This guarantees the application window always appears even when the
     * database is corrupted/unavailable, so the user is informed and can retry
     * recovery, open the backup folder, or quit cleanly. Without this the app would
     * silently fail to show any window when the database crashed.
     */
    private void showDatabaseErrorScreen(Stage primaryStage) {
        logger.warn("Starting in database safe mode: {}", databaseStatusMessage);

        Label title = new Label("The database could not be opened");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #b00020;");

        Label subtitle = new Label(
                "Pasal POS 2 started in safe mode because the local database is unavailable. "
                        + "Your data has not been lost. Try again, or restore from a backup.");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(640);
        subtitle.setStyle("-fx-font-size: 14px;");

        TextArea details = new TextArea(databaseStatusMessage != null
                ? databaseStatusMessage
                : "The local database could not be opened.");
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefRowCount(6);
        details.setMaxWidth(640);

        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: #555555;");

        Button retryButton = new Button("Retry");
        Button openBackupsButton = new Button("Open Backup Folder");
        Button quitButton = new Button("Quit");

        retryButton.setOnAction(e -> {
            retryButton.setDisable(true);
            openBackupsButton.setDisable(true);
            statusLabel.setText("Retrying database recovery...");
            Thread retryThread = new Thread(() -> {
                try {
                    initializeDatabase(BackupService.getInstance());
                } catch (Exception ex) {
                    logger.error("Retry of database initialization failed", ex);
                }
                javafx.application.Platform.runLater(() -> {
                    if (databaseHealthy) {
                        logger.info("Database became healthy after retry, loading application UI");
                        startNormalUi(primaryStage);
                    } else {
                        details.setText(databaseStatusMessage != null
                                ? databaseStatusMessage
                                : "The local database could not be opened.");
                        statusLabel.setText("Still unable to open the database. "
                                + "Restore a backup or contact support.");
                        retryButton.setDisable(false);
                        openBackupsButton.setDisable(false);
                    }
                });
            }, "DatabaseRetry");
            retryThread.setDaemon(true);
            retryThread.start();
        });

        openBackupsButton.setOnAction(e -> openBackupFolder());

        quitButton.setOnAction(e -> {
            stop();
            System.exit(1);
        });

        HBox buttons = new HBox(12, retryButton, openBackupsButton, quitButton);
        buttons.setAlignment(Pos.CENTER);

        VBox root = new VBox(16, title, subtitle, details, buttons, statusLabel);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(40));
        root.setStyle("-fx-background-color: white;");

        Rectangle2D screenBounds = com.pos.util.ResponsiveHelper.getScreenBounds();
        Scene scene = new Scene(root, screenBounds.getWidth(), screenBounds.getHeight());
        applySceneStyles(scene);

        try {
            primaryStage.getIcons().add(new javafx.scene.image.Image(
                    getClass().getResourceAsStream("/images/app_icon.png")));
        } catch (Exception ex) {
            logger.warn("Failed to load application icon", ex);
        }

        primaryStage.setTitle("Pasal POS 2 - Database Error");
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> {
            stop();
            System.exit(1);
        });
        primaryStage.setMaximized(true);
        primaryStage.show();
        primaryStage.toFront();
    }

    /**
     * Opens the local backup directory in the OS file browser so the user can inspect
     * or copy backups when recovering from a database failure. Runs off the JavaFX
     * thread because {@link java.awt.Desktop#open} can block on some platforms.
     */
    private void openBackupFolder() {
        Thread opener = new Thread(() -> {
            try {
                Path dir = BackupService.getInstance().getBackupDirectory();
                if (dir == null) {
                    logger.warn("No backup directory configured; cannot open backup folder");
                    return;
                }
                java.io.File folder = dir.toFile();
                if (!folder.exists()) {
                    folder.mkdirs();
                }
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                    java.awt.Desktop.getDesktop().open(folder);
                } else {
                    logger.warn("Desktop open is not supported on this platform: {}", folder);
                }
            } catch (Exception e) {
                logger.warn("Failed to open backup folder", e);
            }
        }, "OpenBackupFolder");
        opener.setDaemon(true);
        opener.start();
    }

    private void showDatabaseStatusIfNeeded() {
        if (databaseStatusMessage == null) {
            return;
        }

        javafx.application.Platform.runLater(() -> {
            Alert alert = new Alert(databaseRecoveredAutomatically
                    ? Alert.AlertType.WARNING
                    : Alert.AlertType.ERROR);
            alert.setTitle(databaseRecoveredAutomatically ? "Database Restored" : "Database Error");
            alert.setHeaderText(databaseRecoveredAutomatically
                    ? "Your local database was corrupted and has been restored from backup."
                    : (databaseHealthy
                            ? "The local database reported a problem."
                            : "The local database could not be opened."));
            alert.setContentText(databaseStatusMessage);

            Window ownerWindow = primaryStage != null && primaryStage.isShowing()
                    ? primaryStage
                    : DialogHelper.getCurrentWindow();
            DialogHelper.setAlertOwner(alert, ownerWindow);
            alert.showAndWait();
        });
    }

    /**
     * Set the Dock icon for macOS
     */
    private void setDockIcon() {
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            try {
                URL iconUrl = getClass().getResource("/images/app_icon.png");
                if (iconUrl != null && Taskbar.isTaskbarSupported()
                        && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    java.awt.Image image = Toolkit.getDefaultToolkit().getImage(iconUrl);
                    Taskbar.getTaskbar().setIconImage(image);
                    logger.info("Set macOS Dock icon successfully");
                }
            } catch (Exception e) {
                logger.warn("Failed to set macOS Dock icon", e);
            }
        }
    }

    public static void main(String[] args) {
        // Force JavaFX to render at 1:1 pixel scale regardless of Windows display scaling
        // This prevents the UI from being oversized at 125%/150% etc.
        System.setProperty("glass.win.uiScale", "1.0");
        System.setProperty("prism.allowHiDPIScaling", "false");

        // Fix for Windows SSL Handshake failure: Use Windows Trust Store
        // This allows the app to trust system certificates (like those from Antivirus
        // or corporate proxies)
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try {
                java.security.KeyStore.getInstance("WINDOWS-ROOT");
                System.setProperty("javax.net.ssl.trustStoreType", "WINDOWS-ROOT");
            } catch (java.security.KeyStoreException e) {
                System.err.println("WINDOWS-ROOT keystore unavailable on this JVM; using default trust store");
            }
        }

        launch(args);
    }
}
