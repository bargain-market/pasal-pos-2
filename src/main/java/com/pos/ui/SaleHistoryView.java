package com.pos.ui;

import com.pos.api.dto.SaleSubmission;
import com.pos.hardware.HardwareManager;
import com.pos.model.Refund;
import com.pos.service.RefundService;
import com.pos.service.RoleBasedAccessService;
import com.pos.service.SaleHistoryService;
import com.pos.service.VoidService;
import com.pos.ui.dialogs.RefundDialog;
import com.pos.ui.dialogs.SaleDetailDialog;
import com.pos.ui.dialogs.VoidDialog;
import com.pos.util.DialogHelper;
import com.pos.util.ReceiptPrintHelper;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Sale History & Lookup View - View past transactions and reprint receipts
 */
public class SaleHistoryView extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(SaleHistoryView.class);

    private SaleHistoryService saleHistoryService;
    private HardwareManager hardwareManager;
    private RoleBasedAccessService rbacService;

    // UI Components
    private TextField saleIdField;
    private DatePicker startDatePicker;
    private DatePicker endDatePicker;
    private ComboBox<String> cashierCombo;
    private Button searchButton;
    private Button clearButton;

    // Table
    private TableView<SaleHistoryService.SaleRecord> salesTable;
    private ObservableList<SaleHistoryService.SaleRecord> salesList;

    // Number formatter
    private NumberFormat currencyFormatter;
    private Runnable onBackToSales;
    private java.util.function.Consumer<SaleHistoryService.SaleRecord> onNavigateToRefund;
    private Button refreshButton;
    private Button viewDetailsButton;
    private Button reprintButton;
    private Button refundButton;
    private Button voidButton;

    public void setOnNavigateToRefund(java.util.function.Consumer<SaleHistoryService.SaleRecord> onNavigateToRefund) {
        this.onNavigateToRefund = onNavigateToRefund;
    }

    public SaleHistoryView(HardwareManager hardwareManager) {
        this(hardwareManager, null);
    }

    public SaleHistoryView(HardwareManager hardwareManager, Runnable onBackToSales) {
        this.hardwareManager = hardwareManager;
        this.saleHistoryService = SaleHistoryService.getInstance();
        this.rbacService = RoleBasedAccessService.getInstance();
        this.salesList = FXCollections.observableArrayList();
        this.currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US);
        this.onBackToSales = onBackToSales;

        initializeUI();
        loadSales();
    }

    /**
     * Create a styled icon button with text label for responsive layout
     */
    private Button createIconButton(String icon, String text, String color) {
        VBox content = new VBox(4);
        content.setAlignment(Pos.CENTER);
        content.setFillWidth(true);

        Label iconLabel = new Label(icon);
        iconLabel.setStyle("-fx-font-size: 20px; -fx-text-fill: white;");

        Label textLabel = new Label(text);
        textLabel.setWrapText(true);
        textLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        textLabel.setAlignment(Pos.CENTER);
        textLabel.setMaxWidth(72);
        textLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: white;");

        content.getChildren().addAll(iconLabel, textLabel);

        Button button = new Button();
        button.setGraphic(content);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setMinWidth(92);
        button.setMinHeight(82);
        button.setPrefWidth(106);
        button.setPrefHeight(82);
        button.setFocusTraversable(false);
        applyActionButtonStyle(button, color, false, false);

        button.hoverProperty().addListener((obs, oldVal, isHovered) ->
                applyActionButtonStyle(button, color, isHovered, button.isDisabled()));
        button.disabledProperty().addListener((obs, oldVal, isDisabled) ->
                applyActionButtonStyle(button, color, button.isHover(), isDisabled));

        return button;
    }

    private void applyActionButtonStyle(Button button, String color, boolean hovered, boolean disabled) {
        String backgroundColor = disabled
                ? "#cbd5e1"
                : hovered ? "derive(" + color + ", -10%)" : color;
        String shadow = disabled
                ? "-fx-effect: none;"
                : hovered
                        ? "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 6, 0, 0, 2);"
                        : "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 4, 0, 0, 1);";

        button.setStyle(
                "-fx-background-color: " + backgroundColor + ";" +
                        "-fx-text-fill: white;" +
                        "-fx-background-radius: 10;" +
                        "-fx-padding: 10 12;" +
                        "-fx-cursor: " + (disabled ? "default" : "hand") + ";" +
                        "-fx-opacity: " + (disabled ? "0.75" : "1.0") + ";" +
                        shadow);
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #f5f7fa;");

        // Top section - Title and filters
        VBox topSection = createTopSection();
        setTop(topSection);

        // Center - Sales table
        VBox centerSection = createCenterSection();
        setCenter(centerSection);
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

        // Title
        Label titleLabel = new Label("Sale History & Lookup");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        titleRow.getChildren().add(titleLabel);

        // Filters section - organized into multiple rows for better responsiveness
        VBox controlsBox = new VBox(15);
        controlsBox.setPadding(new Insets(0, 0, 5, 0));

        // Row 1: ID and Date Range
        HBox row1 = new HBox(15);
        row1.setAlignment(Pos.CENTER_LEFT);

        Label saleIdLabel = new Label("Sale:");
        saleIdField = new TextField();
        saleIdField.setPromptText("Enter sale ID...");
        saleIdField.setPrefWidth(160);

        Label startLabel = new Label("Start:");
        startDatePicker = new DatePicker();
        startDatePicker.setPrefWidth(140);

        Label endLabel = new Label("End:");
        endDatePicker = new DatePicker();
        endDatePicker.setPrefWidth(140);

        row1.getChildren().addAll(saleIdLabel, saleIdField, startLabel, startDatePicker, endLabel, endDatePicker);

        // Row 2: Cashier and Actions
        HBox row2 = new HBox(15);
        row2.setAlignment(Pos.CENTER_LEFT);

        Label cashierLabel = new Label("Cashier:");
        cashierCombo = new ComboBox<>();
        cashierCombo.setPromptText("All Cashiers");
        cashierCombo.setPrefWidth(160);
        cashierCombo.setEditable(true);
        loadCashiers();

        searchButton = new Button("Search");
        searchButton.setMinWidth(Region.USE_PREF_SIZE);
        searchButton.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-weight: bold;");
        searchButton.setOnAction(e -> searchSales());

        clearButton = new Button("Clear");
        clearButton.setMinWidth(Region.USE_PREF_SIZE);
        clearButton.setStyle("-fx-background-color: #757575; -fx-text-fill: white;");
        clearButton.setOnAction(e -> clearFilters());

        row2.getChildren().addAll(cashierLabel, cashierCombo, searchButton, clearButton);

        controlsBox.getChildren().addAll(row1, row2);
        topSection.getChildren().addAll(titleRow, controlsBox);

        return topSection;
    }

    private VBox createCenterSection() {
        VBox centerSection = new VBox(10);
        centerSection.setPadding(new Insets(20));
        // Make the center section fill available space
        VBox.setVgrow(centerSection, Priority.ALWAYS);

        // Table
        salesTable = new TableView<>();
        salesTable.setItems(salesList);
        salesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        salesTable.setStyle("-fx-background-color: white;");
        salesTable.setMaxHeight(Double.MAX_VALUE);
        salesTable.setMaxWidth(Double.MAX_VALUE);
        // Make table grow to fill available vertical space
        VBox.setVgrow(salesTable, Priority.ALWAYS);

        // Columns
        TableColumn<SaleHistoryService.SaleRecord, String> saleIdCol = new TableColumn<>("Sale ID");
        saleIdCol.setCellValueFactory(param -> {
            SaleHistoryService.SaleRecord sale = param.getValue();
            return sale != null ? new javafx.beans.property.SimpleStringProperty(sale.saleId) : null;
        });
        saleIdCol.setPrefWidth(200);

        TableColumn<SaleHistoryService.SaleRecord, LocalDate> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(param -> {
            SaleHistoryService.SaleRecord sale = param.getValue();
            return sale != null && sale.saleDate != null
                    ? new javafx.beans.property.SimpleObjectProperty<>(sale.saleDate)
                    : null;
        });
        dateCol.setCellFactory(col -> new TableCell<SaleHistoryService.SaleRecord, LocalDate>() {
            @Override
            protected void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.format(DateTimeFormatter.ofPattern("MM/dd/yyyy")));
                }
            }
        });
        dateCol.setPrefWidth(120);

        TableColumn<SaleHistoryService.SaleRecord, String> cashierCol = new TableColumn<>("Cashier");
        cashierCol.setCellValueFactory(param -> {
            SaleHistoryService.SaleRecord sale = param.getValue();
            return sale != null ? new javafx.beans.property.SimpleStringProperty(sale.cashierName) : null;
        });
        cashierCol.setPrefWidth(150);

        TableColumn<SaleHistoryService.SaleRecord, String> paymentCol = new TableColumn<>("Payment");
        paymentCol.setCellValueFactory(param -> {
            SaleHistoryService.SaleRecord sale = param.getValue();
            return sale != null ? new javafx.beans.property.SimpleStringProperty(sale.paymentMethod) : null;
        });
        paymentCol.setPrefWidth(100);

        TableColumn<SaleHistoryService.SaleRecord, BigDecimal> totalCol = new TableColumn<>("Total");
        totalCol.setCellValueFactory(param -> {
            SaleHistoryService.SaleRecord sale = param.getValue();
            return sale != null && sale.total != null ? new javafx.beans.property.SimpleObjectProperty<>(sale.total)
                    : null;
        });
        totalCol.setCellFactory(col -> new TableCell<SaleHistoryService.SaleRecord, BigDecimal>() {
            @Override
            protected void updateItem(BigDecimal item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(currencyFormatter.format(item));
                }
            }
        });
        totalCol.setPrefWidth(120);

        TableColumn<SaleHistoryService.SaleRecord, Boolean> syncedCol = new TableColumn<>("Synced");
        syncedCol.setCellValueFactory(param -> {
            SaleHistoryService.SaleRecord sale = param.getValue();
            return sale != null ? new javafx.beans.property.SimpleBooleanProperty(sale.synced) : null;
        });
        syncedCol.setCellFactory(col -> new TableCell<SaleHistoryService.SaleRecord, Boolean>() {
            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item ? "Yes" : "No");
                    setStyle(item ? "-fx-text-fill: #4CAF50;" : "-fx-text-fill: #f44336;");
                }
            }
        });
        syncedCol.setPrefWidth(80);

        TableColumn<SaleHistoryService.SaleRecord, Boolean> voidedCol = new TableColumn<>("Status");
        voidedCol.setCellValueFactory(param -> {
            SaleHistoryService.SaleRecord sale = param.getValue();
            return sale != null ? new javafx.beans.property.SimpleBooleanProperty(sale.voided) : null;
        });
        voidedCol.setCellFactory(col -> new TableCell<SaleHistoryService.SaleRecord, Boolean>() {
            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    if (item) {
                        setText("VOIDED");
                        setStyle("-fx-text-fill: #f44336; -fx-font-weight: bold;");
                    } else {
                        setText("Active");
                        setStyle("-fx-text-fill: #4CAF50;");
                    }
                }
            }
        });
        voidedCol.setPrefWidth(100);

        salesTable.getColumns().add(saleIdCol);
        salesTable.getColumns().add(dateCol);
        salesTable.getColumns().add(cashierCol);
        salesTable.getColumns().add(paymentCol);
        salesTable.getColumns().add(totalCol);
        salesTable.getColumns().add(syncedCol);
        salesTable.getColumns().add(voidedCol);

        // Double-click to view details
        salesTable.setRowFactory(tv -> {
            TableRow<SaleHistoryService.SaleRecord> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    SaleHistoryService.SaleRecord sale = row.getItem();
                    showSaleDetails(sale);
                }
            });
            return row;
        });

        // Action buttons - FlowPane for responsive layout
        FlowPane buttonBox = new FlowPane();
        buttonBox.setAlignment(Pos.CENTER);
        buttonBox.setPadding(new Insets(15, 0, 10, 0));
        buttonBox.setHgap(10);
        buttonBox.setVgap(10);

        refreshButton = createIconButton("\u21BB", "Refresh", "#607D8B");
        refreshButton.setOnAction(e -> loadSales());

        viewDetailsButton = createIconButton("\u2315", "Details", "#2196F3");
        viewDetailsButton.setOnAction(e -> {
            SaleHistoryService.SaleRecord selected = salesTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showSaleDetails(selected);
            } else {
                showAlert("No Selection", "Please select a sale to view details.");
            }
        });

        reprintButton = createIconButton("\u2399", "Reprint", "#4CAF50");
        reprintButton.setOnAction(e -> {
            SaleHistoryService.SaleRecord selected = salesTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                reprintReceipt(selected);
            } else {
                showAlert("No Selection", "Please select a sale to reprint receipt.");
            }
        });

        // Refund button - Manager+ only
        refundButton = null;
        if (rbacService.hasPermission(RoleBasedAccessService.PERMISSION_PROCESS_REFUND)) {
            refundButton = createIconButton("\u21A9", "Refund", "#f44336");
            refundButton.setOnAction(e -> {
                SaleHistoryService.SaleRecord selected = salesTable.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    processRefund(selected);
                } else {
                    showAlert("No Selection", "Please select a sale to process refund.");
                }
            });
        }

        // Void button - Manager+ only
        voidButton = null;
        if (rbacService.hasPermission(RoleBasedAccessService.PERMISSION_VOID_TRANSACTION)) {
            voidButton = createIconButton("\u2298", "Void", "#ff9800");
            voidButton.setOnAction(e -> {
                SaleHistoryService.SaleRecord selected = salesTable.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    processVoid(selected);
                } else {
                    showAlert("No Selection", "Please select a sale to void.");
                }
            });
        }

        // Add buttons conditionally
        buttonBox.getChildren().addAll(refreshButton, viewDetailsButton, reprintButton);
        if (refundButton != null) {
            buttonBox.getChildren().add(refundButton);
        }
        if (voidButton != null) {
            buttonBox.getChildren().add(voidButton);
        }

        salesTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> updateActionButtons());
        updateActionButtons();

        centerSection.getChildren().addAll(salesTable, buttonBox);

        return centerSection;
    }

    private void updateActionButtons() {
        SaleHistoryService.SaleRecord selected = salesTable != null
                ? salesTable.getSelectionModel().getSelectedItem()
                : null;

        boolean hasSelection = selected != null;
        boolean canRefund = hasSelection && !selected.voided && !selected.isRefund();
        boolean canVoid = hasSelection && !selected.voided && !selected.isRefund();

        if (viewDetailsButton != null) {
            viewDetailsButton.setDisable(!hasSelection);
        }
        if (reprintButton != null) {
            reprintButton.setDisable(!hasSelection);
        }
        if (refundButton != null) {
            refundButton.setDisable(!canRefund);
        }
        if (voidButton != null) {
            voidButton.setDisable(!canVoid);
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

    private void loadSales() {
        new Thread(() -> {
            try {
                SaleHistoryService.SearchCriteria criteria = new SaleHistoryService.SearchCriteria();
                criteria.limit = 100; // Load last 100 sales by default

                List<SaleHistoryService.SaleRecord> sales = saleHistoryService.searchSales(criteria);

                Platform.runLater(() -> {
                    salesList.clear();
                    salesList.addAll(sales);
                    salesTable.getSelectionModel().clearSelection();
                    updateActionButtons();
                });
            } catch (SQLException e) {
                logger.error("Error loading sales", e);
                Platform.runLater(() -> {
                    showAlert("Error", "Failed to load sales: " + e.getMessage());
                });
            }
        }).start();
    }

    private void searchSales() {
        new Thread(() -> {
            try {
                SaleHistoryService.SearchCriteria criteria = new SaleHistoryService.SearchCriteria();

                // Get filters
                String saleId = saleIdField.getText();
                if (saleId != null && !saleId.trim().isEmpty()) {
                    criteria.saleId = saleId.trim();
                }

                LocalDate startDate = startDatePicker.getValue();
                if (startDate != null) {
                    criteria.startDate = startDate;
                }

                LocalDate endDate = endDatePicker.getValue();
                if (endDate != null) {
                    criteria.endDate = endDate;
                }

                String cashier = cashierCombo.getValue();
                if (cashier != null && !cashier.trim().isEmpty()) {
                    criteria.cashierName = cashier.trim();
                }

                criteria.limit = 500; // Increase limit for searches

                List<SaleHistoryService.SaleRecord> sales = saleHistoryService.searchSales(criteria);

                Platform.runLater(() -> {
                    salesList.clear();
                    salesList.addAll(sales);
                    salesTable.getSelectionModel().clearSelection();
                    updateActionButtons();

                    if (sales.isEmpty()) {
                        showAlert("No Results", "No sales found matching the search criteria.");
                    }
                });
            } catch (SQLException e) {
                logger.error("Error searching sales", e);
                Platform.runLater(() -> {
                    showAlert("Error", "Failed to search sales: " + e.getMessage());
                });
            }
        }).start();
    }

    private void clearFilters() {
        saleIdField.clear();
        startDatePicker.setValue(null);
        endDatePicker.setValue(null);
        cashierCombo.setValue(null);
        if (salesTable != null) {
            salesTable.getSelectionModel().clearSelection();
        }
        updateActionButtons();
        loadSales();
    }

    private void showSaleDetails(SaleHistoryService.SaleRecord sale) {
        SaleDetailDialog dialog = new SaleDetailDialog(sale);
        dialog.showAndWait();
    }

    private void reprintReceipt(SaleHistoryService.SaleRecord sale) {
        new Thread(() -> {
            try {
                SaleSubmission submission = saleHistoryService.reconstructSaleSubmission(sale);
                String receiptText = generateReceiptText(submission);

                Platform.runLater(() -> {
                    ReceiptPrintHelper.printReceiptWithPDF(receiptText, sale.saleId,
                            getScene() != null ? getScene().getWindow() : null);
                });
            } catch (Exception e) {
                logger.error("Error reprinting receipt", e);
                Platform.runLater(() -> {
                    showAlert("Error", "Failed to reprint receipt: " + e.getMessage());
                });
            }
        }).start();
    }

    private String generateReceiptText(SaleSubmission submission) {
        StringBuilder sb = new StringBuilder();

        // Get store settings
        com.pos.service.SettingsService settingsService = com.pos.service.SettingsService.getInstance();
        String storeName = settingsService.getStoreName();
        String storeAddress = settingsService.getStoreAddress();
        String storePhone = settingsService.getStorePhone();
        String header = settingsService.getReceiptHeader();
        String footer = settingsService.getReceiptFooter();

        // --- HEADER SECTION ---
        // Store Info
        sb.append(centerText(storeName, 42)).append("\n");
        if (!storeAddress.isEmpty())
            sb.append(centerText(storeAddress, 42)).append("\n");
        if (!storePhone.isEmpty())
            sb.append(centerText(storePhone, 42)).append("\n");
        sb.append("\n");

        if (!header.isEmpty()) {
            sb.append(centerText(header, 42)).append("\n");
            sb.append("-".repeat(42)).append("\n");
        }

        // Transaction Info
        sb.append("Receipt #: ").append(submission.saleId).append("\n");
        if (submission.timestamp != null) {
            try {
                // Try to parse as ISO Instant first
                java.time.Instant instant;
                try {
                    instant = java.time.Instant.parse(submission.timestamp);
                } catch (Exception e) {
                    // Fallback: try parsing as simple date string or long timestamp
                    try {
                        long epoch = Long.parseLong(submission.timestamp);
                        instant = java.time.Instant.ofEpochSecond(epoch);
                    } catch (NumberFormatException nfe) {
                        // Just use current time if really broken? No, better print raw or N/A
                        instant = null;
                    }
                }

                if (instant != null) {
                    java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(instant,
                            java.time.ZoneId.systemDefault());
                    sb.append("Date: ").append(dateTime.format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss")))
                            .append("\n");
                } else {
                    sb.append("Date: ").append(submission.timestamp).append("\n");
                }
            } catch (Exception e) {
                sb.append("Date: ").append(submission.timestamp != null ? submission.timestamp : "N/A").append("\n");
            }
        } else {
            sb.append("Date: N/A\n");
        }
        sb.append("Cashier: ").append(submission.cashierName != null ? submission.cashierName : "N/A").append("\n");
        sb.append("\n");

        // --- ITEMS SECTION ---
        // Table Header: Item (16 chars) | Qty (3) | Price (10) | Total (10)
        // For card sales the stored data holds the card unit price (price) and the cash
        // line total (subtotal), so show both as comparable per-unit columns. Cash/EBT
        // sales only have the cash price stored, so keep the simple layout.
        boolean showDualPricing = submission.paymentMethod != null
                && !("CASH".equals(submission.paymentMethod) || "EBT".equals(submission.paymentMethod));
        if (showDualPricing) {
            sb.append(String.format("%-16s %3s %10s %10s\n", "Item", "Qty", "Card Price", "Cash Price"));
        } else {
            sb.append(String.format("%-16s %3s %10s %10s\n", "Item", "Qty", "Price", "Total"));
        }
        sb.append("-".repeat(42)).append("\n");
        for (SaleSubmission.SaleItemInput item : submission.items) {
            String name = item.name.length() > 16 ? item.name.substring(0, 16) : item.name;
            if (showDualPricing) {
                double cardUnit = item.price != null ? item.price : 0.0;
                // subtotal is the cash line total; divide by qty to get the cash unit price.
                double cashUnit = (item.subtotal != null && item.quantity != null && item.quantity > 0)
                        ? item.subtotal / item.quantity
                        : cardUnit;
                sb.append(String.format("%-16s %3d %10.2f %10.2f\n",
                        name, item.quantity, cardUnit, cashUnit));
            } else {
                sb.append(String.format("%-16s %3d %10.2f %10.2f\n",
                        name, item.quantity, item.price, item.subtotal));
            }

            // Show discount if exists
            if (item.discount != null && item.discount > 0) {
                sb.append(String.format("   Disc: -%8.2f\n", item.discount));
            }
        }
        sb.append("-".repeat(42)).append("\n");

        // --- TOTALS SECTION ---
        sb.append(String.format("%20s %21.2f\n", "Subtotal:", submission.subtotal));
        if (submission.discount > 0) {
            sb.append(String.format("%20s %21.2f\n", "Discount:", -submission.discount));
        }
        sb.append(String.format("%20s %21.2f\n", "Tax:", submission.tax));
        sb.append("=".repeat(42)).append("\n");
        sb.append(String.format("%-20s %21.2f\n", "TOTAL:", submission.total));
        sb.append("=".repeat(42)).append("\n");

        // Payment Details
        sb.append(String.format("%-20s %21s\n", "Payment Method:", submission.paymentMethod));
        if ("CASH".equals(submission.paymentMethod)) {
            if (submission.amountReceived != null && submission.amountReceived > 0) {
                sb.append(String.format("%20s %21.2f\n", "Cash Received:", submission.amountReceived));
            }
            if (submission.change != null && submission.change > 0) {
                sb.append(String.format("%20s %21.2f\n", "Change:", submission.change));
            }
        }
        sb.append("\n");

        // --- FOOTER ---
        if (!footer.isEmpty()) {
            sb.append(centerText(footer, 42)).append("\n");
        }

        // Add barcode placeholder (will be rendered by PDF service)
        if (submission.saleId != null && !submission.saleId.isEmpty()) {
            sb.append("\n");

            // The marker for Barcode rendering. Ensure it starts at the beginning of the
            // line
            if (!submission.saleId.startsWith("SALE-")) {
                sb.append("SALE-");
            }
            sb.append(submission.saleId).append("\n");
        }

        return sb.toString();
    }

    private String centerText(String text, int width) {
        if (text == null || text.length() >= width) {
            return text;
        }
        int padding = (width - text.length()) / 2;
        return " ".repeat(padding) + text;
    }

    private void processRefund(SaleHistoryService.SaleRecord sale) {
        // Permission check - Manager+ required
        if (!rbacService.hasPermission(RoleBasedAccessService.PERMISSION_PROCESS_REFUND)) {
            showAlert("Permission Denied",
                    "You do not have permission to process refunds. Manager or Admin role required.");
            return;
        }

        if (sale.isRefund()) {
            showAlert("Invalid Selection", "Refund transactions cannot be refunded again.");
            return;
        }

        if (sale.voided) {
            showAlert("Invalid Selection", "Voided sales cannot be refunded.");
            return;
        }

        // Use new full-screen refund screen if callback is available
        if (onNavigateToRefund != null) {
            onNavigateToRefund.accept(sale);
            return;
        }

        // Fallback to dialog (legacy)
        RefundDialog dialog = new RefundDialog(sale);
        RefundDialog.RefundResult result = dialog.showAndWait().orElse(null);

        if (result != null && result.refund != null) {
            // Process refund in background thread
            new Thread(() -> {
                try {
                    RefundService refundService = RefundService.getInstance();
                    boolean success = refundService.processRefund(result.refund);

                    Platform.runLater(() -> {
                        if (success) {
                            // Print refund receipt
                            String receiptText = generateRefundReceiptText(result.refund);
                            ReceiptPrintHelper.printReceiptWithPDF(receiptText, result.refund.getRefundId(),
                                    getScene() != null ? getScene().getWindow() : null);

                            // Open cash drawer if cash refund
                            if ("CASH".equals(result.refund.getRefundMethod())) {
                                hardwareManager.openCashDrawer();
                            }

                            showAlert("Success",
                                    "Refund processed successfully. Refund ID: " + result.refund.getRefundId());
                            loadSales(); // Refresh the list
                        } else {
                            showAlert("Error", "Failed to process refund. Please try again.");
                        }
                    });
                } catch (Exception e) {
                    logger.error("Error processing refund", e);
                    Platform.runLater(() -> {
                        showAlert("Error", "Failed to process refund: " + e.getMessage());
                    });
                }
            }).start();
        }
    }

    private String generateRefundReceiptText(Refund refund) {
        StringBuilder sb = new StringBuilder();

        // Get store settings
        com.pos.service.SettingsService settingsService = com.pos.service.SettingsService.getInstance();
        String storeName = settingsService.getStoreName();
        String storeAddress = settingsService.getStoreAddress();
        String storePhone = settingsService.getStorePhone();
        String header = settingsService.getReceiptHeader();
        String footer = settingsService.getReceiptFooter();

        // Store Info
        sb.append(centerText(storeName, 42)).append("\n");
        if (!storeAddress.isEmpty())
            sb.append(centerText(storeAddress, 42)).append("\n");
        if (!storePhone.isEmpty())
            sb.append(centerText(storePhone, 42)).append("\n");
        sb.append("\n");

        sb.append(centerText(header, 42)).append("\n");
        sb.append(centerText("*** REFUND RECEIPT ***", 42)).append("\n");
        sb.append("-".repeat(42)).append("\n");

        // Refund info
        sb.append("Refund ID: ").append(refund.getRefundId()).append("\n");
        sb.append("Original Sale: ").append(refund.getOriginalSaleId()).append("\n");
        if (refund.getTimestamp() != null) {
            try {
                java.time.Instant instant = java.time.Instant.parse(refund.getTimestamp());
                java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(instant,
                        java.time.ZoneId.systemDefault());
                sb.append("Date: ").append(dateTime.format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss")))
                        .append("\n");
            } catch (Exception e) {
                sb.append("Date: ").append(refund.getTimestamp()).append("\n");
            }
        }
        sb.append("Cashier: ").append(refund.getCashierName() != null ? refund.getCashierName() : "N/A").append("\n");
        sb.append("Reason: ").append(refund.getReason() != null ? refund.getReason() : "N/A").append("\n");
        sb.append("\n");

        // Refunded items
        sb.append(String.format("%-18s %3s %9s %9s\n", "Item", "Qty", "Price", "Amount"));
        sb.append("-".repeat(42)).append("\n");
        for (Refund.RefundItem item : refund.getItems()) {
            String name = item.getName();
            if (name.length() > 18) {
                name = name.substring(0, 15) + "...";
            }
            sb.append(String.format("%-18s %3d %9.2f %9.2f\n",
                    name, item.getRefundQuantity(), item.getOriginalPrice(), item.getRefundAmount()));
        }
        sb.append("-".repeat(42)).append("\n");

        // Totals
        sb.append(String.format("%-30s %11.2f\n", "Refund Amount:", refund.getRefundAmount()));
        sb.append(String.format("%-30s %11.2f\n", "Refund Tax:", refund.getRefundTax()));
        sb.append("-".repeat(42)).append("\n");
        sb.append(String.format("%-30s %11.2f\n", "TOTAL REFUND:", refund.getTotalRefund()));
        sb.append("\n");

        // Refund method
        sb.append("Refund Method: ").append(refund.getRefundMethod() != null ? refund.getRefundMethod() : "N/A")
                .append("\n");
        sb.append("\n");

        // Footer
        sb.append(centerText(footer, 42)).append("\n");
        sb.append("-".repeat(42)).append("\n");

        return sb.toString();
    }

    private void processVoid(SaleHistoryService.SaleRecord sale) {
        // Permission check - Manager+ required
        if (!rbacService.hasPermission(RoleBasedAccessService.PERMISSION_VOID_TRANSACTION)) {
            showAlert("Permission Denied",
                    "You do not have permission to void transactions. Manager or Admin role required.");
            return;
        }

        // Check if sale is already voided
        if (sale.voided) {
            showAlert("Already Voided", "This transaction has already been voided.");
            return;
        }

        // Check if sale can be voided
        new Thread(() -> {
            try {
                VoidService voidService = VoidService.getInstance();
                boolean canVoid = voidService.canVoidSale(sale.saleId);

                Platform.runLater(() -> {
                    if (!canVoid) {
                        showAlert("Cannot Void",
                                "This transaction cannot be voided. It may have already been voided, refunded, or is not from today.");
                        return;
                    }

                    // Open void dialog
                    VoidDialog dialog = new VoidDialog(sale);
                    VoidDialog.VoidResult result = dialog.showAndWait().orElse(null);

                    if (result != null && result.saleId != null) {
                        // Process void in background thread
                        new Thread(() -> {
                            try {
                                VoidService voidService2 = VoidService.getInstance();
                                boolean success = voidService2.voidTransaction(result.saleId, result.reason);

                                Platform.runLater(() -> {
                                    if (success) {
                                        showAlert("Success",
                                                "Transaction voided successfully. Stock has been restored.");
                                        loadSales(); // Refresh the list
                                    } else {
                                        showAlert("Error", "Failed to void transaction. Please try again.");
                                    }
                                });
                            } catch (Exception e) {
                                logger.error("Error voiding transaction", e);
                                Platform.runLater(() -> {
                                    showAlert("Error", "Failed to void transaction: " + e.getMessage());
                                });
                            }
                        }).start();
                    }
                });
            } catch (Exception e) {
                logger.error("Error checking if sale can be voided", e);
                Platform.runLater(() -> {
                    showAlert("Error", "Failed to check transaction status: " + e.getMessage());
                });
            }
        }).start();
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        DialogHelper.setAlertOwner(alert, null);
        alert.showAndWait();
    }
}
