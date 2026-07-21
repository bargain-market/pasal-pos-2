package com.pos.ui;

import com.pos.ui.components.NumericKeypad;
import com.pos.ui.components.ToastNotification;
import com.pos.ui.util.ThemeConstants;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import com.pos.model.SaleItem;
import com.pos.model.Payment;

/**
 * Full-screen Split Payment View
 * Provides a dedicated interface for adding multiple payment methods to a sale.
 */
public class SplitPaymentScreen extends BorderPane {

    // Data
    private final ObservableList<SaleItem> cartItems;
    private final BigDecimal subtotal;
    private final BigDecimal tax;
    private final BigDecimal listTotal;    // Card price total
    private final BigDecimal saleDiscount;

    // Callbacks
    private final java.util.function.Consumer<List<Payment>> onProcess;
    private final Runnable onCancel;

    // UI Components
    private Label remainingLabel;
    private TableView<SplitPaymentEntry> paymentsTable;
    private ObservableList<SplitPaymentEntry> paymentsList;
    private TextField amountField;
    private StringBuilder inputBuffer = new StringBuilder();
    private String selectedMethod = "CASH";
    private BigDecimal remainingBalance;

    private Button cashBtn;
    private Button cardBtn;
    private Button processBtn;

    public SplitPaymentScreen(
            ObservableList<SaleItem> cartItems,
            BigDecimal subtotal,
            BigDecimal tax,
            BigDecimal currentTotal,
            BigDecimal listTotal,
            BigDecimal saleDiscount,
            java.util.function.Consumer<List<Payment>> onProcess,
            Runnable onCancel) {

        this.cartItems = cartItems;
        this.subtotal = subtotal;
        this.tax = tax;
        this.listTotal = listTotal;
        this.saleDiscount = saleDiscount != null ? saleDiscount : BigDecimal.ZERO;

        this.onProcess = onProcess;
        this.onCancel = onCancel;

        this.remainingBalance = listTotal;
        this.paymentsList = FXCollections.observableArrayList();

        getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());
        getStyleClass().add("root");

        initializeUI();
    }

    private void initializeUI() {
        HBox mainLayout = new HBox(0);
        mainLayout.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        // LEFT PANEL - Order Summary
        VBox leftPanel = createOrderSummaryPanel();
        leftPanel.setMinWidth(300);
        leftPanel.setPrefWidth(350);
        leftPanel.getStyleClass().add(ThemeConstants.CLASS_GLASS_PANE);

        // RIGHT PANEL - Split Controls
        VBox rightPanel = createSplitControlsPanel();
        HBox.setHgrow(rightPanel, Priority.ALWAYS);

        mainLayout.getChildren().addAll(leftPanel, rightPanel);
        setCenter(mainLayout);
    }

    private VBox createOrderSummaryPanel() {
        VBox panel = new VBox(0);
        panel.getStyleClass().add("receipt-preview");

        // Header
        Label headerLabel = new Label("📋 ORDER SUMMARY");
        headerLabel.getStyleClass().add(ThemeConstants.CLASS_HEADING_2);
        headerLabel.setPadding(new Insets(20));
        headerLabel.setMaxWidth(Double.MAX_VALUE);
        headerLabel.setAlignment(Pos.CENTER);

        // Content
        VBox content = new VBox(10);
        content.setPadding(new Insets(0, 20, 20, 20));
        VBox.setVgrow(content, Priority.ALWAYS);

        // Items list (Simplified)
        ScrollPane scrollPane = new ScrollPane();
        VBox itemsBox = new VBox(5);
        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

        for (SaleItem item : cartItems) {
            HBox row = new HBox(10);
            Label name = new Label(item.getProductName());
            name.setWrapText(true);
            HBox.setHgrow(name, Priority.ALWAYS);
            Label qty = new Label("x" + item.getQuantity());
            Label price = new Label(currencyFormat.format(item.getBaseTotal()));
            row.getChildren().addAll(name, qty, price);
            itemsBox.getChildren().add(row);
        }

        scrollPane.setContent(itemsBox);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("glass-pane");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // Totals
        VBox totalsBox = new VBox(5);
        totalsBox.setPadding(new Insets(10, 0, 0, 0));
        totalsBox.getChildren().addAll(
                createTotalRow("Subtotal", currencyFormat.format(subtotal)),
                createTotalRow("Tax", currencyFormat.format(tax))
        );
        
        if (saleDiscount.compareTo(BigDecimal.ZERO) > 0) {
            totalsBox.getChildren().add(createTotalRow("Discount", "-" + currencyFormat.format(saleDiscount)));
        }

        Separator sep = new Separator();
        sep.setPadding(new Insets(10, 0, 10, 0));

        Label totalDueLabel = new Label("TOTAL DUE (CARD)");
        totalDueLabel.setStyle("-fx-text-fill: #aaa; -fx-font-weight: bold;");
        Label totalAmountLabel = new Label(currencyFormat.format(listTotal));
        totalAmountLabel.getStyleClass().addAll(ThemeConstants.CLASS_HEADING_1, ThemeConstants.CLASS_NUMERIC);
        totalAmountLabel.setStyle("-fx-text-fill: #F59E0B;");

        totalsBox.getChildren().addAll(sep, totalDueLabel, totalAmountLabel);

        content.getChildren().addAll(scrollPane, totalsBox);

        // Back action at the very bottom
        Button backBtn = new Button("← BACK TO PAYMENT");
        backBtn.setMaxWidth(Double.MAX_VALUE);
        backBtn.setMinHeight(60);
        backBtn.getStyleClass().addAll("button", ThemeConstants.CLASS_BTN_DANGER);
        backBtn.setOnAction(e -> onCancel.run());

        panel.getChildren().addAll(headerLabel, content, backBtn);
        return panel;
    }

    private VBox createSplitControlsPanel() {
        VBox panel = new VBox(20);
        panel.setPadding(new Insets(30));
        panel.setAlignment(Pos.TOP_CENTER);
        panel.setStyle("-fx-background-color: #0f3460;");

        // 1. Balance Header
        VBox balanceHeader = new VBox(5);
        remainingLabel = new Label("Remaining: " + NumberFormat.getCurrencyInstance().format(remainingBalance));
        remainingLabel.setFont(Font.font("System", FontWeight.BOLD, 48));
        remainingLabel.setStyle("-fx-text-fill: white;");
        balanceHeader.getChildren().add(remainingLabel);

        // 2. Quick Split Actions
        VBox quickSplitBox = createQuickSplits();

        // 3. Main Split Creation Area
        HBox mainArea = new HBox(30);
        mainArea.setAlignment(Pos.TOP_CENTER);
        VBox.setVgrow(mainArea, Priority.ALWAYS);

        // Left Side of Main Area: Added Payments
        VBox addedPaymentsBox = new VBox(10);
        addedPaymentsBox.setMinWidth(300);
        HBox.setHgrow(addedPaymentsBox, Priority.ALWAYS);
        Label addedLabel = new Label("PAYMENTS ADDED");
        addedLabel.getStyleClass().add("label-secondary");
        
        paymentsTable = new TableView<>(paymentsList);
        paymentsTable.setPlaceholder(new Label("No payments added yet"));
        paymentsTable.getStyleClass().add("glass-pane");
        paymentsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        
        TableColumn<SplitPaymentEntry, String> methodCol = new TableColumn<>("Method");
        methodCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getMethod()));
        
        TableColumn<SplitPaymentEntry, String> amountCol = new TableColumn<>("Amount");
        amountCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                NumberFormat.getCurrencyInstance().format(data.getValue().getAmount())));

        TableColumn<SplitPaymentEntry, Void> actionCol = new TableColumn<>("");
        actionCol.setCellFactory(param -> new TableCell<>() {
            private final Button deleteBtn = new Button("✕");
            {
                deleteBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #e94560; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 5;");
                deleteBtn.setOnAction(event -> {
                    SplitPaymentEntry entry = getTableView().getItems().get(getIndex());
                    paymentsList.remove(entry);
                    updateRemainingBalance();
                });
            }
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    HBox box = new HBox(deleteBtn);
                    box.setAlignment(Pos.CENTER);
                    setGraphic(box);
                }
            }
        });
        actionCol.setMaxWidth(60);
        actionCol.setMinWidth(60);
        
        paymentsTable.getColumns().addAll(methodCol, amountCol, actionCol);
        VBox.setVgrow(paymentsTable, Priority.ALWAYS);
        
        addedPaymentsBox.getChildren().addAll(addedLabel, paymentsTable);

        // Right Side of Main Area: Manual Entry
        VBox manualEntryBox = new VBox(15);
        manualEntryBox.setMinWidth(350);
        manualEntryBox.setAlignment(Pos.TOP_CENTER);

        // Method Selector
        HBox methodSelector = new HBox(10);
        methodSelector.setAlignment(Pos.CENTER);
        cashBtn = createMethodBtn("CASH", "💵", ThemeConstants.SUCCESS);
        cardBtn = createMethodBtn("CARD", "💳", ThemeConstants.PRIMARY);
        methodSelector.getChildren().addAll(cashBtn, cardBtn);
        selectMethod("CASH");

        // Amount Display
        amountField = new TextField();
        amountField.setEditable(false);
        amountField.setAlignment(Pos.CENTER_RIGHT);
        amountField.getStyleClass().add(ThemeConstants.CLASS_NUMERIC);
        amountField.setStyle("-fx-font-size: 32px; -fx-background-color: #1a3a5c; -fx-text-fill: white; -fx-background-radius: 12;");
        amountField.setPrefHeight(60);

        // Keypad
        NumericKeypad keypad = new NumericKeypad(true, true, false);
        keypad.setListener(this::handleKeypadInput);
        
        Button addPaymentBtn = new Button("ADD PAYMENT");
        addPaymentBtn.setMaxWidth(Double.MAX_VALUE);
        addPaymentBtn.setMinHeight(50);
        addPaymentBtn.getStyleClass().addAll("button", ThemeConstants.CLASS_BTN_SUCCESS);
        addPaymentBtn.setOnAction(e -> addManualPayment());

        manualEntryBox.getChildren().addAll(methodSelector, amountField, keypad, addPaymentBtn);

        mainArea.getChildren().addAll(addedPaymentsBox, manualEntryBox);

        // 4. Bottom Action
        processBtn = new Button("COMPLETE TRANSACTION");
        processBtn.setMaxWidth(Double.MAX_VALUE);
        processBtn.setMinHeight(70);
        processBtn.getStyleClass().addAll("button", ThemeConstants.CLASS_BTN_PRIMARY);
        processBtn.setDisable(true);
        processBtn.setOnAction(e -> processTransaction());

        paymentsList.addListener((javafx.collections.ListChangeListener<SplitPaymentEntry>) c -> {
            updateRemainingBalance();
            processBtn.setDisable(remainingBalance.compareTo(BigDecimal.ZERO) != 0 || paymentsList.isEmpty());
            if (remainingBalance.compareTo(BigDecimal.ZERO) == 0) {
                processBtn.getStyleClass().add(ThemeConstants.CLASS_BTN_SUCCESS);
            } else {
                processBtn.getStyleClass().remove(ThemeConstants.CLASS_BTN_SUCCESS);
            }
        });

        panel.getChildren().addAll(balanceHeader, quickSplitBox, mainArea, processBtn);
        return panel;
    }

    private VBox createQuickSplits() {
        VBox box = new VBox(8);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 15; -fx-padding: 15;");

        Label label = new Label("QUICK SPLITS");
        label.getStyleClass().add("label-secondary");

        HBox buttons = new HBox(15);
        buttons.setAlignment(Pos.CENTER);
        buttons.getChildren().addAll(
                createQuickSplitBtn("50/50", () -> handleQuickSplit(2, new String[]{"CASH", "CARD"})),
                createQuickSplitBtn("Split by 2", () -> handleQuickSplit(2, null)),
                createQuickSplitBtn("Split by 3", () -> handleQuickSplit(3, null)),
                createQuickSplitBtn("Split by 4", () -> handleQuickSplit(4, null))
        );

        box.getChildren().addAll(label, buttons);
        return box;
    }

    private Button createQuickSplitBtn(String text, Runnable action) {
        Button btn = new Button(text);
        btn.setPrefSize(140, 45);
        btn.getStyleClass().addAll("button", "glass-pane");
        btn.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");
        btn.setOnAction(e -> action.run());
        return btn;
    }

    private Button createMethodBtn(String method, String icon, String color) {
        Button btn = new Button(icon + " " + method);
        btn.setPrefSize(180, 70);
        btn.getStyleClass().add("glass-pane");
        btn.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        btn.setUserData(color);
        btn.setOnAction(e -> selectMethod(method));
        return btn;
    }

    private void selectMethod(String method) {
        this.selectedMethod = method;
        resetMethodStyles();
        Button btn = method.equals("CASH") ? cashBtn : cardBtn;
        String color = (String) btn.getUserData();
        btn.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; -fx-border-color: white; -fx-border-width: 3; -fx-font-size: 18px; -fx-font-weight: bold;");
    }

    private void resetMethodStyles() {
        cashBtn.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        cardBtn.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
    }

    private void handleKeypadInput(String key) {
        if ("C".equals(key)) {
            inputBuffer.setLength(0);
        } else if ("⌫".equals(key)) {
            if (inputBuffer.length() > 0) inputBuffer.setLength(inputBuffer.length() - 1);
        } else if ("↵".equals(key) || "ENTER".equals(key)) {
            addManualPayment();
            return;
        } else {
            if (".".equals(key) && inputBuffer.indexOf(".") != -1) return;
            if (inputBuffer.length() < 10) inputBuffer.append(key);
        }
        amountField.setText(inputBuffer.toString());
    }

    private void addManualPayment() {
        if (inputBuffer.length() == 0) return;
        try {
            BigDecimal amount = new BigDecimal(inputBuffer.toString());
            if (amount.compareTo(remainingBalance) > 0) {
                ToastNotification.showWarning("Amount exceeds remaining balance", getScene().getWindow());
                return;
            }
            paymentsList.add(new SplitPaymentEntry(selectedMethod, amount));
            inputBuffer.setLength(0);
            amountField.setText("");
            updateRemainingBalance();
        } catch (Exception e) {
            ToastNotification.showError("Invalid amount", getScene().getWindow());
        }
    }

    private void handleQuickSplit(int divisions, String[] methods) {
        paymentsList.clear();
        BigDecimal amount = listTotal.divide(BigDecimal.valueOf(divisions), 2, RoundingMode.HALF_UP);
        BigDecimal remainder = listTotal.subtract(amount.multiply(BigDecimal.valueOf(divisions)));

        for (int i = 0; i < divisions; i++) {
            String method;
            if (methods != null && i < methods.length) {
                method = methods[i];
            } else {
                // Alternate between CASH and CARD for quick splits (3 and 4)
                method = (i % 2 == 0) ? "CASH" : "CARD";
            }
            BigDecimal finalAmount = (i == divisions - 1) ? amount.add(remainder) : amount;
            paymentsList.add(new SplitPaymentEntry(method, finalAmount));
        }
        updateRemainingBalance();
    }

    private void updateRemainingBalance() {
        BigDecimal paid = paymentsList.stream().map(SplitPaymentEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        remainingBalance = listTotal.subtract(paid);
        if (remainingBalance.compareTo(BigDecimal.ZERO) < 0) remainingBalance = BigDecimal.ZERO;
        remainingLabel.setText("Remaining: " + NumberFormat.getCurrencyInstance().format(remainingBalance));
        if (remainingBalance.compareTo(BigDecimal.ZERO) == 0) {
            remainingLabel.setStyle("-fx-text-fill: #10B981;"); // Green when fully paid
        } else {
            remainingLabel.setStyle("-fx-text-fill: white;");
        }
    }

    private HBox createTotalRow(String label, String value) {
        HBox row = new HBox();
        Label l = new Label(label);
        HBox.setHgrow(l, Priority.ALWAYS);
        Label v = new Label(value);
        row.getChildren().addAll(l, v);
        return row;
    }

    private void processTransaction() {
        List<Payment> finalPayments = new ArrayList<>();
        for (SplitPaymentEntry entry : paymentsList) {
            finalPayments.add(new Payment(entry.getMethod(), entry.getAmount()));
        }
        onProcess.accept(finalPayments);
    }

    private static class SplitPaymentEntry {
        private final String method;
        private final BigDecimal amount;

        public SplitPaymentEntry(String method, BigDecimal amount) {
            this.method = method;
            this.amount = amount;
        }

        public String getMethod() { return method; }
        public BigDecimal getAmount() { return amount; }
    }
}
