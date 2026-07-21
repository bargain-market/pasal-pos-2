package com.pos.ui;

import com.pos.config.ConfigManager;
import com.pos.hardware.HardwareManager;
import com.pos.hardware.pax.PaxTerminalException;
import com.pos.hardware.pax.PaxTerminalService;
import com.pos.util.DialogHelper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hardware settings and configuration screen
 */
public class HardwareSettingsScreen extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(HardwareSettingsScreen.class);
    private HardwareManager hardwareManager;
    private ConfigManager configManager;
    private Runnable onBackToSales;

    // Device status labels
    private Label printerStatusLabel;
    private Label scannerStatusLabel;
    private Label cashDrawerStatusLabel;

    // Device configuration fields
    private TextField printerNameField;
    private TextField printerLogicalNameField;
    private TextField scannerLogicalNameField;
    private TextField cashDrawerLogicalNameField;

    // Test buttons
    private Button testPrinterButton;
    private Button testCashDrawerButton;
    private Button testPaxButton;

    // PAX terminal fields
    private CheckBox paxEnabledCheckBox;
    private ComboBox<String> paxCommTypeCombo;
    private TextField paxHostField;
    private TextField paxPortField;
    private TextField paxTimeoutField;
    private Label paxStatusLabel;

    public HardwareSettingsScreen() {
        this(null);
    }

    public HardwareSettingsScreen(Runnable onBackToSales) {
        this.hardwareManager = HardwareManager.getInstance();
        this.configManager = ConfigManager.getInstance();
        this.onBackToSales = onBackToSales;
        initializeUI();
        refreshDeviceStatus();
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

        Label titleLabel = new Label("Hardware Settings");
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
        FlowPane bottomSection = createBottomSection();
        setBottom(bottomSection);
    }

    private VBox createContent() {
        VBox content = new VBox(20);
        content.setPadding(new Insets(20));

        // Printer Section
        VBox printerSection = createDeviceSection("Receipt Printer", "printer");
        content.getChildren().add(printerSection);

        // Scanner Section
        VBox scannerSection = createDeviceSection("Barcode Scanner", "scanner");
        content.getChildren().add(scannerSection);

        // Cash Drawer Section
        VBox cashDrawerSection = createDeviceSection("Cash Drawer", "cashdrawer");
        content.getChildren().add(cashDrawerSection);

        // PAX Payment Terminal Section
        VBox paxSection = createPaxSection();
        content.getChildren().add(paxSection);

        return content;
    }

    private VBox createPaxSection() {
        VBox section = new VBox(15);
        section.setPadding(new Insets(15));
        section.setStyle("-fx-background-color: #f5f5f5; -fx-background-radius: 5;");

        Label sectionTitle = new Label("PAX Payment Terminal");
        sectionTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        section.getChildren().add(sectionTitle);

        paxEnabledCheckBox = new CheckBox("Enable PAX card terminal");
        paxEnabledCheckBox.setSelected(Boolean.parseBoolean(configManager.getProperty("pax.enabled", "true")));
        section.getChildren().add(paxEnabledCheckBox);

        Label statusTitle = new Label("Status:");
        statusTitle.setStyle("-fx-font-weight: bold;");
        paxStatusLabel = new Label(PaxTerminalService.getInstance().getStatusSummary());
        paxStatusLabel.setWrapText(true);

        HBox statusBox = new HBox(10);
        statusBox.getChildren().addAll(statusTitle, paxStatusLabel);
        section.getChildren().add(statusBox);

        Label commTypeTitle = new Label("Communication type:");
        commTypeTitle.setStyle("-fx-font-weight: bold;");
        paxCommTypeCombo = new ComboBox<>(javafx.collections.FXCollections.observableArrayList("TCP", "USB", "BLUETOOTH"));
        paxCommTypeCombo.setValue(configManager.getProperty("pax.comm.type", "TCP"));
        paxCommTypeCombo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(paxCommTypeCombo, Priority.ALWAYS);
        paxCommTypeCombo.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setDisable(false);
                } else {
                    setText(item);
                    setDisable(!"TCP".equals(item));
                }
            }
        });
        paxCommTypeCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
            }
        });

        HBox commTypeBox = new HBox(10);
        commTypeBox.setAlignment(Pos.CENTER_LEFT);
        commTypeBox.getChildren().addAll(commTypeTitle, paxCommTypeCombo);
        section.getChildren().add(commTypeBox);

        Label hostTitle = new Label("Terminal IP / host:");
        hostTitle.setStyle("-fx-font-weight: bold;");
        paxHostField = new TextField(configManager.getProperty("pax.comm.host", "192.168.1.100"));
        paxHostField.setPromptText("e.g. 192.168.1.100");
        paxHostField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(paxHostField, Priority.ALWAYS);
        HBox hostBox = new HBox(10);
        hostBox.setAlignment(Pos.CENTER_LEFT);
        hostBox.getChildren().addAll(hostTitle, paxHostField);
        section.getChildren().add(hostBox);

        Label portTitle = new Label("Port:");
        portTitle.setStyle("-fx-font-weight: bold;");
        paxPortField = new TextField(configManager.getProperty("pax.comm.port", "10009"));
        paxPortField.setPromptText("10009");
        paxPortField.setPrefWidth(120);

        Label timeoutTitle = new Label("Timeout (ms):");
        timeoutTitle.setStyle("-fx-font-weight: bold;");
        paxTimeoutField = new TextField(configManager.getProperty("pax.comm.timeoutMs", "60000"));
        paxTimeoutField.setPromptText("60000");
        paxTimeoutField.setPrefWidth(120);

        HBox portTimeoutBox = new HBox(15);
        portTimeoutBox.setAlignment(Pos.CENTER_LEFT);
        portTimeoutBox.getChildren().addAll(portTitle, paxPortField, timeoutTitle, paxTimeoutField);
        section.getChildren().add(portTimeoutBox);

        Label hint = new Label(
                "For BroadPOS simulator in Parallels Windows, use the VM IP (often 10.211.55.3) "
                        + "and port 10009. Ensure TCP comm is enabled in the simulator and Windows "
                        + "Firewall allows inbound on that port.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        section.getChildren().add(hint);

        testPaxButton = new Button("Test PAX Connection");
        testPaxButton.setStyle(
                "-fx-background-color: #2196F3; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 12px; " +
                        "-fx-padding: 5 15; " +
                        "-fx-background-radius: 3; " +
                        "-fx-cursor: hand;");
        testPaxButton.setOnAction(e -> testPaxConnection());
        section.getChildren().add(testPaxButton);

        return section;
    }

    private VBox createDeviceSection(String deviceName, String deviceType) {
        VBox section = new VBox(15);
        section.setPadding(new Insets(15));
        section.setStyle("-fx-background-color: #f5f5f5; -fx-background-radius: 5;");

        Label sectionTitle = new Label(deviceName);
        sectionTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        section.getChildren().add(sectionTitle);

        // Status display
        Label statusTitle = new Label("Status:");
        statusTitle.setStyle("-fx-font-weight: bold;");
        Label statusLabel;
        if ("printer".equals(deviceType)) {
            printerStatusLabel = new Label("Checking...");
            statusLabel = printerStatusLabel;
        } else if ("scanner".equals(deviceType)) {
            scannerStatusLabel = new Label("Checking...");
            statusLabel = scannerStatusLabel;
        } else {
            cashDrawerStatusLabel = new Label("Checking...");
            statusLabel = cashDrawerStatusLabel;
        }
        statusLabel.setWrapText(true);

        HBox statusBox = new HBox(10);
        statusBox.getChildren().addAll(statusTitle, statusLabel);
        section.getChildren().add(statusBox);

        // Configuration
        Label configTitle = new Label("Logical Name (JavaPOS):");
        configTitle.setStyle("-fx-font-weight: bold;");
        TextField logicalNameField;
        if ("printer".equals(deviceType)) {
            printerLogicalNameField = new TextField();
            logicalNameField = printerLogicalNameField;
        } else if ("scanner".equals(deviceType)) {
            scannerLogicalNameField = new TextField();
            logicalNameField = scannerLogicalNameField;
        } else {
            cashDrawerLogicalNameField = new TextField();
            logicalNameField = cashDrawerLogicalNameField;
        }
        // logicalNameField.setPrefWidth(200);
        logicalNameField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(logicalNameField, Priority.ALWAYS);
        logicalNameField.setPromptText("Enter logical name");

        // Load current logical name
        String configKey = deviceType + ".logicalName";
        String defaultName = deviceType.substring(0, 1).toUpperCase() + deviceType.substring(1);
        if ("cashdrawer".equals(deviceType)) {
            defaultName = "CashDrawer";
        }
        logicalNameField.setText(configManager.getProperty(configKey, defaultName));

        HBox configBox = new HBox(10);
        configBox.setAlignment(Pos.CENTER_LEFT);
        configBox.getChildren().addAll(configTitle, logicalNameField);
        section.getChildren().add(configBox);

        if ("printer".equals(deviceType)) {
            Label printerNameTitle = new Label("Windows queue name:");
            printerNameTitle.setStyle("-fx-font-weight: bold;");
            printerNameField = new TextField();
            printerNameField.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(printerNameField, Priority.ALWAYS);
            printerNameField.setPromptText("Exact name from Printers & Scanners (e.g. POS-58C)");
            printerNameField.setText(
                    configManager.getResolvedPrinterName(HardwareManager.DEFAULT_WINDOWS_PRINTER_QUEUE));
            HBox printerNameBox = new HBox(10);
            printerNameBox.setAlignment(Pos.CENTER_LEFT);
            printerNameBox.getChildren().addAll(printerNameTitle, printerNameField);
            section.getChildren().add(printerNameBox);
            Label printerNameHint = new Label(
                    "When this register has a Store ID, the queue name is saved for that store only (each location can use a different printer).");
            printerNameHint.setWrapText(true);
            printerNameHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
            section.getChildren().add(printerNameHint);
        }

        // Test button
        Button testButton = new Button("Test " + deviceName);
        testButton.setStyle(
                "-fx-background-color: #4CAF50; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 12px; " +
                        "-fx-padding: 5 15; " +
                        "-fx-background-radius: 3; " +
                        "-fx-cursor: hand;");

        if ("printer".equals(deviceType)) {
            testPrinterButton = testButton;
            testButton.setOnAction(e -> testPrinter());
        } else if ("scanner".equals(deviceType)) {
            testButton.setOnAction(e -> testScanner());
        } else {
            testCashDrawerButton = testButton;
            testButton.setOnAction(e -> testCashDrawer());
        }

        section.getChildren().add(testButton);

        return section;
    }

    /**
     * Create a styled icon button with text label for responsive layout
     */
    private Button createIconButton(String icon, String text, String color) {
        VBox content = new VBox(4);
        content.setAlignment(Pos.CENTER);

        Label iconLabel = new Label(icon);
        iconLabel.setStyle("-fx-font-size: 20px; -fx-text-fill: white;");

        Label textLabel = new Label(text);
        textLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: white;");

        content.getChildren().addAll(iconLabel, textLabel);

        Button button = new Button();
        button.setGraphic(content);
        button.setMinWidth(75);
        button.setMinHeight(60);
        button.setPrefWidth(85);
        button.setPrefHeight(65);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setStyle(
                "-fx-background-color: " + color + ";" +
                        "-fx-text-fill: white;" +
                        "-fx-background-radius: 8;" +
                        "-fx-cursor: hand;" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 4, 0, 0, 1);");

        button.setOnMouseEntered(ev -> button.setStyle(
                "-fx-background-color: derive(" + color + ", -10%);" +
                        "-fx-text-fill: white;" +
                        "-fx-background-radius: 8;" +
                        "-fx-cursor: hand;" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 6, 0, 0, 2);"));
        button.setOnMouseExited(ev -> button.setStyle(
                "-fx-background-color: " + color + ";" +
                        "-fx-text-fill: white;" +
                        "-fx-background-radius: 8;" +
                        "-fx-cursor: hand;" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 4, 0, 0, 1);"));

        return button;
    }

    private FlowPane createBottomSection() {
        FlowPane section = new FlowPane();
        section.setPadding(new Insets(15));
        section.setAlignment(Pos.CENTER);
        section.setHgap(10);
        section.setVgap(10);

        Button refreshButton = createIconButton("\u21BB", "Refresh", "#607D8B");
        refreshButton.setOnAction(e -> refreshDeviceStatus());

        Button saveButton = createIconButton("\uD83D\uDCBE", "Save", "#2196F3");
        saveButton.setOnAction(e -> saveConfiguration());

        Button reinitializeButton = createIconButton("\u26A1", "Reinit", "#FF9800");
        reinitializeButton.setOnAction(e -> reinitializeHardware());

        section.getChildren().addAll(refreshButton, saveButton, reinitializeButton);
        return section;
    }

    private void refreshDeviceStatus() {
        new Thread(() -> {
            try {
                HardwareManager.DeviceInfo printerInfo = hardwareManager.getPrinterInfo();
                HardwareManager.DeviceInfo scannerInfo = hardwareManager.getScannerInfo();
                HardwareManager.DeviceInfo cashDrawerInfo = hardwareManager.getCashDrawerInfo();

                Platform.runLater(() -> {
                    if (printerNameField != null) {
                        printerNameField.setText(
                                configManager.getResolvedPrinterName(HardwareManager.DEFAULT_WINDOWS_PRINTER_QUEUE));
                    }
                    updateDeviceStatus(printerStatusLabel, printerInfo);
                    updateDeviceStatus(scannerStatusLabel, scannerInfo);
                    updateDeviceStatus(cashDrawerStatusLabel, cashDrawerInfo);
                    if (paxStatusLabel != null) {
                        paxStatusLabel.setText(PaxTerminalService.getInstance().getStatusSummary());
                    }

                    // Update test button states
                    testPrinterButton.setDisable(!printerInfo.enabled);
                    testCashDrawerButton.setDisable(!cashDrawerInfo.enabled);
                    // Scanner test is always enabled (it's manual)
                });
            } catch (Exception e) {
                logger.error("Error refreshing device status", e);
                Platform.runLater(() -> {
                    showAlert("Error", "Failed to refresh device status: " + e.getMessage());
                });
            }
        }).start();
    }

    private void updateDeviceStatus(Label statusLabel, HardwareManager.DeviceInfo info) {
        if (statusLabel == null)
            return;

        String status;
        String color;
        if (info.enabled) {
            status = String.format("✓ Connected\nPhysical Device: %s\nLogical Name: %s",
                    info.physicalName, info.logicalName);
            color = "#4CAF50";
        } else if (info.available) {
            status = String.format("⚠ Available but not enabled\nPhysical Device: %s\nLogical Name: %s",
                    info.physicalName, info.logicalName);
            color = "#FF9800";
        } else {
            status = String.format(
                    "✗ Not Available\nLogical Name: %s\nPlease check device connection and jpos.xml configuration",
                    info.logicalName);
            color = "#f44336";
        }

        statusLabel.setText(status);
        statusLabel.setStyle("-fx-text-fill: " + color + ";");
    }

    private void testPrinter() {
        testPrinterButton.setDisable(true);
        testPrinterButton.setText("Testing...");
        hardwareManager.testPrinter().thenAccept(success -> {
            Platform.runLater(() -> {
                testPrinterButton.setDisable(false);
                testPrinterButton.setText("Test Printer");
                if (success) {
                    showAlert("Success", "Test receipt printed successfully!");
                } else {
                    showAlert("Error", "Failed to print test receipt. Please check printer connection.");
                }
            });
        });
    }

    private void testScanner() {
        showAlert("Scanner Test", "Please scan a barcode. If the scanner is working, " +
                "the barcode will appear in the product search field on the sales screen.");
    }

    private void testPaxConnection() {
        savePaxConfiguration(false);
        testPaxButton.setDisable(true);
        testPaxButton.setText("Testing...");
        new Thread(() -> {
            try {
                PaxTerminalService paxService = PaxTerminalService.getInstance();
                paxService.reloadClient();
                String result = paxService.testConnection();
                Platform.runLater(() -> {
                    testPaxButton.setDisable(false);
                    testPaxButton.setText("Test PAX Connection");
                    if (paxStatusLabel != null) {
                        paxStatusLabel.setText(result);
                        paxStatusLabel.setStyle("-fx-text-fill: #4CAF50;");
                    }
                    showAlert("PAX Terminal", result);
                });
            } catch (PaxTerminalException e) {
                Platform.runLater(() -> {
                    testPaxButton.setDisable(false);
                    testPaxButton.setText("Test PAX Connection");
                    if (paxStatusLabel != null) {
                        paxStatusLabel.setText(e.getMessage());
                        paxStatusLabel.setStyle("-fx-text-fill: #f44336;");
                    }
                    showAlert("PAX Terminal Error", e.getMessage());
                });
            } catch (Exception e) {
                logger.error("PAX connection test failed", e);
                Platform.runLater(() -> {
                    testPaxButton.setDisable(false);
                    testPaxButton.setText("Test PAX Connection");
                    showAlert("PAX Terminal Error", e.getMessage());
                });
            }
        }, "PaxConnectionTest").start();
    }

    private void savePaxConfiguration(boolean showSuccessAlert) {
        try {
            configManager.setProperty("pax.enabled", String.valueOf(paxEnabledCheckBox.isSelected()));
            configManager.setProperty("pax.comm.type", paxCommTypeCombo.getValue());
            configManager.setProperty("pax.comm.host", paxHostField.getText().trim());
            configManager.setProperty("pax.comm.port", paxPortField.getText().trim());
            configManager.setProperty("pax.comm.timeoutMs", paxTimeoutField.getText().trim());
            PaxTerminalService.getInstance().reloadClient();
            if (paxStatusLabel != null) {
                paxStatusLabel.setText(PaxTerminalService.getInstance().getStatusSummary());
            }
            if (showSuccessAlert) {
                showAlert("Success", "Configuration saved successfully!\n" +
                        "Click 'Reinitialize Hardware' to apply printer/scanner changes.");
            }
        } catch (Exception e) {
            logger.error("Error saving PAX configuration", e);
            showAlert("Error", "Failed to save PAX configuration: " + e.getMessage());
        }
    }

    private void testCashDrawer() {
        testCashDrawerButton.setDisable(true);
        testCashDrawerButton.setText("Testing...");
        hardwareManager.testCashDrawer().thenAccept(success -> {
            Platform.runLater(() -> {
                testCashDrawerButton.setDisable(false);
                testCashDrawerButton.setText("Test Cash Drawer");
                if (success) {
                    showAlert("Success", "Cash drawer opened successfully!");
                } else {
                    showAlert("Error", "Failed to open cash drawer. Please check connection.");
                }
            });
        });
    }

    private void saveConfiguration() {
        try {
            if (printerNameField != null) {
                configManager.setPrinterNameForCurrentStore(printerNameField.getText().trim());
            }
            // Save logical names
            configManager.setProperty("printer.logicalName", printerLogicalNameField.getText().trim());
            configManager.setProperty("scanner.logicalName", scannerLogicalNameField.getText().trim());
            configManager.setProperty("cashdrawer.logicalName", cashDrawerLogicalNameField.getText().trim());
            savePaxConfiguration(false);

            showAlert("Success", "Configuration saved successfully!\n" +
                    "Click 'Reinitialize Hardware' to apply changes.");
        } catch (Exception e) {
            logger.error("Error saving configuration", e);
            showAlert("Error", "Failed to save configuration: " + e.getMessage());
        }
    }

    private void reinitializeHardware() {
        Alert confirmDialog = new Alert(Alert.AlertType.CONFIRMATION);
        confirmDialog.setTitle("Reinitialize Hardware");
        confirmDialog.setHeaderText("Reinitialize Hardware Devices");
        confirmDialog.setContentText("This will close and reopen all hardware devices.\n" +
                "Make sure you have saved your configuration first.\n\n" +
                "Continue?");
        DialogHelper.setAlertOwner(confirmDialog, null);

        confirmDialog.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                new Thread(() -> {
                    try {
                        hardwareManager.reinitialize();
                        Platform.runLater(() -> {
                            refreshDeviceStatus();
                            showAlert("Success", "Hardware reinitialized successfully!");
                        });
                    } catch (Exception e) {
                        logger.error("Error reinitializing hardware", e);
                        Platform.runLater(() -> {
                            showAlert("Error", "Failed to reinitialize hardware: " + e.getMessage());
                        });
                    }
                }).start();
            }
        });
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogHelper.setAlertOwner(alert, null);
        alert.showAndWait();
    }
}
