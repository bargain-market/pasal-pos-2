package com.pos.ui;

import com.pos.api.dto.ShiftResponse;
import com.pos.service.ShiftService;
import com.pos.service.UserAuthService;
import com.pos.service.EndOfDayReportService;
import com.pos.service.EmployeeShiftService;
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

/**
 * A dedicated, premium full-screen for closing a shift.
 * Specifically for individual cashiers to end their session.
 */
public class CloseShiftScreen extends StackPane {
    private static final Logger logger = LoggerFactory.getLogger(CloseShiftScreen.class);
    
    private final ShiftService shiftService = ShiftService.getInstance();
    private final UserAuthService authService = UserAuthService.getInstance();
    private final EndOfDayReportService eodReportService = EndOfDayReportService.getInstance();
    private final EmployeeShiftService employeeShiftService = EmployeeShiftService.getInstance();
    private final Runnable onBack;
    
    private ShiftResponse.ShiftData currentShift;
    private BigDecimal expectedCash = BigDecimal.ZERO;
    
    private Label cashierValueLabel;
    private Label openingCashValueLabel;
    private Label cashSalesValueLabel;
    private Label expectedCashValueLabel;
    private Label actualNetIntakeValueLabel;
    private Label varianceValueLabel;
    
    private TouchTextField actualCashField;
    private CheckBox printReportCheckbox;
    private Button confirmButton;
    private BorderPane mainLayout;
    private StackPane overlayContainer;
    
    private final StringBuilder amountBuffer = new StringBuilder();
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

    public CloseShiftScreen(Runnable onBack) {
        this.onBack = onBack;
        initialize();
    }

    private void initialize() {
        getStyleClass().add("close-shift-screen");
        
        mainLayout = new BorderPane();
        mainLayout.setPadding(new Insets(40));
        mainLayout.setStyle("-fx-background-color: #f8fafc;");

        // Header Section
        VBox header = createHeader();
        mainLayout.setTop(header);

        // Center Content Section - Split into Summary and Input
        HBox content = createMainContent();
        mainLayout.setCenter(content);

        // Footer Actions Section
        HBox footer = createFooter();
        mainLayout.setBottom(footer);

        overlayContainer = new StackPane();
        overlayContainer.setMouseTransparent(true);
        overlayContainer.setVisible(false);

        getChildren().addAll(mainLayout, overlayContainer);

        loadShiftData();
    }

    private VBox createHeader() {
        VBox header = new VBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 40, 0));

        Label title = new Label("Close Shift");
        title.setStyle("-fx-font-size: 36px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");
        
        Label subtitle = new Label("Review shift summary and record final cash count to close your session.");
        subtitle.setStyle("-fx-font-size: 18px; -fx-text-fill: #64748b;");

        header.getChildren().addAll(title, subtitle);
        return header;
    }

    private HBox createMainContent() {
        HBox mainContent = new HBox(30);
        mainContent.setAlignment(Pos.CENTER);
        
        // Left Side: Shift Summary Card
        VBox summaryContainer = createShiftSummaryCard();
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

    private VBox createShiftSummaryCard() {
        VBox card = new VBox(25);
        card.setPadding(new Insets(30));
        card.setStyle("-fx-background-color: white; -fx-background-radius: 20; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 20, 0, 0, 10);");
        card.setMaxWidth(500);

        Label summaryTitle = new Label("SHIFT SUMMARY");
        summaryTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #64748b; -fx-letter-spacing: 1.5px;");
        
        VBox detailsGrid = new VBox(15);
        
        cashierValueLabel = createDetailRow(detailsGrid, "Cashier:", "Loading...");
        
        Separator sep1 = new Separator();
        sep1.setPadding(new Insets(10, 0, 10, 0));
        detailsGrid.getChildren().add(sep1);
        
        openingCashValueLabel = createDetailRow(detailsGrid, "Starting Cash:", "$0.00");
        cashSalesValueLabel = createDetailRow(detailsGrid, "Cash Sales:", "$0.00");
        
        Separator sep2 = new Separator();
        sep2.setPadding(new Insets(10, 0, 10, 0));
        detailsGrid.getChildren().add(sep2);
        
        expectedCashValueLabel = createDetailRow(detailsGrid, "Expected Cash:", "$0.00");
        expectedCashValueLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #2563eb;");
        
        actualNetIntakeValueLabel = createDetailRow(detailsGrid, "Actual Total Cash:", "$0.00");
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

        // Actual Cash Count Input
        VBox amountBox = new VBox(10);
        Label amountLabel = new Label("ACTUAL DRAWER TOTAL (INC. FLOAT)");
        amountLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #64748b; -fx-letter-spacing: 1.5px;");
        
        actualCashField = TouchScreenComponents.createTouchOnlyNumericField("0.00");
        actualCashField.setPrefHeight(80);
        actualCashField.getTextField().setStyle("-fx-font-size: 36px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        actualCashField.getTextField().setEditable(false);
        actualCashField.setShowKeypad(false);
        
        // Connect variance update
        actualCashField.textProperty().addListener((obs, oldVal, newVal) -> updateVariance(newVal));
        
        amountBox.getChildren().addAll(amountLabel, actualCashField);

        // Quick Cash Buttons
        VBox quickCashBox = new VBox(15);
        Label quickLabel = new Label("QUICK CASH ACTIONS");
        quickLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #64748b; -fx-letter-spacing: 1.5px;");
        
        GridPane quickGrid = new GridPane();
        quickGrid.setHgap(15);
        quickGrid.setVgap(15);
        
        String[][] quickValues = {
            {"$20", "20"}, {"$50", "50"},
            {"$100", "100"}, {"$500", "500"},
            {"$1000", "1000"}, {"EXACT", "EXACT"}
        };
        
        int row = 0;
        int col = 0;
        for (String[] val : quickValues) {
            boolean isExact = "EXACT".equals(val[0]);
            if (isExact && col != 0) {
                col = 0;
                row++;
            }
            
            Button btn = new Button(val[0]);
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setPrefHeight(60);
            btn.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-background-radius: 12; -fx-cursor: hand;");
            
            if (isExact) {
                btn.setStyle(btn.getStyle() + "-fx-background-color: #2563eb; -fx-text-fill: white;");
                GridPane.setColumnSpan(btn, 2);
                btn.setOnAction(e -> setAmount(expectedCash));
            } else {
                btn.setStyle(btn.getStyle() + "-fx-background-color: #f1f5f9; -fx-text-fill: #475569;");
                final BigDecimal amount = new BigDecimal(val[1]);
                btn.setOnAction(e -> setAmount(amount));
            }
            
            quickGrid.add(btn, col, row);
            
            if (isExact) {
                col = 0; row++;
            } else {
                col++;
                if (col > 1) {
                    col = 0; row++;
                }
            }
        }
        
        ColumnConstraints colC1 = new ColumnConstraints(); colC1.setPercentWidth(50);
        ColumnConstraints colC2 = new ColumnConstraints(); colC2.setPercentWidth(50);
        quickGrid.getColumnConstraints().addAll(colC1, colC2);
        
        quickCashBox.getChildren().addAll(quickLabel, quickGrid);

        // Options
        printReportCheckbox = new CheckBox("Print Shift Report");
        printReportCheckbox.setSelected(true);
        printReportCheckbox.setStyle("-fx-font-size: 16px; -fx-text-fill: #334155;");
        
        container.getChildren().addAll(amountBox, quickCashBox, printReportCheckbox);
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

        confirmButton = new Button("CLOSE SHIFT & LOGOUT");
        confirmButton.setPrefSize(320, 70);
        confirmButton.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 18px; -fx-background-radius: 12; -fx-cursor: hand; -fx-effect: dropshadow(gaussian, rgba(239, 68, 68, 0.3), 10, 0, 0, 5);");
        confirmButton.setOnAction(e -> performEndShift());

        footer.getChildren().addAll(backButton, confirmButton);
        return footer;
    }

    private void loadShiftData() {
        new Thread(() -> {
            try {
                currentShift = shiftService.getActiveShift();
                if (currentShift != null && "ACTIVE".equals(currentShift.status)) {
                    // Force refresh expected cash from calculation service
                    expectedCash = shiftService.calculateAvailableCash(currentShift.id);
                    
                    Platform.runLater(() -> {
                        cashierValueLabel.setText(currentShift.cashierName);
                        openingCashValueLabel.setText(currencyFormat.format(currentShift.openingCash));
                        
                        BigDecimal totalCashSales = currentShift.totalCashSales != null ? currentShift.totalCashSales : BigDecimal.ZERO;
                        cashSalesValueLabel.setText(currencyFormat.format(totalCashSales));
                        
                        expectedCashValueLabel.setText(currencyFormat.format(expectedCash));
                        
                        // Prefill with expected net cash activity only.
                        setAmount(expectedCash);
                        
                        // Set focus to actual cash field
                        actualCashField.focusTextField();
                    });
                } else {
                    Platform.runLater(() -> {
                        ToastNotification.showWarning("No active shift found.", getScene().getWindow());
                        onBack.run();
                    });
                }
            } catch (Exception e) {
                logger.error("Error loading shift data", e);
                Platform.runLater(() -> {
                    ToastNotification.showError("Failed to load shift summary: " + e.getMessage(), getScene().getWindow());
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
            BigDecimal startingCash = currentShift != null ? currentShift.openingCash : BigDecimal.ZERO;
            
            // Show the actual net intake (Drawer - Start)
            BigDecimal netIntake = actualCash.subtract(startingCash);
            actualNetIntakeValueLabel.setText(currencyFormat.format(netIntake));
            
            // Variance = Net Intake - Expected
            BigDecimal variance = netIntake.subtract(expectedCash);
            
            varianceValueLabel.setText(currencyFormat.format(variance));
            
            if (variance.abs().compareTo(new BigDecimal("0.50")) <= 0) {
                varianceValueLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #10b981;"); // Emerald
            } else if (variance.compareTo(BigDecimal.ZERO) < 0) {
                varianceValueLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #ef4444;"); // Rose
            } else {
                varianceValueLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #f59e0b;"); // Amber
            }
        } catch (NumberFormatException e) {
            varianceValueLabel.setText("Invalid");
            varianceValueLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #ef4444;");
        }
    }

    private void performEndShift() {
        String amountText = actualCashField.getText().trim();
        if (amountText.isEmpty()) {
            ToastNotification.showWarning("Please enter actual cash count", getScene().getWindow());
            return;
        }

        BigDecimal actualCash = new BigDecimal(amountText.replace(",", ""));
        String note = "Closed via Close Shift Screen";
        boolean shouldPrint = printReportCheckbox.isSelected();

        showConfirmationOverlay(() -> {
            confirmButton.setDisable(true);
            
            new Thread(() -> {
                try {
                    // 1. Process Shift End
                    shiftService.endShift(currentShift.id, actualCash, note);
                    logger.info("Shift {} ended successfully", currentShift.id);

                    // 2. Print Report if requested
                    if (shouldPrint) {
                        try {
                            String report = eodReportService.generateReceiptTextForShift(currentShift.id, authService.getCurrentUserName());
                            Platform.runLater(() -> {
                                ReceiptPrintHelper.printReceiptConditionally(report, null, getScene().getWindow(), null);
                            });
                        } catch (Exception e) {
                            logger.error("Failed to generate/print shift report", e);
                        }
                    }

                    // 3. Clock Out Employee
                    try {
                        String currentUserId = authService.getCurrentPosUserId();
                        if (currentUserId != null) {
                            employeeShiftService.clockOut(currentUserId, "Auto-clocked out via Close Shift Screen");
                        }
                    } catch (Exception e) {
                        logger.error("Failed to clock out employee during shift closure", e);
                        // We don't block the closure if clock-out fails, but we log it
                    }

                    // 4. Logout and Navigate
                Platform.runLater(() -> {
                        ToastNotification.showSuccess("Great work today! Shift closed successfully. See you next time!", getScene().getWindow());
                        navigateToLogin();
                    });
                } catch (Exception e) {
                    logger.error("Failed to end shift", e);
                    Platform.runLater(() -> {
                        ToastNotification.showError("Failed to close shift: " + e.getMessage(), getScene().getWindow());
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

        // Semi-transparent backdrop
        Region backdrop = new Region();
        backdrop.setStyle("-fx-background-color: rgba(15, 23, 42, 0.4);");
        
        VBox dialog = new VBox(30);
        dialog.setAlignment(Pos.CENTER);
        dialog.setMaxSize(450, 300);
        dialog.setPadding(new Insets(40));
        dialog.setStyle("-fx-background-color: rgba(255, 255, 255, 0.9); -fx-background-radius: 24; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 30, 0, 0, 15); -fx-backdrop-filter: blur(20);");

        Label title = new Label("Ready to Wrap Up?");
        title.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        Label message = new Label("This will finalize your cash session and log you out. Please ensure your count includes the starting float currently in the drawer.");
        message.setStyle("-fx-font-size: 16px; -fx-text-fill: #64748b; -fx-text-alignment: center;");
        message.setWrapText(true);

        HBox buttons = new HBox(20);
        buttons.setAlignment(Pos.CENTER);

        Button cancelButton = new Button("Not Yet");
        cancelButton.setPrefSize(160, 50);
        cancelButton.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 16px; -fx-background-radius: 10; -fx-cursor: hand;");
        cancelButton.setOnAction(e -> {
            overlayContainer.setVisible(false);
            overlayContainer.setMouseTransparent(true);
        });

        Button confirmBtn = new Button("Close Shift");
        confirmBtn.setPrefSize(160, 50);
        confirmBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 16px; -fx-background-radius: 10; -fx-cursor: hand; -fx-effect: dropshadow(gaussian, rgba(37, 99, 235, 0.3), 10, 0, 0, 5);");
        confirmBtn.setOnAction(e -> {
            overlayContainer.setVisible(false);
            overlayContainer.setMouseTransparent(true);
            onConfirm.run();
        });

        buttons.getChildren().addAll(cancelButton, confirmBtn);
        dialog.getChildren().addAll(title, message, buttons);

        overlayContainer.getChildren().addAll(backdrop, dialog);
        
        // Add subtle animation
        dialog.setOpacity(0);
        dialog.setScaleX(0.9);
        dialog.setScaleY(0.9);
        
        javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(200), dialog);
        fade.setToValue(1);
        
        javafx.animation.ScaleTransition scale = new javafx.animation.ScaleTransition(javafx.util.Duration.millis(250), dialog);
        scale.setToX(1);
        scale.setToY(1);
        scale.setInterpolator(javafx.animation.Interpolator.EASE_OUT);
        
        fade.play();
        scale.play();
    }

    private void navigateToLogin() {
        if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage stage) {
            authService.logout();
            
            LoginScreen loginScreen = new LoginScreen(stage);
            javafx.geometry.Rectangle2D screenBounds = javafx.stage.Screen.getPrimary().getVisualBounds();
            Scene loginScene = new Scene(loginScreen, screenBounds.getWidth(), screenBounds.getHeight());

            try {
                loginScene.getStylesheets().add(getClass().getResource("/styles/application.css").toExternalForm());
            } catch (Exception e) {
                logger.warn("Stylesheet not found");
            }

            stage.setScene(loginScene);
            stage.setTitle("Pasal POS 2 - Login");
            stage.setFullScreen(true);
            stage.show();
        }
    }
}
