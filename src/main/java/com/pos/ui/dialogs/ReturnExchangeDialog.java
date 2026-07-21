package com.pos.ui.dialogs;

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
import javafx.stage.Modality;
import javafx.stage.Window;
import com.pos.service.ReturnService;
import com.pos.service.ReturnService.*;
import com.pos.service.UserAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for processing returns and exchanges
 */
public class ReturnExchangeDialog extends Dialog<ReturnExchangeDialog.ReturnResult> {

    private static final Logger logger = LoggerFactory.getLogger(ReturnExchangeDialog.class);
    private final ReturnService returnService;
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

    // UI Components
    private TextField searchField;
    private ListView<SaleInfo> saleListView;
    private TableView<ReturnItemRow> itemsTable;
    private ComboBox<String> reasonCombo;
    private ComboBox<String> refundMethodCombo;
    private Label totalRefundLabel;
    private Button processButton;

    // Data
    private ObservableList<SaleInfo> foundSales;
    private ObservableList<ReturnItemRow> returnItems;
    private SaleInfo selectedSale;

    public ReturnExchangeDialog() {
        this(null);
    }

    public ReturnExchangeDialog(Window owner) {
        this.returnService = ReturnService.getInstance();
        this.foundSales = FXCollections.observableArrayList();
        this.returnItems = FXCollections.observableArrayList();

        // Set owner and modality to keep dialog in same window
        if (owner != null) {
            initOwner(owner);
        }
        initModality(Modality.APPLICATION_MODAL);

        setTitle("Return / Exchange");
        setHeaderText("Process a return or exchange");

        initializeUI();
        setupResultConverter();
    }

    private void initializeUI() {
        // Main container
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        // content.setPrefWidth(800);
        // content.setPrefHeight(600);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.85, 0.85);

        // Search section
        HBox searchBox = createSearchSection();

        // Split pane: Sales list on left, Items on right
        SplitPane splitPane = new SplitPane();
        splitPane.setDividerPositions(0.35);

        // Left: Sales list
        VBox salesPane = createSalesListPane();

        // Right: Items and return details
        VBox detailsPane = createDetailsPane();

        splitPane.getItems().addAll(salesPane, detailsPane);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        content.getChildren().addAll(searchBox, splitPane);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL);

        // Add Process button
        processButton = new Button("Process Return");
        processButton.setStyle(
                "-fx-background-color: #e53935; -fx-text-fill: white; " +
                        "-fx-font-weight: bold; -fx-padding: 10 20;");
        processButton.setDisable(true);
        processButton.setOnAction(e -> processReturn());

        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.getChildren().add(processButton);
        content.getChildren().add(buttonBox);
    }

    private HBox createSearchSection() {
        HBox searchBox = new HBox(10);
        searchBox.setAlignment(Pos.CENTER_LEFT);

        Label searchLabel = new Label("Search Receipt:");
        searchLabel.setStyle("-fx-font-weight: bold;");

        searchField = new TextField();
        searchField.setPromptText("Enter receipt number or date (YYYY-MM-DD)...");
        // searchField.setPrefWidth(300);
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        Button searchBtn = new Button("Search");
        searchBtn.setStyle("-fx-background-color: #1E88E5; -fx-text-fill: white;");
        searchBtn.setOnAction(e -> searchSales());

        searchField.setOnAction(e -> searchSales());

        searchBox.getChildren().addAll(searchLabel, searchField, searchBtn);
        return searchBox;
    }

    private VBox createSalesListPane() {
        VBox pane = new VBox(10);
        pane.setPadding(new Insets(10));

        Label title = new Label("Found Receipts");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        saleListView = new ListView<>(foundSales);
        saleListView.setCellFactory(lv -> new SaleListCell());
        saleListView.getSelectionModel().selectedItemProperty().addListener((obs, old, newVal) -> {
            if (newVal != null) {
                loadSaleItems(newVal);
            }
        });
        VBox.setVgrow(saleListView, Priority.ALWAYS);

        pane.getChildren().addAll(title, saleListView);
        return pane;
    }

    private VBox createDetailsPane() {
        VBox pane = new VBox(10);
        pane.setPadding(new Insets(10));

        Label title = new Label("Items to Return");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        // Items table
        itemsTable = new TableView<>(returnItems);
        itemsTable.setEditable(true);
        setupItemsTable();
        VBox.setVgrow(itemsTable, Priority.ALWAYS);

        // Return options
        GridPane optionsGrid = new GridPane();
        optionsGrid.setHgap(10);
        optionsGrid.setVgap(10);

        Label reasonLabel = new Label("Reason:");
        reasonCombo = new ComboBox<>();
        reasonCombo.getItems().addAll(
                "Defective/Damaged",
                "Wrong Item",
                "Customer Changed Mind",
                "Size/Fit Issue",
                "Quality Issue",
                "Other");
        reasonCombo.setValue("Customer Changed Mind");
        // reasonCombo.setPrefWidth(200);
        reasonCombo.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(reasonCombo, Priority.ALWAYS);

        Label refundLabel = new Label("Refund Method:");
        refundMethodCombo = new ComboBox<>();
        refundMethodCombo.getItems().addAll("CASH", "CARD", "STORE_CREDIT");
        refundMethodCombo.setValue("CASH");
        // refundMethodCombo.setPrefWidth(200);
        refundMethodCombo.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(refundMethodCombo, Priority.ALWAYS);

        optionsGrid.add(reasonLabel, 0, 0);
        optionsGrid.add(reasonCombo, 1, 0);
        optionsGrid.add(refundLabel, 0, 1);
        optionsGrid.add(refundMethodCombo, 1, 1);

        // Total refund
        HBox totalBox = new HBox(10);
        totalBox.setAlignment(Pos.CENTER_RIGHT);
        totalBox.setPadding(new Insets(10, 0, 0, 0));

        Label totalLabel = new Label("Total Refund:");
        totalLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        totalRefundLabel = new Label("$0.00");
        totalRefundLabel.setStyle(
                "-fx-font-weight: bold; -fx-font-size: 20px; -fx-text-fill: #e53935;");

        totalBox.getChildren().addAll(totalLabel, totalRefundLabel);

        pane.getChildren().addAll(title, itemsTable, optionsGrid, totalBox);
        return pane;
    }

    @SuppressWarnings("unchecked")
    private void setupItemsTable() {
        // Select column
        TableColumn<ReturnItemRow, Boolean> selectCol = new TableColumn<>("Return");
        selectCol.setCellValueFactory(data -> data.getValue().selectedProperty());
        selectCol.setCellFactory(CheckBoxTableCell.forTableColumn(selectCol));
        selectCol.setEditable(true);
        selectCol.setPrefWidth(60);

        // Name column
        TableColumn<ReturnItemRow, String> nameCol = new TableColumn<>("Item");
        nameCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().name));
        nameCol.setPrefWidth(200);

        // Original Qty column
        TableColumn<ReturnItemRow, Number> origQtyCol = new TableColumn<>("Orig Qty");
        origQtyCol.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().originalQuantity));
        origQtyCol.setPrefWidth(70);

        // Returnable column
        TableColumn<ReturnItemRow, Number> returnableCol = new TableColumn<>("Returnable");
        returnableCol.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().returnableQuantity));
        returnableCol.setPrefWidth(80);

        // Return Qty column (editable spinner)
        TableColumn<ReturnItemRow, Integer> returnQtyCol = new TableColumn<>("Return Qty");
        returnQtyCol.setCellValueFactory(data -> data.getValue().returnQuantityProperty().asObject());
        returnQtyCol.setCellFactory(col -> new SpinnerTableCell());
        returnQtyCol.setPrefWidth(100);
        returnQtyCol.setEditable(true);

        // Price column
        TableColumn<ReturnItemRow, String> priceCol = new TableColumn<>("Price");
        priceCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                currencyFormat.format(data.getValue().price)));
        priceCol.setPrefWidth(80);

        // Refund column
        TableColumn<ReturnItemRow, String> refundCol = new TableColumn<>("Refund");
        refundCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                currencyFormat.format(data.getValue().getRefundAmount())));
        refundCol.setPrefWidth(90);

        itemsTable.getColumns().addAll(selectCol, nameCol, origQtyCol, returnableCol,
                returnQtyCol, priceCol, refundCol);
    }

    private void searchSales() {
        String query = searchField.getText().trim();
        if (query.isEmpty())
            return;

        new Thread(() -> {
            List<SaleInfo> sales = returnService.searchSales(query);
            Platform.runLater(() -> {
                foundSales.clear();
                foundSales.addAll(sales);
                returnItems.clear();
                updateTotalRefund();

                if (sales.isEmpty()) {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("No Results");
                    alert.setHeaderText("No receipts found");
                    alert.setContentText("No receipts match your search criteria.");
                    alert.initOwner(getDialogPane().getScene().getWindow());
                    alert.initModality(Modality.APPLICATION_MODAL);
                    alert.showAndWait();
                }
            });
        }).start();
    }

    private void loadSaleItems(SaleInfo sale) {
        selectedSale = sale;

        new Thread(() -> {
            List<SaleItemInfo> items = returnService.getSaleItems(sale.saleId);
            Platform.runLater(() -> {
                returnItems.clear();
                for (SaleItemInfo item : items) {
                    if (item.returnableQuantity > 0) {
                        returnItems.add(new ReturnItemRow(item));
                    }
                }
                updateTotalRefund();
            });
        }).start();
    }

    private void updateTotalRefund() {
        BigDecimal total = BigDecimal.ZERO;
        boolean hasSelected = false;

        for (ReturnItemRow item : returnItems) {
            if (item.isSelected() && item.getReturnQuantity() > 0) {
                total = total.add(item.getRefundAmount());
                hasSelected = true;
            }
        }

        totalRefundLabel.setText(currencyFormat.format(total));
        processButton.setDisable(!hasSelected);
    }

    private void processReturn() {
        if (selectedSale == null)
            return;

        List<ReturnService.ReturnItem> items = new ArrayList<>();
        for (ReturnItemRow row : returnItems) {
            if (row.isSelected() && row.getReturnQuantity() > 0) {
                ReturnService.ReturnItem item = new ReturnService.ReturnItem();
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
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("No Items Selected");
            alert.setHeaderText("Please select items to return");
            alert.initOwner(getDialogPane().getScene().getWindow());
            alert.initModality(Modality.APPLICATION_MODAL);
            alert.showAndWait();
            return;
        }

        String reason = reasonCombo.getValue();
        String refundMethod = refundMethodCombo.getValue();
        String cashierName = UserAuthService.getInstance().getCurrentUserName();

        ReturnService.ReturnResult result = returnService.processReturn(
                selectedSale.saleId, items, reason, refundMethod, cashierName);

        if (result.success) {
            ReturnResult dialogResult = new ReturnResult();
            dialogResult.success = true;
            dialogResult.refundId = result.refundId;
            dialogResult.refundAmount = result.refundAmount;
            dialogResult.refundMethod = refundMethod;

            setResult(dialogResult);
            close();
        } else {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Return Failed");
            alert.setHeaderText("Failed to process return");
            alert.setContentText(result.errorMessage);
            alert.initOwner(getDialogPane().getScene().getWindow());
            alert.initModality(Modality.APPLICATION_MODAL);
            alert.showAndWait();
        }
    }

    private void setupResultConverter() {
        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.CANCEL) {
                return null;
            }
            return getResult();
        });
    }

    // Custom cell for sale list
    private class SaleListCell extends ListCell<SaleInfo> {
        @Override
        protected void updateItem(SaleInfo sale, boolean empty) {
            super.updateItem(sale, empty);

            if (empty || sale == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            VBox content = new VBox(3);
            content.setPadding(new Insets(5));

            Label idLabel = new Label(sale.saleId);
            idLabel.setStyle("-fx-font-weight: bold;");

            Label detailLabel = new Label(
                    currencyFormat.format(sale.total) + " | " +
                            sale.itemCount + " items | " +
                            sale.paymentMethod);
            detailLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

            Label dateLabel = new Label(
                    sale.timestamp != null ? sale.timestamp.substring(0, Math.min(10, sale.timestamp.length())) : "");
            dateLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 10px;");

            content.getChildren().addAll(idLabel, detailLabel, dateLabel);

            if (sale.voided) {
                content.setStyle("-fx-background-color: #ffebee;");
                Label voidLabel = new Label("VOIDED");
                voidLabel.setStyle("-fx-text-fill: #e53935; -fx-font-weight: bold;");
                content.getChildren().add(voidLabel);
            }

            setGraphic(content);
        }
    }

    // Spinner cell for return quantity
    private class SpinnerTableCell extends TableCell<ReturnItemRow, Integer> {
        private Spinner<Integer> spinner;

        public SpinnerTableCell() {
            spinner = new Spinner<>();
            spinner.setEditable(true);
            spinner.setPrefWidth(80);

            spinner.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (getTableRow() != null && getTableRow().getItem() != null) {
                    ReturnItemRow item = getTableRow().getItem();
                    item.setReturnQuantity(newVal);
                    updateTotalRefund();
                    getTableView().refresh();
                }
            });
        }

        @Override
        protected void updateItem(Integer value, boolean empty) {
            super.updateItem(value, empty);

            if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                setGraphic(null);
                return;
            }

            ReturnItemRow item = getTableRow().getItem();
            SpinnerValueFactory<Integer> factory = new SpinnerValueFactory.IntegerSpinnerValueFactory(
                    0, item.returnableQuantity, item.getReturnQuantity());
            spinner.setValueFactory(factory);
            setGraphic(spinner);
        }
    }

    // Row data class
    public static class ReturnItemRow {
        public String productId;
        public String sku;
        public String name;
        public BigDecimal price;
        public int originalQuantity;
        public int returnableQuantity;

        private SimpleBooleanProperty selected = new SimpleBooleanProperty(false);
        private SimpleIntegerProperty returnQuantity = new SimpleIntegerProperty(0);

        public ReturnItemRow(SaleItemInfo item) {
            this.productId = item.productId;
            this.sku = item.sku;
            this.name = item.name;
            this.price = item.price;
            this.originalQuantity = item.quantity;
            this.returnableQuantity = item.returnableQuantity;

            // Auto-select and set return qty to returnable
            this.selected.set(true);
            this.returnQuantity.set(item.returnableQuantity);

            // Update selection when return qty changes
            this.returnQuantity.addListener((obs, old, newVal) -> {
                if (newVal.intValue() > 0 && !selected.get()) {
                    selected.set(true);
                }
            });
        }

        public boolean isSelected() {
            return selected.get();
        }

        public SimpleBooleanProperty selectedProperty() {
            return selected;
        }

        public int getReturnQuantity() {
            return returnQuantity.get();
        }

        public void setReturnQuantity(int qty) {
            returnQuantity.set(qty);
        }

        public SimpleIntegerProperty returnQuantityProperty() {
            return returnQuantity;
        }

        public BigDecimal getRefundAmount() {
            if (!isSelected() || getReturnQuantity() <= 0)
                return BigDecimal.ZERO;
            return price.multiply(BigDecimal.valueOf(getReturnQuantity()));
        }
    }

    // Result class
    public static class ReturnResult {
        public boolean success;
        public String refundId;
        public BigDecimal refundAmount;
        public String refundMethod;
    }
}
