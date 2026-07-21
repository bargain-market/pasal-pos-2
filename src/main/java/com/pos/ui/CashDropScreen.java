package com.pos.ui;

import com.pos.service.SettingsService;
import com.pos.service.ShiftService;
import com.pos.service.UserAuthService;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.NumericKeypad;
import com.pos.ui.components.TouchTextField;
import com.pos.ui.components.ToastNotification;
import com.pos.util.CashOperationReceiptBuilder;
import com.pos.util.ReceiptPrintHelper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.text.NumberFormat;

/**
 * A dedicated screen for performing cash drops.
 * Replaces the old modal-based implementation.
 */
public class CashDropScreen extends BorderPane {
    private static final Logger logger = LoggerFactory.getLogger(CashDropScreen.class);
    private final ShiftService shiftService = ShiftService.getInstance();
    private final SettingsService settingsService = SettingsService.getInstance();
    private final Runnable onBack;
    
    private com.pos.api.dto.ShiftResponse.ShiftData currentShift;
    private BigDecimal availableCash = BigDecimal.ZERO;
    
    private Label availableCashValueLabel;
    private TouchTextField amountField;
    private Label validationLabel;
    private Button confirmButton;
    private StringBuilder amountBuffer = new StringBuilder();
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

    public CashDropScreen(Runnable onBack) {
        this.onBack = onBack;
        initialize();
    }

    private void initialize() {
        getStyleClass().add("cash-drop-screen");
        setPadding(new Insets(30));
        setStyle("-fx-background-color: #f8fafc;");

        // Header Section
        VBox header = createHeader();
        setTop(header);

        // Center Content Section
        VBox content = createContent();
        setCenter(content);

        // Footer Actions Section
        HBox footer = createFooter();
        setBottom(footer);

        loadData();
    }

    private VBox createHeader() {
        VBox header = new VBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 30, 0));

        Label title = new Label("Cash Drop");
        title.setStyle("-fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");
        
        Label subtitle = new Label("Safe removal of excess cash from the register drawer");
        subtitle.setStyle("-fx-font-size: 16px; -fx-text-fill: #64748b;");

        header.getChildren().addAll(title, subtitle);
        return header;
    }

    private VBox createContent() {
        VBox content = new VBox(25);
        content.setAlignment(Pos.TOP_CENTER);
        content.setMaxWidth(900); // Increased width for 2-column layout
        content.setPadding(new Insets(20));
        content.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 10, 0, 0, 4);");

        HBox mainContent = new HBox(30);
        mainContent.setAlignment(Pos.TOP_CENTER);

        // LEFT COLUMN: Cash Info and Amount Field
        VBox leftColumn = new VBox(20);
        leftColumn.setPrefWidth(350);
        leftColumn.setAlignment(Pos.TOP_CENTER);

        // Available Cash Card
        VBox cashCard = new VBox(10);
        cashCard.setAlignment(Pos.CENTER);
        cashCard.setPadding(new Insets(20));
        cashCard.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 8;");
        
        Label cashLabel = new Label("AVAILABLE CASH IN DRAWER");
        cashLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #64748b; -fx-letter-spacing: 1px;");
        
        availableCashValueLabel = new Label("$0.00");
        availableCashValueLabel.setStyle("-fx-font-size: 36px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        
        cashCard.getChildren().addAll(cashLabel, availableCashValueLabel);

        // Amount Input Field Box
        VBox amountBox = new VBox(8);
        Label amountLabel = new Label("Drop Amount ($)");
        amountLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #334155;");
        
        amountField = TouchScreenComponents.createTouchOnlyNumericField("0.00");
        amountField.setPrefHeight(60);
        amountField.getTextField().setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #2563eb;");
        amountField.getTextField().setEditable(false); // Only input via keypad
        amountField.setShowKeypad(false); // Disable floating keypad since we have an embedded one
        
        validationLabel = new Label();
        validationLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 13px;");
        validationLabel.setWrapText(true);
        validationLabel.setVisible(false);
        
        amountBox.getChildren().addAll(amountLabel, amountField, validationLabel);

        // Quick Cash Buttons
        VBox quickCashBox = new VBox(10);
        Label quickLabel = new Label("Quick Cash Values");
        quickLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #64748b;");
        
        GridPane quickGrid = new GridPane();
        quickGrid.setHgap(10);
        quickGrid.setVgap(10);
        
        String[][] quickValues = {
            {"$50", "50"}, {"$100", "100"},
            {"$150", "150"}, {"$200", "200"},
            {"$500", "500"}, {"EXACT", "EXACT"}
        };
        
        int row = 0;
        int col = 0;
        for (String[] val : quickValues) {
            Button btn = new Button(val[0]);
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setPrefHeight(50);
            if ("EXACT".equals(val[0])) {
                btn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 6; -fx-cursor: hand;");
                GridPane.setColumnSpan(btn, 2);
                btn.setOnAction(e -> setAmount(availableCash));
            } else {
                btn.setStyle("-fx-background-color: #f8fafc; -fx-text-fill: #475569; -fx-border-color: #e2e8f0; -fx-border-radius: 6; -fx-background-radius: 6; -fx-cursor: hand;");
                final BigDecimal amount = new BigDecimal(val[1]);
                btn.setOnAction(e -> setAmount(amount));
            }
            
            quickGrid.add(btn, col, row);
            
            if ("EXACT".equals(val[0])) {
                // Done
            } else {
                col++;
                if (col > 1) {
                    col = 0;
                    row++;
                }
            }
        }
        
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        quickGrid.getColumnConstraints().addAll(col1, col2);

        quickCashBox.getChildren().addAll(quickLabel, quickGrid);
        leftColumn.getChildren().addAll(cashCard, amountBox, quickCashBox);

        // RIGHT COLUMN: Numeric Keypad
        VBox rightColumn = new VBox(10);
        rightColumn.setAlignment(Pos.TOP_CENTER);
        
        NumericKeypad keypad = new NumericKeypad(true, false, false);
        keypad.setListener(this::handleKeypadInput);
        keypad.setPrefWidth(350);
        keypad.setStyle("-fx-background-color: #f8fafc; -fx-padding: 10; -fx-background-radius: 8; -fx-border-color: #e2e8f0; -fx-border-radius: 8;");

        rightColumn.getChildren().add(keypad);

        mainContent.getChildren().addAll(leftColumn, rightColumn);
        content.getChildren().add(mainContent);

        // Add real-time validation
        amountField.textProperty().addListener((obs, oldVal, newVal) -> validateAmount(newVal));


        return content;
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
        amountField.setText(amountBuffer.toString());
    }

    private void setAmount(BigDecimal amount) {
        if (amount == null) return;
        amountBuffer.setLength(0);
        amountBuffer.append(amount.setScale(2, java.math.RoundingMode.HALF_UP).toString());
        amountField.setText(amountBuffer.toString());
    }

    private HBox createFooter() {
        HBox footer = new HBox(20);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(30, 0, 0, 0));

        Button cancelButton = new Button("Cancel");
        cancelButton.setPrefSize(180, 50);
        cancelButton.getStyleClass().add("btn-secondary");
        cancelButton.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 16px; -fx-background-radius: 8; -fx-cursor: hand;");
        cancelButton.setOnAction(e -> onBack.run());

        confirmButton = new Button("Perform Cash Drop");
        confirmButton.setPrefSize(220, 50);
        confirmButton.getStyleClass().add("btn-primary");
        confirmButton.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 16px; -fx-background-radius: 8; -fx-cursor: hand;");
        confirmButton.setDisable(true);
        confirmButton.setOnAction(e -> performDrop());

        footer.getChildren().addAll(cancelButton, confirmButton);
        return footer;
    }

    private void validateAmount(String newValue) {
        try {
            if (newValue == null || newValue.trim().isEmpty()) {
                validationLabel.setVisible(false);
                confirmButton.setDisable(true);
                return;
            }

            BigDecimal amount = new BigDecimal(newValue.trim());
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                validationLabel.setText("Amount must be greater than zero");
                validationLabel.setVisible(true);
                confirmButton.setDisable(true);
            } else if (amount.compareTo(availableCash) > 0) {
                validationLabel.setText("This drop exceeds current drawer cash by "
                        + currencyFormat.format(amount.subtract(availableCash))
                        + " and will appear in short/over reporting.");
                validationLabel.setVisible(true);
                confirmButton.setDisable(false);
            } else {
                validationLabel.setVisible(false);
                confirmButton.setDisable(false);
            }
        } catch (NumberFormatException e) {
            validationLabel.setText("Invalid amount format");
            validationLabel.setVisible(true);
            confirmButton.setDisable(true);
        }
    }

    private void loadData() {
        new Thread(() -> {
            try {
                currentShift = shiftService.getActiveShift();
                if (currentShift != null && "ACTIVE".equals(currentShift.status)) {
                    availableCash = shiftService.calculateAvailableCash(currentShift.id);
                    Platform.runLater(() -> {
                        availableCashValueLabel.setText(currencyFormat.format(availableCash));
                    });
                } else {
                    Platform.runLater(() -> {
                        ToastNotification.showWarning("No active shift found.", getScene().getWindow());
                        onBack.run();
                    });
                }
            } catch (Exception e) {
                logger.error("Error loading shift/cash data", e);
                Platform.runLater(() -> {
                    ToastNotification.showError("Failed to load cash data: " + e.getMessage(), getScene().getWindow());
                });
            }
        }).start();
    }

    private void performDrop() {
        String amountText = amountField.getText().trim();
        BigDecimal amount = new BigDecimal(amountText.isEmpty() ? "0" : amountText);
        String note = "Cash removal to safe"; // Default note since field is removed

        confirmButton.setDisable(true);
        
        new Thread(() -> {
            try {
                String performedById = "Unknown";
                String performedByName = "Unknown";
                try {
                    UserAuthService authService = UserAuthService.getInstance();
                    performedById = authService.getCurrentPosUserId();
                    performedByName = authService.getCurrentUserName();
                } catch (Exception e) {
                    logger.debug("Could not get current user info", e);
                }

                shiftService.recordCashOperation(
                        currentShift.id,
                        "DROP",
                        amount,
                        note,
                        performedById,
                        performedByName);

                final String finalPerformedBy = performedByName;

                Platform.runLater(() -> {
                    printCashDropReceipt(amount, finalPerformedBy);
                    ToastNotification.showSuccess("Cash drop of " + currencyFormat.format(amount) + " recorded successfully!", getScene().getWindow());
                    onBack.run();
                });
            } catch (Exception e) {
                logger.error("Failed to record cash drop", e);
                Platform.runLater(() -> {
                    ToastNotification.showError("Failed to record cash drop: " + e.getMessage(), getScene().getWindow());
                    confirmButton.setDisable(false);
                });
            }
        }).start();
    }

    private void printCashDropReceipt(BigDecimal amount, String performedBy) {
        try {
            ShiftService.CashOperationInfo operation = new ShiftService.CashOperationInfo();
            operation.shiftId = currentShift != null ? currentShift.id : null;
            operation.type = "DROP";
            operation.amount = amount;
            operation.note = "Cash removal to safe";
            operation.performedByName = performedBy;
            operation.createdAt = java.time.Instant.now().toString();

            String receipt = CashOperationReceiptBuilder.buildReceipt(settingsService.getStoreName(), operation);
            ReceiptPrintHelper.printReceiptConditionally(
                    receipt,
                    operation.shiftId != null ? "DROP-" + operation.shiftId + "-" + System.currentTimeMillis()
                            : "DROP-" + System.currentTimeMillis(),
                    getScene().getWindow(),
                    settingsService);
        } catch (Exception e) {
            logger.error("Error generating cash drop receipt", e);
            ToastNotification.showError("Failed to print receipt: " + e.getMessage(), getScene().getWindow());
        }
    }
}
