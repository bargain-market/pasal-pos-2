package com.pos.ui;

import com.pos.hardware.HardwareManager;
import com.pos.model.InventoryLog;
import com.pos.service.InventoryService;
import com.pos.service.ProductManagementService;
import com.pos.service.RoleBasedAccessService;
import com.pos.sync.WebSocketClient;
import com.pos.sync.WebSocketMessageHandler;
import com.pos.ui.dialogs.InventoryImportDialog;
import com.pos.ui.dialogs.ReorderLevelDialog;
import com.pos.ui.dialogs.StockAdjustmentDialog;
import com.pos.ui.dialogs.StockReconciliationDialog;
import com.pos.ui.components.ToastNotification;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.util.DialogHelper;
import com.pos.util.ErrorHandler;
import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Inventory Management View - Comprehensive inventory dashboard
 */
public class InventoryView extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(InventoryView.class);

    private InventoryService inventoryService;
    private ProductManagementService productService;
    private HardwareManager hardwareManager;
    private RoleBasedAccessService rbacService;
    private ObservableList<InventoryTableItem> inventoryList;
    private TableView<InventoryTableItem> inventoryTable;
    private com.pos.ui.components.TouchTextField searchField;
    private ComboBox<String> departmentFilter;
    private ComboBox<String> statusFilter;
    private Label statsLabel;
    private Runnable onBackToSales;

    // Scanner Input Handling
    private StringBuilder scannerInputBuffer = new StringBuilder();
    private long lastScannerInputTime = 0;
    private javafx.event.EventHandler<javafx.scene.input.KeyEvent> scannerHandler;
    private static final long SCANNER_INPUT_THRESHOLD_MS = 50;

    // Pagination state
    private static final int PAGE_SIZE = 50;
    private int currentPage = 0;
    private int totalProducts = 0;
    private int totalPages = 0;
    private boolean isLoading = false;

    // Pagination UI components
    private HBox paginationControls;
    private Button prevPageBtn;
    private Button nextPageBtn;
    private Label pageInfoLabel;
    private ProgressIndicator loadingIndicator;
    private StackPane tableContainer;
    private Button adjustStockButton;
    private Button reconcileButton;
    private Button setReorderButton;
    private Button historyButton;
    private Button exportButton;
    private Button importButton;

    public InventoryView(HardwareManager hardwareManager) {
        this(hardwareManager, null);
    }

    public InventoryView(HardwareManager hardwareManager, Runnable onBackToSales) {
        this.rbacService = RoleBasedAccessService.getInstance();
        this.hardwareManager = hardwareManager;
        this.inventoryService = InventoryService.getInstance();
        this.productService = ProductManagementService.getInstance();
        this.inventoryList = FXCollections.observableArrayList();
        this.onBackToSales = onBackToSales;

        initializeUI();
        loadInventory();
        loadDepartments();
        updateStatistics();
        setupWebSocketListeners();

        // Set up barcode scanner callback
        if (hardwareManager != null) {
            hardwareManager.setScanCallback(this::handleBarcodeScan);
        }

        // Setup scanner input listener
        setupScannerInput();
    }

    /**
     * Set up WebSocket listeners for real-time inventory updates.
     * Refreshes the inventory table when products or stock change.
     */
    private void setupWebSocketListeners() {
        try {
            WebSocketClient wsClient = WebSocketClient.getInstance();
            WebSocketMessageHandler messageHandler = wsClient.getMessageHandler();

            // Listen for product changes (create, update, delete)
            messageHandler.addProductEventListener(event -> {
                logger.info("📦 Product event received in InventoryView: {} - {}",
                        event.getAction(), event.getProductName());
                Platform.runLater(() -> {
                    loadInventory();
                    updateStatistics();
                });
            });

            // Listen for stock updates - most important for inventory view
            messageHandler.addStockEventListener(event -> {
                logger.info("📊 Stock event received in InventoryView: {} ({} -> {})",
                        event.getProductId(), event.getPreviousQuantity(), event.getNewQuantity());
                Platform.runLater(() -> {
                    loadInventory();
                    updateStatistics();
                });
            });

            // Listen for department changes
            messageHandler.addDepartmentEventListener(event -> {
                logger.info("🏷️ Department event received in InventoryView: {} - {}",
                        event.getAction(), event.getDepartmentName());
                Platform.runLater(this::loadDepartments);
            });

            logger.info("✅ WebSocket listeners registered for InventoryView");
        } catch (Exception e) {
            logger.error("Failed to setup WebSocket listeners in InventoryView", e);
        }
    }

    private void setupScannerInput() {
        // Create the handler that will process key events at the Scene level
        scannerHandler = event -> {
            long currentTime = System.currentTimeMillis();

            // Check if this is likely scanner input (rapid keystrokes)
            if (currentTime - lastScannerInputTime > SCANNER_INPUT_THRESHOLD_MS && scannerInputBuffer.length() > 0) {
                // If delay is too long, it's likely manual input or a new scan - reset buffer
                if (scannerInputBuffer.length() < 3) {
                    scannerInputBuffer.setLength(0);
                }
            }
            lastScannerInputTime = currentTime;

            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                if (scannerInputBuffer.length() > 0) {
                    String barcode = scannerInputBuffer.toString();
                    logger.info("Scanner input detected (Enter) in InventoryView: {}", barcode);

                    // Process potential barcode
                    if (hardwareManager != null) {
                        hardwareManager.processKeyboardBarcode(barcode);
                    }

                    // Clear buffer
                    scannerInputBuffer.setLength(0);

                    // Consume the event
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
                oldScene.removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, scannerHandler);
            }
            if (newScene != null && scannerHandler != null) {
                newScene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, scannerHandler);
            }
        });

        // Initial check
        if (getScene() != null) {
            getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, scannerHandler);
        }
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #f5f7fa;");

        // Top section - Title, search, filters, and statistics
        VBox topSection = createTopSection();
        setTop(topSection);

        // Center - Inventory table with loading indicator
        tableContainer = new StackPane();

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setContent(createInventoryTable());
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);

        // Loading indicator overlay
        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setMaxSize(60, 60);
        loadingIndicator.setVisible(false);

        tableContainer.getChildren().addAll(scrollPane, loadingIndicator);
        setCenter(tableContainer);

        // Bottom - Action buttons and pagination
        VBox bottomSection = new VBox(10);
        bottomSection.setPadding(new Insets(0));

        // Pagination controls
        paginationControls = createPaginationControls();

        // Action buttons (FlowPane for responsive layout)
        FlowPane actionButtons = createBottomSection();

        bottomSection.getChildren().addAll(paginationControls, actionButtons);
        setBottom(bottomSection);
    }

    /**
     * Create pagination controls (Previous, Page Info, Next)
     */
    private HBox createPaginationControls() {
        HBox controls = new HBox(15);
        controls.setAlignment(Pos.CENTER);
        controls.setPadding(new Insets(10, 15, 0, 15));
        controls.setStyle("-fx-background-color: white;");

        prevPageBtn = new Button("\u2190 Previous");
        prevPageBtn.setStyle(
                "-fx-background-color: #2a5298; -fx-text-fill: white; -fx-background-radius: 5; -fx-padding: 8 16; -fx-cursor: hand;");
        prevPageBtn.setOnAction(e -> loadPreviousPage());
        prevPageBtn.setDisable(true);
        prevPageBtn.setMinWidth(150);

        pageInfoLabel = new Label("Page 1 of 1 (0 products)");
        pageInfoLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #666;");

        nextPageBtn = new Button("Next \u2192");
        nextPageBtn.setStyle(
                "-fx-background-color: #2a5298; -fx-text-fill: white; -fx-background-radius: 5; -fx-padding: 8 16; -fx-cursor: hand;");
        nextPageBtn.setOnAction(e -> loadNextPage());
        nextPageBtn.setDisable(true);
        nextPageBtn.setMinWidth(150);

        controls.getChildren().addAll(prevPageBtn, pageInfoLabel, nextPageBtn);
        return controls;
    }

    /**
     * Load the previous page of products
     */
    private void loadPreviousPage() {
        if (currentPage > 0) {
            currentPage--;
            loadInventory();
        }
    }

    /**
     * Load the next page of products
     */
    private void loadNextPage() {
        if (currentPage < totalPages - 1) {
            currentPage++;
            loadInventory();
        }
    }

    /**
     * Update pagination controls based on current state
     */
    private void updatePaginationControls() {
        prevPageBtn.setDisable(currentPage <= 0 || isLoading);
        nextPageBtn.setDisable(currentPage >= totalPages - 1 || isLoading);

        int startItem = currentPage * PAGE_SIZE + 1;
        int endItem = Math.min((currentPage + 1) * PAGE_SIZE, totalProducts);

        if (totalProducts == 0) {
            pageInfoLabel.setText("No products found");
        } else {
            pageInfoLabel.setText(String.format("Page %d of %d (%d-%d of %d products)",
                    currentPage + 1, totalPages, startItem, endItem, totalProducts));
        }
    }

    /**
     * Show or hide loading indicator
     */
    private void setLoading(boolean loading) {
        this.isLoading = loading;
        Platform.runLater(() -> {
            loadingIndicator.setVisible(loading);
            inventoryTable.setOpacity(loading ? 0.5 : 1.0);
            updatePaginationControls();
        });
    }

    private VBox createTopSection() {
        VBox topSection = new VBox(15);
        topSection.setPadding(new Insets(20));
        topSection.setStyle("-fx-background-color: white;");

        // Title and Back button row
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

        // Title
        Label titleLabel = new Label("Inventory Management");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        titleRow.getChildren().add(titleLabel);

        // Statistics cards
        HBox statsBox = createStatisticsCards();

        // Search and filter section
        HBox searchSection = new HBox(10);
        searchSection.setAlignment(Pos.CENTER_LEFT);

        Label searchLabel = new Label("Search:");
        searchField = TouchScreenComponents.createTouchOnlyTextField("Search by name, SKU, or barcode");
        // searchField.setTextFieldPrefWidth(300);
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> searchInventory());

        Label deptLabel = new Label("Department:");
        departmentFilter = new ComboBox<>();
        departmentFilter.setPromptText("All Departments");
        departmentFilter.setPrefWidth(200);
        departmentFilter.setOnAction(e -> filterInventory());

        Label statusLabel = new Label("Status:");
        statusFilter = new ComboBox<>();
        statusFilter.getItems().addAll("All", "IN_STOCK", "LOW_STOCK", "OUT_OF_STOCK", "STOCK_NOT_UPDATED");
        statusFilter.setValue("All");
        statusFilter.setPrefWidth(150);
        statusFilter.setOnAction(e -> filterInventory());

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> {
            loadInventory();
            updateStatistics();
        });

        searchSection.getChildren().addAll(searchLabel, searchField, deptLabel, departmentFilter,
                statusLabel, statusFilter, refreshButton);

        topSection.getChildren().addAll(titleRow, statsBox, searchSection);

        return topSection;
    }

    private HBox createStatisticsCards() {
        HBox statsBox = new HBox(15);
        // Make stats box responsive - scale on smaller screens if needed
        statsBox.setAlignment(Pos.CENTER_LEFT);

        // Total Products Card
        VBox totalCard = createStatCard("Total Products", "0", "#2196F3");

        // Low Stock Card
        VBox lowStockCard = createStatCard("Low Stock", "0", "#FF9800");

        // Out of Stock Card
        VBox outOfStockCard = createStatCard("Out of Stock", "0", "#f44336");

        // Total Value Card
        VBox valueCard = createStatCard("Total Value", "$0.00", "#4CAF50");

        statsLabel = new Label(); // Will be updated by updateStatistics
        statsBox.getChildren().addAll(totalCard, lowStockCard, outOfStockCard, valueCard);

        return statsBox;
    }

    private VBox createStatCard(String label, String value, String color) {
        VBox card = new VBox(5);
        card.setPadding(new Insets(15));
        card.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 5;");
        card.setPrefWidth(150);
        // Let cards grow to fill space if needed
        HBox.setHgrow(card, Priority.ALWAYS);

        Label valueLabel = new Label(value);
        valueLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: white;");

        Label titleLabel = new Label(label);
        titleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: white;");

        card.getChildren().addAll(valueLabel, titleLabel);
        return card;
    }

    private TableView<InventoryTableItem> createInventoryTable() {
        inventoryTable = new TableView<>();
        inventoryTable.setItems(inventoryList);
        inventoryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // Name column
        TableColumn<InventoryTableItem, String> nameCol = new TableColumn<>("Product Name");
        nameCol.setCellValueFactory(cellData -> {
            InventoryTableItem item = cellData.getValue();
            return item != null ? new SimpleStringProperty(item.getName()) : new SimpleStringProperty("");
        });
        nameCol.setPrefWidth(200);

        // SKU column
        TableColumn<InventoryTableItem, String> skuCol = new TableColumn<>("SKU");
        skuCol.setCellValueFactory(cellData -> {
            InventoryTableItem item = cellData.getValue();
            return item != null ? new SimpleStringProperty(item.getSku()) : new SimpleStringProperty("");
        });
        skuCol.setPrefWidth(120);

        // Barcode column
        TableColumn<InventoryTableItem, String> barcodeCol = new TableColumn<>("Barcode");
        barcodeCol.setCellValueFactory(cellData -> {
            InventoryTableItem item = cellData.getValue();
            return item != null ? new SimpleStringProperty(item.getBarcode()) : new SimpleStringProperty("");
        });
        barcodeCol.setPrefWidth(150);

        // Stock column with status indicator
        TableColumn<InventoryTableItem, Integer> stockCol = new TableColumn<>("Stock");
        stockCol.setCellValueFactory(cellData -> {
            InventoryTableItem item = cellData.getValue();
            return item != null ? new SimpleIntegerProperty(item.getStock()).asObject()
                    : new SimpleIntegerProperty(0).asObject();
        });
        stockCol.setPrefWidth(100);
        stockCol.setCellFactory(column -> new TableCell<InventoryTableItem, Integer>() {
            @Override
            protected void updateItem(Integer stock, boolean empty) {
                super.updateItem(stock, empty);
                if (empty || stock == null) {
                    setText(null);
                    setStyle("");
                } else {
                    InventoryTableItem item = getTableView().getItems().get(getIndex());
                    String status = item.getStatus();
                    setText("STOCK_NOT_UPDATED".equals(status) ? "Not Updated" : String.valueOf(stock));
                    if ("STOCK_NOT_UPDATED".equals(status)) {
                        setStyle("-fx-text-fill: #607d8b; -fx-font-weight: bold;");
                    } else if ("OUT_OF_STOCK".equals(status)) {
                        setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                    } else if ("LOW_STOCK".equals(status)) {
                        setStyle("-fx-text-fill: orange; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: green;");
                    }
                }
            }
        });

        // Status column
        TableColumn<InventoryTableItem, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(cellData -> {
            InventoryTableItem item = cellData.getValue();
            return item != null ? new SimpleStringProperty(item.getStatus()) : new SimpleStringProperty("");
        });
        statusCol.setPrefWidth(100);
        statusCol.setCellFactory(column -> new TableCell<InventoryTableItem, String>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText("STOCK_NOT_UPDATED".equals(status) ? "Stock Not Updated" : status.replace("_", " "));
                    if ("STOCK_NOT_UPDATED".equals(status)) {
                        setStyle("-fx-text-fill: #607d8b; -fx-font-weight: bold;");
                    } else if ("OUT_OF_STOCK".equals(status)) {
                        setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                    } else if ("LOW_STOCK".equals(status)) {
                        setStyle("-fx-text-fill: orange; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: green;");
                    }
                }
            }
        });

        // Reorder Level column
        TableColumn<InventoryTableItem, Integer> reorderCol = new TableColumn<>("Reorder Level");
        reorderCol.setCellValueFactory(cellData -> {
            InventoryTableItem item = cellData.getValue();
            return item != null ? new SimpleIntegerProperty(item.getReorderLevel()).asObject()
                    : new SimpleIntegerProperty(0).asObject();
        });
        reorderCol.setPrefWidth(120);

        // Department column
        TableColumn<InventoryTableItem, String> deptCol = new TableColumn<>("Department");
        deptCol.setCellValueFactory(cellData -> {
            InventoryTableItem item = cellData.getValue();
            return item != null ? new SimpleStringProperty(item.getDepartment()) : new SimpleStringProperty("");
        });
        deptCol.setPrefWidth(150);

        inventoryTable.getColumns().addAll(nameCol, skuCol, barcodeCol, stockCol, statusCol, reorderCol, deptCol);

        // Double-click to view history
        inventoryTable.setRowFactory(tv -> {
            TableRow<InventoryTableItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    viewInventoryHistory(row.getItem());
                }
            });
            return row;
        });

        return inventoryTable;
    }

    /**
     * Create a styled icon button with text label for responsive layout
     */
    private Button createIconButton(String icon, String text, String color) {
        VBox content = new VBox(4);
        content.setAlignment(Pos.CENTER);
        content.setFillWidth(true);

        Label iconLabel = new Label(icon);
        iconLabel.setStyle("-fx-font-size: 20px; -fx-text-fill: white;");

        Label textLabel = new Label(text);
        textLabel.setWrapText(true);
        textLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        textLabel.setAlignment(Pos.CENTER);
        textLabel.setMaxWidth(72);
        textLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: white;");

        content.getChildren().addAll(iconLabel, textLabel);

        Button button = new Button();
        button.setGraphic(content);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setMinWidth(106);
        button.setMinHeight(84);
        button.setPrefWidth(118);
        button.setPrefHeight(84);
        button.setFocusTraversable(false);
        applyActionButtonStyle(button, color, false, false);

        button.hoverProperty().addListener((obs, oldVal, isHovered) ->
                applyActionButtonStyle(button, color, isHovered, button.isDisabled()));
        button.disabledProperty().addListener((obs, oldVal, isDisabled) ->
                applyActionButtonStyle(button, color, button.isHover(), isDisabled));

        return button;
    }

    private void applyActionButtonStyle(Button button, String color, boolean hovered, boolean disabled) {
        String backgroundColor = disabled
                ? "#cbd5e1"
                : hovered ? "derive(" + color + ", -10%)" : color;
        String shadow = disabled
                ? "-fx-effect: none;"
                : hovered
                        ? "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 6, 0, 0, 2);"
                        : "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 4, 0, 0, 1);";

        button.setStyle(
                "-fx-background-color: " + backgroundColor + ";" +
                        "-fx-text-fill: white;" +
                        "-fx-background-radius: 10;" +
                        "-fx-padding: 10 12;" +
                        "-fx-cursor: " + (disabled ? "default" : "hand") + ";" +
                        "-fx-opacity: " + (disabled ? "0.75" : "1.0") + ";" +
                        shadow);
    }

    private FlowPane createBottomSection() {
        // Use FlowPane for responsive button layout that wraps on smaller screens
        FlowPane bottomSection = new FlowPane();
        bottomSection.setPadding(new Insets(15));
        bottomSection.setAlignment(Pos.CENTER);
        bottomSection.setHgap(12);
        bottomSection.setVgap(12);
        bottomSection.setStyle("-fx-background-color: white;");

        // Stock adjustment buttons - Manager+ only
        adjustStockButton = null;
        reconcileButton = null;

        if (rbacService.hasPermission(RoleBasedAccessService.PERMISSION_ADJUST_STOCK)) {
            adjustStockButton = createIconButton("\uD83D\uDCE6", "Adjust", "#FF9800");
            adjustStockButton.setOnAction(e -> {
                InventoryTableItem selected = inventoryTable.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    adjustStock(selected);
                } else {
                    ToastNotification.showWarning("Please select a product to adjust stock",
                            getScene() != null ? getScene().getWindow() : null);
                }
            });

            reconcileButton = createIconButton("\u21BB", "Reconcile", "#2196F3");
            reconcileButton.setOnAction(e -> {
                InventoryTableItem selected = inventoryTable.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    reconcileStock(selected);
                } else {
                    ToastNotification.showWarning("Please select a product to reconcile stock",
                            getScene() != null ? getScene().getWindow() : null);
                }
            });
        }

        setReorderButton = createIconButton("\u25CE", "Reorder", "#9C27B0");
        setReorderButton.setOnAction(e -> {
            InventoryTableItem selected = inventoryTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                setReorderLevel(selected);
            } else {
                ToastNotification.showWarning("Please select a product to set reorder level",
                        getScene() != null ? getScene().getWindow() : null);
            }
        });

        historyButton = createIconButton("\u2630", "History", "#607D8B");
        historyButton.setOnAction(e -> {
            InventoryTableItem selected = inventoryTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                viewInventoryHistory(selected);
            } else {
                ToastNotification.showWarning("Please select a product to view history",
                        getScene() != null ? getScene().getWindow() : null);
            }
        });

        exportButton = createIconButton("\u21E7", "Export", "#4CAF50");
        exportButton.setOnAction(e -> exportToCSV());

        importButton = createIconButton("\u21E9", "Import", "#673AB7");
        importButton.setOnAction(e -> showImportDialog());

        // Add buttons conditionally
        if (adjustStockButton != null) {
            bottomSection.getChildren().add(adjustStockButton);
        }
        if (reconcileButton != null) {
            bottomSection.getChildren().add(reconcileButton);
        }
        bottomSection.getChildren().addAll(setReorderButton,
                historyButton, exportButton, importButton);

        inventoryTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> updateActionButtons());
        updateActionButtons();

        return bottomSection;
    }

    private void updateActionButtons() {
        InventoryTableItem selected = inventoryTable != null
                ? inventoryTable.getSelectionModel().getSelectedItem()
                : null;
        boolean hasSelection = selected != null;

        if (adjustStockButton != null) {
            adjustStockButton.setDisable(!hasSelection);
        }
        if (reconcileButton != null) {
            reconcileButton.setDisable(!hasSelection);
        }
        if (setReorderButton != null) {
            setReorderButton.setDisable(!hasSelection);
        }
        if (historyButton != null) {
            historyButton.setDisable(!hasSelection);
        }
    }

    private void loadInventory() {
        if (isLoading) {
            logger.debug("Already loading inventory, skipping request");
            return;
        }

        setLoading(true);

        new Thread(() -> {
            try {
                // Get total count for pagination
                int count = productService.getProductInfoCount(null);
                totalProducts = count;
                totalPages = (int) Math.ceil((double) count / PAGE_SIZE);

                // Ensure currentPage is valid
                if (currentPage >= totalPages && totalPages > 0) {
                    currentPage = totalPages - 1;
                }

                // Calculate offset
                int offset = currentPage * PAGE_SIZE;

                // Get paginated products
                List<ProductManagementService.ProductInfo> products = productService.getProductInfos(offset, PAGE_SIZE);
                logger.debug("Loaded {} products (page {} of {}, total: {})",
                        products.size(), currentPage + 1, totalPages, totalProducts);

                Platform.runLater(() -> {
                    inventoryList.clear();
                    for (ProductManagementService.ProductInfo info : products) {
                        // reorder level comes from the same SELECT that loaded the product —
                        // no per-row DB query on the FX thread (avoids the catalog-load freeze).
                        inventoryList.add(new InventoryTableItem(info, info.reorderLevel));
                    }
                    logger.info("Loaded {} products for inventory view", products.size());
                    // Reset table items to the full list, then apply filters
                    inventoryTable.setItems(inventoryList);
                    applyFilters();
                    inventoryTable.getSelectionModel().clearSelection();
                    updateActionButtons();
                    setLoading(false);
                });
            } catch (SQLException e) {
                Platform.runLater(() -> setLoading(false));
                ErrorHandler.handleErrorWithToast(e, "Failed to load inventory",
                        getScene() != null ? getScene().getWindow() : null);
            } catch (Exception e) {
                Platform.runLater(() -> setLoading(false));
                ErrorHandler.handleErrorWithToast(e, "Failed to load inventory",
                        getScene() != null ? getScene().getWindow() : null);
            }
        }).start();
    }

    private void loadDepartments() {
        new Thread(() -> {
            try {
                List<ProductManagementService.Department> departments = productService.getDepartments();
                Platform.runLater(() -> {
                    departmentFilter.getItems().clear();
                    departmentFilter.getItems().add("All Departments");
                    for (ProductManagementService.Department dept : departments) {
                        departmentFilter.getItems().add(dept.name);
                    }
                    // Set default value if not already set
                    if (departmentFilter.getValue() == null) {
                        departmentFilter.setValue("All Departments");
                    }
                });
            } catch (SQLException e) {
                ErrorHandler.handleErrorWithToast(e, "Failed to load departments",
                        getScene() != null ? getScene().getWindow() : null);
            } catch (Exception e) {
                ErrorHandler.handleErrorWithToast(e, "Failed to load departments",
                        getScene() != null ? getScene().getWindow() : null);
            }
        }).start();
    }

    private void updateStatistics() {
        new Thread(() -> {
            try {
                InventoryService.InventoryStatistics stats = inventoryService.getStatistics();
                Platform.runLater(() -> {
                    // Update statistics cards
                    HBox statsBox = (HBox) ((VBox) getTop()).getChildren().get(1);
                    if (statsBox.getChildren().size() >= 4) {
                        VBox totalCard = (VBox) statsBox.getChildren().get(0);
                        VBox lowStockCard = (VBox) statsBox.getChildren().get(1);
                        VBox outOfStockCard = (VBox) statsBox.getChildren().get(2);
                        VBox valueCard = (VBox) statsBox.getChildren().get(3);

                        ((Label) totalCard.getChildren().get(0)).setText(String.valueOf(stats.totalProducts));
                        ((Label) lowStockCard.getChildren().get(0)).setText(String.valueOf(stats.lowStockCount));
                        ((Label) outOfStockCard.getChildren().get(0)).setText(String.valueOf(stats.outOfStockCount));
                        ((Label) valueCard.getChildren().get(0)).setText(
                                NumberFormat.getCurrencyInstance().format(stats.totalValue));
                    }
                });
            } catch (SQLException e) {
                ErrorHandler.handleErrorWithToast(e, "Failed to update statistics",
                        getScene() != null ? getScene().getWindow() : null);
            } catch (Exception e) {
                ErrorHandler.handleErrorWithToast(e, "Failed to update statistics",
                        getScene() != null ? getScene().getWindow() : null);
            }
        }).start();
    }

    private void searchInventory() {
        String searchTerm = searchField.getText().trim();
        if (searchTerm.isEmpty()) {
            currentPage = 0;
            filterInventory();
            return;
        }

        setLoading(true);
        new Thread(() -> {
            try {
                List<ProductManagementService.ProductInfo> products = productService.searchProductInfos(searchTerm);
                Platform.runLater(() -> {
                    inventoryList.clear();
                    for (ProductManagementService.ProductInfo info : products) {
                        // reorder level already loaded with the product — no per-row query.
                        inventoryList.add(new InventoryTableItem(info, info.reorderLevel));
                    }
                    // Update pagination for search results
                    totalProducts = products.size();
                    totalPages = 1;
                    currentPage = 0;

                    // Reset table items to the full list, then apply filters
                    inventoryTable.setItems(inventoryList);
                    applyFilters();
                    inventoryTable.getSelectionModel().clearSelection();
                    updateActionButtons();
                    setLoading(false);
                    pageInfoLabel.setText(String.format("Search results: %d products found", products.size()));
                });
            } catch (SQLException e) {
                Platform.runLater(() -> setLoading(false));
                ErrorHandler.handleErrorWithToast(e, "Failed to search inventory",
                        getScene() != null ? getScene().getWindow() : null);
            } catch (Exception e) {
                Platform.runLater(() -> setLoading(false));
                ErrorHandler.handleErrorWithToast(e, "Failed to search inventory",
                        getScene() != null ? getScene().getWindow() : null);
            }
        }).start();
    }

    private void filterInventory() {
        String searchTerm = searchField.getText().trim();
        currentPage = 0; // Reset to first page when filtering
        if (searchTerm.isEmpty()) {
            loadInventory();
        } else {
            searchInventory();
        }
        // Note: applyFilters() is called inside loadInventory() and searchInventory()
        // after data is loaded
    }

    private void applyFilters() {
        String selectedDept = departmentFilter.getValue();
        String selectedStatus = statusFilter.getValue();

        if ("All Departments".equals(selectedDept) && "All".equals(selectedStatus)) {
            // No filtering needed - show all items
            inventoryTable.setItems(inventoryList);
            updateActionButtons();
            return;
        }

        ObservableList<InventoryTableItem> filtered = FXCollections.observableArrayList();

        for (InventoryTableItem item : inventoryList) {
            boolean matchesDept = "All Departments".equals(selectedDept) ||
                    selectedDept == null ||
                    selectedDept.equals(item.getDepartment());

            boolean matchesStatus = "All".equals(selectedStatus) ||
                    selectedStatus == null ||
                    selectedStatus.equals(item.getStatus());

            if (matchesDept && matchesStatus) {
                filtered.add(item);
            }
        }

        inventoryTable.setItems(filtered);
        updateActionButtons();
    }

    private void adjustStock(InventoryTableItem item) {
        // Permission check - Manager+ required
        if (!rbacService.hasPermission(RoleBasedAccessService.PERMISSION_ADJUST_STOCK)) {
            ToastNotification.showError(
                    "You do not have permission to adjust stock. Manager or Admin role required.",
                    getScene() != null ? getScene().getWindow() : null);
            return;
        }

        StockAdjustmentDialog dialog = new StockAdjustmentDialog(item.getStock());
        Optional<StockAdjustmentDialog.StockAdjustmentResult> result = dialog.showAndWait();

        // Restore scanner callback
        if (hardwareManager != null) {
            hardwareManager.setScanCallback(this::handleBarcodeScan);
        }

        result.ifPresent(adjustmentResult -> {
            new Thread(() -> {
                try {
                    inventoryService.adjustStock(item.getId(), adjustmentResult.adjustment,
                            adjustmentResult.changeType, adjustmentResult.reason);
                    Platform.runLater(() -> {
                        loadInventory();
                        updateStatistics();
                        ToastNotification.showSuccess("Stock adjusted successfully",
                                getScene() != null ? getScene().getWindow() : null);
                    });
                } catch (SQLException e) {
                    ErrorHandler.handleErrorWithToast(e, "Failed to adjust stock",
                            getScene() != null ? getScene().getWindow() : null);
                } catch (Exception e) {
                    ErrorHandler.handleErrorWithToast(e, "Failed to adjust stock",
                            getScene() != null ? getScene().getWindow() : null);
                }
            }).start();
        });
    }

    private void reconcileStock(InventoryTableItem item) {
        // Permission check - Manager+ required
        if (!rbacService.hasPermission(RoleBasedAccessService.PERMISSION_ADJUST_STOCK)) {
            ToastNotification.showError(
                    "You do not have permission to reconcile stock. Manager or Admin role required.",
                    getScene() != null ? getScene().getWindow() : null);
            return;
        }

        StockReconciliationDialog dialog = new StockReconciliationDialog(item.getStock());
        Optional<StockReconciliationDialog.StockReconciliationResult> result = dialog.showAndWait();

        // Restore scanner callback
        if (hardwareManager != null) {
            hardwareManager.setScanCallback(this::handleBarcodeScan);
        }

        result.ifPresent(reconciliationResult -> {
            new Thread(() -> {
                try {
                    inventoryService.reconcileStock(item.getId(), reconciliationResult.actualQuantity,
                            reconciliationResult.reason);
                    Platform.runLater(() -> {
                        loadInventory();
                        updateStatistics();
                        ToastNotification.showSuccess("Stock reconciled successfully",
                                getScene() != null ? getScene().getWindow() : null);
                    });
                } catch (SQLException e) {
                    ErrorHandler.handleErrorWithToast(e, "Failed to reconcile stock",
                            getScene() != null ? getScene().getWindow() : null);
                } catch (Exception e) {
                    ErrorHandler.handleErrorWithToast(e, "Failed to reconcile stock",
                            getScene() != null ? getScene().getWindow() : null);
                }
            }).start();
        });
    }

    private void setReorderLevel(InventoryTableItem item) {
        ReorderLevelDialog dialog = new ReorderLevelDialog(item.getStock(), item.getReorderLevel());
        Optional<ReorderLevelDialog.ReorderLevelResult> result = dialog.showAndWait();

        // Restore scanner callback
        if (hardwareManager != null) {
            hardwareManager.setScanCallback(this::handleBarcodeScan);
        }

        result.ifPresent(reorderResult -> {
            new Thread(() -> {
                try {
                    inventoryService.setReorderLevel(item.getId(), reorderResult.reorderLevel);
                    Platform.runLater(() -> {
                        loadInventory();
                        ToastNotification.showSuccess("Reorder level set successfully",
                                getScene() != null ? getScene().getWindow() : null);
                    });
                } catch (SQLException e) {
                    ErrorHandler.handleErrorWithToast(e, "Failed to set reorder level",
                            getScene() != null ? getScene().getWindow() : null);
                } catch (Exception e) {
                    ErrorHandler.handleErrorWithToast(e, "Failed to set reorder level",
                            getScene() != null ? getScene().getWindow() : null);
                }
            }).start();
        });
    }

    private void viewInventoryHistory(InventoryTableItem item) {
        Dialog<ButtonType> historyDialog = new Dialog<>();
        historyDialog.setTitle("Inventory History");
        historyDialog.setHeaderText("History for: " + item.getName());
        historyDialog.initModality(Modality.APPLICATION_MODAL);

        // Set owner and make responsive using DialogHelper
        DialogHelper.setDialogOwner(historyDialog, getScene() != null ? getScene().getWindow() : null);

        historyDialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        TableView<InventoryLog> historyTable = new TableView<>();

        TableColumn<InventoryLog, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(cellData -> {
            InventoryLog log = cellData.getValue();
            if (log.getCreatedAt() != null) {
                return new javafx.beans.property.SimpleStringProperty(
                        log.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            }
            return new javafx.beans.property.SimpleStringProperty("");
        });
        dateCol.setPrefWidth(150);

        TableColumn<InventoryLog, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("changeType"));
        typeCol.setPrefWidth(120);

        TableColumn<InventoryLog, Integer> prevCol = new TableColumn<>("Previous");
        prevCol.setCellValueFactory(new PropertyValueFactory<>("previousQuantity"));
        prevCol.setPrefWidth(80);

        TableColumn<InventoryLog, Integer> newCol = new TableColumn<>("New");
        newCol.setCellValueFactory(new PropertyValueFactory<>("newQuantity"));
        newCol.setPrefWidth(80);

        TableColumn<InventoryLog, Integer> changeCol = new TableColumn<>("Change");
        changeCol.setCellValueFactory(new PropertyValueFactory<>("change"));
        changeCol.setPrefWidth(80);
        changeCol.setCellFactory(column -> new TableCell<InventoryLog, Integer>() {
            @Override
            protected void updateItem(Integer change, boolean empty) {
                super.updateItem(change, empty);
                if (empty || change == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(String.valueOf(change));
                    if (change > 0) {
                        setStyle("-fx-text-fill: green;");
                    } else if (change < 0) {
                        setStyle("-fx-text-fill: red;");
                    }
                }
            }
        });

        TableColumn<InventoryLog, String> reasonCol = new TableColumn<>("Reason");
        reasonCol.setCellValueFactory(new PropertyValueFactory<>("reason"));
        reasonCol.setPrefWidth(200);

        TableColumn<InventoryLog, String> userCol = new TableColumn<>("User");
        userCol.setCellValueFactory(new PropertyValueFactory<>("userName"));
        userCol.setPrefWidth(120);

        // Sync Status column
        TableColumn<InventoryLog, Boolean> syncedCol = new TableColumn<>("Synced");
        syncedCol.setCellValueFactory(new PropertyValueFactory<>("synced"));
        syncedCol.setPrefWidth(80);
        syncedCol.setCellFactory(column -> new TableCell<InventoryLog, Boolean>() {
            @Override
            protected void updateItem(Boolean synced, boolean empty) {
                super.updateItem(synced, empty);
                if (empty || synced == null) {
                    setText(null);
                    setStyle("");
                } else {
                    if (synced) {
                        setText("\u2713");
                        setStyle("-fx-text-fill: #4CAF50; -fx-font-weight: bold;");
                    } else {
                        setText("\u2717");
                        setStyle("-fx-text-fill: #d32f2f;");
                    }
                }
            }
        });

        @SuppressWarnings("unchecked")
        TableColumn<InventoryLog, ?>[] historyColumns = new TableColumn[] {
                dateCol, typeCol, prevCol, newCol, changeCol, reasonCol, userCol, syncedCol
        };
        historyTable.getColumns().addAll(historyColumns);

        new Thread(() -> {
            try {
                List<InventoryLog> history = inventoryService.getInventoryHistory(item.getId(), 100);
                Platform.runLater(() -> {
                    historyTable.getItems().addAll(history);
                });
            } catch (SQLException e) {
                ErrorHandler.handleErrorWithToast(e, "Failed to load inventory history",
                        getScene() != null ? getScene().getWindow() : null);
            } catch (Exception e) {
                ErrorHandler.handleErrorWithToast(e, "Failed to load inventory history",
                        getScene() != null ? getScene().getWindow() : null);
            }
        }).start();

        ScrollPane scrollPane = new ScrollPane(historyTable);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        historyDialog.getDialogPane().setContent(scrollPane);
        historyDialog.showAndWait();

        // Restore scanner callback
        if (hardwareManager != null) {
            hardwareManager.setScanCallback(this::handleBarcodeScan);
        }
    }

    private void exportToCSV() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Inventory to CSV");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        fileChooser.setInitialFileName("inventory_" +
                java.time.LocalDate.now().toString() + ".csv");

        Stage stage = (Stage) getScene().getWindow();
        File file = fileChooser.showSaveDialog(stage);

        if (file != null) {
            new Thread(() -> {
                try {
                    exportInventoryToCSV(file);
                    Platform.runLater(() -> {
                        ToastNotification.showSuccess("Inventory exported successfully",
                                getScene() != null ? getScene().getWindow() : null);
                    });
                } catch (IOException e) {
                    ErrorHandler.handleErrorWithToast(e, "Failed to export CSV",
                            getScene() != null ? getScene().getWindow() : null);
                } catch (Exception e) {
                    ErrorHandler.handleErrorWithToast(e, "Failed to export CSV",
                            getScene() != null ? getScene().getWindow() : null);
                }
            }).start();
        }
    }

    /**
     * Show the inventory import dialog
     */
    private void showImportDialog() {
        InventoryImportDialog dialog = new InventoryImportDialog(
                getScene() != null ? getScene().getWindow() : null);
        dialog.showAndWait().ifPresent(result -> {
            // Restore scanner callback
            if (hardwareManager != null) {
                hardwareManager.setScanCallback(this::handleBarcodeScan);
            }
            if (result) {
                // Refresh inventory after successful import
                loadInventory();
                loadDepartments();
                updateStatistics();
                ToastNotification.showSuccess("Inventory import completed successfully",
                        getScene() != null ? getScene().getWindow() : null);
            }
        });
    }

    private void exportInventoryToCSV(java.io.File file) throws IOException, SQLException {
        List<ProductManagementService.ProductInfo> allProducts = productService.getAllProductInfos();
        try (FileWriter writer = new FileWriter(file)) {
            // Write header
            writer.append(
                    "Name,SKU,Barcode,Stock,Price,Status,Reorder Level,Department,Dept Tax Enabled,Dept Tax Rate,Dept EBT Eligible,Dept Age VR\n");

            // Write data
            for (ProductManagementService.ProductInfo info : allProducts) {
                // Get department info
                String deptName = "";
                boolean deptTaxEnabled = true;
                Double deptTaxRate = null;
                boolean deptEbtEligible = false;
                Integer deptAgeVerification = null;

                if (info.product.getDepartmentId() != null) {
                    try {
                        ProductManagementService.Department dept = productService
                                .getDepartmentById(info.product.getDepartmentId());
                        if (dept != null) {
                            deptName = dept.name;
                            deptTaxEnabled = dept.taxEnabled;
                            deptTaxRate = dept.taxRate;
                            deptEbtEligible = dept.ebtEligible;
                            deptAgeVerification = dept.ageVerification;
                        }
                    } catch (SQLException e) {
                        // Ignore
                    }
                }

                // Reorder level was loaded with the product (no extra per-row query).
                int reorderLevel = info.reorderLevel;

                // Determine status
                String status;
                int stock = info.product.getStock();
                status = ProductManagementService.calculateProductStatus(stock, reorderLevel);

                writer.append(String.format("%s,%s,%s,%d,%s,%s,%d,%s,%s,%s,%s,%s\n",
                        escapeCSV(info.product.getName()),
                        escapeCSV(info.sku),
                        escapeCSV(info.product.getBarcode()),
                        info.product.getStock(),
                        escapeCSV(info.product.getPrice().toString()),
                        status,
                        reorderLevel,
                        escapeCSV(deptName),
                        deptTaxEnabled,
                        deptTaxRate != null ? deptTaxRate.toString() : "",
                        deptEbtEligible,
                        deptAgeVerification != null ? deptAgeVerification.toString() : ""));
            }
        }
    }

    private String escapeCSV(String value) {
        if (value == null)
            return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private void handleBarcodeScan(String barcode) {
        Platform.runLater(() -> {
            searchField.setText(barcode);

            // Try exact barcode match first (more accurate for scanner input)
            new Thread(() -> {
                try {
                    ProductManagementService.ProductInfo exactMatch = productService.getProductInfoByBarcode(barcode);
                    Platform.runLater(() -> {
                        if (exactMatch != null) {
                            // Exact match found - display only this product. reorder level
                            // came with the product load, so no DB query on the FX thread.
                            inventoryList.clear();
                            inventoryList.add(new InventoryTableItem(exactMatch, exactMatch.reorderLevel));
                            inventoryTable.setItems(inventoryList);
                            inventoryTable.getSelectionModel().select(0);
                            // Update pagination info
                            totalProducts = 1;
                            totalPages = 1;
                            currentPage = 0;
                            pageInfoLabel.setText("Product found: " + exactMatch.product.getName());
                            ToastNotification.showSuccess("Product found: " + exactMatch.product.getName(),
                                    getScene() != null ? getScene().getWindow() : null);
                        } else {
                            // No exact match - fall back to pattern search
                            ToastNotification.showWarning("No exact barcode match, searching...",
                                    getScene() != null ? getScene().getWindow() : null);
                            searchInventory();
                        }
                    });
                } catch (SQLException e) {
                    Platform.runLater(() -> {
                        // On error, fall back to pattern search
                        searchInventory();
                    });
                }
            }).start();
        });
    }

    // Removed showAlert - now using ToastNotification

    /**
     * Table item wrapper for inventory display
     */
    private static class InventoryTableItem {
        private final String id;
        private final String sku;
        private final ProductManagementService.ProductInfo productInfo;
        private final String department;
        private final String status;
        private final Integer reorderLevel;

        public InventoryTableItem(ProductManagementService.ProductInfo info, Integer reorderLevel) {
            this.id = info.id;
            this.sku = info.sku != null ? info.sku : "";
            this.productInfo = info;
            this.reorderLevel = reorderLevel != null ? reorderLevel : 0;

            // Get department name
            String deptName = "";
            try {
                List<ProductManagementService.Department> departments = ProductManagementService.getInstance()
                        .getDepartments();
                if (info.product.getDepartmentId() != null) {
                    for (ProductManagementService.Department dept : departments) {
                        if (dept.id.equals(info.product.getDepartmentId())) {
                            deptName = dept.name;
                            break;
                        }
                    }
                }
            } catch (SQLException e) {
                // Ignore
            }
            this.department = deptName;

            // Determine status
            int stock = info.product.getStock();
            this.status = ProductManagementService.calculateProductStatus(stock, this.reorderLevel);
        }

        public String getId() {
            return id;
        }

        public String getSku() {
            return sku;
        }

        public String getName() {
            return productInfo.product.getName();
        }

        public String getBarcode() {
            return productInfo.product.getBarcode() != null ? productInfo.product.getBarcode() : "";
        }

        public Integer getStock() {
            return productInfo.product.getStock();
        }

        public String getStatus() {
            return status;
        }

        public Integer getReorderLevel() {
            return reorderLevel;
        }

        public String getDepartment() {
            return department;
        }
    }
}
