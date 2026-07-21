package com.pos.ui;

import com.pos.api.dto.ShiftResponse;
import com.pos.service.EndOfDayReportService;
import com.pos.service.ShiftService;
import com.pos.service.UserAuthService;
import com.pos.ui.components.NumericKeypad;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.TouchTextField;
import com.pos.ui.components.ToastNotification;
import com.pos.util.ReceiptPrintHelper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.List;

/**
 * Enhanced End Day screen for manager-level daily reconciliation and Z-Report generation.
 * Rebuilt with a premium UI and integrated shift-closing logic.
 */
public class EndDayScreen extends StackPane {
    private static final Logger logger = LoggerFactory.getLogger(EndDayScreen.class);
    
    private final ShiftService shiftService = ShiftService.getInstance();
    private final UserAuthService authService = UserAuthService.getInstance();
    private final EndOfDayReportService eodReportService = EndOfDayReportService.getInstance();
    private final Runnable onBack;
    
    private BigDecimal expectedCashTotal = BigDecimal.ZERO;
    private List<ShiftResponse.ShiftData> activeShifts;
    
    private Label totalSalesValueLabel;
    private Label expectedCashValueLabel;
    private Label actualNetIntakeValueLabel;
    private Label varianceValueLabel;
    private Label pendingShiftsCountLabel;
    
    private TouchTextField actualCashField;
    private Button confirmButton;
    private BorderPane mainLayout;
    private StackPane overlayContainer;
    
    private final StringBuilder amountBuffer = new StringBuilder();
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

    public EndDayScreen(Runnable onBack) {
        this.onBack = onBack;
        initialize();
    }

    private void initialize() {
        getStyleClass().add("end-day-screen");
        
        mainLayout = new BorderPane();
        mainLayout.setPadding(new Insets(40));
        mainLayout.setStyle("-fx-background-color: #f8fafc;");

        // Header Section
        VBox header = createHeader();
        mainLayout.setTop(header);

        // Center Content Section
        HBox content = createMainContent();
        mainLayout.setCenter(content);

        // Footer Actions Section
        HBox footer = createFooter();
        mainLayout.setBottom(footer);

        overlayContainer = new StackPane();
        overlayContainer.setMouseTransparent(true);
        overlayContainer.setVisible(false);

        getChildren().addAll(mainLayout, overlayContainer);

        loadDailyData();
    }

    private VBox createHeader() {
        VBox header = new VBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 40, 0));

        Label title = new Label("End of Day Reconciliation");
        title.setStyle("-fx-font-size: 36px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");
        
        Label subtitle = new Label("Review daily summary, reconcile final cash, and close all active sessions for Z-Report.");
        subtitle.setStyle("-fx-font-size: 18px; -fx-text-fill: #64748b;");

        header.getChildren().addAll(title, subtitle);
        return header;
    }

    private HBox createMainContent() {
        HBox mainContent = new HBox(30);
        mainContent.setAlignment(Pos.CENTER);
        
        // Left Side: Day Summary Card
        VBox summaryContainer = createDaySummaryCard();
        summaryContainer.setPrefWidth(400);
        
        // Center: Input and Quick Actions
        VBox inputContainer = createInputSection();
        inputContainer.setPrefWidth(450);
        
        // Right Side: Numeric Keypad
        NumericKeypad keypad = new NumericKeypad(true, false, false);
        keypad.setListener(this::handleKeypadInput);
        keypad.setPrefWidth(350);
        keypad.setStyle("-fx-background-color: white; -fx-padding: 20; -fx-background-radius: 20; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 20, 0, 0, 10);");
        
        mainContent.getChildren().addAll(summaryContainer, inputContainer, keypad);
        return mainContent;
    }

    private VBox createDaySummaryCard() {
        VBox card = new VBox(25);
        card.setPadding(new Insets(30));
        card.setStyle("-fx-background-color: white; -fx-background-radius: 20; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 20, 0, 0, 10);");
        card.setMaxWidth(500);

        Label summaryTitle = new Label("DAILY SUMMARY");
        summaryTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #64748b; -fx-letter-spacing: 1.5px;");
        
        VBox detailsGrid = new VBox(15);
        
        createDetailRow(detailsGrid, "Date:", LocalDate.now().toString());
        totalSalesValueLabel = createDetailRow(detailsGrid, "Total Gross Sales:", "$0.00");
        
        Separator sep1 = new Separator();
        sep1.setPadding(new Insets(10, 0, 10, 0));
        detailsGrid.getChildren().add(sep1);
        
        pendingShiftsCountLabel = createDetailRow(detailsGrid, "Active Shifts:", "Checking...");
        expectedCashValueLabel = createDetailRow(detailsGrid, "Expected Net Collection:", "$0.00");
        expectedCashValueLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #2563eb;");
        
        actualNetIntakeValueLabel = createDetailRow(detailsGrid, "Actual Net Collection:", "$0.00");
        actualNetIntakeValueLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");
        
        varianceValueLabel = createDetailRow(detailsGrid, "Variance:", "-");
        varianceValueLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        card.getChildren().addAll(summaryTitle, detailsGrid);
        return card;
    }

    private Label createDetailRow(VBox parent, String labelText, String valueText) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        
        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 16px; -fx-text-fill: #64748b;");
        label.setMinWidth(150);
        
        Label value = new Label(valueText);
        value.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        row.getChildren().addAll(label, spacer, value);
        parent.getChildren().add(row);
        return value;
    }

    private VBox createInputSection() {
        VBox container = new VBox(25);
        container.setPadding(new Insets(30));
        container.setStyle("-fx-background-color: white; -fx-background-radius: 20; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 20, 0, 0, 10);");

        VBox amountBox = new VBox(10);
        Label amountLabel = new Label("TOTAL ACTUAL DRAWER (INC. FLOAT)");
        amountLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #64748b; -fx-letter-spacing: 1.5px;");
        
        actualCashField = TouchScreenComponents.createTouchOnlyNumericField("0.00");
        actualCashField.setPrefHeight(80);
        actualCashField.getTextField().setStyle("-fx-font-size: 36px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        actualCashField.getTextField().setEditable(false);
        actualCashField.setShowKeypad(false);
        
        actualCashField.textProperty().addListener((obs, oldVal, newVal) -> updateVariance(newVal));
        
        amountBox.getChildren().addAll(amountLabel, actualCashField);

        VBox noteBox = new VBox(10);
        Label noteLabel = new Label("SYSTEM STATUS");
        noteLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #64748b; -fx-letter-spacing: 1.5px;");
        
        Label statusInfo = new Label("Ending the day will automatically close all active shifts and generate the Z-Report. Note: Reconcile the total cash currently in the drawer including the starting float.");
        statusInfo.setWrapText(true);
        statusInfo.setStyle("-fx-font-size: 15px; -fx-text-fill: #475569; -fx-font-style: italic;");
        
        noteBox.getChildren().addAll(noteLabel, statusInfo);

        container.getChildren().addAll(amountBox, noteBox);
        return container;
    }

    private HBox createFooter() {
        HBox footer = new HBox(30);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(40, 0, 0, 0));

        Button backButton = new Button("← Back to POS");
        backButton.setPrefSize(240, 70);
        backButton.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 18px; -fx-background-radius: 12; -fx-cursor: hand;");
        backButton.setOnAction(e -> onBack.run());

        confirmButton = new Button("END BUSINESS DAY");
        confirmButton.setPrefSize(320, 70);
        confirmButton.setStyle("-fx-background-color: #059669; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 18px; -fx-background-radius: 12; -fx-cursor: hand; -fx-effect: dropshadow(gaussian, rgba(5, 150, 105, 0.3), 10, 0, 0, 5);");
        confirmButton.setOnAction(e -> performEndDay());

        footer.getChildren().addAll(backButton, confirmButton);
        return footer;
    }

    private void loadDailyData() {
        new Thread(() -> {
            try {
                LocalDate today = LocalDate.now();
                var report = eodReportService.generateReport(today, authService.getCurrentUserName());
                activeShifts = shiftService.getAllActiveShifts();
                
                expectedCashTotal = report.cashExpected;
                final BigDecimal dailyStartCash = report.startingCash;
                
                Platform.runLater(() -> {
                    totalSalesValueLabel.setText(currencyFormat.format(report.grossSales));
                    expectedCashValueLabel.setText(currencyFormat.format(expectedCashTotal));
                    pendingShiftsCountLabel.setText(String.valueOf(activeShifts.size()));
                    
                    // Store starting cash for variance calculation
                    actualCashField.setUserData(dailyStartCash);
                    
                    // Prefill with expected net activity only.
                    setAmount(expectedCashTotal);
                    
                    if (!activeShifts.isEmpty()) {
                        pendingShiftsCountLabel.setStyle("-fx-text-fill: #f59e0b;");
                    } else {
                        pendingShiftsCountLabel.setStyle("-fx-text-fill: #10b981;");
                    }
                    
                    actualCashField.focusTextField();
                });
            } catch (Exception e) {
                logger.error("Error loading end day data", e);
                Platform.runLater(() -> {
                    ToastNotification.showError("Failed to load daily data: " + e.getMessage(), getScene().getWindow());
                });
            }
        }).start();
    }

    private void handleKeypadInput(String key) {
        if ("C".equals(key)) {
            amountBuffer.setLength(0);
        } else if ("⌫".equals(key)) {
            if (amountBuffer.length() > 0) {
                amountBuffer.setLength(amountBuffer.length() - 1);
            }
        } else if (".".equals(key)) {
            if (amountBuffer.indexOf(".") == -1) {
                if (amountBuffer.length() == 0) amountBuffer.append("0");
                amountBuffer.append(".");
            }
        } else if ("00".equals(key)) {
            if (amountBuffer.length() < 10) {
                amountBuffer.append("00");
            }
        } else {
            if (amountBuffer.length() < 10) {
                amountBuffer.append(key);
            }
        }
        actualCashField.setText(amountBuffer.toString());
    }

    private void setAmount(BigDecimal amount) {
        if (amount == null) return;
        amountBuffer.setLength(0);
        amountBuffer.append(amount.setScale(2, java.math.RoundingMode.HALF_UP).toString());
        actualCashField.setText(amountBuffer.toString());
    }

    private void updateVariance(String amountText) {
        if (amountText == null || amountText.trim().isEmpty()) {
            varianceValueLabel.setText("-");
            varianceValueLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");
            return;
        }

        try {
            BigDecimal actualCash = new BigDecimal(amountText.replace(",", ""));
            BigDecimal startingCash = (BigDecimal) actualCashField.getUserData();
            if (startingCash == null) startingCash = BigDecimal.ZERO;

            // Show actual net collection (Drawer - Daily Start)
            BigDecimal netIntake = actualCash.subtract(startingCash);
            actualNetIntakeValueLabel.setText(currencyFormat.format(netIntake));

            // Variance = Net Intake - Expected
            BigDecimal variance = netIntake.subtract(expectedCashTotal);
            
            varianceValueLabel.setText(currencyFormat.format(variance));
            
            if (variance.abs().compareTo(new BigDecimal("0.50")) <= 0) {
                varianceValueLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #10b981;");
            } else if (variance.compareTo(BigDecimal.ZERO) < 0) {
                varianceValueLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #ef4444;");
            } else {
                varianceValueLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #f59e0b;");
            }
        } catch (NumberFormatException e) {
            varianceValueLabel.setText("Invalid");
            varianceValueLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #ef4444;");
        }
    }

    private void performEndDay() {
        String amountText = actualCashField.getText().trim();
        if (amountText.isEmpty()) {
            ToastNotification.showWarning("Please enter actual cash count for the day", getScene().getWindow());
            return;
        }

        BigDecimal aggregateActualCash = new BigDecimal(amountText.replace(",", ""));
        
        showConfirmationOverlay(() -> {
            confirmButton.setDisable(true);
            
            new Thread(() -> {
                try {
                    // 1. Close all pending shifts
                    for (ShiftResponse.ShiftData shift : activeShifts) {
                        try {
                            BigDecimal actualDrawerTotal = shiftService.calculateExpectedDrawerTotal(shift.id);
                            shiftService.endShift(shift.id, actualDrawerTotal, "Auto-closed during End Day");
                            logger.info("Auto-closed shift {} during End Day", shift.id);
                        } catch (Exception e) {
                            logger.error("Failed to auto-close shift {} during End Day", shift.id, e);
                        }
                    }

                    // 2. Generate and Print Z-Report
                    LocalDate today = LocalDate.now();
                    String managerName = authService.getCurrentUserName();
                    
                    // We generate the report AFTER closing shifts to ensure totals are final
                    var report = eodReportService.generateReport(today, managerName);
                    // Override the aggregate actual cash in the report for printing
                    report.cashActual = aggregateActualCash;
                    report.cashShortOver = aggregateActualCash.subtract(report.startingCash).subtract(report.cashExpected);
                    
                    String receiptText = eodReportService.generateReceiptText(report);
                    
                    Platform.runLater(() -> {
                        ReceiptPrintHelper.showReceiptPreview(receiptText, getScene().getWindow(), "Z-REPORT", "Daily Z-Report Summary");
                        
                        ToastNotification.showSuccess("Business day ended correctly. Z-Report generated.", getScene().getWindow());
                        
                        // 3. Logout after successful end day
                        navigateToLogin();
                    });
                    
                } catch (Exception e) {
                    logger.error("Failed to perform end day", e);
                    Platform.runLater(() -> {
                        ToastNotification.showError("Failed to end business day: " + e.getMessage(), getScene().getWindow());
                        confirmButton.setDisable(false);
                    });
                }
            }).start();
        });
    }

    private void showConfirmationOverlay(Runnable onConfirm) {
        overlayContainer.getChildren().clear();
        overlayContainer.setMouseTransparent(false);
        overlayContainer.setVisible(true);

        Region backdrop = new Region();
        backdrop.setStyle("-fx-background-color: rgba(15, 23, 42, 0.4);");
        
        VBox dialog = new VBox(30);
        dialog.setAlignment(Pos.CENTER);
        dialog.setMaxSize(500, 350);
        dialog.setPadding(new Insets(40));
        dialog.setStyle("-fx-background-color: rgba(255, 255, 255, 0.95); -fx-background-radius: 24; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 30, 0, 0, 15);");

        Label title = new Label("End Business Day?");
        title.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        Label message = new Label("This will close all active cashier sessions, reconcile total cash, and generate the final Z-Report for today. This action cannot be undone.");
        message.setStyle("-fx-font-size: 16px; -fx-text-fill: #64748b; -fx-text-alignment: center;");
        message.setWrapText(true);

        HBox buttons = new HBox(20);
        buttons.setAlignment(Pos.CENTER);

        Button cancelButton = new Button("Review Sales");
        cancelButton.setPrefSize(180, 50);
        cancelButton.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 16px; -fx-background-radius: 10; -fx-cursor: hand;");
        cancelButton.setOnAction(e -> {
            overlayContainer.setVisible(false);
            overlayContainer.setMouseTransparent(true);
        });

        Button confirmBtn = new Button("Confirm End Day");
        confirmBtn.setPrefSize(180, 50);
        confirmBtn.setStyle("-fx-background-color: #059669; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 16px; -fx-background-radius: 10; -fx-cursor: hand;");
        confirmBtn.setOnAction(e -> {
            overlayContainer.setVisible(false);
            overlayContainer.setMouseTransparent(true);
            onConfirm.run();
        });

        buttons.getChildren().addAll(cancelButton, confirmBtn);
        dialog.getChildren().addAll(title, message, buttons);

        overlayContainer.getChildren().addAll(backdrop, dialog);
    }

    private void navigateToLogin() {
        if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage stage) {
            authService.logout();
            LoginScreen loginScreen = new LoginScreen(stage);
            javafx.geometry.Rectangle2D screenBounds = javafx.stage.Screen.getPrimary().getVisualBounds();
            Scene loginScene = new Scene(loginScreen, screenBounds.getWidth(), screenBounds.getHeight());
            try {
                loginScene.getStylesheets().add(getClass().getResource("/styles/application.css").toExternalForm());
            } catch (Exception e) {}
            stage.setScene(loginScene);
            stage.setTitle("Pasal POS 2 - Login");
            stage.setFullScreen(true);
            stage.show();
        }
    }
}
