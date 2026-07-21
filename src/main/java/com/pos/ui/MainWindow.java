package com.pos.ui;

import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Screen;
import javafx.stage.Stage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import javafx.application.Platform;
import com.pos.model.SaleItem;
import com.pos.hardware.HardwareManager;
import com.pos.service.ProductSyncService;
import com.pos.service.ShiftService;
import com.pos.service.OfflineSyncService;
import com.pos.service.UserAuthService;
import com.pos.service.RoleBasedAccessService;
import com.pos.service.EmployeeShiftService;
import com.pos.service.DayChangeMonitorService;
import com.pos.database.DatabaseManager;
import com.pos.api.dto.PosUserLoginResponse;
import com.pos.api.dto.ShiftResponse;
import com.pos.model.EmployeeShift;
import com.pos.ui.keyboard.KeyboardManager;
import com.pos.util.DialogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main application window
 */
public class MainWindow extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(MainWindow.class);
    private HardwareManager hardwareManager;
    private SalesScreen salesScreen;
    private PaymentScreen paymentScreen; // cached and reused across checkouts
    private Label statusBar;
    private Label shiftStatusLabel;
    private Label syncStatusLabel;
    private Label userLabel;
    private Stage stage;
    private UserAuthService authService;
    private RoleBasedAccessService rbacService;
    private MenuBar menuBar;
    private volatile boolean menuBarHidden = false;

    public MainWindow(HardwareManager hardwareManager) {
        this(hardwareManager, null);
    }

    public MainWindow(HardwareManager hardwareManager, Stage stage) {
        logger.info("MainWindow constructor started");
        this.hardwareManager = hardwareManager;
        this.stage = stage;
        this.authService = UserAuthService.getInstance();
        this.rbacService = RoleBasedAccessService.getInstance();

        // Ensure database schema is initialized
        try {
            DatabaseManager dbManager = DatabaseManager.getInstance();
            dbManager.initializeSchema();
            logger.debug("Database schema check completed");
        } catch (Exception e) {
            logger.warn("Database schema initialization check failed", e);
        }

        // Register logout listener
        authService.addLogoutListener(() -> {
            Platform.runLater(this::performLogout);
        });

        logger.debug("Starting UI initialization");
        initializeUI();
        logger.info("MainWindow initialization completed");

        // Initialize global floating keyboard with this window as owner
        if (stage != null) {
            KeyboardManager.initialize(stage);
        } else {
            // Fallback: initialize once the scene is available
            Platform.runLater(() -> {
                Scene scene = getScene();
                if (scene != null && scene.getWindow() != null) {
                    KeyboardManager.initialize(scene.getWindow());
                }
            });
        }
    }

    private void initializeUI() {
        // Ensure BorderPane fills entire screen
        setStyle("-fx-background-color: #f5f7fa;");
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);
        setPrefWidth(Double.MAX_VALUE);
        setPrefHeight(Double.MAX_VALUE);

        // Top menu bar
        menuBar = createMenuBar();
        setTop(menuBar);

        // Refresh menu bar after a short delay to ensure permissions are loaded
        // This handles cases where MainWindow is created before permissions are fully
        // synced
        Thread menuRefreshThread = new Thread(() -> {
            try {
                // Small delay to ensure permissions are loaded from database
                Thread.sleep(300);
                Platform.runLater(this::refreshMenuBar);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.warn("Failed to refresh menu bar after initialization", e);
            }
        }, "MainWindowMenuRefresh");
        menuRefreshThread.setDaemon(true);
        menuRefreshThread.start();

        // Center - Initial screen logic
        salesScreen = new SalesScreen(hardwareManager);
        setupSalesScreenNavigation();
        
        // Check if we should show StartShiftScreen or SalesScreen
        checkShiftAndShowInitialScreen();

        // Bottom - Status bar with user, shift and sync status
        HBox statusBox = new HBox(15);
        statusBox.setPadding(new Insets(8, 15, 8, 15));
        statusBox.setStyle("-fx-background-color: #f0f0f0;");
        statusBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        statusBar = new Label("System ready");
        statusBar.setStyle("-fx-font-size: 14px;");

        userLabel = new Label();
        updateUserLabel();
        userLabel.setStyle("-fx-font-size: 14px;");

        shiftStatusLabel = new Label("Shift: None");
        shiftStatusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #666;");

        syncStatusLabel = new Label("Sync: Online");
        syncStatusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #4CAF50;");

        // Add separators with proper styling
        Separator sep1 = new Separator();
        sep1.setOrientation(javafx.geometry.Orientation.VERTICAL);
        sep1.setPrefHeight(20);

        Separator sep2 = new Separator();
        sep2.setOrientation(javafx.geometry.Orientation.VERTICAL);
        sep2.setPrefHeight(20);

        Separator sep3 = new Separator();
        sep3.setOrientation(javafx.geometry.Orientation.VERTICAL);
        sep3.setPrefHeight(20);

        statusBox.getChildren().addAll(
                statusBar, sep1,
                userLabel, sep2,
                shiftStatusLabel, sep3,
                syncStatusLabel);

        // Make status bar fill width
        HBox.setHgrow(statusBox, javafx.scene.layout.Priority.ALWAYS);
        setBottom(statusBox);

        // Update status
        updateStatus("System ready");

        // Start periodic status updates
        startStatusUpdates();

        // Apply auto-scaling if enabled
        double scaleFactor = com.pos.util.ResponsiveHelper.getScaleFactor();
        if (scaleFactor != 1.0) {
            this.setScaleX(scaleFactor);
            this.setScaleY(scaleFactor);
            // Translate to top-left to keep layout aligned
            this.setTranslateX((scaleFactor - 1.0) * getPrefWidth() / 2.0);
            this.setTranslateY((scaleFactor - 1.0) * getPrefHeight() / 2.0);
            logger.info("Applied auto-scale factor: {}", scaleFactor);
        }
    }

    private MenuBar createMenuBar() {
        MenuBar menuBar = new MenuBar();

        // File Menu (available to all)
        Menu fileMenu = new Menu("File");
        MenuItem logoutItem = new MenuItem("Logout");
        logoutItem.setOnAction(e -> handleLogout());
        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setOnAction(e -> System.exit(0));
        fileMenu.getItems().addAll(logoutItem, new SeparatorMenuItem(), exitItem);

        // Sales Menu (available to all)
        Menu salesMenu = new Menu("Sales");
        MenuItem newSaleItem = new MenuItem("New Sale");
        newSaleItem.setOnAction(e -> {
            showSalesScreen();
            salesScreen.startNewSale();
        });
        MenuItem saleHistoryItem = new MenuItem("Sale History & Lookup");
        saleHistoryItem.setOnAction(e -> showSaleHistoryView());
        // Void Transaction - Manager+ only
        if (rbacService.hasPermission(RoleBasedAccessService.PERMISSION_VOID_TRANSACTION)) {
            MenuItem voidItem = new MenuItem("Void Transaction");
            voidItem.setOnAction(e -> {
                // Void transaction will be handled in SaleHistoryView with permission check
                showSaleHistoryView();
            });
            salesMenu.getItems().addAll(newSaleItem, saleHistoryItem, new SeparatorMenuItem(), voidItem);
        } else {
            salesMenu.getItems().addAll(newSaleItem, saleHistoryItem);
        }

        boolean canManageInventory = rbacService.hasPermission(RoleBasedAccessService.PERMISSION_MANAGE_INVENTORY);
        boolean canManageProducts = rbacService.hasPermission(RoleBasedAccessService.PERMISSION_MANAGE_PRODUCTS);

        // Inventory Menu
        if (canManageInventory || canManageProducts) {
            Menu inventoryMenu = new Menu("Inventory");

            if (canManageInventory) {
                MenuItem inventoryViewItem = new MenuItem("Inventory Management");
                inventoryViewItem.setOnAction(e -> showInventoryView());
                inventoryMenu.getItems().add(inventoryViewItem);
            }

            if (canManageProducts) {
                MenuItem productManagementItem = new MenuItem("Product Management");
                productManagementItem.setOnAction(e -> showProductManagement());
                MenuItem departmentManagementItem = new MenuItem("Department Management");
                departmentManagementItem.setOnAction(e -> showDepartmentManagement());
                MenuItem syncProductsItem = new MenuItem("Sync Products");
                syncProductsItem.setOnAction(e -> syncProducts());

                if (!inventoryMenu.getItems().isEmpty()) {
                    inventoryMenu.getItems().add(new SeparatorMenuItem());
                }
                inventoryMenu.getItems().addAll(productManagementItem, departmentManagementItem, syncProductsItem);
            }

            menuBar.getMenus().add(inventoryMenu);
        }

        // Reports Menu - Manager+ only
        if (rbacService.hasPermission(RoleBasedAccessService.PERMISSION_VIEW_REPORTS)) {
            Menu reportsMenu = new Menu("Reports");
            MenuItem reportsViewItem = new MenuItem("Reports & Dashboard");
            reportsViewItem.setOnAction(e -> showReportsView());
            MenuItem salesReportItem = new MenuItem("Sales Report");
            salesReportItem.setOnAction(e -> showSalesReportView());
            reportsMenu.getItems().addAll(reportsViewItem, salesReportItem);
            
            if (rbacService.hasPermission(RoleBasedAccessService.PERMISSION_VIEW_SHIFT_REPORT)) {
                reportsMenu.getItems().add(new SeparatorMenuItem());
                MenuItem shiftReportItem = new MenuItem("Employee Shift Report");
                shiftReportItem.setOnAction(e -> showShiftReportView());
                reportsMenu.getItems().add(shiftReportItem);
            }
            
            menuBar.getMenus().add(reportsMenu);
        }

        // Shift Menu (available to all - view only for Cashier)
        Menu shiftMenu = new Menu("Shift");
        MenuItem shiftManagementItem = new MenuItem("Shift Management");
        shiftManagementItem.setOnAction(e -> showShiftScreen());
        shiftMenu.getItems().add(shiftManagementItem);
        menuBar.getMenus().add(shiftMenu);

        // Settings Menu - Admin only
        if (rbacService.isAdmin()) {
            Menu settingsMenu = new Menu("Settings");
            MenuItem settingsItem = new MenuItem("Settings & Configuration");
            settingsItem.setOnAction(e -> showSettingsScreen());
            MenuItem employeeManagementItem = new MenuItem("Employee Management");
            employeeManagementItem.setOnAction(e -> showEmployeeManagement());
            MenuItem permissionsManagementItem = new MenuItem("Permissions Management");
            permissionsManagementItem.setOnAction(e -> showPermissionsManagement());
            MenuItem hardwareSettingsItem = new MenuItem("Hardware Settings");
            hardwareSettingsItem.setOnAction(e -> showHardwareSettingsScreen());
            settingsMenu.getItems().addAll(settingsItem, employeeManagementItem, permissionsManagementItem,
                    new SeparatorMenuItem(), hardwareSettingsItem);
            menuBar.getMenus().add(settingsMenu);
        }

        // Help Menu (available to all)
        Menu helpMenu = new Menu("Help");
        MenuItem helpCenterItem = new MenuItem("Help Center");
        helpCenterItem.setOnAction(e -> showHelpCenter());
        MenuItem aboutItem = new MenuItem("About");
        aboutItem.setOnAction(e -> showAboutDialog());
        helpMenu.getItems().addAll(helpCenterItem, new SeparatorMenuItem(), aboutItem);

        menuBar.getMenus().addAll(fileMenu, salesMenu, helpMenu);
        return menuBar;
    }

    /**
     * Refresh the menu bar based on current user permissions.
     * This should be called after login or when permissions change.
     * Can be called from any thread - will update UI on JavaFX thread.
     */
    public void refreshMenuBar() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refreshMenuBar);
            return;
        }

        logger.debug("Refreshing menu bar based on current permissions");

        // Clear permission cache to force reload from database
        rbacService.clearPermissionCache();

        // Recreate menu bar (this will check permissions fresh from database)
        MenuBar newMenuBar = createMenuBar();

        this.menuBar = newMenuBar;
        if (!menuBarHidden) {
            setTop(newMenuBar);
        }
        logger.debug("Menu bar refreshed successfully");
    }

    public void updateStatus(String message) {
        if (statusBar != null) {
            statusBar.setText(message);
            logger.debug("Status updated: {}", message);
        }
    }

    private void updateUserLabel() {
        if (userLabel != null && authService != null) {
            String userName = authService.getCurrentUserName();
            String role = authService.getCurrentUserRole();
            if (userName != null && !userName.isEmpty()) {
                String displayText = "User: " + userName;
                if (role != null && !role.isEmpty()) {
                    displayText += " (" + role + ")";
                }
                userLabel.setText(displayText);
                userLabel.setStyle("-fx-text-fill: #2196F3;");
            } else {
                userLabel.setText("User: -");
                userLabel.setStyle("-fx-text-fill: #666;");
            }
        }
    }

    private void handleLogout() {
        confirmAndLogout();
    }



    /**
     * Confirm logout and proceed.
     */
    private void confirmAndLogout() {
        String currentUserId = authService.getCurrentPosUserId();
        EmployeeShiftService employeeShiftService = EmployeeShiftService.getInstance();

        EmployeeShift activeEmployeeShift = null;
        try {
            if (currentUserId != null) {
                activeEmployeeShift = employeeShiftService.getActiveShift(currentUserId);
            }
        } catch (Exception e) {
            logger.warn("Could not get active employee shift: {}", e.getMessage());
        }

        if (activeEmployeeShift != null) {
            showCloseShiftScreen(this::showSalesScreen);
        } else {
            // No active employee shift, show simple confirmation
            Alert confirmDialog = new Alert(Alert.AlertType.CONFIRMATION);
            confirmDialog.setTitle("Logout");
            confirmDialog.setHeaderText("Confirm Logout");
            confirmDialog.setContentText("Are you sure you want to logout?");
            DialogHelper.setAlertOwner(confirmDialog, stage);

            // Make confirmation dialog responsive
            com.pos.util.ResponsiveHelper.setupResponsiveDialog(confirmDialog, 0.4, 0.3);

            confirmDialog.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    authService.logout();
                }
            });
        }
    }

    /**
     * Perform the actual logout and navigate to login screen
     */
    private void performLogout() {
        // Navigate back to login screen
        if (stage != null) {
            Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
            LoginScreen loginScreen = new LoginScreen(stage);
            Scene loginScene = new Scene(loginScreen, screenBounds.getWidth(), screenBounds.getHeight());

            // Load stylesheet
            try {
                loginScene.getStylesheets().add(
                        getClass().getResource("/styles/application.css").toExternalForm());
            } catch (Exception e) {
                logger.warn("Stylesheet not found, using default styles", e);
            }

            stage.setScene(loginScene);
            stage.setTitle("Pasal POS 2 - Login");
            stage.setFullScreenExitKeyCombination(javafx.scene.input.KeyCombination.NO_MATCH);
            stage.setFullScreenExitHint("");

            // Prevent fullscreen exit
            stage.fullScreenProperty().addListener((obs, wasFullScreen, isNowFullScreen) -> {
                if (!isNowFullScreen && wasFullScreen) {
                    javafx.application.Platform.runLater(() -> {
                        stage.setFullScreen(true);
                    });
                }
            });

            // Maximize then set fullscreen after scene is set (required for macOS)
            stage.setMaximized(true);
            stage.show();
            stage.toFront();
            javafx.application.Platform.runLater(() -> {
                stage.setFullScreen(true);
                stage.toFront();
                stage.requestFocus();
            });
        } else {
            logger.warn("Stage not available, cannot navigate to login screen");
        }
    }

    private void syncProducts() {
        if (ProductSyncService.getInstance().isSyncing()) {
            updateStatus("Sync already in progress...");
            return;
        }

        updateStatus("Syncing products...");
        new Thread(() -> {
            try {
                ProductSyncService syncService = ProductSyncService.getInstance();
                boolean success = syncService.performFullSync();

                javafx.application.Platform.runLater(() -> {
                    if (success) {
                        updateStatus("Products synced successfully");
                        // Refresh sales screen to show newly synced products and departments
                        if (salesScreen != null) {
                            salesScreen.refresh();
                        }
                    } else {
                        String error = syncService.getLastSyncError();
                        updateStatus("Product sync failed: " + (error != null ? error : "Unknown error"));
                        logger.warn("Product sync failed: {}", error);
                    }
                });
            } catch (Exception e) {
                logger.error("Product sync failed with exception", e);
                javafx.application.Platform.runLater(() -> {
                    updateStatus("Product sync failed: " + e.getMessage());
                });
            }
        }).start();
    }

    private void showShiftScreen() {
        ShiftScreen shiftScreen = new ShiftScreen(this::showSalesScreen);
        shiftScreen.setOnNavigateToCashDrop(() -> showCashDropScreen(this::showShiftScreen));
        shiftScreen.setOnNavigateToCloseShift(() -> showCloseShiftScreen(this::showShiftScreen));
        shiftScreen.setOnNavigateToEndDay(() -> showEndDayScreen(this::showShiftScreen));
        setCenter(shiftScreen);
    }

    private void showSettingsScreen() {
        SettingsScreen settingsScreen = new SettingsScreen(this::showSalesScreen);
        setCenter(settingsScreen);
    }

    private void showInventoryView() {
        InventoryView inventoryView = new InventoryView(hardwareManager, this::showSalesScreen);
        setCenter(inventoryView);
    }

    private void showProductManagement() {
        ProductManagementView productView = new ProductManagementView(hardwareManager, this::showSalesScreen);
        setCenter(productView);
    }

    private void showReportsView() {
        ReportsView reportsView = new ReportsView(hardwareManager, this::showSalesScreen);
        setCenter(reportsView);
    }

    private void showSalesReportView() {
        SalesReportView salesReportView = new SalesReportView(hardwareManager, this::showSalesScreen);
        setCenter(salesReportView);
    }

    private void showShiftReportView() {
        ShiftReportView shiftReportView = new ShiftReportView(this::showSalesScreen);
        setCenter(shiftReportView);
    }

    private void showSaleHistoryView() {
        SaleHistoryView saleHistoryView = new SaleHistoryView(hardwareManager, this::showSalesScreen);
        saleHistoryView.setOnNavigateToRefund(this::showRefundScreen);
        setCenter(saleHistoryView);
    }

    private void showRefundScreen(com.pos.service.SaleHistoryService.SaleRecord sale) {
        RefundScreen refundScreen = new RefundScreen(sale, () -> showSaleHistoryView());
        setCenter(refundScreen);
    }

    private void showReturnScreen() {
        ReturnScreen returnScreen = new ReturnScreen(this::showSalesScreen);
        setCenter(returnScreen);
    }

    private void showEmployeeManagement() {
        EmployeeManagementView employeeView = new EmployeeManagementView(this::showSalesScreen);
        setCenter(employeeView);
    }

    private void showPermissionsManagement() {
        PermissionsManagementView permissionsView = new PermissionsManagementView(this::showSalesScreen);
        setCenter(permissionsView);
    }

    private void showDepartmentManagement() {
        DepartmentManagementView deptView = new DepartmentManagementView(this::showSalesScreen);
        setCenter(deptView);
    }

    private void showHardwareSettingsScreen() {
        HardwareSettingsScreen hardwareSettingsScreen = new HardwareSettingsScreen(this::showSalesScreen);
        setCenter(hardwareSettingsScreen);
    }

    private void showHelpCenter() {
        HelpCenterScreen helpCenterScreen = new HelpCenterScreen(this::showSalesScreen);
        setCenter(helpCenterScreen);
    }

    private void showAboutDialog() {
        String version = com.pos.config.ConfigManager.getInstance().getProperty("app.version", "1.0.0");
        Alert aboutAlert = new Alert(Alert.AlertType.INFORMATION);
        aboutAlert.setTitle("About Pasal POS 2");
        aboutAlert.setHeaderText("Pasal POS 2");
        aboutAlert.setContentText(
                "Version: " + version + "\n\n" +
                        "A modern Point of Sale system for retail operations.\n\n" +
                        "© 2024 POS Solutions. All rights reserved.\n\n" +
                        "For support, go to Help → Help Center");
        DialogHelper.setAlertOwner(aboutAlert, stage);
        aboutAlert.showAndWait();
    }

    /**
     * Show the sales screen - can be called from other views to navigate back
     */
    public void showSalesScreen() {
        menuBarHidden = false;
        if (getTop() == null) {
            setTop(menuBar);
        }
        setCenter(salesScreen);
        // Refresh to ensure new products/departments are visible
        salesScreen.refresh();
        updateStatus("Sales Screen");
    }

    /**
     * Check for active shift and show appropriate initial screen
     */
    private void checkShiftAndShowInitialScreen() {
        updateStatus("Checking shift status...");
        new Thread(() -> {
            try {
                ShiftService shiftService = ShiftService.getInstance();
                ShiftResponse.ShiftData activeShift = shiftService.getActiveShift();

                Platform.runLater(() -> {
                    if (activeShift != null && "ACTIVE".equals(activeShift.status)) {
                        showSalesScreen();
                    } else {
                        showStartShiftScreen();
                    }
                });
            } catch (Exception e) {
                logger.error("Error checking shift during startup", e);
                Platform.runLater(this::showSalesScreen);
            }
        }).start();
    }

    /**
     * Show the dedicated start shift screen
     */
    public void showStartShiftScreen() {
        StartShiftScreen startShiftScreen = new StartShiftScreen(this::showSalesScreen);
        menuBarHidden = true;
        setTop(null);
        setCenter(startShiftScreen);
        updateStatus("Shift Setup");
    }

    /**
     * Show the dedicated cash drop screen
     */
    private void showCashDropScreen(Runnable onBack) {
        CashDropScreen cashDropScreen = new CashDropScreen(onBack);
        setCenter(cashDropScreen);
    }

    private void showVendorPayoutScreen(Runnable onBack) {
        VendorPayoutScreen vendorPayoutScreen = new VendorPayoutScreen(onBack);
        vendorPayoutScreen.setOnNavigateToAddVendor(() -> showAddVendorScreen(() -> showVendorPayoutScreen(onBack)));
        vendorPayoutScreen.setOnNavigateToHistory(() -> showVendorPayoutHistoryScreen(() -> showVendorPayoutScreen(onBack)));
        setCenter(vendorPayoutScreen);
    }

    private void showVendorPayoutHistoryScreen(Runnable onBack) {
        VendorPayoutHistoryScreen historyScreen = new VendorPayoutHistoryScreen(onBack);
        historyScreen.setOnNavigateToEditPayout(payoutId ->
                showVendorPayoutEditScreen(payoutId, () -> showVendorPayoutHistoryScreen(onBack)));
        setCenter(historyScreen);
    }

    private void showVendorPayoutEditScreen(String payoutId, Runnable onBack) {
        VendorPayoutEditScreen editScreen = new VendorPayoutEditScreen(payoutId, onBack);
        setCenter(editScreen);
    }

    private void showRecallHeldSalesScreen(Runnable onBack) {
        RecallHeldSalesScreen recallScreen = new RecallHeldSalesScreen(
                onBack,
                holdId -> {
                    if (salesScreen.recallHeldSale(holdId, stage)) {
                        showSalesScreen();
                    }
                });
        setCenter(recallScreen);
    }

    private void showAddVendorScreen(Runnable onBack) {
        AddVendorScreen addVendorScreen = new AddVendorScreen(onBack);
        setCenter(addVendorScreen);
    }

    /**
     * Show the dedicated close shift (cashier level) screen
     */
    private void showCloseShiftScreen(Runnable onBack) {
        CloseShiftScreen closeShiftScreen = new CloseShiftScreen(onBack);
        setCenter(closeShiftScreen);
    }

    /**
     * Show the dedicated end day (manager level) screen
     */
    private void showEndDayScreen(Runnable onBack) {
        EndDayScreen endDayScreen = new EndDayScreen(onBack);
        setCenter(endDayScreen);
    }

    private void showTimesheetScreen(Runnable onBack) {
        showTimesheetScreen(onBack, null);
    }

    private void showTimesheetScreen(Runnable onBack, String preferredUserId) {
        TimesheetScreen timesheetScreen = new TimesheetScreen(
                onBack,
                user -> showTimesheetEditScreen(user, onBack),
                preferredUserId);
        setCenter(timesheetScreen);
    }

    private void showTimesheetEditScreen(PosUserLoginResponse.PosUserInfo user, Runnable salesBack) {
        TimesheetEditScreen editScreen = new TimesheetEditScreen(
                () -> showTimesheetScreen(salesBack, user != null ? user.id : null),
                user);
        setCenter(editScreen);
    }

    /**
     * Setup navigation callbacks for the SalesScreen
     */
    private void setupSalesScreenNavigation() {
        salesScreen.setOnNavigateToPayment(this::showPaymentScreen);
        salesScreen.setOnNavigateToCashDrop(() -> showCashDropScreen(this::showSalesScreen));
        salesScreen.setOnNavigateToCloseShift(() -> showCloseShiftScreen(this::showSalesScreen));
        salesScreen.setOnNavigateToEndDay(() -> showEndDayScreen(this::showSalesScreen));
        salesScreen.setOnNavigateToTimesheet(() -> showTimesheetScreen(this::showSalesScreen));
        salesScreen.setOnNavigateToRecallSales(() -> showRecallHeldSalesScreen(this::showSalesScreen));
        salesScreen.setOnNavigateToEditItem((item, mode) -> showEditItemScreen(item, mode));
        salesScreen.setOnNavigateToDiscount((subtotal, onApplied) -> showDiscountScreen(subtotal, onApplied, this::showSalesScreen));
        salesScreen.setOnNavigateToVendorPayout(() -> showVendorPayoutScreen(this::showSalesScreen));
        salesScreen.setOnNavigateToReturn(this::showReturnScreen);

        // Setup DayChangeMonitorService callbacks
        DayChangeMonitorService dayChangeMonitor = DayChangeMonitorService.getInstance();

        // Set cart checker - returns true if cart has items
        dayChangeMonitor.setCartHasItemsChecker(() -> !salesScreen.getCartItems().isEmpty());

        // Set warning callback - shows dialog when day changes with items in cart
        dayChangeMonitor.setDayChangeWarningCallback(onComplete -> {
            Platform.runLater(() -> {
                showDayChangeWarningDialog(onComplete);
            });
        });
    }

    /**
     * Show warning dialog when day changes with items in cart
     */
    private void showDayChangeWarningDialog(Runnable onComplete) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Day Change Detected");
        alert.setHeaderText("A new day has started");
        alert.setContentText(
                "The date has changed while you have items in your cart.\n\n" +
                        "Please complete or cancel your current transaction.\n" +
                        "You will be logged out after this sale is finished.");

        ButtonType completeBtn = new ButtonType("OK, I'll finish");
        alert.getButtonTypes().setAll(completeBtn);

        DialogHelper.setAlertOwner(alert, stage);
        alert.showAndWait();
    }

    /**
     * Navigate to the full-screen payment page
     */
    private void showPaymentScreen() {
        setCenter(getOrRefreshPaymentScreen());
    }

    /**
     * Return the shared PaymentScreen, building it once and afterwards just
     * refreshing it with the current sale. Reusing the instance avoids
     * rebuilding the entire payment UI tree on every checkout.
     */
    private PaymentScreen getOrRefreshPaymentScreen() {
        if (paymentScreen == null) {
            paymentScreen = salesScreen.createPaymentScreen(
                    // On payment complete
                    () -> {
                        DayChangeMonitorService.getInstance().onSaleCompleted();
                        showSalesScreen();
                        salesScreen.startNewSale();
                    },
                    // On cancel
                    () -> {
                        DayChangeMonitorService dayChangeMonitor = DayChangeMonitorService.getInstance();
                        if (dayChangeMonitor.isPendingDayChangeLogout()) {
                            dayChangeMonitor.onSaleCompleted();
                        }
                        showSalesScreen();
                    },
                    // On split navigation
                    this::showSplitPaymentScreen);
        } else {
            salesScreen.refreshPaymentScreen(paymentScreen);
        }
        return paymentScreen;
    }

    /**
     * Show the dedicated split payment screen
     */
    private void showSplitPaymentScreen() {
        // Calculate totals for screen
        BigDecimal listTotal = salesScreen.getCurrentTotal(); // This should be list price in PaymentScreen logic
        // In local logic, SalesScreen.currentTotal is cash. 
        // We need to pass the correct card/list price to SplitPaymentScreen.
        
        SplitPaymentScreen splitScreen = new SplitPaymentScreen(
                salesScreen.getCartItems(),
                salesScreen.getSubtotal(),
                salesScreen.getTax(),
                salesScreen.getCurrentTotal(),
                salesScreen.getCurrentTotal().multiply(new BigDecimal("1.04")).setScale(2, RoundingMode.HALF_UP),
                salesScreen.getSaleDiscount(),
                payments -> {
                    logger.info("Processing split payments: {}", payments.size());

                    PaymentScreen ps = getOrRefreshPaymentScreen();
                    setCenter(ps);
                    Platform.runLater(() -> ps.processSplitPaymentTransaction(payments));
                },
                this::showPaymentScreen
        );
        setCenter(splitScreen);
    }

    /**
     * Show the dedicated edit item screen
     */
    private void showEditItemScreen(SaleItem item, EditItemScreen.EditMode mode) {
        EditItemScreen editScreen = new EditItemScreen(
                item,
                mode,
                updatedItem -> {
                    // Item already updated by EditItemScreen
                    showSalesScreen();
                },
                this::showSalesScreen
        );
        setCenter(editScreen);
    }

    /**
     * Show the dedicated discount screen
     */
    private void showDiscountScreen(BigDecimal subtotal, java.util.function.Consumer<DiscountScreen.DiscountResult> onApplied, Runnable onCancel) {
        DiscountScreen discountScreen = new DiscountScreen(
                subtotal,
                result -> {
                    showSalesScreen();
                    onApplied.accept(result);
                },
                onCancel
        );
        setCenter(discountScreen);
        updateStatus("Apply Discount");
    }

    /**
     * Start periodic status updates for shift and sync status
     */
    private void startStatusUpdates() {
        // Register as a SyncManager listener for immediate sync status updates
        com.pos.sync.SyncManager.getInstance().addStatusListener(syncStatus -> {
            Platform.runLater(() -> {
                updateSyncStatusLabel(syncStatus.isOnline(), syncStatus.getPendingOutboundCount());
            });
        });

        Thread statusThread = new Thread(() -> {
            while (true) {
                try {
                    // Update shift status
                    try {
                        ShiftService shiftService = ShiftService.getInstance();
                        OfflineSyncService syncService = OfflineSyncService.getInstance();

                        // Only try backend if online, otherwise use local database
                        ShiftResponse.ShiftData activeShift = null;
                        if (syncService.isOnline()) {
                            try {
                                activeShift = shiftService.getActiveShift();
                            } catch (Exception e) {
                                // If online check fails, fall back to local
                                logger.debug("Backend unavailable, checking local shift data", e);
                                activeShift = shiftService.getActiveShiftFromLocal();
                            }
                        } else {
                            // Offline - use local database only
                            activeShift = shiftService.getActiveShiftFromLocal();
                        }

                        final ShiftResponse.ShiftData finalShift = activeShift;
                        Platform.runLater(() -> {
                            if (finalShift != null && "ACTIVE".equals(finalShift.status)) {
                                shiftStatusLabel.setText("Shift: Active (" + finalShift.cashierName + ")");
                                shiftStatusLabel.setStyle("-fx-text-fill: #4CAF50;");
                            } else {
                                shiftStatusLabel.setText("Shift: None");
                                shiftStatusLabel.setStyle("-fx-text-fill: #666;");
                            }
                        });
                    } catch (Exception e) {
                        logger.debug("Error updating shift status", e);
                        Platform.runLater(() -> {
                            shiftStatusLabel.setText("Shift: Unknown");
                        });
                    }

                    // Update sync status (also done via listener, but keep polling as backup)
                    try {
                        OfflineSyncService syncService = OfflineSyncService.getInstance();
                        boolean isOnline = syncService.isOnline();
                        int pendingCount = syncService.getPendingRequestCount();

                        Platform.runLater(() -> {
                            updateSyncStatusLabel(isOnline, pendingCount);
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> {
                            syncStatusLabel.setText("Sync: Unknown");
                        });
                    }

                    Thread.sleep(5000); // Update every 5 seconds
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    logger.error("Error updating status", e);
                }
            }
        });
        statusThread.setDaemon(true);
        statusThread.start();
    }

    /**
     * Update the sync status label based on online status and pending count
     */
    private void updateSyncStatusLabel(boolean isOnline, int pendingCount) {
        if (isOnline) {
            if (pendingCount > 0) {
                syncStatusLabel.setText("Sync: Online (" + pendingCount + " pending)");
                syncStatusLabel.setStyle("-fx-text-fill: #FF9800;");
            } else {
                syncStatusLabel.setText("Sync: Online");
                syncStatusLabel.setStyle("-fx-text-fill: #4CAF50;");
            }
        } else {
            syncStatusLabel.setText("Sync: Offline (" + pendingCount + " queued)");
            syncStatusLabel.setStyle("-fx-text-fill: #f44336;");
        }
    }
}
