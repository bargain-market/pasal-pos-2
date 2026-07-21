package com.pos.ui;

import com.pos.hardware.HardwareManager;
import com.pos.model.Refund;
import com.pos.service.RefundService;
import com.pos.service.RoleBasedAccessService;
import com.pos.service.SaleHistoryService;
import com.pos.service.UserAuthService;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.TouchTextArea;
import com.pos.util.DialogHelper;
import com.pos.util.ReceiptPrintHelper;
import com.pos.util.ResponsiveHelper;
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
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Full-screen view for processing refunds/returns.
 * Migrated from modal RefundDialog to integrated navigation flow.
 */
public class RefundScreen extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(RefundScreen.class);

    private final SaleHistoryService.SaleRecord originalSale;
    private final Runnable onBack;
    
    private TableView<RefundItemRow> itemsTable;
    private ObservableList<RefundItemRow> refundItems;
    private TouchTextArea reasonField;
    private ComboBox<String> refundMethodCombo;
    private Label totalRefundLabel;
    private Label refundTaxLabel;
    private Label subtotalRefundLabel;
    private Button processButton;
    
    private final NumberFormat currencyFormatter;
    private final SaleHistoryService saleHistoryService;
    private final RefundService refundService;
    private final HardwareManager hardwareManager;

    /**
     * Row data model for the refund items table
     */
    public static class RefundItemRow {
        private final String productId;
        private final String sku;
        private final String name;
        private final BigDecimal originalPrice;
        private final int originalQuantity;
        private int refundQuantity;
        private BigDecimal refundAmount;

        public RefundItemRow(SaleHistoryService.SaleItemRecord item) {
            this.productId = item.productId;
            this.sku = item.sku;
            this.name = item.name;
            this.originalPrice = item.price;
            this.originalQuantity = item.quantity;
            this.refundQuantity = 0;
            this.refundAmount = BigDecimal.ZERO;
        }

        public String getName() { return name; }
        public int getOriginalQuantity() { return originalQuantity; }
        public int getRefundQuantity() { return refundQuantity; }
        public BigDecimal getOriginalPrice() { return originalPrice; }
        public BigDecimal getRefundAmount() { return refundAmount; }
        public String getProductId() { return productId; }
        public String getSku() { return sku; }

        public void setRefundQuantity(int quantity) {
            this.refundQuantity = Math.min(Math.max(0, quantity), originalQuantity);
            this.refundAmount = originalPrice.multiply(BigDecimal.valueOf(this.refundQuantity));
        }
    }

    public RefundScreen(SaleHistoryService.SaleRecord sale, Runnable onBack) {
        this.originalSale = sale;
        this.onBack = onBack;
        this.refundItems = FXCollections.observableArrayList();
        this.currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US);
        this.saleHistoryService = SaleHistoryService.getInstance();
        this.refundService = RefundService.getInstance();
        this.hardwareManager = HardwareManager.getInstance();

        initializeUI();
        
        // Populate items
        for (SaleHistoryService.SaleItemRecord item : originalSale.items) {
            refundItems.add(new RefundItemRow(item));
        }
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #f8fafc;");
        setPadding(new Insets(0));

        // --- TOP SECTION ---
        VBox topSection = new VBox(0);
        
        // Header Bar
        HBox headerBar = new HBox(20);
        headerBar.setAlignment(Pos.CENTER_LEFT);
        headerBar.setPadding(new Insets(15, 25, 15, 25));
        headerBar.setStyle("-fx-background-color: white; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 10, 0, 0, 2);");

        Button backBtn = new Button("← Back to History");
        backBtn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-font-weight: bold; -fx-padding: 10 20; -fx-background-radius: 8; -fx-cursor: hand;");
        backBtn.setOnAction(e -> onBack.run());

        Label titleLabel = new Label("Process Refund");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label saleIdLabel = new Label("Ref: #" + originalSale.saleId);
        saleIdLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #64748b; -fx-background-color: #f8fafc; -fx-padding: 8 15; -fx-background-radius: 20;");

        headerBar.getChildren().addAll(backBtn, titleLabel, spacer, saleIdLabel);
        topSection.getChildren().add(headerBar);
        setTop(topSection);

        // --- CENTER SECTION (SCROLLABLE) ---
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        
        VBox content = new VBox(25);
        content.setPadding(new Insets(25));
        content.setMaxWidth(1200);
        content.setAlignment(Pos.TOP_CENTER);

        // Sale Summary Card
        VBox saleSummaryCard = createSaleSummaryCard();
        
        // Items Selection Card
        VBox itemsCard = createItemsSelectionCard();
        
        content.getChildren().addAll(saleSummaryCard, itemsCard);
        scrollPane.setContent(content);
        setCenter(scrollPane);

        // --- RIGHT SECTION (SIDEBAR FOR SUMMARY & ACTIONS) ---
        VBox sidebar = createSidebar();
        setRight(sidebar);
    }

    private VBox createSaleSummaryCard() {
        VBox card = new VBox(15);
        card.setStyle("-fx-background-color: white; -fx-padding: 20; -fx-background-radius: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.03), 8, 0, 0, 2);");

        Label sectionTitle = new Label("Original Sale Details");
        sectionTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #334155;");

        GridPane grid = new GridPane();
        grid.setHgap(40);
        grid.setVgap(12);

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("MMMM dd, yyyy HH:mm");
        String dateStr = originalSale.saleDate != null ? originalSale.saleDate.atStartOfDay().format(dtf) : "Unknown";

        addSummaryRow(grid, "Date & Time:", dateStr, 0);
        addSummaryRow(grid, "Cashier:", originalSale.cashierName, 1);
        addSummaryRow(grid, "Original Total:", currencyFormatter.format(originalSale.total), 2);
        addSummaryRow(grid, "Payment Method:", originalSale.paymentMethod, 3);

        card.getChildren().addAll(sectionTitle, new Separator(), grid);
        return card;
    }

    private void addSummaryRow(GridPane grid, String label, String value, int row) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-text-fill: #64748b; -fx-font-size: 14px;");
        Label val = new Label(value != null ? value : "N/A");
        val.setStyle("-fx-text-fill: #1e293b; -fx-font-weight: bold; -fx-font-size: 14px;");
        grid.add(lbl, 0, row);
        grid.add(val, 1, row);
    }

    private VBox createItemsSelectionCard() {
        VBox card = new VBox(15);
        card.setStyle("-fx-background-color: white; -fx-padding: 20; -fx-background-radius: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.03), 8, 0, 0, 2);");

        Label sectionTitle = new Label("Select Items to Refund");
        sectionTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #334155;");

        itemsTable = new TableView<>();
        itemsTable.setItems(refundItems);
        itemsTable.setFixedCellSize(60);
        itemsTable.setPrefHeight(400);
        itemsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        itemsTable.setStyle("-fx-background-color: transparent; -fx-selection-bar: #f1f5f9;");

        setupTableColumns();

        card.getChildren().addAll(sectionTitle, new Separator(), itemsTable);
        return card;
    }

    private void setupTableColumns() {
        TableColumn<RefundItemRow, String> nameCol = new TableColumn<>("Item Name");
        nameCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getName()));
        nameCol.setPrefWidth(300);

        TableColumn<RefundItemRow, Integer> origQtyCol = new TableColumn<>("Original Qty");
        origQtyCol.setCellValueFactory(data -> new javafx.beans.property.SimpleIntegerProperty(data.getValue().getOriginalQuantity()).asObject());
        origQtyCol.setStyle("-fx-alignment: CENTER;");

        TableColumn<RefundItemRow, Integer> refundQtyCol = new TableColumn<>("Refund Qty");
        refundQtyCol.setCellFactory(col -> new TableCell<>() {
            private final Spinner<Integer> spinner = new Spinner<>(0, 0, 0);
            {
                spinner.getStyleClass().add(Spinner.STYLE_CLASS_SPLIT_ARROWS_HORIZONTAL);
                spinner.setEditable(true);
                spinner.valueProperty().addListener((obs, oldV, newV) -> {
                    RefundItemRow row = getTableRow().getItem();
                    if (row != null && newV != null) {
                        row.setRefundQuantity(newV);
                        updateTotals();
                        itemsTable.refresh();
                    }
                });
            }
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    RefundItemRow row = getTableRow().getItem();
                    spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, row.getOriginalQuantity(), row.getRefundQuantity()));
                    setGraphic(spinner);
                }
            }
        });
        refundQtyCol.setPrefWidth(150);
        refundQtyCol.setStyle("-fx-alignment: CENTER;");

        TableColumn<RefundItemRow, String> priceCol = new TableColumn<>("Price");
        priceCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(currencyFormatter.format(data.getValue().getOriginalPrice())));
        priceCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<RefundItemRow, String> totalCol = new TableColumn<>("Subtotal");
        totalCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(currencyFormatter.format(data.getValue().getRefundAmount())));
        totalCol.setStyle("-fx-alignment: CENTER-RIGHT; -fx-font-weight: bold;");

        itemsTable.getColumns().add(nameCol);
        itemsTable.getColumns().add(origQtyCol);
        itemsTable.getColumns().add(refundQtyCol);
        itemsTable.getColumns().add(priceCol);
        itemsTable.getColumns().add(totalCol);
    }

    private VBox createSidebar() {
        VBox sidebar = new VBox(20);
        sidebar.setPadding(new Insets(25));
        sidebar.setPrefWidth(350);
        sidebar.setStyle("-fx-background-color: white; -fx-border-color: #e2e8f0; -fx-border-width: 0 0 0 1;");

        Label summaryTitle = new Label("Refund Summary");
        summaryTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        VBox totalsBox = new VBox(12);
        totalsBox.setPadding(new Insets(10, 0, 10, 0));
        
        subtotalRefundLabel = createSummaryLabel("Subtotal:", "$0.00", false);
        refundTaxLabel = createSummaryLabel("Estimated Tax:", "$0.00", false);
        totalRefundLabel = createSummaryLabel("Total Refund:", "$0.00", true);
        totalRefundLabel.lookup(".value").setStyle("-fx-text-fill: #ef4444; -fx-font-size: 24px; -fx-font-weight: bold;");

        totalsBox.getChildren().addAll(
            createSummaryRow("Subtotal:", subtotalRefundLabel),
            createSummaryRow("Tax:", refundTaxLabel),
            new Separator(),
            createSummaryRow("Total:", totalRefundLabel)
        );

        VBox inputsBox = new VBox(15);
        
        Label methodLabel = new Label("Refund Method");
        methodLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #475569;");
        refundMethodCombo = new ComboBox<>();
        refundMethodCombo.getItems().addAll("CASH", "CARD", "STORE_CREDIT");
        refundMethodCombo.setValue(originalSale.paymentMethod != null ? originalSale.paymentMethod : "CASH");
        refundMethodCombo.setMaxWidth(Double.MAX_VALUE);
        refundMethodCombo.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 8; -fx-padding: 5;");

        Label reasonLabel = new Label("Reason for Refund");
        reasonLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #475569;");
        reasonField = TouchScreenComponents.createTouchOnlyTextArea("Enter a mandatory reason for this refund...");
        reasonField.setPrefRowCount(4);
        reasonField.setWrapText(true);
        reasonField.setShowKeypad(true);
        reasonField.setStyle("-fx-background-color: transparent;");

        processButton = new Button("Confirm Refund");
        processButton.setMaxWidth(Double.MAX_VALUE);
        processButton.setPrefHeight(60);
        processButton.setDisable(true);
        processButton.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-background-radius: 12; -fx-cursor: hand;");
        processButton.setOnAction(e -> handleProcessRefund());

        inputsBox.getChildren().addAll(methodLabel, refundMethodCombo, reasonLabel, reasonField, processButton);

        sidebar.getChildren().addAll(summaryTitle, new Separator(), totalsBox, new Separator(), inputsBox);
        return sidebar;
    }

    private HBox createSummaryRow(String label, Label valueLabel) {
        HBox row = new HBox();
        Label lbl = new Label(label);
        lbl.setStyle("-fx-text-fill: #64748b; -fx-font-size: 14px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().addAll(lbl, spacer, valueLabel);
        return row;
    }

    private Label createSummaryLabel(String label, String initialValue, boolean isTotal) {
        Label valLabel = new Label(initialValue);
        valLabel.getStyleClass().add("value");
        valLabel.setStyle(isTotal ? "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #1e293b;" : "-fx-font-size: 14px; -fx-text-fill: #475569;");
        return valLabel;
    }

    private void updateTotals() {
        BigDecimal subtotal = refundItems.stream()
                .map(RefundItemRow::getRefundAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal refundTax = BigDecimal.ZERO;
        if (subtotal.compareTo(BigDecimal.ZERO) > 0 && originalSale.subtotal.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal proportion = subtotal.divide(originalSale.subtotal, 8, RoundingMode.HALF_UP);
            refundTax = originalSale.tax.multiply(proportion).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal total = subtotal.add(refundTax);

        subtotalRefundLabel.setText(currencyFormatter.format(subtotal));
        refundTaxLabel.setText(currencyFormatter.format(refundTax));
        totalRefundLabel.setText(currencyFormatter.format(total));

        processButton.setDisable(total.compareTo(BigDecimal.ZERO) <= 0);
    }

    private void handleProcessRefund() {
        String reason = reasonField.getText() != null ? reasonField.getText().trim() : "";
        if (reason.isEmpty()) {
            showAlert("Reason Required", "Please provide a reason for the refund to proceed.");
            reasonField.requestFocus();
            return;
        }

        // Final confirmation
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Refund");
        confirm.setHeaderText("Authorize Refund Generation");
        confirm.setContentText("Are you sure you want to process a refund of " + totalRefundLabel.getText() + " for this sale?\nThis action cannot be undone.");
        DialogHelper.setAlertOwner(confirm, getScene().getWindow());

        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        processButton.setDisable(true);
        processButton.setText("Processing...");

        new Thread(() -> {
            try {
                Refund refund = createRefundModel();
                boolean success = refundService.processRefund(refund);

                Platform.runLater(() -> {
                    if (success) {
                        handleRefundSuccess(refund);
                    } else {
                        showAlert("Processing Error", "The system could not complete the refund transaction. Please check logs.");
                        processButton.setDisable(false);
                        processButton.setText("Confirm Refund");
                    }
                });
            } catch (Exception e) {
                logger.error("Error during refund processing", e);
                Platform.runLater(() -> {
                    showAlert("Unexpected System Error", e.getMessage());
                    processButton.setDisable(false);
                    processButton.setText("Confirm Refund");
                });
            }
        }).start();
    }

    private Refund createRefundModel() {
        Refund refund = new Refund();
        refund.setRefundId(refundService.generateRefundId());
        refund.setOriginalSaleId(originalSale.saleId);
        refund.setPaymentMethod(originalSale.paymentMethod);
        refund.setRefundMethod(refundMethodCombo.getValue());
        refund.setReason(reasonField.getText().trim());
        refund.setCashierName(UserAuthService.getInstance().getCurrentUserName());
        refund.setPosUserId(UserAuthService.getInstance().getCurrentPosUserId());
        refund.setTimestamp(Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT));

        for (RefundItemRow row : refundItems) {
            if (row.getRefundQuantity() > 0) {
                Refund.RefundItem item = new Refund.RefundItem();
                item.setProductId(row.getProductId());
                item.setSku(row.getSku());
                item.setName(row.getName());
                item.setOriginalPrice(row.getOriginalPrice());
                item.setOriginalQuantity(row.getOriginalQuantity());
                item.setRefundQuantity(row.getRefundQuantity());
                item.setRefundAmount(row.getRefundAmount());
                refund.getItems().add(item);
            }
        }
        return refund;
    }

    private void handleRefundSuccess(Refund refund) {
        // Print receipt
        try {
            String receiptText = generateRefundReceiptText(refund);
            ReceiptPrintHelper.printReceiptWithPDF(receiptText, refund.getRefundId(), getScene().getWindow());
        } catch (Exception e) {
            logger.warn("Failed to print refund receipt automatically", e);
        }

        // Open drawer if cash
        if ("CASH".equals(refund.getRefundMethod())) {
            hardwareManager.openCashDrawer();
        }

        Alert success = new Alert(Alert.AlertType.INFORMATION);
        success.setTitle("Refund Complete");
        success.setHeaderText("Transaction Successful");
        success.setContentText("Refund ID: " + refund.getRefundId() + "\n\nThe amount has been logged in today's shift.");
        DialogHelper.setAlertOwner(success, getScene().getWindow());
        success.showAndWait();

        onBack.run();
    }

    private String generateRefundReceiptText(Refund refund) {
        // Logic from SaleHistoryView.generateRefundReceiptText
        StringBuilder sb = new StringBuilder();
        com.pos.service.SettingsService settingsService = com.pos.service.SettingsService.getInstance();
        
        sb.append(centerText(settingsService.getStoreName(), 42)).append("\n");
        sb.append(centerText(settingsService.getStoreAddress(), 42)).append("\n");
        sb.append(centerText(settingsService.getStorePhone(), 42)).append("\n\n");

        sb.append(centerText("*** REFUND RECEIPT ***", 42)).append("\n");
        sb.append("-".repeat(42)).append("\n");

        sb.append("Refund ID: ").append(refund.getRefundId()).append("\n");
        sb.append("Original Sale: ").append(refund.getOriginalSaleId()).append("\n");
        sb.append("Date: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm"))).append("\n");
        sb.append("Cashier: ").append(refund.getCashierName()).append("\n");
        sb.append("Reason: ").append(refund.getReason()).append("\n\n");

        sb.append(String.format("%-18s %3s %9s %9s\n", "Item", "Qty", "Price", "Amount"));
        sb.append("-".repeat(42)).append("\n");
        for (Refund.RefundItem item : refund.getItems()) {
            String name = item.getName().length() > 18 ? item.getName().substring(0, 15) + "..." : item.getName();
            sb.append(String.format("%-18s %3d %9.2f %9.2f\n", name, item.getRefundQuantity(), item.getOriginalPrice(), item.getRefundAmount()));
        }
        sb.append("-".repeat(42)).append("\n");
        sb.append(String.format("%-30s %11.2f\n", "Refund Amount:", refund.getRefundAmount()));
        sb.append(String.format("%-30s %11.2f\n", "Refund Tax:", refund.getRefundTax()));
        sb.append("=".repeat(42)).append("\n");
        sb.append(String.format("%-30s %11.2f\n", "TOTAL REFUND:", refund.getTotalRefund()));
        sb.append("\n");
        sb.append("Method: ").append(refund.getRefundMethod()).append("\n\n");
        sb.append(centerText(settingsService.getReceiptFooter(), 42)).append("\n");

        return sb.toString();
    }

    private String centerText(String text, int width) {
        if (text == null || text.length() >= width) return text;
        int padding = (width - text.length()) / 2;
        return " ".repeat(padding) + text;
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        if (getScene() != null && getScene().getWindow() != null) {
            DialogHelper.setAlertOwner(alert, getScene().getWindow());
        }
        alert.showAndWait();
    }
}
