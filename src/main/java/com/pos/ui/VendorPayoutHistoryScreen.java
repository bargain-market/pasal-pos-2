package com.pos.ui;

import com.pos.model.Vendor;
import com.pos.model.VendorPayout;
import com.pos.service.VendorPayoutService;
import com.pos.service.VendorService;
import com.pos.service.SettingsService;
import com.pos.ui.components.ToastNotification;
import com.pos.util.DialogHelper;
import com.pos.util.ReceiptPrintHelper;
import com.pos.util.VendorPayoutReceiptBuilder;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Dedicated screen for viewing the full history of vendor payouts.
 */
public class VendorPayoutHistoryScreen extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(VendorPayoutHistoryScreen.class);
    private static final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final VendorPayoutService vendorPayoutService;
    private final VendorService vendorService;
    private final Runnable onBack;

    // UI Components
    private TableView<VendorPayout> historyTable;
    private ObservableList<VendorPayout> historyList;
    private ComboBox<Vendor> vendorFilter;
    private DatePicker startDatePicker;
    private DatePicker endDatePicker;
    private Label totalAmountLabel;
    private Consumer<String> onNavigateToEditPayout;

    public VendorPayoutHistoryScreen(Runnable onBack) {
        this.onBack = onBack;
        this.vendorPayoutService = VendorPayoutService.getInstance();
        this.vendorService = VendorService.getInstance();
        this.historyList = FXCollections.observableArrayList();

        initializeUI();
        loadVendors();
        loadHistory();
    }

    public void setOnNavigateToEditPayout(Consumer<String> onNavigateToEditPayout) {
        this.onNavigateToEditPayout = onNavigateToEditPayout;
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #f8fafc;");

        // Header
        VBox header = createHeader();
        setTop(header);

        // Filters and Table
        VBox centerBox = new VBox(20);
        centerBox.setPadding(new Insets(20));

        HBox filtersRow = createFiltersRow();

        historyTable = createHistoryTable();
        VBox.setVgrow(historyTable, Priority.ALWAYS);

        HBox summaryRow = createSummaryRow();

        centerBox.getChildren().addAll(filtersRow, historyTable, summaryRow);
        setCenter(centerBox);
    }

    private VBox createHeader() {
        VBox header = new VBox(10);
        header.setPadding(new Insets(15, 25, 20, 25));
        header.setStyle("-fx-background-color: linear-gradient(to right, #1e293b, #334155); -fx-background-radius: 12;");

        HBox topRow = new HBox();
        topRow.setAlignment(Pos.CENTER_LEFT);

        Button backBtn = new Button("← Back");
        backBtn.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 15; -fx-background-radius: 8; -fx-cursor: hand;");
        backBtn.setOnAction(e -> {
            if (onBack != null) onBack.run();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label titleLabel = new Label("Vendor Payout History");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 28));
        titleLabel.setStyle("-fx-text-fill: white;");

        topRow.getChildren().addAll(backBtn, spacer, titleLabel, new Region());

        header.getChildren().addAll(topRow);
        return header;
    }

    private HBox createFiltersRow() {
        HBox row = new HBox(20);
        row.setAlignment(Pos.BOTTOM_LEFT);
        row.setPadding(new Insets(15));
        row.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 10, 0, 0, 2);");

        // Vendor Filter
        VBox vendorBox = new VBox(5);
        Label vendorLabel = new Label("Filter by Vendor");
        vendorLabel.setStyle("-fx-text-fill: #64748b; -fx-font-weight: bold; -fx-font-size: 12px;");
        vendorFilter = new ComboBox<>();
        vendorFilter.setPromptText("All Vendors");
        vendorFilter.setPrefWidth(200);
        vendorFilter.setStyle("-fx-background-radius: 8;");
        vendorBox.getChildren().addAll(vendorLabel, vendorFilter);

        // Date Range
        VBox startBox = new VBox(5);
        Label startLabel = new Label("Start Date");
        startLabel.setStyle("-fx-text-fill: #64748b; -fx-font-weight: bold; -fx-font-size: 12px;");
        startDatePicker = new DatePicker(LocalDate.now().minusMonths(1));
        startDatePicker.setPrefWidth(150);
        startDatePicker.setStyle("-fx-background-radius: 8;");
        startBox.getChildren().addAll(startLabel, startDatePicker);

        VBox endBox = new VBox(5);
        Label endLabel = new Label("End Date");
        endLabel.setStyle("-fx-text-fill: #64748b; -fx-font-weight: bold; -fx-font-size: 12px;");
        endDatePicker = new DatePicker(LocalDate.now());
        endDatePicker.setPrefWidth(150);
        endDatePicker.setStyle("-fx-background-radius: 8;");
        endBox.getChildren().addAll(endLabel, endDatePicker);

        Button searchBtn = new Button("Filter History");
        searchBtn.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 10 20; -fx-background-radius: 8; -fx-cursor: hand;");
        searchBtn.setOnAction(e -> loadHistory());

        Button resetBtn = new Button("Reset");
        resetBtn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-font-weight: bold; -fx-padding: 10 20; -fx-background-radius: 8; -fx-cursor: hand;");
        resetBtn.setOnAction(e -> {
            vendorFilter.setValue(null);
            startDatePicker.setValue(LocalDate.now().minusMonths(1));
            endDatePicker.setValue(LocalDate.now());
            loadHistory();
        });

        row.getChildren().addAll(vendorBox, startBox, endBox, searchBtn, resetBtn);
        return row;
    }

    private TableView<VendorPayout> createHistoryTable() {
        TableView<VendorPayout> table = new TableView<>();
        table.getStyleClass().add("modern-table");
        table.setItems(historyList);
        table.setPlaceholder(new Label("No payout records found for the selected criteria"));

        TableColumn<VendorPayout, String> dateCol = new TableColumn<>("Date / Time");
        dateCol.setCellValueFactory(data -> {
            String paidAt = data.getValue().getPaidAt();
            if (paidAt != null && paidAt.length() >= 16) {
                try {
                    LocalDateTime dt = LocalDateTime.parse(paidAt, DATE_FORMATTER);
                    return new SimpleStringProperty(dt.format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm")));
                } catch (Exception e) {
                    return new SimpleStringProperty(paidAt.substring(0, 16).replace("T", " "));
                }
            }
            return new SimpleStringProperty(paidAt != null ? paidAt : "-");
        });
        dateCol.setPrefWidth(180);

        TableColumn<VendorPayout, String> vendorCol = new TableColumn<>("Vendor");
        vendorCol.setCellValueFactory(data -> data.getValue().vendorNameProperty());
        vendorCol.setPrefWidth(200);

        TableColumn<VendorPayout, String> amountCol = new TableColumn<>("Amount Paid");
        amountCol.setCellValueFactory(data -> new SimpleStringProperty(currencyFormat.format(data.getValue().getAmountPaid())));
        amountCol.setPrefWidth(130);
        amountCol.setStyle("-fx-alignment: CENTER-RIGHT; -fx-font-weight: bold;");

        TableColumn<VendorPayout, String> methodCol = new TableColumn<>("Method");
        methodCol.setCellValueFactory(data -> new SimpleStringProperty(formatPaymentMethod(data.getValue().getPaymentMethod())));
        methodCol.setPrefWidth(110);
        methodCol.setStyle("-fx-alignment: CENTER;");

        TableColumn<VendorPayout, String> referenceCol = new TableColumn<>("Reference");
        referenceCol.setCellValueFactory(data -> new SimpleStringProperty(getPaymentReferenceDisplay(data.getValue())));
        referenceCol.setPrefWidth(160);

        TableColumn<VendorPayout, String> paidByCol = new TableColumn<>("Paid By");
        paidByCol.setCellValueFactory(data -> data.getValue().paidByProperty());
        paidByCol.setPrefWidth(150);

        TableColumn<VendorPayout, String> notesCol = new TableColumn<>("Notes");
        notesCol.setCellValueFactory(data -> data.getValue().notesProperty());
        notesCol.setPrefWidth(250);

        TableColumn<VendorPayout, Void> actionCol = new TableColumn<>("Actions");
        actionCol.setPrefWidth(280);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button editBtn = new Button("Edit");
            private final Button reprintBtn = new Button("Reprint");
            private final Button deleteBtn = new Button("Delete");
            private final HBox actions = new HBox(8);

            {
                editBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px; -fx-background-radius: 4; -fx-cursor: hand;");
                reprintBtn.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px; -fx-background-radius: 4; -fx-cursor: hand;");
                deleteBtn.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px; -fx-background-radius: 4; -fx-cursor: hand;");
                editBtn.setOnAction(e -> {
                    VendorPayout payout = getTableView().getItems().get(getIndex());
                    if (onNavigateToEditPayout != null && payout != null && payout.getId() != null) {
                        onNavigateToEditPayout.accept(payout.getId());
                    }
                });
                reprintBtn.setOnAction(e -> {
                    VendorPayout payout = getTableView().getItems().get(getIndex());
                    reprintPayout(payout);
                });
                deleteBtn.setOnAction(e -> {
                    VendorPayout payout = getTableView().getItems().get(getIndex());
                    confirmAndDeletePayout(payout);
                });
                actions.setAlignment(Pos.CENTER);
                actions.getChildren().addAll(editBtn, reprintBtn, deleteBtn);
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    VendorPayout payout = getTableView().getItems().get(getIndex());
                    boolean cashPayout = payout != null
                            && payout.getPaymentMethod() != null
                            && "CASH".equalsIgnoreCase(payout.getPaymentMethod().trim());
                    editBtn.setDisable(cashPayout);
                    if (cashPayout) {
                        editBtn.setTooltip(new Tooltip(
                                "Cash payouts cannot be edited; they are tied to drawer cash and reports."));
                    } else {
                        editBtn.setTooltip(null);
                    }
                    boolean canDelete = payout != null && vendorPayoutService.isPaidPayoutDeletableToday(payout);
                    deleteBtn.setDisable(!canDelete);
                    if (canDelete) {
                        deleteBtn.setTooltip(new Tooltip("Remove this payout (same calendar day as paid time only)"));
                    } else {
                        deleteBtn.setTooltip(new Tooltip("Only payouts recorded today can be deleted"));
                    }
                    setGraphic(actions);
                    setAlignment(Pos.CENTER);
                }
            }
        });

        table.getColumns().addAll(dateCol, vendorCol, amountCol, methodCol, referenceCol, paidByCol, notesCol, actionCol);

        return table;
    }

    private String formatPaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return "-";
        }
        return switch (paymentMethod.toUpperCase()) {
            case "CASH" -> "Cash";
            case "CHEQUE" -> "Cheque";
            case "INVOICE" -> "Invoice";
            case "CREDIT" -> "Credit";
            default -> paymentMethod;
        };
    }

    private String getPaymentReferenceDisplay(VendorPayout payout) {
        String paymentMethod = payout.getPaymentMethod() != null ? payout.getPaymentMethod().trim().toUpperCase() : "";
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

    private HBox createSummaryRow() {
        HBox row = new HBox(15);
        row.setAlignment(Pos.CENTER_RIGHT);
        row.setPadding(new Insets(10, 20, 10, 20));
        row.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 12;");

        Label label = new Label("Total Paid in Period:");
        label.setStyle("-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 16px;");

        totalAmountLabel = new Label("$0.00");
        totalAmountLabel.setStyle("-fx-text-fill: #10b981; -fx-font-weight: 900; -fx-font-size: 22px;");

        row.getChildren().addAll(label, totalAmountLabel);
        return row;
    }

    private void loadVendors() {
        try {
            List<Vendor> vendors = vendorService.getAllVendors();
            vendorFilter.setItems(FXCollections.observableArrayList(vendors));
            vendorFilter.setCellFactory(lv -> new ListCell<Vendor>() {
                @Override
                protected void updateItem(Vendor vendor, boolean empty) {
                    super.updateItem(vendor, empty);
                    setText(empty || vendor == null ? "All Vendors" : vendor.getName());
                }
            });
            vendorFilter.setButtonCell(new ListCell<Vendor>() {
                @Override
                protected void updateItem(Vendor vendor, boolean empty) {
                    super.updateItem(vendor, empty);
                    setText(empty || vendor == null ? "All Vendors" : vendor.getName());
                }
            });
        } catch (Exception e) {
            logger.error("Error loading vendors", e);
        }
    }

    private void loadHistory() {
        LocalDate start = startDatePicker.getValue();
        LocalDate end = endDatePicker.getValue();
        Vendor selectedVendor = vendorFilter.getValue();

        new Thread(() -> {
            try {
                List<VendorPayout> payouts = vendorPayoutService.getPaidPayoutsForDateRange(start, end);

                // Client-side filter for vendor if selected
                if (selectedVendor != null) {
                    payouts = payouts.stream()
                            .filter(p -> p.getVendorId().equals(selectedVendor.getId()))
                            .toList();
                }

                List<VendorPayout> finalPayouts = payouts;
                BigDecimal total = payouts.stream()
                        .map(p -> p.getAmountPaid() != null ? p.getAmountPaid() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                Platform.runLater(() -> {
                    historyList.setAll(finalPayouts);
                    totalAmountLabel.setText(currencyFormat.format(total));
                });
            } catch (Exception e) {
                logger.error("Error loading payout history", e);
            }
        }).start();
    }

    private void confirmAndDeletePayout(VendorPayout payout) {
        if (payout == null || !vendorPayoutService.isPaidPayoutDeletableToday(payout)) {
            ToastNotification.showWarning(
                    "Only payouts recorded today can be deleted.",
                    getScene() != null ? getScene().getWindow() : null);
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Delete vendor payout");
        alert.setHeaderText("Remove this payout?");
        alert.setContentText(
                "This permanently deletes the payout from this register. "
                        + "Only payouts from today can be removed (same calendar day as the paid time). "
                        + "Cash payouts affect drawer totals; delete only to correct a mistake.");
        alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        DialogHelper.setAlertOwner(alert, getScene() != null ? getScene().getWindow() : null);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        new Thread(() -> {
            boolean ok = vendorPayoutService.deletePaidPayoutIfSameDay(payout.getId());
            Platform.runLater(() -> {
                if (ok) {
                    ToastNotification.showSuccess("Payout removed", getScene() != null ? getScene().getWindow() : null);
                    loadHistory();
                } else {
                    ToastNotification.showError("Could not delete payout", getScene() != null ? getScene().getWindow() : null);
                }
            });
        }).start();
    }

    private void reprintPayout(VendorPayout payout) {
        try {
            String storeName = SettingsService.getInstance().getStoreName();
            String receipt = VendorPayoutReceiptBuilder.buildReceipt(storeName, payout);
            ReceiptPrintHelper.showReceiptPreview(
                receipt,
                getScene().getWindow(),
                "Reprint Vendor Payout",
                "Vendor: " + payout.getVendorName()
            );
        } catch (Exception e) {
            logger.error("Error reprinting payout", e);
        }
    }
}
