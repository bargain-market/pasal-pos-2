package com.pos.ui;

import com.pos.model.Vendor;
import com.pos.model.VendorPayout;
import com.pos.model.VendorPayout.PayoutStatus;
import com.pos.service.VendorService;
import com.pos.service.VendorPayoutService;
import com.pos.service.SettingsService;
import com.pos.ui.components.NumericKeypad;
import com.pos.ui.components.ToastNotification;
import com.pos.util.ReceiptPrintHelper;
import com.pos.util.VendorPayoutReceiptBuilder;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Screen to correct an existing paid vendor payout (amount, vendor, method, references, notes).
 * Cash payouts cannot be edited here because they affect shift drawer cash and reporting.
 */
public class VendorPayoutEditScreen extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(VendorPayoutEditScreen.class);
    private static final DateTimeFormatter PAID_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VendorService vendorService;
    private final VendorPayoutService vendorPayoutService;
    private final String payoutId;
    private final Runnable onBack;

    private VendorPayout loadedOriginal;

    private ComboBox<Vendor> vendorComboBox;
    private TextField amountField;
    private NumericKeypad keypad;
    private ToggleGroup paymentMethodGroup;
    private RadioButton chequeRadio;
    private RadioButton invoiceRadio;
    private RadioButton creditRadio;
    private TextField chequeNumberField;
    private Label chequeNumberLabel;
    private TextField invoiceNumberField;
    private Label invoiceNumberLabel;
    private TextArea notesArea;
    private TextField paidAtField;
    private TextField activeField;
    private Label payoutIdLabel;
    private Button saveButton;

    public VendorPayoutEditScreen(String payoutId, Runnable onBack) {
        this.payoutId = payoutId;
        this.onBack = onBack;
        this.vendorService = VendorService.getInstance();
        this.vendorPayoutService = VendorPayoutService.getInstance();

        initializeShell();
        loadPayoutAsync();
    }

    private void initializeShell() {
        setStyle("-fx-background-color: #f8fafc;");

        VBox header = createHeader();
        setTop(header);

        VBox loading = new VBox(new Label("Loading payout…"));
        loading.setAlignment(Pos.CENTER);
        setCenter(loading);
        setPadding(new Insets(10));
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
            if (onBack != null) {
                onBack.run();
            }
        });

        Region spacer1 = new Region();
        HBox.setHgrow(spacer1, Priority.ALWAYS);

        Label titleLabel = new Label("Edit Vendor Payout");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 28));
        titleLabel.setStyle("-fx-text-fill: white;");

        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);

        payoutIdLabel = new Label();
        payoutIdLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 12px;");

        topRow.getChildren().addAll(backBtn, spacer1, titleLabel, spacer2, payoutIdLabel);

        Label subtitleLabel = new Label("Update payment details; changes are saved to this register and queued for sync.");
        subtitleLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 14px;");
        subtitleLabel.setMaxWidth(Double.MAX_VALUE);

        header.getChildren().addAll(topRow, subtitleLabel);
        return header;
    }

    private void loadPayoutAsync() {
        new Thread(() -> {
            try {
                VendorPayout p = vendorPayoutService.getPayoutById(payoutId);
                Platform.runLater(() -> applyLoadedPayout(p));
            } catch (Exception e) {
                logger.error("Failed to load payout {}", payoutId, e);
                Platform.runLater(() -> {
                    ToastNotification.showError("Could not load payout", getScene() != null ? getScene().getWindow() : null);
                    if (onBack != null) {
                        onBack.run();
                    }
                });
            }
        }).start();
    }

    private void applyLoadedPayout(VendorPayout p) {
        if (p == null || p.getStatus() != PayoutStatus.PAID) {
            ToastNotification.showError("Payout not found or not paid", getScene() != null ? getScene().getWindow() : null);
            if (onBack != null) {
                onBack.run();
            }
            return;
        }
        if (isCashPaymentMethod(p.getPaymentMethod())) {
            ToastNotification.showWarning(
                    "Cash payouts cannot be edited; they are tied to drawer cash and reports.",
                    getScene() != null ? getScene().getWindow() : null);
            if (onBack != null) {
                onBack.run();
            }
            return;
        }
        this.loadedOriginal = p;
        payoutIdLabel.setText("ID: " + p.getId());

        HBox centerContent = new HBox(20);
        centerContent.setPadding(new Insets(20));
        centerContent.setAlignment(Pos.TOP_CENTER);
        centerContent.setFillHeight(false);

        VBox entryForm = buildFormFromPayout(p);
        HBox.setHgrow(entryForm, Priority.ALWAYS);
        entryForm.setMaxWidth(600);
        entryForm.setMaxHeight(Region.USE_PREF_SIZE);

        VBox keypadContainer = createKeypadContainer();
        keypadContainer.setMinWidth(400);
        keypadContainer.setPrefWidth(450);
        keypadContainer.setMaxHeight(Region.USE_PREF_SIZE);

        centerContent.getChildren().addAll(entryForm, keypadContainer);

        ScrollPane scroll = new ScrollPane(centerContent);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(false);
        scroll.setPannable(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: #f8fafc;");
        BorderPane.setAlignment(scroll, Pos.TOP_CENTER);
        setCenter(scroll);

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

        Label kbLabel = new Label("Amount Entry Keypad");
        kbLabel.setStyle("-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 14px;");

        keypad = new NumericKeypad(true, true, false);
        keypad.setListener(this::handleKeypadInput);
        keypad.setEnterListener(this::savePayout);
        keypad.setMaxHeight(Region.USE_PREF_SIZE);
        keypad.setMaxWidth(Region.USE_PREF_SIZE);
        for (javafx.scene.Node n : keypad.getChildren()) {
            if (n instanceof GridPane gp) {
                VBox.setVgrow(gp, Priority.NEVER);
                gp.setMaxHeight(Region.USE_PREF_SIZE);
            }
        }

        container.getChildren().addAll(kbLabel, keypad);
        return container;
    }

    private void handleKeypadInput(String key) {
        if (activeField == null) {
            activeField = amountField;
        }
        if (!(activeField instanceof TextField)) {
            return;
        }
        TextField tf = (TextField) activeField;
        String currentText = tf.getText();

        if ("C".equals(key)) {
            tf.clear();
        } else if (NumericKeypad.BACKSPACE_KEY.equals(key)) {
            if (!currentText.isEmpty()) {
                tf.setText(currentText.substring(0, currentText.length() - 1));
            }
        } else if (".".equals(key)) {
            if (!currentText.contains(".")) {
                tf.setText(currentText + ".");
            }
        } else if ("00".equals(key)) {
            tf.setText(currentText + "00");
        } else if (key.matches("\\d")) {
            tf.setText(currentText + key);
        }

        tf.requestFocus();
        tf.positionCaret(tf.getText().length());
    }

    private VBox buildFormFromPayout(VendorPayout p) {
        VBox form = new VBox(16);
        form.setPadding(new Insets(25));
        form.setStyle("-fx-background-color: white; -fx-background-radius: 16; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 15, 0, 0, 5);");

        Label formTitle = new Label("Payout details");
        formTitle.setFont(Font.font("System", FontWeight.BOLD, 18));
        formTitle.setStyle("-fx-text-fill: #1e293b;");

        VBox vendorBox = new VBox(8);
        Label vendorLabel = new Label("Vendor *");
        vendorLabel.setStyle("-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 14px;");
        vendorComboBox = new ComboBox<>();
        vendorComboBox.setPromptText("Choose a vendor...");
        vendorComboBox.setMaxWidth(Double.MAX_VALUE);
        vendorComboBox.setStyle("-fx-font-size: 15px; -fx-padding: 5; -fx-background-radius: 8;");
        loadVendorsAndSelect(p);
        vendorBox.getChildren().addAll(vendorLabel, vendorComboBox);

        VBox amountBox = new VBox(8);
        Label amountLabel = new Label("Amount paid *");
        amountLabel.setStyle("-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 14px;");
        amountField = new TextField();
        amountField.setPromptText("0.00");
        amountField.setMaxWidth(Double.MAX_VALUE);
        amountField.setStyle("-fx-font-size: 28px; -fx-padding: 15; -fx-background-radius: 8; -fx-font-weight: 900; -fx-text-fill: #1e293b;");
        UnaryOperator<TextFormatter.Change> numericFilter = change -> {
            String newText = change.getControlNewText();
            if (newText.matches("\\d*\\.?\\d*")) {
                return change;
            }
            return null;
        };
        amountField.setTextFormatter(new TextFormatter<>(numericFilter));
        BigDecimal ap = p.getAmountPaid() != null ? p.getAmountPaid() : BigDecimal.ZERO;
        amountField.setText(ap.stripTrailingZeros().toPlainString());
        amountField.focusedProperty().addListener((obs, o, n) -> {
            if (Boolean.TRUE.equals(n)) {
                activeField = amountField;
            }
        });
        amountBox.getChildren().addAll(amountLabel, amountField);

        VBox paidAtBox = new VBox(8);
        Label paidAtLabel = new Label("Paid at * (yyyy-MM-dd HH:mm:ss)");
        paidAtLabel.setStyle("-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 14px;");
        paidAtField = new TextField();
        paidAtField.setPromptText("2026-04-12 14:30:00");
        paidAtField.setMaxWidth(Double.MAX_VALUE);
        paidAtField.setStyle("-fx-font-size: 14px; -fx-padding: 10; -fx-background-radius: 8;");
        paidAtField.setText(formatPaidAtForField(p.getPaidAt()));
        paidAtField.focusedProperty().addListener((obs, o, n) -> {
            if (Boolean.TRUE.equals(n)) {
                activeField = paidAtField;
            }
        });
        paidAtBox.getChildren().addAll(paidAtLabel, paidAtField);

        VBox methodBox = new VBox(10);
        Label methodLabel = new Label("Payment method * (cheque, invoice, or credit)");
        methodLabel.setStyle("-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 14px;");
        Label methodHint = new Label("Cash payouts cannot be edited; they are tied to drawer cash and reports.");
        methodHint.setWrapText(true);
        methodHint.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");
        paymentMethodGroup = new ToggleGroup();
        chequeRadio = new RadioButton("Cheque");
        chequeRadio.setToggleGroup(paymentMethodGroup);
        invoiceRadio = new RadioButton("Invoice");
        invoiceRadio.setToggleGroup(paymentMethodGroup);
        creditRadio = new RadioButton("Credit");
        creditRadio.setToggleGroup(paymentMethodGroup);
        FlowPane radioBox = new FlowPane(25, 10);
        radioBox.getChildren().addAll(chequeRadio, invoiceRadio, creditRadio);

        paymentMethodGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (chequeNumberLabel != null) {
                updatePaymentReferenceVisibility();
            }
        });

        methodBox.getChildren().addAll(methodLabel, methodHint, radioBox);

        chequeNumberLabel = new Label("Cheque / Reference Number *");
        chequeNumberLabel.setStyle("-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 14px;");
        chequeNumberField = new TextField();
        chequeNumberField.setPromptText("Cheque #");
        chequeNumberField.setStyle("-fx-font-size: 15px; -fx-padding: 12; -fx-background-radius: 8;");
        chequeNumberField.focusedProperty().addListener((obs, o, n) -> {
            if (Boolean.TRUE.equals(n)) {
                activeField = chequeNumberField;
            }
        });

        invoiceNumberLabel = new Label("Invoice Number *");
        invoiceNumberLabel.setStyle("-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 14px;");
        invoiceNumberField = new TextField();
        invoiceNumberField.setPromptText("Invoice #");
        invoiceNumberField.setStyle("-fx-font-size: 15px; -fx-padding: 12; -fx-background-radius: 8;");
        invoiceNumberField.focusedProperty().addListener((obs, o, n) -> {
            if (Boolean.TRUE.equals(n)) {
                activeField = invoiceNumberField;
            }
        });

        populateReferenceFieldsFromPayout(p);
        selectMethodRadio(p.getPaymentMethod());

        VBox notesBox = new VBox(8);
        Label notesLabel = new Label("Notes");
        notesLabel.setStyle("-fx-text-fill: #475569; -fx-font-weight: bold; -fx-font-size: 14px;");
        notesArea = new TextArea();
        notesArea.setPrefRowCount(3);
        notesArea.setWrapText(true);
        notesArea.setStyle("-fx-font-size: 14px; -fx-background-radius: 8;");
        notesArea.setText(p.getNotes() != null ? p.getNotes() : "");
        notesArea.focusedProperty().addListener((obs, o, n) -> {
            if (Boolean.TRUE.equals(n)) {
                activeField = null;
            }
        });

        notesBox.getChildren().addAll(notesLabel, notesArea);

        saveButton = new Button("Save changes");
        saveButton.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 18px; -fx-background-radius: 12; -fx-padding: 15; -fx-cursor: hand;");
        saveButton.setMaxWidth(Double.MAX_VALUE);
        saveButton.setOnAction(e -> savePayout());

        VBox chequeBox = new VBox(8, chequeNumberLabel, chequeNumberField);
        VBox invoiceBox = new VBox(8, invoiceNumberLabel, invoiceNumberField);

        form.getChildren().addAll(
                formTitle,
                new Separator(),
                vendorBox,
                amountBox,
                paidAtBox,
                methodBox,
                chequeBox,
                invoiceBox,
                notesBox,
                saveButton);
        return form;
    }

    private void loadVendorsAndSelect(VendorPayout p) {
        try {
            List<Vendor> vendors = vendorService.getAllVendors();
            vendorComboBox.setItems(FXCollections.observableArrayList(vendors));
            vendorComboBox.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(Vendor v, boolean empty) {
                    super.updateItem(v, empty);
                    setText(empty || v == null ? null : v.getName());
                }
            });
            vendorComboBox.setButtonCell(new ListCell<>() {
                @Override
                protected void updateItem(Vendor v, boolean empty) {
                    super.updateItem(v, empty);
                    setText(empty || v == null ? "Choose a vendor..." : v.getName());
                }
            });
            Vendor match = vendors.stream()
                    .filter(v -> p.getVendorId() != null && p.getVendorId().equals(v.getId()))
                    .findFirst()
                    .orElse(null);
            vendorComboBox.setValue(match);
        } catch (Exception e) {
            logger.error("Error loading vendors", e);
        }
    }

    private void selectMethodRadio(String method) {
        String m = method != null ? method.trim().toUpperCase() : "CHEQUE";
        RadioButton select = switch (m) {
            case "INVOICE" -> invoiceRadio;
            case "CREDIT" -> creditRadio;
            case "CASH" -> chequeRadio;
            default -> chequeRadio;
        };
        select.setSelected(true);
    }

    private static boolean isCashPaymentMethod(String method) {
        return method != null && "CASH".equalsIgnoreCase(method.trim());
    }

    private void populateReferenceFieldsFromPayout(VendorPayout p) {
        String method = p.getPaymentMethod() != null ? p.getPaymentMethod().trim().toUpperCase() : "";
        String cheque = p.getChequeNumber() != null ? p.getChequeNumber() : "";
        String inv = p.getPaymentReference() != null ? p.getPaymentReference() : "";
        if ("CHEQUE".equals(method)) {
            chequeNumberField.setText(cheque);
            invoiceNumberField.setText(inv);
        } else {
            chequeNumberField.clear();
            invoiceNumberField.setText(inv);
        }
    }

    private void updatePaymentReferenceVisibility() {
        Toggle selectedToggle = paymentMethodGroup.getSelectedToggle();
        boolean isCheque = selectedToggle == chequeRadio;
        boolean showsInvoice = selectedToggle == chequeRadio || selectedToggle == invoiceRadio || selectedToggle == creditRadio;

        chequeNumberLabel.setVisible(isCheque);
        chequeNumberLabel.setManaged(isCheque);
        chequeNumberField.setVisible(isCheque);
        chequeNumberField.setManaged(isCheque);
        if (!isCheque) {
            chequeNumberField.clear();
        }

        String invoiceLabelText = selectedToggle == chequeRadio
                ? "Invoice Number (optional)"
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
        if (invoiceRadio.isSelected()) {
            return "INVOICE";
        }
        if (creditRadio.isSelected()) {
            return "CREDIT";
        }
        return "CHEQUE";
    }

    private String resolvePaymentReference(String paymentMethod, String chequeNumber, String invoiceNumber) {
        return switch (paymentMethod) {
            case "CHEQUE", "INVOICE", "CREDIT" -> invoiceNumber.isEmpty() ? null : invoiceNumber;
            default -> null;
        };
    }

    private String formatPaidAtForField(String paidAt) {
        if (paidAt == null || paidAt.isBlank()) {
            return LocalDateTime.now().format(PAID_AT_FORMAT);
        }
        try {
            return LocalDateTime.parse(paidAt, PAID_AT_FORMAT).format(PAID_AT_FORMAT);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(paidAt, DateTimeFormatter.ISO_LOCAL_DATE_TIME).format(PAID_AT_FORMAT);
            } catch (DateTimeParseException ignored2) {
                return paidAt.length() >= 19 ? paidAt.substring(0, 19) : paidAt;
            }
        }
    }

    private String parsePaidAtInput(String text) {
        try {
            return LocalDateTime.parse(text.trim(), PAID_AT_FORMAT).format(PAID_AT_FORMAT);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME).format(PAID_AT_FORMAT);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private void savePayout() {
        if (loadedOriginal == null) {
            return;
        }
        if (isCashPaymentMethod(loadedOriginal.getPaymentMethod())) {
            ToastNotification.showWarning(
                    "Cash payouts cannot be edited.",
                    getScene() != null ? getScene().getWindow() : null);
            return;
        }
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
                ToastNotification.showWarning("Amount must be greater than zero", getScene().getWindow());
                return;
            }
        } catch (Exception e) {
            ToastNotification.showWarning("Invalid amount", getScene().getWindow());
            return;
        }

        String paidAtResolved = parsePaidAtInput(paidAtField.getText());
        if (paidAtResolved == null) {
            ToastNotification.showWarning("Invalid paid date/time", getScene().getWindow());
            return;
        }

        String paymentMethod = getSelectedPaymentMethod();
        String chequeNumber = chequeNumberField.getText().trim();
        String invoiceNumber = invoiceNumberField.getText().trim();

        if ("CHEQUE".equals(paymentMethod) && chequeNumber.isEmpty()) {
            ToastNotification.showWarning("Enter cheque/reference number", getScene().getWindow());
            return;
        }
        if (("INVOICE".equals(paymentMethod) || "CREDIT".equals(paymentMethod)) && invoiceNumber.isEmpty()) {
            ToastNotification.showWarning("Enter invoice number", getScene().getWindow());
            return;
        }

        String paymentRef = resolvePaymentReference(paymentMethod, chequeNumber, invoiceNumber);
        String chequeStored = "CHEQUE".equals(paymentMethod) && !chequeNumber.isEmpty() ? chequeNumber : null;
        String notes = notesArea.getText() != null ? notesArea.getText().trim() : "";

        try {
            VendorPayout updated = vendorPayoutService.updatePaidPayoutDetails(
                    loadedOriginal.getId(),
                    vendor.getId(),
                    vendor.getName(),
                    amount,
                    paymentMethod,
                    paymentRef,
                    chequeStored,
                    notes,
                    paidAtResolved);

            try {
                String storeName = SettingsService.getInstance().getStoreName();
                String receipt = VendorPayoutReceiptBuilder.buildReceipt(storeName, updated);
                ReceiptPrintHelper.printReceiptConditionally(
                        receipt,
                        "VP-" + updated.getId(),
                        getScene().getWindow(),
                        SettingsService.getInstance());
            } catch (Exception pe) {
                logger.error("Error printing receipt", pe);
            }

            ToastNotification.showSuccess("Payout updated", getScene().getWindow());
            if (onBack != null) {
                onBack.run();
            }
        } catch (Exception e) {
            logger.error("Save failed", e);
            ToastNotification.showError("Save failed: " + e.getMessage(), getScene().getWindow());
        }
    }
}
