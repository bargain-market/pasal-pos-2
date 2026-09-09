package com.pos.ui;

import com.pos.api.ApiClient;
import com.pos.api.dto.MinimumSaleAmountRequest;
import com.pos.api.dto.MinimumSaleAmountSyncData;
import com.pos.api.dto.StoreSettingsResponse;
import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.service.BackupService;
import com.pos.service.CustomerDisplayService;
import com.pos.service.DeviceRegistrationService;
import com.pos.service.RoleBasedAccessService;
import com.pos.service.SettingsService;
import com.pos.service.UpdateService;
import com.pos.service.UserAuthService;
import com.pos.util.DialogHelper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.Scene;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import com.pos.ui.components.ToastNotification;

/**
 * Settings and configuration screen
 */
public class SettingsScreen extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(SettingsScreen.class);
    private SettingsService settingsService;
    private DeviceRegistrationService deviceService;
    private ApiClient apiClient;

    private Label deviceInfoLabel;
    private Label storeInfoLabel;
    private Label syncStatusLabel;
    private Label backupStatusLabel;
    private Label versionLabel;
    private Button refreshSettingsButton;
    private Button syncNowButton;
    private Button checkForUpdatesButton;
    private Button outboundSyncButton;
    private Button markAllSyncedButton;
    private Button backupNowButton;
    private Button restoreBackupButton;
    private TextField minimumSaleAmountField;
    private CheckBox customerDisplayEnabledCheckbox;
    private Label customerDisplayStatusLabel;
    private Runnable onBackToSales;

    public SettingsScreen() {
        this(null);
    }

    public SettingsScreen(Runnable onBackToSales) {
        // Permission check - Admin only
        RoleBasedAccessService rbacService = RoleBasedAccessService.getInstance();
        if (!rbacService.isAdmin()) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Access Denied");
            alert.setHeaderText("Permission Denied");
            alert.setContentText("You do not have permission to access settings. Admin role required.");
            DialogHelper.setAlertOwner(alert, null);
            alert.showAndWait();
            // Redirect to sales screen if callback available
            if (onBackToSales != null) {
                Platform.runLater(() -> onBackToSales.run());
            }
            return;
        }

        this.settingsService = SettingsService.getInstance();
        this.deviceService = DeviceRegistrationService.getInstance();
        this.apiClient = ApiClient.getInstance();
        this.onBackToSales = onBackToSales;
        initializeUI();
        loadSettings();
    }

    private void initializeUI() {
        // Top section - Title and Back button
        VBox topSection = new VBox(10);
        topSection.setPadding(new Insets(20));
        topSection.setStyle("-fx-background-color: white;");

        HBox titleRow = new HBox(15);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        // Back to Sales button
        if (onBackToSales != null) {
            Button backToSalesButton = new Button("\u2190 Back to Sales");
            backToSalesButton.setStyle(
                    "-fx-background-color: #2196F3; " +
                            "-fx-text-fill: white; " +
                            "-fx-font-size: 14px; " +
                            "-fx-font-weight: bold; " +
                            "-fx-padding: 8 20; " +
                            "-fx-background-radius: 5; " +
                            "-fx-cursor: hand;");
            backToSalesButton.setOnAction(e -> onBackToSales.run());
            titleRow.getChildren().add(backToSalesButton);
        }

        Label titleLabel = new Label("Settings & Configuration");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        titleRow.getChildren().add(titleLabel);

        topSection.getChildren().add(titleRow);
        setTop(topSection);

        // Center - Settings content
        ScrollPane scrollPane = new ScrollPane();
        VBox content = createContent();
        scrollPane.setContent(content);
        scrollPane.setFitToWidth(true);
        setCenter(scrollPane);

        // Bottom - Actions
        HBox bottomSection = createBottomSection();
        setBottom(bottomSection);
    }

    private VBox createContent() {
        VBox content = new VBox(20);
        content.setPadding(new Insets(20));

        // Device Information Section
        VBox deviceSection = createSection("Device Information");
        deviceInfoLabel = new Label("Loading...");
        deviceInfoLabel.setWrapText(true);
        deviceSection.getChildren().add(deviceInfoLabel);

        // Add Reset Registration button
        Button resetRegistrationButton = new Button("Reset Registration");
        resetRegistrationButton.setStyle(
                "-fx-background-color: #f44336; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 14px; " +
                        "-fx-padding: 8 20; " +
                        "-fx-background-radius: 5; " +
                        "-fx-cursor: hand;");
        resetRegistrationButton.setOnAction(e -> resetRegistration());
        resetRegistrationButton.setPrefWidth(200);
        resetRegistrationButton.setPrefHeight(35);

        HBox deviceButtonBox = new HBox(10);
        deviceButtonBox.getChildren().add(resetRegistrationButton);
        deviceSection.getChildren().add(deviceButtonBox);

        content.getChildren().add(deviceSection);

        // Store Information Section
        VBox storeSection = createSection("Store Information");
        storeInfoLabel = new Label("Loading...");
        storeInfoLabel.setWrapText(true);
        storeSection.getChildren().add(storeInfoLabel);
        content.getChildren().add(storeSection);

        // Sync Status Section
        VBox syncSection = createSection("Synchronization Status");
        syncStatusLabel = new Label("Checking...");
        syncStatusLabel.setWrapText(true);
        Label syncQueueNote = new Label(
                "Use Sync Now to upload pending records to the website and mobile app. "
                        + "Mark All as Synced only clears local pending status; it does not upload records.");
        syncQueueNote.setWrapText(true);
        syncQueueNote.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");
        syncSection.getChildren().addAll(syncStatusLabel, syncQueueNote);
        content.getChildren().add(syncSection);

        // Backend Configuration Section
        VBox backendSection = createSection("Backend Configuration");
        TextField backendUrlField = new TextField();
        backendUrlField.setPromptText("Backend API URL");
        backendUrlField.setText(apiClient.getBaseUrl());
        // backendUrlField.setPrefWidth(400);
        backendUrlField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(backendUrlField, Priority.ALWAYS);
        backendUrlField.setPrefHeight(40);
        backendUrlField.setStyle(
                "-fx-font-size: 14px; " +
                        "-fx-padding: 8px 12px; " +
                        "-fx-background-radius: 5; " +
                        "-fx-border-radius: 5; " +
                        "-fx-border-color: #ddd; " +
                        "-fx-border-width: 1; " +
                        "-fx-background-color: white;");
        // Enable keyboard input - ensure field is editable
        backendUrlField.setEditable(true);
        Button saveUrlButton = new Button("Save URL");
        saveUrlButton.setOnAction(e -> {
            apiClient.setBaseUrl(backendUrlField.getText());
            showAlert("Backend URL updated");
        });

        HBox urlBox = new HBox(10);
        urlBox.getChildren().addAll(backendUrlField, saveUrlButton);
        HBox.setHgrow(backendUrlField, Priority.ALWAYS); // Ensure it grows in this specific HBox
        backendSection.getChildren().add(urlBox);
        content.getChildren().add(backendSection);

        // Receipt Settings Section
        VBox receiptSection = createReceiptSection();
        content.getChildren().add(receiptSection);

        // Customer Display Section
        VBox customerDisplaySection = createCustomerDisplaySection();
        content.getChildren().add(customerDisplaySection);

        // Minimum sale amount (store policy)
        VBox minimumSaleSection = createMinimumSaleSection();
        content.getChildren().add(minimumSaleSection);

        // Cash/Check Limit Settings Section
        VBox cashCheckSection = createCashCheckLimitSection();
        content.getChildren().add(cashCheckSection);

        // Database Backup Section
        VBox backupSection = createBackupSection();
        content.getChildren().add(backupSection);

        // About & Updates Section
        VBox aboutSection = createSection("About & Updates");
        String currentVersion = ConfigManager.getInstance().getProperty("app.version", "1.0.0");
        versionLabel = new Label("Current Version: " + currentVersion);
        versionLabel.setStyle("-fx-font-size: 14px;");

        checkForUpdatesButton = new Button("Check for Updates");
        checkForUpdatesButton.setStyle(
                "-fx-background-color: #4CAF50; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 14px; " +
                        "-fx-padding: 8 20; " +
                        "-fx-background-radius: 5; " +
                        "-fx-cursor: hand;");
        checkForUpdatesButton.setOnAction(e -> checkForUpdates());

        HBox updateBox = new HBox(20);
        updateBox.setAlignment(Pos.CENTER_LEFT);
        updateBox.getChildren().addAll(versionLabel, checkForUpdatesButton);
        aboutSection.getChildren().add(updateBox);
        content.getChildren().add(aboutSection);

        return content;
    }

    private VBox createSection(String title) {
        VBox section = new VBox(10);
        section.setPadding(new Insets(15));
        section.setStyle("-fx-background-color: #f5f5f5; -fx-background-radius: 5;");

        Label sectionTitle = new Label(title);
        sectionTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        section.getChildren().add(sectionTitle);

        return section;
    }

    /**
     * Create the Receipt Settings section
     */
    private VBox createReceiptSection() {
        VBox section = createSection("Receipt Settings");
        section.setSpacing(15);

        Label modeLabel = new Label("Receipt Print Mode:");
        modeLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        ComboBox<String> modeCombo = new ComboBox<>();
        modeCombo.getItems().addAll("AUTO", "MANUAL");
        modeCombo.setValue(settingsService.getReceiptPrintMode());
        modeCombo.setPrefWidth(200);
        modeCombo.setPrefHeight(40);
        modeCombo.setStyle("-fx-font-size: 14px;");

        Label descLabel = new Label();
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");
        updateModeDescription(descLabel, modeCombo.getValue());

        modeCombo.setOnAction(e -> {
            String selectedMode = modeCombo.getValue();
            settingsService.setReceiptPrintMode(selectedMode);
            updateModeDescription(descLabel, selectedMode);
            ToastNotification.showSuccess("Receipt print mode updated to " + selectedMode, getScene().getWindow());
        });

        VBox modeBox = new VBox(5);
        modeBox.getChildren().addAll(modeLabel, modeCombo, descLabel);

        section.getChildren().add(modeBox);
        return section;
    }

    private VBox createCustomerDisplaySection() {
        VBox section = createSection("Customer Display (Second Monitor)");
        section.setSpacing(12);

        Label descLabel = new Label(
                "Show the cart and sale totals on a secondary monitor for customers. "
                        + "Turn off to hide the customer-facing screen even when a second display is connected.");
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        CustomerDisplayService displayService = CustomerDisplayService.getInstance();
        customerDisplayEnabledCheckbox = new CheckBox("Enable customer display");
        customerDisplayEnabledCheckbox.setSelected(displayService.isConfiguredEnabled());
        customerDisplayEnabledCheckbox.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        customerDisplayStatusLabel = new Label();
        customerDisplayStatusLabel.setWrapText(true);
        updateCustomerDisplayStatus();

        customerDisplayEnabledCheckbox.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            displayService.setDisplayEnabled(isSelected);
            updateCustomerDisplayStatus();
            String message = isSelected
                    ? (displayService.isEnabled()
                            ? "Customer display enabled."
                            : "Setting saved. No secondary monitor was detected — connect one and toggle again.")
                    : "Customer display disabled.";
            if (getScene() != null && getScene().getWindow() != null) {
                if (isSelected && !displayService.isEnabled()) {
                    ToastNotification.showWarning(message, getScene().getWindow());
                } else {
                    ToastNotification.showSuccess(message, getScene().getWindow());
                }
            } else {
                showAlert(message);
            }
        });

        section.getChildren().addAll(descLabel, customerDisplayEnabledCheckbox, customerDisplayStatusLabel);
        return section;
    }

    private void updateCustomerDisplayStatus() {
        if (customerDisplayStatusLabel == null) {
            return;
        }

        CustomerDisplayService displayService = CustomerDisplayService.getInstance();
        if (!displayService.isConfiguredEnabled()) {
            customerDisplayStatusLabel.setText("Status: Disabled");
            customerDisplayStatusLabel.setStyle("-fx-text-fill: #666;");
            return;
        }

        if (!displayService.isSecondaryScreenAvailable()) {
            customerDisplayStatusLabel.setText(
                    "Status: Enabled in settings, but no secondary monitor detected.");
            customerDisplayStatusLabel.setStyle("-fx-text-fill: #FF9800;");
            return;
        }

        if (displayService.isEnabled()) {
            customerDisplayStatusLabel.setText("Status: Active on secondary monitor");
            customerDisplayStatusLabel.setStyle("-fx-text-fill: #4CAF50;");
        } else {
            customerDisplayStatusLabel.setText("Status: Secondary monitor detected — display is starting...");
            customerDisplayStatusLabel.setStyle("-fx-text-fill: #FF9800;");
        }
    }

    /**
     * Minimum sale total for this register: stored locally and optionally synced to the server.
     */
    private VBox createMinimumSaleSection() {
        VBox section = createSection("Minimum Sale Amount");
        section.setSpacing(12);

        Label descLabel = new Label(
                "Set the smallest sale total allowed (after tax). Leave empty or 0 for no minimum.\n"
                        + "Example: 0.10 blocks totals under $0.10.\n"
                        + "Saved to this register right away. If the device is registered and online, the same value is sent to your store account.");
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        minimumSaleAmountField = new TextField();
        minimumSaleAmountField.setPromptText("e.g. 0.10 or leave empty");
        minimumSaleAmountField.setMaxWidth(220);
        minimumSaleAmountField.setPrefHeight(40);

        Button saveMinSaleButton = new Button("Save (this register)");
        saveMinSaleButton.setPrefWidth(160);
        saveMinSaleButton.setPrefHeight(40);
        saveMinSaleButton.setStyle(
                "-fx-background-color: #FF9800; "
                        + "-fx-text-fill: white; "
                        + "-fx-font-size: 14px; "
                        + "-fx-font-weight: bold; "
                        + "-fx-background-radius: 5;");
        saveMinSaleButton.setOnAction(e -> saveMinimumSaleAmount());

        HBox row = new HBox(10, minimumSaleAmountField, saveMinSaleButton);
        row.setAlignment(Pos.CENTER_LEFT);

        section.getChildren().addAll(descLabel, row);
        return section;
    }

    private void saveMinimumSaleAmount() {
        String raw = minimumSaleAmountField.getText() != null ? minimumSaleAmountField.getText().trim() : "";
        saveMinSaleButtonWork(raw);
    }

    private void saveMinSaleButtonWork(String raw) {
        new Thread(() -> {
            try {
                Double payload;
                BigDecimal localAmount;
                if (raw.isEmpty()) {
                    payload = null;
                    localAmount = null;
                } else {
                    BigDecimal parsed = new BigDecimal(raw.replace(",", ""));
                    if (parsed.compareTo(BigDecimal.ZERO) < 0) {
                        Platform.runLater(() -> showAlert("Minimum amount cannot be negative."));
                        return;
                    }
                    if (parsed.compareTo(BigDecimal.ZERO) == 0) {
                        payload = null;
                        localAmount = null;
                    } else {
                        localAmount = parsed.setScale(2, RoundingMode.HALF_UP);
                        payload = localAmount.doubleValue();
                    }
                }

                // Always persist on this POS first (local store_settings)
                settingsService.updateMinimumSaleAmountLocally(localAmount);

                boolean pushedToServer = false;
                if (apiClient.isRegistered()) {
                    try {
                        MinimumSaleAmountRequest body = new MinimumSaleAmountRequest();
                        body.minimumSaleAmount = payload;
                        apiClient.post("/pos/settings/minimum-sale-amount", body, MinimumSaleAmountSyncData.class);
                        pushedToServer = true;
                    } catch (ApiClient.ApiException ex) {
                        logger.warn("Minimum sale saved locally; could not update server: {}", ex.getMessage());
                    }
                }

                final boolean registered = apiClient.isRegistered();
                final boolean pushed = pushedToServer;
                Platform.runLater(() -> {
                    String msg;
                    if (!registered) {
                        msg = "Minimum sale saved on this register. Register the device to sync it to your store account.";
                    } else if (pushed) {
                        msg = "Minimum sale saved on this register and updated for your store.";
                    } else {
                        msg = "Minimum sale saved on this register. Server was unreachable—other registers keep their copy until sync.";
                    }
                    if (getScene() != null && getScene().getWindow() != null) {
                        ToastNotification.showSuccess(msg, getScene().getWindow());
                    } else {
                        showAlert(msg);
                    }
                });
            } catch (Exception ex) {
                logger.error("Failed to save minimum sale amount", ex);
                Platform.runLater(() -> showAlert("Could not save: " + ex.getMessage()));
            }
        }).start();
    }

    /**
     * Create the Cash/Check Limit Settings section
     */
    private VBox createCashCheckLimitSection() {
        VBox section = createSection("Cash/Check Operation Limits");
        section.setSpacing(15);

        Label descLabel = new Label(
            "Configure daily limits for manual cash operations (Add, Drop, Payout, Adjustment).\n" +
            "Users exceeding their limit will need an admin PIN to continue."
        );
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        Button setPinButton = new Button("Set Admin PIN");
        setPinButton.setPrefWidth(180);
        setPinButton.setPrefHeight(40);
        setPinButton.setStyle(
                "-fx-background-color: #2196F3; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 14px; " +
                        "-fx-font-weight: bold; " +
                        "-fx-background-radius: 5;");
        setPinButton.setOnAction(e -> openCashCheckPinDialog());

        Button manageLimitsButton = new Button("Manage Role Limits");
        manageLimitsButton.setPrefWidth(180);
        manageLimitsButton.setPrefHeight(40);
        manageLimitsButton.setStyle(
                "-fx-background-color: #4CAF50; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 14px; " +
                        "-fx-font-weight: bold; " +
                        "-fx-background-radius: 5;");
        manageLimitsButton.setOnAction(e -> openRoleLimitDialog());

        HBox buttonRow = new HBox(10, setPinButton, manageLimitsButton);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        section.getChildren().addAll(descLabel, buttonRow);
        return section;
    }

    private void openCashCheckPinDialog() {
        com.pos.ui.dialogs.CashCheckPinDialog dialog = new com.pos.ui.dialogs.CashCheckPinDialog();
        dialog.showAndWait().ifPresent(result -> {
            if (result != null && result.success) {
                showAlert("PIN " + (result.message != null ? result.message : "saved successfully"));
            }
        });
    }

    private void openRoleLimitDialog() {
        com.pos.ui.dialogs.RoleCashCheckLimitDialog dialog = new com.pos.ui.dialogs.RoleCashCheckLimitDialog();
        dialog.showAndWait().ifPresent(result -> {
            if (result != null && result) {
                showAlert("Role limits saved successfully. Changes will sync to backend.");
            }
        });
    }

    private VBox createBackupSection() {
        VBox section = createSection("Database Backup");
        section.setSpacing(15);

        backupStatusLabel = new Label("Checking backup status...");
        backupStatusLabel.setWrapText(true);
        backupStatusLabel.setStyle("-fx-font-size: 13px;");

        backupNowButton = new Button("Create Backup Now");
        backupNowButton.setPrefWidth(180);
        backupNowButton.setPrefHeight(40);
        backupNowButton.setStyle(
                "-fx-background-color: #4CAF50; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 14px; " +
                        "-fx-font-weight: bold; " +
                        "-fx-background-radius: 5;");
        backupNowButton.setOnAction(e -> createBackupNow());

        restoreBackupButton = new Button("Restore Backup");
        restoreBackupButton.setPrefWidth(180);
        restoreBackupButton.setPrefHeight(40);
        restoreBackupButton.setStyle(
                "-fx-background-color: #FF9800; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 14px; " +
                        "-fx-font-weight: bold; " +
                        "-fx-background-radius: 5;");
        restoreBackupButton.setOnAction(e -> restoreBackup());

        Label noteLabel = new Label(
                "Restore is applied on the next app launch. A safety snapshot is created before queueing the restore.");
        noteLabel.setWrapText(true);
        noteLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        HBox buttonRow = new HBox(10, backupNowButton, restoreBackupButton);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        section.getChildren().addAll(backupStatusLabel, buttonRow, noteLabel);
        return section;
    }

    private void updateModeDescription(Label label, String mode) {
        if ("AUTO".equals(mode)) {
            label.setText("Receipts will be printed automatically after every successful transaction.");
        } else {
            label.setText("A prompt will appear after every transaction asking if you want to print a receipt.");
        }
    }

    private HBox createBottomSection() {
        HBox section = new HBox(10);
        section.setPadding(new Insets(10));
        section.setAlignment(Pos.CENTER);

        refreshSettingsButton = new Button("Refresh Settings");
        refreshSettingsButton.setPrefWidth(150);
        refreshSettingsButton.setPrefHeight(40);
        refreshSettingsButton.setOnAction(e -> refreshSettings());

        syncNowButton = new Button("Sync Now");
        syncNowButton.setPrefWidth(150);
        syncNowButton.setPrefHeight(40);
        syncNowButton.setOnAction(e -> syncNow());

        outboundSyncButton = new Button("Outbound Sync");
        outboundSyncButton.setPrefWidth(150);
        outboundSyncButton.setPrefHeight(40);
        outboundSyncButton.setOnAction(e -> runOutboundSync());

        markAllSyncedButton = new Button("Mark All as Synced");
        markAllSyncedButton.setPrefWidth(180);
        markAllSyncedButton.setPrefHeight(40);
        markAllSyncedButton.setOnAction(e -> confirmMarkAllPendingAsSynced());

        Button backfillButton = new Button("Sync from Backend");
        backfillButton.setPrefWidth(180);
        backfillButton.setPrefHeight(40);
        backfillButton.setOnAction(e -> backfillFromBackend(backfillButton));

        section.getChildren().addAll(
                refreshSettingsButton, syncNowButton, outboundSyncButton, markAllSyncedButton, backfillButton);
        return section;
    }

    /**
     * Recover transactional data (sales, refunds, shifts, vendor payouts) from
     * the backend back into the local database. Prompts for the number of days
     * to pull, then downloads and upserts them. Used after local data loss.
     */
    private void backfillFromBackend(Button trigger) {
        TextInputDialog daysDialog = new TextInputDialog("30");
        daysDialog.setTitle("Sync from Backend");
        daysDialog.setHeaderText("Recover sales data from the server");
        daysDialog.setContentText("Number of days to pull (1-365):");
        DialogHelper.setDialogOwner(daysDialog, getScene() != null ? getScene().getWindow() : null);

        java.util.Optional<String> answer = daysDialog.showAndWait();
        if (answer.isEmpty()) {
            return;
        }

        final int days;
        try {
            int parsed = Integer.parseInt(answer.get().trim());
            if (parsed < 1 || parsed > 365) {
                showAlert("Please enter a number of days between 1 and 365.");
                return;
            }
            days = parsed;
        } catch (NumberFormatException ex) {
            showAlert("'" + answer.get() + "' is not a valid number of days.");
            return;
        }

        trigger.setDisable(true);
        new Thread(() -> {
            try {
                com.pos.sync.inbound.BackfillInboundSync.BackfillCounts counts =
                        com.pos.sync.inbound.BackfillInboundSync.getInstance().backfill(days);
                Platform.runLater(() -> {
                    showAlert(String.format(
                            "Synced from backend (last %d days):%n%n"
                                    + "  • Sales: %d%n"
                                    + "  • Refunds: %d%n"
                                    + "  • Shifts: %d%n"
                                    + "  • Cash operations: %d%n"
                                    + "  • Vendor payouts: %d%n%n"
                                    + "Note: expenses are not stored on the server and cannot be recovered.",
                            days, counts.sales, counts.refunds, counts.shifts,
                            counts.cashOperations, counts.vendorPayouts));
                    updateSyncStatus();
                    trigger.setDisable(false);
                });
            } catch (Exception ex) {
                logger.error("Backfill from backend failed", ex);
                Platform.runLater(() -> {
                    showAlert("Sync from backend failed: " + ex.getMessage());
                    trigger.setDisable(false);
                });
            }
        }).start();
    }

    private void loadSettings() {
        new Thread(() -> {
            try {
                // Load device info
                if (deviceService.isDeviceRegistered()) {
                    try {
                        var deviceInfo = deviceService.getDeviceInfo();
                        Platform.runLater(() -> {
                            deviceInfoLabel.setText(String.format(
                                    "Device ID: %s\nDevice Name: %s\nStore ID: %s\nStatus: Registered",
                                    deviceInfo.deviceId != null ? deviceInfo.deviceId : "N/A",
                                    deviceInfo.deviceName != null ? deviceInfo.deviceName : "N/A",
                                    deviceInfo.storeId != null ? deviceInfo.storeId : "N/A"));
                        });
                    } catch (Exception e) {
                        logger.warn("Could not fetch device info", e);
                        Platform.runLater(() -> {
                            deviceInfoLabel.setText("Device: Registered (Info unavailable)");
                        });
                    }
                } else {
                    Platform.runLater(() -> {
                        deviceInfoLabel.setText("Device: Not Registered");
                    });
                }

                // Load store settings
                StoreSettingsResponse.StoreSettingsData settings = settingsService.getSettings();
                // #region agent log
                try {
                    java.io.FileWriter fw = new java.io.FileWriter(
                            "/Users/sanjog/projects/retail_solutions/product/mobile/.cursor/debug.log", true);
                    String storeName = settings != null ? (settings.storeName != null ? settings.storeName : "null")
                            : "null";
                    String currency = settings != null ? (settings.currency != null ? settings.currency : "null")
                            : "null";
                    fw.write("{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"timestamp\":"
                            + System.currentTimeMillis()
                            + ",\"location\":\"SettingsScreen.java:193\",\"message\":\"Settings retrieved\",\"data\":{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"B\",\"settingsNull\":"
                            + (settings == null) + ",\"storeName\":\"" + storeName + "\",\"currency\":\"" + currency
                            + "\"}}\n");
                    fw.close();
                } catch (Exception e) {
                }
                // #endregion
                Platform.runLater(() -> {
                    if (settings != null) {
                        StringBuilder storeInfo = new StringBuilder();
                        storeInfo.append("Store: ").append(settings.storeName != null ? settings.storeName : "N/A").append("\n");
                        
                        // Add address if available
                        if (settings.storeAddress != null && !settings.storeAddress.isEmpty()) {
                            storeInfo.append("Address: ").append(settings.storeAddress).append("\n");
                        }
                        
                        // Add phone if available
                        if (settings.storePhone != null && !settings.storePhone.isEmpty()) {
                            storeInfo.append("Phone: ").append(settings.storePhone).append("\n");
                        }
                        
                        storeInfo.append("Currency: ").append(settings.currency != null ? settings.currency : "USD").append("\n");
                        storeInfo.append("Timezone: ").append(settings.timezone != null ? settings.timezone : "UTC").append("\n");
                        storeInfo.append(String.format("Tax Rate: %.2f%%",
                                settings.taxSettings != null && settings.taxSettings.defaultTaxRate != null
                                        ? settings.taxSettings.defaultTaxRate
                                                .multiply(java.math.BigDecimal.valueOf(100)).doubleValue()
                                        : 8.0));
                        
                        storeInfoLabel.setText(storeInfo.toString());

                        if (minimumSaleAmountField != null) {
                            if (settings.minimumSaleAmount != null
                                    && settings.minimumSaleAmount.compareTo(BigDecimal.ZERO) > 0) {
                                minimumSaleAmountField.setText(settings.minimumSaleAmount.stripTrailingZeros()
                                        .toPlainString());
                            } else {
                                minimumSaleAmountField.clear();
                            }
                        }
                    } else {
                        storeInfoLabel.setText("Store settings: Not loaded");
                    }
                });

                // Update sync status
                Platform.runLater(() -> {
                    updateSyncStatus();
                    updateBackupStatus();
                });

            } catch (Exception e) {
                logger.error("Error loading settings", e);
                Platform.runLater(() -> {
                    deviceInfoLabel.setText("Error loading device info");
                    storeInfoLabel.setText("Error loading store info");
                    if (backupStatusLabel != null) {
                        backupStatusLabel.setText("Error loading backup status");
                    }
                });
            }
        }).start();
    }

    private void refreshSettings() {
        refreshSettingsButton.setDisable(true);
        new Thread(() -> {
            try {
                settingsService.refreshSettings();
                Platform.runLater(() -> {
                    loadSettings();
                    showAlert("Settings refreshed successfully");
                    refreshSettingsButton.setDisable(false);
                });
            } catch (Exception e) {
                logger.error("Failed to refresh settings", e);
                Platform.runLater(() -> {
                    showAlert("Failed to refresh settings: " + e.getMessage());
                    refreshSettingsButton.setDisable(false);
                });
            }
        }).start();
    }

    private void syncNow() {
        syncNowButton.setDisable(true);
        new Thread(() -> {
            try {
                com.pos.service.OfflineSyncService syncService = com.pos.service.OfflineSyncService.getInstance();
                var result = syncService.syncPendingOperations(true);
                Platform.runLater(() -> {
                    showAlert(formatSyncDialogMessage("Sync finished", result));
                    updateSyncStatus();
                    syncNowButton.setDisable(false);
                });
            } catch (Exception e) {
                logger.error("Sync failed", e);
                Platform.runLater(() -> {
                    showAlert("Sync failed: " + e.getMessage());
                    syncNowButton.setDisable(false);
                });
            }
        }).start();
    }

    private void runOutboundSync() {
        outboundSyncButton.setDisable(true);
        new Thread(() -> {
            try {
                com.pos.service.OfflineSyncService syncService = com.pos.service.OfflineSyncService.getInstance();
                var result = syncService.syncPendingOperations(true);
                Platform.runLater(() -> {
                    showAlert(formatSyncDialogMessage("Outbound sync finished", result));
                    updateSyncStatus();
                    outboundSyncButton.setDisable(false);
                });
            } catch (Exception e) {
                logger.error("Outbound Sync failed", e);
                Platform.runLater(() -> {
                    showAlert("Outbound Sync failed: " + e.getMessage());
                    outboundSyncButton.setDisable(false);
                });
            }
        }).start();
    }

    private void confirmMarkAllPendingAsSynced() {
        Alert confirmAlert = new Alert(Alert.AlertType.WARNING);
        confirmAlert.setTitle("Mark All as Synced");
        confirmAlert.setHeaderText("This does not upload your data");
        confirmAlert.setContentText(
                "This clears the pending sync status on this POS and removes queued offline API requests. "
                        + "Unsent sales will not appear on the website or mobile app.\n\n"
                        + "Recovering them afterward may require a backup or technical assistance. "
                        + "Restoring an older backup can overwrite newer sales.\n\n"
                        + "To upload your records, choose Cancel, then Sync Now.");
        ButtonType markLocally = new ButtonType("Mark locally without uploading",
                javafx.scene.control.ButtonBar.ButtonData.OTHER);
        confirmAlert.getButtonTypes().setAll(ButtonType.CANCEL, markLocally);
        ((Button) confirmAlert.getDialogPane().lookupButton(markLocally)).setDefaultButton(false);
        ((Button) confirmAlert.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        Optional<ButtonType> result = confirmAlert.showAndWait();
        if (result.isPresent() && result.get() == markLocally) {
            markAllPendingAsSynced();
        }
    }

    private void markAllPendingAsSynced() {
        markAllSyncedButton.setDisable(true);
        new Thread(() -> {
            try {
                com.pos.service.OfflineSyncService syncService = com.pos.service.OfflineSyncService.getInstance();
                var result = syncService.markAllPendingAsSynced();
                Platform.runLater(() -> {
                    showAlert(formatMarkAllSyncedMessage(result));
                    updateSyncStatus();
                    markAllSyncedButton.setDisable(false);
                });
            } catch (Exception e) {
                logger.error("Failed to mark all pending as synced", e);
                Platform.runLater(() -> {
                    showAlert("Failed to clear pending sync: " + e.getMessage());
                    markAllSyncedButton.setDisable(false);
                });
            }
        }).start();
    }

    private static String formatMarkAllSyncedMessage(com.pos.sync.MarkAllAsSyncedResult result) {
        StringBuilder msg = new StringBuilder();
        msg.append("Local sync queue cleared\n\n");
        msg.append(String.format("Rows/requests updated: %d\n", result.totalRowsMarked()));
        if (result.totalRowsMarked() > 0) {
            msg.append("\nBy type:\n");
            if (result.sales > 0) {
                msg.append("  sales: ").append(result.sales).append("\n");
            }
            if (result.shifts > 0) {
                msg.append("  shifts: ").append(result.shifts).append("\n");
            }
            if (result.products > 0) {
                msg.append("  products: ").append(result.products).append("\n");
            }
            if (result.departments > 0) {
                msg.append("  departments: ").append(result.departments).append("\n");
            }
            if (result.cartCancellations > 0) {
                msg.append("  cart_cancellations: ").append(result.cartCancellations).append("\n");
            }
            if (result.refunds > 0) {
                msg.append("  refunds: ").append(result.refunds).append("\n");
            }
            if (result.cashOperations > 0) {
                msg.append("  cash_operations: ").append(result.cashOperations).append("\n");
            }
            if (result.vendors > 0) {
                msg.append("  vendors: ").append(result.vendors).append("\n");
            }
            if (result.vendorPayouts > 0) {
                msg.append("  vendor_payouts: ").append(result.vendorPayouts).append("\n");
            }
            if (result.expenses > 0) {
                msg.append("  expenses: ").append(result.expenses).append("\n");
            }
            if (result.inventoryLog > 0) {
                msg.append("  inventory_log: ").append(result.inventoryLog).append("\n");
            }
            if (result.employeeShifts > 0) {
                msg.append("  employee_shifts: ").append(result.employeeShifts).append("\n");
            }
            if (result.customers > 0) {
                msg.append("  customers: ").append(result.customers).append("\n");
            }
            if (result.pendingRequestsDeleted > 0) {
                msg.append("  pending_requests removed: ").append(result.pendingRequestsDeleted).append("\n");
            }
        }
        if (result.breakdownAfter != null) {
            msg.append("\nPending remaining: ").append(result.breakdownAfter.total());
        }
        return msg.toString();
    }

    private static String formatSyncDialogMessage(String title, com.pos.service.OfflineSyncService.SyncResult result) {
        StringBuilder msg = new StringBuilder();
        msg.append(title).append("\n");
        msg.append(String.format("Synced: %d\nFailed: %d\nRemaining: %d",
                result.synced, result.failed, result.remaining));
        if (result.roundsExecuted > 0) {
            msg.append("Rounds: ").append(result.roundsExecuted).append("\n");
        }
        if (result.breakdownAfter != null) {
            msg.append("\nPending by type (after):\n");
            msg.append("  sales: ").append(result.breakdownAfter.sales).append("\n");
            msg.append("  shifts: ").append(result.breakdownAfter.shifts).append("\n");
            msg.append("  products: ").append(result.breakdownAfter.products).append("\n");
            msg.append("  departments: ").append(result.breakdownAfter.departments).append("\n");
            msg.append("  pending_requests: ").append(result.breakdownAfter.pendingRequests).append("\n");
            msg.append("  cart_cancellations: ").append(result.breakdownAfter.cartCancellations).append("\n");
        }
        if (result.note != null && !result.note.isBlank()) {
            msg.append("\n").append(result.note);
        }
        if (result.reportPath != null && !result.reportPath.isBlank()) {
            msg.append("\n\nFull details saved to:\n").append(result.reportPath);
        }
        return msg.toString();
    }

    private void updateSyncStatus() {
        com.pos.service.OfflineSyncService syncService = com.pos.service.OfflineSyncService.getInstance();
        boolean isOnline = syncService.isOnline();
        int pendingCount = syncService.getPendingRequestCount();

        String status = isOnline ? "Online" : "Offline";
        String pending = pendingCount > 0 ? String.format(" (%d pending)", pendingCount) : "";
        String reportHint = pendingCount > 0
                ? " — see Documents/Pasal POS 2/sync logs after Sync Now"
                : "";

        syncStatusLabel.setText(String.format("Status: %s%s%s", status, pending, reportHint));
        syncStatusLabel.setStyle(isOnline ? "-fx-text-fill: #4CAF50;" : "-fx-text-fill: #f44336;");
    }

    private void updateBackupStatus() {
        BackupService backupService = BackupService.getInstance();
        boolean supported = backupService.isBackupSupported();

        if (backupNowButton != null) {
            backupNowButton.setDisable(!supported);
        }
        if (restoreBackupButton != null) {
            restoreBackupButton.setDisable(!supported);
        }

        if (backupStatusLabel != null) {
            backupStatusLabel.setText(backupService.getStatusSummary());
        }
    }

    private void createBackupNow() {
        backupNowButton.setDisable(true);
        new Thread(() -> {
            try {
                BackupService.BackupInfo backupInfo = BackupService.getInstance().createManualBackup();
                Platform.runLater(() -> {
                    showAlert("Backup created successfully.\n\n" + backupInfo.path);
                    updateBackupStatus();
                    backupNowButton.setDisable(false);
                });
            } catch (Exception e) {
                logger.error("Manual backup failed", e);
                Platform.runLater(() -> {
                    showError("Backup Failed", "Failed to create backup: " + e.getMessage());
                    updateBackupStatus();
                    backupNowButton.setDisable(false);
                });
            }
        }, "ManualBackupThread").start();
    }

    private void restoreBackup() {
        BackupService backupService = BackupService.getInstance();
        if (!backupService.isBackupSupported()) {
            showError("Restore Unavailable", "Restore is only supported for the local H2 file database.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Backup Archive");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Backup Archives", "*.zip"));

        Path backupDirectory = backupService.getBackupDirectory();
        if (backupDirectory != null && Files.isDirectory(backupDirectory)) {
            chooser.setInitialDirectory(backupDirectory.toFile());
        }

        File selectedFile = chooser.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
        if (selectedFile == null) {
            return;
        }

        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle("Restore Backup");
        confirmAlert.setHeaderText("Queue Database Restore");
        confirmAlert.setContentText(
                "Selected backup:\n" + selectedFile.getAbsolutePath() + "\n\n" +
                        "The app will create a safety snapshot, queue this restore, and close.\n" +
                        "When you launch the app again, the selected backup will be applied before startup.\n\n" +
                        "Continue?");
        DialogHelper.setAlertOwner(confirmAlert, getScene() != null ? getScene().getWindow() : null);
        confirmAlert.setHeaderText("Complete System Reset");
        confirmAlert.setContentText(
                "WARNING: This will permanently delete local data.\n\n" +
                        "- A safety database backup will be created first when available\n" +
                        "- All database data (products, sales, users, etc.)\n" +
                        "- Device registration information\n" +
                        "- User session and authentication tokens\n" +
                        "- Cached files and logs (for privacy)\n\n" +
                        "This action cannot be undone.\n\n" +
                        "After reset, you will need to:\n" +
                        "1. Register the device again\n" +
                        "2. Re-sync all data from the backend\n\n" +
                        "Are you sure you want to continue?");

        Optional<ButtonType> result = confirmAlert.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        restoreBackupButton.setDisable(true);
        new Thread(() -> {
            try {
                BackupService.BackupInfo safetySnapshot = backupService.queueRestoreOnNextStartup(selectedFile.toPath());
                Platform.runLater(() -> {
                    Alert successAlert = new Alert(Alert.AlertType.INFORMATION);
                    successAlert.setTitle("Restore Queued");
                    successAlert.setHeaderText("Backup restore queued successfully");
                    successAlert.setContentText(
                            "A safety snapshot was created at:\n" + safetySnapshot.path + "\n\n" +
                                    "The application will close now. Reopen it to apply the selected restore archive.");
                    DialogHelper.setAlertOwner(successAlert, getScene() != null ? getScene().getWindow() : null);
                    successAlert.showAndWait();

                    Platform.exit();
                    System.exit(0);
                });
            } catch (Exception e) {
                logger.error("Failed to queue restore", e);
                Platform.runLater(() -> {
                    showError("Restore Failed", "Failed to queue restore: " + e.getMessage());
                    updateBackupStatus();
                    restoreBackupButton.setDisable(false);
                });
            }
        }, "QueueRestoreThread").start();
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Settings");
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogHelper.setAlertOwner(alert, getScene() != null ? getScene().getWindow() : null);
        alert.showAndWait();
    }

    private void checkForUpdates() {
        checkForUpdatesButton.setDisable(true);
        checkForUpdatesButton.setText("Checking...");
        logger.info("Starting update check initiated by user");

        new Thread(() -> {
            try {
                UpdateService updateService = UpdateService.getInstance();
                UpdateService.UpdateInfo updateInfo = updateService.checkForUpdates(true);

                Platform.runLater(() -> {
                    try {
                        if (updateInfo != null) {
                            logger.info("Update available: {}", updateInfo.version);
                            promptForUpdate(updateInfo);
                        } else {
                            logger.info("No update available");
                            String currentVersion = ConfigManager.getInstance().getProperty("app.version", "1.0.0");
                            showAlert("You are running the latest version (" + currentVersion + ").");
                        }
                    } catch (Exception e) {
                        logger.error("Error processing update info", e);
                        showError("Update Error", "An error occurred while displaying update info: " + e.getMessage());
                    } finally {
                        // Ensure button is always re-enabled
                        checkForUpdatesButton.setDisable(false);
                        checkForUpdatesButton.setText("Check for Updates");
                    }
                });
            } catch (java.io.IOException e) {
                logger.error("Failed to check for updates (IO/Network)", e);
                Platform.runLater(() -> {
                    checkForUpdatesButton.setDisable(false);
                    checkForUpdatesButton.setText("Check for Updates");
                    showError("Update Check Failed",
                            "Failed to connect to update server.\n\n" +
                                    "Possible causes:\n" +
                                    "• No internet connection\n" +
                                    "• GitHub API rate limit exceeded\n" +
                                    "• Firewall blocking connection\n\n" +
                                    "Error Details: " + e.getMessage());
                });
            } catch (Throwable e) {
                // Catch Throwable to ensure we handle RuntimeExceptions and Errors
                logger.error("Unexpected error during update check", e);
                Platform.runLater(() -> {
                    checkForUpdatesButton.setDisable(false);
                    checkForUpdatesButton.setText("Check for Updates");
                    showError("Update Check Failed", "An unexpected error occurred: " + e.getMessage());
                });
            }
        }).start();
    }

    private void promptForUpdate(UpdateService.UpdateInfo updateInfo) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Update Available");
        alert.setHeaderText("A new version is available: " + updateInfo.version);

        String releaseNotes = updateInfo.releaseNotes != null ? updateInfo.releaseNotes : "No release notes available.";

        if (updateInfo.downloadUrl == null) {
            alert.setContentText("Release Notes:\n" + releaseNotes
                    + "\n\nNo automatic installer is available for your system. Please download it manually.");

            ButtonType openBrowserButton = new ButtonType("Open Github Release");
            ButtonType closeButton = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);

            alert.getButtonTypes().setAll(openBrowserButton, closeButton);
            DialogHelper.setAlertOwner(alert, getScene() != null ? getScene().getWindow() : null);

            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get() == openBrowserButton) {
                if (updateInfo.htmlUrl != null && !updateInfo.htmlUrl.isEmpty()) {
                    try {
                        java.awt.Desktop.getDesktop().browse(java.net.URI.create(updateInfo.htmlUrl));
                    } catch (Exception e) {
                        logger.error("Failed to open browser", e);
                        showError("Error", "Could not open browser: " + e.getMessage());
                    }
                } else {
                    showError("Error", "No release URL available.");
                }
            }
            return;
        }

        alert.setContentText("Release Notes:\n" + releaseNotes + "\n\nDo you want to download and install it now?");

        ButtonType updateButton = new ButtonType("Download & Install");
        ButtonType laterButton = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);

        alert.getButtonTypes().setAll(updateButton, laterButton);
        DialogHelper.setAlertOwner(alert, getScene() != null ? getScene().getWindow() : null);

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
        content.setPadding(new Insets(20));
        progressDialog.getDialogPane().setContent(content);
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);

        DialogHelper.setDialogOwner(progressDialog, getScene() != null ? getScene().getWindow() : null);

        progressDialog.show();

        Thread downloadThread = new Thread(() -> {
            try {
                Path filePath = UpdateService.getInstance().downloadUpdate(updateInfo, progress -> {
                    Platform.runLater(() -> {
                        progressBar.setProgress(progress);
                        statusLabel.setText(String.format("Downloading... %.0f%%", progress * 100));
                    });
                });

                Platform.runLater(() -> {
                    progressDialog.close();
                    try {
                        UpdateService.getInstance().installUpdate(filePath);
                        // Exit application so installer can replace files without locks
                        logger.info("Update installer launched. Exiting application for installation.");
                        System.exit(0);
                    } catch (Exception e) {
                        logger.error("Failed to install update", e);
                        showError("Update Failed", "Failed to launch installer: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                logger.error("Update preparation failed", e);
                Platform.runLater(() -> {
                    progressDialog.close();
                    showError("Update Failed", "Failed to prepare update: " + e.getMessage());
                });
            }
        });
        downloadThread.setName("UpdateDownloadThread");
        downloadThread.setDaemon(true);
        downloadThread.start();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogHelper.setAlertOwner(alert, getScene() != null ? getScene().getWindow() : null);
        alert.showAndWait();
    }

    private void resetRegistration() {
        // Show confirmation dialog with warning about full data deletion
        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle("Reset Pasal POS 2");
        confirmAlert.setHeaderText("⚠️ Complete System Reset");
        confirmAlert.setContentText(
                "WARNING: This will PERMANENTLY DELETE ALL LOCAL DATA:\n\n" +
                        "\u2022 A safety database backup will be created first\n" +
                        "• All database data (products, sales, users, etc.)\n" +
                        "• Device registration information\n" +
                        "• User session and authentication tokens\n" +
                        "• Cached files and logs (for privacy)\n\n" +
                        "This action CANNOT be undone!\n\n" +
                        "After reset, you will be logged out and must:\n" +
                        "1. Register the device again\n" +
                        "2. Re-sync all data from the backend\n\n" +
                        "Are you absolutely sure you want to continue?");
        DialogHelper.setAlertOwner(confirmAlert, getScene() != null ? getScene().getWindow() : null);
        confirmAlert.setHeaderText("Complete System Reset");
        confirmAlert.setContentText(
                "WARNING: This will permanently delete local data.\n\n" +
                        "- A safety database backup will be created first when available\n" +
                        "- All database data (products, sales, users, etc.)\n" +
                        "- Device registration information\n" +
                        "- User session and authentication tokens\n" +
                        "- Cached files and logs (for privacy)\n\n" +
                        "This action cannot be undone.\n\n" +
                        "After reset, you will need to:\n" +
                        "1. Register the device again\n" +
                        "2. Re-sync all data from the backend\n\n" +
                        "Are you sure you want to continue?");

        Optional<ButtonType> result = confirmAlert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            // Perform reset on a background thread to avoid blocking UI
            new Thread(() -> {
                try {
                    logger.info("Starting complete system reset...");

                    BackupService backupService = BackupService.getInstance();
                    if (backupService.isBackupSupported()) {
                        try {
                            BackupService.BackupInfo backupInfo = backupService.createPreResetBackup();
                            logger.info("Created pre-reset backup at {}", backupInfo.path);
                        } catch (Exception e) {
                            logger.error("Failed to create pre-reset backup", e);
                            Platform.runLater(() -> {
                                showError("Reset Aborted",
                                        "Failed to create the safety backup before reset: " + e.getMessage());
                            });
                            return;
                        }
                    }

                    // Step 1: Logout current user
                    try {
                        UserAuthService authService = UserAuthService.getInstance();
                        authService.logout();
                        logger.info("User logged out successfully");
                    } catch (Exception e) {
                        logger.warn("Error during logout (continuing with reset): {}", e.getMessage());
                    }

                    // Step 2: Delete database and all data
                    try {
                        DatabaseManager dbManager = DatabaseManager.getInstance();

                        // Important: The application must be restarted after database deletion
                        // because the database files may be locked by active connections
                        logger.info("Attempting to delete database...");
                        logger.warn("NOTE: Application restart may be required if database files are locked");

                        dbManager.deleteDatabase();
                        logger.info("Database deleted successfully");

                        // Wait a moment to ensure files are fully deleted
                        Thread.sleep(1000);

                        // CRITICAL FIX: Re-initialize schema immediately after deletion
                        // This ensures tables exist before we navigate to registration/login
                        logger.info("Re-initializing database schema...");
                        dbManager.initializeSchema();
                        logger.info("Database schema re-initialized successfully");

                    } catch (Exception e) {
                        logger.error("Error deleting database", e);
                        Platform.runLater(() -> {
                            Alert errorAlert = new Alert(Alert.AlertType.ERROR);
                            errorAlert.setTitle("Database Deletion Failed");
                            errorAlert.setHeaderText("Could Not Delete Database");
                            errorAlert.setContentText(
                                    "Failed to delete database: " + e.getMessage() +
                                            "\n\nThis usually happens because:\n" +
                                            "• The database files are locked by active connections\n" +
                                            "• H2 web console is still running\n" +
                                            "• Another instance of the application is running\n\n" +
                                            "SOLUTION:\n" +
                                            "1. Close H2 web console in your browser (if open)\n" +
                                            "2. Close this application completely\n" +
                                            "3. Run the clear-database.sh script from terminal:\n" +
                                            "   cd pos-system && ./clear-database.sh\n" +
                                            "4. Restart the application");
                            DialogHelper.setAlertOwner(errorAlert, getScene() != null ? getScene().getWindow() : null);
                            errorAlert.showAndWait();
                        });
                        return; // Don't continue with reset if database deletion failed
                    }

                    // Step 3: Clear cached files and logs for privacy
                    clearCacheAndLogs();

                    // Step 4: Clear all registration-related properties
                    ConfigManager config = ConfigManager.getInstance();
                    config.setProperty("api.key", "");
                    config.setProperty("api.secret", "");
                    config.setProperty("device.id", "");
                    config.setProperty("store.id", "");
                    config.setProperty("device.name", "");
                    config.setProperty("register.number", "");
                    config.setProperty("store.name", "");

                    // Clear credentials in ApiClient
                    apiClient.setCredentials("", "");
                    apiClient.setUserToken("");
                    config.setProperty("user.token", "");

                    logger.info("System reset completed successfully");

                    // Step 5: Show success message and instruct user to restart
                    Platform.runLater(() -> {
                        Alert successAlert = new Alert(Alert.AlertType.INFORMATION);
                        successAlert.setTitle("Reset Complete");
                        successAlert.setHeaderText("System Reset Completed");
                        successAlert.setContentText(
                                "All local data has been deleted and the database has been reset.\n\n" +
                                        "You have been logged out and must now:\n" +
                                        "1. Register the device again\n" +
                                        "2. Re-sync all data from the backend");
                        DialogHelper.setAlertOwner(successAlert, getScene() != null ? getScene().getWindow() : null);
                        successAlert.showAndWait();

                        // Step 6: Navigate to Device Registration screen (or close app)
                        try {
                            Stage stage = (Stage) getScene().getWindow();
                            if (stage != null) {
                                // Get screen dimensions
                                javafx.geometry.Rectangle2D screenBounds = javafx.stage.Screen.getPrimary()
                                        .getVisualBounds();

                                // Create device registration screen
                                DeviceRegistrationScreen registrationScreen = new DeviceRegistrationScreen(stage);
                                Scene registrationScene = new Scene(registrationScreen, screenBounds.getWidth(),
                                        screenBounds.getHeight());

                                // Load stylesheet if available
                                try {
                                    registrationScene.getStylesheets().add(
                                            getClass().getResource("/styles/application.css").toExternalForm());
                                } catch (Exception e) {
                                    logger.warn("Stylesheet not found, using default styles", e);
                                }

                                // Set the new scene
                                stage.setScene(registrationScene);
                                stage.setTitle("Pasal POS 2 - Device Registration");
                                stage.setResizable(false);
                                stage.setFullScreenExitKeyCombination(javafx.scene.input.KeyCombination.NO_MATCH);
                                stage.setFullScreenExitHint("");

                                // Prevent fullscreen exit
                                stage.fullScreenProperty().addListener((obs, wasFullScreen, isNowFullScreen) -> {
                                    if (!isNowFullScreen && wasFullScreen) {
                                        Platform.runLater(() -> {
                                            stage.setFullScreen(true);
                                        });
                                    }
                                });

                                // Set fullscreen after scene is set (required for macOS)
                                stage.setMaximized(true);
                                stage.show();
                                stage.toFront();
                                Platform.runLater(() -> {
                                    stage.setFullScreen(true);
                                });

                                logger.info("Navigated to Device Registration screen");
                            }
                        } catch (Exception e) {
                            logger.error("Failed to navigate to registration screen", e);
                            // Fallback: show alert with restart instruction
                            Alert errorAlert = new Alert(Alert.AlertType.WARNING);
                            errorAlert.setTitle("Reset Complete");
                            errorAlert.setHeaderText("System Reset Completed");
                            errorAlert.setContentText(
                                    "All local data has been deleted successfully.\n\n" +
                                            "However, navigation to the registration screen failed.\n\n" +
                                            "Please RESTART the application to register the device again.\n\n" +
                                            "Error: " + e.getMessage());
                            DialogHelper.setAlertOwner(errorAlert, getScene() != null ? getScene().getWindow() : null);
                            errorAlert.showAndWait();
                        }
                    });

                } catch (Exception e) {
                    logger.error("Failed to reset system", e);
                    Platform.runLater(() -> {
                        showError("Reset Failed", "Failed to complete system reset: " + e.getMessage());
                    });
                }
            }, "SystemResetThread").start();
        }
    }

    /**
     * Clear cached files and logs for privacy.
     * Removes log files from the standard log directory.
     */
    private void clearCacheAndLogs() {
        try {
            // Determine log directory based on OS
            String userHome = System.getProperty("user.home");
            String os = System.getProperty("os.name").toLowerCase();
            Path logDir = null;

            if (os.contains("win")) {
                logDir = Paths.get(System.getenv("APPDATA"), "Pasal POS 2", "logs");
            } else if (os.contains("mac")) {
                logDir = Paths.get(userHome, "Library", "Logs", "POS");
            } else {
                // Linux/Unix
                logDir = Paths.get(userHome, ".pos-system", "logs");
            }

            // Try to delete log files if directory exists
            if (logDir != null && Files.exists(logDir)) {
                File logDirectory = logDir.toFile();
                File[] logFiles = logDirectory
                        .listFiles((dir, name) -> name.endsWith(".log") || name.endsWith(".log.gz"));

                if (logFiles != null) {
                    int deletedCount = 0;
                    for (File logFile : logFiles) {
                        try {
                            if (logFile.delete()) {
                                deletedCount++;
                            }
                        } catch (Exception e) {
                            logger.debug("Could not delete log file {}: {}", logFile.getName(), e.getMessage());
                        }
                    }
                    if (deletedCount > 0) {
                        logger.info("Deleted {} log file(s) for privacy", deletedCount);
                    }
                }
            }
        } catch (Exception e) {
            // Don't fail the reset if log clearing fails
            logger.warn("Could not clear log files: {}", e.getMessage());
        }
    }
}
