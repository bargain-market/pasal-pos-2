package com.pos.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.util.Duration;
import javafx.application.Platform;

import com.pos.hardware.HardwareManager;
import com.pos.hardware.pax.PaxPaymentResult;
import com.pos.hardware.pax.PaxTerminalException;
import com.pos.hardware.pax.PaxTerminalService;
import com.pos.api.dto.SaleSubmission;
import com.pos.model.Customer;
import com.pos.model.Payment;
import com.pos.model.SaleItem;
import com.pos.service.SalesService;
import com.pos.service.SettingsService;
import com.pos.service.UserAuthService;
import com.pos.service.CustomerService;
import com.pos.ui.components.ToastNotification;
import com.pos.ui.components.NumericKeypad;
import com.pos.util.ReceiptPrintHelper;
import com.pos.util.SplitPaymentCalculator;
import com.pos.service.ProductManagementService;
import com.pos.ui.util.ThemeConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.sql.SQLException;

/**
 * Full-screen payment page with 3-column layout:
 * Left: Receipt preview
 * Center: Payment method-specific controls
 * Right: Payment method selection
 */
public class PaymentScreen extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(PaymentScreen.class);

    // Services
    private final HardwareManager hardwareManager;
    private final PaxTerminalService paxTerminalService;
    private final SalesService salesService;
    private final SettingsService settingsService;
    private final CustomerService customerService;
    private final ProductManagementService productManagementService;

    // Data passed from SalesScreen. The cartItems list is the same observable
    // instance for the whole app lifetime (SalesScreen only clears it), so the
    // reference is stable; the per-sale scalar values below are refreshed for
    // each checkout via prepareForSale() so this screen can be reused.
    private final ObservableList<SaleItem> cartItems;
    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal currentTotal;
    private BigDecimal listTotal;
    private BigDecimal saleDiscount;
    private String saleDiscountReason;
    private Customer currentCustomer;
    private String orderNote;
    private String selectedPaymentMethod;

    // Callbacks
    private final Runnable onPaymentComplete;
    private final Runnable onCancel;
    private final Runnable onNavigateToSplit;

    // Left panel receipt preview — content is rebuilt per sale on reuse
    private ScrollPane receiptScrollPane;

    // Total-dependent nodes that must be refreshed when the screen is reused for
    // a new sale (they bake in the sale totals at build time).
    private VBox cashQuickAmountsColumn;
    private Label cardAmountLabel;
    private Label cardSurchargeLabel;
    private Label cardStatusLabel;
    private Label ebtAmountLabel;
    private Label splitTotalLabel;

    // UI Components - Center Panel (payment controls)
    private StackPane centerContentStack;
    private VBox cashPaymentPanel;
    private VBox cardPaymentPanel;
    private VBox ebtPaymentPanel;
    private VBox splitPaymentPanel;
    private VBox noSelectionPanel;

    // Cash input components
    private Label amountReceivedLabel;
    private Label changeLabel;
    private Button cashConfirmBtn;
    private StringBuilder cashInputBuffer = new StringBuilder();
    private StringBuilder splitInputBuffer = new StringBuilder();
    private BigDecimal amountReceived = BigDecimal.ZERO;

    // Split payment components
    private ObservableList<SplitPaymentEntry> splitPaymentsList;
    private TableView<SplitPaymentEntry> splitPaymentsTable;
    private Label splitRemainingLabel;
    private BigDecimal splitRemainingBalance;
    // private ComboBox<String> splitMethodCombo; // Removed in favor of buttons
    private TextField splitAmountField;
    private Button splitProcessBtn;

    // New Split Payment UI Components
    private String selectedSplitMethod = "CASH";
    private String lastUsedSplitMethod = "CASH"; // For smart defaults
    private Button splitCashBtn;
    private Button splitCardBtn;
    private Button splitEbtBtn;

    // Payment method buttons (for highlighting)
    private Button cashBtn;
    private Button cardBtn;
    private Button ebtBtn;
    private Button splitBtn;

    // Complete payment button
    private Button completePaymentBtn;

    // Amount due label (for dynamic updates based on payment method)
    private Label amountDueLabel;

    // Payment state
    private String currentPaymentMethod = null;
    private boolean paymentProcessing = false;

    public PaymentScreen(
            HardwareManager hardwareManager,
            ObservableList<SaleItem> cartItems,
            BigDecimal subtotal,
            BigDecimal tax,
            BigDecimal currentTotal,
            BigDecimal saleDiscount,
            String saleDiscountReason,
            Customer currentCustomer,
            String orderNote,
            String initialMethod,
            Runnable onComplete,
            Runnable onCancel,
            Runnable onNavigateToSplit) {

        this.hardwareManager = hardwareManager;
        this.paxTerminalService = PaxTerminalService.getInstance();
        this.salesService = SalesService.getInstance();
        this.settingsService = SettingsService.getInstance();
        this.customerService = CustomerService.getInstance();
        this.productManagementService = ProductManagementService.getInstance();

        this.cartItems = cartItems;
        this.subtotal = subtotal;
        this.tax = tax;
        this.currentTotal = currentTotal;
        this.listTotal = calculateCardTotal(currentTotal);
        this.saleDiscount = saleDiscount != null ? saleDiscount : BigDecimal.ZERO;
        this.saleDiscountReason = saleDiscountReason != null ? saleDiscountReason : "";
        this.currentCustomer = currentCustomer;
        this.orderNote = orderNote != null ? orderNote : "";
        this.selectedPaymentMethod = initialMethod != null ? initialMethod : "CASH";
        this.onPaymentComplete = onComplete;
        this.onCancel = onCancel;
        this.onNavigateToSplit = onNavigateToSplit;

        // Load new design system stylesheet
        getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());
        getStyleClass().add("root");

        initializeUI();
    }

    /**
     * Refresh this screen for a new sale without rebuilding the UI tree. Lets a
     * single PaymentScreen instance be reused across checkouts instead of being
     * reconstructed every time (which was the main source of payment-page lag).
     * Updates the per-sale totals/customer, rebuilds the cart-dependent receipt
     * preview, and resets all transient payment state. Must run on the FX thread.
     */
    public void prepareForSale(
            BigDecimal subtotal,
            BigDecimal tax,
            BigDecimal currentTotal,
            BigDecimal saleDiscount,
            String saleDiscountReason,
            Customer currentCustomer,
            String orderNote,
            String initialMethod) {
        this.subtotal = subtotal;
        this.tax = tax;
        this.currentTotal = currentTotal;
        this.listTotal = calculateCardTotal(currentTotal);
        this.saleDiscount = saleDiscount != null ? saleDiscount : BigDecimal.ZERO;
        this.saleDiscountReason = saleDiscountReason != null ? saleDiscountReason : "";
        this.currentCustomer = currentCustomer;
        this.orderNote = orderNote != null ? orderNote : "";
        this.selectedPaymentMethod = initialMethod != null ? initialMethod : "CASH";

        // Rebuild the cart-dependent receipt preview (reads the fields set above).
        if (receiptScrollPane != null) {
            receiptScrollPane.setContent(createReceiptContent());
        }

        // Refresh the per-method panels, whose amounts/quick-buttons were baked
        // in at build time and would otherwise show the previous sale's totals.
        refreshPanelTotals();

        refreshEbtButtonState();

        resetForNewSale();
    }

    /**
     * Re-evaluate cart EBT eligibility and update the EBT payment button. The
     * button state is only set once during UI construction; this must run on
     * every checkout so a prior non-EBT sale cannot leave EBT disabled.
     */
    private void refreshEbtButtonState() {
        if (ebtBtn == null) {
            return;
        }
        boolean eligible = productManagementService.isCartEbtEligible(cartItems);
        ebtBtn.setDisable(!eligible);
        if (eligible) {
            resetButtonStyle(ebtBtn);
        } else {
            resetButtonStyle(ebtBtn);
            ebtBtn.setStyle(ebtBtn.getStyle() + "-fx-opacity: 0.5;");
        }
    }

    /**
     * Update the total-dependent nodes in the cash/card/EBT/split panels for the
     * current sale. The static structure (keypad, buttons, layout) is untouched.
     */
    private void refreshPanelTotals() {
        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

        // Cash quick amounts (EXACT + suggested amounts depend on currentTotal,
        // and each button captures its amount, so the buttons must be rebuilt).
        if (cashQuickAmountsColumn != null) {
            cashQuickAmountsColumn.getChildren().setAll(createQuickAmountButtons());
        }

        // Card panel amount + surcharge note
        if (cardAmountLabel != null) {
            cardAmountLabel.setText(currencyFormat.format(listTotal));
        }
        if (cardSurchargeLabel != null) {
            if (listTotal.compareTo(currentTotal) > 0) {
                cardSurchargeLabel.setText(
                        "Includes card fee: " + currencyFormat.format(listTotal.subtract(currentTotal)));
                cardSurchargeLabel.setStyle("-fx-text-fill: #F59E0B;");
            } else {
                cardSurchargeLabel.setText("");
            }
        }

        // EBT panel amount
        if (ebtAmountLabel != null) {
            ebtAmountLabel.setText(currencyFormat.format(currentTotal));
        }

        // Split panel total (remaining balance is reset in resetForNewSale)
        if (splitTotalLabel != null) {
            splitTotalLabel.setText("Total: " + currencyFormat.format(listTotal));
        }
    }

    /**
     * Reset all transient payment state so no values leak between sales. Mirrors
     * the initial state produced when the screen is first constructed.
     */
    private void resetForNewSale() {
        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

        currentPaymentMethod = null;
        paymentProcessing = false;
        cashInputBuffer.setLength(0);
        splitInputBuffer.setLength(0);
        amountReceived = BigDecimal.ZERO;

        // Clear any split payments and recompute the remaining balance from the
        // new sale's totals. We call updateSplitRemainingBalance() explicitly
        // because clear() only fires the list listener when the list was
        // non-empty, so a prior balance could otherwise linger.
        if (splitPaymentsList != null) {
            splitPaymentsList.clear();
            if (splitRemainingLabel != null) {
                updateSplitRemainingBalance();
            }
        }

        // Reset cash entry display
        if (amountReceivedLabel != null) {
            amountReceivedLabel.setText(currencyFormat.format(BigDecimal.ZERO));
        }
        if (changeLabel != null) {
            changeLabel.setText(currencyFormat.format(amountReceived.subtract(currentTotal)));
        }
        if (cashConfirmBtn != null) {
            cashConfirmBtn.setDisable(true);
            cashConfirmBtn.setVisible(false);
        }

        // Reset the complete-payment button
        if (completePaymentBtn != null) {
            completePaymentBtn.setText("COMPLETE PAYMENT");
            completePaymentBtn.setDisable(true);
            completePaymentBtn.setVisible(true);
            completePaymentBtn.setManaged(true);
        }

        // Clear method highlight and return to the "no selection" panel
        highlightSelectedButton(null);
        if (noSelectionPanel != null) {
            showPanel(noSelectionPanel);
        }

        // Reset amount due to the cash total
        if (amountDueLabel != null) {
            amountDueLabel.setText(currencyFormat.format(currentTotal));
        }
    }

    private void initializeUI() {
        getStyleClass().add("payment-screen");
        // Style handled by .root in style.css

        // Main 3-column layout
        HBox mainLayout = new HBox(0);
        mainLayout.setMaxWidth(Double.MAX_VALUE);
        mainLayout.setMaxHeight(Double.MAX_VALUE);

        // LEFT PANEL - Receipt Preview (compact for small screens)
        VBox leftPanel = createReceiptPanel();
        leftPanel.setMinWidth(220);
        leftPanel.setPrefWidth(260);
        leftPanel.setMaxWidth(280);

        // CENTER PANEL - Payment Controls (takes remaining space)
        VBox centerPanel = createCenterPanel();
        centerPanel.setMinWidth(350);
        HBox.setHgrow(centerPanel, Priority.ALWAYS);

        // RIGHT PANEL - Payment Methods (compact for small screens)
        VBox rightPanel = createPaymentMethodsPanel();
        rightPanel.setMinWidth(180);
        rightPanel.setPrefWidth(200);
        rightPanel.setMaxWidth(220);
        rightPanel.getStyleClass().add(ThemeConstants.CLASS_GLASS_PANE);

        mainLayout.getChildren().addAll(leftPanel, centerPanel, rightPanel);

        setCenter(mainLayout);
    }

    private BigDecimal calculateCardTotal(BigDecimal cashTotal) {
        try {
            com.pos.service.SettingsService.CardSurchargeSettings settings = settingsService.getCardSurchargeSettings();
            if (settings != null && settings.enabled && settings.percent != null) {
                BigDecimal multiplier = BigDecimal.ONE.add(BigDecimal.valueOf(settings.percent / 100.0));
                return cashTotal.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
            }
        } catch (Exception e) {
            logger.error("Error calculating card total", e);
        }
        return cashTotal;
    }

    // ==================== LEFT PANEL - RECEIPT ====================

    private VBox createReceiptPanel() {
        VBox panel = new VBox(0);
        panel.getStyleClass().add(ThemeConstants.CLASS_GLASS_PANE);

        // Header
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(20));
        header.getStyleClass().add("header-panel"); // Optional, but let's keep it clean

        Label headerLabel = new Label("🧾 ORDER RECEIPT");
        headerLabel.getStyleClass().add(ThemeConstants.CLASS_HEADING_2);
        header.getChildren().add(headerLabel);

        // Receipt content
        VBox receiptContent = new VBox(0);
        receiptContent.setStyle("-fx-background-color: #fafafa; -fx-padding: 15;");

        VBox receiptInner = createReceiptContent();

        ScrollPane scrollPane = new ScrollPane(receiptInner);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        this.receiptScrollPane = scrollPane;

        receiptContent.getChildren().add(scrollPane);
        VBox.setVgrow(receiptContent, Priority.ALWAYS);

        // Back button - always visible at bottom
        Button backBtn = new Button("< BACK TO SALE");
        backBtn.setMaxWidth(Double.MAX_VALUE);
        backBtn.setMinHeight(60);
        backBtn.setPrefHeight(60);
        backBtn.getStyleClass().addAll("button", ThemeConstants.CLASS_BTN_DANGER);
        backBtn.setOnAction(e -> handleCancel());

        panel.getChildren().addAll(header, receiptContent, backBtn);
        return panel;
    }

    private VBox createReceiptContent() {
        VBox content = new VBox(8);
        content.setPadding(new Insets(10));

        // Store header
        String storeName = settingsService.getReceiptHeader();
        if (storeName == null || storeName.isEmpty()) {
            storeName = "POS Store";
        }
        String[] headerLines = storeName.split("\n");

        Label storeLabel = new Label(headerLines[0]);
        storeLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        storeLabel.setStyle("-fx-text-fill: #1a1a1a;");
        storeLabel.setMaxWidth(Double.MAX_VALUE);
        storeLabel.setAlignment(Pos.CENTER);
        content.getChildren().add(storeLabel);

        // Date/time
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm a"));
        Label dateLabel = new Label(dateTime);
        dateLabel.setFont(Font.font("System", FontWeight.NORMAL, 11));
        dateLabel.setStyle("-fx-text-fill: #666;");
        dateLabel.setMaxWidth(Double.MAX_VALUE);
        dateLabel.setAlignment(Pos.CENTER);
        content.getChildren().add(dateLabel);

        content.getChildren().add(createDivider());

        // Items
        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
        for (SaleItem item : cartItems) {
            HBox itemRow = new HBox(5);
            itemRow.setAlignment(Pos.CENTER_LEFT);

            String name = item.getProductName();
            if (name.length() > 20)
                name = name.substring(0, 17) + "...";

            Label nameLabel = new Label(name);
            nameLabel.setFont(Font.font("System", FontWeight.NORMAL, 12));
            nameLabel.setStyle("-fx-text-fill: #333;");
            HBox.setHgrow(nameLabel, Priority.ALWAYS);

            Label qtyLabel = new Label("x" + item.getQuantity());
            qtyLabel.setFont(Font.font("System", FontWeight.NORMAL, 12));
            qtyLabel.setStyle("-fx-text-fill: #666;");
            qtyLabel.setMinWidth(40);

            // Price display with both cash and list prices
            // Always show cash price (base price)
            BigDecimal itemCashTotal = item.getPrice("CASH").multiply(BigDecimal.valueOf(item.getQuantity()));
            Label priceLabel = new Label(currencyFormat.format(itemCashTotal));
            priceLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
            priceLabel.setStyle("-fx-text-fill: #333;");
            priceLabel.setAlignment(Pos.CENTER_RIGHT);
            priceLabel.setMinWidth(90);

            itemRow.getChildren().addAll(nameLabel, qtyLabel, priceLabel);
            content.getChildren().add(itemRow);

            // Show discount if any
            if (item.getTotalDiscount().setScale(2, java.math.RoundingMode.HALF_UP).compareTo(BigDecimal.ZERO) > 0) {
                Label discountLabel = new Label("  🏷 -" + currencyFormat.format(item.getTotalDiscount()));
                discountLabel.setFont(Font.font("System", FontWeight.NORMAL, 10));
                discountLabel.setStyle("-fx-text-fill: #e65100;");
                content.getChildren().add(discountLabel);
            }
        }

        content.getChildren().add(createDivider());

        // Totals
        BigDecimal lineItemDiscounts = cartItems.stream()
                .map(SaleItem::getTotalDiscount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossSubtotal = cartItems.stream()
                .map(SaleItem::getBaseTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        content.getChildren().add(createTotalRow("Subtotal", currencyFormat.format(grossSubtotal), false));

        if (lineItemDiscounts.setScale(2, java.math.RoundingMode.HALF_UP).compareTo(BigDecimal.ZERO) > 0) {
            HBox discRow = createTotalRow("Discounts", "-" + currencyFormat.format(lineItemDiscounts), false);
            ((Label) discRow.getChildren().get(1)).setStyle("-fx-text-fill: #e65100; -fx-font-weight: normal;");
            content.getChildren().add(discRow);
        }

        if (saleDiscount.compareTo(BigDecimal.ZERO) > 0) {
            HBox saleDiscRow = createTotalRow("Sale Discount", "-" + currencyFormat.format(saleDiscount), false);
            ((Label) saleDiscRow.getChildren().get(1)).setStyle("-fx-text-fill: #e65100; -fx-font-weight: normal;");
            content.getChildren().add(saleDiscRow);
        }

        content.getChildren().add(createTotalRow("Tax", currencyFormat.format(tax), false));

        content.getChildren().add(createDivider());

        // Grand totals - show both Cash and Card prices
        HBox grandTotalRow = new HBox(15);
        grandTotalRow.setAlignment(Pos.CENTER);
        grandTotalRow.setStyle("-fx-background-color: #e3f2fd; -fx-padding: 10; -fx-background-radius: 8;");

        // Cash total
        VBox cashTotalBox = new VBox(2);
        cashTotalBox.setAlignment(Pos.CENTER);
        Label cashTotalTitle = new Label("CASH");
        cashTotalTitle.setFont(Font.font("System", FontWeight.NORMAL, 10));
        cashTotalTitle.setStyle("-fx-text-fill: #2e7d32;");
        Label cashTotalValue = new Label(currencyFormat.format(currentTotal));
        cashTotalValue.setFont(Font.font("System", FontWeight.BOLD, 18));
        cashTotalValue.setStyle("-fx-text-fill: #2e7d32;");
        cashTotalBox.getChildren().addAll(cashTotalTitle, cashTotalValue);

        // Divider
        Region divider = new Region();
        divider.setMinWidth(1);
        divider.setMaxWidth(1);
        divider.setMinHeight(40);
        divider.setStyle("-fx-background-color: #ccc;");

        // Card total
        VBox cardTotalBox = new VBox(2);
        cardTotalBox.setAlignment(Pos.CENTER);
        Label cardTotalTitle = new Label("CARD");
        cardTotalTitle.setFont(Font.font("System", FontWeight.NORMAL, 10));
        cardTotalTitle.setStyle("-fx-text-fill: #1565c0;");
        Label cardTotalValue = new Label(currencyFormat.format(listTotal));
        cardTotalValue.setFont(Font.font("System", FontWeight.BOLD, 18));
        cardTotalValue.setStyle("-fx-text-fill: #1565c0;");
        cardTotalBox.getChildren().addAll(cardTotalTitle, cardTotalValue);

        HBox.setHgrow(cashTotalBox, Priority.ALWAYS);
        HBox.setHgrow(cardTotalBox, Priority.ALWAYS);

        grandTotalRow.getChildren().addAll(cashTotalBox, divider, cardTotalBox);
        content.getChildren().add(grandTotalRow);

        // Customer info
        if (currentCustomer != null) {
            content.getChildren().add(createDivider());
            Label custLabel = new Label("👤 " + currentCustomer.getFullName());
            custLabel.setFont(Font.font("System", FontWeight.BOLD, 11));
            custLabel.setStyle("-fx-text-fill: #1565C0;");
            content.getChildren().add(custLabel);
        }

        return content;
    }

    private Separator createDivider() {
        Separator sep = new Separator();
        sep.setStyle("-fx-background-color: #ddd;");
        VBox.setMargin(sep, new Insets(8, 0, 8, 0));
        return sep;
    }

    private HBox createTotalRow(String label, String value, boolean isGrand) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_LEFT);

        Label labelNode = new Label(label);
        HBox.setHgrow(labelNode, Priority.ALWAYS);

        Label valueNode = new Label(value);
        valueNode.setAlignment(Pos.CENTER_RIGHT);

        if (isGrand) {
            labelNode.setFont(Font.font("System", FontWeight.BOLD, 16));
            labelNode.setStyle("-fx-text-fill: #1565C0;");
            valueNode.setFont(Font.font("System", FontWeight.BOLD, 20));
            valueNode.setStyle("-fx-text-fill: #1565C0;");
        } else {
            labelNode.setFont(Font.font("System", FontWeight.NORMAL, 12));
            labelNode.setStyle("-fx-text-fill: #666;");
            valueNode.setFont(Font.font("System", FontWeight.NORMAL, 12));
            valueNode.setStyle("-fx-text-fill: #333;");
        }

        row.getChildren().addAll(labelNode, valueNode);
        return row;
    }

    // ==================== CENTER PANEL - PAYMENT CONTROLS ====================

    private VBox createCenterPanel() {
        VBox panel = new VBox(0);
        panel.getStyleClass().add(ThemeConstants.CLASS_GLASS_PANE);
        panel.setAlignment(Pos.TOP_CENTER);

        // Amount Due Header - Compact for small screens
        VBox amountDueHeader = new VBox(3);
        amountDueHeader.setAlignment(Pos.CENTER);
        amountDueHeader.setPadding(new Insets(10, 15, 10, 15));
        amountDueHeader.setStyle("-fx-background-color: #16213e;");
        amountDueHeader.setMinHeight(80);

        Label dueTitle = new Label("AMOUNT DUE");
        dueTitle.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");

        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
        amountDueLabel = new Label(currencyFormat.format(currentTotal));
        amountDueLabel.getStyleClass().addAll(ThemeConstants.CLASS_HEADING_1, ThemeConstants.CLASS_NUMERIC);
        amountDueLabel.setStyle("-fx-text-fill: #F59E0B;"); // Explicit Amber

        amountDueHeader.getChildren().addAll(dueTitle, amountDueLabel);

        // Scrollable content wrapper for payment panels
        VBox contentWrapper = new VBox(0);

        // Content stack for different payment panels
        centerContentStack = new StackPane();
        centerContentStack.setPadding(new Insets(20));
        // Allow stack to size to content naturally for scrolling
        VBox.setVgrow(centerContentStack, Priority.ALWAYS);

        // Create all payment panels
        noSelectionPanel = createNoSelectionPanel();
        cashPaymentPanel = createCashPaymentPanel();
        cardPaymentPanel = createCardPaymentPanel();
        ebtPaymentPanel = createEbtPaymentPanel();
        splitPaymentPanel = createSplitPaymentPanel();

        // Initially show no selection
        centerContentStack.getChildren().addAll(
                noSelectionPanel, cashPaymentPanel, cardPaymentPanel,
                ebtPaymentPanel, splitPaymentPanel);

        contentWrapper.getChildren().add(centerContentStack);

        // Wrap in ScrollPane to ensure content is always accessible
        ScrollPane scrollPane = new ScrollPane(contentWrapper);
        scrollPane.setFitToWidth(true);
        // Don't set fitToHeight - allow content to expand naturally for scrolling
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: #0f3460;");
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // Complete Payment Button - Compact for small screens
        completePaymentBtn = new Button("COMPLETE PAYMENT");
        completePaymentBtn.setMaxWidth(Double.MAX_VALUE);
        completePaymentBtn.setMinHeight(50);
        completePaymentBtn.setPrefHeight(55);
        completePaymentBtn.getStyleClass().addAll("button", ThemeConstants.CLASS_BTN_SUCCESS);
        completePaymentBtn.setDisable(true);
        completePaymentBtn.setOnAction(e -> processPayment());

        // Hover and visual logic now handled by CSS via getStyleClass()
        showPanel(noSelectionPanel);

        panel.getChildren().addAll(amountDueHeader, scrollPane, completePaymentBtn);
        return panel;
    }

    private void showPanel(VBox panel) {
        // Hide all panels and remove from layout
        noSelectionPanel.setVisible(false);
        noSelectionPanel.setManaged(false);
        cashPaymentPanel.setVisible(false);
        cashPaymentPanel.setManaged(false);
        cardPaymentPanel.setVisible(false);
        cardPaymentPanel.setManaged(false);
        ebtPaymentPanel.setVisible(false);
        ebtPaymentPanel.setManaged(false);
        splitPaymentPanel.setVisible(false);
        splitPaymentPanel.setManaged(false);

        // Show and manage only the active panel
        panel.setVisible(true);
        panel.setManaged(true);
        panel.toFront();

        // Reset main button visibility (hidden only for SPLIT)
        if (panel != splitPaymentPanel && completePaymentBtn != null) {
            completePaymentBtn.setVisible(true);
            completePaymentBtn.setManaged(true);
        }
    }

    private VBox createNoSelectionPanel() {
        VBox panel = new VBox(20);
        panel.setAlignment(Pos.CENTER);
        panel.setStyle("-fx-background-color: transparent;");

        Label icon = new Label("...");
        icon.setFont(Font.font("System", FontWeight.NORMAL, 80));

        Label message = new Label("Select a payment method");
        message.setFont(Font.font("System", FontWeight.NORMAL, 24));
        message.setStyle("-fx-text-fill: #aaa;");

        Label hint = new Label("Choose from the options on the right");
        hint.setFont(Font.font("System", FontWeight.NORMAL, 14));
        hint.setStyle("-fx-text-fill: #666;");

        panel.getChildren().addAll(icon, message, hint);
        return panel;
    }

    private VBox createCashPaymentPanel() {
        VBox panel = new VBox(10);
        panel.setAlignment(Pos.TOP_CENTER);
        panel.setPadding(new Insets(10));
        panel.setStyle(
                "-fx-background-color: #1a3a5c; -fx-background-radius: 12; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 8, 0, 0, 3);");

        // Title - Compact
        Label title = new Label("💵 CASH PAYMENT");
        title.getStyleClass().add(ThemeConstants.CLASS_HEADING_2);
        title.setStyle("-fx-text-fill: white;");

        // Main Content Area (Responsive Wrap Layout)
        // Uses FlowPane to allow columns to wrap on smaller screens
        FlowPane contentArea = new FlowPane();
        contentArea.setAlignment(Pos.TOP_CENTER);
        contentArea.setHgap(12);
        contentArea.setVgap(12);
        contentArea.setPadding(new Insets(5));

        // Orientation horizontal means it lays out LTR, then wraps down
        contentArea.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        // Don't set VBox.setVgrow - allow natural sizing for scrolling

        // COLUMN 1: Keypad - Compact for small screens
        VBox keypadContainer = new VBox();
        keypadContainer.setAlignment(Pos.CENTER);
        keypadContainer.setStyle("-fx-background-color: #0d2137; -fx-background-radius: 12; -fx-padding: 10;");
        keypadContainer.setMinWidth(250);
        keypadContainer.setMaxWidth(280);

        NumericKeypad keypad = new NumericKeypad(true);
        keypad.setListener(this::handleCashKeypadInput);
        keypad.setMaxWidth(260);

        // Make keypad fill available space up to max
        keypad.prefWidthProperty().bind(keypadContainer.widthProperty().subtract(20));
        keypadContainer.getChildren().add(keypad);

        // COLUMN 2: Status & Confirm Button - Compact
        VBox statusColumn = new VBox(12);
        statusColumn.setAlignment(Pos.TOP_CENTER);
        statusColumn.setPrefWidth(220);
        statusColumn.setMinWidth(200);

        // Status Card (Received & Change)
        VBox statusCard = createStatusCard();
        statusCard.setMaxWidth(Double.MAX_VALUE);

        // Confirm Button (initially hidden/disabled) - Compact
        cashConfirmBtn = new Button("✓ CONFIRM");
        cashConfirmBtn.setMaxWidth(Double.MAX_VALUE);
        cashConfirmBtn.setPrefHeight(45);
        cashConfirmBtn.setFont(Font.font("System", FontWeight.BOLD, 14));
        cashConfirmBtn.setStyle(
                "-fx-background-color: #4CAF50; -fx-text-fill: white; " +
                        "-fx-background-radius: 10; -fx-cursor: hand; " +
                        "-fx-effect: dropshadow(three-pass-box, rgba(76,175,80,0.5), 6, 0, 0, 2);");
        cashConfirmBtn.setDisable(true);
        cashConfirmBtn.setVisible(false);
        cashConfirmBtn.setOnAction(e -> processPayment());

        cashConfirmBtn.setOnMouseEntered(e -> {
            if (!cashConfirmBtn.isDisabled()) {
                cashConfirmBtn.setStyle(
                        "-fx-background-color: #66BB6A; -fx-text-fill: white; " +
                                "-fx-background-radius: 10; -fx-cursor: hand; " +
                                "-fx-effect: dropshadow(three-pass-box, rgba(102,187,106,0.6), 8, 0, 0, 3);");
            }
        });
        cashConfirmBtn.setOnMouseExited(e -> {
            if (!cashConfirmBtn.isDisabled()) {
                cashConfirmBtn.setStyle(
                        "-fx-background-color: #4CAF50; -fx-text-fill: white; " +
                                "-fx-background-radius: 10; -fx-cursor: hand; " +
                                "-fx-effect: dropshadow(three-pass-box, rgba(76,175,80,0.5), 6, 0, 0, 2);");
            }
        });

        statusColumn.getChildren().addAll(statusCard, cashConfirmBtn);

        // COLUMN 3: Quick Amounts - Compact
        VBox quickAmountsColumn = new VBox(8);
        quickAmountsColumn.setAlignment(Pos.TOP_CENTER);
        quickAmountsColumn.setPrefWidth(180);
        quickAmountsColumn.setMinWidth(160);

        // Create quick amounts
        VBox quickAmounts = createQuickAmountButtons();
        quickAmounts.setMaxWidth(Double.MAX_VALUE);

        quickAmountsColumn.getChildren().add(quickAmounts);
        this.cashQuickAmountsColumn = quickAmountsColumn;

        // Add all columns to the FlowPane
        contentArea.getChildren().addAll(keypadContainer, statusColumn, quickAmountsColumn);

        // Ensure the scroll pane above can scroll this content
        panel.getChildren().addAll(title, contentArea);

        return panel;
    }

    private VBox createStatusCard() {
        VBox card = new VBox(10);
        card.setPadding(new Insets(12));
        card.setStyle(
                "-fx-background-color: #0d2137; -fx-background-radius: 12; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.2), 4, 0, 0, 2);");

        // Amount Received Row
        VBox receivedBox = new VBox(3);
        Label receivedTitle = new Label("Amount Received");
        receivedTitle.setFont(Font.font("System", FontWeight.NORMAL, 11));
        receivedTitle.setStyle("-fx-text-fill: #aaa;");

        amountReceivedLabel = new Label("$0.00");
        amountReceivedLabel.setFont(Font.font("System", FontWeight.BOLD, 26));
        amountReceivedLabel.setStyle("-fx-text-fill: white;");
        receivedBox.getChildren().addAll(receivedTitle, amountReceivedLabel);

        // Change Due Row
        VBox changeBox = new VBox(3);
        Label changeTitle = new Label("Change Due");
        changeTitle.setStyle("-fx-text-fill: #aaa;");

        changeLabel = new Label("$0.00");
        changeLabel.getStyleClass().addAll(ThemeConstants.CLASS_NUMERIC);
        changeLabel.setStyle("-fx-text-fill: white;");
        changeBox.getChildren().addAll(changeTitle, changeLabel);

        card.getChildren().addAll(receivedBox, changeBox);
        return card;
    }

    private VBox createQuickAmountButtons() {
        VBox container = new VBox(6);
        container.setAlignment(Pos.CENTER);
        // Ensure container fills width
        container.setMaxWidth(Double.MAX_VALUE);

        Label quickLabel = new Label("Quick Amounts");
        quickLabel.setFont(Font.font("System", FontWeight.BOLD, 11));
        quickLabel.setStyle("-fx-text-fill: #aaa;");
        container.getChildren().add(quickLabel);

        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(6);
        grid.setAlignment(Pos.CENTER);
        // Grid should fill width
        grid.setMaxWidth(Double.MAX_VALUE);

        List<BigDecimal> suggestions = getSuggestedAmounts(currentTotal);

        int col = 0;
        int row = 0;
        // Switch to single column for better stacking in 3-column layout
        int maxCols = 1;

        for (BigDecimal amount : suggestions) {
            String label;
            String color;

            if (amount.compareTo(currentTotal) == 0) {
                label = "EXACT";
                color = "#4CAF50"; // Green
            } else {
                NumberFormat nf = NumberFormat.getCurrencyInstance();
                if (amount.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
                    nf.setMaximumFractionDigits(0);
                }
                label = nf.format(amount);

                // Color coding
                if (amount.remainder(new BigDecimal("100")).compareTo(BigDecimal.ZERO) == 0)
                    color = "#795548"; // Brown
                else if (amount.remainder(new BigDecimal("50")).compareTo(BigDecimal.ZERO) == 0)
                    color = "#3F51B5"; // Indigo
                else if (amount.remainder(new BigDecimal("20")).compareTo(BigDecimal.ZERO) == 0)
                    color = "#009688"; // Teal
                else if (amount.remainder(new BigDecimal("10")).compareTo(BigDecimal.ZERO) == 0)
                    color = "#FF9800"; // Orange
                else if (amount.remainder(new BigDecimal("5")).compareTo(BigDecimal.ZERO) == 0)
                    color = "#E91E63"; // Pink
                else
                    color = "#607D8B"; // Blue Grey
            }

            Button btn = createQuickBtn(label, color, () -> setCashAmount(amount));
            // Make buttons flexible
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setPrefHeight(38);
            GridPane.setFillWidth(btn, true);
            GridPane.setHgrow(btn, Priority.ALWAYS);
            grid.add(btn, col, row);

            col++;
            if (col >= maxCols) {
                col = 0;
                row++;
            }
        }

        // Ensure grid columns grow
        ColumnConstraints colC = new ColumnConstraints();
        colC.setPercentWidth(100);
        grid.getColumnConstraints().add(colC);

        // Add Clear button
        Button clearBtn = createQuickBtn("CLEAR", "#e94560", () -> {
            cashInputBuffer.setLength(0);
            amountReceived = BigDecimal.ZERO;
            updateCashDisplay();
        });
        clearBtn.setMaxWidth(Double.MAX_VALUE);
        clearBtn.setPrefHeight(35);

        container.getChildren().addAll(grid, clearBtn);
        return container;
    }

    private Button createQuickBtn(String text, String color, Runnable action) {
        Button btn = new Button(text);
        btn.setPrefHeight(45);
        btn.setFont(Font.font("System", FontWeight.BOLD, 14));
        btn.setStyle(
                "-fx-background-color: " + color + "; -fx-text-fill: white; " +
                        "-fx-background-radius: 8; -fx-cursor: hand;");

        String darkerColor = darkenColor(color);
        btn.setOnMouseEntered(e -> btn.setStyle(
                "-fx-background-color: " + darkerColor + "; -fx-text-fill: white; " +
                        "-fx-background-radius: 8; -fx-cursor: hand;"));
        btn.setOnMouseExited(e -> btn.setStyle(
                "-fx-background-color: " + color + "; -fx-text-fill: white; " +
                        "-fx-background-radius: 8; -fx-cursor: hand;"));

        btn.setOnAction(e -> action.run());
        return btn;
    }

    private VBox createCardPaymentPanel() {
        VBox panel = new VBox(20);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(20));
        panel.setStyle("-fx-background-color: #1a3a5c; -fx-background-radius: 12;");

        Label icon = new Label("CARD");
        icon.setFont(Font.font("System", FontWeight.NORMAL, 64));

        Label title = new Label("CARD PAYMENT");
        title.setFont(Font.font("System", FontWeight.BOLD, 22));
        title.setStyle("-fx-text-fill: #2196F3;");

        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
        // Use list price total for card payments (includes card surcharge)
        Label amount = new Label(currencyFormat.format(listTotal));
        amount.setFont(Font.font("System", FontWeight.BOLD, 36));
        amount.setStyle("-fx-text-fill: white;");
        this.cardAmountLabel = amount;

        // Show card surcharge info if applicable
        Label surchargeInfo = new Label("");
        if (listTotal.compareTo(currentTotal) > 0) {
            BigDecimal surchargeAmount = listTotal.subtract(currentTotal);
            surchargeInfo.setText("Includes card fee: " + currencyFormat.format(surchargeAmount));
            surchargeInfo.setStyle("-fx-text-fill: #F59E0B;");
        }
        this.cardSurchargeLabel = surchargeInfo;

        Label instruction = new Label("Ready to process card payment");
        instruction.setFont(Font.font("System", FontWeight.NORMAL, 14));
        instruction.setStyle("-fx-text-fill: #aaa;");

        Label status = new Label("Terminal: Ready");
        status.setFont(Font.font("System", FontWeight.NORMAL, 13));
        status.setStyle("-fx-text-fill: #4CAF50;");
        this.cardStatusLabel = status;

        Label hint = new Label("Click 'Complete Payment' to charge the card terminal");
        hint.setFont(Font.font("System", FontWeight.NORMAL, 12));
        hint.setStyle("-fx-text-fill: #666;");

        panel.getChildren().addAll(icon, title, amount, surchargeInfo, instruction, status, hint);
        return panel;
    }

    private VBox createEbtPaymentPanel() {
        VBox panel = new VBox(20);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(20));
        panel.setStyle("-fx-background-color: #1a3a5c; -fx-background-radius: 12;");

        Label icon = new Label("EBT");
        icon.setFont(Font.font("System", FontWeight.NORMAL, 64));

        Label title = new Label("EBT PAYMENT");
        title.getStyleClass().add(ThemeConstants.CLASS_HEADING_2);
        title.setStyle("-fx-text-fill: #F59E0B;");

        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
        // Use current total (cash price) for EBT payments
        Label amount = new Label(currencyFormat.format(currentTotal));
        amount.getStyleClass().addAll(ThemeConstants.CLASS_HEADING_1, ThemeConstants.CLASS_NUMERIC);
        this.ebtAmountLabel = amount;

        Label instruction = new Label("Ready to process EBT payment");
        instruction.setFont(Font.font("System", FontWeight.NORMAL, 14));
        instruction.setStyle("-fx-text-fill: #aaa;");

        Label hint = new Label("Click 'Complete Payment' to finalize");
        hint.setFont(Font.font("System", FontWeight.NORMAL, 12));
        hint.setStyle("-fx-text-fill: #666;");

        panel.getChildren().addAll(icon, title, amount, instruction, hint);
        return panel;
    }

    private VBox createSplitPaymentPanel() {
        VBox panel = new VBox(0);
        panel.setAlignment(Pos.TOP_CENTER);
        panel.getStyleClass().add(ThemeConstants.CLASS_GLASS_PANE);

        // Initialize split payment data - always start with list total for splits
        splitRemainingBalance = listTotal;
        splitPaymentsList = FXCollections.observableArrayList();
        splitInputBuffer.setLength(0);
        selectedSplitMethod = "CASH";
        lastUsedSplitMethod = "CASH";

        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

        // 1. Header (Steps & Balance) - Compact
        HBox headerBox = new HBox(15);
        headerBox.setAlignment(Pos.CENTER_LEFT);
        headerBox.setPadding(new Insets(10, 15, 5, 15));

        Label icon = new Label("SPLIT");
        icon.setFont(Font.font("System", FontWeight.NORMAL, 24));

        VBox titleBox = new VBox(0);
        Label title = new Label("SPLIT PAYMENT");
        title.getStyleClass().add(ThemeConstants.CLASS_HEADING_2);

        Label subtitle = new Label("Add multiple payments");
        subtitle.setFont(Font.font("System", FontWeight.NORMAL, 12));
        subtitle.setStyle("-fx-text-fill: #aaa;");
        titleBox.getChildren().addAll(title, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Balance Display (Top Right)
        VBox balanceBox = new VBox(2);
        balanceBox.setAlignment(Pos.CENTER_RIGHT);

        // Show list price total (split mode always uses list price)
        Label totalLabel = new Label("Total: " + currencyFormat.format(listTotal));
        totalLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
        totalLabel.setStyle("-fx-text-fill: #aaa;");
        this.splitTotalLabel = totalLabel;

        splitRemainingLabel = new Label("Remaining: " + currencyFormat.format(splitRemainingBalance));
        splitRemainingLabel.getStyleClass().addAll(ThemeConstants.CLASS_HEADING_2, ThemeConstants.CLASS_NUMERIC);
        splitRemainingLabel.setStyle("-fx-text-fill: var(--primary);");

        balanceBox.getChildren().addAll(totalLabel, splitRemainingLabel);

        headerBox.getChildren().addAll(icon, titleBox, spacer, balanceBox);

        // 1.5. Quick Split Buttons Section - Compact
        VBox quickSplitSection = createQuickSplitButtons();
        quickSplitSection.setPadding(new Insets(5, 15, 5, 15));

        // 2. Main Content (Responsive Flow Layout)
        FlowPane contentBox = new FlowPane();
        contentBox.setHgap(15);
        contentBox.setVgap(15);
        contentBox.setPadding(new Insets(5, 15, 15, 15));
        contentBox.setAlignment(Pos.TOP_CENTER);
        contentBox.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        VBox.setVgrow(contentBox, Priority.ALWAYS);

        // LEFT SIDE: Payment List
        VBox leftSide = new VBox(8);
        leftSide.setMinWidth(250);
        // leftSide.setPrefWidth(350); // Flexible

        // Ensure left side grows nicely in flow pane
        // For FlowPane children we can't use HBox.setHgrow directly for resizing logic
        // the same way
        // But we can bind width to take half space if available
        leftSide.prefWidthProperty().bind(
                contentBox.widthProperty().divide(2).subtract(15) // Try to take half width
        );
        // But constrain min width to avoid squash
        leftSide.minWidthProperty().set(250);
        leftSide.maxWidthProperty().bind(contentBox.widthProperty()); // Don't exceed container

        Label listLabel = new Label("Payments Added");
        listLabel.setStyle("-fx-text-fill: #aaa; -fx-font-weight: bold; -fx-font-size: 11px;");

        splitPaymentsTable = new TableView<>(splitPaymentsList);
        splitPaymentsTable.setPlaceholder(new Label("No payments added"));
        splitPaymentsTable.setStyle("-fx-background-color: #0f2540; -fx-background-radius: 8;");
        splitPaymentsTable.setMinHeight(150); // Ensure responsive height
        VBox.setVgrow(splitPaymentsTable, Priority.ALWAYS);

        TableColumn<SplitPaymentEntry, String> methodCol = new TableColumn<>("Method");
        methodCol.setCellValueFactory(
                data -> new javafx.beans.property.SimpleStringProperty(data.getValue().getPaymentMethod()));
        methodCol.setPrefWidth(100);
        methodCol.setStyle("-fx-alignment: CENTER;");

        TableColumn<SplitPaymentEntry, String> amountCol = new TableColumn<>("Amount");
        amountCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                currencyFormat.format(data.getValue().getAmount())));
        amountCol.setPrefWidth(100);
        amountCol.setStyle("-fx-alignment: CENTER;");

        TableColumn<SplitPaymentEntry, Void> actionCol = new TableColumn<>("");
        actionCol.setCellFactory(param -> new TableCell<>() {
            private final Button deleteBtn = new Button("X");
            {
                deleteBtn.setStyle(
                        "-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 5; -fx-padding: 2 6; -fx-font-size: 10px;");
                deleteBtn.setOnAction(event -> {
                    SplitPaymentEntry entry = getTableView().getItems().get(getIndex());
                    removeSplitPayment(entry);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : deleteBtn);
            }
        });
        actionCol.setPrefWidth(50);

        splitPaymentsTable.getColumns().addAll(methodCol, amountCol, actionCol);
        leftSide.getChildren().addAll(listLabel, splitPaymentsTable);

        // RIGHT SIDE: Input Controls
        VBox rightSide = new VBox(10);
        rightSide.setMinWidth(250);
        rightSide.setStyle("-fx-background-color: #0f2540; -fx-background-radius: 12; -fx-padding: 15;");

        // Similar binding for right side
        rightSide.prefWidthProperty().bind(
                contentBox.widthProperty().divide(2).subtract(15));
        rightSide.maxWidthProperty().bind(contentBox.widthProperty());

        // Method Selection Grid
        Label methodLabel = new Label("1. Select Method");
        methodLabel.setStyle("-fx-text-fill: #aaa; -fx-font-weight: bold; -fx-font-size: 11px;");

        GridPane methodGrid = new GridPane();
        methodGrid.setHgap(8);
        methodGrid.setVgap(8);

        splitCashBtn = createMethodToggleBtn("CASH", "$", ThemeConstants.SUCCESS);
        splitCardBtn = createMethodToggleBtn("CARD", "#", ThemeConstants.PRIMARY);
        splitEbtBtn = createMethodToggleBtn("EBT", "E", "#FF9800");

        methodGrid.add(splitCashBtn, 0, 0);
        methodGrid.add(splitCardBtn, 1, 0);
        methodGrid.add(splitEbtBtn, 0, 1);

        // Ensure buttons resize
        splitCashBtn.setMaxWidth(Double.MAX_VALUE);
        splitCardBtn.setMaxWidth(Double.MAX_VALUE);
        splitEbtBtn.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(splitCashBtn, Priority.ALWAYS);
        GridPane.setHgrow(splitCardBtn, Priority.ALWAYS);
        GridPane.setHgrow(splitEbtBtn, Priority.ALWAYS);

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        methodGrid.getColumnConstraints().addAll(col1, col2);

        selectSplitMethod("CASH"); // Default

        // Amount Input
        Label amountLabel = new Label("2. Enter Amount (or press Enter)");
        amountLabel.setStyle("-fx-text-fill: #aaa; -fx-font-weight: bold; -fx-font-size: 11px;");

        splitAmountField = new TextField();
        splitAmountField.setPromptText("$0.00");
        splitAmountField.setEditable(false); // Use keypad
        splitAmountField.setAlignment(Pos.CENTER_RIGHT);
        splitAmountField.getStyleClass().add(ThemeConstants.CLASS_NUMERIC);
        splitAmountField.setStyle("-fx-font-size: 22px; -fx-background-color: #1a3a5c; -fx-text-fill: white; -fx-background-radius: 8;");

        // Keypad
        NumericKeypad keypad = new NumericKeypad(true, true, false);
        keypad.setListener(this::handleSplitKeypadInput);

        // Quick Actions
        HBox quickActions = new HBox(8);
        quickActions.setAlignment(Pos.CENTER);

        Button fillBtn = createSplitQuickButton("Fill Remaining", "#2196F3", () -> {
            if (splitRemainingBalance.compareTo(BigDecimal.ZERO) > 0) {
                splitInputBuffer.setLength(0);
                splitInputBuffer.append(splitRemainingBalance.setScale(2, RoundingMode.HALF_UP).toString());
                updateSplitAmountDisplay();
            }
        });
        fillBtn.setPrefWidth(110);

        Button clearBtn = createSplitQuickButton("Clear", "#607D8B", () -> {
            splitInputBuffer.setLength(0);
            updateSplitAmountDisplay();
        });
        clearBtn.setPrefWidth(70);

        quickActions.getChildren().addAll(fillBtn, clearBtn);

        // Add Button (secondary - Enter key is primary)
        Button addBtn = new Button("ADD PAYMENT");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setPrefHeight(40);
        addBtn.getStyleClass().addAll("button", ThemeConstants.CLASS_BTN_SUCCESS);
        addBtn.setOnAction(e -> addSplitPayment());

        rightSide.getChildren().addAll(methodLabel, methodGrid, amountLabel, splitAmountField,
                keypad, quickActions,
                addBtn);

        contentBox.getChildren().addAll(leftSide, rightSide);
        
        // Wrap content in ScrollPane for small screen heights
        ScrollPane scrollPane = new ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-padding: 0;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // 3. Bottom Process Button
        HBox bottomBox = new HBox();
        bottomBox.setPadding(new Insets(0, 15, 15, 15));
        bottomBox.setAlignment(Pos.CENTER);

        splitProcessBtn = new Button("COMPLETE SPLIT PAYMENT");
        splitProcessBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(splitProcessBtn, Priority.ALWAYS);
        splitProcessBtn.setPrefHeight(50);
        splitProcessBtn.getStyleClass().addAll("button", ThemeConstants.CLASS_BTN_PRIMARY);
        splitProcessBtn.setDisable(true);
        splitProcessBtn.setOnAction(e -> processSplitPaymentFromPanel());

        // Listeners
        splitPaymentsList.addListener((javafx.collections.ListChangeListener.Change<? extends SplitPaymentEntry> c) -> {
            updateSplitRemainingBalance();
            boolean canProcess = splitRemainingBalance.compareTo(BigDecimal.ZERO) == 0 && !splitPaymentsList.isEmpty();
            splitProcessBtn.setDisable(!canProcess);
            if (canProcess) {
                splitProcessBtn.getStyleClass().add(ThemeConstants.CLASS_BTN_SUCCESS);
            } else {
                splitProcessBtn.getStyleClass().remove(ThemeConstants.CLASS_BTN_SUCCESS);
            }
        });

        panel.getChildren().addAll(headerBox, quickSplitSection, scrollPane, bottomBox);
        bottomBox.getChildren().add(splitProcessBtn);
        return panel;
    }

    private VBox createQuickSplitButtons() {
        VBox container = new VBox(8);
        container.setStyle("-fx-background-color: #0f2540; -fx-background-radius: 12; -fx-padding: 10;");

        Label title = new Label("⚡ Quick Splits");
        title.setFont(Font.font("System", FontWeight.BOLD, 12));
        title.setStyle("-fx-text-fill: white;");
        container.getChildren().add(title);

        FlowPane buttonsRow = new FlowPane();
        buttonsRow.setAlignment(Pos.CENTER);
        buttonsRow.setHgap(8);
        buttonsRow.setVgap(8);

        // 50/50 Split
        Button btn5050 = createQuickSplitButton("50/50", "#4CAF50",
                () -> handleQuickSplit(2, new String[] { "CASH", "CARD" }));
        // Split by 2
        Button btnSplit2 = createQuickSplitButton("Split by 2", "#2196F3", () -> handleQuickSplit(2, null));
        // Split by 3
        Button btnSplit3 = createQuickSplitButton("Split by 3", "#FF9800", () -> handleQuickSplit(3, null));
        // Split by 4
        Button btnSplit4 = createQuickSplitButton("Split by 4", "#9C27B0", () -> handleQuickSplit(4, null));

        buttonsRow.getChildren().addAll(btn5050, btnSplit2, btnSplit3, btnSplit4);
        container.getChildren().add(buttonsRow);

        return container;
    }

    private Button createQuickSplitButton(String text, String color, Runnable action) {
        Button btn = new Button(text);
        btn.setPrefSize(120, 38);
        btn.setMinHeight(38);
        btn.setFont(Font.font("System", FontWeight.BOLD, 14));
        btn.setStyle(
                "-fx-background-color: " + color + "; -fx-text-fill: white; " +
                        "-fx-background-radius: 8; -fx-cursor: hand; " +
                        "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 5, 0, 0, 2);");
        btn.setOnMouseEntered(e -> {
            String darkerColor = darkenColor(color);
            btn.setStyle("-fx-background-color: " + darkerColor + "; -fx-text-fill: white; " +
                    "-fx-background-radius: 8; -fx-cursor: hand; " +
                    "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.4), 7, 0, 0, 3);");
        });
        btn.setOnMouseExited(e -> {
            btn.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; " +
                    "-fx-background-radius: 8; -fx-cursor: hand; " +
                    "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 5, 0, 0, 2);");
        });
        btn.setOnAction(e -> action.run());
        return btn;
    }

    private Button createSplitQuickButton(String text, String color, Runnable action) {
        Button btn = new Button(text);
        btn.setPrefSize(90, 32);
        btn.setFont(Font.font("System", FontWeight.BOLD, 11));
        btn.setStyle(
                "-fx-background-color: " + color + "; -fx-text-fill: white; " +
                        "-fx-background-radius: 6; -fx-cursor: hand;");
        btn.setOnAction(e -> action.run());
        return btn;
    }

    private Button createMethodToggleBtn(String method, String icon, String color) {
        Button btn = new Button(icon + " " + method);
        btn.setPrefSize(100, 45);
        btn.setFont(Font.font("System", FontWeight.BOLD, 11));
        btn.setUserData(color);
        btn.setOnAction(e -> selectSplitMethod(method));

        // Default style
        btn.setStyle("-fx-background-color: #1a3a5c; -fx-text-fill: white; -fx-background-radius: 8; -fx-border-color: "
                + color + "; -fx-border-radius: 8; -fx-cursor: hand;");

        return btn;
    }

    private void selectSplitMethod(String method) {
        selectedSplitMethod = method;
        lastUsedSplitMethod = method; // Remember for smart defaults

        // Reset all buttons
        resetSplitBtnStyle(splitCashBtn);
        resetSplitBtnStyle(splitCardBtn);
        resetSplitBtnStyle(splitEbtBtn);

        // Highlight selected
        Button selectedBtn = null;
        switch (method) {
            case "CASH":
                selectedBtn = splitCashBtn;
                break;
            case "CARD":
                selectedBtn = splitCardBtn;
                break;
            case "EBT":
                selectedBtn = splitEbtBtn;
                break;
        }

        if (selectedBtn != null) {
            String color = (String) selectedBtn.getUserData();
            selectedBtn.setStyle("-fx-background-color: " + color
                    + "; -fx-text-fill: white; -fx-background-radius: 8; -fx-border-color: white; -fx-border-radius: 8; -fx-effect: dropshadow(gaussian, "
                    + color + ", 10, 0.4, 0, 0);");
        }

        // Recalculate remaining balance when switching methods (cash vs card pricing)
        updateSplitRemainingBalance();
    }

    private void resetSplitBtnStyle(Button btn) {
        if (btn == null)
            return;
        String color = (String) btn.getUserData();
        btn.setStyle("-fx-background-color: #1a3a5c; -fx-text-fill: white; -fx-background-radius: 8; -fx-border-color: "
                + color + "; -fx-border-radius: 8; -fx-cursor: hand;");
    }

    private void handleSplitKeypadInput(String key) {
        if ("C".equals(key)) {
            splitInputBuffer.setLength(0);
        } else if (NumericKeypad.BACKSPACE_KEY.equals(key)) {
            if (splitInputBuffer.length() > 0) {
                splitInputBuffer.setLength(splitInputBuffer.length() - 1);
            }
        } else if (NumericKeypad.ENTER_KEY.equals(key) || "ENTER".equals(key)) {
            // Visual feedback before adding
            if (splitInputBuffer.length() > 0) {
                // Flash the amount field to show it's being processed
                String originalStyle = splitAmountField.getStyle();
                splitAmountField.setStyle(originalStyle + "; -fx-background-color: #4CAF50;");

                Timeline flashTimeline = new Timeline(
                        new KeyFrame(Duration.millis(150), e -> splitAmountField.setStyle(originalStyle)));
                flashTimeline.play();
            }
            addSplitPayment();
            return;
        } else {
            // Prevent multiple decimals
            if (".".equals(key) && splitInputBuffer.toString().contains(".")) {
                return;
            }
            // Limit length
            if (splitInputBuffer.length() < 10) {
                splitInputBuffer.append(key);
            }
        }
        updateSplitAmountDisplay();
    }

    private void updateSplitAmountDisplay() {
        if (splitInputBuffer.length() == 0) {
            splitAmountField.setText("");
        } else {
            splitAmountField.setText(splitInputBuffer.toString());
        }
    }

    private void addSplitPayment() {
        String amountStr = splitInputBuffer.toString();

        if (amountStr.isEmpty()) {
            ToastNotification.showWarning("Please enter an amount", getScene().getWindow());
            return;
        }

        try {
            BigDecimal amount = new BigDecimal(amountStr);
            addSplitPaymentWithAmount(selectedSplitMethod, amount);
        } catch (NumberFormatException e) {
            ToastNotification.showWarning("Invalid amount format", getScene().getWindow());
        }
    }

    private void addSplitPaymentWithAmount(String method, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            ToastNotification.showWarning("Amount must be greater than zero", getScene().getWindow());
            return;
        }

        // Allow small tolerance for floating point issues, but generally strict
        if (amount.compareTo(splitRemainingBalance) > 0) {
            NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
            ToastNotification.showWarning("Amount cannot exceed remaining: " +
                    currencyFormat.format(splitRemainingBalance), getScene().getWindow());
            return;
        }

        SplitPaymentEntry entry = new SplitPaymentEntry(method, amount);
        splitPaymentsList.add(entry);

        // Remember last used method for smart defaults
        lastUsedSplitMethod = method;

        // Clear amount field
        splitInputBuffer.setLength(0);
        updateSplitAmountDisplay();

        // Smart defaults: Auto-fill remaining balance for next payment if this isn't
        // the last
        updateSplitRemainingBalance();

        // Auto-fill remaining if there's still a balance
        if (splitRemainingBalance.compareTo(BigDecimal.ZERO) > 0) {
            // Auto-select next method (cycle: CASH -> CARD -> EBT -> CASH)
            String nextMethod = getNextPaymentMethod(method);
            selectSplitMethod(nextMethod);

            // Auto-fill remaining amount
            splitInputBuffer.setLength(0);
            splitInputBuffer.append(splitRemainingBalance.setScale(2, RoundingMode.HALF_UP).toString());
            updateSplitAmountDisplay();
        }

        // Visual feedback: Highlight the newly added row
        animatePaymentAddition();

        // Auto-focus amount field for next entry
        javafx.application.Platform.runLater(() -> {
            if (splitAmountField != null) {
                splitAmountField.requestFocus();
            }
        });
    }

    private String getNextPaymentMethod(String currentMethod) {
        switch (currentMethod) {
            case "CASH":
                return "CARD";
            case "CARD":
                return "EBT";
            case "EBT":
                return "CASH";
            default:
                return "CASH";
        }
    }

    private void handleQuickSplit(int divisions, String[] methods) {
        if (divisions < 2 || divisions > 4) {
            ToastNotification.showWarning("Split must be between 2 and 4", getScene().getWindow());
            return;
        }

        // Clear existing payments if any
        if (!splitPaymentsList.isEmpty()) {
            splitPaymentsList.clear();
        }

        BigDecimal amountPerDivision = listTotal.divide(new BigDecimal(divisions), 2, RoundingMode.HALF_UP);
        BigDecimal totalAllocated = amountPerDivision.multiply(new BigDecimal(divisions));
        BigDecimal remainder = listTotal.subtract(totalAllocated);

        // Default methods if not provided - cycle through CASH, CARD, EBT
        String[] defaultMethods = new String[divisions];
        if (methods == null) {
            String currentMethod = "CASH";
            for (int i = 0; i < divisions; i++) {
                defaultMethods[i] = currentMethod;
                currentMethod = getNextPaymentMethod(currentMethod);
            }
            methods = defaultMethods;
        }

        // Create equal payments
        for (int i = 0; i < divisions; i++) {
            BigDecimal paymentAmount = amountPerDivision;
            // Add remainder to last payment to ensure exact total
            if (i == divisions - 1 && remainder.compareTo(BigDecimal.ZERO) != 0) {
                paymentAmount = paymentAmount.add(remainder);
            }

            String method = methods.length > i ? methods[i] : "CASH";
            SplitPaymentEntry entry = new SplitPaymentEntry(method, paymentAmount);
            splitPaymentsList.add(entry);
        }

        updateSplitRemainingBalance();
        animatePaymentAddition();

        ToastNotification.showSuccess("Split into " + divisions + " equal payments", getScene().getWindow());
    }

    private void animatePaymentAddition() {
        // Simple visual feedback - flash the table
        if (splitPaymentsTable != null) {
            String originalStyle = splitPaymentsTable.getStyle();
            splitPaymentsTable.setStyle(originalStyle + "; -fx-background-color: #1a4a6c;");

            Timeline timeline = new Timeline(
                    new KeyFrame(
                            Duration.millis(200),
                            e -> splitPaymentsTable.setStyle(originalStyle)));
            timeline.play();
        }
    }

    private void removeSplitPayment(SplitPaymentEntry entry) {
        splitPaymentsList.remove(entry);
        updateSplitRemainingBalance();
    }

    private void updateSplitRemainingBalance() {
        BigDecimal totalPaid = splitPaymentsList.stream()
                .map(SplitPaymentEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Calculate required total based on payment methods used
        // If any CARD/EBT payment is added, the total should be based on list price
        BigDecimal requiredTotal = calculateSplitRequiredTotal();
        splitRemainingBalance = requiredTotal.subtract(totalPaid);

        if (splitRemainingBalance.compareTo(BigDecimal.ZERO) < 0) {
            splitRemainingBalance = BigDecimal.ZERO;
        }

        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
        splitRemainingLabel.setText("Remaining: " + currencyFormat.format(splitRemainingBalance));

        // Update the main amount due label to reflect the split payment total
        if (amountDueLabel != null) {
            amountDueLabel.setText(currencyFormat.format(requiredTotal));
        }

        // Update color based on balance
        if (splitRemainingBalance.compareTo(BigDecimal.ZERO) == 0) {
            splitRemainingLabel.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: #4CAF50;");
        } else {
            splitRemainingLabel.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: #2196F3;");
        }
    }

    /**
     * Calculate the required total for split payment based on payment methods used.
     * If any CARD/EBT payment is present, the total should be list price.
     * If only CASH payments, the total is cash price.
     */
    private BigDecimal calculateSplitRequiredTotal() {
        // Split payments always use list price
        return listTotal;
    }

    private void processSplitPaymentFromPanel() {
        if (splitRemainingBalance.compareTo(BigDecimal.ZERO) != 0) {
            ToastNotification.showWarning("Remaining balance must be $0.00 to process", getScene().getWindow());
            return;
        }

        if (splitPaymentsList.isEmpty()) {
            ToastNotification.showWarning("Please add at least one payment", getScene().getWindow());
            return;
        }

        // Convert to Payment objects
        List<Payment> payments = new ArrayList<>();
        for (SplitPaymentEntry entry : splitPaymentsList) {
            payments.add(new Payment(entry.getPaymentMethod(), entry.getAmount()));
        }

        // Process the split payment transaction
        processSplitPaymentTransaction(payments);
    }

    /**
     * Inner class for split payment entries
     */
    public static class SplitPaymentEntry {
        private final String paymentMethod;
        private final BigDecimal amount;

        public SplitPaymentEntry(String paymentMethod, BigDecimal amount) {
            this.paymentMethod = paymentMethod;
            this.amount = amount;
        }

        public String getPaymentMethod() {
            return paymentMethod;
        }

        public BigDecimal getAmount() {
            return amount;
        }
    }

    // ==================== RIGHT PANEL - PAYMENT METHODS ====================

    private VBox createPaymentMethodsPanel() {
        VBox panel = new VBox(0);
        panel.setStyle("-fx-background-color: #16213e;");

        // Header
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(20));
        header.setStyle("-fx-background-color: #0f3460;");

        Label headerLabel = new Label("PAYMENT METHOD");
        headerLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 16px;");
        header.getChildren().add(headerLabel);

        // Payment buttons
        VBox buttonsContainer = new VBox(12);
        buttonsContainer.setPadding(new Insets(20));
        buttonsContainer.setAlignment(Pos.TOP_CENTER);
        VBox.setVgrow(buttonsContainer, Priority.ALWAYS);

        cashBtn = createPaymentMethodButton("💵", "CASH", ThemeConstants.SUCCESS, () -> selectPaymentMethod("CASH"));
        cardBtn = createPaymentMethodButton("💳", "CARD", ThemeConstants.PRIMARY, () -> selectPaymentMethod("CARD"));

        ebtBtn = createPaymentMethodButton("🏦", "EBT", "#FF9800", () -> selectPaymentMethod("EBT"));
        refreshEbtButtonState();

        splitBtn = createPaymentMethodButton("🔀", "SPLIT", "#00897B", () -> selectPaymentMethod("SPLIT"));

        buttonsContainer.getChildren().addAll(cashBtn, cardBtn, ebtBtn, splitBtn);

        panel.getChildren().addAll(header, buttonsContainer);
        return panel;
    }

    private Button createPaymentMethodButton(String icon, String text, String color, Runnable action) {
        Button btn = new Button();
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setPrefHeight(60);
        btn.setAlignment(Pos.CENTER_LEFT);
        btn.setStyle(
                "-fx-background-color: #1a3a5c; -fx-text-fill: white; " +
                        "-fx-background-radius: 12; -fx-cursor: hand; " +
                        "-fx-border-color: " + color + "; -fx-border-width: 2; -fx-border-radius: 12;");

        HBox content = new HBox(10);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPadding(new Insets(0, 10, 0, 10));

        Label iconLabel = new Label(icon);
        iconLabel.setFont(Font.font("System", FontWeight.NORMAL, 24));

        Label textLabel = new Label(text);
        textLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        textLabel.setStyle("-fx-text-fill: white;");

        content.getChildren().addAll(iconLabel, textLabel);
        btn.setGraphic(content);

        btn.getStyleClass().add("payment-method-btn");
        btn.setStyle("-fx-border-color: " + color + ";"); // Keep specific accent border
        // Hover logic handled by CSS

        btn.setOnAction(e -> action.run());
        btn.setUserData(color);
        return btn;
    }

    private void highlightSelectedButton(Button selectedBtn) {
        // Reset all buttons
        resetButtonStyle(cashBtn);
        resetButtonStyle(cardBtn);
        resetButtonStyle(ebtBtn);
        resetButtonStyle(splitBtn);

        // Highlight selected
        if (selectedBtn != null) {
            String color = (String) selectedBtn.getUserData();
            selectedBtn.getStyleClass().add("selected");
            selectedBtn.setStyle(
                    "-fx-background-color: " + color + "; -fx-text-fill: white; " +
                            "-fx-background-radius: 12; -fx-cursor: hand; " +
                            "-fx-border-color: white; -fx-border-width: 3; -fx-border-radius: 12; " +
                            "-fx-effect: dropshadow(gaussian, " + color + ", 15, 0.5, 0, 0);");
        }
    }

    private void resetButtonStyle(Button btn) {
        btn.getStyleClass().remove("selected");
        String color = (String) btn.getUserData();
        btn.setStyle(
                "-fx-background-color: #1a3a5c; -fx-text-fill: white; " +
                        "-fx-background-radius: 12; -fx-cursor: hand; " +
                        "-fx-border-color: " + color + "; -fx-border-width: 2; -fx-border-radius: 12;");
    }

    // ==================== PAYMENT LOGIC ====================

    private void selectPaymentMethod(String method) {
        if ("SPLIT".equals(method)) {
            if (onNavigateToSplit != null) {
                onNavigateToSplit.run();
            } else {
                logger.warn("Split navigation callback not set");
            }
            return;
        }

        currentPaymentMethod = method;
        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

        switch (method) {
            case "CASH":
                showPanel(cashPaymentPanel);
                highlightSelectedButton(cashBtn);
                completePaymentBtn.setDisable(true);
                cashInputBuffer.setLength(0);
                amountReceived = BigDecimal.ZERO;
                if (cashConfirmBtn != null) {
                    cashConfirmBtn.setDisable(true);
                    cashConfirmBtn.setVisible(false);
                }
                updateCashDisplay();
                // Update amount due to cash total
                if (amountDueLabel != null) {
                    amountDueLabel.setText(currencyFormat.format(currentTotal));
                }
                break;
            case "CARD":
                showPanel(cardPaymentPanel);
                highlightSelectedButton(cardBtn);
                completePaymentBtn.setDisable(false);
                // Use list price total for card payments (includes card surcharge)
                amountReceived = listTotal;
                refreshCardTerminalReadyStatus();
                // Update amount due to list total (card pricing)
                if (amountDueLabel != null) {
                    amountDueLabel.setText(currencyFormat.format(listTotal));
                }
                break;
            case "EBT":
                showPanel(ebtPaymentPanel);
                highlightSelectedButton(ebtBtn);
                completePaymentBtn.setDisable(false);
                // EBT is tax exempt - remove tax from total
                // Also usually exempt from surcharge, so use cash price base (currentTotal)
                // minus tax
                BigDecimal ebtTotal = currentTotal.subtract(tax);
                amountReceived = ebtTotal;
                // Update amount due
                if (amountDueLabel != null) {
                    amountDueLabel.setText(currencyFormat.format(ebtTotal));
                }
                break;
            case "SPLIT":
                showPanel(splitPaymentPanel);
                highlightSelectedButton(splitBtn);
                completePaymentBtn.setDisable(true); // Split uses its own dialog
                completePaymentBtn.setVisible(false); // Hide main button to avoid confusion
                completePaymentBtn.setManaged(false); // Don't take up space
                // For split, show list total (split payments always use list price)
                if (amountDueLabel != null) {
                    amountDueLabel.setText(currencyFormat.format(listTotal));
                }
                break;
        }
    }

    private void handleCashKeypadInput(String key) {
        if ("C".equals(key)) {
            cashInputBuffer.setLength(0);
        } else if (NumericKeypad.BACKSPACE_KEY.equals(key)) {
            if (cashInputBuffer.length() > 0) {
                cashInputBuffer.setLength(cashInputBuffer.length() - 1);
            }
        } else if (NumericKeypad.ENTER_KEY.equals(key) || "ENTER".equals(key)) {
            if (amountReceived.compareTo(currentTotal) >= 0) {
                processPayment();
            }
        } else if (".".equals(key)) {
            // Ignore decimal point - we handle cents automatically
        } else {
            // Append digit(s) to buffer
            if (cashInputBuffer.length() < 10) {
                cashInputBuffer.append(key);
            }
        }

        try {
            if (cashInputBuffer.length() > 0) {
                // Parse as cents and convert to dollars
                long cents = Long.parseLong(cashInputBuffer.toString());
                amountReceived = new BigDecimal(cents).divide(new BigDecimal(100), 2, java.math.RoundingMode.HALF_UP);
            } else {
                amountReceived = BigDecimal.ZERO;
            }
        } catch (NumberFormatException e) {
            amountReceived = BigDecimal.ZERO;
        }

        updateCashDisplay();
    }

    private void setCashAmount(BigDecimal amount) {
        amountReceived = amount;
        cashInputBuffer.setLength(0);
        cashInputBuffer.append(amount.toPlainString());
        updateCashDisplay();
    }

    private void updateCashDisplay() {
        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

        amountReceivedLabel.setText(currencyFormat.format(amountReceived));

        BigDecimal change = amountReceived.subtract(currentTotal);
        changeLabel.setText(currencyFormat.format(change));

        if (change.compareTo(BigDecimal.ZERO) >= 0) {
            changeLabel.setStyle("-fx-text-fill: #4CAF50; -fx-font-size: 36px; -fx-font-weight: bold;");
            completePaymentBtn.setDisable(false);
            // Show and enable confirm button
            if (cashConfirmBtn != null) {
                cashConfirmBtn.setDisable(false);
                cashConfirmBtn.setVisible(true);
            }
        } else {
            changeLabel.setStyle("-fx-text-fill: #e94560; -fx-font-size: 36px; -fx-font-weight: bold;");
            completePaymentBtn.setDisable(true);
            // Hide confirm button when amount is insufficient
            if (cashConfirmBtn != null) {
                cashConfirmBtn.setDisable(true);
                cashConfirmBtn.setVisible(false);
            }
        }
    }

    private void updateCardTerminalStatus(String message, String color) {
        if (cardStatusLabel != null) {
            cardStatusLabel.setText(message);
            cardStatusLabel.setStyle("-fx-text-fill: " + color + ";");
        }
    }

    /**
     * Shows the real terminal state when the CARD panel opens, instead of a hardcoded "Ready".
     * Reflects mock/testing mode, missing SDK JARs, or a configured live terminal.
     */
    private void refreshCardTerminalReadyStatus() {
        if (paymentProcessing) {
            return;
        }
        if (paxTerminalService.shouldUseTerminalForCard()) {
            if (!paxTerminalService.isSdkAvailable()) {
                updateCardTerminalStatus("Terminal: " + paxTerminalService.getStatusSummary(), "#e94560");
            } else {
                updateCardTerminalStatus("Terminal: Ready — " + paxTerminalService.getStatusSummary(), "#4CAF50");
            }
        } else if (paxTerminalService.isTestingMode()) {
            updateCardTerminalStatus("Terminal: Testing mode (mock approval)", "#FF9800");
        } else {
            updateCardTerminalStatus("Terminal: Manual / external card processing", "#4CAF50");
        }
    }

    /**
     * Locks the payment-method buttons while a transaction is in flight so the cashier can't
     * abandon a card payment that the terminal is still processing.
     */
    private void setPaymentMethodButtonsLocked(boolean locked) {
        if (cashBtn != null) {
            cashBtn.setDisable(locked);
        }
        if (cardBtn != null) {
            cardBtn.setDisable(locked);
        }
        if (ebtBtn != null) {
            ebtBtn.setDisable(locked);
        }
        if (splitBtn != null) {
            splitBtn.setDisable(locked);
        }
    }

    private String generateProvisionalSaleRef() {
        return "POS" + System.currentTimeMillis();
    }

    private void processPayment() {
        if (paymentProcessing)
            return;

        // Validate active shift before processing payment
        if (!com.pos.service.ShiftService.getInstance().hasActiveShift()) {
            ToastNotification.showError("No active shift. Please start a shift to process transactions.",
                    getScene().getWindow());
            return;
        }

        if (currentPaymentMethod == null) {
            ToastNotification.showWarning("Please select a payment method", getScene().getWindow());
            return;
        }

        paymentProcessing = true;
        completePaymentBtn.setDisable(true);
        completePaymentBtn.setText("Processing...");
        setPaymentMethodButtonsLocked(true);

        BigDecimal finalTxTotal = currentTotal;
        BigDecimal finalTxTax = tax;

        BigDecimal change = BigDecimal.ZERO;
        if ("CASH".equals(currentPaymentMethod)) {
            change = amountReceived.subtract(currentTotal);
            if (change.compareTo(BigDecimal.ZERO) < 0) {
                ToastNotification.showError("Insufficient payment amount", getScene().getWindow());
                paymentProcessing = false;
                completePaymentBtn.setDisable(false);
                completePaymentBtn.setText("COMPLETE PAYMENT");
                return;
            }
        } else if ("EBT".equals(currentPaymentMethod)) {
            // EBT: Zero tax, Total = Amount Received (which is currentTotal - tax)
            finalTxTax = BigDecimal.ZERO;
            finalTxTotal = amountReceived; // Use the already calculated ebtTotal
        } else if ("CARD".equals(currentPaymentMethod)) {
            finalTxTotal = listTotal;
            amountReceived = listTotal;
        } else {
            amountReceived = currentTotal;
        }

        final BigDecimal finalChange = change;
        final BigDecimal finalAmountReceived = amountReceived;
        final BigDecimal finalTxTotalValue = finalTxTotal;
        final BigDecimal finalTxTaxValue = finalTxTax;
        final String provisionalSaleRef = generateProvisionalSaleRef();

        final String cashierName = UserAuthService.getInstance().getCurrentUserName();
        final List<SaleItem> itemsSnapshot = new ArrayList<>(cartItems);
        final String paymentMethodSnapshot = currentPaymentMethod;

        new Thread(() -> {
            String saleId = null;
            String receiptText = null;
            IllegalArgumentException validationError = null;
            Exception processingError = null;
            PaxPaymentResult paxResult = null;
            try {
                if ("CARD".equals(paymentMethodSnapshot) && paxTerminalService.shouldUseTerminalForCard()) {
                    Platform.runLater(() -> updateCardTerminalStatus(
                            "Terminal: Waiting for card — insert / tap / swipe…", "#FF9800"));
                    paxResult = paxTerminalService.processSale(finalTxTotalValue, provisionalSaleRef);
                    if (!paxResult.approved) {
                        throw new PaxTerminalException(
                                paxResult.message != null ? paxResult.message : "Card payment declined");
                    }
                    Platform.runLater(() -> updateCardTerminalStatus("Terminal: Approved", "#4CAF50"));
                } else if ("CARD".equals(paymentMethodSnapshot) && paxTerminalService.isTestingMode()) {
                    paxResult = paxTerminalService.processSale(finalTxTotalValue, provisionalSaleRef);
                }

                SaleSubmission.PaymentDetails paymentDetails = paxResult != null ? paxResult.toPaymentDetails() : null;
                saleId = salesService.processSaleAndGetId(
                        itemsSnapshot,
                        paymentMethodSnapshot,
                        finalTxTotalValue,
                        cashierName,
                        saleDiscount,
                        finalTxTaxValue,
                        finalAmountReceived,
                        finalChange,
                        paymentDetails);
                if (saleId != null) {
                    receiptText = generateReceipt(paymentMethodSnapshot, finalAmountReceived, finalChange, saleId);
                }
            } catch (IllegalArgumentException e) {
                validationError = e;
            } catch (PaxTerminalException e) {
                processingError = e;
                logger.error("PAX card payment failed", e);
            } catch (Exception e) {
                processingError = e;
                logger.error("Error processing payment", e);
            }

            final String resultSaleId = saleId;
            final String resultReceipt = receiptText;
            final IllegalArgumentException resultValidation = validationError;
            final Exception resultError = processingError;

            Platform.runLater(() -> {
                if (resultValidation != null) {
                    resetCompletePaymentButton();
                    ToastNotification.showError(resultValidation.getMessage(), getScene().getWindow());
                    return;
                }
                if (resultError != null) {
                    resetCompletePaymentButton();
                    if ("CARD".equals(paymentMethodSnapshot)) {
                        updateCardTerminalStatus("Terminal: Declined", "#e94560");
                    }
                    String message = resultError instanceof PaxTerminalException
                            ? resultError.getMessage()
                            : "Failed to process payment";
                    ToastNotification.showError(message, getScene().getWindow());
                    return;
                }
                if (resultSaleId != null) {
                    finalizeSuccessfulPayment(resultSaleId, resultReceipt, paymentMethodSnapshot, finalAmountReceived,
                            finalChange, finalTxTotalValue);
                } else {
                    resetCompletePaymentButton();
                    ToastNotification.showError("Failed to process payment", getScene().getWindow());
                }
            });
        }, "PaymentProcessor").start();
    }

    private void resetCompletePaymentButton() {
        paymentProcessing = false;
        setPaymentMethodButtonsLocked(false);
        if (completePaymentBtn != null) {
            completePaymentBtn.setDisable(false);
            completePaymentBtn.setText("COMPLETE PAYMENT");
        }
    }

    private void resetSplitPaymentButton() {
        paymentProcessing = false;
        setPaymentMethodButtonsLocked(false);
        if (splitProcessBtn != null) {
            splitProcessBtn.setDisable(false);
            splitProcessBtn.setText("PROCESS SPLIT PAYMENT");
        }
    }

    private void finalizeSuccessfulPayment(String saleId, String receiptText, String paymentMethod,
            BigDecimal amountReceived, BigDecimal change, BigDecimal saleTotal) {
        if (currentCustomer != null) {
            customerService.recordSale(currentCustomer.getId(), saleTotal);
        }

        if (receiptText != null) {
            try {
                ReceiptPrintHelper.printReceiptConditionally(receiptText, saleId, getScene().getWindow(),
                        settingsService);
            } catch (Exception e) {
                logger.error("Failed to print receipt for sale: {}", saleId, e);
                ToastNotification.showWarning("Payment successful but receipt printing failed", getScene().getWindow());
            }
        }

        if ("CASH".equals(paymentMethod)) {
            hardwareManager.openCashDrawer();
        }

        if ("CASH".equals(paymentMethod) && change.compareTo(BigDecimal.ZERO) > 0) {
            ToastNotification.showSuccess("Payment completed! Give change: " +
                    NumberFormat.getCurrencyInstance().format(change), getScene().getWindow());

            if (changeLabel != null) {
                changeLabel.setStyle("-fx-text-fill: #4CAF50; -fx-font-size: 48px; -fx-font-weight: bold;");
                Timeline pulse = new Timeline(
                        new KeyFrame(Duration.ZERO,
                                evt -> changeLabel.setStyle(
                                        "-fx-text-fill: #4CAF50; -fx-font-size: 48px; -fx-font-weight: bold;")),
                        new KeyFrame(Duration.millis(500),
                                evt -> changeLabel.setStyle(
                                        "-fx-text-fill: #2E7D32; -fx-font-size: 52px; -fx-font-weight: bold;")),
                        new KeyFrame(Duration.millis(1000), evt -> changeLabel
                                .setStyle("-fx-text-fill: #4CAF50; -fx-font-size: 48px; -fx-font-weight: bold;")));
                pulse.setCycleCount(8);
                pulse.play();
            }

            Timeline delayNavigation = new Timeline(
                    new KeyFrame(Duration.seconds(8), evt -> {
                        if (onPaymentComplete != null) {
                            onPaymentComplete.run();
                        }
                    }));
            delayNavigation.play();
        } else {
            ToastNotification.showSuccess("Payment completed!", getScene().getWindow());
            if (onPaymentComplete != null) {
                onPaymentComplete.run();
            }
        }
    }

    public void processSplitPaymentTransaction(List<Payment> payments) {
        if (paymentProcessing)
            return;

        paymentProcessing = true;
        setPaymentMethodButtonsLocked(true);
        if (splitProcessBtn != null) {
            splitProcessBtn.setDisable(true);
            splitProcessBtn.setText("Processing...");
        }

        Map<String, BigDecimal> departmentTaxRates = getDepartmentTaxRates();
        SplitPaymentCalculator.SplitPaymentResult splitResult = SplitPaymentCalculator.calculateSplitPayment(
                cartItems.stream().toList(),
                payments,
                saleDiscount,
                departmentTaxRates);

        BigDecimal calculatedTotal = splitResult.cashTotal.add(splitResult.cardTotal);

        BigDecimal totalPaid = payments.stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal difference = calculatedTotal.subtract(totalPaid).abs();
        List<Payment> paymentsToProcess = new ArrayList<>(payments);
        if (difference.compareTo(new BigDecimal("0.01")) > 0) {
            logger.warn("Payment total (${}) does not match calculated total (${}), difference: ${}",
                    totalPaid, calculatedTotal, difference);
            if (!paymentsToProcess.isEmpty()) {
                Payment lastPayment = paymentsToProcess.get(paymentsToProcess.size() - 1);
                BigDecimal adjustedAmount = lastPayment.getAmount().add(calculatedTotal.subtract(totalPaid));
                if (adjustedAmount.compareTo(BigDecimal.ZERO) < 0) {
                    ToastNotification.showError(
                            "Payment amounts do not match calculated total. Please adjust payments.",
                            getScene().getWindow());
                    resetSplitPaymentButton();
                    return;
                }
                paymentsToProcess.set(paymentsToProcess.size() - 1,
                        new Payment(lastPayment.getPaymentMethod(), adjustedAmount));
            }
        }

        final List<Payment> finalPayments = paymentsToProcess;
        final BigDecimal finalCalculatedTotal = calculatedTotal;
        final SplitPaymentCalculator.SplitPaymentResult finalSplitResult = splitResult;
        final List<SaleItem> itemsSnapshot = new ArrayList<>(cartItems);
        final String cashierName = UserAuthService.getInstance().getCurrentUserName();
        final String splitSaleRef = generateProvisionalSaleRef();

        new Thread(() -> {
            String saleId = null;
            String receiptText = null;
            IllegalArgumentException validationError = null;
            Exception processingError = null;
            try {
                SaleSubmission.PaymentDetails paymentDetails = null;
                List<BigDecimal> cardAmounts = finalPayments.stream()
                        .filter(p -> "CARD".equals(p.getPaymentMethod()))
                        .map(Payment::getAmount)
                        .toList();

                if (!cardAmounts.isEmpty()
                        && (paxTerminalService.shouldUseTerminalForCard() || paxTerminalService.isTestingMode())) {
                    List<PaxPaymentResult> paxResults = paxTerminalService.processCardSplitPayments(
                            cardAmounts, splitSaleRef);
                    if (!paxResults.isEmpty()) {
                        paymentDetails = paxResults.get(0).toPaymentDetails();
                    }
                }

                saleId = salesService.processSaleAndGetId(
                        itemsSnapshot,
                        "SPLIT",
                        finalCalculatedTotal,
                        cashierName,
                        saleDiscount,
                        finalSplitResult.totalTax,
                        finalPayments,
                        paymentDetails);
                if (saleId != null) {
                    receiptText = generateSplitPaymentReceipt(finalPayments, saleId, finalSplitResult);
                }
            } catch (IllegalArgumentException e) {
                validationError = e;
            } catch (PaxTerminalException e) {
                processingError = e;
                logger.error("PAX split card payment failed", e);
            } catch (Exception e) {
                processingError = e;
                logger.error("Error processing split payment", e);
            }

            final String resultSaleId = saleId;
            final String resultReceipt = receiptText;
            final IllegalArgumentException resultValidation = validationError;
            final Exception resultError = processingError;

            Platform.runLater(() -> {
                if (resultValidation != null) {
                    resetSplitPaymentButton();
                    ToastNotification.showError(resultValidation.getMessage(), getScene().getWindow());
                    return;
                }
                if (resultError != null || resultSaleId == null) {
                    resetSplitPaymentButton();
                    String message = resultError instanceof PaxTerminalException
                            ? resultError.getMessage()
                            : "Failed to process split payment";
                    ToastNotification.showError(message, getScene().getWindow());
                    return;
                }

                if (currentCustomer != null) {
                    customerService.recordSale(currentCustomer.getId(), finalCalculatedTotal);
                }
                if (resultReceipt != null) {
                    ReceiptPrintHelper.printReceiptConditionally(resultReceipt, resultSaleId, getScene().getWindow(),
                            settingsService);
                }
                boolean hasCashPayment = finalPayments.stream()
                        .anyMatch(p -> "CASH".equals(p.getPaymentMethod()));
                if (hasCashPayment) {
                    hardwareManager.openCashDrawer();
                }
                ToastNotification.showSuccess("Split payment completed!", getScene().getWindow());
                if (onPaymentComplete != null) {
                    onPaymentComplete.run();
                }
            });
        }, "SplitPaymentProcessor").start();
    }

    private String generateReceipt(String paymentMethod, BigDecimal amountReceived, BigDecimal change, String saleId) {
        StringBuilder sb = new StringBuilder();

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
        sb.append("Date: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"))).append("\n");

        String cashierName = UserAuthService.getInstance().getCurrentUserName();
        sb.append("Cashier: ").append(cashierName != null ? cashierName : "N/A").append("\n");

        if (currentCustomer != null) {
            sb.append("Customer: ").append(currentCustomer.getFullName()).append("\n");
        }
        sb.append("\n");

        // --- ITEMS SECTION ---
        // Table Header: Item (20 chars) | Qty (3) | Price (8) | Total (9)
        // Total width: 20 + 1 + 3 + 1 + 8 + 1 + 9 = 43 (approx 42 chars fit well)
        // Adjusted: Item(16) Qty(3) Price(10) Total(10) -> 16+1+3+1+10+1+10 = 42
        // For card payments, show the Card Price and the Cash Price side by side (both
        // unit prices) so the customer can clearly compare. Cash/EBT receipts keep the
        // simple unit-price + line-total layout.
        boolean showDualPricing = !("CASH".equals(paymentMethod) || "EBT".equals(paymentMethod));
        if (showDualPricing) {
            sb.append(String.format("%-16s %3s %10s %10s\n", "Item", "Qty", "Card Price", "Cash Price"));
        } else {
            sb.append(String.format("%-16s %3s %10s %10s\n", "Item", "Qty", "Price", "Total"));
        }
        sb.append("-".repeat(42)).append("\n");

        for (SaleItem item : cartItems) {
            String name = item.getProductName();
            // Truncate name if too long for single line, or wrap?
            // For now, let's print full name on its own line if it's long,
            // or just truncate to keep it clean.
            // Decision: Truncate to 16 chars to keep alignment perfect.
            String displayName = name.length() > 16 ? name.substring(0, 16) : name;

            if (showDualPricing) {
                // Card Price | Cash Price (both per-unit, directly comparable)
                BigDecimal cardUnit = item.getPrice("CARD");
                BigDecimal cashUnit = item.getPrice("CASH");
                sb.append(String.format("%-16s %3d %10.2f %10.2f\n",
                        displayName, item.getQuantity(), cardUnit, cashUnit));
            } else {
                BigDecimal unitPrice = item.getPrice("CASH");
                BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
                sb.append(String.format("%-16s %3d %10.2f %10.2f\n",
                        displayName, item.getQuantity(), unitPrice, lineTotal));
            }

            // If name was truncated, maybe print full name on next line?
            // Optional: for now keep it simple as per request for "proper" look.

            // Show discount if exists
            if (item.getTotalDiscount().compareTo(BigDecimal.ZERO) > 0) {
                sb.append(String.format("   Disc: -%8.2f\n", item.getTotalDiscount()));
            }
        }
        sb.append("-".repeat(42)).append("\n");

        // --- TOTALS SECTION ---
        // Right align nums
        boolean isCashOrEbt = "CASH".equals(paymentMethod) || "EBT".equals(paymentMethod);
        BigDecimal subtotalVal = isCashOrEbt ? subtotal : calculateCardTotal(subtotal);

        sb.append(String.format("%20s %21.2f\n", "Subtotal:", subtotalVal));

        BigDecimal totalDiscount = cartItems.stream()
                .map(SaleItem::getTotalDiscount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).add(saleDiscount);

        if (totalDiscount.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("%20s %21.2f\n", "Discount:", totalDiscount.negate()));
        }

        BigDecimal displayTax = "EBT".equals(paymentMethod) ? BigDecimal.ZERO : tax;
        sb.append(String.format("%20s %21.2f\n", "Tax:", displayTax));

        sb.append("=".repeat(42)).append("\n");
        // Grand Total
        boolean isEbt = "EBT".equals(paymentMethod);
        BigDecimal finalTotal = isCashOrEbt ? (isEbt ? amountReceived : currentTotal) : listTotal;
        sb.append(String.format("%-20s %21.2f\n", "TOTAL:", finalTotal));
        sb.append("=".repeat(42)).append("\n");

        // Payment Details
        sb.append(String.format("%-20s %21s\n", "Payment Method:", paymentMethod));
        if ("CASH".equals(paymentMethod)) {
            sb.append(String.format("%20s %21.2f\n", "Cash Received:", amountReceived));
            sb.append(String.format("%20s %21.2f\n", "Change:", change));
        }

        sb.append("\n");

        // --- FOOTER ---
        if (!footer.isEmpty()) {
            sb.append(centerText(footer, 42)).append("\n");
        }

        // Add barcode placeholder (will be rendered by PDF service)
        if (saleId != null && !saleId.isEmpty()) {
            sb.append("\n");
            // The marker for Barcode rendering. Ensure it starts at the beginning of the
            // line
            if (!saleId.startsWith("SALE-")) {
                sb.append("SALE-");
            }
            sb.append(saleId).append("\n");
        }

        return sb.toString();
    }

    private String generateSplitPaymentReceipt(List<Payment> payments, String saleId,
            SplitPaymentCalculator.SplitPaymentResult splitResult) {
        StringBuilder sb = new StringBuilder();

        String storeName = settingsService.getStoreName();
        String storeAddress = settingsService.getStoreAddress();
        String storePhone = settingsService.getStorePhone();
        String header = settingsService.getReceiptHeader();
        String footer = settingsService.getReceiptFooter();

        // Header
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

        sb.append("Date: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"))).append("\n");
        String cashierName = UserAuthService.getInstance().getCurrentUserName();
        sb.append("Cashier: ").append(cashierName != null ? cashierName : "N/A").append("\n");

        if (currentCustomer != null) {
            sb.append("Customer: ").append(currentCustomer.getFullName()).append("\n");
        }
        sb.append("\n");

        // Table Header - split payments involve a card portion, so show both the card and
        // cash unit prices side by side for clarity.
        sb.append(String.format("%-16s %3s %10s %10s\n", "Item", "Qty", "Card Price", "Cash Price"));
        sb.append("-".repeat(42)).append("\n");

        for (SaleItem item : cartItems) {
            String name = item.getProductName();
            String displayName = name.length() > 16 ? name.substring(0, 16) : name;

            // Card Price | Cash Price (both per-unit). The totals section below reflects the
            // actual split between cash and card payments.
            BigDecimal cardUnit = item.getPrice("CARD");
            BigDecimal cashUnit = item.getPrice("CASH");

            sb.append(String.format("%-16s %3d %10.2f %10.2f\n",
                    displayName, item.getQuantity(), cardUnit, cashUnit));

            if (item.getTotalDiscount().compareTo(BigDecimal.ZERO) > 0) {
                sb.append(String.format("   Disc: -%8.2f\n", item.getTotalDiscount()));
            }
        }
        sb.append("-".repeat(42)).append("\n");

        // Totals
        BigDecimal subtotalBeforeDiscounts = cartItems.stream()
                .map(SaleItem::getBaseTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        sb.append(String.format("%20s %21.2f\n", "Subtotal:", subtotalBeforeDiscounts));

        BigDecimal totalDiscount = cartItems.stream()
                .map(SaleItem::getTotalDiscount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).add(saleDiscount);

        if (totalDiscount.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("%20s %21.2f\n", "Discount:", totalDiscount.negate()));
        }

        // Show total tax (combined)
        sb.append(String.format("%20s %21.2f\n", "Tax:", splitResult.totalTax));
        sb.append("=".repeat(42)).append("\n");

        BigDecimal calculatedTotal = splitResult.cashTotal.add(splitResult.cardTotal);
        sb.append(String.format("%-20s %21.2f\n", "TOTAL:", calculatedTotal));
        sb.append("=".repeat(42)).append("\n");

        sb.append(centerText("*** SPLIT PAYMENT ***", 42)).append("\n");

        // Breakdown
        BigDecimal totalCashPaid = payments.stream()
                .filter(p -> "CASH".equals(p.getPaymentMethod()))
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCardPaid = payments.stream()
                .filter(p -> "CARD".equals(p.getPaymentMethod()) || "EBT".equals(p.getPaymentMethod()))
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalCashPaid.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("%-20s %21.2f\n", "Cash Paid:", totalCashPaid));
        }
        if (totalCardPaid.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("%-20s %21.2f\n", "Card/EBT Paid:", totalCardPaid));
        }

        sb.append("\n");

        if (!footer.isEmpty()) {
            sb.append(centerText(footer, 42)).append("\n");
        }

        if (saleId != null && !saleId.isEmpty()) {
            sb.append("\n");
            sb.append("SALE-").append(saleId);
        }

        return sb.toString();
    }

    private Map<String, BigDecimal> getDepartmentTaxRates() {
        Map<String, BigDecimal> taxRates = new HashMap<>();

        // Get unique department IDs from cart items
        Set<String> departmentIds = new java.util.HashSet<>();
        for (SaleItem item : cartItems) {
            String deptId = item.getProduct().getDepartmentId();
            if (deptId != null && !deptId.isEmpty()) {
                departmentIds.add(deptId);
            }
        }

        // Fetch tax rates for each department
        for (String deptId : departmentIds) {
            try {
                ProductManagementService.Department dept = productManagementService.getDepartmentById(deptId);
                if (dept != null && dept.taxEnabled && dept.taxRate != null) {
                    taxRates.put(deptId, new BigDecimal(dept.taxRate));
                }
            } catch (SQLException e) {
                logger.error("Error getting department tax rate for dept: " + deptId, e);
            }
        }

        return taxRates;
    }

    private String centerText(String text, int width) {
        if (text == null || text.length() >= width) {
            return text;
        }
        int padding = (width - text.length()) / 2;
        return " ".repeat(padding) + text;
    }

    private void handleCancel() {
        if (onCancel != null) {
            onCancel.run();
        }
    }

    private List<BigDecimal> getSuggestedAmounts(BigDecimal total) {
        Set<BigDecimal> amounts = new TreeSet<>();

        // 1. Exact Amount
        amounts.add(total);

        // 2. Next Dollar (if total is not a whole number)
        if (total.scale() > 0 && total.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0) {
            amounts.add(total.setScale(0, RoundingMode.UP));
        }

        // 3. Common Bills and Multiples
        BigDecimal[] denominations = {
                new BigDecimal("5"),
                new BigDecimal("10"),
                new BigDecimal("20"),
                new BigDecimal("50"),
                new BigDecimal("100")
        };

        for (BigDecimal bill : denominations) {
            // Find the smallest multiple of this bill that is >= total
            BigDecimal multiple = total.divide(bill, 0, RoundingMode.CEILING).multiply(bill);
            amounts.add(multiple);

            // If the multiple is very close to the total (within $5), add the next multiple
            // This handles cases like Total $4.50 -> Suggest $5 AND $10
            if (multiple.subtract(total).compareTo(new BigDecimal("5")) < 0) {
                amounts.add(multiple.add(bill));
            }
        }

        return new ArrayList<>(amounts);
    }

    private String darkenColor(String hexColor) {
        if (hexColor.startsWith("#")) {
            hexColor = hexColor.substring(1);
        }

        try {
            int r = Integer.parseInt(hexColor.substring(0, 2), 16);
            int g = Integer.parseInt(hexColor.substring(2, 4), 16);
            int b = Integer.parseInt(hexColor.substring(4, 6), 16);

            r = Math.max(0, (int) (r * 0.85));
            g = Math.max(0, (int) (g * 0.85));
            b = Math.max(0, (int) (b * 0.85));

            return String.format("#%02X%02X%02X", r, g, b);
        } catch (Exception e) {
            return hexColor.startsWith("#") ? hexColor : "#" + hexColor;
        }
    }
}
