package com.pos.ui;

import com.pos.hardware.HardwareManager;
import com.pos.service.EndOfDayReportService;
import com.pos.service.ReportService;
import com.pos.service.UserAuthService;
import com.pos.util.DialogHelper;
import com.pos.util.ExportService;
import com.pos.util.ReceiptPrintHelper;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Reports & Dashboard View - Sales analytics and reporting
 */
public class ReportsView extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(ReportsView.class);

    private final HardwareManager hardwareManager;
    private ReportService reportService;
    private EndOfDayReportService eodReportService;

    // UI Components
    private ComboBox<String> dateRangeCombo;
    private DatePicker startDatePicker;
    private DatePicker endDatePicker;
    private Button refreshButton;
    private Button exportButton;
    private Button previewEodButton;
    private Button printEodButton;
    private ComboBox<CashierItem> cashierCombo;

    // Summary cards
    // Summary cards
    private Label grossSalesLabel;
    private Label netSalesLabel;
    // Total Revenue removed as per user request
    private Label transactionCountLabel;
    private Label cashSalesLabel;
    private Label cardSalesLabel;
    private Label averageTransactionLabel;
    private Label totalExpensesLabel;
    private Label totalGpiLabel;
    private Label totalEbtFeesLabel;
    private Label finalDepositLabel;

    // Tables
    private TableView<EmployeeSalesRow> employeeTable;
    private TableView<PaymentMethodRow> paymentMethodTable;
    private TableView<TopProductRow> topProductsTable;

    // Charts
    private PieChart paymentMethodChart;
    private BarChart<String, Number> topProductsChart;

    // Data
    private ObservableList<EmployeeSalesRow> employeeSalesList;
    private ObservableList<PaymentMethodRow> paymentMethodList;
    private ObservableList<TopProductRow> topProductsList;

    private LocalDate currentStartDate;
    private LocalDate currentEndDate;
    private Runnable onBackToSales;

    public ReportsView(HardwareManager hardwareManager) {
        this(hardwareManager, null);
    }

    public ReportsView(HardwareManager hardwareManager, Runnable onBackToSales) {
        this.hardwareManager = hardwareManager;
        this.reportService = ReportService.getInstance();
        this.eodReportService = EndOfDayReportService.getInstance();
        this.employeeSalesList = FXCollections.observableArrayList();
        this.paymentMethodList = FXCollections.observableArrayList();
        this.topProductsList = FXCollections.observableArrayList();
        this.onBackToSales = onBackToSales;

        // Set default date range to today
        this.currentEndDate = LocalDate.now();
        this.currentStartDate = LocalDate.now();

        initializeUI();
        loadReports();
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #f5f7fa;");

        // Top section - Title, date range selector, and action buttons
        VBox topSection = createTopSection();
        setTop(topSection);

        // Center - Scrollable content with summary cards and tables
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setContent(createContentArea());
        scrollPane.setFitToWidth(true);
        // Remove fitToHeight to prevent layout loops and shaking
        scrollPane.setFitToHeight(false);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        setCenter(scrollPane);
    }

    private VBox createTopSection() {
        VBox topSection = new VBox(15);
        topSection.setPadding(new Insets(20));
        topSection.setStyle("-fx-background-color: white;");

        // Title and Back button row
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

        // Cashier filter
        Label cashierLabel = new Label("Cashier:");
        cashierCombo = new ComboBox<>();
        cashierCombo.setPrefWidth(150);
        
        // Load cashiers
        populateCashierCombo();
        
        cashierCombo.setOnAction(e -> loadReports());
        
        HBox cashierBox = new HBox(10, cashierLabel, cashierCombo);
        cashierBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(cashierBox, Priority.NEVER);

        // Title
        Label titleLabel = new Label("Sales Reports & Dashboard");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        titleRow.getChildren().addAll(titleLabel, cashierBox);

        // Date range selector and action buttons organized into two rows
        VBox controlsBox = new VBox(15);
        controlsBox.setPadding(new Insets(0, 0, 5, 0));

        // Row 1: Date selections
        HBox dateRow = new HBox(10);
        dateRow.setAlignment(Pos.CENTER_LEFT);

        Label dateRangeLabel = new Label("Date Range:");
        dateRangeCombo = new ComboBox<>();
        dateRangeCombo.getItems().addAll("Today", "Yesterday", "This Week", "This Month", "Custom");
        dateRangeCombo.setValue("Today");
        dateRangeCombo.setPrefWidth(130);
        dateRangeCombo.setOnAction(e -> handleDateRangeChange());

        Label startLabel = new Label("From:");
        startDatePicker = new DatePicker(currentStartDate);
        startDatePicker.setPrefWidth(140);
        startDatePicker.setDisable(true);
        startDatePicker.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && "Custom".equals(dateRangeCombo.getValue())) {
                currentStartDate = newVal;
                loadReports();
            }
        });

        Label endLabel = new Label("To:");
        endDatePicker = new DatePicker(currentEndDate);
        endDatePicker.setPrefWidth(140);
        endDatePicker.setDisable(true);
        endDatePicker.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && "Custom".equals(dateRangeCombo.getValue())) {
                currentEndDate = newVal;
                loadReports();
            }
        });

        dateRow.getChildren().addAll(
                dateRangeLabel, dateRangeCombo,
                startLabel, startDatePicker,
                endLabel, endDatePicker);

        // Row 2: Action buttons
        HBox actionRow = new HBox(10);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        refreshButton = new Button("Refresh");
        refreshButton.setMinWidth(Region.USE_PREF_SIZE);
        refreshButton.setOnAction(e -> loadReports());

        exportButton = new Button("Export to CSV");
        exportButton.setMinWidth(Region.USE_PREF_SIZE);
        exportButton.setOnAction(e -> handleExport());

        previewEodButton = new Button("Preview End of Day");
        previewEodButton.setMinWidth(Region.USE_PREF_SIZE);
        previewEodButton.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-weight: bold;");
        previewEodButton.setOnAction(e -> handlePreviewEndOfDay());

        printEodButton = new Button("Print End of Day");
        printEodButton.setMinWidth(Region.USE_PREF_SIZE);
        printEodButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        printEodButton.setOnAction(e -> handlePrintEndOfDay());

        actionRow.getChildren().addAll(refreshButton, exportButton, previewEodButton, printEodButton);

        controlsBox.getChildren().addAll(dateRow, actionRow);
        topSection.getChildren().addAll(titleRow, controlsBox);

        return topSection;
    }

    private VBox createContentArea() {
        VBox contentArea = new VBox(20);
        contentArea.setPadding(new Insets(20));

        // Summary cards
        FlowPane summaryCards = createSummaryCards();

        // Employee sales table
        VBox employeeSection = createEmployeeSalesSection();

        // Payment method breakdown section
        VBox paymentMethodSection = createPaymentMethodSection();

        // Top products section
        VBox topProductsSection = createTopProductsSection();

        contentArea.getChildren().addAll(
                summaryCards,
                employeeSection,
                paymentMethodSection,
                topProductsSection);

        return contentArea;
    }

    private FlowPane createSummaryCards() {
        FlowPane cardsBox = new FlowPane();
        cardsBox.setHgap(15);
        cardsBox.setVgap(15);
        cardsBox.setPadding(new Insets(0, 0, 15, 0));
        cardsBox.setPrefWrapLength(1000); // Encourage wrapping but keep it stable

        // Gross Sales Card (NRS)
        VBox grossSalesCard = createStatCard("Gross Sales", "$0.00", "#795548",
                "Net sales plus discounts, fees/surcharge, and sales tax");
        grossSalesLabel = (Label) ((VBox) grossSalesCard.getChildren().get(0)).getChildren().get(0);

        // Net Sales Card (NRS)
        VBox netSalesCard = createStatCard("Net Sales", "$0.00", "#673AB7",
                "Subtotal less fees/surcharge and EBT fees");
        netSalesLabel = (Label) ((VBox) netSalesCard.getChildren().get(0)).getChildren().get(0);

        // Transaction Count Card
        VBox transactionCard = createStatCard("Transactions", "0", "#4CAF50",
                "Total number of completed sales transactions");
        transactionCountLabel = (Label) ((VBox) transactionCard.getChildren().get(0)).getChildren().get(0);

        // Cash Sales Card
        VBox cashCard = createStatCard("Cash Sales", "$0.00", "#FF9800",
                "Total sales paid by Cash (including split payments)");
        cashSalesLabel = (Label) ((VBox) cashCard.getChildren().get(0)).getChildren().get(0);

        // Card Sales Card
        VBox cardCard = createStatCard("Card Sales", "$0.00", "#9C27B0",
                "Total sales paid by Card (including split payments)");
        cardSalesLabel = (Label) ((VBox) cardCard.getChildren().get(0)).getChildren().get(0);

        // Average Transaction Card
        VBox avgCard = createStatCard("Avg Transaction", "$0.00", "#00BCD4",
                "Total collected divided by number of transactions");
        averageTransactionLabel = (Label) ((VBox) avgCard.getChildren().get(0)).getChildren().get(0);

        // Total Expenses Card
        VBox expensesCard = createStatCard("Total Expenses", "$0.00", "#F44336",
                "Total expenses recorded for the selected period");
        totalExpensesLabel = (Label) ((VBox) expensesCard.getChildren().get(0)).getChildren().get(0);

        // GPI Card
        VBox gpiCard = createStatCard("GPI", "$0.00", "#607D8B",
                "Total Grid Protection Insurance (Surcharge) collected");
        totalGpiLabel = (Label) ((VBox) gpiCard.getChildren().get(0)).getChildren().get(0);

        // EBT Fees Card
        VBox ebtFeesCard = createStatCard("EBT Fees", "$0.00", "#FF5722",
                "Total EBT processing fees (Merchant Expense)");
        totalEbtFeesLabel = (Label) ((VBox) ebtFeesCard.getChildren().get(0)).getChildren().get(0);

        // Final Deposit Card
        VBox finalDepositCard = createStatCard("Final Deposit", "$0.00", "#4CAF50",
                "Total collected from customers before payouts or expenses");
        finalDepositLabel = (Label) ((VBox) finalDepositCard.getChildren().get(0)).getChildren().get(0);

        cardsBox.getChildren().addAll(grossSalesCard, netSalesCard, transactionCard, cashCard,
                cardCard, avgCard, expensesCard, gpiCard, ebtFeesCard, finalDepositCard);

        return cardsBox;
    }

    private VBox createStatCard(String label, String value, String color, String description) {
        VBox card = new VBox(5);
        card.setPadding(new Insets(15));
        card.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 5;");
        card.setPrefWidth(180);
        card.setMinWidth(180);

        // Value Label
        Label valueLabel = new Label(value);
        valueLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: white;");

        // Title and Info Icon Row
        HBox titleRow = new HBox(5);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label titleLabel = new Label(label);
        titleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: white;");

        // Info Button (i)
        Label infoIcon = new Label("i");
        infoIcon.setStyle(
                "-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: " + color + "; " +
                        "-fx-background-color: white; -fx-background-radius: 10; -fx-min-width: 14px; -fx-min-height: 14px; "
                        +
                        "-fx-alignment: center; -fx-padding: 0 0 0 0; -fx-cursor: hand;");

        // Create tooltip
        Tooltip tooltip = new Tooltip(description);
        tooltip.setStyle("-fx-font-size: 14px;");
        tooltip.setShowDelay(javafx.util.Duration.ZERO);
        tooltip.setAutoHide(true);
        Tooltip.install(infoIcon, tooltip);

        // Handle click for touch or manual activation
        infoIcon.setOnMouseClicked(e -> {
            if (tooltip.isShowing()) {
                tooltip.hide();
            } else {
                Point2D p = infoIcon.localToScreen(0, 0);
                if (p != null) {
                    tooltip.show(infoIcon, p.getX(), p.getY() + infoIcon.getHeight() + 5);
                }
            }
            e.consume(); // Prevent event bubbling
        });

        titleRow.getChildren().addAll(titleLabel, infoIcon);
        // Push info icon to the right if needed, or keep next to text
        // HBox.setHgrow(titleLabel, Priority.ALWAYS); // Optional: push icon to far
        // right

        // Wrap value in a container to maintain existing structure access if needed,
        // but since we look up by index in createSummaryCards, we need to be careful.
        // The original code accessed children 0 and 1.
        // child 0 was valueLabel, child 1 was titleLabel.
        // We will keep a VBox wrapper for valueLabel to make it easy to find?
        // Actually, the original code did:
        // grossSalesLabel = (Label) grossSalesCard.getChildren().get(0);
        //
        // Now calculateStatCard returns a VBox with:
        // 0: VBox containing valueLabel (to keep structure compatible-ish or just
        // update access)
        // OR we just change the access pattern in createSummaryCards.
        // Let's create a container for value so we can reliably find it.

        VBox valueContainer = new VBox(valueLabel);
        valueContainer.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(valueContainer, titleRow);
        return card;
    }

    private VBox createEmployeeSalesSection() {
        VBox section = new VBox(10);
        section.setPadding(new Insets(15));
        section.setStyle("-fx-background-color: white; -fx-background-radius: 5;");

        Label sectionTitle = new Label("Sales by Employee");
        sectionTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        employeeTable = new TableView<>();
        employeeTable.setItems(employeeSalesList);
        employeeTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        employeeTable.setPrefHeight(200);

        // Employee Name column
        TableColumn<EmployeeSalesRow, String> nameCol = new TableColumn<>("Employee Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("employeeName"));
        nameCol.setPrefWidth(200);

        // Transaction Count column
        TableColumn<EmployeeSalesRow, Integer> countCol = new TableColumn<>("Transactions");
        countCol.setCellValueFactory(new PropertyValueFactory<>("transactionCount"));
        countCol.setPrefWidth(120);

        // Total Sales column
        TableColumn<EmployeeSalesRow, String> totalCol = new TableColumn<>("Total Sales");
        totalCol.setCellValueFactory(new PropertyValueFactory<>("totalSales"));
        totalCol.setPrefWidth(150);

        // Cash Sales column
        TableColumn<EmployeeSalesRow, String> cashCol = new TableColumn<>("Cash Sales");
        cashCol.setCellValueFactory(new PropertyValueFactory<>("cashSales"));
        cashCol.setPrefWidth(150);

        // Card Sales column
        TableColumn<EmployeeSalesRow, String> cardCol = new TableColumn<>("Card Sales");
        cardCol.setCellValueFactory(new PropertyValueFactory<>("cardSales"));
        cardCol.setPrefWidth(150);

        @SuppressWarnings("unchecked")
        TableColumn<EmployeeSalesRow, ?>[] columns = new TableColumn[] { nameCol, countCol, totalCol, cashCol,
                cardCol };
        employeeTable.getColumns().addAll(columns);

        section.getChildren().addAll(sectionTitle, employeeTable);

        return section;
    }

    private VBox createPaymentMethodSection() {
        VBox section = new VBox(10);
        section.setPadding(new Insets(15));
        section.setStyle("-fx-background-color: white; -fx-background-radius: 5;");

        Label sectionTitle = new Label("Sales by Payment Method");
        sectionTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        HBox contentBox = new HBox(20);
        contentBox.setAlignment(Pos.CENTER_LEFT);

        // Table
        paymentMethodTable = new TableView<>();
        paymentMethodTable.setItems(paymentMethodList);
        paymentMethodTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        paymentMethodTable.setPrefHeight(300);
        // paymentMethodTable.setPrefWidth(500); // Remove fixed width
        paymentMethodTable.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(paymentMethodTable, Priority.ALWAYS);

        // Payment Method column
        TableColumn<PaymentMethodRow, String> methodCol = new TableColumn<>("Payment Method");
        methodCol.setCellValueFactory(new PropertyValueFactory<>("paymentMethod"));
        methodCol.setPrefWidth(150);

        // Transaction Count column
        TableColumn<PaymentMethodRow, Integer> countCol = new TableColumn<>("Transactions");
        countCol.setCellValueFactory(new PropertyValueFactory<>("transactionCount"));
        countCol.setPrefWidth(100);

        // Total Amount column
        TableColumn<PaymentMethodRow, String> amountCol = new TableColumn<>("Total Amount");
        amountCol.setCellValueFactory(new PropertyValueFactory<>("totalAmount"));
        amountCol.setPrefWidth(120);

        // Percentage column
        TableColumn<PaymentMethodRow, String> percentCol = new TableColumn<>("Percentage");
        percentCol.setCellValueFactory(new PropertyValueFactory<>("percentage"));
        percentCol.setPrefWidth(100);

        @SuppressWarnings("unchecked")
        TableColumn<PaymentMethodRow, ?>[] pmColumns = new TableColumn[] { methodCol, countCol, amountCol, percentCol };
        paymentMethodTable.getColumns().addAll(pmColumns);

        // Chart
        paymentMethodChart = new PieChart();
        paymentMethodChart.setTitle("Payment Methods");
        paymentMethodChart.setLabelsVisible(true);
        paymentMethodChart.setLegendVisible(true);
        paymentMethodChart.setPrefHeight(300);
        paymentMethodChart.setAnimated(false); // Disable animation for immediate display

        contentBox.getChildren().addAll(paymentMethodTable, paymentMethodChart);
        HBox.setHgrow(paymentMethodChart, Priority.ALWAYS);

        section.getChildren().addAll(sectionTitle, contentBox);

        return section;
    }

    private VBox createTopProductsSection() {
        VBox section = new VBox(10);
        section.setPadding(new Insets(15));
        section.setStyle("-fx-background-color: white; -fx-background-radius: 5;");

        Label sectionTitle = new Label("Top Products");
        sectionTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        HBox contentBox = new HBox(20);

        topProductsTable = new TableView<>();
        topProductsTable.setItems(topProductsList);
        topProductsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        topProductsTable.setPrefHeight(300);
        topProductsTable.setMaxHeight(300);
        // topProductsTable.setPrefWidth(500);
        topProductsTable.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(topProductsTable, Priority.ALWAYS);

        // Product Name column
        TableColumn<TopProductRow, String> nameCol = new TableColumn<>("Product Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("productName"));
        nameCol.setPrefWidth(200);

        // SKU column
        TableColumn<TopProductRow, String> skuCol = new TableColumn<>("SKU");
        skuCol.setCellValueFactory(new PropertyValueFactory<>("sku"));
        skuCol.setPrefWidth(100);

        // Quantity Sold column
        TableColumn<TopProductRow, Integer> qtyCol = new TableColumn<>("Quantity Sold");
        qtyCol.setCellValueFactory(new PropertyValueFactory<>("quantitySold"));
        qtyCol.setPrefWidth(100);

        // Total Revenue column
        TableColumn<TopProductRow, String> revenueCol = new TableColumn<>("Total Revenue");
        revenueCol.setCellValueFactory(new PropertyValueFactory<>("totalRevenue"));
        revenueCol.setPrefWidth(100);

        @SuppressWarnings("unchecked")
        TableColumn<TopProductRow, ?>[] productColumns = new TableColumn[] { nameCol, skuCol, qtyCol, revenueCol };
        topProductsTable.getColumns().addAll(productColumns);

        // Chart
        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Revenue");

        topProductsChart = new BarChart<>(xAxis, yAxis);
        topProductsChart.setTitle("Top 5 Products by Revenue");
        topProductsChart.setLegendVisible(false);
        topProductsChart.setPrefHeight(300);
        topProductsChart.setAnimated(false); // Disable animation for immediate display

        contentBox.getChildren().addAll(topProductsTable, topProductsChart);
        HBox.setHgrow(topProductsChart, Priority.ALWAYS);

        section.getChildren().addAll(sectionTitle, contentBox);

        return section;
    }

    private void handleDateRangeChange() {
        String selected = dateRangeCombo.getValue();
        LocalDate today = LocalDate.now();

        switch (selected) {
            case "Today":
                currentStartDate = today;
                currentEndDate = today;
                startDatePicker.setDisable(true);
                endDatePicker.setDisable(true);
                break;
            case "Yesterday":
                currentStartDate = today.minusDays(1);
                currentEndDate = today.minusDays(1);
                startDatePicker.setDisable(true);
                endDatePicker.setDisable(true);
                break;
            case "This Week":
                currentStartDate = today.minusDays(today.getDayOfWeek().getValue() - 1);
                currentEndDate = today;
                startDatePicker.setDisable(true);
                endDatePicker.setDisable(true);
                break;
            case "This Month":
                currentStartDate = today.withDayOfMonth(1);
                currentEndDate = today;
                startDatePicker.setDisable(true);
                endDatePicker.setDisable(true);
                break;
            case "Custom":
                startDatePicker.setDisable(false);
                endDatePicker.setDisable(false);
                break;
        }

        if (!"Custom".equals(selected)) {
            startDatePicker.setValue(currentStartDate);
            endDatePicker.setValue(currentEndDate);
            loadReports();
        }
    }

    private void loadReports() {
        System.out.println("DEBUG: Loading reports for range: " + currentStartDate + " to " + currentEndDate);
        String selectedCashierId = getSelectedCashierId();
        new Thread(() -> {
            try {
                ReportService.DailySummary summary = reportService.getDailySummary(currentStartDate, currentEndDate, selectedCashierId);
                List<ReportService.EmployeeSales> employeeSales = reportService.getSalesByEmployee(currentStartDate,
                        currentEndDate, selectedCashierId);
                List<ReportService.PaymentMethodBreakdown> paymentMethods = reportService
                        .getPaymentMethodBreakdown(currentStartDate, currentEndDate, selectedCashierId);
                List<ReportService.TopProduct> topProducts = reportService.getTopProducts(currentStartDate,
                        currentEndDate, 20, selectedCashierId);

                Platform.runLater(() -> {
                    updateSummaryCards(summary);
                    updateEmployeeTable(employeeSales);
                    updatePaymentMethodTable(paymentMethods);
                    updateTopProductsTable(topProducts);
                });
            } catch (SQLException e) {
                logger.error("Error loading reports", e);
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Error");
                    alert.setHeaderText("Failed to load reports");
                    alert.setContentText("Error: " + e.getMessage());
                    DialogHelper.setAlertOwner(alert, null);
                    alert.showAndWait();
                });
            }
        }).start();
    }

    private void updateSummaryCards(ReportService.DailySummary summary) {
        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.US);

        grossSalesLabel.setText(currencyFormat.format(summary.grossSales));
        netSalesLabel.setText(currencyFormat.format(summary.netSales));
        transactionCountLabel.setText(String.valueOf(summary.transactionCount));
        cashSalesLabel.setText(currencyFormat.format(summary.cashSales));
        cardSalesLabel.setText(currencyFormat.format(summary.cardSales));
        averageTransactionLabel.setText(currencyFormat.format(summary.averageTransaction));
        totalExpensesLabel.setText(currencyFormat.format(summary.totalExpenses));
        totalGpiLabel.setText(currencyFormat.format(summary.totalGpi));
        totalEbtFeesLabel.setText(currencyFormat.format(summary.totalEbtFees));
        finalDepositLabel.setText(currencyFormat.format(summary.finalDeposit));
    }

    private void updateEmployeeTable(List<ReportService.EmployeeSales> employeeSales) {
        employeeSalesList.clear();
        for (ReportService.EmployeeSales emp : employeeSales) {
            employeeSalesList.add(new EmployeeSalesRow(emp));
        }
    }

    private void updatePaymentMethodTable(List<ReportService.PaymentMethodBreakdown> breakdown) {
        paymentMethodList.clear();
        paymentMethodChart.getData().clear();

        for (ReportService.PaymentMethodBreakdown pmb : breakdown) {
            paymentMethodList.add(new PaymentMethodRow(pmb));
            paymentMethodChart.getData().add(new PieChart.Data(pmb.paymentMethod, pmb.totalAmount.doubleValue()));
        }
    }

    private void updateTopProductsTable(List<ReportService.TopProduct> products) {
        topProductsList.clear();
        topProductsChart.getData().clear();

        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName("Revenue");

        int count = 0;
        for (ReportService.TopProduct product : products) {
            topProductsList.add(new TopProductRow(product));

            // Add top 5 to chart
            if (count < 5) {
                series.getData().add(new XYChart.Data<>(product.productName, product.totalRevenue));
                count++;
            }
        }

        topProductsChart.getData().add(series);
    }

    private void handleExport() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Reports");
        fileChooser.setInitialFileName(
                "sales_report_" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".csv");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));

        Stage stage = (Stage) getScene().getWindow();
        File file = fileChooser.showSaveDialog(stage);

        if (file != null) {
            String selectedCashierId = getSelectedCashierId();
            new Thread(() -> {
                try {
                    List<java.util.Map<String, Object>> sales = reportService.getSalesForExport(currentStartDate,
                            currentEndDate, selectedCashierId);
                    boolean success = ExportService.exportSalesToCSV(sales, file.getAbsolutePath());

                    Platform.runLater(() -> {
                        if (success) {
                            Alert alert = new Alert(Alert.AlertType.INFORMATION);
                            alert.setTitle("Export Successful");
                            alert.setHeaderText("Reports exported successfully");
                            alert.setContentText("File saved to: " + file.getAbsolutePath());
                            DialogHelper.setAlertOwner(alert, null);
                            alert.showAndWait();
                        } else {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setTitle("Export Failed");
                            alert.setHeaderText("Failed to export reports");
                            alert.setContentText("Please try again.");
                            DialogHelper.setAlertOwner(alert, null);
                            alert.showAndWait();
                        }
                    });
                } catch (Exception e) {
                    logger.error("Error exporting reports", e);
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("Export Failed");
                        alert.setHeaderText("Error exporting reports");
                        alert.setContentText("Error: " + e.getMessage());
                        DialogHelper.setAlertOwner(alert, null);
                        alert.showAndWait();
                    });
                }
            }).start();
        }
    }

    /**
     * Handle previewing End of Day report in a dialog
     */
    private void handlePreviewEndOfDay() {
        previewEodButton.setDisable(true);
        previewEodButton.setText("Loading...");

        new Thread(() -> {
            try {
                // Get current user name for the "Created by" field
                String createdBy = "System";
                try {
                    UserAuthService authService = UserAuthService.getInstance();
                    String userName = authService.getCurrentUserName();
                    if (userName != null && !userName.isEmpty()) {
                        createdBy = userName;
                    }
                } catch (Exception e) {
                    logger.debug("Could not get current user name for EOD report", e);
                }

                // Generate the End of Day receipt text for the selected date range
                String receiptText = eodReportService.generateReceiptTextForDateRange(currentStartDate, currentEndDate, createdBy);

                Platform.runLater(() -> {
                    showReportPreviewDialog(receiptText);
                    previewEodButton.setDisable(false);
                    previewEodButton.setText("Preview End of Day");
                });

            } catch (SQLException e) {
                logger.error("Error generating End of Day report", e);
                Platform.runLater(() -> {
                    previewEodButton.setDisable(false);
                    previewEodButton.setText("Preview End of Day");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Error");
                    alert.setHeaderText("Failed to generate End of Day report");
                    alert.setContentText("Database error: " + e.getMessage());
                    DialogHelper.setAlertOwner(alert, getScene() != null ? getScene().getWindow() : null);
                    alert.showAndWait();
                });
            } catch (Exception e) {
                logger.error("Unexpected error generating End of Day report", e);
                Platform.runLater(() -> {
                    previewEodButton.setDisable(false);
                    previewEodButton.setText("Preview End of Day");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Error");
                    alert.setHeaderText("Failed to generate End of Day report");
                    alert.setContentText("Error: " + e.getMessage());
                    DialogHelper.setAlertOwner(alert, getScene() != null ? getScene().getWindow() : null);
                    alert.showAndWait();
                });
            }
        }).start();
    }

    /**
     * Show report preview dialog with print option
     */
    private void showReportPreviewDialog(String receiptText) {
        ReceiptPrintHelper.showReceiptPreview(
                receiptText,
                getScene() != null ? getScene().getWindow() : null,
                "End of Day Report Preview",
                "Report for: " + (currentStartDate.equals(currentEndDate)
                        ? currentEndDate.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
                        : currentStartDate.format(DateTimeFormatter.ofPattern("MMM d")) + " - "
                                + currentEndDate.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))));
    }

    /**
     * Handle printing End of Day report to receipt printer
     */
    private void handlePrintEndOfDay() {
        new Thread(() -> {
            try {
                // Get current user name for the "Created by" field
                String createdBy = "System";
                try {
                    UserAuthService authService = UserAuthService.getInstance();
                    String userName = authService.getCurrentUserName();
                    if (userName != null && !userName.isEmpty()) {
                        createdBy = userName;
                    }
                } catch (Exception e) {
                    logger.debug("Could not get current user name for EOD report", e);
                }

                // Generate the End of Day receipt text for the selected date range
                String receiptText = eodReportService.generateReceiptTextForDateRange(currentStartDate, currentEndDate, createdBy);

                // Print the receipt using receipt print helper (includes PDF saving)
                ReceiptPrintHelper.printReceiptWithPDF(receiptText, null,
                        getScene() != null ? getScene().getWindow() : null);

            } catch (SQLException e) {
                logger.error("Error generating End of Day report", e);
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Error");
                    alert.setHeaderText("Failed to generate End of Day report");
                    alert.setContentText("Database error: " + e.getMessage());
                    DialogHelper.setAlertOwner(alert, getScene() != null ? getScene().getWindow() : null);
                    alert.showAndWait();
                });
            } catch (Exception e) {
                logger.error("Unexpected error printing End of Day report", e);
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Error");
                    alert.setHeaderText("Failed to print End of Day report");
                    alert.setContentText("Error: " + e.getMessage());
                    DialogHelper.setAlertOwner(alert, getScene() != null ? getScene().getWindow() : null);
                    alert.showAndWait();
                });
            }
        }).start();
    }

    // Table row classes
    public static class EmployeeSalesRow {
        private final String employeeName;
        private final int transactionCount;
        private final String totalSales;
        private final String cashSales;
        private final String cardSales;

        public EmployeeSalesRow(ReportService.EmployeeSales emp) {
            this.employeeName = emp.employeeName;
            this.transactionCount = emp.transactionCount;
            NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.US);
            this.totalSales = currencyFormat.format(emp.totalSales);
            this.cashSales = currencyFormat.format(emp.cashSales);
            this.cardSales = currencyFormat.format(emp.cardSales);
        }

        public String getEmployeeName() {
            return employeeName;
        }

        public int getTransactionCount() {
            return transactionCount;
        }

        public String getTotalSales() {
            return totalSales;
        }

        public String getCashSales() {
            return cashSales;
        }

        public String getCardSales() {
            return cardSales;
        }
    }

    public static class PaymentMethodRow {
        private final String paymentMethod;
        private final int transactionCount;
        private final String totalAmount;
        private final String percentage;

        public PaymentMethodRow(ReportService.PaymentMethodBreakdown pmb) {
            this.paymentMethod = pmb.paymentMethod;
            this.transactionCount = pmb.transactionCount;
            NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.US);
            this.totalAmount = currencyFormat.format(pmb.totalAmount);
            this.percentage = String.format("%.2f%%", pmb.percentage);
        }

        public String getPaymentMethod() {
            return paymentMethod;
        }

        public int getTransactionCount() {
            return transactionCount;
        }

        public String getTotalAmount() {
            return totalAmount;
        }

        public String getPercentage() {
            return percentage;
        }
    }

    public static class TopProductRow {
        private final String productName;
        private final String sku;
        private final int quantitySold;
        private final String totalRevenue;

        public TopProductRow(ReportService.TopProduct product) {
            this.productName = product.productName;
            this.sku = product.sku != null ? product.sku : "";
            this.quantitySold = product.quantitySold;
            NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.US);
            this.totalRevenue = currencyFormat.format(product.totalRevenue);
        }

        public String getProductName() {
            return productName;
        }

        public String getSku() {
            return sku;
        }

        public int getQuantitySold() {
            return quantitySold;
        }

        public String getTotalRevenue() {
            return totalRevenue;
        }
    }

    /**
     * Helper class for cashier combo box
     */
    private static class CashierItem {
        String id;
        String fullName;

        public CashierItem(String id, String fullName) {
            this.id = id;
            this.fullName = fullName;
        }

        @Override
        public String toString() {
            return fullName;
        }
    }

    private void populateCashierCombo() {
        if (cashierCombo == null) return;
        
        ObservableList<CashierItem> items = FXCollections.observableArrayList();
        items.add(new CashierItem(null, "All Cashiers"));
        
        try {
            List<com.pos.api.dto.PosUserLoginResponse.PosUserInfo> users = UserAuthService.getInstance().getAllPosUsers();
            for (com.pos.api.dto.PosUserLoginResponse.PosUserInfo user : users) {
                items.add(new CashierItem(user.id, user.fullName));
            }
        } catch (Exception e) {
            logger.error("Error populating cashier combo", e);
        }
        
        cashierCombo.setItems(items);
        cashierCombo.getSelectionModel().selectFirst();
    }
    
    private String getSelectedCashierId() {
        if (cashierCombo == null || cashierCombo.getValue() == null) {
            return null;
        }
        return cashierCombo.getValue().id;
    }
}
