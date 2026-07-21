package com.pos.ui;

import com.pos.api.dto.ShiftResponse;
import com.pos.model.Vendor;
import com.pos.model.VendorPayout;
import com.pos.model.VendorPayout.PayoutStatus;
import com.pos.service.ShiftService;
import com.pos.service.UserAuthService;
import com.pos.service.VendorService;
import com.pos.service.VendorPayoutService;
import com.pos.sync.WebSocketClient;
import com.pos.sync.WebSocketMessageHandler;
import com.pos.ui.components.NumericKeypad;
import com.pos.ui.components.ToastNotification;
import com.pos.util.ReceiptPrintHelper;
import com.pos.util.VendorPayoutReceiptBuilder;
import com.pos.service.SettingsService;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Dedicated screen for manual vendor payout entry.
 * Replaces the VendorPayoutDialog for a better user experience.
 */
public class VendorPayoutScreen extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(VendorPayoutScreen.class);
    private static final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VendorService vendorService;
    private final VendorPayoutService vendorPayoutService;
    private Runnable onBack;
    private Runnable onNavigateToAddVendor;
    private Runnable onNavigateToHistory;

    // UI Components - Entry Form
    private ComboBox<Vendor> vendorComboBox;
    private Button addVendorButton;
    private TextField amountField;
    private NumericKeypad keypad;
    private Label availableCashLabel;
    private ToggleGroup paymentMethodGroup;
    private RadioButton cashRadio;
    private RadioButton chequeRadio;
    private RadioButton invoiceRadio;
    private RadioButton creditRadio;
    private TextField chequeNumberField;
    private Label chequeNumberLabel;
    private TextField invoiceNumberField;
    private Label invoiceNumberLabel;
    private TextField activeField;

    // UI Components - History (Today's Summary)
    private ObservableList<VendorPayout> payoutHistoryList;
    private Label totalPaidLabel;

    // WebSocket vendor event listener
    private Consumer<WebSocketMessageHandler.VendorEvent> vendorEventListener;

    public VendorPayoutScreen(Runnable onBack) {
        this.onBack = onBack;
        this.vendorService = VendorService.getInstance();
        this.vendorPayoutService = VendorPayoutService.getInstance();
        this.payoutHistoryList = FXCollections.observableArrayList();
        
        initializeUI();
        loadVendors();
        loadPayoutHistory();
        registerVendorEventListener();
    }

    public void setOnNavigateToHistory(Runnable callback) {
        this.onNavigateToHistory = callback;
    }

    public void setOnNavigateToAddVendor(Runnable callback) {
        this.onNavigateToAddVendor = callback;
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #f8fafc;");

        // Header
        VBox header = createHeader();
        setTop(header);

        // Center - Entry form and Keypad
        HBox centerContent = new HBox(20);
        centerContent.setPadding(new Insets(20));
        centerContent.setAlignment(Pos.TOP_CENTER);

        VBox entryForm = createEntryForm();
        HBox.setHgrow(entryForm, Priority.ALWAYS);
        entryForm.setMaxWidth(600);

        VBox keypadContainer = createKeypadContainer();
        keypadContainer.setMinWidth(400);
        keypadContainer.setPrefWidth(450);

        centerContent.getChildren().addAll(entryForm, keypadContainer);
        setCenter(centerContent);

        // Footer - Today's Summary
        HBox footer = createTodaySummaryFooter();
        setBottom(footer);

        setPadding(new Insets(10));
        
        Platform.runLater(() -> {
            amountField.requestFocus();
            activeField = amountField;
        });
    }

    private VBox createKeypadContainer() {
        VBox container = new VBox(15);
        container.setPadding(new Insets(20));
        container.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 16;");
        container.setAlignment(Pos.TOP_CENTER);

        Label kbLabel = new Label("⌨️ Amount Entry Keypad");
        kbLabel.setStyle("-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 14px;");

        keypad = new NumericKeypad(true, true, false);
        keypad.setListener(this::handleKeypadInput);
        keypad.setEnterListener(this::savePayout);
        keypad.setPrefSize(400, 300);
        keypad.setMaxHeight(Region.USE_PREF_SIZE);
        VBox.setVgrow(keypad, Priority.NEVER);

        container.getChildren().addAll(kbLabel, keypad);
        return container;
    }

    private void handleKeypadInput(String key) {
        if (activeField == null) activeField = amountField;
        
        String currentText = activeField.getText();
        
        if ("C".equals(key)) {
            activeField.clear();
        } else if ("⌫".equals(key)) {
            if (!currentText.isEmpty()) {
                activeField.setText(currentText.substring(0, currentText.length() - 1));
            }
        } else if (".".equals(key)) {
            if (!currentText.contains(".")) {
                activeField.setText(currentText + ".");
            }
        } else if ("00".equals(key)) {
            activeField.setText(currentText + "00");
        } else if (key.matches("\\d")) {
            activeField.setText(currentText + key);
        }
        
        activeField.requestFocus();
        activeField.positionCaret(activeField.getText().length());
    }

    private HBox createTodaySummaryFooter() {
        HBox footer = new HBox(30);
        footer.setPadding(new Insets(15, 25, 15, 25));
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setStyle("-fx-background-color: white; -fx-border-color: #e2e8f0; -fx-border-width: 1 0 0 0;");

        VBox totalBox = new VBox(2);
        Label totalTitle = new Label("TOTAL PAID TODAY");
        totalTitle.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px; -fx-font-weight: bold;");
        totalPaidLabel = new Label("$0.00");
        totalPaidLabel.setStyle("-fx-text-fill: #10b981; -fx-font-weight: 900; -fx-font-size: 20px;");
        totalBox.getChildren().addAll(totalTitle, totalPaidLabel);

        footer.getChildren().add(totalBox);
        return footer;
    }

    private VBox createHeader() {
        VBox header = new VBox(12);
        header.setPadding(new Insets(15, 25, 20, 25));
        header.setStyle("-fx-background-color: linear-gradient(to right, #1e293b, #334155); -fx-background-radius: 12;");

        HBox topRow = new HBox();
        topRow.setAlignment(Pos.CENTER_LEFT);

        Button backBtn = new Button("← Back");
        backBtn.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 15; -fx-background-radius: 8; -fx-cursor: hand;");
        backBtn.setOnAction(e -> {
            unregisterVendorEventListener();
            if (onBack != null) onBack.run();
        });

        Region spacer1 = new Region();
        HBox.setHgrow(spacer1, Priority.ALWAYS);

        Label titleLabel = new Label("💰 Vendor Payout");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 28));
        titleLabel.setStyle("-fx-text-fill: white;");

        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);

        Button historyBtn = new Button("📋 Full History");
        historyBtn.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 15; -fx-background-radius: 8; -fx-cursor: hand;");
        historyBtn.setOnAction(e -> {
            if (onNavigateToHistory != null) onNavigateToHistory.run();
        });

        topRow.getChildren().addAll(backBtn, spacer1, titleLabel, spacer2, historyBtn);

        Label subtitleLabel = new Label("Record and track payments to your registered vendors");
        subtitleLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 14px;");
        subtitleLabel.setAlignment(Pos.CENTER);
        subtitleLabel.setMaxWidth(Double.MAX_VALUE);

        header.getChildren().addAll(topRow, subtitleLabel);
        return header;
    }

    private VBox createEntryForm() {
        VBox form = new VBox(20);
        form.setPadding(new Insets(25));
        form.setStyle("-fx-background-color: white; -fx-background-radius: 16; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 15, 0, 0, 5);");

        Label formTitle = new Label("📝 New Payout Entry");
        formTitle.setFont(Font.font("System", FontWeight.BOLD, 18));
        formTitle.setStyle("-fx-text-fill: #1e293b;");

        // Vendor Selection
        VBox vendorBox = new VBox(8);
        Label vendorLabel = new Label("Select Vendor *");
        vendorLabel.setStyle("-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 14px;");

        HBox vendorRow = new HBox(10);
        vendorRow.setAlignment(Pos.CENTER_LEFT);

        vendorComboBox = new ComboBox<>();
        vendorComboBox.setPromptText("Choose a vendor...");
        vendorComboBox.setMaxWidth(Double.MAX_VALUE);
        vendorComboBox.setStyle("-fx-font-size: 15px; -fx-padding: 5; -fx-background-radius: 8;");
        HBox.setHgrow(vendorComboBox, Priority.ALWAYS);

        addVendorButton = new Button("➕ Add Vendor");
        addVendorButton.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 10 15; -fx-background-radius: 8; -fx-cursor: hand;");
        addVendorButton.setOnAction(e -> {
            if (onNavigateToAddVendor != null) onNavigateToAddVendor.run();
        });

        vendorRow.getChildren().addAll(vendorComboBox, addVendorButton);
        vendorBox.getChildren().addAll(vendorLabel, vendorRow);

        // Amount Field
        VBox amountBox = new VBox(8);
        Label amountLabel = new Label("Amount to Pay *");
        amountLabel.setStyle("-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 14px;");

        amountField = new TextField();
        amountField.setPromptText("0.00");
        amountField.setMaxWidth(Double.MAX_VALUE);
        amountField.setStyle("-fx-font-size: 28px; -fx-padding: 15; -fx-background-radius: 8; -fx-font-weight: 900; -fx-text-fill: #1e293b;");

        // Only allow digits and a single decimal point — blocks "C" and other non-numeric input
        UnaryOperator<TextFormatter.Change> numericFilter = change -> {
            String newText = change.getControlNewText();
            if (newText.matches("\\d*\\.?\\d*")) {
                return change;
            }
            return null;
        };
        amountField.setTextFormatter(new TextFormatter<>(numericFilter));
        
        amountField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) activeField = amountField;
        });

        amountBox.getChildren().addAll(amountLabel, amountField);

        // Payment Method Selection
        VBox methodBox = new VBox(10);
        Label methodLabel = new Label("Payment Method *");
        methodLabel.setStyle("-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 14px;");

        paymentMethodGroup = new ToggleGroup();
        cashRadio = new RadioButton("💵  Cash Payment");
        cashRadio.setToggleGroup(paymentMethodGroup);
        cashRadio.setSelected(true);
        cashRadio.setStyle("-fx-font-size: 15px; -fx-padding: 8;");

        chequeRadio = new RadioButton("📝  Cheque Payment");
        chequeRadio.setToggleGroup(paymentMethodGroup);
        chequeRadio.setStyle("-fx-font-size: 15px; -fx-padding: 8;");

        invoiceRadio = new RadioButton("🧾  Invoice");
        invoiceRadio.setToggleGroup(paymentMethodGroup);
        invoiceRadio.setStyle("-fx-font-size: 15px; -fx-padding: 8;");

        creditRadio = new RadioButton("💳  Credit");
        creditRadio.setToggleGroup(paymentMethodGroup);
        creditRadio.setStyle("-fx-font-size: 15px; -fx-padding: 8;");

        FlowPane radioBox = new FlowPane(25, 10);
        radioBox.getChildren().addAll(cashRadio, chequeRadio, invoiceRadio, creditRadio);

        HBox availableCashBox = new HBox(10);
        availableCashBox.setAlignment(Pos.CENTER_LEFT);
        availableCashBox.setPadding(new Insets(10, 15, 10, 15));
        availableCashBox.setStyle("-fx-background-color: #fefce8; -fx-background-radius: 8; -fx-border-color: #fef08a; -fx-border-radius: 8;");

        Label availableCashTitle = new Label("Drawer Balance:");
        availableCashTitle.setStyle("-fx-text-fill: #854d0e; -fx-font-size: 13px;");
        availableCashLabel = new Label("$0.00");
        availableCashLabel.setStyle("-fx-text-fill: #854d0e; -fx-font-weight: 800; -fx-font-size: 16px;");
        availableCashBox.getChildren().addAll(availableCashTitle, availableCashLabel);
        updateAvailableCashDisplay();

        paymentMethodGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            boolean isCash = newToggle == cashRadio;
            availableCashBox.setVisible(isCash);
            availableCashBox.setManaged(isCash);
            if (isCash) updateAvailableCashDisplay();
            updatePaymentReferenceVisibility();
        });

        methodBox.getChildren().addAll(methodLabel, radioBox, availableCashBox);

        // Cheque Number Field
        VBox chequeBox = new VBox(8);
        chequeNumberLabel = new Label("Cheque / Reference Number *");
        chequeNumberLabel.setStyle("-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 14px;");
        chequeNumberLabel.setVisible(false);
        chequeNumberLabel.setManaged(false);

        chequeNumberField = new TextField();
        chequeNumberField.setPromptText("Enter cheque # or ref");
        chequeNumberField.setMaxWidth(Double.MAX_VALUE);
        chequeNumberField.setStyle("-fx-font-size: 15px; -fx-padding: 12; -fx-background-radius: 8;");
        chequeNumberField.setVisible(false);
        chequeNumberField.setManaged(false);

        chequeNumberField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) activeField = chequeNumberField;
        });

        chequeBox.getChildren().addAll(chequeNumberLabel, chequeNumberField);

        VBox invoiceBox = new VBox(8);
        invoiceNumberLabel = new Label("Invoice Number *");
        invoiceNumberLabel.setStyle("-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 14px;");
        invoiceNumberLabel.setVisible(false);
        invoiceNumberLabel.setManaged(false);

        invoiceNumberField = new TextField();
        invoiceNumberField.setPromptText("Enter invoice number");
        invoiceNumberField.setMaxWidth(Double.MAX_VALUE);
        invoiceNumberField.setStyle("-fx-font-size: 15px; -fx-padding: 12; -fx-background-radius: 8;");
        invoiceNumberField.setVisible(false);
        invoiceNumberField.setManaged(false);

        invoiceNumberField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) activeField = invoiceNumberField;
        });

        invoiceBox.getChildren().addAll(invoiceNumberLabel, invoiceNumberField);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Button saveButton = new Button("💾  Record Payout");
        saveButton.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 18px; -fx-background-radius: 12; -fx-padding: 15; -fx-cursor: hand;");
        saveButton.setMaxWidth(Double.MAX_VALUE);
        saveButton.setOnAction(e -> savePayout());

        Button clearButton = new Button("Clear Form");
        clearButton.setStyle("-fx-background-color: transparent; -fx-text-fill: #64748b; -fx-font-weight: bold; -fx-cursor: hand;");
        clearButton.setOnAction(e -> clearForm());

        form.getChildren().addAll(formTitle, new Separator(), vendorBox, amountBox, methodBox, chequeBox, invoiceBox, spacer, saveButton, clearButton);

        return form;
    }

    private void updatePaymentReferenceVisibility() {
        Toggle selectedToggle = paymentMethodGroup.getSelectedToggle();
        boolean isCheque = selectedToggle == chequeRadio;
        boolean showsInvoice = selectedToggle == chequeRadio || selectedToggle == invoiceRadio || selectedToggle == creditRadio;
        boolean requiresInvoice = selectedToggle == invoiceRadio || selectedToggle == creditRadio;

        chequeNumberLabel.setVisible(isCheque);
        chequeNumberLabel.setManaged(isCheque);
        chequeNumberField.setVisible(isCheque);
        chequeNumberField.setManaged(isCheque);
        if (!isCheque) {
            chequeNumberField.clear();
        }

        String invoiceLabelText = selectedToggle == chequeRadio
                ? "Invoice Number (Optional)"
                : selectedToggle == creditRadio ? "Invoice Number for Credit *" : "Invoice Number *";
        invoiceNumberLabel.setText(invoiceLabelText);
        invoiceNumberLabel.setVisible(showsInvoice);
        invoiceNumberLabel.setManaged(showsInvoice);
        invoiceNumberField.setVisible(showsInvoice);
        invoiceNumberField.setManaged(showsInvoice);
        if (!showsInvoice) {
            invoiceNumberField.clear();
        }
    }

    private String getSelectedPaymentMethod() {
        if (chequeRadio.isSelected()) {
            return "CHEQUE";
        }
        if (invoiceRadio.isSelected()) {
            return "INVOICE";
        }
        if (creditRadio.isSelected()) {
            return "CREDIT";
        }
        return "CASH";
    }

    private void loadVendors() {
        try {
            List<Vendor> vendors = vendorService.getAllVendors();
            vendorComboBox.setItems(FXCollections.observableArrayList(vendors));
            vendorComboBox.setCellFactory(lv -> new ListCell<>() {
                @Override protected void updateItem(Vendor v, boolean empty) {
                    super.updateItem(v, empty);
                    setText(empty || v == null ? null : v.getName());
                }
            });
            vendorComboBox.setButtonCell(new ListCell<>() {
                @Override protected void updateItem(Vendor v, boolean empty) {
                    super.updateItem(v, empty);
                    setText(empty || v == null ? "Choose a vendor..." : v.getName());
                }
            });
        } catch (Exception e) {
            logger.error("Error loading vendors", e);
        }
    }

    private void loadPayoutHistory() {
        try {
            List<VendorPayout> paidPayouts = vendorPayoutService.getPayoutsByStatus(PayoutStatus.PAID);
            payoutHistoryList.setAll(paidPayouts);
            updateTotalPaid();
        } catch (Exception e) {
            logger.error("Error loading history", e);
        }
    }

    private void updateTotalPaid() {
        String today = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        BigDecimal totalToday = payoutHistoryList.stream()
                .filter(p -> p.getPaidAt() != null && p.getPaidAt().startsWith(today))
                .map(p -> p.getAmountPaid() != null ? p.getAmountPaid() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        totalPaidLabel.setText(currencyFormat.format(totalToday));
    }

    private void savePayout() {
        Vendor vendor = vendorComboBox.getValue();
        if (vendor == null) {
            ToastNotification.showWarning("Select vendor", getScene().getWindow());
            return;
        }

        String amountText = amountField.getText().trim();
        if (amountText.isEmpty()) {
            ToastNotification.showWarning("Enter amount", getScene().getWindow());
            return;
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(amountText);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                ToastNotification.showWarning("Amount > 0", getScene().getWindow());
                return;
            }
        } catch (Exception e) {
            ToastNotification.showWarning("Invalid amount", getScene().getWindow());
            return;
        }

        String paymentMethod = getSelectedPaymentMethod();
        boolean isCash = "CASH".equals(paymentMethod);
        String chequeNumber = chequeNumberField.getText().trim();
        String invoiceNumber = invoiceNumberField.getText().trim();
        String currentShiftId = null;

        if ("CHEQUE".equals(paymentMethod) && chequeNumber.isEmpty()) {
            ToastNotification.showWarning("Enter cheque/reference number", getScene().getWindow());
            return;
        }

        if (("INVOICE".equals(paymentMethod) || "CREDIT".equals(paymentMethod)) && invoiceNumber.isEmpty()) {
            ToastNotification.showWarning("Enter invoice number", getScene().getWindow());
            return;
        }

        if (isCash) {
            try {
                ShiftResponse.ShiftData activeShift = ShiftService.getInstance().getActiveShift();
                if (activeShift == null) {
                    ToastNotification.showError("No active shift", getScene().getWindow());
                    return;
                }
                currentShiftId = activeShift.id;
                BigDecimal available = ShiftService.getInstance().calculateAvailableCash(currentShiftId);
                if (amount.compareTo(available) > 0) {
                    ToastNotification.showError("Insufficient cash", getScene().getWindow());
                    return;
                }
            } catch (Exception e) {
                logger.error("Shift error", e);
                return;
            }
        }

        try {
            VendorPayout pv = new VendorPayout();
            pv.setId(UUID.randomUUID().toString());
            pv.setVendorId(vendor.getId());
            pv.setVendorName(vendor.getName());
            pv.setAmountPaid(amount);
            pv.setTotalPayout(amount);
            pv.setPaymentMethod(paymentMethod);
            pv.setPaymentReference(resolvePaymentReference(paymentMethod, chequeNumber, invoiceNumber));
            pv.setChequeNumber("CHEQUE".equals(paymentMethod) && !chequeNumber.isEmpty() ? chequeNumber : null);
            pv.setNotes("");
            pv.setStatus(PayoutStatus.PAID);
            pv.setPaidAt(LocalDateTime.now().format(DATE_FORMATTER));
            pv.setShiftId(currentShiftId);
            pv.setPaidBy(UserAuthService.getInstance().getCurrentUserName());

            vendorPayoutService.saveManualPayout(pv);
            
            // Print receipt
            try {
                String storeName = SettingsService.getInstance().getStoreName();
                String receipt = VendorPayoutReceiptBuilder.buildReceipt(storeName, pv);
                ReceiptPrintHelper.printReceiptConditionally(
                    receipt, 
                    "VP-" + pv.getId(), 
                    getScene().getWindow(), 
                    SettingsService.getInstance()
                );
            } catch (Exception pe) {
                logger.error("Error printing receipt", pe);
            }

            ToastNotification.showSuccess("Payout recorded", getScene().getWindow());
            clearForm();
            loadPayoutHistory();
            updateAvailableCashDisplay();
        } catch (Exception e) {
            ToastNotification.showError("Save failed: " + e.getMessage(), getScene().getWindow());
        }
    }

    private void clearForm() {
        vendorComboBox.setValue(null);
        amountField.clear();
        cashRadio.setSelected(true);
        chequeNumberField.clear();
        invoiceNumberField.clear();
        activeField = amountField;
        amountField.requestFocus();
    }

    private String resolvePaymentReference(String paymentMethod, String chequeNumber, String invoiceNumber) {
        return switch (paymentMethod) {
            case "CHEQUE", "INVOICE", "CREDIT" -> invoiceNumber.isEmpty() ? null : invoiceNumber;
            default -> null;
        };
    }

    private void updateAvailableCashDisplay() {
        new Thread(() -> {
            try {
                ShiftResponse.ShiftData activeShift = ShiftService.getInstance().getActiveShift();
                if (activeShift != null) {
                    BigDecimal available = ShiftService.getInstance().calculateAvailableCash(activeShift.id);
                    Platform.runLater(() -> availableCashLabel.setText(currencyFormat.format(available)));
                } else {
                    Platform.runLater(() -> availableCashLabel.setText("No active shift"));
                }
            } catch (Exception e) {
                logger.error("Error", e);
            }
        }).start();
    }

    private void registerVendorEventListener() {
        try {
            WebSocketClient wsClient = WebSocketClient.getInstance();
            if (wsClient != null && wsClient.getMessageHandler() != null) {
                vendorEventListener = event -> Platform.runLater(this::loadVendors);
                wsClient.getMessageHandler().addVendorEventListener(vendorEventListener);
            }
        } catch (Exception e) { /* ignore */ }
    }

    private void unregisterVendorEventListener() {
        if (vendorEventListener != null) {
            try {
                WebSocketClient.getInstance().getMessageHandler().removeVendorEventListener(vendorEventListener);
            } catch (Exception e) { /* ignore */ }
            vendorEventListener = null;
        }
    }

    private void showAddVendorDialog() {
        ToastNotification.showWarning("Add Vendor screen required", getScene().getWindow());
    }
}
