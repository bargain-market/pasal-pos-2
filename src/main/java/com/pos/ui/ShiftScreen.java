package com.pos.ui;

import com.pos.api.dto.ShiftResponse;
import com.pos.hardware.HardwareManager;
import com.pos.model.VendorPayout;
import com.pos.service.ExpenseService;
import com.pos.service.RoleBasedAccessService;
import com.pos.service.SettingsService;
import com.pos.service.ShiftService;
import com.pos.service.UserAuthService;
import com.pos.service.VendorPayoutService;
import com.pos.ui.dialogs.ExpenseDialog;
import com.pos.util.CashOperationReceiptBuilder;
import com.pos.util.DialogHelper;
import com.pos.util.ReceiptPrintHelper;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Shift management screen for cashier operations
 */
public class ShiftScreen extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(ShiftScreen.class);
    private ShiftService shiftService;
    private HardwareManager hardwareManager;
    private RoleBasedAccessService rbacService;
    private SettingsService settingsService;
    private ShiftResponse.ShiftData currentShift;

    private Label shiftStatusLabel;
    private Label cashierNameValue;
    private Label openingCashValue;
    private Label totalSalesValue;
    private Label cashSalesValue;
    private Label cardSalesValue;
    private Label transactionCountValue;
    private Label expectedCashValue;
    private Label totalCashDropsValue;
    private Label vendorPayoutsValue;
    private Label totalExpensesValue;

    private Button startShiftButton;
    private Button endShiftButton;
    private Button endDayButton;
    private Button cashDropButton;
    private Button cashAddButton;
    private Button viewCashOperationsButton;
    private Button addExpenseButton;
    private TableView<CashOperationDisplay> cashOperationsTable;
    private TableView<VendorPayoutDisplay> vendorPayoutsTable;
    private Label vendorPayoutsCashLabel;
    private Label vendorPayoutsChequeLabel;
    private Label vendorPayoutsInvoiceLabel;
    private Label vendorPayoutsCreditLabel;
    private Runnable onBackToSales;
    private Runnable onNavigateToCashDrop;
    private Runnable onNavigateToCloseShift;
    private Runnable onNavigateToEndDay;

    public ShiftScreen() {
        this(null);
    }

    public ShiftScreen(Runnable onBackToSales) {
        this(onBackToSales, null);
    }

    public ShiftScreen(Runnable onBackToSales, HardwareManager hardwareManager) {
        this.shiftService = ShiftService.getInstance();
        this.hardwareManager = hardwareManager != null ? hardwareManager : HardwareManager.getInstance();
        this.rbacService = RoleBasedAccessService.getInstance();
        this.settingsService = SettingsService.getInstance();
        this.onBackToSales = onBackToSales;
        initializeUI();
        loadShiftStatus();
    }

    public void setOnNavigateToCashDrop(Runnable callback) {
        this.onNavigateToCashDrop = callback;
    }

    public void setOnNavigateToCloseShift(Runnable callback) {
        this.onNavigateToCloseShift = callback;
    }

    public void setOnNavigateToEndDay(Runnable callback) {
        this.onNavigateToEndDay = callback;
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #f5f7fa;");

        // Top section - Header
        VBox topSection = createTopSection();
        setTop(topSection);

        // Center - Shift details & Log
        VBox centerSection = createCenterSection();
        setCenter(centerSection);

        // Bottom - Actions
        FlowPane bottomSection = createBottomSection();

        // Wrap in ScrollPane for small screens
        ScrollPane scrollPane = new ScrollPane(bottomSection);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-padding: 0;");

        setBottom(scrollPane);
    }

    private VBox createTopSection() {
        VBox section = new VBox();
        section.getStyleClass().add("header-section");

        HBox headerContent = new HBox(20);
        headerContent.setAlignment(Pos.CENTER_LEFT);

        // Back to Sales button
        if (onBackToSales != null) {
            Button backToSalesButton = new Button("← Back");
            backToSalesButton.getStyleClass().add("button");
            backToSalesButton.setStyle("-fx-background-color: #e0e0e0; -fx-text-fill: #333; -fx-padding: 8 15;");
            backToSalesButton.setOnAction(e -> onBackToSales.run());
            headerContent.getChildren().add(backToSalesButton);
        }

        Label titleLabel = new Label("Shift Management");
        titleLabel.getStyleClass().add("title");
        titleLabel.setStyle("-fx-font-size: 24px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        shiftStatusLabel = new Label("No Active Shift");
        shiftStatusLabel.getStyleClass().add("status-badge");
        shiftStatusLabel.getStyleClass().add("inactive");

        headerContent.getChildren().addAll(titleLabel, spacer, shiftStatusLabel);
        section.getChildren().add(headerContent);

        return section;
    }

    private VBox createCenterSection() {
        VBox section = new VBox(25);
        section.setPadding(new Insets(25));

        // Stats Row
        FlowPane statsContainer = new FlowPane(15, 15);
        statsContainer.setAlignment(Pos.CENTER_LEFT);

        // Create Stat Cards
        statsContainer.getChildren().add(createStatCard("Cashier", cashierNameValue = new Label("-")));
        statsContainer.getChildren().add(createStatCard("Starting Cash", openingCashValue = new Label("$0.00")));
        statsContainer.getChildren().add(createStatCard("Total Sales", totalSalesValue = new Label("$0.00")));
        statsContainer.getChildren().add(createStatCard("Cash Sales", cashSalesValue = new Label("$0.00")));
        statsContainer.getChildren().add(createStatCard("Card Sales", cardSalesValue = new Label("$0.00")));
        statsContainer.getChildren().add(createStatCard("Transactions", transactionCountValue = new Label("0")));
        statsContainer.getChildren().add(createStatCard("Expected Cash", expectedCashValue = new Label("$0.00")));
        statsContainer.getChildren().add(createStatCard("Cash Drops", totalCashDropsValue = new Label("$0.00")));
        statsContainer.getChildren().add(createStatCard("Vendor Payouts", vendorPayoutsValue = new Label("$0.00")));
        statsContainer.getChildren().add(createStatCard("Expenses", totalExpensesValue = new Label("$0.00")));

        // Split pane for Cash Operations and Vendor Payouts
        SplitPane logsSplitPane = new SplitPane();
        logsSplitPane.setDividerPositions(0.5);
        VBox.setVgrow(logsSplitPane, Priority.ALWAYS);

        // Cash Operations Log Section
        VBox cashLogSection = new VBox(10);
        cashLogSection.setPadding(new Insets(10));

        HBox logHeader = new HBox(15);
        logHeader.setAlignment(Pos.CENTER_LEFT);

        Label logTitle = new Label("💵 Cash Operations Log");
        logTitle.getStyleClass().add("shift-section-title");
        logTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        viewCashOperationsButton = new Button("🔄 Refresh");
        viewCashOperationsButton.getStyleClass().add("button");
        viewCashOperationsButton.setStyle("-fx-font-size: 11px; -fx-padding: 4 10;");
        viewCashOperationsButton.setOnAction(e -> {
            loadCashOperations();
            loadVendorPayouts();
        });
        viewCashOperationsButton.setDisable(true);

        logHeader.getChildren().addAll(logTitle, spacer, viewCashOperationsButton);

        // Cash Operations Table
        cashOperationsTable = new TableView<>();
        cashOperationsTable.getStyleClass().add("modern-table");
        cashOperationsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(cashOperationsTable, Priority.ALWAYS);

        TableColumn<CashOperationDisplay, String> typeColumn = new TableColumn<>("Type");
        typeColumn.setCellValueFactory(new PropertyValueFactory<>("type"));

        TableColumn<CashOperationDisplay, String> amountColumn = new TableColumn<>("Amount");
        amountColumn.setCellValueFactory(new PropertyValueFactory<>("amount"));
        amountColumn.setStyle("-fx-alignment: CENTER-RIGHT; -fx-font-weight: bold;");

        TableColumn<CashOperationDisplay, String> dateColumn = new TableColumn<>("Date/Time");
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("dateTime"));

        TableColumn<CashOperationDisplay, String> performedByColumn = new TableColumn<>("Performed By");
        performedByColumn.setCellValueFactory(new PropertyValueFactory<>("performedBy"));

        TableColumn<CashOperationDisplay, String> noteColumn = new TableColumn<>("Note");
        noteColumn.setCellValueFactory(new PropertyValueFactory<>("note"));

        TableColumn<CashOperationDisplay, Void> actionColumn = new TableColumn<>("Receipt");
        actionColumn.setCellFactory(col -> new TableCell<>() {
            private final Button reprintButton = new Button("Reprint");

            {
                reprintButton.getStyleClass().add("button");
                reprintButton.setStyle("-fx-font-size: 11px; -fx-padding: 4 10;");
                reprintButton.setOnAction(e -> {
                    CashOperationDisplay display = getTableView().getItems().get(getIndex());
                    reprintCashOperationReceipt(display);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getIndex() < 0 || getIndex() >= getTableView().getItems().size()) {
                    setGraphic(null);
                    return;
                }
                CashOperationDisplay display = getTableView().getItems().get(getIndex());
                reprintButton.setDisable(display == null || display.operation == null);
                setGraphic(reprintButton);
            }
        });

        cashOperationsTable.getColumns().add(typeColumn);
        cashOperationsTable.getColumns().add(amountColumn);
        cashOperationsTable.getColumns().add(dateColumn);
        cashOperationsTable.getColumns().add(performedByColumn);
        cashOperationsTable.getColumns().add(noteColumn);
        cashOperationsTable.getColumns().add(actionColumn);

        Label placeholder = new Label("No cash operations recorded for this shift");
        placeholder.setStyle("-fx-text-fill: #999; -fx-font-size: 14px;");
        cashOperationsTable.setPlaceholder(placeholder);

        cashLogSection.getChildren().addAll(logHeader, cashOperationsTable);

        // Vendor Payouts Section
        VBox vendorPayoutsSection = new VBox(10);
        vendorPayoutsSection.setPadding(new Insets(10));

        HBox vendorHeader = new HBox(15);
        vendorHeader.setAlignment(Pos.CENTER_LEFT);

        Label vendorTitle = new Label("🏢 Vendor Payouts");
        vendorTitle.getStyleClass().add("shift-section-title");
        vendorTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        vendorHeader.getChildren().add(vendorTitle);

        // Vendor Payouts Table
        vendorPayoutsTable = new TableView<>();
        vendorPayoutsTable.getStyleClass().add("modern-table");
        vendorPayoutsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(vendorPayoutsTable, Priority.ALWAYS);

        TableColumn<VendorPayoutDisplay, String> vendorNameCol = new TableColumn<>("Vendor");
        vendorNameCol.setCellValueFactory(new PropertyValueFactory<>("vendorName"));
        vendorNameCol.setPrefWidth(120);

        TableColumn<VendorPayoutDisplay, String> payoutAmountCol = new TableColumn<>("Amount");
        payoutAmountCol.setCellValueFactory(new PropertyValueFactory<>("amount"));
        payoutAmountCol.setStyle("-fx-alignment: CENTER-RIGHT; -fx-font-weight: bold;");
        payoutAmountCol.setPrefWidth(80);

        TableColumn<VendorPayoutDisplay, String> payoutMethodCol = new TableColumn<>("Method");
        payoutMethodCol.setCellValueFactory(new PropertyValueFactory<>("paymentMethod"));
        payoutMethodCol.setStyle("-fx-alignment: CENTER;");
        payoutMethodCol.setPrefWidth(70);

        TableColumn<VendorPayoutDisplay, String> chequeNumCol = new TableColumn<>("Reference");
        chequeNumCol.setCellValueFactory(new PropertyValueFactory<>("chequeNumber"));
        chequeNumCol.setStyle("-fx-alignment: CENTER;");
        chequeNumCol.setPrefWidth(110);

        TableColumn<VendorPayoutDisplay, String> payoutDateCol = new TableColumn<>("Date/Time");
        payoutDateCol.setCellValueFactory(new PropertyValueFactory<>("dateTime"));
        payoutDateCol.setPrefWidth(100);

        vendorPayoutsTable.getColumns().add(vendorNameCol);
        vendorPayoutsTable.getColumns().add(payoutAmountCol);
        vendorPayoutsTable.getColumns().add(payoutMethodCol);
        vendorPayoutsTable.getColumns().add(chequeNumCol);
        vendorPayoutsTable.getColumns().add(payoutDateCol);

        Label vendorPlaceholder = new Label("No vendor payouts for this shift");
        vendorPlaceholder.setStyle("-fx-text-fill: #999; -fx-font-size: 14px;");
        vendorPayoutsTable.setPlaceholder(vendorPlaceholder);

        // Vendor Payouts Summary
        HBox vendorSummary = new HBox(20);
        vendorSummary.setAlignment(Pos.CENTER_LEFT);
        vendorSummary.setPadding(new Insets(8));
        vendorSummary.setStyle("-fx-background-color: #f0f9ff; -fx-background-radius: 6;");

        VBox cashPayoutsBox = new VBox(2);
        Label cashPayoutsTitle = new Label("💵 Cash Payouts:");
        cashPayoutsTitle.setStyle("-fx-text-fill: #0369a1; -fx-font-size: 11px;");
        vendorPayoutsCashLabel = new Label("$0.00");
        vendorPayoutsCashLabel.setStyle("-fx-text-fill: #0369a1; -fx-font-weight: bold; -fx-font-size: 13px;");
        cashPayoutsBox.getChildren().addAll(cashPayoutsTitle, vendorPayoutsCashLabel);

        VBox chequePayoutsBox = new VBox(2);
        Label chequePayoutsTitle = new Label("📝 Cheque Payouts:");
        chequePayoutsTitle.setStyle("-fx-text-fill: #0369a1; -fx-font-size: 11px;");
        vendorPayoutsChequeLabel = new Label("$0.00");
        vendorPayoutsChequeLabel.setStyle("-fx-text-fill: #0369a1; -fx-font-weight: bold; -fx-font-size: 13px;");
        chequePayoutsBox.getChildren().addAll(chequePayoutsTitle, vendorPayoutsChequeLabel);

        VBox invoicePayoutsBox = new VBox(2);
        Label invoicePayoutsTitle = new Label("Invoice Payouts:");
        invoicePayoutsTitle.setStyle("-fx-text-fill: #0369a1; -fx-font-size: 11px;");
        vendorPayoutsInvoiceLabel = new Label("$0.00");
        vendorPayoutsInvoiceLabel.setStyle("-fx-text-fill: #0369a1; -fx-font-weight: bold; -fx-font-size: 13px;");
        invoicePayoutsBox.getChildren().addAll(invoicePayoutsTitle, vendorPayoutsInvoiceLabel);

        VBox creditPayoutsBox = new VBox(2);
        Label creditPayoutsTitle = new Label("Credit Payouts:");
        creditPayoutsTitle.setStyle("-fx-text-fill: #0369a1; -fx-font-size: 11px;");
        vendorPayoutsCreditLabel = new Label("$0.00");
        vendorPayoutsCreditLabel.setStyle("-fx-text-fill: #0369a1; -fx-font-weight: bold; -fx-font-size: 13px;");
        creditPayoutsBox.getChildren().addAll(creditPayoutsTitle, vendorPayoutsCreditLabel);

        vendorSummary.getChildren().addAll(cashPayoutsBox, chequePayoutsBox, invoicePayoutsBox, creditPayoutsBox);

        vendorPayoutsSection.getChildren().addAll(vendorHeader, vendorPayoutsTable, vendorSummary);

        logsSplitPane.getItems().addAll(cashLogSection, vendorPayoutsSection);

        section.getChildren().addAll(statsContainer, logsSplitPane);
        return section;
    }

    private VBox createStatCard(String title, Label valueLabel) {
        VBox card = new VBox(5);
        card.getStyleClass().add("stat-card");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("stat-label");

        valueLabel.getStyleClass().add("stat-value");

        card.getChildren().addAll(titleLabel, valueLabel);
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
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
        button.setMinWidth(70);
        button.setMinHeight(55);
        button.setPrefWidth(Region.USE_COMPUTED_SIZE);
        button.setPrefHeight(Region.USE_COMPUTED_SIZE);
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
        section.getStyleClass().add("action-bar");
        section.setPadding(new Insets(15));
        section.setAlignment(Pos.CENTER);
        section.setHgap(10);
        section.setVgap(10);

        // Cash operation buttons - Manager+ only (Cashier can view but not perform
        // operations)
        cashDropButton = null;
        cashAddButton = null;

        if (rbacService.hasPermission(RoleBasedAccessService.PERMISSION_CASH_OPERATIONS)) {
            cashDropButton = createIconButton("\uD83D\uDCB5", "Drop", "#2196F3");
            cashDropButton.setOnAction(e -> {
                if (onNavigateToCashDrop != null) {
                    onNavigateToCashDrop.run();
                } else {
                    showCashOperationDialog("DROP");
                }
            });
            cashDropButton.setDisable(true);

            cashAddButton = createIconButton("\uD83D\uDCB0", "Add", "#2196F3");
            cashAddButton.setOnAction(e -> showCashOperationDialog("ADD"));
            cashAddButton.setDisable(true);
        }

        if (rbacService.hasPermission(RoleBasedAccessService.PERMISSION_EXPENSES)) {
            addExpenseButton = createIconButton("\uD83D\uDCDD", "Expense", "#FF9800");
            addExpenseButton.setOnAction(e -> showAddExpenseDialog());
            addExpenseButton.setDisable(true);
        }

        startShiftButton = createIconButton("\u25B6", "Start", "#4CAF50");
        startShiftButton.setOnAction(e -> showStartShiftDialog());

        endShiftButton = createIconButton("\u23F9", "Clock Out", "#f44336");
        endShiftButton.setOnAction(e -> showEndShiftDialog());
        endShiftButton.setDisable(true);

        // End Day button - Accessible to all roles
        endDayButton = createIconButton("\uD83C\uDFC1", "End Day", "#9C27B0"); // Purple for End Day
        endDayButton.setOnAction(e -> {
            if (onNavigateToEndDay != null) {
                onNavigateToEndDay.run();
            }
        });
        endDayButton.setDisable(true); // Will be enabled when shift status loads

        // Add buttons conditionally
        if (cashDropButton != null) {
            section.getChildren().add(cashDropButton);
        }
        if (cashAddButton != null) {
            section.getChildren().add(cashAddButton);
        }
        if (addExpenseButton != null) {
            section.getChildren().add(addExpenseButton);
        }
        section.getChildren().addAll(startShiftButton, endShiftButton, endDayButton);
        return section;
    }

    private void loadShiftStatus() {
        new Thread(() -> {
            try {
                ShiftResponse.ShiftData shift = shiftService.getActiveShift();
                Platform.runLater(() -> {
                    updateShiftDisplay(shift);
                });
            } catch (Exception e) {
                logger.error("Error loading shift status", e);
                Platform.runLater(() -> {
                    updateShiftDisplay(null);
                });
            }
        }).start();
    }

    private void updateShiftDisplay(ShiftResponse.ShiftData shift) {
        currentShift = shift;

        if (shift == null || !"ACTIVE".equals(shift.status)) {
            shiftStatusLabel.setText("No Active Shift");
            shiftStatusLabel.getStyleClass().removeAll("active");
            shiftStatusLabel.getStyleClass().add("inactive");

            cashierNameValue.setText("-");
            openingCashValue.setText("$0.00");
            totalSalesValue.setText("$0.00");
            cashSalesValue.setText("$0.00");
            cardSalesValue.setText("$0.00");
            transactionCountValue.setText("0");
            expectedCashValue.setText("$0.00");
            totalCashDropsValue.setText("$0.00");
            vendorPayoutsValue.setText("$0.00");
            totalExpensesValue.setText("$0.00");

            startShiftButton.setDisable(false);
            endShiftButton.setDisable(true);
            if (cashDropButton != null) {
                cashDropButton.setDisable(true);
            }
            if (cashAddButton != null) {
                cashAddButton.setDisable(true);
            }
            if (addExpenseButton != null) {
                addExpenseButton.setDisable(true);
            }
            viewCashOperationsButton.setDisable(true);
            endDayButton.setDisable(false); // Can end day even if no shift is active? Usually yes, to reconcile all shifts.
            cashOperationsTable.getItems().clear();
            vendorPayoutsTable.getItems().clear();
            vendorPayoutsCashLabel.setText("$0.00");
            vendorPayoutsChequeLabel.setText("$0.00");
        } else {
            shiftStatusLabel.setText("Shift Active");
            shiftStatusLabel.getStyleClass().removeAll("inactive");
            shiftStatusLabel.getStyleClass().add("active");

            cashierNameValue.setText(shift.cashierName != null ? shift.cashierName : "-");

            NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
            openingCashValue.setText(currencyFormat.format(shift.openingCash));

            BigDecimal totalSales = shift.grossSales != null ? shift.grossSales : BigDecimal.ZERO;
            totalSalesValue.setText(currencyFormat.format(totalSales));

            // Payment method breakdown
            BigDecimal cashSales = shift.totalCashSales != null ? shift.totalCashSales : BigDecimal.ZERO;
            cashSalesValue.setText(currencyFormat.format(cashSales));

            BigDecimal cardSales = shift.totalCardSales != null ? shift.totalCardSales : BigDecimal.ZERO;
            BigDecimal ebtSales = shift.totalEbtSales != null ? shift.totalEbtSales : BigDecimal.ZERO;
            BigDecimal otherSales = shift.totalOtherSales != null ? shift.totalOtherSales : BigDecimal.ZERO;
            BigDecimal totalCardSales = cardSales.add(ebtSales).add(otherSales);
            cardSalesValue.setText(currencyFormat.format(totalCardSales));

            transactionCountValue.setText(String.valueOf(shift.transactionCount != null ? shift.transactionCount : 0));

            // Calculate expected cash (includes vendor cash payouts deduction)
            BigDecimal expected;
            try {
                expected = shiftService.calculateAvailableCash(shift.id);
            } catch (Exception e) {
                logger.warn("Could not calculate available cash, using fallback: {}", e.getMessage());
                expected = shift.expectedCash != null ? shift.expectedCash
                        : cashSales;
            }
            expectedCashValue.setText(currencyFormat.format(expected));

            // Calculate total cash drops from cash operations
            try {
                List<ShiftService.CashOperationInfo> operations = shiftService.getCashOperationsForShift(shift.id);
                BigDecimal totalDrops = BigDecimal.ZERO;
                for (ShiftService.CashOperationInfo op : operations) {
                    if ("DROP".equals(op.type)) {
                        totalDrops = totalDrops.add(op.amount);
                    }
                }
                totalCashDropsValue.setText(currencyFormat.format(totalDrops));
            } catch (Exception e) {
                logger.error("Error calculating cash drops", e);
                totalCashDropsValue.setText("$0.00");
            }

            // Calculate vendor cash payouts for this shift
            try {
                com.pos.service.VendorPayoutService vendorPayoutService = com.pos.service.VendorPayoutService
                        .getInstance();
                BigDecimal vendorPayouts = vendorPayoutService.getTotalCashPayoutsForShift(shift.id);
                vendorPayoutsValue.setText(currencyFormat.format(vendorPayouts));
            } catch (Exception e) {
                logger.error("Error calculating vendor payouts", e);
                vendorPayoutsValue.setText("$0.00");
            }

            // Calculate total expenses for this shift
            try {
                ExpenseService expenseService = ExpenseService.getInstance();
                BigDecimal totalExpenses = expenseService.getTotalExpensesForShift(shift.id);
                totalExpensesValue.setText(currencyFormat.format(totalExpenses));
            } catch (Exception e) {
                logger.error("Error calculating expenses", e);
                totalExpensesValue.setText("$0.00");
            }

            startShiftButton.setDisable(true);
            endShiftButton.setDisable(false);
            if (cashDropButton != null) {
                cashDropButton.setDisable(false);
            }
            if (cashAddButton != null) {
                cashAddButton.setDisable(false);
            }
            if (addExpenseButton != null) {
                addExpenseButton.setDisable(false);
            }
            viewCashOperationsButton.setDisable(false);
            loadCashOperations();
            loadVendorPayouts();
        }
    }

    private void showAddExpenseDialog() {
        if (currentShift == null) {
            showAlert("No active shift");
            return;
        }

        ExpenseDialog dialog = new ExpenseDialog(getScene() != null ? getScene().getWindow() : null);
        dialog.showAndWait().ifPresent(expense -> {
            new Thread(() -> {
                try {
                    ExpenseService expenseService = ExpenseService.getInstance();
                    expenseService.createExpense(expense);
                    Platform.runLater(() -> {
                        loadShiftStatus(); // Refresh shift display
                        showAlert("Expense added successfully!");
                    });
                } catch (Exception e) {
                    logger.error("Error creating expense", e);
                    Platform.runLater(() -> {
                        showAlert("Failed to add expense: " + e.getMessage());
                    });
                }
            }).start();
        });
    }

    private void showStartShiftDialog() {
        Dialog<ShiftStartData> dialog = new Dialog<>();
        dialog.setTitle("Start Shift");
        dialog.setHeaderText("Enter shift details");

        javafx.stage.Modality modality = javafx.stage.Modality.APPLICATION_MODAL;
        dialog.initModality(modality);
        DialogHelper.setDialogOwner(dialog, null);

        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(15);
        grid.setPadding(new Insets(20));

        com.pos.ui.components.TouchTextField cashierField = com.pos.ui.components.TouchScreenComponents
                .createTouchOnlyTextField("Cashier Name");
        cashierField.setPrefWidth(250);
        cashierField.setMaxWidth(300);
        try {
            com.pos.service.UserAuthService authService = com.pos.service.UserAuthService.getInstance();
            String currentUserName = authService.getCurrentUserName();
            if (currentUserName != null && !currentUserName.isEmpty()) {
                cashierField.setText(currentUserName);
            }
        } catch (Exception e) {
            // Ignore
        }

        com.pos.ui.components.TouchTextField registerField = com.pos.ui.components.TouchScreenComponents
                .createTouchOnlyTextField("Register ID");
        registerField.setPrefWidth(250);
        registerField.setMaxWidth(300);
        registerField.setText("REG001");

        com.pos.ui.components.TouchTextField openingCashField = com.pos.ui.components.TouchScreenComponents
                .createTouchOnlyNumericField("0.00");
        openingCashField.setPrefWidth(250);
        openingCashField.setMaxWidth(300);

        com.pos.ui.components.TouchTextArea noteArea = com.pos.ui.components.TouchScreenComponents
                .createTouchOnlyTextArea("Opening Note (Optional)");
        noteArea.setPrefRowCount(2);
        noteArea.setPrefWidth(250);
        noteArea.setMaxWidth(350);
        noteArea.setWrapText(true);

        // Create labels with consistent styling
        Label cashierLabel = new Label("Cashier Name:");
        Label registerLabel = new Label("Register ID:");
        Label openingLabel = new Label("Opening Cash ($):");
        Label notesLabel = new Label("Notes:");

        // Apply style to all labels
        String labelStyle = "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;";
        cashierLabel.setStyle(labelStyle);
        registerLabel.setStyle(labelStyle);
        openingLabel.setStyle(labelStyle);
        notesLabel.setStyle(labelStyle);

        grid.add(cashierLabel, 0, 0);
        grid.add(cashierField, 1, 0);
        grid.add(registerLabel, 0, 1);
        grid.add(registerField, 1, 1);
        grid.add(openingLabel, 0, 2);
        grid.add(openingCashField, 1, 2);
        grid.add(notesLabel, 0, 3);
        grid.add(noteArea, 1, 3);

        // Add column constraints to prevent label truncation
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setMinWidth(160);
        col1.setPrefWidth(180);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(col1, col2);

        dialog.getDialogPane().setContent(grid);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(dialog, 0.45, 0.45);

        ButtonType startButtonType = new ButtonType("Start", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(startButtonType, ButtonType.CANCEL);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == startButtonType) {
                try {
                    String text = openingCashField.getText().trim();
                    if (text.isEmpty()) {
                        text = "0.00";
                    }
                    BigDecimal openingCash = new BigDecimal(text);
                    if (openingCash.compareTo(BigDecimal.ZERO) < 0) {
                        showAlert("Opening cash cannot be negative");
                        return null;
                    }
                    return new ShiftStartData(
                            cashierField.getText(),
                            registerField.getText(),
                            openingCash,
                            noteArea.getText());
                } catch (NumberFormatException e) {
                    showAlert("Invalid opening cash amount. Please enter a valid number.");
                    return null;
                } catch (Exception e) {
                    showAlert("Error reading input: " + e.getMessage());
                    return null;
                }
            }
            return null;
        });

        dialog.showAndWait().ifPresent(data -> {
            new Thread(() -> {
                try {
                    ShiftResponse.ShiftData shift = shiftService.startShift(
                            data.cashierName,
                            data.registerId,
                            data.openingCash,
                            data.note);
                    Platform.runLater(() -> {
                        updateShiftDisplay(shift);
                        showAlert("Shift started successfully!");
                    });
                } catch (Exception e) {
                    logger.error("Failed to start shift", e);
                    Platform.runLater(() -> {
                        showAlert("Failed to start shift: " + e.getMessage());
                    });
                }
            }).start();
        });
    }

    private void showEndShiftDialog() {
        if (currentShift == null) {
            showAlert("No active shift to end");
            return;
        }

        if (onNavigateToCloseShift != null) {
            onNavigateToCloseShift.run();
        } else {
            logger.warn("onNavigateToCloseShift callback not set. Cannot navigate to Close Shift screen.");
            showAlert("Navigation error: Could not open Close Shift screen.");
        }
    }



    private void showCashOperationDialog(String type) {
        if (currentShift == null) {
            showAlert("No active shift");
            return;
        }

        Dialog<CashOperationData> dialog = new Dialog<>();
        dialog.setTitle(type.equals("DROP") ? "Cash Drop" : "Cash Add");
        dialog.setHeaderText(type.equals("DROP") ? "Remove cash from register drawer" : "Add cash to register drawer");

        javafx.stage.Modality modality = javafx.stage.Modality.APPLICATION_MODAL;
        dialog.initModality(modality);
        DialogHelper.setDialogOwner(dialog, null);

        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(15);
        grid.setPadding(new Insets(20));

        // Add column constraints to prevent label truncation
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setMinWidth(160);
        col1.setPrefWidth(180);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(col1, col2);

        // Create labels with consistent styling
        String labelStyle = "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;";

        // Calculate and display available cash for cash drops
        final BigDecimal availableCash;
        if ("DROP".equals(type)) {
            BigDecimal calculatedCash = BigDecimal.ZERO;
            try {
                calculatedCash = shiftService.calculateAvailableCash(currentShift.id);
                NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

                Label availableCashMainLabel = new Label("Available Cash:");
                availableCashMainLabel.setStyle(labelStyle);
                Label availableCashValueLabel = new Label(currencyFormat.format(calculatedCash));
                availableCashValueLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 18px; -fx-text-fill: #2a5298;");

                grid.add(availableCashMainLabel, 0, 0);
                grid.add(availableCashValueLabel, 1, 0);
            } catch (Exception e) {
                logger.error("Error calculating available cash", e);
            }
            availableCash = calculatedCash;
        } else {
            availableCash = BigDecimal.ZERO;
        }

        com.pos.ui.components.TouchTextField amountField = com.pos.ui.components.TouchScreenComponents
                .createTouchOnlyNumericField("0.00");
        amountField.setPrefWidth(250);
        amountField.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(amountField, Priority.ALWAYS);

        // Add validation label for cash drops
        Label validationLabel = new Label();
        validationLabel.setStyle("-fx-text-fill: #d32f2f; -fx-font-size: 12px;");
        validationLabel.setVisible(false);
        validationLabel.setWrapText(true);
        validationLabel.setMaxWidth(Double.MAX_VALUE);

        com.pos.ui.components.TouchTextArea noteArea = com.pos.ui.components.TouchScreenComponents
                .createTouchOnlyTextArea("Reason for " + (type.equals("DROP") ? "cash drop" : "cash add") + " (e.g., " +
                        (type.equals("DROP") ? "Excess cash removal" : "Change replenishment") + ")");
        noteArea.setPrefRowCount(3);
        noteArea.setPrefWidth(250);
        noteArea.setMaxWidth(Double.MAX_VALUE);
        noteArea.setWrapText(true);
        GridPane.setHgrow(noteArea, Priority.ALWAYS);

        int amountRow = "DROP".equals(type) ? 1 : 0;
        Label amountLabel = new Label(type.equals("DROP") ? "Drop Amount ($):" : "Add Amount ($):");
        amountLabel.setStyle(labelStyle);
        grid.add(amountLabel, 0, amountRow);
        grid.add(amountField, 1, amountRow);

        if ("DROP".equals(type)) {
            grid.add(validationLabel, 0, amountRow + 1, 2, 1);
        }

        int noteRow = amountRow + ("DROP".equals(type) ? 2 : 1);
        Label noteLabel = new Label("Reason/Note:");
        noteLabel.setStyle(labelStyle);
        grid.add(noteLabel, 0, noteRow);
        grid.add(noteArea, 1, noteRow);

        // Add real-time validation for cash drops
        if ("DROP".equals(type)) {
            amountField.textProperty().addListener((observable, oldValue, newValue) -> {
                try {
                    if (newValue != null && !newValue.trim().isEmpty()) {
                        BigDecimal amount = new BigDecimal(newValue.trim());
                        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                            validationLabel.setText("Amount must be greater than zero");
                            validationLabel.setVisible(true);
                        } else if (amount.compareTo(availableCash) > 0) {
                            NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
                            validationLabel.setText(String.format(
                                    "This drop exceeds current drawer cash by %s and will appear in short/over reporting.",
                                    currencyFormat.format(amount.subtract(availableCash))));
                            validationLabel.setVisible(true);
                        } else {
                            validationLabel.setVisible(false);
                        }
                    } else {
                        validationLabel.setVisible(false);
                    }
                } catch (NumberFormatException e) {
                    validationLabel.setText("Invalid amount format");
                    validationLabel.setVisible(true);
                }
            });
        }

        dialog.getDialogPane().setContent(grid);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(dialog, 0.5, 0.55);

        ButtonType confirmButtonType = new ButtonType("Confirm", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirmButtonType, ButtonType.CANCEL);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == confirmButtonType) {
                try {
                    String amountText = amountField.getText().trim();
                    if (amountText.isEmpty()) {
                        showAlert("Please enter an amount");
                        return null;
                    }

                    BigDecimal amount = new BigDecimal(amountText);

                    // Validate amount is positive
                    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                        showAlert("Amount must be greater than zero");
                        return null;
                    }

                    return new CashOperationData(amount, noteArea.getText());
                } catch (NumberFormatException e) {
                    showAlert("Invalid amount format. Please enter a valid number.");
                    return null;
                } catch (Exception e) {
                    showAlert("Invalid amount: " + e.getMessage());
                    return null;
                }
            }
            return null;
        });

        dialog.showAndWait().ifPresent(data -> {
            new Thread(() -> {
                try {
                    UserAuthService authService = UserAuthService.getInstance();
                    String userId = authService.getCurrentPosUserId();
                    String userName = authService.getCurrentUserName();

                    shiftService.recordCashOperation(
                            currentShift.id,
                            type,
                            data.amount,
                            data.note,
                            userId != null ? userId : "Unknown",
                            userName != null ? userName : (currentShift.cashierName != null ? currentShift.cashierName : "Unknown"));
                    Platform.runLater(() -> {
                        loadShiftStatus();
                        loadCashOperations();
                        showAlert("Cash operation recorded successfully!");
                    });
                } catch (IllegalArgumentException e) {
                    logger.error("Cash operation validation failed", e);
                    Platform.runLater(() -> {
                        showAlert("Cash operation failed: " + e.getMessage());
                    });
                } catch (Exception e) {
                    logger.error("Failed to record cash operation", e);
                    Platform.runLater(() -> {
                        showAlert("Failed to record cash operation: " + e.getMessage());
                    });
                }
            }).start();
        });
    }

    private void loadCashOperations() {
        if (currentShift == null) {
            cashOperationsTable.getItems().clear();
            return;
        }

        new Thread(() -> {
            try {
                List<ShiftService.CashOperationInfo> operations = shiftService
                        .getCashOperationsForShift(currentShift.id);
                Platform.runLater(() -> {
                    cashOperationsTable.getItems().clear();
                    NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

                    for (ShiftService.CashOperationInfo op : operations) {
                        CashOperationDisplay display = new CashOperationDisplay();
                        display.operation = op;
                        display.type = "DROP".equals(op.type) ? "Cash Drop" : "Cash Add";
                        display.amount = currencyFormat.format(op.amount);

                        try {
                            if (op.createdAt != null && !op.createdAt.isEmpty()) {
                                Instant instant = Instant.parse(op.createdAt);
                                Date date = Date.from(instant);
                                SimpleDateFormat dateFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
                                display.dateTime = dateFormat.format(date);
                            } else {
                                display.dateTime = "-";
                            }
                        } catch (Exception e) {
                            display.dateTime = op.createdAt != null ? op.createdAt : "-";
                        }

                        String performedBy = op.performedByName != null && !op.performedByName.isEmpty()
                                ? op.performedByName
                                : op.performedBy;
                        display.performedBy = performedBy != null ? performedBy : "-";
                        display.note = op.note != null && !op.note.isEmpty() ? op.note : "-";

                        cashOperationsTable.getItems().add(display);
                    }
                });
            } catch (Exception e) {
                logger.error("Error loading cash operations", e);
                Platform.runLater(() -> {
                    showAlert("Failed to load cash operations: " + e.getMessage());
                });
            }
        }).start();
    }

    private void reprintCashOperationReceipt(CashOperationDisplay display) {
        if (display == null || display.operation == null) {
            showAlert("Cash operation receipt data is not available.");
            return;
        }

        try {
            String receiptText = CashOperationReceiptBuilder.buildReceipt(
                    settingsService.getStoreName(),
                    display.operation);
            String receiptId = "CASH-OP-" + (display.operation.id != null ? display.operation.id : System.currentTimeMillis());
            ReceiptPrintHelper.printReceiptConditionally(
                    receiptText,
                    receiptId,
                    getScene() != null ? getScene().getWindow() : null,
                    settingsService);
        } catch (Exception e) {
            logger.error("Failed to reprint cash operation receipt", e);
            showAlert("Failed to reprint cash operation receipt: " + e.getMessage());
        }
    }

    /**
     * Load vendor payouts for the current shift
     */
    private void loadVendorPayouts() {
        if (currentShift == null) {
            vendorPayoutsTable.getItems().clear();
            vendorPayoutsCashLabel.setText("$0.00");
            vendorPayoutsChequeLabel.setText("$0.00");
            vendorPayoutsInvoiceLabel.setText("$0.00");
            vendorPayoutsCreditLabel.setText("$0.00");
            return;
        }

        new Thread(() -> {
            try {
                VendorPayoutService vendorPayoutService = VendorPayoutService.getInstance();
                List<VendorPayout> payouts = vendorPayoutService.getPayoutsForShift(currentShift.id);

                Platform.runLater(() -> {
                    vendorPayoutsTable.getItems().clear();
                    NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
                    BigDecimal totalCash = BigDecimal.ZERO;
                    BigDecimal totalCheque = BigDecimal.ZERO;
                    BigDecimal totalInvoice = BigDecimal.ZERO;
                    BigDecimal totalCredit = BigDecimal.ZERO;

                    for (VendorPayout payout : payouts) {
                        VendorPayoutDisplay display = new VendorPayoutDisplay();
                        display.vendorName = payout.getVendorName() != null ? payout.getVendorName() : "-";
                        display.amount = currencyFormat
                                .format(payout.getAmountPaid() != null ? payout.getAmountPaid() : BigDecimal.ZERO);
                        display.paymentMethod = payout.getPaymentMethod() != null
                                ? ("CASH".equals(payout.getPaymentMethod()) ? "💵 Cash" : "📝 Cheque")
                                : "-";
                        display.chequeNumber = payout.getChequeNumber() != null && !payout.getChequeNumber().isEmpty()
                                ? payout.getChequeNumber()
                                : "-";

                        display.paymentMethod = formatVendorPayoutMethod(payout.getPaymentMethod());
                        display.chequeNumber = getVendorPayoutReference(payout);

                        // Format date
                        try {
                            String paidAt = payout.getPaidAt();
                            if (paidAt != null && !paidAt.isEmpty()) {
                                // Try parsing ISO format
                                if (paidAt.contains("T")) {
                                    java.time.LocalDateTime dateTime = java.time.LocalDateTime.parse(paidAt);
                                    java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter
                                            .ofPattern("MM/dd HH:mm");
                                    display.dateTime = dateTime.format(formatter);
                                } else {
                                    display.dateTime = paidAt.substring(0, Math.min(10, paidAt.length()));
                                }
                            } else {
                                display.dateTime = "-";
                            }
                        } catch (Exception e) {
                            display.dateTime = payout.getPaidAt() != null
                                    ? payout.getPaidAt().substring(0, Math.min(10, payout.getPaidAt().length()))
                                    : "-";
                        }

                        vendorPayoutsTable.getItems().add(display);

                        // Sum by payment method
                        BigDecimal amt = payout.getAmountPaid() != null ? payout.getAmountPaid() : BigDecimal.ZERO;
                        switch (normalizePaymentMethod(payout.getPaymentMethod())) {
                            case "CASH" -> totalCash = totalCash.add(amt);
                            case "CHEQUE" -> totalCheque = totalCheque.add(amt);
                            case "INVOICE" -> totalInvoice = totalInvoice.add(amt);
                            case "CREDIT" -> totalCredit = totalCredit.add(amt);
                            default -> {
                            }
                        }
                    }

                    vendorPayoutsCashLabel.setText(currencyFormat.format(totalCash));
                    vendorPayoutsChequeLabel.setText(currencyFormat.format(totalCheque));
                    vendorPayoutsInvoiceLabel.setText(currencyFormat.format(totalInvoice));
                    vendorPayoutsCreditLabel.setText(currencyFormat.format(totalCredit));
                });
            } catch (Exception e) {
                logger.error("Error loading vendor payouts", e);
                Platform.runLater(() -> {
                    vendorPayoutsTable.getItems().clear();
                    vendorPayoutsCashLabel.setText("$0.00");
                    vendorPayoutsChequeLabel.setText("$0.00");
                    vendorPayoutsInvoiceLabel.setText("$0.00");
                    vendorPayoutsCreditLabel.setText("$0.00");
                });
            }
        }).start();
    }

    private String normalizePaymentMethod(String paymentMethod) {
        return paymentMethod == null ? "" : paymentMethod.trim().toUpperCase();
    }

    private String formatVendorPayoutMethod(String paymentMethod) {
        return switch (normalizePaymentMethod(paymentMethod)) {
            case "CASH" -> "Cash";
            case "CHEQUE" -> "Cheque";
            case "INVOICE" -> "Invoice";
            case "CREDIT" -> "Credit";
            default -> "-";
        };
    }

    private String getVendorPayoutReference(VendorPayout payout) {
        String paymentMethod = normalizePaymentMethod(payout.getPaymentMethod());
        String chequeNumber = payout.getChequeNumber();
        String invoiceNumber = payout.getPaymentReference();

        if ("CHEQUE".equals(paymentMethod)) {
            if (chequeNumber != null && !chequeNumber.isBlank() && invoiceNumber != null && !invoiceNumber.isBlank()) {
                return chequeNumber + " / Inv " + invoiceNumber;
            }
            if (chequeNumber != null && !chequeNumber.isBlank()) {
                return chequeNumber;
            }
            if (invoiceNumber != null && !invoiceNumber.isBlank()) {
                return "Inv " + invoiceNumber;
            }
        }

        String reference = invoiceNumber;
        if (reference == null || reference.isBlank()) {
            reference = chequeNumber;
        }
        return reference == null || reference.isBlank() ? "-" : reference;
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Shift Management");
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogHelper.setAlertOwner(alert, null);
        alert.showAndWait();
    }

    // Data classes
    private static class ShiftStartData {
        String cashierName;
        String registerId;
        BigDecimal openingCash;
        String note;

        ShiftStartData(String cashierName, String registerId, BigDecimal openingCash, String note) {
            this.cashierName = cashierName;
            this.registerId = registerId;
            this.openingCash = openingCash;
            this.note = note;
        }
    }



    private static class CashOperationData {
        BigDecimal amount;
        String note;

        CashOperationData(BigDecimal amount, String note) {
            this.amount = amount;
            this.note = note;
        }
    }

    public static class CashOperationDisplay {
        private ShiftService.CashOperationInfo operation;
        private String type;
        private String amount;
        private String dateTime;
        private String performedBy;
        private String note;

        public ShiftService.CashOperationInfo getOperation() {
            return operation;
        }

        public void setOperation(ShiftService.CashOperationInfo operation) {
            this.operation = operation;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getAmount() {
            return amount;
        }

        public void setAmount(String amount) {
            this.amount = amount;
        }

        public String getDateTime() {
            return dateTime;
        }

        public void setDateTime(String dateTime) {
            this.dateTime = dateTime;
        }

        public String getPerformedBy() {
            return performedBy;
        }

        public void setPerformedBy(String performedBy) {
            this.performedBy = performedBy;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    /**
     * Display class for vendor payouts in the table
     */
    public static class VendorPayoutDisplay {
        private String vendorName;
        private String amount;
        private String paymentMethod;
        private String chequeNumber;
        private String dateTime;

        public String getVendorName() {
            return vendorName;
        }

        public void setVendorName(String vendorName) {
            this.vendorName = vendorName;
        }

        public String getAmount() {
            return amount;
        }

        public void setAmount(String amount) {
            this.amount = amount;
        }

        public String getPaymentMethod() {
            return paymentMethod;
        }

        public void setPaymentMethod(String paymentMethod) {
            this.paymentMethod = paymentMethod;
        }

        public String getChequeNumber() {
            return chequeNumber;
        }

        public void setChequeNumber(String chequeNumber) {
            this.chequeNumber = chequeNumber;
        }

        public String getDateTime() {
            return dateTime;
        }

        public void setDateTime(String dateTime) {
            this.dateTime = dateTime;
        }
    }
}
