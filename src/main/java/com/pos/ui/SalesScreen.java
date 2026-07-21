package com.pos.ui;

import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.event.EventHandler;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.scene.text.FontWeight;
import javafx.scene.Cursor;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.KeyCode;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import com.pos.hardware.HardwareManager;
import com.pos.model.Product;
import com.pos.model.SaleItem;
import com.pos.model.Customer;
import com.pos.service.SalesService;
import com.pos.service.SettingsService;
import com.pos.service.UserAuthService;
import com.pos.service.RoleBasedAccessService;
import com.pos.service.CustomerDisplayService;
import com.pos.service.ShiftService;
import com.pos.service.CustomerService;
import com.pos.service.ProductManagementService;
import com.pos.service.EmployeeShiftService;
import com.pos.model.EmployeeShift;
import com.pos.ui.dialogs.ClockOutDialog;
import com.pos.sync.WebSocketClient;
import com.pos.sync.WebSocketMessageHandler;
import com.pos.api.dto.ShiftResponse;
import com.pos.ui.dialogs.CashPaymentDialog;

import com.pos.ui.dialogs.CustomerLookupDialog;

import com.pos.ui.dialogs.ProductFormDialog;
import com.pos.ui.dialogs.ProductSearchDialog;

import com.pos.ui.dialogs.AgeVerificationDialog;

import com.pos.util.ReceiptPrintHelper;
import com.pos.ui.components.ToastNotification;
import com.pos.service.AgeVerificationService;
import com.pos.service.CartCancellationService;
import com.pos.ui.components.NumericKeypad;

import com.pos.util.DialogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.util.Map;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;
import java.text.NumberFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.stream.Collectors;
import java.sql.SQLException;

/**
 * RetailPro Touch Terminal - Revolutionary Touch-First Design
 * 
 * ULTRA TOUCH-FRIENDLY DESIGN PRINCIPLES:
 * - Minimum 60px touch targets (exceeds 48px accessibility standard)
 * - Thumb-zone optimization for one-handed operation
 * - Visual feedback on every touch interaction
 * - High contrast for all lighting conditions
 * - Zero hover-dependent UI (pure touch experience)
 * 
 * UNIQUE LAYOUT ARCHITECTURE:
 * - LEFT (25%): Glass-morphism cart panel with oversized keypad
 * - CENTER (55%): Mega-tile product discovery with swipe gestures
 * - RIGHT (20%): Floating action orbs with radial quick-access
 * 
 * DESIGN IDENTITY:
 * - Organic rounded shapes (24px radius)
 * - Depth through layered shadows
 * - Nature-inspired gradient palette
 * - Animated state transitions
 * - Zero sharp corners or hard edges
 */
public class SalesScreen extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(SalesScreen.class);

    // Services
    private final HardwareManager hardwareManager;
    private final SalesService salesService;
    private final SettingsService settingsService;
    private final CustomerService customerService;
    private final ProductManagementService productManagementService;
    private final RoleBasedAccessService rbacService;
    private final AgeVerificationService ageVerificationService;

    // Data
    private ObservableList<SaleItem> cartItems;
    private final Map<String, SaleItem> cartItemsByBarcode = new HashMap<>();
    private final Set<SaleItem> cartItemsWithQuantityListeners = Collections
            .newSetFromMap(new IdentityHashMap<>());
    private PauseTransition totalUpdateDebounce;
    private PauseTransition cartRefreshDebounce;
    private static final Duration TOTAL_UPDATE_DEBOUNCE = Duration.millis(120);
    private static final Duration CART_REFRESH_DEBOUNCE = Duration.millis(150);
    private String selectedPaymentMethod = "CASH";
    private BigDecimal currentTotal = BigDecimal.ZERO;
    private BigDecimal subtotal = BigDecimal.ZERO;
    private BigDecimal tax = BigDecimal.ZERO;
    private BigDecimal saleDiscount = BigDecimal.ZERO;
    private String saleDiscountReason = "";
    // Customer tracking
    private Customer currentCustomer = null;

    // Quantity multiplier
    private int pendingQuantityMultiplier = 1;

    // Order notes
    private String orderNote = "";

    // Receipt number counter
    private static final AtomicInteger receiptCounter = new AtomicInteger(1000);
    private int currentReceiptNumber;

    // Cash only mode
    private boolean cashOnlyMode = false;

    private java.util.function.BiConsumer<BigDecimal, java.util.function.Consumer<DiscountScreen.DiscountResult>> onNavigateToDiscount;
    private Runnable onNavigateToPayment;
    private Runnable onNavigateToCashDrop;
    private Runnable onNavigateToCloseShift;
    private Runnable onNavigateToEndDay;
    private Runnable onNavigateToTimesheet;
    private Runnable onNavigateToRecallSales;
    private java.util.function.BiConsumer<SaleItem, EditItemScreen.EditMode> onNavigateToEditItem;
    private Runnable onNavigateToVendorPayout;
    private Runnable onNavigateToReturn;


    // Scanner Input Handling
    private StringBuilder scannerInputBuffer = new StringBuilder();
    private long lastScannerInputTime = 0;
    private EventHandler<KeyEvent> scannerHandler;
    private static final long SCANNER_INPUT_THRESHOLD_MS = 50; // Threshold to distinguish rapid scanner input from
                                                               // manual typing

    // UI Components - Left Panel (Receipt)
    private Label receiptNumberLabel;

    public void setOnNavigateToVendorPayout(Runnable callback) {
        this.onNavigateToVendorPayout = callback;
    }

    public void setOnNavigateToReturn(Runnable callback) {
        this.onNavigateToReturn = callback;
    }

    private Label receiptDateLabel;
    private Label cashierLabel;
    private Label storeNameLabel;
    private TableView<SaleItem> cartTable;
    private Label cartSummaryItemCountLabel;
    private Label cartHeaderItemCountLabel;
    private Label inputDisplayLabel;
    private StringBuilder inputBuffer = new StringBuilder();
    private Label subtotalLabel;
    private Label taxLabel;
    private Label discountLabel;
    private VBox discountBox;
    private Button removeDiscountBtn;
    private Label totalAmountLabel;
    private Label cardTotalAmountLabel;
    private Label customerInfoLabel;
    private Label multiplierLabel;
    private Button totalBtn;

    // UI Components - Center Panel
    private StackPane centerContainer;
    private GridPane departmentGrid;
    // private ScrollPane deptScroll; // Removed for auto-scaling layout
    private BorderPane productView;
    private FlowPane productGrid;
    private ScrollPane productScrollPane;
    private Label currentDeptLabel;
    private HBox paginationControls;

    // Pagination for Products
    private static final int PAGE_SIZE = 50;
    private int currentPage = 0;
    private int totalProducts = 0;
    private int totalPages = 0;
    private boolean isLoading = false;
    private String currentDepartmentId = null;
    private ProgressIndicator loadingIndicator;
    private Label pageInfoLabel;
    private Button prevPageBtn;
    private Button nextPageBtn;

    // RetailPro Signature Color Palette - Nature-inspired gradient scheme
    // Uses warm earth tones mixed with fresh greens - completely unique
    private static final String[] CATEGORY_COLORS = {
            "#059669", // Forest Green (primary brand)
            "#0891B2", // Ocean Teal
            "#7C3AED", // Royal Purple
            "#DB2777", // Magenta Rose
            "#EA580C", // Burnt Orange
            "#0D9488", // Seafoam
            "#4F46E5", // Electric Indigo
            "#B45309", // Caramel
            "#0E7490", // Deep Cyan
            "#9333EA", // Vivid Purple
            "#16A34A", // Kelly Green
            "#C026D3", // Fuchsia
    };
    private int colorIndex = 0;

    // Unique product card styling - Soft pastel accents
    private static final String[] PRODUCT_ACCENT_COLORS = {
            "#059669", "#0891B2", "#7C3AED", "#DB2777",
            "#EA580C", "#0D9488", "#4F46E5", "#16A34A"
    };

    // TOUCH-FIRST shape constants - optimized for small screens (<13 inch)
    private static final int TOUCH_TARGET_MIN = 48; // Minimum touch target size (compact)

    public SalesScreen(HardwareManager hardwareManager) {
        logger.debug("SalesScreen constructor started");
        this.hardwareManager = hardwareManager;
        this.salesService = SalesService.getInstance();
        this.settingsService = SettingsService.getInstance();
        this.customerService = CustomerService.getInstance();
        this.productManagementService = ProductManagementService.getInstance();
        this.rbacService = RoleBasedAccessService.getInstance();
        this.ageVerificationService = AgeVerificationService.getInstance();
        this.cartItems = FXCollections.observableArrayList();
        this.currentReceiptNumber = receiptCounter.incrementAndGet();

        // Initialize Customer Facing Display
        CustomerDisplayService.getInstance().initialize(this.cartItems);

        // Set up scanner callback
        hardwareManager.setScanCallback(this::handleBarcodeScan);

        initializeUI();
        loadDepartments();
        showDepartments();

        // Register WebSocket event listeners for real-time updates
        setupWebSocketListeners();

        // Setup scanner input listener
        setupScannerInput();
    }

    /**
     * Set up WebSocket listeners for real-time updates from backend.
     * This enables live refresh of products, stock levels, and departments
     * when changes are made via the mobile app or other POS terminals.
     */
    private void setupWebSocketListeners() {
        try {
            WebSocketClient wsClient = WebSocketClient.getInstance();
            WebSocketMessageHandler messageHandler = wsClient.getMessageHandler();

            // Listen for product changes (create, update, delete)
            messageHandler.addProductEventListener(this::handleProductEvent);

            // Listen for stock updates
            messageHandler.addStockEventListener(this::handleStockEvent);

            // Listen for price changes
            messageHandler.addPriceEventListener(this::handlePriceEvent);

            // Listen for department changes
            messageHandler.addDepartmentEventListener(this::handleDepartmentEvent);

            logger.info("\u2705 WebSocket listeners registered for real-time updates");
        } catch (Exception e) {
            logger.error("Failed to setup WebSocket listeners", e);
        }
    }

    /**
     * Handle product events from WebSocket (create, update, delete).
     * Refreshes the product grid if currently viewing the affected department.
     */
    private void handleProductEvent(WebSocketMessageHandler.ProductEvent event) {
        logger.info("\uD83D\uDCE6 Product event received: {} - {}", event.getAction(), event.getProductName());

        // Refresh product display if products are currently visible
        refreshProductsIfVisible();

        // Show toast notification
        String message = switch (event.getAction()) {
            case "created" -> "New product added: " + event.getProductName();
            case "updated" -> "Product updated: " + event.getProductName();
            case "deleted" -> "Product removed";
            default -> "Product changed";
        };
        ToastNotification.showInfo(message, getSceneWindow());
    }

    /**
     * Handle stock update events from WebSocket.
     * Updates the product card display to reflect new stock levels.
     */
    private void handleStockEvent(WebSocketMessageHandler.StockEvent event) {
        logger.info("\uD83D\uDCCA Stock event received: {} ({} -> {})",
                event.getProductId(), event.getPreviousQuantity(), event.getNewQuantity());

        // Refresh product display to show updated stock
        refreshProductsIfVisible();

        // Suppress zero/negative stock warning to avoid interrupting sales flow.
        // The product badge already reflects the current stock state.
    }

    /**
     * Handle price change events from WebSocket.
     * Updates product cards and alerts cashier about price changes.
     */
    private void handlePriceEvent(WebSocketMessageHandler.PriceEvent event) {
        logger.info("\uD83D\uDCB0 Price event received: {} ({} -> {})",
                event.getProductId(), event.getOldPrice(), event.getNewPrice());

        // Refresh product display to show new prices
        refreshProductsIfVisible();

        // Alert cashier about price change
        ToastNotification.showInfo("Price updated!", getSceneWindow());
    }

    /**
     * Refresh product display if products are currently visible.
     * Checks if the product scroll pane is visible before refreshing.
     */
    private void refreshProductsIfVisible() {
        // Refresh if we have a department selected OR if the product scroll pane is
        // visible
        if (currentDepartmentId != null) {
            loadProducts(currentDepartmentId);
        } else if (productScrollPane != null && productScrollPane.isVisible() && productScrollPane.isManaged()) {
            // Products might be visible without a specific department (showing all)
            loadProducts(null);
        }
    }

    /**
     * Handle department events from WebSocket (create, update).
     * Refreshes the department grid to show new/updated departments.
     */
    private void handleDepartmentEvent(WebSocketMessageHandler.DepartmentEvent event) {
        logger.info("\uD83C\uDFF7\uFE0F Department event received: {} - {}", event.getAction(), event.getDepartmentName());

        productManagementService.invalidateDepartmentCache(event.getDepartmentId());
        settingsService.invalidateMultiPackCache(event.getDepartmentId());

        // Refresh department list
        loadDepartments();

        // Show notification
        String message = switch (event.getAction()) {
            case "created" -> "New department: " + event.getDepartmentName();
            case "updated" -> "Department updated: " + event.getDepartmentName();
            default -> "Department changed";
        };
        ToastNotification.showInfo(message, getSceneWindow());
    }

    /**
     * Helper method to safely get the Window from the scene.
     * Returns null if scene is not yet attached.
     */
    private javafx.stage.Window getSceneWindow() {
        return getScene() != null ? getScene().getWindow() : null;
    }

    private void setupScannerInput() {
        // Create the handler that will process key events at the Scene level
        scannerHandler = event -> {
            long currentTime = System.currentTimeMillis();

            // Check if this is likely scanner input (rapid keystrokes)
            if (currentTime - lastScannerInputTime > SCANNER_INPUT_THRESHOLD_MS && scannerInputBuffer.length() > 0) {
                // If delay is too long, it's likely manual input or a new scan - reset buffer
                // But don't reset if buffer is short (might be manual typing of barcode)
                if (scannerInputBuffer.length() < 3) {
                    scannerInputBuffer.setLength(0);
                }
            }
            lastScannerInputTime = currentTime;

            if (event.getCode() == KeyCode.ENTER) {
                if (scannerInputBuffer.length() > 0) {
                    String barcode = scannerInputBuffer.toString();
                    logger.info("Scanner input detected (Enter): {}", barcode);

                    // Process potential barcode
                    hardwareManager.processKeyboardBarcode(barcode);

                    // Clear buffer
                    scannerInputBuffer.setLength(0);

                    // Consume the event to prevent it from triggering other actions (like button
                    // clicks)
                    event.consume();
                }
            } else {
                // Determine character from key code
                String text = event.getText();
                if (text != null && !text.isEmpty()) {
                    // Check if it's a printable character
                    char c = text.charAt(0);
                    if (!Character.isISOControl(c)) {
                        scannerInputBuffer.append(text);
                    }
                }
            }
        };

        // Attach to scene when available, remove when unavailable
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null && scannerHandler != null) {
                oldScene.removeEventFilter(KeyEvent.KEY_PRESSED, scannerHandler);
            }
            if (newScene != null && scannerHandler != null) {
                newScene.addEventFilter(KeyEvent.KEY_PRESSED, scannerHandler);
            }
        });

        // Initial check
        if (getScene() != null) {
            getScene().addEventFilter(KeyEvent.KEY_PRESSED, scannerHandler);
        }
    }

    private void initializeUI() {
        getStyleClass().add("touch-terminal-layout");
        setFocusTraversable(true);
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);

        // Ensure totals are updated whenever cart changes
        cartItems.addListener((javafx.collections.ListChangeListener<SaleItem>) c -> {
            while (c.next()) {
                if (c.wasRemoved()) {
                    for (SaleItem removed : c.getRemoved()) {
                        String barcode = removed.getProduct().getBarcode();
                        if (barcode != null) {
                            cartItemsByBarcode.remove(barcode);
                        }
                    }
                }
            }
            scheduleUpdateTotal();
        });

        // 1. Left Panel - Glass-morphism cart with oversized elements
        VBox leftPanel = createReceiptPanel();
        leftPanel.getStyleClass().add("touch-cart-panel");
        leftPanel.setMaxHeight(Double.MAX_VALUE);

        // 2. Center Panel - Mega-tile product discovery
        VBox centerPanel = createCategoryProductPanel();
        centerPanel.getStyleClass().add("touch-discovery-panel");

        // 3. Right Panel - Floating action orbs
        BorderPane rightPanel = createFunctionPanel();
        rightPanel.getStyleClass().add("touch-action-dock");

        // Touch-optimized layout with generous spacing
        HBox mainLayout = new HBox(0);
        mainLayout.setMaxWidth(Double.MAX_VALUE);
        mainLayout.setMaxHeight(Double.MAX_VALUE);
        mainLayout.getStyleClass().add("touch-main-layout");
        mainLayout.getChildren().addAll(leftPanel, centerPanel, rightPanel);

        HBox.setHgrow(leftPanel, Priority.NEVER);
        HBox.setHgrow(centerPanel, Priority.ALWAYS);
        HBox.setHgrow(rightPanel, Priority.NEVER);

        // Responsive widths optimized for small screens (<13 inch)
        mainLayout.widthProperty().addListener((obs, oldVal, newVal) -> {
            double width = newVal.doubleValue();

            // For small screens: use more compact layout
            // Left panel: 28% for receipt, min 280px for usability
            double leftWidth = Math.max(280, Math.min(width * 0.28, 380));

            // Right panel: compact action rail
            double rightWidth = Math.max(120, Math.min(width * 0.12, 160));

            // Center panel takes remaining space automatically via HBox priority

            leftPanel.setPrefWidth(leftWidth);
            leftPanel.setMinWidth(leftWidth);
            leftPanel.setMaxWidth(leftWidth);

            rightPanel.setPrefWidth(rightWidth);
            rightPanel.setMinWidth(rightWidth);
            rightPanel.setMaxWidth(rightWidth);
        });

        // Bind height to parent for responsiveness
        mainLayout.heightProperty().addListener((obs, oldVal, newVal) -> {
            double height = newVal.doubleValue();
            leftPanel.setMaxHeight(height);
            rightPanel.setMaxHeight(height);
        });

        setCenter(mainLayout);
    }

    // ==================== LEFT PANEL: RECEIPT ====================

    private VBox createReceiptPanel() {
        VBox panel = new VBox(0);
        panel.getStyleClass().add("touch-cart-container");
        panel.setFillWidth(true);
        panel.setMaxHeight(Double.MAX_VALUE);

        // 1. Compact header focused on sale identity, not decorative status
        VBox receiptHeader = createReceiptHeader();

        // 2. Customer info (touch-friendly banner)
        customerInfoLabel = new Label();
        customerInfoLabel.setMaxWidth(Double.MAX_VALUE);
        customerInfoLabel.setVisible(false);
        customerInfoLabel.setManaged(false);
        customerInfoLabel.getStyleClass().add("touch-customer-banner");
        customerInfoLabel.setMinHeight(TOUCH_TARGET_MIN);

        // 3. Cart - scrollable, takes available space
        cartTable = new TableView<>();
        cartTable.setItems(cartItems);
        Label emptyCartLabel = new Label("Cart is empty\nTap products to add");
        emptyCartLabel.getStyleClass().add("touch-empty-cart");
        emptyCartLabel.setTextAlignment(TextAlignment.CENTER);
        cartTable.setPlaceholder(emptyCartLabel);
        cartTable.getStyleClass().add("touch-cart-table");
        cartTable.setMinHeight(80);
        cartTable.setFixedCellSize(50.0); // Compact rows for small screens
        VBox.setVgrow(cartTable, Priority.ALWAYS);
        setupCartColumns();

        // 4. Quantity multiplier - large pill
        multiplierLabel = new Label();
        multiplierLabel.setMaxWidth(Double.MAX_VALUE);
        multiplierLabel.setAlignment(Pos.CENTER);
        multiplierLabel.setVisible(false);
        multiplierLabel.setManaged(false);
        multiplierLabel.getStyleClass().add("touch-multiplier-pill");
        multiplierLabel.setMinHeight(36);

        // 5. Keypad section at bottom - styled input display
        VBox keypadSection = new VBox(4);
        keypadSection.setFillWidth(true);
        keypadSection.setPadding(new Insets(6, 10, 10, 10));
        keypadSection.setAlignment(Pos.BOTTOM_CENTER);
        keypadSection.getStyleClass().add("touch-keypad-section");

        // Compact input display with tighter styling
        inputDisplayLabel = new Label("0.00");
        inputDisplayLabel.setMaxWidth(Double.MAX_VALUE);
        inputDisplayLabel.setAlignment(Pos.CENTER);
        inputDisplayLabel.getStyleClass().add("touch-input-display");
        inputDisplayLabel.setStyle(
                "-fx-background-color: linear-gradient(to bottom, rgba(5, 150, 105, 0.15), rgba(5, 150, 105, 0.05)); " +
                        "-fx-background-radius: 8; " +
                        "-fx-border-color: rgba(34, 197, 94, 0.3); " +
                        "-fx-border-width: 1; " +
                        "-fx-border-radius: 8; " +
                        "-fx-font-size: 20px; " +
                        "-fx-font-weight: 800; " +
                        "-fx-text-fill: #22c55e; " +
                        "-fx-padding: 4 8;");
        inputDisplayLabel.setMinHeight(32);
        inputDisplayLabel.setPrefHeight(36);

        // Compact Keypad for small screens - Height adjusted to prevent overlap
        NumericKeypad keypad = new NumericKeypad(true, true, false);
        keypad.setShowQtyButton(true);
        keypad.setListener(this::handleKeypadInput);
        keypad.setEnterListener(this::handleEnterPressed);
        keypad.getStyleClass().add("touch-mega-keypad");
        keypad.setMinHeight(220);
        keypad.setPrefHeight(240);
        keypad.setMaxHeight(260);
        VBox.setVgrow(keypad, Priority.NEVER);

        keypadSection.getChildren().addAll(multiplierLabel, inputDisplayLabel, keypad);
        VBox.setVgrow(keypadSection, Priority.NEVER);

        panel.getChildren().addAll(
                receiptHeader,
                cartTable,
                keypadSection);

        return panel;
    }

    private VBox createReceiptHeader() {
        // Compact two-line sale header to keep more room for the cart itself
        VBox header = new VBox(1);
        header.getStyleClass().add("touch-header");
        header.setPadding(new Insets(6, 10, 4, 10));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setMinHeight(Region.USE_PREF_SIZE);

        HBox primaryRow = new HBox(8);
        primaryRow.setAlignment(Pos.CENTER_LEFT);
        primaryRow.getStyleClass().add("touch-receipt-header-row");

        storeNameLabel = new Label(settingsService.getStoreName());
        storeNameLabel.getStyleClass().add("touch-store-name");
        storeNameLabel.getStyleClass().add("touch-store-name-compact");

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);

        receiptNumberLabel = new Label(String.format("%07d", currentReceiptNumber));
        receiptNumberLabel.getStyleClass().add("touch-tx-number");
        receiptNumberLabel.getStyleClass().add("touch-tx-number-compact");

        primaryRow.getChildren().addAll(storeNameLabel, titleSpacer, receiptNumberLabel);

        HBox infoStrip = new HBox(6);
        infoStrip.setAlignment(Pos.CENTER_LEFT);
        infoStrip.getStyleClass().add("touch-receipt-meta-row");

        receiptDateLabel = new Label();
        receiptDateLabel.getStyleClass().add("touch-meta-text");
        updateReceiptDateTime();

        Region dotSpacer = new Region();
        dotSpacer.setMinSize(3, 3);
        dotSpacer.setMaxSize(3, 3);
        dotSpacer.setStyle("-fx-background-color: rgba(255,255,255,0.3); -fx-background-radius: 2;");

        String cashierName = UserAuthService.getInstance().getCurrentUserName();
        cashierLabel = new Label(cashierName != null ? cashierName : "Staff");
        cashierLabel.getStyleClass().add("touch-meta-text");

        infoStrip.getChildren().addAll(receiptDateLabel, dotSpacer, cashierLabel);

        header.getChildren().addAll(primaryRow, infoStrip);
        return header;
    }

    private void updateReceiptDateTime() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("h:mm a");
        receiptDateLabel.setText(now.format(formatter));
    }

    private VBox createTotalsArea() {
        VBox totalArea = new VBox();
        totalArea.getStyleClass().add("touch-totals-wave");

        VBox summaryCard = new VBox(12); // Slightly more gap
        summaryCard.getStyleClass().add("touch-summary-glass");
        summaryCard.setPadding(new Insets(10, 15, 12, 15));
        summaryCard.setAlignment(Pos.CENTER);

        // Item count - floating pill badge (Top center)
        cartSummaryItemCountLabel = new Label("0 items in cart");
        cartSummaryItemCountLabel.getStyleClass().add("touch-item-pill");
        cartSummaryItemCountLabel.setAlignment(Pos.CENTER);
        cartSummaryItemCountLabel.setVisible(false);
        cartSummaryItemCountLabel.setManaged(false);

        // --- Details Row (Subtotal & Tax) ---
        HBox detailsRow = new HBox(30);
        detailsRow.setAlignment(Pos.CENTER);

        // Subtotal
        VBox subtotalBox = new VBox(2);
        subtotalBox.setAlignment(Pos.CENTER);
        Label subtotalText = new Label("SUBTOTAL");
        subtotalText.getStyleClass().add("touch-label-mini");
        subtotalLabel = new Label("$0.00");
        subtotalLabel.getStyleClass().add("touch-value-medium");
        subtotalBox.getChildren().addAll(subtotalText, subtotalLabel);

        // Subtle Glow Divider
        Region vDivider = new Region();
        vDivider.setMinWidth(1.5);
        vDivider.setMaxWidth(1.5);
        vDivider.setMinHeight(28);
        vDivider.getStyleClass().add("touch-glow-divider");

        // Tax
        VBox taxBox = new VBox(2);
        taxBox.setAlignment(Pos.CENTER);
        Label taxText = new Label("TAX");
        taxText.getStyleClass().add("touch-label-mini");
        taxLabel = new Label("$0.00");
        taxLabel.getStyleClass().add("touch-value-medium");
        taxBox.getChildren().addAll(taxText, taxLabel);

        detailsRow.getChildren().addAll(subtotalBox, vDivider, taxBox);

        // --- Discount display ---
        discountBox = new VBox(2);
        discountBox.setAlignment(Pos.CENTER);
        discountBox.setVisible(false);
        discountBox.setManaged(false);
        discountBox.setPadding(new Insets(8, 15, 8, 15));
        discountBox.setStyle(
                "-fx-background-color: rgba(255, 255, 255, 0.05); " +
                "-fx-background-radius: 12; " +
                "-fx-border-color: rgba(255, 255, 255, 0.2); " + 
                "-fx-border-width: 1; " +
                "-fx-border-style: solid;");

        StackPane discountContent = new StackPane();
        
        VBox discountLabels = new VBox(0);
        discountLabels.setAlignment(Pos.CENTER);
        
        Label discountAppliedText = new Label("DISCOUNT APPLIED");
        discountAppliedText.setStyle("-fx-font-size: 9px; -fx-text-fill: rgba(251, 191, 36, 0.9); -fx-font-weight: 800; -fx-letter-spacing: 1.2px;");
        
        discountLabel = new Label("-$0.00");
        discountLabel.setStyle("-fx-font-size: 22px; -fx-text-fill: #ffffff; -fx-font-weight: 900; -fx-font-family: 'JetBrains Mono', monospace; -fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.3), 4, 0, 0, 2);");
        
        discountLabels.getChildren().addAll(discountAppliedText, discountLabel);
        
        removeDiscountBtn = new Button("\u2716");
        removeDiscountBtn.setCursor(Cursor.HAND);
        removeDiscountBtn.setStyle("-fx-background-color: rgba(255, 255, 255, 0.1); -fx-text-fill: white; -fx-font-size: 12px; -fx-background-radius: 50; -fx-min-width: 28; -fx-min-height: 28;");
        removeDiscountBtn.setOnAction(e -> removeSaleDiscount());
        removeDiscountBtn.setOnMouseEntered(e -> removeDiscountBtn.setStyle("-fx-background-color: #EF4444; -fx-text-fill: white; -fx-font-size: 12px; -fx-background-radius: 50; -fx-min-width: 28; -fx-min-height: 28;"));
        removeDiscountBtn.setOnMouseExited(e -> removeDiscountBtn.setStyle("-fx-background-color: rgba(255, 255, 255, 0.1); -fx-text-fill: white; -fx-font-size: 12px; -fx-background-radius: 50; -fx-min-width: 28; -fx-min-height: 28;"));
        
        StackPane.setAlignment(removeDiscountBtn, Pos.CENTER_RIGHT);
        
        discountContent.getChildren().addAll(discountLabels, removeDiscountBtn);
        discountBox.getChildren().add(discountContent);

        // --- MEGA TOTALS ---
        HBox totalBoxContainer = new HBox(0);
        totalBoxContainer.setAlignment(Pos.CENTER);
        totalBoxContainer.setPadding(new Insets(5, 0, 0, 0));

        // Cash Total
        VBox cashTotalBox = new VBox(2);
        cashTotalBox.setAlignment(Pos.CENTER);
        cashTotalBox.setPrefWidth(220);
        cashTotalBox.getStyleClass().add("touch-mega-total");

        Label cashTotalTitle = new Label("CASH TOTAL");
        cashTotalTitle.getStyleClass().add("touch-total-label");

        HBox cashAmountRow = new HBox(4);
        cashAmountRow.setAlignment(Pos.CENTER);
        Label cashDollar = new Label("$");
        cashDollar.getStyleClass().add("touch-currency-symbol");
        totalAmountLabel = new Label("0.00");
        totalAmountLabel.getStyleClass().add("touch-mega-amount");
        cashAmountRow.getChildren().addAll(cashDollar, totalAmountLabel);
        
        cashTotalBox.getChildren().addAll(cashTotalTitle, cashAmountRow);

        // Center Divider (Thin glass line)
        Region centerDivider = new Region();
        centerDivider.setMinWidth(1);
        centerDivider.setMaxWidth(1);
        centerDivider.setMinHeight(55);
        centerDivider.setStyle("-fx-background-color: rgba(255, 255, 255, 0.15);");

        // Card Total
        VBox cardTotalBox = new VBox(2);
        cardTotalBox.setAlignment(Pos.CENTER);
        cardTotalBox.setPrefWidth(220);
        cardTotalBox.getStyleClass().add("touch-mega-total");

        Label cardTotalTitle = new Label("TOTAL DUE (CARD)");
        cardTotalTitle.getStyleClass().add("touch-total-label");

        HBox cardAmountRow = new HBox(4);
        cardAmountRow.setAlignment(Pos.CENTER);
        Label cardDollar = new Label("$");
        cardDollar.getStyleClass().add("touch-currency-symbol");
        cardTotalAmountLabel = new Label("0.00");
        cardTotalAmountLabel.getStyleClass().add("touch-mega-amount");
        cardAmountRow.getChildren().addAll(cardDollar, cardTotalAmountLabel);
        
        cardTotalBox.getChildren().addAll(cardTotalTitle, cardAmountRow);

        totalBoxContainer.getChildren().addAll(cashTotalBox, centerDivider, cardTotalBox);

        summaryCard.getChildren().addAll(cartSummaryItemCountLabel, detailsRow, discountBox, totalBoxContainer);
        totalArea.getChildren().add(summaryCard);

        return totalArea;
    }

    private void refreshCartItemCountLabels() {
        int count = cartItems.stream().mapToInt(SaleItem::getQuantity).sum();
        if (cartSummaryItemCountLabel != null) {
            cartSummaryItemCountLabel.setText(count + " item" + (count != 1 ? "s" : "") + " in cart");
            cartSummaryItemCountLabel.setVisible(count > 0);
            cartSummaryItemCountLabel.setManaged(count > 0);
        }
        if (cartHeaderItemCountLabel != null) {
            cartHeaderItemCountLabel.setText(String.valueOf(count));
            cartHeaderItemCountLabel.setVisible(count > 0);
            cartHeaderItemCountLabel.setManaged(count > 0);
        }
    }

    private void renderCartLineName(SaleItem item, String name, VBox content) {
        content.getChildren().clear();
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("pos-modern-cart-item-name");
        content.getChildren().add(nameLabel);
        String reason = item.getDiscountReason();
        if (reason != null && !reason.isEmpty()) {
            Label noteLabel = new Label("  " + reason);
            noteLabel.getStyleClass().add("pos-modern-cart-item-note");
            content.getChildren().add(noteLabel);
        }
    }

    private void renderCartLinePrice(SaleItem item, TextFlow textFlow) {
        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
        BigDecimal cashPrice = item.getPrice("CASH");
        BigDecimal cardPrice = item.getPrice("CARD");
        BigDecimal cashBaseTotal = item.getBaseTotalAtCashPrice();
        BigDecimal cardBaseTotal = item.getBaseTotalAtCardPrice();
        BigDecimal totalDiscount = item.getTotalDiscount();
        boolean hasDiscount = totalDiscount != null && totalDiscount.compareTo(BigDecimal.ZERO) > 0;
        BigDecimal cashTotal = cashBaseTotal;
        BigDecimal cardTotal = cardBaseTotal;

        if (hasDiscount) {
            BigDecimal discountPercent = item.getDiscountPercent();
            BigDecimal discountAmount = item.getDiscountAmount();
            if (discountPercent != null && discountPercent.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal cashDiscount = cashBaseTotal.multiply(discountPercent)
                        .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
                BigDecimal cardDiscount = cardBaseTotal.multiply(discountPercent)
                        .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
                cashTotal = cashBaseTotal.subtract(cashDiscount);
                cardTotal = cardBaseTotal.subtract(cardDiscount);
            } else if (discountAmount != null && discountAmount.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal itemBaseTotal = item.getBaseTotal();
                if (itemBaseTotal.compareTo(BigDecimal.ZERO) > 0) {
                    if (cashBaseTotal.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal cashRatio = cashBaseTotal.divide(itemBaseTotal, 4, RoundingMode.HALF_UP);
                        BigDecimal cashDiscount = discountAmount.multiply(cashRatio).min(cashBaseTotal);
                        cashTotal = cashBaseTotal.subtract(cashDiscount);
                    }
                    if (cardBaseTotal.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal cardRatio = cardBaseTotal.divide(itemBaseTotal, 4, RoundingMode.HALF_UP);
                        BigDecimal cardDiscount = discountAmount.multiply(cardRatio).min(cardBaseTotal);
                        cardTotal = cardBaseTotal.subtract(cardDiscount);
                    }
                }
            }
        }

        textFlow.getChildren().clear();
        boolean isCashActive = "CASH".equals(selectedPaymentMethod) ||
                (selectedPaymentMethod == null) ||
                (!"CARD".equals(selectedPaymentMethod) && !"EBT".equals(selectedPaymentMethod));

        if (cardPrice.compareTo(cashPrice) != 0 && cardBaseTotal.compareTo(cashBaseTotal) != 0) {
            Text cashLabel = new Text("Cash: ");
            Text cashText = new Text(currencyFormat.format(cashTotal));
            Text separatorText = new Text(" | ");
            Text cardLabel = new Text("Card: ");
            Text listText = new Text(currencyFormat.format(cardTotal));
            if (isCashActive) {
                cashLabel.setFont(javafx.scene.text.Font.font("System", FontWeight.BOLD, 10));
                cashLabel.setStyle("-fx-fill: #2e7d32;");
                cashText.setFont(javafx.scene.text.Font.font("System", FontWeight.BOLD, 12));
                cashText.setStyle("-fx-fill: #2e7d32;");
                separatorText.setStyle("-fx-fill: #999;");
                cardLabel.setFont(javafx.scene.text.Font.font("System", FontWeight.NORMAL, 10));
                cardLabel.setStyle("-fx-fill: #999;");
                listText.setFont(javafx.scene.text.Font.font("System", FontWeight.NORMAL, 11));
                listText.setStyle("-fx-fill: #999;");
            } else {
                cashLabel.setFont(javafx.scene.text.Font.font("System", FontWeight.NORMAL, 10));
                cashLabel.setStyle("-fx-fill: #999;");
                cashText.setFont(javafx.scene.text.Font.font("System", FontWeight.NORMAL, 11));
                cashText.setStyle("-fx-fill: #999;");
                separatorText.setStyle("-fx-fill: #999;");
                cardLabel.setFont(javafx.scene.text.Font.font("System", FontWeight.BOLD, 10));
                cardLabel.setStyle("-fx-fill: #1565c0;");
                listText.setFont(javafx.scene.text.Font.font("System", FontWeight.BOLD, 12));
                listText.setStyle("-fx-fill: #1565c0;");
            }
            textFlow.getChildren().addAll(cashLabel, cashText, separatorText, cardLabel, listText);
            if (hasDiscount) {
                Text newline = new Text("\n");
                Text discountLabel = new Text("Disc: ");
                discountLabel.setFont(javafx.scene.text.Font.font("System", FontWeight.NORMAL, 9));
                discountLabel.setStyle("-fx-fill: #16A34A;");
                Text discountText = new Text("-" + currencyFormat.format(totalDiscount));
                discountText.setFont(javafx.scene.text.Font.font("System", FontWeight.BOLD, 10));
                discountText.setStyle("-fx-fill: #16A34A;");
                textFlow.getChildren().addAll(newline, discountLabel, discountText);
            }
        } else {
            Text priceText = new Text(currencyFormat.format(cashTotal));
            priceText.setFont(javafx.scene.text.Font.font("System", FontWeight.BOLD, 12));
            priceText.setStyle("-fx-fill: #333;");
            textFlow.getChildren().add(priceText);
            if (hasDiscount) {
                Text newline = new Text("\n");
                Text discountLabel = new Text("Disc: ");
                discountLabel.setFont(javafx.scene.text.Font.font("System", FontWeight.NORMAL, 9));
                discountLabel.setStyle("-fx-fill: #16A34A;");
                Text discountText = new Text("-" + currencyFormat.format(totalDiscount));
                discountText.setFont(javafx.scene.text.Font.font("System", FontWeight.BOLD, 10));
                discountText.setStyle("-fx-fill: #16A34A;");
                textFlow.getChildren().addAll(newline, discountLabel, discountText);
            }
        }
    }

    private void setupCartColumns() {
        // Item name column - larger text for touch
        TableColumn<SaleItem, String> nameCol = new TableColumn<>("Item");
        nameCol.setCellValueFactory(data -> data.getValue().productNameProperty());
        nameCol.setCellFactory(col -> new TableCell<SaleItem, String>() {
            private final VBox content = new VBox(2);
            private SaleItem boundItem;
            private final ChangeListener<String> discountReasonListener = (obs, oldVal, newVal) -> {
                if (boundItem != null && !isEmpty()) {
                    renderCartLineName(boundItem, boundItem.getProductName(), content);
                }
            };

            private void detachNameListeners() {
                if (boundItem != null) {
                    boundItem.discountReasonProperty().removeListener(discountReasonListener);
                    boundItem = null;
                }
            }

            @Override
            protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                detachNameListeners();
                if (empty || name == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                } else {
                    boundItem = getTableRow().getItem();
                    if (boundItem != null) {
                        boundItem.discountReasonProperty().addListener(discountReasonListener);
                        renderCartLineName(boundItem, name, content);
                    }
                    setGraphic(content);
                    setText(null);
                    if (getTableRow().isSelected()) {
                        setStyle("-fx-background-color: #e3f2fd;");
                    } else {
                        setStyle("");
                    }
                }
            }
        });

        // Quantity column - shows quantity with remove button
        TableColumn<SaleItem, SaleItem> qtyCol = new TableColumn<>("Qty");
        qtyCol.setPrefWidth(70);
        qtyCol.setMinWidth(60);
        qtyCol.setCellValueFactory(data -> new javafx.beans.property.SimpleObjectProperty<>(data.getValue()));
        qtyCol.setCellFactory(col -> new TableCell<SaleItem, SaleItem>() {
            private final HBox container = new HBox(5);
            private final Label qtyLabel = new Label();
            private final Button removeBtn = new Button("\u00D7");

            {
                container.setAlignment(Pos.CENTER);
                qtyLabel.getStyleClass().add("pos-modern-cart-qty-label");
                removeBtn.getStyleClass().add("pos-modern-cart-remove-btn");
                removeBtn.setMinSize(24, 24);
                removeBtn.setMaxSize(24, 24);
                container.getChildren().addAll(qtyLabel, removeBtn);
            }

            @Override
            protected void updateItem(SaleItem item, boolean empty) {
                super.updateItem(item, empty);
                qtyLabel.textProperty().unbind();
                if (empty || item == null) {
                    setGraphic(null);
                    removeBtn.setOnAction(null);
                } else {
                    qtyLabel.textProperty().bind(item.quantityProperty().asString());
                    removeBtn.setOnAction(e -> {
                        // Log item removal before removing from cart
                        try {
                            CartCancellationService cancellationService = CartCancellationService.getInstance();
                            String receiptNumberStr = receiptNumberLabel != null ? receiptNumberLabel.getText()
                                    : String.format("%07d", currentReceiptNumber);
                            cancellationService.logItemCancellation(item, receiptNumberStr);
                        } catch (Exception ex) {
                            logger.error("Error logging item cancellation in SalesScreen", ex);
                        }

                        cartItems.remove(item);
                        scheduleUpdateTotal();
                        // Ensure focus returns to the screen after item removal for barcode scanning
                        Platform.runLater(() -> SalesScreen.this.requestFocus());
                    });
                    setGraphic(container);
                }
            }
        });

        // Price column - shows both cash and list prices with transparency
        TableColumn<SaleItem, SaleItem> priceCol = new TableColumn<>("Price");
        priceCol.setPrefWidth(100);
        priceCol.setMinWidth(80);
        priceCol.setCellValueFactory(data -> new javafx.beans.property.SimpleObjectProperty<>(data.getValue()));
        priceCol.setCellFactory(col -> new TableCell<SaleItem, SaleItem>() {
            private final TextFlow textFlow = new TextFlow();
            private SaleItem boundItem;
            private final ChangeListener<Number> quantityListener = (obs, oldVal, newVal) -> refreshPriceCell();
            private final ChangeListener<BigDecimal> discountAmountListener = (obs, oldVal, newVal) -> refreshPriceCell();
            private final ChangeListener<BigDecimal> discountPercentListener = (obs, oldVal, newVal) -> refreshPriceCell();

            {
                textFlow.setTextAlignment(javafx.scene.text.TextAlignment.RIGHT);
                setAlignment(Pos.CENTER_RIGHT);
                getStyleClass().add("pos-modern-cart-item-price");
            }

            private void detachPriceListeners() {
                if (boundItem != null) {
                    boundItem.quantityProperty().removeListener(quantityListener);
                    boundItem.discountAmountProperty().removeListener(discountAmountListener);
                    boundItem.discountPercentProperty().removeListener(discountPercentListener);
                    boundItem = null;
                }
            }

            private void refreshPriceCell() {
                if (boundItem == null || isEmpty()) {
                    return;
                }
                renderCartLinePrice(boundItem, textFlow);
                setGraphic(textFlow);
            }

            @Override
            protected void updateItem(SaleItem item, boolean empty) {
                super.updateItem(item, empty);
                detachPriceListeners();
                if (empty || item == null) {
                    textFlow.getChildren().clear();
                    setGraphic(null);
                } else {
                    boundItem = item;
                    item.quantityProperty().addListener(quantityListener);
                    item.discountAmountProperty().addListener(discountAmountListener);
                    item.discountPercentProperty().addListener(discountPercentListener);
                    refreshPriceCell();
                }
            }
        });

        cartTable.getColumns().clear();
        cartTable.getColumns().addAll(nameCol, qtyCol, priceCol);
        cartTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // Click on row to select
        cartTable.setRowFactory(tv -> {
            TableRow<SaleItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    if (onNavigateToEditItem != null) {
                        onNavigateToEditItem.accept(row.getItem(), EditItemScreen.EditMode.QUANTITY);
                    }
                }
            });
            return row;
        });
    }

    // ==================== CENTER PANEL: CATEGORIES/PRODUCTS ====================

    public void setOnNavigateToEditItem(java.util.function.BiConsumer<SaleItem, EditItemScreen.EditMode> callback) {
        this.onNavigateToEditItem = callback;
    }

    private VBox createCategoryProductPanel() {
        VBox container = new VBox(0);
        container.getStyleClass().add("touch-discovery-container");

        // Quick Actions Strip - Unique floating pills at top
        HBox quickStrip = createQuickActionsStrip();
        VBox.setVgrow(quickStrip, Priority.NEVER);

        // Center content (mega tile grid)
        centerContainer = createCenterContent();
        VBox.setVgrow(centerContainer, Priority.ALWAYS);

        // Totals Area - wave design at bottom
        VBox totalArea = createTotalsArea();
        VBox.setVgrow(totalArea, Priority.NEVER);

        container.getChildren().addAll(quickStrip, centerContainer, totalArea);
        return container;
    }

    /**
     * Creates a unique Quick Actions Strip with floating pill buttons
     * for common actions - completely unique design element
     */
    private HBox createQuickActionsStrip() {
        HBox strip = new HBox(12);
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.setPadding(new Insets(12, 24, 8, 24));
        strip.getStyleClass().add("touch-quick-strip");

        // Live clock with pulse dot
        HBox clockPill = new HBox(8);
        clockPill.setAlignment(Pos.CENTER);
        clockPill.getStyleClass().add("touch-clock-pill");
        clockPill.setPadding(new Insets(8, 16, 8, 16));

        Region clockDot = new Region();
        clockDot.setMinSize(8, 8);
        clockDot.setMaxSize(8, 8);
        clockDot.getStyleClass().add("touch-pulse-dot");

        Label clockLabel = new Label();
        clockLabel.getStyleClass().add("touch-clock-label");
        updateClockLabel(clockLabel);

        // Update clock every minute
        javafx.animation.Timeline clockTimeline = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(30), e -> updateClockLabel(clockLabel)));
        clockTimeline.setCycleCount(javafx.animation.Animation.INDEFINITE);
        clockTimeline.play();

        clockPill.getChildren().addAll(clockDot, clockLabel);

        // Quick discount chip
        Button discountChip = new Button("% Discount");
        discountChip.getStyleClass().add("touch-quick-chip");
        discountChip.setOnAction(e -> showSaleDiscountDialog());

        // Quick customer chip
        Button customerChip = new Button("\uD83D\uDC64 Customer");
        customerChip.getStyleClass().add("touch-quick-chip");
        customerChip.setOnAction(e -> showCustomerLookupDialog());

        // Quick note chip
        Button noteChip = new Button("\uD83D\uDCDD Note");
        noteChip.getStyleClass().add("touch-quick-chip");
        noteChip.setOnAction(e -> showOrderNoteDialog());

        // Clock out chip - allows employee to clock out from sales screen
        Button clockOutChip = new Button("\uD83D\uDD50 Clock Out");
        clockOutChip.getStyleClass().add("touch-quick-chip");
        clockOutChip.setStyle("-fx-background-color: #ef5350; -fx-text-fill: white;");
        clockOutChip.setOnAction(e -> {
            if (onNavigateToCloseShift != null) {
                onNavigateToCloseShift.run();
            } else {
                handleClockOut();
            }
        });
        
        // End Day chip - allows manager to perform end of day reconciliation
        Button endDayChip = new Button("\uD83D\uDCC5 End Day");
        endDayChip.getStyleClass().add("touch-quick-chip");
        endDayChip.setStyle("-fx-background-color: #3949ab; -fx-text-fill: white;");
        endDayChip.setOnAction(e -> {
            if (!cartItems.isEmpty()) {
                ToastNotification.showWarning("Please complete or cancel current transaction before ending day",
                        getScene().getWindow());
                return;
            }
            if (onNavigateToEndDay != null) {
                onNavigateToEndDay.run();
            }
        });

        // 1. Store Branding with Dynamic Item Count (The requested "Store Icon")
        HBox storeBranding = new HBox(10);
        storeBranding.setAlignment(Pos.CENTER);
        storeBranding.getStyleClass().add("touch-store-branding");
        storeBranding.setPadding(new Insets(8, 16, 8, 16));

        Label storeIcon = new Label("\uD83C\uDFEA");
        storeIcon.getStyleClass().add("touch-store-icon");
        storeIcon.setStyle("-fx-font-size: 18px;");

        String name = settingsService.getStoreName();
        Label storeNameLabel = new Label(name != null ? name.toUpperCase() : "POS STORE");
        storeNameLabel.getStyleClass().add("touch-store-header-name");
        storeNameLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #334155;");

        // Item count badge attached to the store branding
        cartHeaderItemCountLabel = new Label("0");
        cartHeaderItemCountLabel.getStyleClass().add("touch-store-count");
        refreshCartItemCountLabels();

        storeBranding.getChildren().addAll(storeIcon, storeNameLabel, cartHeaderItemCountLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        strip.getChildren().addAll(storeBranding, spacer, clockPill, discountChip, customerChip, noteChip, clockOutChip);
        return strip;
    }

    private void updateClockLabel(Label label) {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("h:mm a \u2022 EEE, MMM d");
        label.setText(now.format(formatter));
    }

    private StackPane createCenterContent() {
        StackPane container = new StackPane();

        // 1. Department View - MEGA tile mosaic with favorites row
        VBox departmentView = new VBox(0);
        departmentView.getStyleClass().add("touch-mega-grid-view");

        // Minimal header with large touch search
        HBox deptHeader = new HBox(16);
        deptHeader.setAlignment(Pos.CENTER_LEFT);
        deptHeader.setPadding(new Insets(16, 24, 12, 24));
        deptHeader.getStyleClass().add("touch-discovery-header");

        // Welcome message with current time context
        VBox greetingBox = new VBox(4);
        Label greetingLabel = new Label(getTimeBasedGreeting() + "!");
        greetingLabel.getStyleClass().add("touch-greeting-xl");
        Label subLabel = new Label("Tap a category to browse");
        subLabel.getStyleClass().add("touch-greeting-sub");
        greetingBox.getChildren().addAll(greetingLabel, subLabel);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        // MEGA search button - oversized for easy touch
        Button globalSearchBtn = new Button("\uD83D\uDD0D  Search All Products");
        globalSearchBtn.getStyleClass().add("touch-mega-search-btn");
        globalSearchBtn.setMinHeight(TOUCH_TARGET_MIN);
        globalSearchBtn.setMinWidth(200);
        globalSearchBtn.setOnAction(e -> showProductSearchDialog());

        deptHeader.getChildren().addAll(greetingBox, headerSpacer, globalSearchBtn);

        // MEGA department tiles - responsive grid
        departmentGrid = new GridPane();
        departmentGrid.setAlignment(Pos.CENTER);
        departmentGrid.setHgap(12);
        departmentGrid.setVgap(12);
        departmentGrid.setPadding(new Insets(12, 12, 12, 12));
        departmentGrid.getStyleClass().add("touch-mega-tile-grid");

        // Ensure grid takes available space
        VBox.setVgrow(departmentGrid, Priority.ALWAYS);

        // Add resize listener to adjust layout if needed, though Grid constraints
        // handle most
        departmentGrid.widthProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.doubleValue() > 0 && departmentGrid.getChildren().size() > 0) {
                // re-layout if strictly necessary, but percent constraints should handle it
            }
        });

        departmentView.getChildren().addAll(deptHeader, departmentGrid);

        // 2. Product View - touch-optimized card grid
        productView = new BorderPane();
        productView.getStyleClass().add("touch-product-view");

        // Product header - large touch nav
        HBox productHeader = new HBox(16);
        productHeader.setAlignment(Pos.CENTER_LEFT);
        productHeader.setPadding(new Insets(18, 24, 18, 24));
        productHeader.getStyleClass().add("touch-product-header");

        // Large back button for easy touch
        Button homeBtn = new Button("\u2190  Categories");
        homeBtn.getStyleClass().add("touch-back-orb");
        homeBtn.setMinHeight(TOUCH_TARGET_MIN);
        homeBtn.setMinWidth(150);
        homeBtn.setOnAction(e -> showDepartments());

        // Current category - large readable text
        currentDeptLabel = new Label("Category");
        currentDeptLabel.getStyleClass().add("touch-category-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Product count pill
        Label productCountLabel = new Label("");
        productCountLabel.getStyleClass().add("touch-count-pill");

        // Large search button
        Button quickSearchBtn = new Button("\uD83D\uDD0D  Search");
        quickSearchBtn.getStyleClass().add("touch-search-orb");
        quickSearchBtn.setMinHeight(TOUCH_TARGET_MIN);
        quickSearchBtn.setOnAction(e -> showProductSearchDialog());

        productHeader.getChildren().addAll(homeBtn, currentDeptLabel, spacer, productCountLabel, quickSearchBtn);
        productView.setTop(productHeader);

        // Product grid - MEGA tiles for touch
        productGrid = new FlowPane();
        productGrid.setHgap(18);
        productGrid.setVgap(18);
        productGrid.setAlignment(Pos.TOP_LEFT);
        productGrid.setPadding(new Insets(16, 24, 24, 24));
        productGrid.getStyleClass().add("touch-product-mega-grid");

        productScrollPane = new ScrollPane(productGrid);
        productScrollPane.setFitToWidth(true);
        productScrollPane.getStyleClass().add("touch-smooth-scroll");

        // Pagination - large touch buttons
        paginationControls = createPaginationControls();

        VBox productCenter = new VBox(12);
        productCenter.getChildren().addAll(productScrollPane, paginationControls);
        VBox.setVgrow(productScrollPane, Priority.ALWAYS);
        productView.setCenter(productCenter);

        // Loading indicator - modern orbital spinner
        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setMaxSize(60, 60);
        loadingIndicator.setVisible(false);
        loadingIndicator.getStyleClass().add("touch-loading-spinner");

        container.getChildren().addAll(departmentView, productView, loadingIndicator);

        return container;
    }

    private String getTimeBasedGreeting() {
        int hour = LocalDateTime.now().getHour();
        if (hour < 12)
            return "Good Morning";
        if (hour < 17)
            return "Good Afternoon";
        return "Good Evening";
    }

    private void showDepartments() {
        productView.setVisible(false);
        productView.setManaged(false);
        // Show parent container (departmentView)
        departmentGrid.getParent().setVisible(true);
        departmentGrid.getParent().setManaged(true);
        departmentGrid.setVisible(true);
        departmentGrid.setManaged(true);
        currentDepartmentId = null;
        departmentGrid.getParent().toFront();
    }

    private void showProducts(String deptId, String deptName) {
        currentDepartmentId = deptId;
        currentDeptLabel.setText(deptName);
        // Hide parent container (departmentView)
        departmentGrid.getParent().setVisible(false);
        departmentGrid.getParent().setManaged(false);
        productView.setVisible(true);
        productView.setManaged(true);
        productView.toFront();
        currentPage = 0;
        loadProducts(deptId);
    }

    private void loadDepartments() {
        // Fetch departments off the FX thread. getDepartments() is a blocking DB query and
        // this method runs on init, on refresh() after every sale, and on every department
        // WebSocket event — running it synchronously on the FX thread froze the sales screen
        // ("Not Responding"), especially when the connection pool was busy during sync.
        new Thread(() -> {
            Map<String, String> depts = salesService.getDepartments();
            javafx.application.Platform.runLater(() -> buildDepartmentGrid(depts));
        }, "load-departments").start();
    }

    /** Builds the department button grid on the FX thread from already-fetched data. */
    private void buildDepartmentGrid(Map<String, String> depts) {
        departmentGrid.getChildren().clear();
        departmentGrid.getColumnConstraints().clear();
        departmentGrid.getRowConstraints().clear();

        int totalDepts = depts.size();
        if (totalDepts == 0)
            return;

        // Calculate grid dimensions to ideally fit items
        // We want a balance, slightly more columns than rows usually looks better for
        // wide screens
        // But for squarish touch screens, sqrt is good.

        int cols = (int) Math.ceil(Math.sqrt(totalDepts));
        if (cols < 2)
            cols = 2; // Min 2 columns
        int rows = (int) Math.ceil((double) totalDepts / cols);

        // Setup percent constraints
        for (int i = 0; i < cols; i++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(100.0 / cols);
            col.setHgrow(Priority.ALWAYS);
            col.setFillWidth(true);
            departmentGrid.getColumnConstraints().add(col);
        }

        for (int i = 0; i < rows; i++) {
            RowConstraints row = new RowConstraints();
            row.setPercentHeight(100.0 / rows);
            row.setVgrow(Priority.ALWAYS);
            row.setFillHeight(true);
            departmentGrid.getRowConstraints().add(row);
        }

        colorIndex = 0;
        int col = 0;
        int row = 0;

        for (Map.Entry<String, String> entry : depts.entrySet()) {
            Button deptBtn = createCategoryButton(entry.getValue(), entry.getKey());
            // Make button fill the grid cell
            deptBtn.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

            departmentGrid.add(deptBtn, col, row);

            col++;
            if (col >= cols) {
                col = 0;
                row++;
            }
        }
    }

    private Button createCategoryButton(String name, String id) {
        Button btn = new Button();
        // Remove fixed sizes to allow scaling
        // btn.setPrefSize(140, 60);
        // btn.setMinSize(130, 50);
        btn.getStyleClass().add("touch-mega-category-tile");

        // Stacked card layout
        VBox content = new VBox(0);
        content.setAlignment(Pos.CENTER);

        // Category name - readable text
        Label nameLabel = new Label(name);
        nameLabel.setWrapText(true);
        nameLabel.setTextAlignment(TextAlignment.CENTER);
        nameLabel.setMaxWidth(130);
        nameLabel.getStyleClass().add("touch-category-label");
        nameLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: white;");

        content.getChildren().add(nameLabel);
        btn.setGraphic(content);

        // Vibrant gradient with smooth corners
        String color = CATEGORY_COLORS[colorIndex % CATEGORY_COLORS.length];
        btn.setStyle("-fx-background-color: linear-gradient(135deg, " + color + " 0%, "
                + adjustColorBrightness(color, -30) + " 100%); -fx-background-radius: 12;"); // Reduced radius
        colorIndex++;

        btn.setOnAction(e -> handleDepartmentClick(name, id));
        return btn;
    }

    private String adjustColorBrightness(String hexColor, int amount) {
        try {
            int r = Integer.parseInt(hexColor.substring(1, 3), 16);
            int g = Integer.parseInt(hexColor.substring(3, 5), 16);
            int b = Integer.parseInt(hexColor.substring(5, 7), 16);
            r = Math.max(0, Math.min(255, r + amount));
            g = Math.max(0, Math.min(255, g + amount));
            b = Math.max(0, Math.min(255, b + amount));
            return String.format("#%02X%02X%02X", r, g, b);
        } catch (Exception e) {
            return hexColor;
        }
    }

    private void handleDepartmentClick(String name, String id) {
        BigDecimal inputValue = getInputValue();

        if (inputValue.compareTo(BigDecimal.ZERO) > 0) {
            // Create open-price item
            createOpenPriceItem(name, id, inputValue);
            clearInput();
        } else {
            showProducts(id, name);
        }
    }

    private void createOpenPriceItem(String deptName, String deptId, BigDecimal cashPrice) {
        Product openProduct = new Product(
                UUID.randomUUID().toString(),
                "OPEN-" + System.currentTimeMillis(),
                deptName + " (Open)",
                cashPrice,
                9999,
                deptId);

        addProductToCart(openProduct, pendingQuantityMultiplier);
    }

    // ==================== RIGHT PANEL: FUNCTIONS ====================

    private BorderPane createFunctionPanel() {
        BorderPane panel = new BorderPane();
        panel.getStyleClass().add("touch-action-container");

        // 1. Minimal header - compact for small screens
        VBox headerBox = new VBox(2);
        headerBox.setPadding(new Insets(8, 6, 6, 6));
        headerBox.getStyleClass().add("touch-action-header");

        Label functionsLabel = new Label("\u26A1 ACTIONS");
        functionsLabel.getStyleClass().add("touch-action-title");
        functionsLabel.setMaxWidth(Double.MAX_VALUE);
        functionsLabel.setAlignment(Pos.CENTER);
        functionsLabel.setStyle(
                "-fx-font-size: 9px; -fx-font-weight: 800; -fx-text-fill: #94a3b8; -fx-letter-spacing: 1px;");

        headerBox.getChildren().add(functionsLabel);
        panel.setTop(headerBox);

        // 2. Compact action buttons for small screens
        VBox functionsContainer = new VBox(6);
        functionsContainer.setPadding(new Insets(4, 6, 4, 6));
        functionsContainer.setFillWidth(true);
        functionsContainer.setAlignment(Pos.TOP_CENTER);

        Button timesheetBtn = createTouchOrb("\uD83D\uDCCB", "Timesheet", "touch-orb-indigo");
        timesheetBtn.setOnAction(e -> {
            if (onNavigateToTimesheet != null) {
                onNavigateToTimesheet.run();
            } else {
                ToastNotification.showWarning("Timesheet screen is not available.", getSceneWindow());
            }
        });

        // Hold Sale - Large touch orb
        Button holdSaleBtn = createTouchOrb("\u23F8", "Hold", "touch-orb-amber");
        holdSaleBtn.setOnAction(e -> holdSale());

        // Recall Sale
        Button recallSaleBtn = createTouchOrb("\u25B6", "Recall", "touch-orb-teal");
        recallSaleBtn.setOnAction(e -> recallSale());

        // Return
        Button returnBtn = createTouchOrb("\u21A9", "Return", "touch-orb-orange");
        returnBtn.setOnAction(e -> showReturnDialog());

        // Cancel Receipt - Clear entire cart
        Button cancelReceiptBtn = createTouchOrb("\u2716", "Cancel", "touch-orb-red");
        cancelReceiptBtn.setOnAction(e -> cancelReceipt());

        // No Sale / Open Drawer
        Button noSaleBtn = createTouchOrb("\uD83D\uDCB5", "Drawer", "touch-orb-slate");
        noSaleBtn.setOnAction(e -> handleNoSale());
        // Disable button if user doesn't have permission
        if (!rbacService.hasPermission(RoleBasedAccessService.PERMISSION_NO_SALE)) {
            noSaleBtn.setDisable(true);
            noSaleBtn.setOpacity(0.5);
        }

        // Cash Drop - Essential feature for removing excess cash from drawer
        Button cashDropBtn = createTouchOrb("\uD83C\uDFE6", "Cash Drop", "touch-orb-blue");
        cashDropBtn.setOnAction(e -> {
            if (onNavigateToCashDrop != null) {
                onNavigateToCashDrop.run();
            } else {
                showCashDropDialog(); // Fallback for safety
            }
        });
        // Disable button if user doesn't have permission for cash operations
        if (!rbacService.hasPermission(RoleBasedAccessService.PERMISSION_CASH_OPERATIONS)) {
            cashDropBtn.setDisable(true);
            cashDropBtn.setOpacity(0.5);
        }

        // Vendor Payout
        Button vendorPayoutBtn = createTouchOrb("\uD83D\uDCB0", "Payout", "touch-orb-emerald");
        vendorPayoutBtn.setOnAction(e -> showVendorPayoutDialog());

        // End Day - Redirected from End Shift to End Day as requested
        Button endShiftBtn = createTouchOrb("\uD83D\uDD12", "End", "touch-orb-rose");
        endShiftBtn.setOnAction(e -> {
            if (!cartItems.isEmpty()) {
                ToastNotification.showWarning("Please complete or cancel current transaction before ending day",
                        getScene().getWindow());
                return;
            }
            if (onNavigateToEndDay != null) {
                onNavigateToEndDay.run();
            }
        });

        functionsContainer.getChildren().addAll(
                timesheetBtn,
                holdSaleBtn, recallSaleBtn,
                returnBtn, cancelReceiptBtn, noSaleBtn, cashDropBtn, vendorPayoutBtn, endShiftBtn);

        // Smooth scroll for touch
        ScrollPane scrollPane = new ScrollPane(functionsContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.getStyleClass().add("touch-smooth-scroll");

        panel.setCenter(scrollPane);

        // 3. Compact CHECKOUT button for small screens
        VBox footerBox = new VBox(4);
        footerBox.setPadding(new Insets(6, 8, 10, 8));
        footerBox.getStyleClass().add("touch-checkout-footer");

        // CHECKOUT - compact for small screens
        totalBtn = new Button("CHECKOUT\n$0.00");
        totalBtn.setMaxWidth(Double.MAX_VALUE);
        totalBtn.setPrefHeight(60);
        totalBtn.setMinHeight(55);
        totalBtn.getStyleClass().add("touch-mega-checkout");
        totalBtn.setStyle(
                "-fx-background-color: linear-gradient(to bottom right, #1e293b 0%, #312e81 100%); -fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: 800; -fx-background-radius: 12; -fx-effect: dropshadow(gaussian, rgba(15, 23, 42, 0.4), 8, 0, 0, 3);");
        totalBtn.setOnAction(e -> navigateToPaymentScreen());
        totalBtn.setTextAlignment(TextAlignment.CENTER);

        footerBox.getChildren().add(totalBtn);
        panel.setBottom(footerBox);

        return panel;
    }

    private Button createTouchOrb(String icon, String label, String styleClass) {
        Button btn = new Button();
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setMinHeight(TOUCH_TARGET_MIN); // Compact height for small screens
        btn.setPrefHeight(52);
        btn.setTextAlignment(TextAlignment.CENTER);

        // Compact horizontal layout with icon + label for small screens
        HBox content = new HBox(4);
        content.setAlignment(Pos.CENTER);

        Label iconLabel = new Label(icon);
        iconLabel.setStyle("-fx-font-size: 16px;");

        Label textLabel = new Label(label);
        textLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: 700; -fx-text-fill: white;");

        content.getChildren().addAll(iconLabel, textLabel);
        btn.setGraphic(content);
        btn.getStyleClass().addAll("touch-action-orb", styleClass);

        return btn;
    }

    // ==================== PAGINATION ====================

    private HBox createPaginationControls() {
        HBox controls = new HBox(16);
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(14));
        controls.getStyleClass().add("touch-pagination");

        prevPageBtn = new Button("\u2190  Prev");
        prevPageBtn.setOnAction(e -> loadPreviousPage());
        prevPageBtn.setDisable(true);
        prevPageBtn.getStyleClass().add("touch-page-btn");
        prevPageBtn.setMinHeight(TOUCH_TARGET_MIN);
        prevPageBtn.setMinWidth(100);

        pageInfoLabel = new Label("Page 1");
        pageInfoLabel.getStyleClass().add("touch-page-info");
        pageInfoLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: 600; -fx-text-fill: #64748b;");

        nextPageBtn = new Button("Next  \u2192");
        nextPageBtn.setOnAction(e -> loadNextPage());
        nextPageBtn.setDisable(true);
        nextPageBtn.getStyleClass().add("touch-page-btn");
        nextPageBtn.setMinHeight(TOUCH_TARGET_MIN);
        nextPageBtn.setMinWidth(100);

        controls.getChildren().addAll(prevPageBtn, pageInfoLabel, nextPageBtn);
        return controls;
    }

    private void loadPreviousPage() {
        if (currentPage > 0) {
            currentPage--;
            loadProducts(currentDepartmentId);
        }
    }

    private void loadNextPage() {
        if (currentPage < totalPages - 1) {
            currentPage++;
            loadProducts(currentDepartmentId);
        }
    }

    private void updatePaginationControls() {
        prevPageBtn.setDisable(currentPage <= 0 || isLoading);
        nextPageBtn.setDisable(currentPage >= totalPages - 1 || isLoading);

        if (totalProducts == 0) {
            pageInfoLabel.setText("No items");
        } else {
            pageInfoLabel.setText(String.format("Page %d of %d", currentPage + 1, totalPages));
        }
    }

    private void setLoading(boolean loading) {
        this.isLoading = loading;
        javafx.application.Platform.runLater(() -> {
            if (loadingIndicator != null)
                loadingIndicator.setVisible(loading);
            if (productGrid != null)
                productGrid.setOpacity(loading ? 0.5 : 1.0);
            updatePaginationControls();
        });
    }

    private void loadProducts(String departmentId) {
        if (isLoading)
            return;
        setLoading(true);

        new Thread(() -> {
            try {
                int count = salesService.getProductCount(departmentId);
                totalProducts = count;
                totalPages = (int) Math.ceil((double) count / PAGE_SIZE);

                if (currentPage >= totalPages && totalPages > 0) {
                    currentPage = totalPages - 1;
                }

                int offset = currentPage * PAGE_SIZE;
                List<Product> products = salesService.getProductsByDepartment(departmentId, offset, PAGE_SIZE);

                javafx.application.Platform.runLater(() -> {
                    displayProducts(products);
                    setLoading(false);
                });
            } catch (Exception e) {
                logger.error("Error loading products", e);
                javafx.application.Platform.runLater(() -> {
                    displayProducts(List.of());
                    setLoading(false);
                });
            }
        }).start();
    }

    private void displayProducts(List<Product> products) {
        productGrid.getChildren().clear();
        for (Product product : products) {
            Button card = createProductCard(product);
            productGrid.getChildren().add(card);
        }
        productScrollPane.setVvalue(0);
        updatePaginationControls();
    }

    private Button createProductCard(Product product) {
        Button btn = new Button();
        // MEGA touch target - 135x130 for better density
        btn.setPrefSize(135, 130);
        btn.setMinSize(135, 130);
        btn.getStyleClass().add("touch-product-tile");

        // Vibrant accent color for visual variety
        String accentColor = PRODUCT_ACCENT_COLORS[Math.abs(product.getName().hashCode())
                % PRODUCT_ACCENT_COLORS.length];

        VBox content = new VBox(8);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(8));

        // Colored side accent - unique vertical bar
        StackPane cardContent = new StackPane();

        Region accentBar = new Region();
        accentBar.setMinWidth(6);
        accentBar.setMaxWidth(6);
        accentBar.setMinHeight(100);
        accentBar.setStyle("-fx-background-color: " + accentColor + "; -fx-background-radius: 3;");
        StackPane.setAlignment(accentBar, Pos.CENTER_LEFT);

        VBox textContent = new VBox(8);
        textContent.setAlignment(Pos.CENTER);
        textContent.setPadding(new Insets(0, 0, 0, 10));

        // Product name - larger readable text
        Label nameLabel = new Label(product.getName());
        nameLabel.setWrapText(true);
        nameLabel.getStyleClass().add("touch-product-name");
        nameLabel.setMaxWidth(130);
        nameLabel.setMaxHeight(50);
        nameLabel.setTextAlignment(TextAlignment.CENTER);
        nameLabel.setAlignment(Pos.CENTER);
        nameLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 600; -fx-text-fill: #1e293b;");

        // Price - prominent pill badge
        Label priceLabel = new Label(NumberFormat.getCurrencyInstance().format(product.getPrice()));
        priceLabel.getStyleClass().add("touch-product-price");
        priceLabel.setStyle("-fx-background-color: " + accentColor
                + "; -fx-text-fill: white; -fx-padding: 6 14; -fx-background-radius: 16; -fx-font-size: 14px; -fx-font-weight: bold;");

        textContent.getChildren().addAll(nameLabel, priceLabel);

        // Stock indicator - clear badges
        int stock = product.getStock();
        if (stock < 0) {
            Label stockBadge = new Label("STOCK NOT UPDATED");
            stockBadge.getStyleClass().add("touch-stock-low");
            stockBadge.setStyle(
                    "-fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-padding: 4 10; -fx-background-radius: 12; -fx-font-size: 10px; -fx-font-weight: bold;");
            textContent.getChildren().add(stockBadge);
        } else if (stock == 0) {
            Label stockBadge = new Label("OUT OF STOCK");
            stockBadge.getStyleClass().add("touch-stock-out");
            stockBadge.setStyle(
                    "-fx-background-color: #fef2f2; -fx-text-fill: #dc2626; -fx-padding: 4 10; -fx-background-radius: 12; -fx-font-size: 10px; -fx-font-weight: bold;");
            textContent.getChildren().add(stockBadge);
            // Products can still be sold when out of stock - just show the indicator
        } else if (stock <= 5) {
            Label stockBadge = new Label("LOW: " + stock + " left");
            stockBadge.getStyleClass().add("touch-stock-low");
            stockBadge.setStyle(
                    "-fx-background-color: #fef3c7; -fx-text-fill: #d97706; -fx-padding: 4 10; -fx-background-radius: 12; -fx-font-size: 10px; -fx-font-weight: bold;");
            textContent.getChildren().add(stockBadge);
        }

        cardContent.getChildren().addAll(textContent, accentBar);
        content.getChildren().add(cardContent);

        btn.setGraphic(content);
        btn.setStyle(
                "-fx-background-color: white; -fx-background-radius: 20; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 8, 0, 0, 4);");
        btn.setOnAction(e -> addProductToCart(product, pendingQuantityMultiplier));

        return btn;
    }

    // ==================== KEYPAD HANDLING ====================

    private void handleKeypadInput(String key) {
        if ("C".equals(key)) {
            inputBuffer.setLength(0);
            clearQuantityMultiplier();
        } else if ("\u232B".equals(key)) {
            if (inputBuffer.length() > 0) {
                inputBuffer.setLength(inputBuffer.length() - 1);
            }
        } else if (NumericKeypad.QTY_KEY.equals(key) || "\u00D7".equals(key) || "X".equals(key) || "x".equals(key)) {
            setQuantityMultiplier();
        } else if (".".equals(key)) {
            // Ignore decimal point - we handle cents automatically
        } else if ("\u21B5".equals(key) || "ENTER".equals(key)) {
            // Ignore enter key - handled separately by handleEnterPressed
        } else {
            // Append digit(s) to buffer
            if (inputBuffer.length() < 10) {
                inputBuffer.append(key);
            }
        }
        updateInputDisplay();
    }

    private void handleEnterPressed() {
        // When Enter is pressed, try to add an open item with the entered price
        BigDecimal value = getInputValue();
        if (value.compareTo(BigDecimal.ZERO) > 0) {
            // Create an open item with the entered price
            String barcode = "OPEN-" + System.currentTimeMillis();
            Product openItem = new Product(
                    UUID.randomUUID().toString(),
                    barcode,
                    "Open Item",
                    value,
                    9999,
                    "MISC");

            addProductToCart(openItem, pendingQuantityMultiplier);
            clearInput();
        }
    }

    private void setQuantityMultiplier() {
        // The QTY key treats the current entry as a whole-number item count
        // (e.g. type "4" then QTY -> the next scanned item is added x4),
        // not as a currency amount, so parse the raw digits directly.
        if (inputBuffer.length() == 0) {
            return;
        }
        try {
            int qty = Integer.parseInt(inputBuffer.toString());
            if (qty > 0 && qty <= 999) {
                pendingQuantityMultiplier = qty;
                multiplierLabel.setText("Next item: \u00D7 " + pendingQuantityMultiplier);
                multiplierLabel.setVisible(true);
                multiplierLabel.setManaged(true);
                clearInput();
            }
        } catch (NumberFormatException e) {
            // Ignore non-numeric entries
        }
    }

    private void clearQuantityMultiplier() {
        pendingQuantityMultiplier = 1;
        multiplierLabel.setVisible(false);
        multiplierLabel.setManaged(false);
    }

    private void updateInputDisplay() {
        if (inputBuffer.length() == 0) {
            inputDisplayLabel.setText("0.00");
        } else {
            try {
                // Parse as cents and format as currency
                long cents = Long.parseLong(inputBuffer.toString());
                BigDecimal amount = new BigDecimal(cents).divide(new BigDecimal(100), 2,
                        java.math.RoundingMode.HALF_UP);
                NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
                inputDisplayLabel.setText(currencyFormat.format(amount));
            } catch (NumberFormatException e) {
                inputDisplayLabel.setText(inputBuffer.toString());
            }
        }
    }

    private BigDecimal getInputValue() {
        if (inputBuffer.length() == 0)
            return BigDecimal.ZERO;
        try {
            // Parse as cents and convert to dollars
            long cents = Long.parseLong(inputBuffer.toString());
            return new BigDecimal(cents).divide(new BigDecimal(100), 2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private void clearInput() {
        inputBuffer.setLength(0);
        updateInputDisplay();
    }

    // ==================== CART OPERATIONS ====================

    private void handleBarcodeScan(String barcode) {
        logger.info("Barcode scanned: {}", barcode);
        // The lookup hits the local DB and may fall back to a backend HTTP call,
        // so run it off the JavaFX thread to keep scanning responsive. All UI
        // mutation happens back on the FX thread via Platform.runLater.
        new Thread(() -> {
            Product product = salesService.getProductByBarcode(barcode);
            javafx.application.Platform.runLater(() -> {
                clearInput();
                if (product != null) {
                    addProductToCart(product, pendingQuantityMultiplier);
                    SalesScreen.this.requestFocus();
                } else {
                    handleUnknownBarcode(barcode);
                }
            });
        }, "BarcodeLookup").start();
    }

    /**
     * Prompt to create a product for an unrecognized barcode. Must run on the
     * JavaFX application thread (shows modal dialogs).
     */
    private void handleUnknownBarcode(String barcode) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Product Not Found");
        alert.setHeaderText("Unknown Barcode: " + barcode);
        alert.setContentText("This product does not exist in the database.\nWould you like to add it now?");
        DialogHelper.setAlertOwner(alert, getSceneWindow());

        try {
            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                // Show product form dialog with pre-filled barcode
                ProductFormDialog dialog = new ProductFormDialog(null, null, null, hardwareManager, barcode);
                dialog.showAndWait().ifPresent(formResult -> {
                    try {
                        // Save new product
                        productManagementService.createProduct(formResult.product,
                                formResult.sku != null ? formResult.sku : "");
                        ToastNotification.showSuccess("Product created successfully", getSceneWindow());

                        // Add to cart immediately
                        // We need to fetch the full product with ID to ensure it works correctly
                        Product newProduct = salesService.getProductByBarcode(formResult.product.getBarcode());
                        if (newProduct != null) {
                            addProductToCart(newProduct, 1);
                        }
                    } catch (SQLException e) {
                        logger.error("Error creating product from scan", e);
                        DialogHelper.showError("Error", "Failed to create product: " + e.getMessage(),
                                getSceneWindow());
                    }
                });
            }
        } finally {
            // CRITICAL: Always restore scanner callback after ANY dialog interaction
            // (OK, Cancel, or dialog close). The ProductFormDialog sets callback to null
            // when it closes, so we must always restore it here.
            hardwareManager.setScanCallback(this::handleBarcodeScan);
            logger.debug("Scanner callback restored after product not found dialog");
        }
        // Ensure focus returns to the screen after dialog is dismissed
        javafx.application.Platform.runLater(() -> SalesScreen.this.requestFocus());
    }

    private void addProductToCart(Product product, int quantity) {
        clearQuantityMultiplier();

        SaleItem existingItem = null;
        String barcode = product.getBarcode();
        if (barcode != null && !product.getName().contains("(Open)")) {
            existingItem = cartItemsByBarcode.get(barcode);
        }

        if (existingItem != null) {
            registerCartItemListeners(existingItem);
            existingItem.setQuantity(existingItem.getQuantity() + quantity);
        } else {
            SaleItem item = new SaleItem(product, quantity, selectedPaymentMethod);
            registerCartItemListeners(item);
            cartItems.add(item);
            if (barcode != null && !product.getName().contains("(Open)")) {
                cartItemsByBarcode.put(barcode, item);
            }
            checkAndApplyMultiPackDiscount(item);
            scheduleUpdateTotal();
        }
        cartTable.scrollTo(cartItems.size() - 1);
        updateReceiptDateTime();
    }

    private void registerCartItemListeners(SaleItem item) {
        if (!cartItemsWithQuantityListeners.add(item)) {
            return;
        }
        item.quantityProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.equals(oldVal)) {
                checkAndApplyMultiPackDiscount(item);
                scheduleUpdateTotal();
            }
        });
    }

    /**
     * Check if an item is eligible for multi-pack discount and apply it
     * automatically
     */
    private void checkAndApplyMultiPackDiscount(SaleItem item) {
        try {
            // Get department ID from the item
            String deptId = item.getProduct().getDepartmentId();
            if (deptId == null) {
                return; // No department, cannot apply multipack discount
            }

            // Get department-specific multipack settings
            SettingsService.MultiPackDiscountSettings settings = settingsService
                    .getMultiPackDiscountSettingsForDepartment(deptId);

            // Check if multi-pack discount is enabled for this department
            if (!settings.enabled) {
                return;
            }

            // Check if item already has a multi-pack discount applied (manual or auto)
            String existingReason = item.getDiscountReason();
            boolean hasExistingMultiPack = existingReason != null && existingReason.contains("Multi-Pack");

            if (hasExistingMultiPack) {
                // Check if quantity dropped below minimum - if so, remove discount
                if (item.getQuantity() < settings.minimumQuantity) {
                    // Quantity dropped below minimum, remove discount
                    item.clearDiscount();
                    return;
                }
            }

            // Check if quantity meets minimum requirement
            if (item.getQuantity() < settings.minimumQuantity) {
                return;
            }

            // If we reach here, we're going to apply auto discount
            // Remove any existing manual multipack discount first
            if (hasExistingMultiPack) {
                // Clear existing discount to replace with auto-applied one
                item.clearDiscount();
            }

            // Calculate discount amount
            BigDecimal baseTotal = item.getBaseTotal();
            BigDecimal discountAmount = BigDecimal.ZERO;
            BigDecimal discountPercent = BigDecimal.ZERO;

            if (settings.discountType == SettingsService.MultiPackDiscountSettings.DiscountType.PERCENT) {
                discountPercent = settings.discountValue;
                discountAmount = baseTotal.multiply(settings.discountValue)
                        .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
            } else {
                // Fixed amount discount
                discountAmount = settings.discountValue.min(baseTotal);
            }

            // Apply discount (no manager approval needed for automatic application)
            if (discountPercent.compareTo(BigDecimal.ZERO) > 0) {
                item.setDiscountPercent(discountPercent);
            } else {
                item.setDiscountAmount(discountAmount);
            }
            item.setDiscountReason("Multi-Pack Discount (" + settings.getDiscountDisplayString() + ")");

            logger.info("Auto-applied multi-pack discount: {} {} to {} (qty: {})",
                    settings.discountType == SettingsService.MultiPackDiscountSettings.DiscountType.PERCENT
                            ? discountPercent + "%"
                            : "$" + discountAmount,
                    item.getProductName(), item.getQuantity());
        } catch (Exception e) {
            logger.error("Error checking/applying multi-pack discount", e);
        }
    }



    // ==================== TOTALS ====================

    private void scheduleUpdateTotal() {
        if (totalUpdateDebounce == null) {
            totalUpdateDebounce = new PauseTransition(TOTAL_UPDATE_DEBOUNCE);
            totalUpdateDebounce.setOnFinished(e -> updateTotal());
        }
        totalUpdateDebounce.playFromStart();
    }

    private void scheduleCartRefresh() {
        if (cartRefreshDebounce == null) {
            cartRefreshDebounce = new PauseTransition(CART_REFRESH_DEBOUNCE);
            cartRefreshDebounce.setOnFinished(e -> cartTable.refresh());
        }
        cartRefreshDebounce.playFromStart();
    }

    private void updateTotal() {
        refreshCartItemCountLabels();

        // If cart is empty, reset all sale-level state
        if (cartItems.isEmpty()) {
            saleDiscount = BigDecimal.ZERO;
            saleDiscountReason = "";
            currentCustomer = null;
            orderNote = "";
            clearInput();
            clearQuantityMultiplier();
            updateCustomerDisplay();
            CustomerDisplayService.getInstance().resetDisplay();

            // Re-generate receipt number if it was a fresh start after clearing
            // This matches clearCartSilently behavior for consistency
            currentReceiptNumber = receiptCounter.incrementAndGet();
            if (receiptNumberLabel != null) {
                receiptNumberLabel.setText(String.format("%07d", currentReceiptNumber));
            }
            updateReceiptDateTime();
        }

        // Calculate cash price totals
        BigDecimal cashSubtotal = cartItems.stream()
                .map(SaleItem::getBaseTotalAtCashPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal lineItemDiscounts = cartItems.stream()
                .map(SaleItem::getTotalDiscount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Apply discounts to cash subtotal
        BigDecimal cashDiscountedSubtotal = cashSubtotal.subtract(lineItemDiscounts).subtract(saleDiscount);
        if (cashDiscountedSubtotal.compareTo(BigDecimal.ZERO) < 0)
            cashDiscountedSubtotal = BigDecimal.ZERO;

        // Calculate card price totals
        BigDecimal cardSubtotal = cartItems.stream()
                .map(SaleItem::getBaseTotalAtCardPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal cardLineItemDiscounts = cartItems.stream()
                .map(item -> {
                    BigDecimal base = item.getBaseTotalAtCardPrice();
                    BigDecimal discount = BigDecimal.ZERO;

                    // Percent
                    BigDecimal pct = item.getDiscountPercent();
                    if (pct != null && pct.compareTo(BigDecimal.ZERO) > 0) {
                        discount = base.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    }

                    // Fixed
                    BigDecimal fixed = item.getDiscountAmount();
                    if (fixed != null) {
                        discount = discount.add(fixed);
                    }

                    // Cap
                    return discount.min(base);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Apply discounts to card subtotal
        BigDecimal cardDiscountedSubtotal = cardSubtotal.subtract(cardLineItemDiscounts).subtract(saleDiscount);
        if (cardDiscountedSubtotal.compareTo(BigDecimal.ZERO) < 0)
            cardDiscountedSubtotal = BigDecimal.ZERO;

        // Calculate tax based on department-specific tax settings
        BigDecimal cashTotalTax = BigDecimal.ZERO;
        BigDecimal cardTotalTax = BigDecimal.ZERO;

        // Group items by department ID
        Map<String, List<SaleItem>> itemsByDept = cartItems.stream()
                .collect(Collectors.groupingBy(item -> {
                    String deptId = item.getProduct().getDepartmentId();
                    return deptId != null ? deptId : "NO_DEPT";
                }));

        // Calculate tax for each department
        for (Map.Entry<String, List<SaleItem>> entry : itemsByDept.entrySet()) {
            String deptId = entry.getKey();
            List<SaleItem> deptItems = entry.getValue();

            // Skip items without department
            if ("NO_DEPT".equals(deptId)) {
                continue;
            }

            try {
                ProductManagementService.Department dept = productManagementService.getDepartmentById(deptId);

                // Only apply tax if department has tax enabled
                if (dept != null && dept.taxEnabled) {
                    BigDecimal taxRateFactor = settingsService.getDefaultTaxRate();

                    // --- CASH CALCULATION ---
                    BigDecimal deptCashSubtotal = deptItems.stream()
                            .map(SaleItem::getBaseTotalAtCashPrice)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    BigDecimal deptLineDiscounts = deptItems.stream()
                            .map(SaleItem::getTotalDiscount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    BigDecimal deptCashDiscountedSubtotal = deptCashSubtotal.subtract(deptLineDiscounts);

                    if (saleDiscount.compareTo(BigDecimal.ZERO) > 0
                            && cashDiscountedSubtotal.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal deptProportion = deptCashDiscountedSubtotal.divide(cashDiscountedSubtotal, 4,
                                RoundingMode.HALF_UP);
                        BigDecimal deptSaleDiscount = saleDiscount.multiply(deptProportion).setScale(2,
                                RoundingMode.HALF_UP);
                        deptCashDiscountedSubtotal = deptCashDiscountedSubtotal.subtract(deptSaleDiscount);
                    }

                    if (deptCashDiscountedSubtotal.compareTo(BigDecimal.ZERO) < 0) {
                        deptCashDiscountedSubtotal = BigDecimal.ZERO;
                    }

                    BigDecimal deptCashTax = deptCashDiscountedSubtotal
                            .multiply(taxRateFactor)
                            .setScale(2, RoundingMode.HALF_UP);

                    cashTotalTax = cashTotalTax.add(deptCashTax);

                    // --- CARD CALCULATION ---
                    BigDecimal deptCardSubtotal = deptItems.stream()
                            .map(SaleItem::getBaseTotalAtCardPrice)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    BigDecimal deptCardLineDiscounts = deptItems.stream()
                            .map(item -> {
                                BigDecimal base = item.getBaseTotalAtCardPrice();
                                BigDecimal discount = BigDecimal.ZERO;
                                BigDecimal pct = item.getDiscountPercent();
                                if (pct != null && pct.compareTo(BigDecimal.ZERO) > 0) {
                                    discount = base.multiply(pct).divide(BigDecimal.valueOf(100), 2,
                                            RoundingMode.HALF_UP);
                                }
                                BigDecimal fixed = item.getDiscountAmount();
                                if (fixed != null) {
                                    discount = discount.add(fixed);
                                }
                                return discount.min(base);
                            })
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    BigDecimal deptCardDiscountedSubtotal = deptCardSubtotal.subtract(deptCardLineDiscounts);

                    if (saleDiscount.compareTo(BigDecimal.ZERO) > 0
                            && cardDiscountedSubtotal.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal deptProportion = deptCardDiscountedSubtotal.divide(cardDiscountedSubtotal, 4,
                                RoundingMode.HALF_UP);
                        BigDecimal deptSaleDiscount = saleDiscount.multiply(deptProportion).setScale(2,
                                RoundingMode.HALF_UP);
                        deptCardDiscountedSubtotal = deptCardDiscountedSubtotal.subtract(deptSaleDiscount);
                    }

                    if (deptCardDiscountedSubtotal.compareTo(BigDecimal.ZERO) < 0) {
                        deptCardDiscountedSubtotal = BigDecimal.ZERO;
                    }

                    BigDecimal deptCardTax = deptCardDiscountedSubtotal
                            .multiply(taxRateFactor)
                            .setScale(2, RoundingMode.HALF_UP);

                    cardTotalTax = cardTotalTax.add(deptCardTax);
                }
            } catch (SQLException e) {
                logger.error("Error getting department tax info for dept: " + deptId, e);
            }
        }

        // Set cash totals
        subtotal = cashSubtotal;
        tax = cashTotalTax;
        currentTotal = cashDiscountedSubtotal.add(tax);

        BigDecimal totalDiscount = lineItemDiscounts.add(saleDiscount);

        CustomerDisplayService.getInstance().updateTotals(subtotal, totalDiscount, tax, currentTotal);

        NumberFormat fmt = NumberFormat.getCurrencyInstance();

        // Update cash price labels
        subtotalLabel.setText(fmt.format(cashDiscountedSubtotal));
        taxLabel.setText(fmt.format(tax));

        // Update card price label
        BigDecimal cardTotal = cardDiscountedSubtotal.add(cardTotalTax);
        if (cardTotalAmountLabel != null) {
            cardTotalAmountLabel.setText(String.format("%.2f", cardTotal));
        }

        // Update discount display
        if (totalDiscount.compareTo(BigDecimal.ZERO) > 0) {
            discountLabel.setText("-" + fmt.format(totalDiscount));
            discountBox.setVisible(true);
            discountBox.setManaged(true);
        } else {
            discountBox.setVisible(false);
            discountBox.setManaged(false);
        }

        // Format total without currency symbol for prominent display
        totalAmountLabel.setText(String.format("%.2f", currentTotal));

        // Update Checkout Button with modern formatting
        if (totalBtn != null) {
            totalBtn.setText("CHECKOUT\n" + fmt.format(currentTotal));
        }
    }

    private void removeSaleDiscount() {
        saleDiscount = BigDecimal.ZERO;
        saleDiscountReason = "";
        scheduleUpdateTotal();
        ToastNotification.showInfo("Discount removed", getSceneWindow());
    }

    private void clearCartSilently() {
        cartItems.clear();
        cartItemsByBarcode.clear();
        cartItemsWithQuantityListeners.clear();
        ageVerificationService.clearSession();
    }

    /**
     * Cancels the current receipt/transaction with confirmation
     */
    private void cancelReceipt() {
        if (cartItems.isEmpty()) {
            ToastNotification.showInfo("Cart is already empty", getScene().getWindow());
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Cancel Receipt");
        alert.setHeaderText("Cancel this transaction?");
        alert.setContentText("This will clear all items in the cart. This action cannot be undone.");
        DialogHelper.setAlertOwner(alert, getScene().getWindow());

        alert.showAndWait().ifPresent(buttonType -> {
            if (buttonType == ButtonType.OK) {
                // Log the cart cancellation before clearing
                try {
                    CartCancellationService cancellationService = CartCancellationService.getInstance();
                    String receiptNumberStr = receiptNumberLabel != null ? receiptNumberLabel.getText()
                            : String.format("%07d", currentReceiptNumber);
                    cancellationService.logCartCancellation(
                            new java.util.ArrayList<>(cartItems), // Create copy of cart items
                            currentTotal,
                            subtotal,
                            saleDiscount,
                            tax,
                            receiptNumberStr);
                } catch (Exception e) {
                    logger.error("Error logging cart cancellation", e);
                    // Continue with cancellation even if logging fails
                }

                clearCartSilently();
                ToastNotification.showSuccess("Receipt cancelled. Cart cleared.", getScene().getWindow());
            }
        });
    }

    // ==================== DIALOGS ====================

    private void showProductSearchDialog() {
        ProductSearchDialog dialog = new ProductSearchDialog(getScene().getWindow());
        dialog.showAndWait().ifPresent(product -> {
            if (product != null) {
                addProductToCart(product, pendingQuantityMultiplier);
            }
        });
    }

    /**
     * Shows the sale-level discount dialog
     */
    private void showSaleDiscountDialog() {
        if (cartItems.isEmpty()) {
            ToastNotification.showWarning("Add items to cart first", getScene().getWindow());
            return;
        }

        if (onNavigateToDiscount != null) {
            onNavigateToDiscount.accept(subtotal, result -> {
                if (result != null) {
                    saleDiscount = result.discountAmount;
                    saleDiscountReason = result.discountReason;
                    scheduleUpdateTotal();
                    ToastNotification.showSuccess(
                            "Discount applied: " + NumberFormat.getCurrencyInstance().format(saleDiscount),
                            getScene().getWindow());
                }
            });
        }
    }

    /**
     * Shows the customer lookup dialog
     */
    private void showCustomerLookupDialog() {
        CustomerLookupDialog dialog = new CustomerLookupDialog(getScene().getWindow());
        dialog.showAndWait().ifPresent(customer -> {
            if (customer != null) {
                currentCustomer = customer;
                updateCustomerDisplay();
                ToastNotification.showSuccess(
                        "Customer added: " + customer.getFullName(),
                        getScene().getWindow());
            }
        });
    }

    /**
     * Shows order note input dialog
     */
    private void showOrderNoteDialog() {
        TextInputDialog dialog = new TextInputDialog(orderNote);
        dialog.setTitle("Order Note");
        dialog.setHeaderText("Add a note to this order");
        dialog.setContentText("Note:");
        dialog.initOwner(getScene().getWindow());

        // Style the dialog
        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.setStyle("-fx-background-color: white; -fx-padding: 20;");
        dialogPane.getStylesheets().add(getClass().getResource("/styles/application.css").toExternalForm());

        dialog.showAndWait().ifPresent(note -> {
            orderNote = note.trim();
            if (!orderNote.isEmpty()) {
                ToastNotification.showSuccess("Note added to order", getScene().getWindow());
            } else {
                ToastNotification.showInfo("Note cleared", getScene().getWindow());
            }
        });
    }

    /**
     * Handle clock out from sales screen
     * Shows the clock-out dialog with shift summary
     */
    private void handleClockOut() {
        // Check if there are items in cart
        if (!cartItems.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Cannot Clock Out");
            alert.setHeaderText("Cart is not empty");
            alert.setContentText("Please complete or clear the current transaction before clocking out.");
            DialogHelper.setAlertOwner(alert, getScene().getWindow());
            alert.showAndWait();
            return;
        }

        // Get current employee's active shift
        UserAuthService authService = UserAuthService.getInstance();
        String currentUserId = authService.getCurrentPosUserId();
        EmployeeShiftService employeeShiftService = EmployeeShiftService.getInstance();

        EmployeeShift activeEmployeeShift = null;
        try {
            if (currentUserId != null) {
                activeEmployeeShift = employeeShiftService.getActiveShift(currentUserId);
            }
        } catch (Exception e) {
            logger.warn("Could not get active employee shift: {}", e.getMessage());
        }

        if (activeEmployeeShift == null) {
            ToastNotification.showWarning("No active shift found", getScene().getWindow());
            return;
        }

        // Show clock-out dialog
        ClockOutDialog clockOutDialog = new ClockOutDialog(activeEmployeeShift, getScene().getWindow());

        clockOutDialog.showAndWait().ifPresent(result -> {
            // User confirmed clock-out
            new Thread(() -> {
                try {
                    // Clock out the employee
                    employeeShiftService.clockOut(currentUserId, result.notes);
                    logger.info("Employee clocked out from sales screen");

                    javafx.application.Platform.runLater(() -> {
                        ToastNotification.showSuccess("Clocked out successfully!", getScene().getWindow());

                        // Trigger logout flow - navigate back to login
                        // Find the MainWindow and trigger logout
                        if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage stage) {
                            // Logout and navigate to login screen
                            authService.logout();

                            javafx.geometry.Rectangle2D screenBounds = javafx.stage.Screen.getPrimary()
                                    .getVisualBounds();
                            LoginScreen loginScreen = new LoginScreen(stage);
                            javafx.scene.Scene loginScene = new javafx.scene.Scene(loginScreen, screenBounds.getWidth(),
                                    screenBounds.getHeight());

                            try {
                                loginScene.getStylesheets().add(
                                        getClass().getResource("/styles/application.css").toExternalForm());
                            } catch (Exception e) {
                                logger.warn("Stylesheet not found", e);
                            }

                            stage.setScene(loginScene);
                            stage.setTitle("Pasal POS 2 - Login");
                            stage.setMaximized(true);
                            stage.show();
                            stage.toFront();
                            javafx.application.Platform.runLater(() -> {
                                stage.setFullScreen(true);
                            });
                        }
                    });
                } catch (Exception e) {
                    logger.error("Failed to clock out: {}", e.getMessage());
                    javafx.application.Platform.runLater(() -> {
                        ToastNotification.showError("Failed to clock out: " + e.getMessage(), getScene().getWindow());
                    });
                }
            }).start();
        });
    }

    private void updateCustomerDisplay() {
        if (currentCustomer != null) {
            customerInfoLabel.setText(
                    "\uD83D\uDC64 " + currentCustomer.getFullName() + " | " +
                            currentCustomer.getTierDisplayName() + " | " +
                            String.format("%.0f pts", currentCustomer.getLoyaltyPoints()));
            customerInfoLabel.setVisible(true);
            customerInfoLabel.setManaged(true);
        } else {
            customerInfoLabel.setVisible(false);
            customerInfoLabel.setManaged(false);
        }
    }

    private void showReturnDialog() {
        if (onNavigateToReturn != null) {
            onNavigateToReturn.run();
        } else {
            ToastNotification.showWarning("Return navigation is not configured.", getScene().getWindow());
        }
    }

    /**
     * Show the Vendor Payout dialog for calculating and processing vendor payments.
     */
    private void showVendorPayoutDialog() {
        if (onNavigateToVendorPayout != null) {
            onNavigateToVendorPayout.run();
        } else {
            ToastNotification.showWarning("Vendor Payout navigation is not configured.", getScene().getWindow());
        }
    }

    private void handleNoSale() {
        // Permission check
        if (!rbacService.hasPermission(RoleBasedAccessService.PERMISSION_NO_SALE)) {
            ToastNotification.showWarning(
                    "You do not have permission to open the cash drawer without a sale. Manager or Admin role required.",
                    getScene().getWindow());
            return;
        }

        hardwareManager.openCashDrawer().thenAccept(success -> {
            javafx.application.Platform.runLater(() -> {
                if (success) {
                    ToastNotification.showSuccess("Cash drawer opened", getScene().getWindow());

                    // Record "No Sale" operation in the database for EOD report tracking
                    try {
                        ShiftService shiftService = ShiftService.getInstance();
                        ShiftResponse.ShiftData activeShift = shiftService.getActiveShift();
                        if (activeShift != null) {
                            UserAuthService authService = UserAuthService.getInstance();
                            String userId = authService.getCurrentPosUserId();
                            String userName = authService.getCurrentUserName();
                            shiftService.recordNoSale(activeShift.id, userId != null ? userId : "Unknown", userName != null ? userName : (activeShift.cashierName != null ? activeShift.cashierName : "Cashier"));
                            logger.info("No Sale operation recorded for shift {}", activeShift.id);
                        } else {
                            logger.warn("Could not record No Sale check: No active shift found");
                        }
                    } catch (Exception e) {
                        logger.error("Error recording No Sale operation", e);
                    }
                } else {
                    ToastNotification.showWarning("Failed to open cash drawer", getScene().getWindow());
                }
            });
        });
    }

    private void holdSale() {
        if (cartItems.isEmpty()) {
            ToastNotification.showWarning("Cart is empty", getScene().getWindow());
            return;
        }

        BigDecimal subtotal = getSubtotal();
        BigDecimal discount = getTotalDiscount();
        String customerName = currentCustomer != null ? currentCustomer.getFullName() : "";

        String holdId = salesService.holdSale(
                cartItems.stream().toList(),
                subtotal,
                discount,
                saleDiscount,
                saleDiscountReason,
                tax,
                currentTotal,
                selectedPaymentMethod,
                customerName,
                "");

        if (holdId != null) {
            clearCartSilently();
            ToastNotification.showSuccess("Sale held: " + holdId, getScene().getWindow());
        } else {
            ToastNotification.showError("Failed to hold sale", getScene().getWindow());
        }
    }

    private void recallSale() {
        if (!cartItems.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Recall Sale");
            alert.setHeaderText("Current cart is not empty");
            alert.setContentText("Recalling a sale will clear the current items. Continue?");
            DialogHelper.setAlertOwner(alert, getScene().getWindow());

            if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
        }

        if (onNavigateToRecallSales == null) {
            ToastNotification.showWarning("Recall sales screen is not available.", getSceneWindow());
            return;
        }
        onNavigateToRecallSales.run();
    }

    // ==================== PAYMENT NAVIGATION ====================

    public void setOnNavigateToDiscount(java.util.function.BiConsumer<BigDecimal, java.util.function.Consumer<DiscountScreen.DiscountResult>> callback) {
        this.onNavigateToDiscount = callback;
    }

    public void setOnNavigateToPayment(Runnable callback) {
        this.onNavigateToPayment = callback;
    }

    public void setOnNavigateToCashDrop(Runnable callback) {
        this.onNavigateToCashDrop = callback;
    }

    public void setOnNavigateToCloseShift(Runnable callback) {
        this.onNavigateToCloseShift = callback;
    }

    public void setOnNavigateToEndDay(Runnable callback) {
        this.onNavigateToEndDay = callback;
    }

    public void setOnNavigateToTimesheet(Runnable callback) {
        this.onNavigateToTimesheet = callback;
    }

    public void setOnNavigateToRecallSales(Runnable callback) {
        this.onNavigateToRecallSales = callback;
    }

    public boolean recallHeldSale(String holdId, javafx.stage.Window ownerWindow) {
        SalesService.HeldSaleData saleData = salesService.recallSale(holdId);
        if (saleData == null) {
            ToastNotification.showError("Failed to recall sale", ownerWindow);
            return false;
        }

        clearCartSilently();
        salesService.deleteHeldSale(holdId);

        cartItems.addAll(saleData.items);
        for (SaleItem item : saleData.items) {
            registerCartItemListeners(item);
            checkAndApplyMultiPackDiscount(item);
        }

        saleDiscount = saleData.saleDiscount != null ? saleData.saleDiscount : BigDecimal.ZERO;
        saleDiscountReason = saleData.saleDiscountReason;
        selectedPaymentMethod = saleData.paymentMethod != null ? saleData.paymentMethod : "CASH";
        cartItemsByBarcode.clear();
        for (SaleItem item : saleData.items) {
            String barcode = item.getProduct().getBarcode();
            if (barcode != null && !item.getProductName().contains("(Open)")) {
                cartItemsByBarcode.put(barcode, item);
            }
        }
        scheduleUpdateTotal();
        scheduleCartRefresh();
        ToastNotification.showSuccess("Recalled sale", ownerWindow);
        return true;
    }

    private void navigateToPaymentScreen() {
        if (cartItems.isEmpty()) {
            ToastNotification.showWarning("Cart is empty", getScene().getWindow());
            return;
        }

        // Validate active shift before allowing payment
        if (!com.pos.service.ShiftService.getInstance().hasActiveShift()) {
            ToastNotification.showError("No active shift. Please start a shift to process transactions.",
                    getScene().getWindow());
            return;
        }

        // Check if any items in cart require age verification
        int maxRequiredAge = getMaxRequiredAgeInCart();
        if (maxRequiredAge > 0 && !ageVerificationService.hasVerificationForAge(maxRequiredAge)) {
            // Show age verification dialog before proceeding to payment
            showAgeVerificationForCheckout(maxRequiredAge, () -> {
                // Age verification passed, proceed to payment
                proceedToPayment();
            });
        } else {
            // No age verification needed or already verified
            proceedToPayment();
        }
    }

    /**
     * Get the maximum required age from all items in the cart
     * 
     * @return Maximum required age (e.g., 21) or 0 if no age-restricted items
     */
    private int getMaxRequiredAgeInCart() {
        int maxAge = 0;
        for (SaleItem item : cartItems) {
            int requiredAge = ageVerificationService.getRequiredAge(item.getProduct());
            if (requiredAge > maxAge) {
                maxAge = requiredAge;
            }
        }
        return maxAge;
    }

    /**
     * Show age verification dialog for checkout
     * 
     * @param requiredAge Minimum age required
     * @param onSuccess   Callback to run if verification succeeds
     */
    private void showAgeVerificationForCheckout(int requiredAge, Runnable onSuccess) {
        // Find the first age-restricted product name for display
        String productName = "Age-Restricted Items";
        String departmentId = null;
        for (SaleItem item : cartItems) {
            int itemAge = ageVerificationService.getRequiredAge(item.getProduct());
            if (itemAge == requiredAge) {
                productName = item.getProduct().getName();
                departmentId = item.getProduct().getDepartmentId();
                break;
            }
        }

        logger.info("Age verification required at checkout: {} (age {}+)", productName, requiredAge);

        AgeVerificationDialog dialog = new AgeVerificationDialog(
                productName + " (and other " + requiredAge + "+ items)",
                departmentId,
                requiredAge);
        dialog.initOwner(getScene().getWindow());
        dialog.showAndWait().ifPresent(result -> {
            if (result.approved) {
                logger.info("Age verification approved at checkout");
                ToastNotification.showSuccess("Age verified - Proceeding to payment", getScene().getWindow());
                if (onSuccess != null) {
                    onSuccess.run();
                }
            } else {
                logger.info("Age verification denied at checkout");
                ToastNotification.showWarning("Sale cannot proceed - Customer does not meet age requirement",
                        getScene().getWindow());
            }
        });
    }

    /**
     * Proceed to payment after any required age verification
     */
    private void proceedToPayment() {
        if (cashOnlyMode) {
            // Force cash payment
            processCashPayment();
        } else if (onNavigateToPayment != null) {
            onNavigateToPayment.run();
        }
    }

    private void processCashPayment() {
        try {
            CashPaymentDialog dialog = new CashPaymentDialog(currentTotal);
            CashPaymentDialog.CashPaymentResult result = dialog.showAndWait().orElse(null);

            if (result != null) {
                processPayment("CASH", result.amountReceived, result.change);
            }
        } catch (Exception e) {
            logger.error("Error in cash payment", e);
        }
    }

    private void processPayment(String method, BigDecimal amountReceived, BigDecimal change) {
        if (!com.pos.service.ShiftService.getInstance().hasActiveShift()) {
            ToastNotification.showError("No active shift. Please start a shift to process transactions.",
                    getScene().getWindow());
            return;
        }

        selectedPaymentMethod = method;
        UserAuthService authService = UserAuthService.getInstance();
        final String cashierName = authService.getCurrentUserName();
        final List<SaleItem> itemsSnapshot = new ArrayList<>(cartItems);
        final BigDecimal totalSnapshot = currentTotal;
        final BigDecimal taxSnapshot = tax;
        final BigDecimal discountSnapshot = saleDiscount;
        final Customer customerSnapshot = currentCustomer;

        new Thread(() -> {
            boolean success = false;
            IllegalArgumentException validationError = null;
            String receiptText = null;
            try {
                success = salesService.processSale(
                        itemsSnapshot,
                        method,
                        totalSnapshot,
                        cashierName,
                        discountSnapshot,
                        taxSnapshot,
                        null,
                        amountReceived,
                        change,
                        false);
                if (success) {
                    receiptText = generateReceipt(method, amountReceived, change);
                }
            } catch (IllegalArgumentException e) {
                validationError = e;
            } catch (Exception e) {
                logger.error("Error processing payment", e);
            }

            final boolean paymentSuccess = success;
            final String resultReceipt = receiptText;
            final IllegalArgumentException resultValidation = validationError;

            Platform.runLater(() -> {
                if (resultValidation != null) {
                    ToastNotification.showError(resultValidation.getMessage(), getScene().getWindow());
                    return;
                }
                if (paymentSuccess) {
                    if (customerSnapshot != null) {
                        customerService.recordSale(customerSnapshot.getId(), totalSnapshot);
                    }
                    if (resultReceipt != null) {
                        ReceiptPrintHelper.printReceiptConditionally(resultReceipt, null, getScene().getWindow(),
                                settingsService);
                    }
                    if ("CASH".equals(method)) {
                        hardwareManager.openCashDrawer();
                    }
                    clearCartSilently();
                    ToastNotification.showSuccess("Transaction completed!", getScene().getWindow());
                } else {
                    ToastNotification.showError("Failed to process transaction.", getScene().getWindow());
                }
            });
        }, "SalesScreenPayment").start();
    }

    private String generateReceipt(String paymentMethod, BigDecimal amountReceived, BigDecimal change) {
        StringBuilder sb = new StringBuilder();

        String storeName = settingsService.getStoreName();
        String storeAddress = settingsService.getStoreAddress();
        String storePhone = settingsService.getStorePhone();
        String header = settingsService.getReceiptHeader();
        String footer = settingsService.getReceiptFooter();

        sb.append(centerText(storeName, 42)).append("\n");
        if (!storeAddress.isEmpty())
            sb.append(centerText(storeAddress, 42)).append("\n");
        if (!storePhone.isEmpty())
            sb.append(centerText(storePhone, 42)).append("\n");
        sb.append("\n");

        sb.append(centerText(header, 42)).append("\n");
        sb.append("-".repeat(42)).append("\n");

        sb.append("Receipt: ").append(String.format("%07d", currentReceiptNumber)).append("\n");
        sb.append("Date: ").append(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"))).append("\n");
        UserAuthService authService = UserAuthService.getInstance();
        String cashierName = authService.getCurrentUserName();
        sb.append("Cashier: ").append(cashierName != null ? cashierName : "N/A").append("\n");

        if (currentCustomer != null) {
            sb.append("Customer: ").append(currentCustomer.getFullName()).append("\n");
        }
        sb.append("\n");

        // For card payments, show Card Price and Cash Price side by side (both unit prices)
        // so the customer can compare. Cash/EBT keeps the simple unit-price + line-total layout.
        boolean showDualPricing = !("CASH".equals(paymentMethod) || "EBT".equals(paymentMethod));
        if (showDualPricing) {
            sb.append(String.format("%-16s %3s %10s %10s\n", "Item", "Qty", "Card Price", "Cash Price"));
        } else {
            sb.append(String.format("%-16s %3s %10s %10s\n", "Item", "Qty", "Price", "Total"));
        }
        sb.append("-".repeat(42)).append("\n");
        for (SaleItem item : cartItems) {
            String name = item.getProductName();
            if (name.length() > 16) {
                name = name.substring(0, 13) + "...";
            }
            BigDecimal itemDiscount = item.getTotalDiscount();

            if (showDualPricing) {
                BigDecimal cardUnit = item.getPrice("CARD");
                BigDecimal cashUnit = item.getPrice("CASH");
                sb.append(String.format("%-16s %3d %10.2f %10.2f\n",
                        name, item.getQuantity(), cardUnit, cashUnit));
            } else {
                BigDecimal itemPrice = item.getPrice(paymentMethod);
                BigDecimal itemTotal = item.getBaseTotal();
                sb.append(String.format("%-16s %3d %10.2f %10.2f\n",
                        name, item.getQuantity(), itemPrice, itemTotal));
            }

            if (itemDiscount.setScale(2, java.math.RoundingMode.HALF_UP).compareTo(BigDecimal.ZERO) > 0) {
                sb.append(String.format("  Discount: -%9.2f (%s)\n",
                        itemDiscount, item.getDiscountReason()));
            }
        }
        sb.append("-".repeat(42)).append("\n");

        BigDecimal subtotalBeforeDiscounts = cartItems.stream()
                .map(SaleItem::getBaseTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        sb.append(String.format("%-30s %11.2f\n", "Subtotal:", subtotalBeforeDiscounts));

        BigDecimal totalDiscount = getTotalDiscount();
        if (totalDiscount.setScale(2, java.math.RoundingMode.HALF_UP).compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("%-30s %11.2f\n", "Discount:", totalDiscount.negate()));
            if (!saleDiscountReason.isEmpty()) {
                sb.append(String.format("  %s\n", saleDiscountReason));
            }
        }

        sb.append(String.format("%-30s %11.2f\n", "Tax:", tax));
        sb.append("-".repeat(42)).append("\n");
        sb.append(String.format("%-30s %11.2f\n", "TOTAL:", currentTotal));
        sb.append("\n");

        sb.append("Payment: ").append(paymentMethod).append("\n");
        if ("CASH".equals(paymentMethod) && amountReceived != null && change != null) {
            sb.append(String.format("%-30s %11.2f\n", "Amount Received:", amountReceived));
            sb.append(String.format("%-30s %11.2f\n", "Change:", change));
        }
        sb.append("\n");

        if (!orderNote.isEmpty()) {
            sb.append("Note: ").append(orderNote).append("\n\n");
        }

        sb.append(centerText(footer, 42)).append("\n");
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

    private BigDecimal getTotalDiscount() {
        BigDecimal lineItemDiscounts = cartItems.stream()
                .map(SaleItem::getTotalDiscount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return lineItemDiscounts.add(saleDiscount);
    }

    // ==================== PUBLIC API ====================

    public void refresh() {
        currentPage = 0;
        clearInput();
        loadDepartments();
        showDepartments();
        
        // Refresh cart display and totals to ensure updates from other screens are reflected
        scheduleUpdateTotal();
        scheduleCartRefresh();

        // Restore scanner callback when returning to this screen
        hardwareManager.setScanCallback(this::handleBarcodeScan);

        // Ensure focus returns to the screen for barcode scanning
        Platform.runLater(() -> this.requestFocus());

        logger.info("Sales screen refreshed and scanner callback restored");
    }

    public void startNewSale() {
        clearCartSilently();
    }

    public ObservableList<SaleItem> getCartItems() {
        return cartItems;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getTax() {
        return tax;
    }

    public BigDecimal getCurrentTotal() {
        return currentTotal;
    }

    public BigDecimal getSaleDiscount() {
        return saleDiscount;
    }

    public PaymentScreen createPaymentScreen(Runnable onComplete, Runnable onCancel, Runnable onNavigateToSplit) {
        return new PaymentScreen(
                hardwareManager,
                cartItems,
                subtotal,
                tax,
                currentTotal,
                saleDiscount,
                saleDiscountReason,
                currentCustomer,
                orderNote,
                selectedPaymentMethod,
                onComplete,
                onCancel,
                onNavigateToSplit);
    }

    /**
     * Refresh an existing (cached) PaymentScreen with the current sale's state
     * instead of constructing a new one. Keeps the payment page snappy on repeat
     * checkouts.
     */
    public void refreshPaymentScreen(PaymentScreen paymentScreen) {
        paymentScreen.prepareForSale(
                subtotal,
                tax,
                currentTotal,
                saleDiscount,
                saleDiscountReason,
                currentCustomer,
                orderNote,
                selectedPaymentMethod);
    }

    public String getOrderNote() {
        return orderNote;
    }

    public String getSelectedPaymentMethod() {
        return selectedPaymentMethod;
    }

    public HardwareManager getHardwareManager() {
        return hardwareManager;
    }

    public String getSaleDiscountReason() {
        return saleDiscountReason;
    }

    public Customer getCurrentCustomer() {
        return currentCustomer;
    }

    /**
     * Show cash drop dialog to remove excess cash from drawer during active shift
     * This is an essential feature for managing cash in the register
     */
    private void showCashDropDialog() {
        ShiftService shiftService = ShiftService.getInstance();
        ShiftResponse.ShiftData currentShift;

        try {
            currentShift = shiftService.getActiveShift();
        } catch (Exception e) {
            logger.error("Error getting active shift for cash drop", e);
            ToastNotification.showError("Error checking shift status: " + e.getMessage(), getScene().getWindow());
            return;
        }

        if (currentShift == null || !"ACTIVE".equals(currentShift.status)) {
            ToastNotification.showWarning("No active shift. Please start a shift before performing cash drops.",
                    getScene().getWindow());
            return;
        }

        Dialog<CashDropData> dialog = new Dialog<>();
        dialog.setTitle("Cash Drop");
        dialog.setHeaderText("Remove cash from register drawer");

        javafx.stage.Modality modality = javafx.stage.Modality.APPLICATION_MODAL;
        dialog.initModality(modality);
        DialogHelper.setDialogOwner(dialog, getScene() != null ? getScene().getWindow() : null);

        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(15);
        grid.setPadding(new Insets(20));

        // Add column constraints to prevent label truncation
        javafx.scene.layout.ColumnConstraints col1 = new javafx.scene.layout.ColumnConstraints();
        col1.setMinWidth(160);
        col1.setPrefWidth(180);
        javafx.scene.layout.ColumnConstraints col2 = new javafx.scene.layout.ColumnConstraints();
        col2.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        grid.getColumnConstraints().addAll(col1, col2);

        // Calculate and display available cash
        BigDecimal availableCash = BigDecimal.ZERO;
        try {
            availableCash = shiftService.calculateAvailableCash(currentShift.id);
        } catch (Exception e) {
            logger.error("Error calculating available cash", e);
        }

        final BigDecimal finalAvailableCash = availableCash;
        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

        // Create labels with consistent styling
        String labelStyle = "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;";
        Label availableCashMainLabel = new Label("Available Cash:");
        availableCashMainLabel.setStyle(labelStyle);
        Label dropAmountLabel = new Label("Drop Amount ($):");
        dropAmountLabel.setStyle(labelStyle);
        Label reasonNoteLabel = new Label("Reason/Note:");
        reasonNoteLabel.setStyle(labelStyle);

        Label availableCashValueLabel = new Label(currencyFormat.format(availableCash));
        availableCashValueLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 18px; -fx-text-fill: #2a5298;");

        com.pos.ui.components.TouchTextField amountField = com.pos.ui.components.TouchScreenComponents
                .createTouchOnlyNumericField("0.00");
        amountField.setPrefWidth(250);
        amountField.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(amountField, javafx.scene.layout.Priority.ALWAYS);

        // Add validation label for real-time feedback
        Label validationLabel = new Label();
        validationLabel.setStyle("-fx-text-fill: #d32f2f; -fx-font-size: 12px;");
        validationLabel.setVisible(false);
        validationLabel.setWrapText(true);
        validationLabel.setMaxWidth(Double.MAX_VALUE);

        com.pos.ui.components.TouchTextArea noteArea = com.pos.ui.components.TouchScreenComponents
                .createTouchOnlyTextArea("Reason for cash drop (e.g., Excess cash removal)");
        noteArea.setPrefRowCount(3);
        noteArea.setPrefWidth(250);
        noteArea.setMaxWidth(Double.MAX_VALUE);
        noteArea.setWrapText(true);
        GridPane.setHgrow(noteArea, javafx.scene.layout.Priority.ALWAYS);

        grid.add(availableCashMainLabel, 0, 0);
        grid.add(availableCashValueLabel, 1, 0);
        grid.add(dropAmountLabel, 0, 1);
        grid.add(amountField, 1, 1);
        grid.add(validationLabel, 0, 2, 2, 1);
        grid.add(reasonNoteLabel, 0, 3);
        grid.add(noteArea, 1, 3);

        // Add real-time validation
        amountField.textProperty().addListener((observable, oldValue, newValue) -> {
            try {
                if (newValue != null && !newValue.trim().isEmpty()) {
                    BigDecimal amount = new BigDecimal(newValue.trim());
                    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                        validationLabel.setText("Amount must be greater than zero");
                        validationLabel.setVisible(true);
                    } else if (amount.compareTo(finalAvailableCash) > 0) {
                        validationLabel.setText(String.format(
                                "This drop exceeds current drawer cash by %s and will appear in short/over reporting.",
                                currencyFormat.format(amount.subtract(finalAvailableCash))));
                        validationLabel.setVisible(true);
                    } else {
                        validationLabel.setVisible(false);
                    }
                } else {
                    validationLabel.setVisible(false);
                }
            } catch (NumberFormatException e) {
                validationLabel.setText("Invalid amount format");
                validationLabel.setVisible(true);
            }
        });

        dialog.getDialogPane().setContent(grid);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(dialog, 0.5, 0.55);

        ButtonType dropButtonType = new ButtonType("Drop Cash", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(dropButtonType, ButtonType.CANCEL);

        final ShiftResponse.ShiftData shiftForDrop = currentShift;

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == dropButtonType) {
                try {
                    String amountText = amountField.getText().trim();
                    if (amountText.isEmpty()) {
                        ToastNotification.showWarning("Please enter an amount", getScene().getWindow());
                        return null;
                    }

                    BigDecimal amount = new BigDecimal(amountText);

                    // Validate amount is positive
                    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                        ToastNotification.showWarning("Amount must be greater than zero", getScene().getWindow());
                        return null;
                    }

                    return new CashDropData(amount, noteArea.getText());
                } catch (NumberFormatException e) {
                    ToastNotification.showError("Invalid amount format. Please enter a valid number.",
                            getScene().getWindow());
                    return null;
                } catch (Exception e) {
                    ToastNotification.showError("Invalid amount: " + e.getMessage(), getScene().getWindow());
                    return null;
                }
            }
            return null;
        });

        dialog.showAndWait().ifPresent(data -> {
            new Thread(() -> {
                try {
                    // Get current user name for the performed by field
                    String performedById = "Unknown";
                    String performedByName = "Unknown";
                    try {
                        UserAuthService authService = UserAuthService.getInstance();
                        performedById = authService.getCurrentPosUserId();
                        performedByName = authService.getCurrentUserName();
                    } catch (Exception e) {
                        logger.debug("Could not get current user info for cash drop", e);
                    }

                    shiftService.recordCashOperation(
                            shiftForDrop.id,
                            "DROP",
                            data.amount,
                            data.note,
                            performedById,
                            performedByName);

                    javafx.application.Platform.runLater(() -> {
                        ToastNotification.showSuccess(
                                String.format("Cash drop of %s recorded successfully!",
                                        currencyFormat.format(data.amount)),
                                getScene().getWindow());
                    });
                } catch (IllegalArgumentException e) {
                    logger.error("Cash drop validation failed", e);
                    javafx.application.Platform.runLater(() -> {
                        ToastNotification.showError("Cash drop failed: " + e.getMessage(), getScene().getWindow());
                    });
                } catch (Exception e) {
                    logger.error("Failed to record cash drop", e);
                    javafx.application.Platform.runLater(() -> {
                        ToastNotification.showError("Failed to record cash drop: " + e.getMessage(),
                                getScene().getWindow());
                    });
                }
            }).start();
        });
    }

    /**
     * Data class for cash drop dialog
     */
    private static class CashDropData {
        BigDecimal amount;
        String note;

        CashDropData(BigDecimal amount, String note) {
            this.amount = amount;
            this.note = note;
        }
    }
}

