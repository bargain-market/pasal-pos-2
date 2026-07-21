package com.pos.ui;

import com.pos.hardware.HardwareManager;
import com.pos.service.SaleHistoryService;
import com.pos.ui.dialogs.SaleDetailDialog;
import com.pos.util.DialogHelper;
import com.pos.util.ErrorHandler;
import com.pos.util.ExportService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sales Report View - High-density professional transaction report
 */
public class SalesReportView extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(SalesReportView.class);
    private static final int PAGE_SIZE = 50;

    private final SaleHistoryService saleHistoryService;
    private final Runnable onBackToSales;

    // UI Components - Filters
    private ComboBox<String> dateRangeCombo;
    private DatePicker startDatePicker;
    private DatePicker endDatePicker;
    private ComboBox<String> cashierCombo;
    private ComboBox<String> paymentMethodCombo;
    private ComboBox<SaleHistoryService.TransactionType> typeCombo;

    // UI Components - Summary Ribbon
    private Label grossSalesLabel;
    private Label netSalesLabel;
    private Label taxLabel;
    private Label discountLabel;
    private Label countLabel;
    private Label avgLabel;

    // Table
    private TableView<SaleHistoryService.SaleRecord> reportTable;
    private ObservableList<SaleHistoryService.SaleRecord> reportData;
    private Button prevPageBtn;
    private Button nextPageBtn;
    private Label pageInfoLabel;
    private int currentPage = 0;
    private int totalPages = 1;
    private int totalRecords = 0;
    private boolean isLoading = false;

    // Formatters
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.US);

    public SalesReportView(HardwareManager hardwareManager, Runnable onBackToSales) {
        this.onBackToSales = onBackToSales;
        this.saleHistoryService = SaleHistoryService.getInstance();
        this.reportData = FXCollections.observableArrayList();

        initializeUI();
        loadData();
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #f8fafc;");

        // Top Section: Title and Filters
        VBox topContainer = new VBox(0);
        topContainer.setStyle("-fx-background-color: white; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 5, 0, 0, 1);");
        
        topContainer.getChildren().add(createHeader());
        topContainer.getChildren().add(createFilterBar());
        topContainer.getChildren().add(createSummaryRibbon());
        
        setTop(topContainer);

        // Center Section: Table
        setCenter(createTableArea());

        // Bottom Section: Actions
        setBottom(createActionBar());
    }

    private HBox createHeader() {
        HBox header = new HBox(20);
        header.setPadding(new Insets(15, 20, 10, 20));
        header.setAlignment(Pos.CENTER_LEFT);

        Button backBtn = new Button("\u2190 Back");
        backBtn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-font-weight: bold; -fx-padding: 8 16; -fx-background-radius: 6; -fx-cursor: hand;");
        backBtn.setOnAction(e -> {
            if (onBackToSales != null) onBackToSales.run();
        });

        Label title = new Label("Sales Detailed Report");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header.getChildren().addAll(backBtn, title, spacer);
        return header;
    }

    private HBox createFilterBar() {
        HBox filterBar = new HBox(15);
        filterBar.setPadding(new Insets(10, 20, 15, 20));
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setStyle("-fx-border-color: #f1f5f9; -fx-border-width: 0 0 1 0;");

        // Date Range
        VBox dateBox = new VBox(5);
        Label dateLabel = new Label("Date Range");
        dateLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b; -fx-font-weight: bold;");
        dateRangeCombo = new ComboBox<>(FXCollections.observableArrayList("Today", "Yesterday", "This Week", "This Month", "Custom"));
        dateRangeCombo.setValue("Today");
        dateRangeCombo.setPrefWidth(120);
        dateRangeCombo.setOnAction(e -> handleDateRangeChange());
        dateBox.getChildren().addAll(dateLabel, dateRangeCombo);

        // Custom Dates (initially hidden/disabled)
        startDatePicker = new DatePicker(LocalDate.now());
        startDatePicker.setPrefWidth(130);
        startDatePicker.setDisable(true);
        
        endDatePicker = new DatePicker(LocalDate.now());
        endDatePicker.setPrefWidth(130);
        endDatePicker.setDisable(true);

        // Cashier
        VBox cashierBox = new VBox(5);
        Label cLabel = new Label("Cashier");
        cLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b; -fx-font-weight: bold;");
        cashierCombo = new ComboBox<>();
        cashierCombo.setPromptText("All Cashiers");
        cashierCombo.setPrefWidth(140);
        loadCashiers();
        cashierBox.getChildren().addAll(cLabel, cashierCombo);

        // Payment Method
        VBox payBox = new VBox(5);
        Label pLabel = new Label("Payment");
        pLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b; -fx-font-weight: bold;");
        paymentMethodCombo = new ComboBox<>(FXCollections.observableArrayList("All", "CASH", "CARD", "SPLIT"));
        paymentMethodCombo.setValue("All");
        paymentMethodCombo.setPrefWidth(100);
        payBox.getChildren().addAll(pLabel, paymentMethodCombo);

        // Type
        VBox typeBox = new VBox(5);
        Label tLabel = new Label("Type");
        tLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b; -fx-font-weight: bold;");
        typeCombo = new ComboBox<>(FXCollections.observableArrayList(null, SaleHistoryService.TransactionType.SALE, SaleHistoryService.TransactionType.REFUND));
        typeCombo.setPromptText("All Types");
        typeCombo.setPrefWidth(100);
        typeBox.getChildren().addAll(tLabel, typeCombo);

        Button runBtn = new Button("Run Report");
        runBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 20; -fx-background-radius: 6; -fx-cursor: hand;");
        runBtn.setOnAction(e -> loadFirstPage());
        HBox.setMargin(runBtn, new Insets(15, 0, 0, 0));

        filterBar.getChildren().addAll(dateBox, startDatePicker, endDatePicker, cashierBox, payBox, typeBox, runBtn);
        return filterBar;
    }

    private HBox createSummaryRibbon() {
        HBox ribbon = new HBox(0);
        ribbon.setPadding(new Insets(15, 20, 15, 20));
        ribbon.setAlignment(Pos.CENTER);
        ribbon.setSpacing(40);
        ribbon.setStyle("-fx-background-color: #f8fafc;");

        grossSalesLabel = createRibbonItem("GROSS SALES", "$0.00", "#1e293b");
        netSalesLabel = createRibbonItem("NET SALES", "$0.00", "#2563eb");
        taxLabel = createRibbonItem("TOTAL TAX", "$0.00", "#64748b");
        discountLabel = createRibbonItem("DISCOUNTS", "$0.00", "#dc2626");
        countLabel = createRibbonItem("TRANSACTIONS", "0", "#1e293b");
        avgLabel = createRibbonItem("AVG. SALE", "$0.00", "#1e293b");

        ribbon.getChildren().addAll(
            wrapRibbonItem(grossSalesLabel, "GROSS SALES"),
            createSeparator(),
            wrapRibbonItem(netSalesLabel, "NET SALES"),
            createSeparator(),
            wrapRibbonItem(taxLabel, "TOTAL TAX"),
            createSeparator(),
            wrapRibbonItem(discountLabel, "DISCOUNTS"),
            createSeparator(),
            wrapRibbonItem(countLabel, "TRANSACTIONS"),
            createSeparator(),
            wrapRibbonItem(avgLabel, "AVG. SALE")
        );

        return ribbon;
    }

    private Label createRibbonItem(String title, String value, String color) {
        Label label = new Label(value);
        label.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: " + color + ";");
        return label;
    }

    private VBox wrapRibbonItem(Label valueLabel, String title) {
        VBox box = new VBox(2);
        box.setAlignment(Pos.CENTER);
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #94a3b8; -fx-letter-spacing: 0.1em;");
        box.getChildren().addAll(titleLabel, valueLabel);
        return box;
    }

    private Separator createSeparator() {
        Separator s = new Separator();
        s.setOrientation(javafx.geometry.Orientation.VERTICAL);
        s.setPrefHeight(30);
        s.setStyle("-fx-opacity: 0.5;");
        return s;
    }

    private VBox createTableArea() {
        VBox area = new VBox(0);
        area.setPadding(new Insets(20));
        VBox.setVgrow(area, Priority.ALWAYS);

        reportTable = new TableView<>();
        reportTable.setItems(reportData);
        reportTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        reportTable.setStyle("-fx-background-color: white; -fx-background-radius: 8; -fx-border-color: #e2e8f0; -fx-border-radius: 8;");
        VBox.setVgrow(reportTable, Priority.ALWAYS);

        // Columns
        TableColumn<SaleHistoryService.SaleRecord, String> idCol = new TableColumn<>("TRANSACTION ID");
        idCol.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().saleId));
        idCol.setPrefWidth(180);

        TableColumn<SaleHistoryService.SaleRecord, String> dateCol = new TableColumn<>("DATE / TIME");
        dateCol.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().timestamp)); // Need better formatting?
        dateCol.setPrefWidth(160);

        TableColumn<SaleHistoryService.SaleRecord, String> cashierCol = new TableColumn<>("CASHIER");
        cashierCol.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().cashierName));
        cashierCol.setPrefWidth(120);

        TableColumn<SaleHistoryService.SaleRecord, String> methodCol = new TableColumn<>("METHOD");
        methodCol.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().paymentMethod));
        methodCol.setPrefWidth(100);

        TableColumn<SaleHistoryService.SaleRecord, String> subCol = new TableColumn<>("SUBTOTAL");
        subCol.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(currencyFormat.format(p.getValue().subtotal)));
        subCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<SaleHistoryService.SaleRecord, String> taxCol = new TableColumn<>("TAX");
        taxCol.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(currencyFormat.format(p.getValue().tax)));
        taxCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<SaleHistoryService.SaleRecord, String> totalCol = new TableColumn<>("TOTAL");
        totalCol.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(currencyFormat.format(p.getValue().total)));
        totalCol.setStyle("-fx-alignment: CENTER-RIGHT; -fx-font-weight: bold;");

        TableColumn<SaleHistoryService.SaleRecord, String> statusCol = new TableColumn<>("STATUS");
        statusCol.setCellValueFactory(p -> {
            String status = p.getValue().voided ? "VOIDED" : (p.getValue().isRefund() ? "REFUNDED" : "COMPLETED");
            return new javafx.beans.property.SimpleStringProperty(status);
        });
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label l = new Label(item);
                    String color = "#10b981"; // green
                    if ("VOIDED".equals(item)) color = "#ef4444"; // red
                    if ("REFUNDED".equals(item)) color = "#f59e0b"; // amber
                    l.setStyle("-fx-background-color: " + color + "22; -fx-text-fill: " + color + "; -fx-font-size: 10px; -fx-font-weight: bold; -fx-padding: 2 8; -fx-background-radius: 10;");
                    setGraphic(l);
                    setAlignment(Pos.CENTER);
                }
            }
        });

        reportTable.getColumns().add(idCol);
        reportTable.getColumns().add(dateCol);
        reportTable.getColumns().add(cashierCol);
        reportTable.getColumns().add(methodCol);
        reportTable.getColumns().add(subCol);
        reportTable.getColumns().add(taxCol);
        reportTable.getColumns().add(totalCol);
        reportTable.getColumns().add(statusCol);

        // Double click to view details
        reportTable.setRowFactory(tv -> {
            TableRow<SaleHistoryService.SaleRecord> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && (!row.isEmpty())) {
                    showDetails(row.getItem());
                }
            });
            return row;
        });

        area.getChildren().add(reportTable);
        return area;
    }

    private HBox createActionBar() {
        HBox bar = new HBox(15);
        bar.setPadding(new Insets(10, 20, 20, 20));
        bar.setAlignment(Pos.CENTER_RIGHT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button exportCsv = new Button("Export CSV");
        exportCsv.setStyle("-fx-background-color: white; -fx-border-color: #e2e8f0; -fx-text-fill: #475569; -fx-font-weight: bold; -fx-padding: 8 16; -fx-background-radius: 6; -fx-cursor: hand;");
        exportCsv.setOnAction(e -> handleExportCSV());

        Button printBtn = new Button("Print Report");
        printBtn.setStyle("-fx-background-color: white; -fx-border-color: #e2e8f0; -fx-text-fill: #475569; -fx-font-weight: bold; -fx-padding: 8 16; -fx-background-radius: 6; -fx-cursor: hand;");

        bar.getChildren().addAll(createPaginationControls(), spacer, exportCsv, printBtn);
        return bar;
    }

    private HBox createPaginationControls() {
        HBox controls = new HBox(12);
        controls.setAlignment(Pos.CENTER_LEFT);

        prevPageBtn = new Button("\u2190 Previous");
        prevPageBtn.setStyle("-fx-background-color: white; -fx-border-color: #cbd5e1; -fx-text-fill: #334155; -fx-font-weight: bold; -fx-padding: 8 14; -fx-background-radius: 6; -fx-cursor: hand;");
        prevPageBtn.setOnAction(e -> loadPreviousPage());
        prevPageBtn.setDisable(true);

        pageInfoLabel = new Label("No sales found");
        pageInfoLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b; -fx-font-weight: bold;");

        nextPageBtn = new Button("Next \u2192");
        nextPageBtn.setStyle("-fx-background-color: white; -fx-border-color: #cbd5e1; -fx-text-fill: #334155; -fx-font-weight: bold; -fx-padding: 8 14; -fx-background-radius: 6; -fx-cursor: hand;");
        nextPageBtn.setOnAction(e -> loadNextPage());
        nextPageBtn.setDisable(true);

        controls.getChildren().addAll(prevPageBtn, pageInfoLabel, nextPageBtn);
        return controls;
    }

    private void handleDateRangeChange() {
        String selected = dateRangeCombo.getValue();
        boolean custom = "Custom".equals(selected);
        startDatePicker.setDisable(!custom);
        endDatePicker.setDisable(!custom);

        if (!custom) {
            LocalDate today = LocalDate.now();
            switch (selected) {
                case "Today":
                    startDatePicker.setValue(today);
                    endDatePicker.setValue(today);
                    break;
                case "Yesterday":
                    startDatePicker.setValue(today.minusDays(1));
                    endDatePicker.setValue(today.minusDays(1));
                    break;
                case "This Week":
                    startDatePicker.setValue(today.minusDays(today.getDayOfWeek().getValue() - 1));
                    endDatePicker.setValue(today);
                    break;
                case "This Month":
                    startDatePicker.setValue(today.withDayOfMonth(1));
                    endDatePicker.setValue(today);
                    break;
            }
            loadFirstPage();
        }
    }

    private void loadCashiers() {
        new Thread(() -> {
            try {
                List<String> cashiers = saleHistoryService.getCashierNames();
                Platform.runLater(() -> {
                    cashierCombo.getItems().clear();
                    cashierCombo.getItems().addAll(cashiers);
                });
            } catch (SQLException e) {
                logger.error("Error loading cashiers", e);
            }
        }).start();
    }

    private void loadFirstPage() {
        currentPage = 0;
        loadData();
    }

    private void loadPreviousPage() {
        if (currentPage > 0 && !isLoading) {
            currentPage--;
            loadData();
        }
    }

    private void loadNextPage() {
        if (currentPage < totalPages - 1 && !isLoading) {
            currentPage++;
            loadData();
        }
    }

    private void updatePaginationControls() {
        if (prevPageBtn == null || nextPageBtn == null || pageInfoLabel == null) {
            return;
        }

        prevPageBtn.setDisable(currentPage <= 0 || isLoading || totalRecords == 0);
        nextPageBtn.setDisable(currentPage >= totalPages - 1 || isLoading || totalRecords == 0);

        if (totalRecords == 0) {
            pageInfoLabel.setText(isLoading ? "Loading sales..." : "No sales found");
            return;
        }

        int startItem = currentPage * PAGE_SIZE + 1;
        int endItem = Math.min((currentPage + 1) * PAGE_SIZE, totalRecords);
        pageInfoLabel.setText(String.format("Page %d of %d (%d-%d of %d sales)",
                currentPage + 1, totalPages, startItem, endItem, totalRecords));
    }

    private void setLoading(boolean loading) {
        isLoading = loading;
        Platform.runLater(() -> {
            reportTable.setDisable(loading);
            reportTable.setOpacity(loading ? 0.7 : 1.0);
            updatePaginationControls();
        });
    }

    private SaleHistoryService.SearchCriteria buildCriteria() {
        LocalDate start = startDatePicker.getValue();
        LocalDate end = endDatePicker.getValue();
        String cashier = cashierCombo.getValue();
        String method = paymentMethodCombo.getValue();
        SaleHistoryService.TransactionType type = typeCombo.getValue();

        SaleHistoryService.SearchCriteria criteria = new SaleHistoryService.SearchCriteria();
        criteria.startDate = start;
        criteria.endDate = end;
        criteria.cashierName = (cashier != null && !cashier.isEmpty()) ? cashier : null;
        criteria.paymentMethod = ("All".equals(method) || method == null || method.isEmpty()) ? null : method;
        criteria.transactionType = type;
        criteria.limit = PAGE_SIZE;
        criteria.offset = currentPage * PAGE_SIZE;
        return criteria;
    }

    private void loadData() {
        SaleHistoryService.SearchCriteria criteria = buildCriteria();
        setLoading(true);
        new Thread(() -> {
            try {
                List<SaleHistoryService.SaleRecord> pageRecords = saleHistoryService.searchSales(criteria);
                int totalCount = saleHistoryService.countSales(criteria);
                SaleHistoryService.SalesSummary summary = saleHistoryService.getSalesSummary(criteria);

                int pageCount = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / PAGE_SIZE);
                if (totalCount > 0 && currentPage >= pageCount) {
                    currentPage = pageCount - 1;
                    criteria.offset = currentPage * PAGE_SIZE;
                    pageRecords = saleHistoryService.searchSales(criteria);
                }

                final List<SaleHistoryService.SaleRecord> finalRecords = pageRecords;
                final int finalTotalCount = totalCount;
                final int finalPageCount = pageCount;
                final SaleHistoryService.SalesSummary finalSummary = summary;

                Platform.runLater(() -> {
                    totalRecords = finalTotalCount;
                    totalPages = finalPageCount;
                    reportData.clear();
                    reportData.addAll(finalRecords);

                    grossSalesLabel.setText(currencyFormat.format(finalSummary.grossSales));
                    netSalesLabel.setText(currencyFormat.format(finalSummary.netSales));
                    taxLabel.setText(currencyFormat.format(finalSummary.totalTax));
                    discountLabel.setText(currencyFormat.format(finalSummary.totalDiscount));
                    countLabel.setText(String.valueOf(finalSummary.transactionCount));
                    avgLabel.setText(currencyFormat.format(finalSummary.getAverageSale()));
                    updatePaginationControls();
                    setLoading(false);
                });

            } catch (SQLException e) {
                logger.error("Error loading report data", e);
                Platform.runLater(() -> {
                    setLoading(false);
                    DialogHelper.showError("Report Error", "Failed to load sales data: " + e.getMessage());
                });
            }
        }).start();
    }

    private void showDetails(SaleHistoryService.SaleRecord sale) {
        SaleDetailDialog dialog = new SaleDetailDialog(sale);
        dialog.showAndWait();
    }

    private void handleExportCSV() {
        if (reportData.isEmpty()) {
            ErrorHandler.showWarning("There is no data to export.", null, false);
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Sales Report");
        fileChooser.setInitialFileName("sales_report_" + LocalDate.now() + ".csv");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));

        File file = fileChooser.showSaveDialog(getScene().getWindow());
        if (file != null) {
            new Thread(() -> {
                try {
                    // Prepare data for export
                    List<Map<String, Object>> exportList = new ArrayList<>();
                    for (SaleHistoryService.SaleRecord r : reportData) {
                        Map<String, Object> map = new java.util.HashMap<>();
                        map.put("Sale ID", r.saleId);
                        map.put("Date", r.timestamp);
                        map.put("Cashier", r.cashierName);
                        map.put("Method", r.paymentMethod);
                        map.put("Subtotal", r.subtotal);
                        map.put("Tax", r.tax);
                        map.put("Discount", r.discount);
                        map.put("Total", r.total);
                        map.put("Status", r.voided ? "VOIDED" : (r.isRefund() ? "REFUNDED" : "COMPLETED"));
                        exportList.add(map);
                    }
                    
                    boolean success = ExportService.exportSalesToCSV(exportList, file.getAbsolutePath());
                    Platform.runLater(() -> {
                        if (success) {
                            ErrorHandler.showInfo("Report exported successfully to:\n" + file.getAbsolutePath(), null, false);
                        } else {
                            DialogHelper.showError("Export Failed", "Failed to write CSV file.");
                        }
                    });
                } catch (Exception e) {
                    logger.error("Export error", e);
                    Platform.runLater(() -> DialogHelper.showError("Export Error", "An error occurred during export: " + e.getMessage()));
                }
            }).start();
        }
    }
}
