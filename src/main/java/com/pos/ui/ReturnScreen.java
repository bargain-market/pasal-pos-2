package com.pos.ui;

import com.pos.hardware.HardwareManager;
import com.pos.service.ReturnService;
import com.pos.service.ReturnService.ReturnItem;
import com.pos.service.ReturnService.ReturnResult;
import com.pos.service.ReturnService.SaleInfo;
import com.pos.service.ReturnService.SaleItemInfo;
import com.pos.service.UserAuthService;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.TouchTextArea;
import com.pos.util.DialogHelper;
import com.pos.util.ReceiptPrintHelper;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen view for searching and processing returns and exchanges.
 * Replaces the modal ReturnExchangeDialog with a more integrated, touch-friendly flow.
 */
public class ReturnScreen extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(ReturnScreen.class);

    private final ReturnService returnService;
    private final HardwareManager hardwareManager;
    private final Runnable onBack;
    private final NumberFormat currencyFormatter;

    // UI Components - Search Section
    private TextField searchField;
    private ListView<SaleInfo> saleListView;
    private ObservableList<SaleInfo> foundSales;

    // UI Components - Details Section
    private TableView<ReturnItemRow> itemsTable;
    private ObservableList<ReturnItemRow> returnItems;
    private SaleInfo selectedSale;
    
    // UI Components - Sidebar
    private Label totalRefundLabel;
    private ComboBox<String> reasonCombo;
    private ComboBox<String> refundMethodCombo;
    private TouchTextArea additionalNotes;
    private Button processButton;

    // View State
    private enum ViewState { SEARCH, DETAILS }
    private ViewState currentViewState = ViewState.SEARCH;
    private StackPane centerStack;
    private VBox searchView;
    private VBox detailsView;

    public ReturnScreen(Runnable onBack) {
        this.onBack = onBack;
        this.returnService = ReturnService.getInstance();
        this.hardwareManager = HardwareManager.getInstance();
        this.currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US);
        this.foundSales = FXCollections.observableArrayList();
        this.returnItems = FXCollections.observableArrayList();

        initializeUI();
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #f8fafc;");
        setPadding(new Insets(0));

        // --- TOP HEADER ---
        VBox topHeader = createHeader();
        setTop(topHeader);

        // --- CENTER CONTENT ---
        centerStack = new StackPane();
        
        searchView = createSearchView();
        detailsView = createDetailsView();
        detailsView.setVisible(false);
        detailsView.setManaged(false);

        centerStack.getChildren().addAll(searchView, detailsView);
        setCenter(centerStack);

        // --- RIGHT SIDEBAR ---
        VBox sidebar = createSidebar();
        setRight(sidebar);
        
        // Initial state
        switchViewState(ViewState.SEARCH);
    }

    private VBox createHeader() {
        VBox header = new VBox(0);
        header.setStyle("-fx-background-color: white; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 10, 0, 0, 2);");

        HBox topBar = new HBox(20);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(15, 25, 15, 25));

        Button backBtn = new Button("\u2190 Back to Sales");
        backBtn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-font-weight: bold; -fx-padding: 10 20; -fx-background-radius: 8; -fx-cursor: hand;");
        backBtn.setOnAction(e -> {
            if (currentViewState == ViewState.DETAILS) {
                switchViewState(ViewState.SEARCH);
            } else {
                onBack.run();
            }
        });

        Label titleLabel = new Label("Return & Exchange Center");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header.getChildren().add(topBar);
        topBar.getChildren().addAll(backBtn, titleLabel, spacer);

        return header;
    }

    private VBox createSearchView() {
        VBox view = new VBox(25);
        view.setPadding(new Insets(40));
        view.setAlignment(Pos.TOP_CENTER);
        view.setMaxWidth(1000);

        Label searchTitle = new Label("Find Sale Receipt");
        searchTitle.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #334155;");

        HBox searchBox = new HBox(15);
        searchBox.setAlignment(Pos.CENTER);
        searchBox.setMaxWidth(800);

        searchField = new TextField();
        searchField.setPromptText("Enter Receipt ID (e.g., #2024-001) or Date (YYYY-MM-DD)");
        searchField.setPrefHeight(60);
        searchField.setStyle("-fx-font-size: 18px; -fx-background-radius: 12; -fx-border-radius: 12; -fx-border-color: #e2e8f0; -fx-padding: 0 20;");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setOnAction(e -> handleSearch());

        Button searchBtn = new Button("Search Receipts");
        searchBtn.setPrefHeight(60);
        searchBtn.setMinWidth(200);
        searchBtn.setStyle("-fx-background-color: #0ea5e9; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-background-radius: 12; -fx-cursor: hand;");
        searchBtn.setOnAction(e -> handleSearch());

        searchBox.getChildren().addAll(searchField, searchBtn);

        // Results List
        saleListView = new ListView<>(foundSales);
        saleListView.setCellFactory(lv -> new SaleListCell());
        saleListView.setPrefHeight(500);
        saleListView.setMaxWidth(800);
        saleListView.setStyle("-fx-background-radius: 12; -fx-border-radius: 12; -fx-border-color: #e2e8f0;");
        saleListView.getSelectionModel().selectedItemProperty().addListener((obs, old, newVal) -> {
            if (newVal != null) {
                loadSaleDetails(newVal);
            }
        });

        VBox resultsContainer = new VBox(15);
        resultsContainer.setAlignment(Pos.TOP_CENTER);
        resultsContainer.getChildren().addAll(new Label("Recent Receipts Found:"), saleListView);
        resultsContainer.setVisible(false);
        resultsContainer.setManaged(false);

        foundSales.addListener((javafx.collections.ListChangeListener<SaleInfo>) c -> {
            boolean hasResults = !foundSales.isEmpty();
            resultsContainer.setVisible(hasResults);
            resultsContainer.setManaged(hasResults);
        });

        view.getChildren().addAll(searchTitle, searchBox, resultsContainer);
        return view;
    }

    private VBox createDetailsView() {
        VBox view = new VBox(20);
        view.setPadding(new Insets(30));
        view.setAlignment(Pos.TOP_CENTER);

        // Header for Details
        HBox detailsHeader = new HBox(20);
        detailsHeader.setAlignment(Pos.CENTER_LEFT);
        
        VBox saleMeta = new VBox(5);
        Label saleIdLabel = new Label("Processing Return for Receipt #");
        saleIdLabel.setStyle("-fx-font-size: 16px; -fx-text-fill: #64748b;");
        Label saleTotalLabel = new Label("Original Total: $0.00");
        saleTotalLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #94a3b8;");
        saleMeta.getChildren().addAll(saleIdLabel, saleTotalLabel);

        detailsHeader.getChildren().add(saleMeta);

        // Items Table
        itemsTable = new TableView<>(returnItems);
        itemsTable.setEditable(true);
        itemsTable.setFixedCellSize(70);
        itemsTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        itemsTable.setStyle("-fx-background-radius: 12; -fx-border-radius: 12;");
        VBox.setVgrow(itemsTable, Priority.ALWAYS);

        setupTableColumns();

        view.getChildren().addAll(detailsHeader, itemsTable);
        return view;
    }

    private void setupTableColumns() {
        // Select Col
        TableColumn<ReturnItemRow, Boolean> selectCol = new TableColumn<>("Return?");
        selectCol.setCellValueFactory(data -> data.getValue().selectedProperty());
        selectCol.setCellFactory(CheckBoxTableCell.forTableColumn(selectCol));
        selectCol.setMaxWidth(80);
        selectCol.setStyle("-fx-alignment: CENTER;");

        // Name Col
        TableColumn<ReturnItemRow, String> nameCol = new TableColumn<>("Product Name");
        nameCol.setCellValueFactory(data -> data.getValue().nameProperty());
        nameCol.setMinWidth(250);

        // Qty Col
        TableColumn<ReturnItemRow, Number> availQtyCol = new TableColumn<>("Purchased");
        availQtyCol.setCellValueFactory(data -> data.getValue().originalQuantityProperty());
        availQtyCol.setMaxWidth(100);
        availQtyCol.setStyle("-fx-alignment: CENTER;");

        // Return Qty Col
        TableColumn<ReturnItemRow, Integer> returnQtyCol = new TableColumn<>("Return Qty");
        returnQtyCol.setCellValueFactory(data -> data.getValue().returnQuantityProperty().asObject());
        returnQtyCol.setCellFactory(col -> new SpinnerTableCell());
        returnQtyCol.setMaxWidth(120);

        // Price Col
        TableColumn<ReturnItemRow, String> priceCol = new TableColumn<>("UnitPrice");
        priceCol.setCellValueFactory(data -> data.getValue().priceProperty());
        priceCol.setMaxWidth(120);
        priceCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        // Refund Col
        TableColumn<ReturnItemRow, String> refundCol = new TableColumn<>("Refund Amount");
        refundCol.setCellValueFactory(data -> data.getValue().refundAmountProperty());
        refundCol.setMaxWidth(150);
        refundCol.setStyle("-fx-alignment: CENTER-RIGHT; -fx-font-weight: bold; -fx-text-fill: #10b981;");

        itemsTable.getColumns().setAll(selectCol, nameCol, availQtyCol, returnQtyCol, priceCol, refundCol);
    }

    private VBox createSidebar() {
        VBox sidebar = new VBox(25);
        sidebar.setPadding(new Insets(30));
        sidebar.setPrefWidth(400);
        sidebar.setStyle("-fx-background-color: white; -fx-border-color: #e2e8f0; -fx-border-width: 0 0 0 1;");

        Label summaryTitle = new Label("Return Summary");
        summaryTitle.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        // Totals Box
        VBox totalsBox = new VBox(15);
        totalsBox.setPadding(new Insets(10, 0, 10, 0));
        
        HBox refundRow = new HBox();
        Label refundLabelHeader = new Label("Estimated Refund:");
        refundLabelHeader.setStyle("-fx-font-size: 16px; -fx-text-fill: #64748b;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        totalRefundLabel = new Label("$0.00");
        totalRefundLabel.setStyle("-fx-font-size: 32px; -fx-font-weight: 900; -fx-text-fill: #ef4444;");
        
        refundRow.getChildren().addAll(refundLabelHeader, sp, totalRefundLabel);
        totalsBox.getChildren().add(refundRow);

        // Refund Mode
        VBox configBox = new VBox(20);
        
        VBox methodBox = new VBox(8);
        Label methodLbl = new Label("Refund Method");
        methodLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #475569;");
        refundMethodCombo = new ComboBox<>();
        refundMethodCombo.getItems().addAll("CASH", "CARD", "STORE_CREDIT");
        refundMethodCombo.setValue("CASH");
        refundMethodCombo.setMaxWidth(Double.MAX_VALUE);
        refundMethodCombo.setPrefHeight(50);
        refundMethodCombo.setStyle("-fx-background-radius: 8;");
        methodBox.getChildren().addAll(methodLbl, refundMethodCombo);

        VBox reasonBox = new VBox(8);
        Label reasonLbl = new Label("Return Reason");
        reasonLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #475569;");
        reasonCombo = new ComboBox<>();
        reasonCombo.getItems().addAll(
                "Defective/Damaged",
                "Wrong Item",
                "Customer Changed Mind",
                "Size/Fit Issue",
                "Quality Issue",
                "Other");
        reasonCombo.setValue("Customer Changed Mind");
        reasonCombo.setMaxWidth(Double.MAX_VALUE);
        reasonCombo.setPrefHeight(50);
        reasonCombo.setStyle("-fx-background-radius: 8;");
        reasonBox.getChildren().addAll(reasonLbl, reasonCombo);

        VBox noteBox = new VBox(8);
        Label noteLbl = new Label("Additional Notes");
        noteLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #475569;");
        additionalNotes = TouchScreenComponents.createTouchOnlyTextArea("Enter any additional details here...");
        additionalNotes.setPrefRowCount(4);
        additionalNotes.setShowKeypad(true);
        noteBox.getChildren().addAll(noteLbl, additionalNotes);

        configBox.getChildren().addAll(methodBox, reasonBox, noteBox);

        // Process Button
        processButton = new Button("Process Refund");
        processButton.setMaxWidth(Double.MAX_VALUE);
        processButton.setPrefHeight(70);
        processButton.setDisable(true);
        processButton.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-size: 20px; -fx-font-weight: bold; -fx-background-radius: 15; -fx-cursor: hand;");
        processButton.setOnAction(e -> handleProcessReturn());

        sidebar.getChildren().addAll(summaryTitle, new Separator(), totalsBox, configBox, new Region(), processButton);
        VBox.setVgrow(sidebar.getChildren().get(sidebar.getChildren().size()-2), Priority.ALWAYS);

        return sidebar;
    }

    // ==================== LOGIC ====================

    private void handleSearch() {
        String query = searchField.getText().trim();
        if (query.isEmpty()) return;

        new Thread(() -> {
            try {
                List<SaleInfo> sales = returnService.searchSales(query);
                Platform.runLater(() -> {
                    foundSales.clear();
                    foundSales.addAll(sales);
                    if (sales.isEmpty()) {
                        DialogHelper.showError("No Results", "No receipts match '" + query + "'.", getScene().getWindow());
                    }
                });
            } catch (Exception e) {
                logger.error("Search failed", e);
                Platform.runLater(() -> DialogHelper.showError("Search Error", "Failed to search for receipts: " + e.getMessage(), getScene().getWindow()));
            }
        }).start();
    }

    private void loadSaleDetails(SaleInfo sale) {
        this.selectedSale = sale;
        new Thread(() -> {
            try {
                List<SaleItemInfo> items = returnService.getSaleItems(sale.saleId);
                Platform.runLater(() -> {
                    returnItems.clear();
                    for (SaleItemInfo item : items) {
                        if (item.returnableQuantity > 0) {
                            returnItems.add(new ReturnItemRow(item));
                        }
                    }
                    updateTotals();
                    switchViewState(ViewState.DETAILS);
                    
                    // Update header/summary info
                    if (detailsView.getChildren().get(0) instanceof HBox hb) {
                        if (hb.getChildren().get(0) instanceof VBox vb) {
                            ((Label)vb.getChildren().get(0)).setText("Processing Return for Receipt #" + sale.saleId);
                            ((Label)vb.getChildren().get(1)).setText("Original Total: " + currencyFormatter.format(sale.total) + " | " + sale.paymentMethod);
                        }
                    }
                    refundMethodCombo.setValue(sale.paymentMethod != null ? sale.paymentMethod : "CASH");
                });
            } catch (Exception e) {
                logger.error("Load items failed", e);
                Platform.runLater(() -> DialogHelper.showError("Load Error", "Failed to load sale details: " + e.getMessage(), getScene().getWindow()));
            }
        }).start();
    }

    private void updateTotals() {
        BigDecimal total = returnItems.stream()
                .map(ReturnItemRow::getRefundAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        totalRefundLabel.setText(currencyFormatter.format(total));
        processButton.setDisable(total.compareTo(BigDecimal.ZERO) <= 0);
    }

    private void handleProcessReturn() {
        if (selectedSale == null) return;

        List<ReturnItem> items = new ArrayList<>();
        for (ReturnItemRow row : returnItems) {
            if (row.isSelected() && row.getReturnQuantity() > 0) {
                ReturnItem item = new ReturnItem();
                item.productId = row.productId;
                item.sku = row.sku;
                item.name = row.name;
                item.originalPrice = row.price;
                item.originalQuantity = row.originalQuantity;
                item.returnQuantity = row.getReturnQuantity();
                item.refundAmount = row.getRefundAmount();
                item.restoreToInventory = true;
                items.add(item);
            }
        }

        if (items.isEmpty()) {
            DialogHelper.showError("Selection Required", "Please select at least one item to return.", getScene().getWindow());
            return;
        }

        String reason = reasonCombo.getValue();
        String method = refundMethodCombo.getValue();
        String cashier = UserAuthService.getInstance().getCurrentUserName();

        // Confirmation
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Refund");
        confirm.setHeaderText("Process Refund of " + totalRefundLabel.getText() + "?");
        confirm.setContentText("This will record the return and update inventory. Proceed?");
        DialogHelper.setAlertOwner(confirm, getScene().getWindow());

        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        processButton.setDisable(true);
        processButton.setText("Processing...");

        new Thread(() -> {
            try {
                ReturnResult result = returnService.processReturn(selectedSale.saleId, items, reason, method, cashier);
                Platform.runLater(() -> {
                    if (result.success) {
                        handleProcessSuccess(result);
                    } else {
                        DialogHelper.showError("Process Failed", "Return could not be processed: " + result.errorMessage, getScene().getWindow());
                        processButton.setDisable(false);
                        processButton.setText("Process Refund");
                    }
                });
            } catch (Exception e) {
                logger.error("Return processing failed", e);
                Platform.runLater(() -> {
                    DialogHelper.showError("System Error", "An unexpected error occurred: " + e.getMessage(), getScene().getWindow());
                    processButton.setDisable(false);
                    processButton.setText("Process Refund");
                });
            }
        }).start();
    }

    private void handleProcessSuccess(ReturnResult result) {
        // Print Receipt
        try {
            // Re-using simplified receipt generation logic
            StringBuilder sb = new StringBuilder();
            com.pos.service.SettingsService settingsService = com.pos.service.SettingsService.getInstance();
            sb.append("\n").append(centerText(settingsService.getStoreName(), 42)).append("\n");
            sb.append(centerText("*** RETURN RECEIPT ***", 42)).append("\n");
            sb.append("-".repeat(42)).append("\n");
            sb.append("Refund ID: ").append(result.refundId).append("\n");
            sb.append("Amount: ").append(currencyFormatter.format(result.refundAmount)).append("\n");
            sb.append("Method: ").append(refundMethodCombo.getValue()).append("\n");
            sb.append("Date: ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm"))).append("\n");
            sb.append("-".repeat(42)).append("\n\n");
            
            ReceiptPrintHelper.printReceiptWithPDF(sb.toString(), result.refundId, getScene().getWindow());
        } catch (Exception e) {
            logger.warn("Print failed: {}", e.getMessage());
        }

        if ("CASH".equals(refundMethodCombo.getValue())) {
            hardwareManager.openCashDrawer();
        }

        Alert success = new Alert(Alert.AlertType.INFORMATION);
        success.setTitle("Return Complete");
        success.setHeaderText("Success");
        success.setContentText("Return processed successfully.\nRefund ID: " + result.refundId);
        DialogHelper.setAlertOwner(success, getScene().getWindow());
        success.showAndWait();

        onBack.run();
    }

    private void switchViewState(ViewState state) {
        this.currentViewState = state;
        searchView.setVisible(state == ViewState.SEARCH);
        searchView.setManaged(state == ViewState.SEARCH);
        detailsView.setVisible(state == ViewState.DETAILS);
        detailsView.setManaged(state == ViewState.DETAILS);
        
        if (state == ViewState.SEARCH) {
            selectedSale = null;
            returnItems.clear();
            searchField.requestFocus();
            updateTotals();
        }
    }

    private String centerText(String text, int width) {
        if (text == null || text.length() >= width) return text;
        int p = (width - text.length()) / 2;
        return " ".repeat(p) + text;
    }

    // ==================== HELPER CLASSES ====================

    private class SaleListCell extends ListCell<SaleInfo> {
        @Override
        protected void updateItem(SaleInfo sale, boolean empty) {
            super.updateItem(sale, empty);
            if (empty || sale == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            VBox row = new VBox(5);
            row.setPadding(new Insets(10, 15, 10, 15));
            row.getStyleClass().add("touch-list-cell");

            Label idLabel = new Label("Receipt #" + sale.saleId);
            idLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

            Label meta = new Label(currencyFormatter.format(sale.total) + " | " + sale.itemCount + " items | " + sale.paymentMethod + " | " + (sale.timestamp != null ? sale.timestamp.substring(0, 10) : "N/A"));
            meta.setStyle("-fx-text-fill: #64748b; -fx-font-size: 13px;");

            row.getChildren().addAll(idLabel, meta);
            setGraphic(row);
        }
    }

    private class SpinnerTableCell extends TableCell<ReturnItemRow, Integer> {
        private final Spinner<Integer> spinner = new Spinner<>();
        public SpinnerTableCell() {
            spinner.setEditable(true);
            spinner.setMaxWidth(Double.MAX_VALUE);
            spinner.valueProperty().addListener((obs, oldV, newV) -> {
                if (getTableRow() != null && getTableRow().getItem() != null) {
                    ReturnItemRow row = getTableRow().getItem();
                    row.setReturnQuantity(newV);
                    updateTotals();
                    getTableView().refresh();
                }
            });
        }
        @Override
        protected void updateItem(Integer item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                setGraphic(null);
            } else {
                ReturnItemRow row = getTableRow().getItem();
                spinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, row.returnableQuantity, row.getReturnQuantity()));
                setGraphic(spinner);
            }
        }
    }

    public static class ReturnItemRow {
        public String productId;
        public String sku;
        public String name;
        public BigDecimal price;
        public int originalQuantity;
        public int returnableQuantity;

        private final SimpleBooleanProperty selected = new SimpleBooleanProperty(true);
        private final SimpleIntegerProperty returnQuantity = new SimpleIntegerProperty(0);

        public ReturnItemRow(SaleItemInfo item) {
            this.productId = item.productId;
            this.sku = item.sku;
            this.name = item.name;
            this.price = item.price;
            this.originalQuantity = item.quantity;
            this.returnableQuantity = item.returnableQuantity;
            this.returnQuantity.set(item.returnableQuantity);
            
            this.returnQuantity.addListener((obs, old, newVal) -> {
                if (newVal.intValue() > 0 && !selected.get()) {
                    selected.set(true);
                }
            });
        }

        public SimpleBooleanProperty selectedProperty() { return selected; }
        public boolean isSelected() { return selected.get(); }
        public SimpleIntegerProperty returnQuantityProperty() { return returnQuantity; }
        public int getReturnQuantity() { return returnQuantity.get(); }
        public void setReturnQuantity(int q) { returnQuantity.set(q); }

        public javafx.beans.property.StringProperty nameProperty() { return new javafx.beans.property.SimpleStringProperty(name); }
        public javafx.beans.property.IntegerProperty originalQuantityProperty() { return new javafx.beans.property.SimpleIntegerProperty(originalQuantity); }
        public javafx.beans.property.StringProperty priceProperty() { return new javafx.beans.property.SimpleStringProperty(NumberFormat.getCurrencyInstance().format(price)); }
        public javafx.beans.property.StringProperty refundAmountProperty() { 
            return new javafx.beans.property.SimpleStringProperty(NumberFormat.getCurrencyInstance().format(getRefundAmount())); 
        }

        public BigDecimal getRefundAmount() {
            if (!isSelected() || getReturnQuantity() <= 0) return BigDecimal.ZERO;
            return price.multiply(BigDecimal.valueOf(getReturnQuantity()));
        }
    }
}

