package com.pos.ui.dialogs;

import com.pos.model.Refund;
import com.pos.service.RoleBasedAccessService;
import com.pos.service.SaleHistoryService;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.TouchTextArea;
import com.pos.util.DialogHelper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Dialog for processing refunds/returns
 */
public class RefundDialog extends Dialog<RefundDialog.RefundResult> {

    private static final Logger logger = LoggerFactory.getLogger(RefundDialog.class);

    private SaleHistoryService.SaleRecord originalSale;
    private TableView<RefundItemRow> itemsTable;
    private ObservableList<RefundItemRow> refundItems;
    private TouchTextArea reasonField;
    private ComboBox<String> refundMethodCombo;
    private Label totalRefundLabel;
    private NumberFormat currencyFormatter;

    /**
     * Row data for refund items table
     */
    public static class RefundItemRow {
        private String productId;
        private String sku;
        private String name;
        private BigDecimal originalPrice;
        private int originalQuantity;
        private int refundQuantity;
        private BigDecimal refundAmount;

        public RefundItemRow(SaleHistoryService.SaleItemRecord item) {
            this.productId = item.productId;
            this.sku = item.sku;
            this.name = item.name;
            this.originalPrice = item.price;
            this.originalQuantity = item.quantity;
            this.refundQuantity = 0; // Start with 0
            this.refundAmount = BigDecimal.ZERO;
        }

        // Getters and setters
        public String getProductId() {
            return productId;
        }

        public String getSku() {
            return sku;
        }

        public String getName() {
            return name;
        }

        public BigDecimal getOriginalPrice() {
            return originalPrice;
        }

        public int getOriginalQuantity() {
            return originalQuantity;
        }

        public int getRefundQuantity() {
            return refundQuantity;
        }

        public void setRefundQuantity(int refundQuantity) {
            this.refundQuantity = Math.min(Math.max(0, refundQuantity), originalQuantity);
            this.refundAmount = originalPrice.multiply(BigDecimal.valueOf(this.refundQuantity));
        }

        public BigDecimal getRefundAmount() {
            return refundAmount;
        }
    }

    public RefundDialog(SaleHistoryService.SaleRecord sale) {
        // Permission check - Manager+ required
        RoleBasedAccessService rbacService = RoleBasedAccessService.getInstance();
        if (!rbacService.hasPermission(RoleBasedAccessService.PERMISSION_PROCESS_REFUND)) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Permission Denied");
            alert.setHeaderText(null);
            alert.setContentText("You do not have permission to process refunds. Manager or Admin role required.");
            DialogHelper.setAlertOwner(alert, null);
            alert.showAndWait();
            // Return early - dialog will be closed
            return;
        }

        this.originalSale = sale;
        this.refundItems = FXCollections.observableArrayList();
        this.currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US);

        initializeDialog();
    }

    private void initializeDialog() {
        setTitle("Process Refund");
        setHeaderText("Refund for Sale #" + originalSale.saleId);
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
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        // content.setPrefWidth(800);
        // content.setPrefHeight(600);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.7, 0.85);

        // Original sale info
        VBox saleInfoBox = createSaleInfoSection();

        // Items table
        VBox itemsBox = createItemsSection();

        // Refund details
        VBox refundDetailsBox = createRefundDetailsSection();

        content.getChildren().addAll(saleInfoBox, itemsBox, refundDetailsBox);

        // Set dialog content
        getDialogPane().setContent(content);

        // Buttons
        ButtonType processButtonType = new ButtonType("Process Refund", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(processButtonType, cancelButtonType);

        // Style
        getDialogPane().setStyle("-fx-background-color: #f5f7fa;");

        // Set result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == processButtonType) {
                return processRefund();
            }
            return null;
        });

        // Set default button
        Button processButton = (Button) getDialogPane().lookupButton(processButtonType);
        processButton.setDefaultButton(true);
    }

    private VBox createSaleInfoSection() {
        VBox infoBox = new VBox(10);
        infoBox.setStyle("-fx-background-color: white; -fx-padding: 15; -fx-background-radius: 5;");

        Label titleLabel = new Label("Original Sale Information");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 0, 0, 0));

        // Add column constraints
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setMinWidth(160);
        col1.setPrefWidth(180);
        grid.getColumnConstraints().add(col1);

        grid.add(new Label("Sale ID:"), 0, 0);
        grid.add(new Label(originalSale.saleId), 1, 0);

        grid.add(new Label("Date:"), 0, 1);
        String dateStr = originalSale.saleDate != null
                ? originalSale.saleDate.format(DateTimeFormatter.ofPattern("MM/dd/yyyy"))
                : "N/A";
        grid.add(new Label(dateStr), 1, 1);

        grid.add(new Label("Original Total:"), 0, 2);
        grid.add(new Label(currencyFormatter.format(originalSale.total)), 1, 2);

        grid.add(new Label("Payment Method:"), 0, 3);
        grid.add(new Label(originalSale.paymentMethod != null ? originalSale.paymentMethod : "N/A"), 1, 3);

        infoBox.getChildren().addAll(titleLabel, grid);
        return infoBox;
    }

    private VBox createItemsSection() {
        VBox itemsBox = new VBox(10);
        itemsBox.setStyle("-fx-background-color: white; -fx-padding: 15; -fx-background-radius: 5;");

        Label titleLabel = new Label("Select Items to Refund");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        // Initialize items table
        itemsTable = new TableView<>();
        itemsTable.setItems(refundItems);
        itemsTable.setPrefHeight(300); // Increased from 250
        itemsTable.setMinHeight(200);

        // Create rows from original sale items
        for (SaleHistoryService.SaleItemRecord item : originalSale.items) {
            refundItems.add(new RefundItemRow(item));
        }

        setupItemsColumns();

        itemsBox.getChildren().addAll(titleLabel, itemsTable);
        return itemsBox;
    }

    private void setupItemsColumns() {
        itemsTable.getColumns().clear();

        // Product Name
        TableColumn<RefundItemRow, String> nameCol = new TableColumn<>("Item");
        nameCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getName()));
        nameCol.setPrefWidth(200);

        // Original Quantity
        TableColumn<RefundItemRow, Integer> origQtyCol = new TableColumn<>("Original Qty");
        origQtyCol.setCellValueFactory(
                data -> new javafx.beans.property.SimpleIntegerProperty(data.getValue().getOriginalQuantity())
                        .asObject());
        origQtyCol.setPrefWidth(100);

        // Refund Quantity (Editable)
        TableColumn<RefundItemRow, Integer> refundQtyCol = new TableColumn<>("Refund Qty");
        refundQtyCol.setCellValueFactory(
                data -> new javafx.beans.property.SimpleIntegerProperty(data.getValue().getRefundQuantity())
                        .asObject());
        refundQtyCol.setCellFactory(column -> new TableCell<>() {
            private final Spinner<Integer> spinner = new Spinner<>(0, 0, 0);

            {
                spinner.setEditable(true);
                spinner.valueProperty().addListener((obs, oldVal, newVal) -> {
                    RefundItemRow row = getTableRow().getItem();
                    if (row != null && newVal != null) {
                        row.setRefundQuantity(newVal);
                        updateTotal();
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
                    spinner.setValueFactory(new javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory(
                            0, row.getOriginalQuantity(), row.getRefundQuantity()));
                    setGraphic(spinner);
                }
            }
        });
        refundQtyCol.setPrefWidth(120);

        // Price
        TableColumn<RefundItemRow, String> priceCol = new TableColumn<>("Price");
        priceCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                currencyFormatter.format(data.getValue().getOriginalPrice())));
        priceCol.setPrefWidth(100);

        // Refund Amount
        TableColumn<RefundItemRow, String> refundAmountCol = new TableColumn<>("Refund Amount");
        refundAmountCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                currencyFormatter.format(data.getValue().getRefundAmount())));
        refundAmountCol.setPrefWidth(120);

        itemsTable.getColumns().addAll(nameCol, origQtyCol, refundQtyCol, priceCol, refundAmountCol);
    }

    private VBox createRefundDetailsSection() {
        VBox detailsBox = new VBox(10);
        detailsBox.setStyle("-fx-background-color: white; -fx-padding: 15; -fx-background-radius: 5;");

        Label titleLabel = new Label("Refund Details");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(15);
        grid.setPadding(new Insets(10, 0, 0, 0));

        // Add column constraints
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setMinWidth(160);
        col1.setPrefWidth(180);
        grid.getColumnConstraints().add(col1);

        // Refund method
        grid.add(new Label("Refund Method:"), 0, 0);
        refundMethodCombo = new ComboBox<>();
        refundMethodCombo.getItems().addAll("CASH", "CARD", "STORE_CREDIT");
        refundMethodCombo.setValue(originalSale.paymentMethod != null ? originalSale.paymentMethod : "CASH");
        grid.add(refundMethodCombo, 1, 0);

        // Reason
        grid.add(new Label("Reason (Required):"), 0, 1);
        reasonField = TouchScreenComponents.createTouchOnlyTextArea(
                "Enter reason for refund (e.g., 'Defective item', 'Customer request', 'Wrong item')");
        reasonField.setPrefRowCount(2); // Reduced from 3
        reasonField.setMaxWidth(Double.MAX_VALUE);
        reasonField.setWrapText(true);
        grid.add(reasonField, 1, 1);

        // Total refund
        grid.add(new Label("Total Refund:"), 0, 2);
        totalRefundLabel = new Label("$0.00");
        totalRefundLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #f44336;");
        grid.add(totalRefundLabel, 1, 2);

        detailsBox.getChildren().addAll(titleLabel, grid);

        // Update total when items change
        updateTotal();

        return detailsBox;
    }

    private void updateTotal() {
        BigDecimal totalRefund = refundItems.stream()
                .map(RefundItemRow::getRefundAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Calculate proportional tax
        if (totalRefund.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal originalSubtotal = originalSale.subtotal;
            BigDecimal proportion = totalRefund.divide(originalSubtotal, 4, java.math.RoundingMode.HALF_UP);
            BigDecimal refundTax = originalSale.tax.multiply(proportion).setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal totalWithTax = totalRefund.add(refundTax);
            totalRefundLabel.setText(currencyFormatter.format(totalWithTax));
        } else {
            totalRefundLabel.setText(currencyFormatter.format(BigDecimal.ZERO));
        }
    }

    private RefundResult processRefund() {
        // Validate
        if (reasonField.getText() == null || reasonField.getText().trim().isEmpty()) {
            showError("Please enter a reason for the refund");
            return null;
        }

        // Check if any items are selected
        boolean hasRefundItems = refundItems.stream()
                .anyMatch(item -> item.getRefundQuantity() > 0);

        if (!hasRefundItems) {
            showError("Please select at least one item to refund");
            return null;
        }

        // Create refund result
        RefundResult result = new RefundResult();
        result.refund = new Refund();
        result.refund.setRefundId(com.pos.service.RefundService.getInstance().generateRefundId());
        result.refund.setOriginalSaleId(originalSale.saleId);
        result.refund.setPaymentMethod(originalSale.paymentMethod);
        result.refund.setRefundMethod(refundMethodCombo.getValue());
        result.refund.setReason(reasonField.getText().trim());
        result.refund.setCashierName(com.pos.service.UserAuthService.getInstance().getCurrentUserName());
        result.refund.setPosUserId(com.pos.service.UserAuthService.getInstance().getCurrentPosUserId());
        result.refund.setTimestamp(Instant.now().atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT));

        // Add refund items
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
                result.refund.getItems().add(item);
            }
        }

        return result;
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
     * Result class for refund processing
     */
    public static class RefundResult {
        public Refund refund;
    }
}
