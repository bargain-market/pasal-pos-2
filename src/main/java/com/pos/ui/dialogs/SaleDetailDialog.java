package com.pos.ui.dialogs;

import com.pos.api.dto.SaleSubmission;
import com.pos.model.Refund;
import com.pos.service.SaleHistoryService;
import com.pos.service.SettingsService;
import com.pos.util.DialogHelper;
import com.pos.util.ReceiptPrintHelper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
 * Dialog to display sale details and allow receipt reprint
 */
public class SaleDetailDialog extends Dialog<Void> {

    private static final Logger logger = LoggerFactory.getLogger(SaleDetailDialog.class);

    private SaleHistoryService.SaleRecord sale;
    private NumberFormat currencyFormatter;

    public SaleDetailDialog(SaleHistoryService.SaleRecord sale) {
        this.sale = sale;
        this.currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US);

        initializeDialog();
    }

    private boolean isRefund() {
        return sale != null && sale.isRefund();
    }

    private String transactionLabel() {
        return isRefund() ? "Refund" : "Sale";
    }

    private void initializeDialog() {
        setTitle(transactionLabel() + " Details");
        setHeaderText(transactionLabel() + " #" + sale.saleId);
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
        // content.setPrefWidth(700);
        // content.setPrefHeight(600);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.75, 0.85);

        // Sale information section
        VBox saleInfoBox = createSaleInfoSection();

        // Items table
        TableView<SaleHistoryService.SaleItemRecord> itemsTable = createItemsTable();

        // Totals section
        VBox totalsBox = createTotalsSection();

        // Buttons
        HBox buttonBox = createButtonBox();

        content.getChildren().addAll(saleInfoBox, itemsTable, totalsBox, buttonBox);

        // Set dialog content
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        // Style
        getDialogPane().setStyle("-fx-background-color: #f5f7fa;");
    }

    private VBox createSaleInfoSection() {
        VBox infoBox = new VBox(10);
        infoBox.setStyle("-fx-background-color: white; -fx-padding: 15; -fx-background-radius: 5;");

        Label titleLabel = new Label(transactionLabel() + " Information");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 0, 0, 0));

        int row = 0;
        grid.add(new Label(isRefund() ? "Refund ID:" : "Sale ID:"), 0, row);
        grid.add(new Label(sale.saleId), 1, row++);

        String dateStr = "N/A";
        if (sale.saleDate != null) {
            dateStr = sale.saleDate.format(DateTimeFormatter.ofPattern("MM/dd/yyyy"));
        } else if (sale.timestamp != null) {
            try {
                Instant instant = Instant.parse(sale.timestamp);
                dateStr = instant.atOffset(ZoneOffset.UTC).toLocalDateTime()
                        .format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"));
            } catch (Exception e) {
                dateStr = sale.timestamp;
            }
        }
        grid.add(new Label("Date:"), 0, row);
        grid.add(new Label(dateStr), 1, row++);

        grid.add(new Label("Cashier:"), 0, row);
        grid.add(new Label(sale.cashierName != null ? sale.cashierName : "N/A"), 1, row++);

        grid.add(new Label(isRefund() ? "Refund Method:" : "Payment Method:"), 0, row);
        Label paymentLabel = new Label(sale.paymentMethod != null ? sale.paymentMethod : "N/A");
        paymentLabel.setStyle("-fx-font-weight: bold;");
        grid.add(paymentLabel, 1, row++);

        if (isRefund()) {
            grid.add(new Label("Original Sale:"), 0, row);
            grid.add(new Label(sale.originalSaleId != null ? sale.originalSaleId : "N/A"), 1, row++);

            grid.add(new Label("Original Payment:"), 0, row);
            grid.add(new Label(sale.originalPaymentMethod != null ? sale.originalPaymentMethod : "N/A"), 1, row++);

            grid.add(new Label("Reason:"), 0, row);
            Label reasonLabel = new Label(sale.refundReason != null ? sale.refundReason : "N/A");
            reasonLabel.setWrapText(true);
            reasonLabel.setMaxWidth(400);
            grid.add(reasonLabel, 1, row++);
        }

        grid.add(new Label("Synced:"), 0, row);
        Label syncedLabel = new Label(sale.synced ? "Yes" : "No");
        syncedLabel.setStyle(sale.synced ? "-fx-text-fill: #4CAF50; -fx-font-weight: bold;"
                : "-fx-text-fill: #f44336; -fx-font-weight: bold;");
        grid.add(syncedLabel, 1, row++);

        if (!sale.synced && sale.syncError != null && !sale.syncError.isEmpty()) {
            grid.add(new Label("Sync Error:"), 0, row);
            Label errorLabel = new Label(sale.syncError);
            errorLabel.setStyle("-fx-text-fill: #cc0000;");
            errorLabel.setWrapText(true);
            errorLabel.setMaxWidth(400);
            grid.add(errorLabel, 1, row);
        }

        infoBox.getChildren().addAll(titleLabel, grid);

        return infoBox;
    }

    private TableView<SaleHistoryService.SaleItemRecord> createItemsTable() {
        TableView<SaleHistoryService.SaleItemRecord> table = new TableView<>();
        table.setStyle("-fx-background-color: white;");
        table.setPrefHeight(250);

        // Columns
        TableColumn<SaleHistoryService.SaleItemRecord, String> nameCol = new TableColumn<>("Item");
        nameCol.setCellValueFactory(param -> {
            SaleHistoryService.SaleItemRecord item = param.getValue();
            return item != null ? new javafx.beans.property.SimpleStringProperty(item.name) : null;
        });
        nameCol.setPrefWidth(300);

        TableColumn<SaleHistoryService.SaleItemRecord, String> skuCol = new TableColumn<>("SKU");
        skuCol.setCellValueFactory(param -> {
            SaleHistoryService.SaleItemRecord item = param.getValue();
            return item != null ? new javafx.beans.property.SimpleStringProperty(item.sku) : null;
        });
        skuCol.setPrefWidth(150);

        TableColumn<SaleHistoryService.SaleItemRecord, Integer> qtyCol = new TableColumn<>(isRefund() ? "Refund Qty" : "Qty");
        qtyCol.setCellValueFactory(param -> {
            SaleHistoryService.SaleItemRecord item = param.getValue();
            return item != null ? new javafx.beans.property.SimpleObjectProperty<>(item.quantity) : null;
        });
        qtyCol.setPrefWidth(80);

        TableColumn<SaleHistoryService.SaleItemRecord, BigDecimal> priceCol = new TableColumn<>("Price");
        priceCol.setCellValueFactory(param -> {
            SaleHistoryService.SaleItemRecord item = param.getValue();
            return item != null && item.price != null ? new javafx.beans.property.SimpleObjectProperty<>(item.price)
                    : null;
        });
        priceCol.setCellFactory(col -> new TableCell<SaleHistoryService.SaleItemRecord, BigDecimal>() {
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
        priceCol.setPrefWidth(100);

        TableColumn<SaleHistoryService.SaleItemRecord, BigDecimal> totalCol = new TableColumn<>(isRefund() ? "Refund" : "Total");
        totalCol.setCellValueFactory(param -> {
            SaleHistoryService.SaleItemRecord item = param.getValue();
            return item != null && item.subtotal != null
                    ? new javafx.beans.property.SimpleObjectProperty<>(item.subtotal)
                    : null;
        });
        totalCol.setCellFactory(col -> new TableCell<SaleHistoryService.SaleItemRecord, BigDecimal>() {
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
        totalCol.setPrefWidth(100);

        table.getColumns().add(nameCol);
        table.getColumns().add(skuCol);
        table.getColumns().add(qtyCol);
        table.getColumns().add(priceCol);
        table.getColumns().add(totalCol);
        table.getItems().addAll(sale.items);
        VBox.setVgrow(table, Priority.ALWAYS);
        return table;
    }

    private VBox createTotalsSection() {
        VBox totalsBox = new VBox(5);
        totalsBox.setStyle("-fx-background-color: white; -fx-padding: 15; -fx-background-radius: 5;");
        totalsBox.setAlignment(Pos.CENTER_RIGHT);

        HBox subtotalBox = new HBox(10);
        subtotalBox.setAlignment(Pos.CENTER_RIGHT);
        subtotalBox.getChildren().addAll(
                new Label(isRefund() ? "Refund Amount:" : "Subtotal:"),
                new Label(currencyFormatter.format(sale.subtotal)));

        if (!isRefund() && sale.discount.compareTo(BigDecimal.ZERO) > 0) {
            HBox discountBox = new HBox(10);
            discountBox.setAlignment(Pos.CENTER_RIGHT);
            discountBox.getChildren().addAll(
                    new Label("Discount:"),
                    new Label("-" + currencyFormatter.format(sale.discount)));
            totalsBox.getChildren().add(discountBox);
        }

        HBox taxBox = new HBox(10);
        taxBox.setAlignment(Pos.CENTER_RIGHT);
        taxBox.getChildren().addAll(
                new Label(isRefund() ? "Refund Tax:" : "Tax:"),
                new Label(currencyFormatter.format(sale.tax)));

        Separator separator = new Separator();

        HBox totalBox = new HBox(10);
        totalBox.setAlignment(Pos.CENTER_RIGHT);
        Label totalLabel = new Label(isRefund() ? "Total Refund:" : "Total:");
        totalLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Label totalValue = new Label(currencyFormatter.format(sale.total));
        totalValue.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        totalBox.getChildren().addAll(totalLabel, totalValue);

        totalsBox.getChildren().addAll(subtotalBox, taxBox, separator, totalBox);

        Separator paymentSeparator = new Separator();
        paymentSeparator.setPadding(new Insets(5, 0, 5, 0));
        totalsBox.getChildren().add(paymentSeparator);

        HBox paymentMethodBox = new HBox(10);
        paymentMethodBox.setAlignment(Pos.CENTER_RIGHT);
        Label paymentMethodLabel = new Label(isRefund() ? "Refund Method:" : "Payment Method:");
        Label paymentMethodValue = new Label(sale.paymentMethod != null ? sale.paymentMethod : "N/A");
        paymentMethodValue.setStyle("-fx-font-weight: bold;");
        paymentMethodBox.getChildren().addAll(paymentMethodLabel, paymentMethodValue);
        totalsBox.getChildren().add(paymentMethodBox);

        if (isRefund()) {
            HBox originalPaymentBox = new HBox(10);
            originalPaymentBox.setAlignment(Pos.CENTER_RIGHT);
            originalPaymentBox.getChildren().addAll(
                    new Label("Original Payment:"),
                    new Label(sale.originalPaymentMethod != null ? sale.originalPaymentMethod : "N/A"));
            totalsBox.getChildren().add(originalPaymentBox);
        } else if ("CASH".equals(sale.paymentMethod)) {
            if (sale.amountReceived != null && sale.amountReceived.compareTo(BigDecimal.ZERO) > 0) {
                HBox cashReceivedBox = new HBox(10);
                cashReceivedBox.setAlignment(Pos.CENTER_RIGHT);
                cashReceivedBox.getChildren().addAll(
                        new Label("Cash Received:"),
                        new Label(currencyFormatter.format(sale.amountReceived)));
                totalsBox.getChildren().add(cashReceivedBox);
            }

            if (sale.change != null && sale.change.compareTo(BigDecimal.ZERO) > 0) {
                HBox changeBox = new HBox(10);
                changeBox.setAlignment(Pos.CENTER_RIGHT);
                Label changeLabel = new Label("Change:");
                Label changeValue = new Label(currencyFormatter.format(sale.change));
                changeValue.setStyle("-fx-text-fill: #4CAF50; -fx-font-weight: bold;");
                changeBox.getChildren().addAll(changeLabel, changeValue);
                totalsBox.getChildren().add(changeBox);
            }
        }

        return totalsBox;
    }

    private HBox createButtonBox() {
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.setPadding(new Insets(10, 0, 0, 0));

        Button viewReceiptButton = new Button("View Receipt");
        viewReceiptButton.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-weight: bold;");
        viewReceiptButton.setOnAction(e -> viewReceipt());

        Button reprintButton = new Button("Reprint Receipt");
        reprintButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        reprintButton.setOnAction(e -> reprintReceipt());

        buttonBox.getChildren().addAll(viewReceiptButton, reprintButton);

        return buttonBox;
    }

    private void viewReceipt() {
        new Thread(() -> {
            try {
                SaleHistoryService saleHistoryService = SaleHistoryService.getInstance();
                String receiptText;
                String previewTitle;
                String previewHeader;
                if (isRefund()) {
                    Refund refund = saleHistoryService.reconstructRefund(sale);
                    receiptText = generateRefundReceiptText(refund);
                    previewTitle = "Refund Receipt Preview";
                    previewHeader = "Receipt for Refund #" + sale.saleId;
                } else {
                    SaleSubmission submission = saleHistoryService.reconstructSaleSubmission(sale);
                    receiptText = generateReceiptText(submission);
                    previewTitle = "Sale Receipt Preview";
                    previewHeader = "Receipt for Sale #" + sale.saleId;
                }

                javafx.application.Platform.runLater(() -> {
                    ReceiptPrintHelper.showReceiptPreview(
                            receiptText,
                            getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
                            previewTitle,
                            previewHeader);
                });
            } catch (Exception e) {
                logger.error("Error viewing receipt", e);
                javafx.application.Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Error");
                    alert.setHeaderText("Failed to generate receipt preview");
                    alert.setContentText("Error: " + e.getMessage());
                    DialogHelper.setAlertOwner(alert,
                            getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
                    alert.showAndWait();
                });
            }
        }).start();
    }

    private void reprintReceipt() {
        new Thread(() -> {
            try {
                SaleHistoryService saleHistoryService = SaleHistoryService.getInstance();
                String receiptText;
                if (isRefund()) {
                    Refund refund = saleHistoryService.reconstructRefund(sale);
                    receiptText = generateRefundReceiptText(refund);
                } else {
                    SaleSubmission submission = saleHistoryService.reconstructSaleSubmission(sale);
                    receiptText = generateReceiptText(submission);
                }

                javafx.application.Platform.runLater(() -> {
                    ReceiptPrintHelper.printReceiptWithPDF(receiptText, sale.saleId,
                            getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
                });
            } catch (Exception e) {
                logger.error("Error reprinting receipt", e);
                javafx.application.Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Error");
                    alert.setHeaderText("Failed to reprint receipt");
                    alert.setContentText("Error: " + e.getMessage());
                    DialogHelper.setAlertOwner(alert,
                            getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
                    alert.showAndWait();
                });
            }
        }).start();
    }

    private String generateReceiptText(SaleSubmission submission) {
        StringBuilder sb = new StringBuilder();

        // Get store settings
        SettingsService settingsService = SettingsService.getInstance();
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
                Instant instant;
                try {
                    instant = Instant.parse(submission.timestamp);
                } catch (Exception e) {
                    // Fallback: try parsing as long timestamp
                    try {
                        long epoch = Long.parseLong(submission.timestamp);
                        instant = Instant.ofEpochSecond(epoch);
                    } catch (NumberFormatException nfe) {
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

    private String generateRefundReceiptText(Refund refund) {
        StringBuilder sb = new StringBuilder();

        SettingsService settingsService = SettingsService.getInstance();
        String storeName = settingsService.getStoreName();
        String storeAddress = settingsService.getStoreAddress();
        String storePhone = settingsService.getStorePhone();
        String header = settingsService.getReceiptHeader();
        String footer = settingsService.getReceiptFooter();

        sb.append(centerText(storeName, 42)).append("\n");
        if (!storeAddress.isEmpty()) {
            sb.append(centerText(storeAddress, 42)).append("\n");
        }
        if (!storePhone.isEmpty()) {
            sb.append(centerText(storePhone, 42)).append("\n");
        }
        sb.append("\n");

        if (!header.isEmpty()) {
            sb.append(centerText(header, 42)).append("\n");
        }
        sb.append(centerText("*** REFUND RECEIPT ***", 42)).append("\n");
        sb.append("-".repeat(42)).append("\n");

        sb.append("Refund ID: ").append(refund.getRefundId()).append("\n");
        sb.append("Original Sale: ").append(refund.getOriginalSaleId()).append("\n");
        if (refund.getTimestamp() != null) {
            try {
                Instant instant = Instant.parse(refund.getTimestamp());
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

        sb.append(String.format("%-30s %11.2f\n", "Refund Amount:", refund.getRefundAmount()));
        sb.append(String.format("%-30s %11.2f\n", "Refund Tax:", refund.getRefundTax()));
        sb.append("-".repeat(42)).append("\n");
        sb.append(String.format("%-30s %11.2f\n", "TOTAL REFUND:", refund.getTotalRefund()));
        sb.append("\n");

        sb.append("Refund Method: ").append(refund.getRefundMethod() != null ? refund.getRefundMethod() : "N/A")
                .append("\n");
        sb.append("\n");

        if (!footer.isEmpty()) {
            sb.append(centerText(footer, 42)).append("\n");
        }
        sb.append("-".repeat(42)).append("\n");

        return sb.toString();
    }

    private String centerText(String text, int width) {
        if (text == null || text.length() >= width) {
            return text;
        }
        int padding = (width - text.length()) / 2;
        return " ".repeat(padding) + text;
    }
}
