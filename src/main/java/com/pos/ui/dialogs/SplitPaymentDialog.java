package com.pos.ui.dialogs;

import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.TouchTextField;
import com.pos.util.DialogHelper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for splitting payment across multiple payment methods
 */
public class SplitPaymentDialog extends Dialog<SplitPaymentDialog.SplitPaymentResult> {

    private static final Logger logger = LoggerFactory.getLogger(SplitPaymentDialog.class);

    private BigDecimal totalAmount;
    private BigDecimal remainingBalance;
    private Label remainingLabel;
    private TableView<PaymentEntry> paymentsTable;
    private ObservableList<PaymentEntry> paymentsList;
    private ComboBox<String> paymentMethodCombo;
    private TouchTextField amountField;

    public SplitPaymentDialog(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
        this.remainingBalance = totalAmount;
        
        // Load stylesheet
        getDialogPane().getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());
        getDialogPane().getStyleClass().add("root");
        
        initializeDialog();
    }

    private void initializeDialog() {
        setTitle("Split Payment");
        setHeaderText("Split payment across multiple methods");
        initModality(Modality.APPLICATION_MODAL);

        // Set owner window to ensure dialog appears on same screen
        try {
            Window currentWindow = javafx.stage.Stage.getWindows().stream()
                    .filter(Window::isShowing)
                    .findFirst()
                    .orElse(null);
            if (currentWindow != null) {
                initOwner(currentWindow);
            }
        } catch (Exception e) {
            // Ignore if we can't set owner
        }

        // Create content
        BorderPane content = new BorderPane();
        content.setPadding(new Insets(20));
        content.getStyleClass().add(com.pos.ui.util.ThemeConstants.CLASS_GLASS_PANE);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.6, 0.7);

        // Top section: Total and remaining balance
        VBox topSection = new VBox(10);
        topSection.setPadding(new Insets(10));

        Label totalLabel = new Label("Total Amount: " + NumberFormat.getCurrencyInstance().format(totalAmount));
        totalLabel.getStyleClass().add(com.pos.ui.util.ThemeConstants.CLASS_HEADING_2);

        remainingLabel = new Label("Remaining Balance: " + NumberFormat.getCurrencyInstance().format(remainingBalance));
        remainingLabel.getStyleClass().addAll(com.pos.ui.util.ThemeConstants.CLASS_HEADING_2, com.pos.ui.util.ThemeConstants.CLASS_NUMERIC);
        remainingLabel.setStyle("-fx-text-fill: var(--primary);");

        topSection.getChildren().addAll(totalLabel, remainingLabel);

        // Center section: Payments table
        paymentsList = FXCollections.observableArrayList();
        paymentsTable = new TableView<>(paymentsList);
        paymentsTable.setPlaceholder(new Label("No payments added yet"));

        TableColumn<PaymentEntry, String> methodCol = new TableColumn<>("Payment Method");
        methodCol.setCellValueFactory(new PropertyValueFactory<>("paymentMethod"));
        methodCol.setPrefWidth(150);

        TableColumn<PaymentEntry, String> amountCol = new TableColumn<>("Amount");
        amountCol.setCellValueFactory(data -> {
            BigDecimal amount = data.getValue().getAmount();
            return new javafx.beans.property.SimpleStringProperty(
                    NumberFormat.getCurrencyInstance().format(amount));
        });
        amountCol.setPrefWidth(150);

        TableColumn<PaymentEntry, Void> actionCol = new TableColumn<>("");
        actionCol.setCellFactory(param -> new TableCell<>() {
            private final Button deleteBtn = new Button("✕");

            {
                deleteBtn.setStyle(
                        "-fx-background-color: transparent; -fx-text-fill: #f44336; -fx-font-weight: bold; -fx-cursor: hand;");
                deleteBtn.setOnAction(event -> {
                    PaymentEntry entry = getTableView().getItems().get(getIndex());
                    removePayment(entry);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : deleteBtn);
            }
        });
        actionCol.setPrefWidth(80);

        paymentsTable.getColumns().addAll(methodCol, amountCol, actionCol);

        // Bottom section: Add payment controls
        VBox addPaymentSection = new VBox(10);
        addPaymentSection.setPadding(new Insets(10));
        addPaymentSection.setStyle("-fx-background-color: #f8f9fa;");

        GridPane addPaymentBox = new GridPane();
        addPaymentBox.setHgap(15);
        addPaymentBox.setVgap(10);
        addPaymentBox.setPadding(new Insets(10));
        addPaymentBox.setAlignment(javafx.geometry.Pos.CENTER);

        // Column constraints for responsiveness
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setHgrow(javafx.scene.layout.Priority.NEVER);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        ColumnConstraints col3 = new ColumnConstraints();
        col3.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        addPaymentBox.getColumnConstraints().addAll(col1, col2, col3);

        Label methodLabel = new Label("Method:");
        methodLabel.getStyleClass().add("label-secondary");
        paymentMethodCombo = new ComboBox<>(FXCollections.observableArrayList("CASH", "CARD", "DIGITAL", "EBT"));
        paymentMethodCombo.setValue("CASH");
        paymentMethodCombo.setMaxWidth(Double.MAX_VALUE);
        paymentMethodCombo.setPrefHeight(40);
        paymentMethodCombo.getStyleClass().add("glass-pane");

        Label amountLabel = new Label("Amount:");
        amountField = TouchScreenComponents.createTouchOnlyNumericField("Enter amount");
        amountField.setMaxWidth(Double.MAX_VALUE);
        amountField.setPrefHeight(40);

        Button fillRemainingBtn = new Button("Fill Remaining");
        fillRemainingBtn.setMaxWidth(Double.MAX_VALUE);
        fillRemainingBtn.setPrefHeight(40);
        fillRemainingBtn.setStyle(
                "-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-weight: bold;");
        fillRemainingBtn.setOnAction(e -> fillRemainingAmount());
        fillRemainingBtn.setDisable(true);

        Button addBtn = new Button("Add Payment");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setPrefHeight(40);
        addBtn.getStyleClass().addAll("button", com.pos.ui.util.ThemeConstants.CLASS_BTN_SUCCESS);
        addBtn.setOnAction(e -> addPayment());

        // Row 1: Method and Amount
        addPaymentBox.add(methodLabel, 0, 0);
        addPaymentBox.add(paymentMethodCombo, 1, 0);
        addPaymentBox.add(amountLabel, 0, 1);
        addPaymentBox.add(amountField, 1, 1);
        
        // Buttons in their own row for small screens
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(javafx.geometry.Pos.CENTER);
        buttonBox.getChildren().addAll(fillRemainingBtn, addBtn);
        HBox.setHgrow(fillRemainingBtn, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(addBtn, javafx.scene.layout.Priority.ALWAYS);
        
        addPaymentBox.add(buttonBox, 0, 2, 2, 1);
        addPaymentSection.getChildren().add(addPaymentBox);

        content.setTop(topSection);
        content.setCenter(paymentsTable);
        content.setBottom(addPaymentSection);

        // Set dialog content
        getDialogPane().setContent(content);

        // Buttons
        ButtonType processButtonType = new ButtonType("Process Payment", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(processButtonType, cancelButtonType);

        // Disable process button if balance not zero
        Button processButton = (Button) getDialogPane().lookupButton(processButtonType);
        processButton.setDisable(true);

        // Update button states when payments change
        paymentsList.addListener((javafx.collections.ListChangeListener.Change<? extends PaymentEntry> c) -> {
            updateRemainingBalance();
            processButton.setDisable(remainingBalance.compareTo(BigDecimal.ZERO) != 0);
            fillRemainingBtn.setDisable(remainingBalance.compareTo(BigDecimal.ZERO) <= 0);
        });

        // Style handled by loading stylesheet and adding classes

        // Set result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == processButtonType) {
                if (remainingBalance.compareTo(BigDecimal.ZERO) != 0) {
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("Incomplete Payment");
                    alert.setHeaderText("Payment not complete");
                    alert.setContentText("The remaining balance must be $0.00 to process the payment.");
                    DialogHelper.setAlertOwner(alert,
                            getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
                    alert.showAndWait();
                    return null;
                }

                SplitPaymentResult result = new SplitPaymentResult();
                result.payments = new ArrayList<>();
                for (PaymentEntry entry : paymentsList) {
                    result.payments.add(new com.pos.model.Payment(entry.getPaymentMethod(), entry.getAmount()));
                }
                return result;
            }
            return null;
        });

        // Focus amount field when dialog opens
        javafx.application.Platform.runLater(() -> amountField.requestFocus());
    }

    private void addPayment() {
        String method = paymentMethodCombo.getValue();
        String amountStr = amountField.getText();

        if (amountStr == null || amountStr.trim().isEmpty()) {
            showError("Please enter an amount");
            return;
        }

        try {
            BigDecimal amount = new BigDecimal(amountStr);

            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                showError("Amount must be greater than zero");
                return;
            }

            if (amount.compareTo(remainingBalance) > 0) {
                showError("Amount cannot exceed remaining balance: " +
                        NumberFormat.getCurrencyInstance().format(remainingBalance));
                return;
            }

            PaymentEntry entry = new PaymentEntry(method, amount);
            paymentsList.add(entry);

            // Clear amount field
            amountField.clear();
            amountField.requestFocus();

            updateRemainingBalance();
        } catch (NumberFormatException e) {
            showError("Invalid amount format. Please enter a valid number.");
        }
    }

    private void removePayment(PaymentEntry entry) {
        paymentsList.remove(entry);
        updateRemainingBalance();
    }

    private void fillRemainingAmount() {
        if (remainingBalance.compareTo(BigDecimal.ZERO) > 0) {
            amountField.setText(remainingBalance.toString());
            amountField.requestFocus();
            // Optionally auto-add the payment
            addPayment();
        }
    }

    private void updateRemainingBalance() {
        BigDecimal totalPaid = paymentsList.stream()
                .map(PaymentEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        remainingBalance = totalAmount.subtract(totalPaid);

        if (remainingBalance.compareTo(BigDecimal.ZERO) < 0) {
            remainingBalance = BigDecimal.ZERO;
        }

        remainingLabel.setText("Remaining Balance: " + NumberFormat.getCurrencyInstance().format(remainingBalance));

        // Update color based on balance
        if (remainingBalance.compareTo(BigDecimal.ZERO) == 0) {
            remainingLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #4CAF50;");
        } else {
            remainingLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #2a5298;");
        }
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Invalid Input");
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogHelper.setAlertOwner(alert,
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
        alert.showAndWait();
    }

    /**
     * Result class for split payment
     */
    public static class SplitPaymentResult {
        public List<com.pos.model.Payment> payments;
    }

    /**
     * Payment entry for table display
     */
    public static class PaymentEntry {
        private String paymentMethod;
        private BigDecimal amount;

        public PaymentEntry(String paymentMethod, BigDecimal amount) {
            this.paymentMethod = paymentMethod;
            this.amount = amount;
        }

        public String getPaymentMethod() {
            return paymentMethod;
        }

        public BigDecimal getAmount() {
            return amount;
        }
    }
}
