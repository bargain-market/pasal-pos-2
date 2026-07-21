package com.pos.ui;

import com.pos.config.ConfigManager;
import com.pos.service.DeviceRegistrationService;
import com.pos.service.OfflineSyncService;
import com.pos.util.DialogHelper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Help Center screen providing user documentation, FAQs, troubleshooting,
 * keyboard shortcuts, and support contact information.
 */
public class HelpCenterScreen extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(HelpCenterScreen.class);
    private Runnable onBackToSales;
    private ConfigManager configManager;
    private VBox contentArea;
    private TextField searchField;

    public HelpCenterScreen() {
        this(null);
    }

    public HelpCenterScreen(Runnable onBackToSales) {
        this.onBackToSales = onBackToSales;
        this.configManager = ConfigManager.getInstance();
        initializeUI();
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #f5f7fa;");

        // Top section - Title, Back button, and Search
        VBox topSection = createTopSection();
        setTop(topSection);

        // Left side - Navigation menu
        VBox navigationMenu = createNavigationMenu();
        setLeft(navigationMenu);

        // Center - Content area
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        contentArea = new VBox(20);
        contentArea.setPadding(new Insets(20));
        contentArea.setStyle("-fx-background-color: white;");
        scrollPane.setContent(contentArea);
        setCenter(scrollPane);

        // Show Quick Start by default
        showQuickStart();
    }

    private VBox createTopSection() {
        VBox topSection = new VBox(15);
        topSection.setPadding(new Insets(20));
        topSection.setStyle(
                "-fx-background-color: white; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 5, 0, 0, 2);");

        HBox titleRow = new HBox(15);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        // Back to Sales button
        if (onBackToSales != null) {
            Button backToSalesButton = new Button("\u2190 Back to Sales");
            backToSalesButton.setStyle(
                    "-fx-background-color: #2196F3; " +
                            "-fx-text-fill: white; " +
                            "-fx-font-size: 14px; " +
                            "-fx-font-weight: bold; " +
                            "-fx-padding: 8 20; " +
                            "-fx-background-radius: 5; " +
                            "-fx-cursor: hand;");
            backToSalesButton.setOnAction(e -> onBackToSales.run());
            titleRow.getChildren().add(backToSalesButton);
        }

        Label titleLabel = new Label("Help Center");
        titleLabel.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: #333;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Search field
        searchField = new TextField();
        searchField.setPromptText("Search help topics...");
        // searchField.setPrefWidth(300);
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setStyle("-fx-font-size: 14px; -fx-padding: 8 15; -fx-background-radius: 20;");
        searchField.setOnAction(e -> performSearch(searchField.getText()));

        Button searchButton = new Button("Search");
        searchButton.setStyle(
                "-fx-background-color: #4CAF50; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 14px; " +
                        "-fx-padding: 8 20; " +
                        "-fx-background-radius: 20; " +
                        "-fx-cursor: hand;");
        searchButton.setOnAction(e -> performSearch(searchField.getText()));

        titleRow.getChildren().addAll(titleLabel, spacer, searchField, searchButton);
        topSection.getChildren().add(titleRow);

        return topSection;
    }

    private VBox createNavigationMenu() {
        VBox menu = new VBox(5);
        menu.setPadding(new Insets(20, 10, 20, 10));
        menu.setStyle("-fx-background-color: #f0f4f8;");
        menu.setPrefWidth(220);

        Label menuTitle = new Label("Topics");
        menuTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #555;");
        menuTitle.setPadding(new Insets(0, 0, 10, 10));

        menu.getChildren().add(menuTitle);
        menu.getChildren().addAll(
                createNavButton("🚀 Quick Start", this::showQuickStart),
                createNavButton("📖 User Manual", this::showUserManual),
                createNavButton("💳 Sales & Payments", this::showSalesHelp),
                createNavButton("📦 Inventory", this::showInventoryHelp),
                createNavButton("📊 Reports", this::showReportsHelp),
                createNavButton("⏰ Shifts & Cash", this::showShiftsHelp),
                createNavButton("❓ FAQ", this::showFAQ),
                createNavButton("🔧 Troubleshooting", this::showTroubleshooting),
                createNavButton("💻 System Info", this::showSystemInfo),
                createNavButton("📞 Contact Support", this::showContactSupport));

        return menu;
    }

    private Button createNavButton(String text, Runnable action) {
        Button button = new Button(text);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setStyle(
                "-fx-background-color: transparent; " +
                        "-fx-text-fill: #333; " +
                        "-fx-font-size: 14px; " +
                        "-fx-padding: 12 15; " +
                        "-fx-cursor: hand;");
        button.setOnMouseEntered(e -> button.setStyle(
                "-fx-background-color: #e3f2fd; " +
                        "-fx-text-fill: #1976D2; " +
                        "-fx-font-size: 14px; " +
                        "-fx-padding: 12 15; " +
                        "-fx-cursor: hand; " +
                        "-fx-background-radius: 5;"));
        button.setOnMouseExited(e -> button.setStyle(
                "-fx-background-color: transparent; " +
                        "-fx-text-fill: #333; " +
                        "-fx-font-size: 14px; " +
                        "-fx-padding: 12 15; " +
                        "-fx-cursor: hand;"));
        button.setOnAction(e -> action.run());
        return button;
    }

    // ========== Quick Start Section ==========
    private void showQuickStart() {
        contentArea.getChildren().clear();

        Label sectionTitle = createSectionTitle("🚀 Quick Start Guide");
        contentArea.getChildren().add(sectionTitle);

        // Welcome message
        VBox welcomeBox = createInfoBox("Welcome to Pasal POS 2",
                "This Quick Start Guide will help you get up and running with the Point of Sale system. " +
                        "Follow these steps to start processing sales efficiently.");

        // Step-by-step guide
        VBox stepsBox = new VBox(15);
        stepsBox.setPadding(new Insets(15));
        stepsBox.setStyle("-fx-background-color: #f5f5f5; -fx-background-radius: 8;");

        stepsBox.getChildren().addAll(
                createStep("1", "Login",
                        "Enter your employee credentials to access the system. Your manager will provide your login details."),
                createStep("2", "Start a Shift",
                        "Open a cash session before processing sales. Go to Shift menu and click 'Start Shift'. Enter your opening cash amount."),
                createStep("3", "Process a Sale",
                        "Use the Sales Screen to add products by scanning barcodes, searching, or selecting from categories."),
                createStep("4", "Accept Payment",
                        "Click 'Pay' to open the payment screen. Choose payment method (Cash, Card, or Split)."),
                createStep("5", "Print Receipt",
                        "The receipt will print automatically after payment. You can also email or reprint receipts."),
                createStep("6", "End Your Shift",
                        "When finished, go to Shift menu and 'End Shift'. Count your cash and enter the closing amount."));

        // Tips box
        VBox tipsBox = createInfoBox("💡 Pro Tips",
                "• Use touch gestures for faster checkout\n" +
                        "• You can search products by name, SKU, or barcode\n" +
                        "• Add frequently used items to Favorites for quick access\n" +
                        "• Use the 'Hold' feature to save a transaction and serve another customer\n" +
                        "• Check Reports daily to track your sales performance");

        contentArea.getChildren().addAll(welcomeBox, stepsBox, tipsBox);
    }

    // ========== User Manual Section ==========
    private void showUserManual() {
        contentArea.getChildren().clear();

        Label sectionTitle = createSectionTitle("📖 User Manual");
        contentArea.getChildren().add(sectionTitle);

        VBox overviewBox = createInfoBox("System Overview",
                "The Pasal POS 2 is designed for efficient retail operations. It includes:\n\n" +
                        "• Sales Processing - Handle transactions, returns, and refunds\n" +
                        "• Inventory Management - Track stock levels and product information\n" +
                        "• Customer Management - Store customer data and purchase history\n" +
                        "• Reports - Generate sales and inventory reports\n" +
                        "• Shift Management - Track cash sessions and employee activity\n" +
                        "• Hardware Integration - Printers, scanners, and cash drawers");

        // Create expandable sections
        VBox sectionsBox = new VBox(10);

        TitledPane loginSection = createExpandableSection("Login & Authentication",
                "The login screen requires your employee credentials:\n\n" +
                        "1. Enter your username (usually your employee ID)\n" +
                        "2. Enter your password\n" +
                        "3. Click 'Login' or press Enter\n\n" +
                        "If you forget your password, contact your manager for a reset.\n\n" +
                        "Security Features:\n" +
                        "• Automatic logout after inactivity\n" +
                        "• Session tracking for audit purposes\n" +
                        "• Role-based access control");

        TitledPane navigationSection = createExpandableSection("Navigation",
                "The system uses a menu-based navigation:\n\n" +
                        "• File Menu - Logout and exit options\n" +
                        "• Sales Menu - New sale, sale history, void transactions\n" +
                        "• Inventory Menu - Stock management and product sync\n" +
                        "• Shift Menu - Cash session management\n" +
                        "• Reports Menu - Sales and inventory reports\n" +
                        "• Settings Menu - System configuration\n\n" +
                        "The status bar at the bottom shows current user, shift status, and sync status.");

        TitledPane offlineSection = createExpandableSection("Offline Mode",
                "The system can work offline when internet is unavailable:\n\n" +
                        "• Sales are saved locally and synced when online\n" +
                        "• Product data is cached for offline access\n" +
                        "• Pending operations show in the status bar\n" +
                        "• Automatic sync when connection is restored\n\n" +
                        "Note: Some features like real-time inventory updates require internet.");

        sectionsBox.getChildren().addAll(loginSection, navigationSection, offlineSection);
        contentArea.getChildren().addAll(overviewBox, sectionsBox);
    }

    // ========== Sales Help Section ==========
    private void showSalesHelp() {
        contentArea.getChildren().clear();

        Label sectionTitle = createSectionTitle("💳 Sales & Payments");
        contentArea.getChildren().add(sectionTitle);

        VBox salesProcessBox = createInfoBox("Processing a Sale",
                "1. Start a new sale from the Sales screen\n" +
                        "2. Add items using one of these methods:\n" +
                        "   • Scan barcode with scanner\n" +
                        "   • Type barcode/SKU and press Enter\n" +
                        "   • Search by product name\n" +
                        "   • Click product from category grid\n" +
                        "   • Select from Favorites\n" +
                        "3. Adjust quantities using +/- buttons or type directly\n" +
                        "4. Apply discounts if authorized\n" +
                        "5. Click 'Pay' when ready");

        VBox paymentBox = createInfoBox("Payment Methods",
                "Cash:\n" +
                        "• Enter amount tendered\n" +
                        "• System calculates change automatically\n" +
                        "• Cash drawer opens for payment\n\n" +
                        "Card:\n" +
                        "• Swipe, insert, or tap card\n" +
                        "• Wait for authorization\n" +
                        "• Get customer signature if required\n\n" +
                        "Split Payment:\n" +
                        "• Select 'Split' option\n" +
                        "• Enter amount for first payment method\n" +
                        "• Complete remaining balance with another method");

        VBox returnsBox = createInfoBox("Returns & Refunds",
                "To process a return:\n\n" +
                        "1. Go to Sales → Sale History & Lookup\n" +
                        "2. Find the original transaction by receipt number or date\n" +
                        "3. Select the transaction and click 'Return'\n" +
                        "4. Choose items to return\n" +
                        "5. Select refund method (original payment or store credit)\n" +
                        "6. Complete the refund\n\n" +
                        "Note: Returns may require manager approval based on store policy.");

        VBox voidBox = createInfoBox("Void Transaction",
                "To void a current transaction:\n" +
                        "• Click 'Clear' or 'Void' button\n" +
                        "• Confirm the void action\n\n" +
                        "To void a completed transaction:\n" +
                        "• Must be done within the same business day\n" +
                        "• Requires manager authorization\n" +
                        "• Go to Sale History, find transaction, and select 'Void'");

        contentArea.getChildren().addAll(salesProcessBox, paymentBox, returnsBox, voidBox);
    }

    // ========== Inventory Help Section ==========
    private void showInventoryHelp() {
        contentArea.getChildren().clear();

        Label sectionTitle = createSectionTitle("📦 Inventory Management");
        contentArea.getChildren().add(sectionTitle);

        VBox overviewBox = createInfoBox("Inventory Overview",
                "The inventory system helps you track and manage your products:\n\n" +
                        "• View current stock levels\n" +
                        "• Receive new inventory\n" +
                        "• Adjust stock counts\n" +
                        "• Track low stock alerts\n" +
                        "• Manage product information");

        VBox viewingBox = createInfoBox("Viewing Inventory",
                "Access inventory from the Inventory menu:\n\n" +
                        "1. Click 'Inventory Management' to see all products\n" +
                        "2. Use search to find specific items\n" +
                        "3. Filter by department or category\n" +
                        "4. Sort by name, quantity, or price\n" +
                        "5. View low stock items with the alert filter");

        VBox adjustmentsBox = createInfoBox("Stock Adjustments",
                "When to adjust stock:\n" +
                        "• Physical count differs from system\n" +
                        "• Damaged/expired products removed\n" +
                        "• Internal use or samples\n\n" +
                        "To make an adjustment:\n" +
                        "1. Find the product\n" +
                        "2. Click 'Adjust Stock'\n" +
                        "3. Enter new quantity or adjustment amount\n" +
                        "4. Select reason for adjustment\n" +
                        "5. Add notes if needed\n" +
                        "6. Confirm the adjustment");

        VBox syncBox = createInfoBox("Product Sync",
                "Products sync automatically with the backend server:\n\n" +
                        "• New products appear after sync\n" +
                        "• Price changes update automatically\n" +
                        "• Manual sync available from Inventory menu\n" +
                        "• Sync status shows in the status bar\n\n" +
                        "If products aren't showing correctly, try:\n" +
                        "1. Click 'Sync Products' from Inventory menu\n" +
                        "2. Check your internet connection\n" +
                        "3. Contact support if issues persist");

        contentArea.getChildren().addAll(overviewBox, viewingBox, adjustmentsBox, syncBox);
    }

    // ========== Reports Help Section ==========
    private void showReportsHelp() {
        contentArea.getChildren().clear();

        Label sectionTitle = createSectionTitle("📊 Reports");
        contentArea.getChildren().add(sectionTitle);

        VBox overviewBox = createInfoBox("Reports Overview",
                "Access reports from the Reports menu. Available reports:\n\n" +
                        "• Daily Sales Report - Summary of daily transactions\n" +
                        "• Sales by Hour - Identify peak hours\n" +
                        "• Sales by Category - Product performance\n" +
                        "• Payment Methods - Cash vs. card breakdown\n" +
                        "• Employee Performance - Sales by employee\n" +
                        "• Inventory Report - Stock levels and value");

        VBox dailyReportBox = createInfoBox("Daily Sales Report",
                "The daily report includes:\n\n" +
                        "• Total sales amount\n" +
                        "• Number of transactions\n" +
                        "• Average transaction value\n" +
                        "• Payment method breakdown\n" +
                        "• Returns and refunds\n" +
                        "• Tax collected\n\n" +
                        "Export options: Print, PDF, or Email");

        VBox generatingBox = createInfoBox("Generating Reports",
                "To generate a report:\n\n" +
                        "1. Go to Reports menu\n" +
                        "2. Select report type\n" +
                        "3. Choose date range\n" +
                        "4. Apply any filters (employee, department, etc.)\n" +
                        "5. Click 'Generate Report'\n" +
                        "6. View on screen or export");

        contentArea.getChildren().addAll(overviewBox, dailyReportBox, generatingBox);
    }

    // ========== Shifts Help Section ==========
    private void showShiftsHelp() {
        contentArea.getChildren().clear();

        Label sectionTitle = createSectionTitle("⏰ Shifts & Cash Management");
        contentArea.getChildren().add(sectionTitle);

        VBox overviewBox = createInfoBox("Shift Management Overview",
                "Shifts track cash sessions and employee activity:\n\n" +
                        "• Each employee should start/end their own shift\n" +
                        "• Cash is counted at start and end\n" +
                        "• All transactions are linked to the active shift\n" +
                        "• Reports can be generated per shift");

        VBox startShiftBox = createInfoBox("Starting a Shift",
                "To start a shift:\n\n" +
                        "1. Go to Shift menu → Shift Management\n" +
                        "2. Click 'Start Shift'\n" +
                        "3. Count the cash in the drawer\n" +
                        "4. Enter the opening cash amount\n" +
                        "5. Add any notes if needed\n" +
                        "6. Click 'Start'\n\n" +
                        "Note: You cannot start a new shift if one is already active.");

        VBox endShiftBox = createInfoBox("Ending a Shift",
                "To end a shift:\n\n" +
                        "1. Go to Shift menu → Shift Management\n" +
                        "2. Click 'End Shift'\n" +
                        "3. Count all cash in the drawer\n" +
                        "4. Enter the counted cash amount\n" +
                        "5. System shows expected vs. actual difference\n" +
                        "6. Add notes for any discrepancy\n" +
                        "7. Click 'End Shift'\n\n" +
                        "A shift report will be generated automatically.");

        VBox discrepancyBox = createInfoBox("Cash Discrepancies",
                "If your cash count doesn't match the expected amount:\n\n" +
                        "• Review all transactions for the shift\n" +
                        "• Check for unclosed cash drawer events\n" +
                        "• Look for any returns or voids\n" +
                        "• Document the discrepancy with notes\n" +
                        "• Report significant discrepancies to your manager\n\n" +
                        "Common causes:\n" +
                        "• Incorrect change given\n" +
                        "• Missed cash transactions\n" +
                        "• Counting errors");

        contentArea.getChildren().addAll(overviewBox, startShiftBox, endShiftBox, discrepancyBox);
    }

    // ========== FAQ Section ==========
    private void showFAQ() {
        contentArea.getChildren().clear();

        Label sectionTitle = createSectionTitle("❓ Frequently Asked Questions");
        contentArea.getChildren().add(sectionTitle);

        VBox faqBox = new VBox(10);

        faqBox.getChildren().addAll(
                createFAQItem("How do I apply a discount?",
                        "Click the 'Discount' button on the item or the total. Enter the discount percentage or amount. "
                                +
                                "Some discounts require manager authorization."),

                createFAQItem("How do I add a product that won't scan?",
                        "Try typing the barcode number manually. If that doesn't work, search for the product by name "
                                +
                                "or use the department button to enter it as a misc item."),

                createFAQItem("What if the card reader isn't working?",
                        "1. Check if the device is connected\n" +
                                "2. Try a different payment method\n" +
                                "3. Restart the card reader from Hardware Settings\n" +
                                "4. Contact support if the issue persists"),

                createFAQItem("How do I reprint a receipt?",
                        "Go to Sales → Sale History & Lookup. Find the transaction and click 'Reprint Receipt'."),

                createFAQItem("Can I cancel a transaction after payment?",
                        "Yes, but it requires a void or return. For same-day transactions, use Void. " +
                                "For previous days, process a return."),

                createFAQItem("How do I add a new customer?",
                        "Click 'Customer' on the sales screen, then 'Add New Customer'. " +
                                "Fill in the customer details and save."),

                createFAQItem("What does 'Offline Mode' mean?",
                        "The system can't reach the server. Sales will be saved locally and synced " +
                                "when the connection is restored. Some features may be limited."),

                createFAQItem("How do I change my password?",
                        "Contact your manager to reset your password. Self-service password changes " +
                                "are not available for security reasons."),

                createFAQItem("Why are some products not showing?",
                        "Products sync from the server. Try Inventory → Sync Products. " +
                                "If still missing, the product may not be assigned to this store."),

                createFAQItem("How do I open the cash drawer without a sale?",
                        "Go to Shift Management and click 'Open Drawer'. " +
                                "You may need to enter a reason for the drawer open event."));

        contentArea.getChildren().add(faqBox);
    }

    // ========== Troubleshooting Section ==========
    private void showTroubleshooting() {
        contentArea.getChildren().clear();

        Label sectionTitle = createSectionTitle("🔧 Troubleshooting");
        contentArea.getChildren().add(sectionTitle);

        VBox issuesBox = new VBox(15);

        issuesBox.getChildren().addAll(
                createTroubleshootItem("Printer not working",
                        "Symptoms: Receipts won't print, printer errors",
                        "Solutions:\n" +
                                "1. Check if printer is powered on and connected\n" +
                                "2. Check for paper jams or empty paper roll\n" +
                                "3. Go to Settings → Hardware Settings → Test Printer\n" +
                                "4. Restart the POS application\n" +
                                "5. Check printer cable connections\n" +
                                "6. Contact support if issue persists"),

                createTroubleshootItem("Barcode scanner not reading",
                        "Symptoms: Scanner beeps but product doesn't appear",
                        "Solutions:\n" +
                                "1. Clean the scanner lens with a soft cloth\n" +
                                "2. Check if barcode is damaged or unclear\n" +
                                "3. Try typing the barcode manually\n" +
                                "4. Check scanner connection in Hardware Settings\n" +
                                "5. Restart scanner by unplugging and reconnecting"),

                createTroubleshootItem("Cash drawer won't open",
                        "Symptoms: Drawer stays closed after cash payment",
                        "Solutions:\n" +
                                "1. Check if drawer is connected to printer\n" +
                                "2. Test drawer from Hardware Settings\n" +
                                "3. Use manual key to open (for emergencies)\n" +
                                "4. Check cable connections\n" +
                                "5. Verify drawer configuration in settings"),

                createTroubleshootItem("System running slowly",
                        "Symptoms: Lag, delays, slow response",
                        "Solutions:\n" +
                                "1. Close unnecessary applications\n" +
                                "2. Restart the POS application\n" +
                                "3. Check internet connection speed\n" +
                                "4. Restart the computer\n" +
                                "5. Contact IT support for performance check"),

                createTroubleshootItem("Cannot connect to server",
                        "Symptoms: Offline status, sync errors",
                        "Solutions:\n" +
                                "1. Check internet connection (try browser)\n" +
                                "2. Verify backend URL in Settings\n" +
                                "3. Check if server is under maintenance\n" +
                                "4. Sales will queue offline and sync later\n" +
                                "5. Contact support if connection doesn't restore"),

                createTroubleshootItem("Card payment declined",
                        "Symptoms: Payment fails, error messages",
                        "Solutions:\n" +
                                "1. Ask customer to try a different card\n" +
                                "2. Check internet connection\n" +
                                "3. Try processing again\n" +
                                "4. Use alternative payment method\n" +
                                "5. Contact payment processor if repeated failures"),

                createTroubleshootItem("Screen frozen / Not responding",
                        "Symptoms: Application doesn't respond to clicks",
                        "Solutions:\n" +
                                "1. Wait 30 seconds for response\n" +
                                "2. Press Escape key to cancel current action\n" +
                                "3. Force quit and restart application\n" +
                                "4. Restart computer if needed\n" +
                                "5. Report recurring freezes to support"));

        contentArea.getChildren().add(issuesBox);
    }

    // ========== System Info Section ==========
    private void showSystemInfo() {
        contentArea.getChildren().clear();

        Label sectionTitle = createSectionTitle("💻 System Information");
        contentArea.getChildren().add(sectionTitle);

        VBox infoBox = new VBox(15);
        infoBox.setPadding(new Insets(20));
        infoBox.setStyle("-fx-background-color: #f5f5f5; -fx-background-radius: 8;");

        // Application Info
        String appVersion = configManager.getProperty("app.version", "1.0.0");
        String buildDate = configManager.getProperty("app.build.date", "Unknown");

        infoBox.getChildren().add(createInfoRow("Application Version", appVersion));
        infoBox.getChildren().add(createInfoRow("Build Date", buildDate));

        // Device Info
        try {
            DeviceRegistrationService deviceService = DeviceRegistrationService.getInstance();
            if (deviceService.isDeviceRegistered()) {
                var deviceInfo = deviceService.getDeviceInfo();
                infoBox.getChildren().add(new Separator());
                infoBox.getChildren()
                        .add(createInfoRow("Device ID", deviceInfo.deviceId != null ? deviceInfo.deviceId : "N/A"));
                infoBox.getChildren().add(
                        createInfoRow("Device Name", deviceInfo.deviceName != null ? deviceInfo.deviceName : "N/A"));
                infoBox.getChildren()
                        .add(createInfoRow("Store ID", deviceInfo.storeId != null ? deviceInfo.storeId : "N/A"));
            }
        } catch (Exception e) {
            logger.debug("Could not load device info", e);
        }

        // Sync Status
        try {
            OfflineSyncService syncService = OfflineSyncService.getInstance();
            infoBox.getChildren().add(new Separator());
            infoBox.getChildren()
                    .add(createInfoRow("Connection Status", syncService.isOnline() ? "Online" : "Offline"));
            infoBox.getChildren()
                    .add(createInfoRow("Pending Operations", String.valueOf(syncService.getPendingRequestCount())));
        } catch (Exception e) {
            logger.debug("Could not load sync status", e);
        }

        // System Info
        infoBox.getChildren().add(new Separator());
        infoBox.getChildren().add(createInfoRow("Operating System",
                System.getProperty("os.name") + " " + System.getProperty("os.version")));
        infoBox.getChildren().add(createInfoRow("Java Version", System.getProperty("java.version")));
        infoBox.getChildren().add(createInfoRow("Current Time",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));

        // Action buttons
        HBox buttonBox = new HBox(15);
        buttonBox.setPadding(new Insets(20, 0, 0, 0));

        Button copyInfoButton = new Button("Copy System Info");
        copyInfoButton.setStyle(
                "-fx-background-color: #2196F3; -fx-text-fill: white; -fx-padding: 10 20; -fx-background-radius: 5;");
        copyInfoButton.setOnAction(e -> copySystemInfo());

        Button openLogsButton = new Button("Open Log Folder");
        openLogsButton.setStyle(
                "-fx-background-color: #607D8B; -fx-text-fill: white; -fx-padding: 10 20; -fx-background-radius: 5;");
        openLogsButton.setOnAction(e -> openLogFolder());

        Button refreshButton = new Button("Refresh");
        refreshButton.setStyle(
                "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-padding: 10 20; -fx-background-radius: 5;");
        refreshButton.setOnAction(e -> showSystemInfo());

        buttonBox.getChildren().addAll(copyInfoButton, openLogsButton, refreshButton);

        contentArea.getChildren().addAll(infoBox, buttonBox);
    }

    private HBox createInfoRow(String label, String value) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);

        Label labelNode = new Label(label + ":");
        labelNode.setStyle("-fx-font-weight: bold; -fx-min-width: 150;");

        Label valueNode = new Label(value);
        valueNode.setStyle("-fx-font-family: 'Courier New', monospace;");

        row.getChildren().addAll(labelNode, valueNode);
        return row;
    }

    // ========== Contact Support Section ==========
    private void showContactSupport() {
        contentArea.getChildren().clear();

        Label sectionTitle = createSectionTitle("📞 Contact Support");
        contentArea.getChildren().add(sectionTitle);

        VBox contactBox = new VBox(20);
        contactBox.setPadding(new Insets(20));
        contactBox.setStyle("-fx-background-color: #e3f2fd; -fx-background-radius: 8;");

        Label intro = new Label("Need help? Our support team is here for you!");
        intro.setStyle("-fx-font-size: 16px;");

        VBox phoneBox = createContactMethod("📱 Phone Support", "1-800-POS-HELP (1-800-767-4357)",
                "Available Monday-Friday, 8 AM - 8 PM EST\nSaturday-Sunday, 10 AM - 6 PM EST");

        VBox emailBox = createContactMethod("📧 Email Support", "support@possystem.com",
                "Response within 24 hours on business days");

        VBox chatBox = createContactMethod("💬 Live Chat", "Available in the web portal",
                "Instant support during business hours");

        VBox emergencyBox = new VBox(5);
        emergencyBox.setPadding(new Insets(15));
        emergencyBox.setStyle("-fx-background-color: #ffebee; -fx-background-radius: 5;");
        Label emergencyTitle = new Label("🚨 Emergency Support");
        emergencyTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #d32f2f;");
        Label emergencyText = new Label(
                "For critical system failures during business hours:\nCall 1-800-POS-911 (24/7 for Enterprise customers)");
        emergencyText.setStyle("-fx-font-size: 14px;");
        emergencyBox.getChildren().addAll(emergencyTitle, emergencyText);

        contactBox.getChildren().addAll(intro, phoneBox, emailBox, chatBox, emergencyBox);

        // Before contacting support
        VBox beforeContactBox = createInfoBox("Before Contacting Support",
                "Please have the following information ready:\n\n" +
                        "• Store ID and Device ID (see System Info)\n" +
                        "• Description of the issue\n" +
                        "• Steps to reproduce the problem\n" +
                        "• Any error messages displayed\n" +
                        "• Time when the issue occurred");

        // Submit ticket button
        HBox buttonBox = new HBox(15);
        buttonBox.setPadding(new Insets(20, 0, 0, 0));

        Button submitTicketButton = new Button("Submit Support Ticket");
        submitTicketButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; " +
                "-fx-font-size: 16px; -fx-padding: 12 30; -fx-background-radius: 5; -fx-cursor: hand;");
        submitTicketButton.setOnAction(e -> showSubmitTicketDialog());

        Button callNowButton = new Button("Call Support Now");
        callNowButton.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; " +
                "-fx-font-size: 16px; -fx-padding: 12 30; -fx-background-radius: 5; -fx-cursor: hand;");
        callNowButton.setOnAction(e -> showSupportPhoneDialog());

        buttonBox.getChildren().addAll(submitTicketButton, callNowButton);

        contentArea.getChildren().addAll(contactBox, beforeContactBox, buttonBox);
    }

    // ========== Helper Methods ==========

    private Label createSectionTitle(String title) {
        Label label = new Label(title);
        label.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #333;");
        label.setPadding(new Insets(0, 0, 15, 0));
        return label;
    }

    private VBox createInfoBox(String title, String content) {
        VBox box = new VBox(10);
        box.setPadding(new Insets(15));
        box.setStyle("-fx-background-color: #f5f5f5; -fx-background-radius: 8;");

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");

        Label contentLabel = new Label(content);
        contentLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #555;");
        contentLabel.setWrapText(true);

        box.getChildren().addAll(titleLabel, contentLabel);
        return box;
    }

    private HBox createStep(String number, String title, String description) {
        HBox step = new HBox(15);
        step.setAlignment(Pos.TOP_LEFT);

        Label numLabel = new Label(number);
        numLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: white; " +
                "-fx-background-color: #2196F3; -fx-min-width: 35; -fx-min-height: 35; " +
                "-fx-alignment: center; -fx-background-radius: 20;");
        numLabel.setAlignment(Pos.CENTER);

        VBox textBox = new VBox(3);
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label descLabel = new Label(description);
        descLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");
        descLabel.setWrapText(true);
        textBox.getChildren().addAll(titleLabel, descLabel);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        step.getChildren().addAll(numLabel, textBox);
        return step;
    }

    private TitledPane createExpandableSection(String title, String content) {
        TitledPane pane = new TitledPane();
        pane.setText(title);
        pane.setExpanded(false);
        pane.setStyle("-fx-font-size: 14px;");

        Label contentLabel = new Label(content);
        contentLabel.setWrapText(true);
        contentLabel.setPadding(new Insets(10));
        contentLabel.setStyle("-fx-font-size: 13px;");

        pane.setContent(contentLabel);
        return pane;
    }

    private VBox createFAQItem(String question, String answer) {
        VBox item = new VBox(8);
        item.setPadding(new Insets(15));
        item.setStyle("-fx-background-color: #fafafa; -fx-background-radius: 5; " +
                "-fx-border-color: #e0e0e0; -fx-border-radius: 5;");

        Label questionLabel = new Label("Q: " + question);
        questionLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #1976D2;");
        questionLabel.setWrapText(true);

        Label answerLabel = new Label("A: " + answer);
        answerLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #555;");
        answerLabel.setWrapText(true);

        item.getChildren().addAll(questionLabel, answerLabel);
        return item;
    }

    private VBox createTroubleshootItem(String title, String symptoms, String solutions) {
        VBox item = new VBox(10);
        item.setPadding(new Insets(15));
        item.setStyle("-fx-background-color: #fff8e1; -fx-background-radius: 8; " +
                "-fx-border-color: #ffcc80; -fx-border-radius: 8;");

        Label titleLabel = new Label("⚠️ " + title);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label symptomsLabel = new Label(symptoms);
        symptomsLabel.setStyle("-fx-font-size: 13px; -fx-font-style: italic; -fx-text-fill: #666;");

        Label solutionsLabel = new Label(solutions);
        solutionsLabel.setStyle("-fx-font-size: 13px;");
        solutionsLabel.setWrapText(true);

        item.getChildren().addAll(titleLabel, symptomsLabel, solutionsLabel);
        return item;
    }

    private VBox createContactMethod(String title, String value, String details) {
        VBox box = new VBox(5);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: white; -fx-background-radius: 5;");

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label valueLabel = new Label(value);
        valueLabel.setStyle("-fx-font-size: 18px; -fx-text-fill: #1976D2;");

        Label detailsLabel = new Label(details);
        detailsLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        box.getChildren().addAll(titleLabel, valueLabel, detailsLabel);
        return box;
    }

    // ========== Action Methods ==========

    private void performSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            showAlert("Please enter a search term");
            return;
        }

        query = query.toLowerCase().trim();

        // Simple keyword matching to navigate to relevant sections
        if (query.contains("start") || query.contains("begin") || query.contains("quick")) {
            showQuickStart();
        } else if (query.contains("manual") || query.contains("guide") || query.contains("overview")) {
            showUserManual();
        } else if (query.contains("sale") || query.contains("payment") || query.contains("refund")
                || query.contains("return")) {
            showSalesHelp();
        } else if (query.contains("inventory") || query.contains("stock") || query.contains("product")) {
            showInventoryHelp();
        } else if (query.contains("report")) {
            showReportsHelp();
        } else if (query.contains("shift") || query.contains("cash") || query.contains("drawer")) {
            showShiftsHelp();
        } else if (query.contains("faq") || query.contains("question")) {
            showFAQ();
        } else if (query.contains("trouble") || query.contains("error") || query.contains("problem")
                || query.contains("fix")) {
            showTroubleshooting();
        } else if (query.contains("system") || query.contains("info") || query.contains("version")) {
            showSystemInfo();
        } else if (query.contains("support") || query.contains("contact") || query.contains("help")
                || query.contains("phone")) {
            showContactSupport();
        } else {
            showSearchResults(query);
        }
    }

    private void showSearchResults(String query) {
        contentArea.getChildren().clear();

        Label sectionTitle = createSectionTitle("🔍 Search Results for: \"" + query + "\"");
        contentArea.getChildren().add(sectionTitle);

        VBox resultsBox = new VBox(15);
        resultsBox.setPadding(new Insets(20));
        resultsBox.setStyle("-fx-background-color: #f5f5f5; -fx-background-radius: 8;");

        Label noResults = new Label(
                "No exact matches found. Try browsing the topics on the left, or try different keywords.");
        noResults.setStyle("-fx-font-size: 14px;");
        noResults.setWrapText(true);

        Label suggestions = new Label("\nSuggested searches:\n" +
                "• sales, payment, refund\n" +
                "• inventory, products, stock\n" +
                "• shift, cash, drawer\n" +
                "• printer, scanner, hardware\n" +
                "• report, daily\n" +
                "• support, contact");
        suggestions.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");

        resultsBox.getChildren().addAll(noResults, suggestions);
        contentArea.getChildren().add(resultsBox);
    }

    private void copySystemInfo() {
        StringBuilder info = new StringBuilder();
        info.append("=== Pasal POS 2 Information ===\n\n");

        String appVersion = configManager.getProperty("app.version", "1.0.0");
        info.append("Application Version: ").append(appVersion).append("\n");
        info.append("Operating System: ").append(System.getProperty("os.name")).append(" ")
                .append(System.getProperty("os.version")).append("\n");
        info.append("Java Version: ").append(System.getProperty("java.version")).append("\n");
        info.append("Timestamp: ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");

        try {
            DeviceRegistrationService deviceService = DeviceRegistrationService.getInstance();
            if (deviceService.isDeviceRegistered()) {
                var deviceInfo = deviceService.getDeviceInfo();
                info.append("\nDevice ID: ").append(deviceInfo.deviceId != null ? deviceInfo.deviceId : "N/A")
                        .append("\n");
                info.append("Store ID: ").append(deviceInfo.storeId != null ? deviceInfo.storeId : "N/A").append("\n");
            }
        } catch (Exception e) {
            logger.debug("Could not get device info", e);
        }

        try {
            OfflineSyncService syncService = OfflineSyncService.getInstance();
            info.append("\nConnection Status: ").append(syncService.isOnline() ? "Online" : "Offline").append("\n");
            info.append("Pending Operations: ").append(syncService.getPendingRequestCount()).append("\n");
        } catch (Exception e) {
            logger.debug("Could not get sync status", e);
        }

        // Copy to clipboard
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(info.toString());
        clipboard.setContent(content);

        showAlert("System information copied to clipboard");
    }

    private void openLogFolder() {
        try {
            // Try to find log folder
            File logDir = new File(System.getProperty("user.home") + "/Library/Logs/POS");
            if (!logDir.exists()) {
                logDir = new File("logs");
            }
            if (!logDir.exists()) {
                logDir = new File(".");
            }

            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(logDir);
            } else {
                showAlert("Log folder: " + logDir.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.error("Failed to open log folder", e);
            showAlert("Could not open log folder: " + e.getMessage());
        }
    }

    private void showSubmitTicketDialog() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Submit Support Ticket");
        dialog.setHeaderText("Describe your issue");

        DialogPane dialogPane = dialog.getDialogPane();
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialogPane.setPrefWidth(500);

        VBox content = new VBox(15);
        content.setPadding(new Insets(20));

        ComboBox<String> categoryBox = new ComboBox<>();
        categoryBox.getItems().addAll("Hardware Issue", "Software Bug", "Feature Request", "Account/Login",
                "Payment Processing", "Other");
        categoryBox.setPromptText("Select Category");
        categoryBox.setMaxWidth(Double.MAX_VALUE);

        TextField subjectField = new TextField();
        subjectField.setPromptText("Brief subject");

        TextArea descriptionArea = new TextArea();
        descriptionArea.setPromptText("Describe your issue in detail...");
        descriptionArea.setPrefRowCount(6);
        descriptionArea.setWrapText(true);

        CheckBox includeSystemInfo = new CheckBox("Include system information");
        includeSystemInfo.setSelected(true);

        content.getChildren().addAll(
                new Label("Category:"), categoryBox,
                new Label("Subject:"), subjectField,
                new Label("Description:"), descriptionArea,
                includeSystemInfo);

        dialogPane.setContent(content);
        DialogHelper.setDialogOwner(dialog, getScene() != null ? getScene().getWindow() : null);

        dialog.setResultConverter(button -> {
            if (button == ButtonType.OK) {
                return "submitted";
            }
            return null;
        });

        dialog.showAndWait().ifPresent(result -> {
            if ("submitted".equals(result)) {
                // In a real app, this would send the ticket to a support system
                showAlert("Thank you! Your support ticket has been submitted.\n\n" +
                        "Ticket reference: TKT-" + System.currentTimeMillis() % 100000 + "\n\n" +
                        "Our team will respond within 24 hours.");
            }
        });
    }

    private void showSupportPhoneDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Support Phone");
        alert.setHeaderText("Call Support");
        alert.setContentText("Support Hotline: 1-800-POS-HELP\n(1-800-767-4357)\n\n" +
                "Hours:\nMonday-Friday: 8 AM - 8 PM EST\nSaturday-Sunday: 10 AM - 6 PM EST\n\n" +
                "Have your Store ID and Device ID ready.");
        DialogHelper.setAlertOwner(alert, getScene() != null ? getScene().getWindow() : null);
        alert.showAndWait();
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Help Center");
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogHelper.setAlertOwner(alert, getScene() != null ? getScene().getWindow() : null);
        alert.showAndWait();
    }
}
