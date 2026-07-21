package com.pos.ui;

import com.pos.hardware.HardwareManager;
import com.pos.model.Product;
import com.pos.service.ProductManagementService;
import com.pos.service.RoleBasedAccessService;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.dialogs.CategoryFormDialog;
import com.pos.ui.dialogs.ProductFormDialog;
import com.pos.ui.dialogs.StockAdjustmentDialog;
import com.pos.util.DialogHelper;
import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pos.sync.WebSocketClient;
import com.pos.sync.WebSocketMessageHandler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Product Management View - Full CRUD interface for products
 */
public class ProductManagementView extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(ProductManagementView.class);

    private ProductManagementService productService;
    private HardwareManager hardwareManager;
    private RoleBasedAccessService rbacService;
    private ObservableList<ProductTableItem> productList;
    private TableView<ProductTableItem> productTable;
    private com.pos.ui.components.TouchTextField searchField;
    private ComboBox<String> departmentFilter;
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
    private String currentDepartmentId = null;

    // Pagination UI components
    private HBox paginationControls;
    private Button prevPageBtn;
    private Button nextPageBtn;
    private Label pageInfoLabel;
    private ProgressIndicator loadingIndicator;
    private StackPane tableContainer;

    public ProductManagementView(HardwareManager hardwareManager) {
        this(hardwareManager, null);
    }

    public ProductManagementView(HardwareManager hardwareManager, Runnable onBackToSales) {
        this.hardwareManager = hardwareManager;
        this.productService = ProductManagementService.getInstance();
        this.rbacService = RoleBasedAccessService.getInstance();
        this.productList = FXCollections.observableArrayList();
        this.onBackToSales = onBackToSales;

        initializeUI();
        loadProducts();
        loadDepartments();
        setupWebSocketListeners();

        // Set up barcode scanner callback
        if (hardwareManager != null) {
            hardwareManager.setScanCallback(this::handleBarcodeScan);
        }

        // Setup scanner input listener
        setupScannerInput();
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
                    logger.info("Scanner input detected (Enter) in ProductManagementView: {}", barcode);

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

    private void setupWebSocketListeners() {
        try {
            WebSocketClient wsClient = WebSocketClient.getInstance();
            WebSocketMessageHandler messageHandler = wsClient.getMessageHandler();

            // Listen for product changes (create, update, delete)
            messageHandler.addProductEventListener(event -> {
                logger.info("📦 Product event received in ProductManagement: {} - {}",
                        event.getAction(), event.getProductName());
                Platform.runLater(this::loadProducts);
            });

            // Listen for stock updates
            messageHandler.addStockEventListener(event -> {
                logger.info("📊 Stock event received in ProductManagement: {} ({} -> {})",
                        event.getProductId(), event.getPreviousQuantity(), event.getNewQuantity());
                Platform.runLater(this::loadProducts);
            });

            // Listen for price changes
            messageHandler.addPriceEventListener(event -> {
                logger.info("💰 Price event received in ProductManagement: {} ({} -> {})",
                        event.getProductId(), event.getOldPrice(), event.getNewPrice());
                Platform.runLater(this::loadProducts);
            });

            // Listen for department changes
            messageHandler.addDepartmentEventListener(event -> {
                logger.info("🏷️ Department event received in ProductManagement: {} - {}",
                        event.getAction(), event.getDepartmentName());
                Platform.runLater(() -> {
                    loadProducts();
                    loadDepartments();
                });
            });

            logger.info("✅ WebSocket listeners registered for ProductManagementView");
        } catch (Exception e) {
            logger.error("Failed to setup WebSocket listeners in ProductManagementView", e);
        }
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #f5f7fa;");

        // Top section - Title and search
        VBox topSection = createTopSection();
        setTop(topSection);

        // Center - Product table with loading indicator
        tableContainer = new StackPane();

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setContent(createProductTable());
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

        prevPageBtn = new Button("← Previous");
        prevPageBtn.setStyle(
                "-fx-background-color: #2a5298; -fx-text-fill: white; -fx-background-radius: 5; -fx-padding: 8 16; -fx-cursor: hand;");
        prevPageBtn.setOnAction(e -> loadPreviousPage());
        prevPageBtn.setDisable(true);

        pageInfoLabel = new Label("Page 1 of 1 (0 products)");
        pageInfoLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #666;");

        nextPageBtn = new Button("Next →");
        nextPageBtn.setStyle(
                "-fx-background-color: #2a5298; -fx-text-fill: white; -fx-background-radius: 5; -fx-padding: 8 16; -fx-cursor: hand;");
        nextPageBtn.setOnAction(e -> loadNextPage());
        nextPageBtn.setDisable(true);

        controls.getChildren().addAll(prevPageBtn, pageInfoLabel, nextPageBtn);
        return controls;
    }

    /**
     * Load the previous page of products
     */
    private void loadPreviousPage() {
        if (currentPage > 0) {
            currentPage--;
            loadProducts();
        }
    }

    /**
     * Load the next page of products
     */
    private void loadNextPage() {
        if (currentPage < totalPages - 1) {
            currentPage++;
            loadProducts();
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
            productTable.setOpacity(loading ? 0.5 : 1.0);
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
        Label titleLabel = new Label("Product Management");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        titleRow.getChildren().add(titleLabel);

        // Search and filter section
        HBox searchSection = new HBox(10);
        searchSection.setAlignment(Pos.CENTER_LEFT);

        Label searchLabel = new Label("Search:");
        searchField = TouchScreenComponents.createTouchOnlyTextField("Search by name, SKU, or barcode");
        // searchField.setTextFieldPrefWidth(300);
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> searchProducts());

        Label deptLabel = new Label("Department:");
        departmentFilter = new ComboBox<>();
        departmentFilter.setPromptText("All Departments");
        // departmentFilter.setPrefWidth(200);
        departmentFilter.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(departmentFilter, Priority.ALWAYS);
        departmentFilter.setOnAction(e -> filterByDepartment());

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> loadProducts());

        searchSection.getChildren().addAll(searchLabel, searchField, deptLabel, departmentFilter, refreshButton);

        topSection.getChildren().addAll(titleRow, searchSection);

        return topSection;
    }

    private TableView<ProductTableItem> createProductTable() {
        // #region agent log
        try {
            PrintWriter logWriter = new PrintWriter(new FileWriter(
                    "/Users/sanjog/projects/retail_solutions/product/mobile/pos-system/.cursor/debug.log", true));
            logWriter.println("{\"id\":\"log_" + System.currentTimeMillis() + "_product_table_create\",\"timestamp\":"
                    + System.currentTimeMillis()
                    + ",\"location\":\"ProductManagementView.java:142\",\"message\":\"Creating product table\",\"data\":{\"runId\":\"product-fix\"},\"sessionId\":\"debug-session\"}");
            logWriter.close();
        } catch (Exception e) {
        }
        // #endregion
        productTable = new TableView<>();
        productTable.setItems(productList);
        productTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        // Name column
        TableColumn<ProductTableItem, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(cellData -> {
            ProductTableItem item = cellData.getValue();
            return item != null ? new SimpleStringProperty(item.getName()) : new SimpleStringProperty("");
        });
        nameCol.setPrefWidth(200);

        // SKU column
        TableColumn<ProductTableItem, String> skuCol = new TableColumn<>("SKU");
        skuCol.setCellValueFactory(cellData -> {
            ProductTableItem item = cellData.getValue();
            return item != null ? new SimpleStringProperty(item.getSku()) : new SimpleStringProperty("");
        });
        skuCol.setPrefWidth(120);

        // Barcode column
        TableColumn<ProductTableItem, String> barcodeCol = new TableColumn<>("Barcode");
        barcodeCol.setCellValueFactory(cellData -> {
            ProductTableItem item = cellData.getValue();
            return item != null ? new SimpleStringProperty(item.getBarcode()) : new SimpleStringProperty("");
        });
        barcodeCol.setPrefWidth(150);

        // Cash Price column
        TableColumn<ProductTableItem, String> priceCol = new TableColumn<>("Cash Price");
        priceCol.setCellValueFactory(cellData -> {
            ProductTableItem item = cellData.getValue();
            return item != null ? new SimpleStringProperty(item.getCashPrice()) : new SimpleStringProperty("");
        });
        priceCol.setPrefWidth(100);

        // Stock column
        TableColumn<ProductTableItem, Integer> stockCol = new TableColumn<>("Stock");
        stockCol.setCellValueFactory(cellData -> {
            ProductTableItem item = cellData.getValue();
            return item != null ? new SimpleIntegerProperty(item.getStock()).asObject()
                    : new SimpleIntegerProperty(0).asObject();
        });
        stockCol.setPrefWidth(80);
        stockCol.setCellFactory(column -> new TableCell<ProductTableItem, Integer>() {
            @Override
            protected void updateItem(Integer stock, boolean empty) {
                super.updateItem(stock, empty);
                if (empty || stock == null) {
                    setText(null);
                    setStyle("");
                } else {
                    if (stock < 0) {
                        setText("Not Updated");
                        setStyle("-fx-text-fill: #607d8b; -fx-font-weight: bold;");
                    } else {
                        setText(String.valueOf(stock));
                        if (stock == 0) {
                            setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                        } else if (stock < 10) {
                            setStyle("-fx-text-fill: orange;");
                        } else {
                            setStyle("-fx-text-fill: green;");
                        }
                    }
                }
            }
        });

        // Department column
        TableColumn<ProductTableItem, String> deptCol = new TableColumn<>("Department");
        deptCol.setCellValueFactory(cellData -> {
            ProductTableItem item = cellData.getValue();
            return item != null ? new SimpleStringProperty(item.getDepartment()) : new SimpleStringProperty("");
        });
        deptCol.setPrefWidth(150);

        productTable.getColumns().addAll(nameCol, skuCol, barcodeCol, priceCol, stockCol, deptCol);

        // Double-click to edit
        productTable.setRowFactory(tv -> {
            TableRow<ProductTableItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    editProduct(row.getItem());
                }
            });
            return row;
        });

        return productTable;
    }

    /**
     * Create a styled icon button with text label for responsive layout
     */
    private Button createIconButton(String icon, String text, String color) {
        VBox content = new VBox(4);
        content.setAlignment(Pos.CENTER);

        Label iconLabel = new Label(icon);
        iconLabel.setStyle("-fx-font-size: 20px; -fx-text-fill: white;");

        Label textLabel = new Label(text);
        textLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: white;");

        content.getChildren().addAll(iconLabel, textLabel);

        Button button = new Button();
        button.setGraphic(content);
        button.setMinWidth(75);
        button.setMinHeight(60);
        button.setPrefWidth(85);
        button.setPrefHeight(65);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setStyle(
                "-fx-background-color: " + color + ";" +
                        "-fx-text-fill: white;" +
                        "-fx-background-radius: 8;" +
                        "-fx-cursor: hand;" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 4, 0, 0, 1);");

        // Hover effects
        button.setOnMouseEntered(ev -> {
            button.setStyle(
                    "-fx-background-color: derive(" + color + ", -10%);" +
                            "-fx-text-fill: white;" +
                            "-fx-background-radius: 8;" +
                            "-fx-cursor: hand;" +
                            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 6, 0, 0, 2);");
        });
        button.setOnMouseExited(ev -> {
            button.setStyle(
                    "-fx-background-color: " + color + ";" +
                            "-fx-text-fill: white;" +
                            "-fx-background-radius: 8;" +
                            "-fx-cursor: hand;" +
                            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 4, 0, 0, 1);");
        });

        return button;
    }

    private FlowPane createBottomSection() {
        // Use FlowPane for responsive button layout that wraps on smaller screens
        FlowPane bottomSection = new FlowPane();
        bottomSection.setPadding(new Insets(15));
        bottomSection.setAlignment(Pos.CENTER);
        bottomSection.setHgap(12);
        bottomSection.setVgap(12);
        bottomSection.setStyle("-fx-background-color: white;");

        // Create icon buttons with consistent styling
        Button addButton = createIconButton("\u2795", "Add", "#4CAF50");
        addButton.setOnAction(e -> addProduct());

        // Edit and Delete buttons - Manager+ only
        Button editButton = null;
        Button deleteButton = null;

        if (rbacService.hasPermission(RoleBasedAccessService.PERMISSION_MANAGE_PRODUCTS)) {
            editButton = createIconButton("\u270E", "Edit", "#2196F3");
            editButton.setOnAction(e -> {
                ProductTableItem selected = productTable.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    editProduct(selected);
                } else {
                    showAlert("No Selection", "Please select a product to edit");
                }
            });

            deleteButton = createIconButton("\uD83D\uDDD1", "Delete", "#f44336");
            deleteButton.setOnAction(e -> {
                ProductTableItem selected = productTable.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    deleteProduct(selected);
                } else {
                    showAlert("No Selection", "Please select a product to delete");
                }
            });
        }

        Button adjustStockButton = createIconButton("\uD83D\uDCE6", "Stock", "#FF9800");
        adjustStockButton.setOnAction(e -> {
            ProductTableItem selected = productTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                adjustStock(selected);
            } else {
                showAlert("No Selection", "Please select a product to adjust stock");
            }
        });

        Button importButton = createIconButton("\u21E9", "Import", "#9C27B0");
        importButton.setOnAction(e -> importCSV());

        Button manageDeptButton = createIconButton("\uD83C\uDFF7", "Depts", "#607D8B");
        manageDeptButton.setOnAction(e -> manageDepartments());

        // Add buttons conditionally
        bottomSection.getChildren().add(addButton);
        if (editButton != null) {
            bottomSection.getChildren().add(editButton);
        }
        if (deleteButton != null) {
            bottomSection.getChildren().add(deleteButton);
        }
        bottomSection.getChildren().addAll(adjustStockButton, importButton,
                manageDeptButton);

        return bottomSection;
    }

    private void loadProducts() {
        if (isLoading) {
            logger.debug("Already loading products, skipping request");
            return;
        }

        setLoading(true);

        new Thread(() -> {
            try {
                // Get total count for pagination
                int count = productService.getProductInfoCount(currentDepartmentId);
                totalProducts = count;
                totalPages = (int) Math.ceil((double) count / PAGE_SIZE);

                // Ensure currentPage is valid
                if (currentPage >= totalPages && totalPages > 0) {
                    currentPage = totalPages - 1;
                }

                // Calculate offset
                int offset = currentPage * PAGE_SIZE;

                // Get paginated products
                List<ProductManagementService.ProductInfo> products = productService.getProductInfosByDepartment(
                        currentDepartmentId, offset, PAGE_SIZE);
                logger.debug("Loaded {} products (page {} of {}, total: {})",
                        products.size(), currentPage + 1, totalPages, totalProducts);

                // Resolve department names once on this background thread (not per row on
                // the FX thread) to avoid an N+1 query that froze the UI on large catalogs.
                Map<String, String> deptNameById = buildDepartmentNameMap();

                Platform.runLater(() -> {
                    productList.clear();
                    for (ProductManagementService.ProductInfo info : products) {
                        String deptName = deptNameById.getOrDefault(info.product.getDepartmentId(), "");
                        ProductTableItem item = new ProductTableItem(info, deptName);
                        productList.add(item);
                    }
                    logger.info("Loaded {} products", products.size());
                    setLoading(false);
                });
            } catch (SQLException e) {
                logger.error("Error loading products", e);
                Platform.runLater(() -> {
                    setLoading(false);
                    showAlert("Error", "Failed to load products: " + e.getMessage());
                });
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
                });
            } catch (SQLException e) {
                logger.error("Error loading departments", e);
            }
        }).start();
    }

    /**
     * Build a department id -> name map with a single DB query. Call this off the JavaFX
     * thread before building table rows so department names can be resolved in-memory
     * instead of querying the DB once per row (which froze the UI on large catalogs).
     */
    private Map<String, String> buildDepartmentNameMap() {
        Map<String, String> map = new HashMap<>();
        try {
            for (ProductManagementService.Department dept : productService.getDepartments()) {
                map.put(dept.id, dept.name);
            }
        } catch (SQLException e) {
            logger.warn("Could not load departments for name resolution: {}", e.getMessage());
        }
        return map;
    }

    private void searchProducts() {
        String searchTerm = searchField.getText().trim();
        if (searchTerm.isEmpty()) {
            currentPage = 0;
            loadProducts();
            return;
        }

        setLoading(true);
        new Thread(() -> {
            try {
                List<ProductManagementService.ProductInfo> products = productService.searchProductInfos(searchTerm);
                // Resolve department names once off the FX thread (see loadProducts()).
                Map<String, String> deptNameById = buildDepartmentNameMap();
                Platform.runLater(() -> {
                    productList.clear();
                    for (ProductManagementService.ProductInfo info : products) {
                        String deptName = deptNameById.getOrDefault(info.product.getDepartmentId(), "");
                        productList.add(new ProductTableItem(info, deptName));
                    }
                    // Update pagination for search results
                    totalProducts = products.size();
                    totalPages = 1;
                    currentPage = 0;
                    setLoading(false);
                    pageInfoLabel.setText(String.format("Search results: %d products found", products.size()));
                });
            } catch (SQLException e) {
                logger.error("Error searching products", e);
                Platform.runLater(() -> {
                    setLoading(false);
                    showAlert("Error", "Failed to search products: " + e.getMessage());
                });
            }
        }).start();
    }

    private void filterByDepartment() {
        String selectedDept = departmentFilter.getValue();
        currentPage = 0; // Reset to first page when filtering

        if (selectedDept == null || "All Departments".equals(selectedDept)) {
            currentDepartmentId = null;
            loadProducts();
            return;
        }

        // Find department ID first, then load products
        new Thread(() -> {
            try {
                List<ProductManagementService.Department> departments = productService.getDepartments();

                // Find department ID
                String deptId = null;
                for (ProductManagementService.Department dept : departments) {
                    if (dept.name.equals(selectedDept)) {
                        deptId = dept.id;
                        break;
                    }
                }

                final String finalDeptId = deptId;
                Platform.runLater(() -> {
                    currentDepartmentId = finalDeptId;
                    loadProducts();
                });
            } catch (SQLException e) {
                logger.error("Error filtering products", e);
                Platform.runLater(() -> {
                    showAlert("Error", "Failed to filter products: " + e.getMessage());
                });
            }
        }).start();
    }

    private void addProduct() {
        ProductFormDialog dialog = new ProductFormDialog(null, null, null, hardwareManager);
        Optional<ProductFormDialog.ProductFormResult> result = dialog.showAndWait();

        // Restore scanner callback for ProductManagementView after dialog closes
        if (hardwareManager != null) {
            hardwareManager.setScanCallback(this::handleBarcodeScan);
        }

        result.ifPresent(formResult -> {
            new Thread(() -> {
                try {
                    productService.createProduct(formResult.product, formResult.sku);
                    Platform.runLater(() -> {
                        loadProducts();
                        showAlert("Success", "Product added successfully");
                    });
                } catch (SQLException e) {
                    logger.error("Error creating product", e);
                    Platform.runLater(() -> {
                        showAlert("Error", "Failed to create product: " + e.getMessage());
                    });
                }
            }).start();
        });
    }

    private void editProduct(ProductTableItem item) {
        // Permission check - Manager+ required
        if (!rbacService.hasPermission(RoleBasedAccessService.PERMISSION_MANAGE_PRODUCTS)) {
            showAlert("Permission Denied",
                    "You do not have permission to edit products. Manager or Admin role required.");
            return;
        }

        ProductFormDialog dialog = new ProductFormDialog(item.getProduct(), item.getId(), item.getSku());
        Optional<ProductFormDialog.ProductFormResult> result = dialog.showAndWait();

        // Restore scanner callback
        if (hardwareManager != null) {
            hardwareManager.setScanCallback(this::handleBarcodeScan);
        }

        result.ifPresent(formResult -> {
            new Thread(() -> {
                try {
                    productService.updateProduct(formResult.productId, formResult.product, formResult.sku);
                    Platform.runLater(() -> {
                        loadProducts();
                        showAlert("Success", "Product updated successfully");
                    });
                } catch (SQLException e) {
                    logger.error("Error updating product", e);
                    Platform.runLater(() -> {
                        showAlert("Error", "Failed to update product: " + e.getMessage());
                    });
                }
            }).start();
        });
    }

    private void deleteProduct(ProductTableItem item) {
        // Permission check - Manager+ required
        if (!rbacService.hasPermission(RoleBasedAccessService.PERMISSION_MANAGE_PRODUCTS)) {
            showAlert("Permission Denied",
                    "You do not have permission to delete products. Manager or Admin role required.");
            return;
        }

        Alert confirmDialog = new Alert(Alert.AlertType.CONFIRMATION);
        confirmDialog.setTitle("Delete Product");
        confirmDialog.setHeaderText("Confirm Deletion");
        confirmDialog.setContentText("Are you sure you want to delete '" + item.getName() + "'?");
        DialogHelper.setAlertOwner(confirmDialog, null);

        Optional<ButtonType> result = confirmDialog.showAndWait();

        // Restore scanner callback
        if (hardwareManager != null) {
            hardwareManager.setScanCallback(this::handleBarcodeScan);
        }

        if (result.isPresent() && result.get() == ButtonType.OK) {
            new Thread(() -> {
                try {
                    productService.deleteProduct(item.getId());
                    Platform.runLater(() -> {
                        loadProducts();
                        showAlert("Success", "Product deleted successfully");
                    });
                } catch (SQLException e) {
                    logger.error("Error deleting product", e);
                    Platform.runLater(() -> {
                        showAlert("Error", "Failed to delete product: " + e.getMessage());
                    });
                }
            }).start();
        }
    }

    private void adjustStock(ProductTableItem item) {
        StockAdjustmentDialog dialog = new StockAdjustmentDialog(item.getStock());
        Optional<StockAdjustmentDialog.StockAdjustmentResult> result = dialog.showAndWait();

        // Restore scanner callback
        if (hardwareManager != null) {
            hardwareManager.setScanCallback(this::handleBarcodeScan);
        }

        result.ifPresent(adjustmentResult -> {
            new Thread(() -> {
                try {
                    // Use InventoryService for better tracking
                    com.pos.service.InventoryService inventoryService = com.pos.service.InventoryService.getInstance();
                    inventoryService.adjustStock(item.getId(), adjustmentResult.adjustment,
                            adjustmentResult.changeType, adjustmentResult.reason);
                    Platform.runLater(() -> {
                        loadProducts();
                        showAlert("Success", "Stock adjusted successfully");
                    });
                } catch (SQLException e) {
                    logger.error("Error adjusting stock", e);
                    Platform.runLater(() -> {
                        showAlert("Error", "Failed to adjust stock: " + e.getMessage());
                    });
                }
            }).start();
        });
    }

    private void importCSV() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select CSV File");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV Files", "*.csv"));

        Stage stage = (Stage) getScene().getWindow();
        File file = fileChooser.showOpenDialog(stage);

        // Restore scanner callback
        if (hardwareManager != null) {
            hardwareManager.setScanCallback(this::handleBarcodeScan);
        }

        if (file != null) {
            new Thread(() -> {
                try {
                    int imported = importProductsFromCSV(file);
                    Platform.runLater(() -> {
                        loadProducts();
                        showAlert("Import Complete", String.format("Imported %d products from CSV", imported));
                    });
                } catch (Exception e) {
                    logger.error("Error importing CSV", e);
                    Platform.runLater(() -> {
                        showAlert("Error", "Failed to import CSV: " + e.getMessage());
                    });
                }
            }).start();
        }
    }

    private int importProductsFromCSV(File file) throws Exception {
        int imported = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            boolean firstLine = true;

            while ((line = reader.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    continue; // Skip header
                }

                String[] parts = line.split(",");
                if (parts.length < 4)
                    continue;

                try {
                    String name = parts[0].trim();
                    String sku = parts.length > 1 ? parts[1].trim() : "";
                    String barcode = parts.length > 2 ? parts[2].trim() : "";
                    BigDecimal price = new BigDecimal(parts[3].trim());
                    int stock = parts.length > 5 ? Integer.parseInt(parts[5].trim()) : 0;

                    Product product = new Product(barcode, name, price, stock, null);
                    productService.createProduct(product, sku);
                    imported++;
                } catch (Exception e) {
                    logger.warn("Failed to import line: {}", line, e);
                }
            }
        }

        return imported;
    }

    private void manageDepartments() {
        // Department management dialog with full features
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Manage Departments");
        dialog.setHeaderText("Department Management");
        dialog.initModality(Modality.APPLICATION_MODAL);

        // Set owner and make responsive using DialogHelper
        DialogHelper.setDialogOwner(dialog, getScene() != null ? getScene().getWindow() : null);

        // Create a table view for departments with more details
        TableView<DepartmentTableItem> deptTable = new TableView<>();
        deptTable.setPrefHeight(300);
        deptTable.setPrefWidth(600);

        // Name column
        TableColumn<DepartmentTableItem, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(
                cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().name));
        nameCol.setPrefWidth(150);

        // Type column
        TableColumn<DepartmentTableItem, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(
                cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().departmentType));
        typeCol.setPrefWidth(80);

        // Tax column
        TableColumn<DepartmentTableItem, String> taxCol = new TableColumn<>("Tax");
        taxCol.setCellValueFactory(
                cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().taxInfo));
        taxCol.setPrefWidth(100);

        // Features column
        TableColumn<DepartmentTableItem, String> featuresCol = new TableColumn<>("Features");
        featuresCol.setCellValueFactory(
                cellData -> new javafx.beans.property.SimpleStringProperty(cellData.getValue().features));
        featuresCol.setPrefWidth(200);

        deptTable.getColumns().addAll(nameCol, typeCol, taxCol, featuresCol);

        // Load departments into table
        Runnable refreshDepartments = () -> {
            try {
                List<ProductManagementService.Department> departments = productService.getDepartments();
                deptTable.getItems().clear();
                for (ProductManagementService.Department dept : departments) {
                    deptTable.getItems().add(new DepartmentTableItem(dept));
                }
            } catch (SQLException e) {
                logger.error("Error loading departments", e);
            }
        };
        refreshDepartments.run();

        // Button bar
        Button addDeptButton = new Button("Add Department");
        addDeptButton.setOnAction(e -> {
            CategoryFormDialog deptDialog = new CategoryFormDialog(null, null);
            Optional<CategoryFormDialog.CategoryFormResult> result = deptDialog.showAndWait();
            result.ifPresent(formResult -> {
                new Thread(() -> {
                    try {
                        productService.createDepartment(
                                formResult.name,
                                formResult.icon,
                                formResult.parentId,
                                formResult.departmentType,
                                formResult.taxEnabled,
                                formResult.taxRate,
                                formResult.hideOnRegister,
                                formResult.ebtEligible,
                                formResult.excludeFromGlobalPriceIncrease,
                                formResult.noPointsEarning,
                                formResult.ageVerification,
                                false, // multipackEnabled
                                null, // multipackDiscountType
                                null, // multipackDiscountValue
                                null, // multipackMinQuantity
                                false // multipackRequiresApproval
                        );
                        Platform.runLater(() -> {
                            refreshDepartments.run();
                            loadDepartments();
                            showAlert("Success", "Department created successfully");
                        });
                    } catch (SQLException ex) {
                        logger.error("Error creating department", ex);
                        Platform.runLater(() -> {
                            showAlert("Error", "Failed to create department: " + ex.getMessage());
                        });
                    }
                }).start();
            });
        });

        Button editDeptButton = new Button("Edit Department");
        editDeptButton.setDisable(true);
        editDeptButton.setOnAction(e -> {
            DepartmentTableItem selected = deptTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                CategoryFormDialog deptDialog = new CategoryFormDialog(selected.id, selected.name);
                Optional<CategoryFormDialog.CategoryFormResult> result = deptDialog.showAndWait();
                result.ifPresent(formResult -> {
                    new Thread(() -> {
                        try {
                            productService.updateDepartment(
                                    selected.id,
                                    formResult.name,
                                    formResult.icon,
                                    formResult.parentId,
                                    formResult.departmentType,
                                    formResult.taxEnabled,
                                    formResult.taxRate,
                                    formResult.hideOnRegister,
                                    formResult.ebtEligible,
                                    formResult.excludeFromGlobalPriceIncrease,
                                    formResult.noPointsEarning,
                                    formResult.ageVerification,
                                    false, // multipackEnabled
                                    null, // multipackDiscountType
                                    null, // multipackDiscountValue
                                    null, // multipackMinQuantity
                                    false // multipackRequiresApproval
                            );
                            Platform.runLater(() -> {
                                refreshDepartments.run();
                                loadDepartments();
                                showAlert("Success", "Department updated successfully");
                            });
                        } catch (SQLException ex) {
                            logger.error("Error updating department", ex);
                            Platform.runLater(() -> {
                                showAlert("Error", "Failed to update department: " + ex.getMessage());
                            });
                        }
                    }).start();
                });
            }
        });

        Button deleteDeptButton = new Button("Delete Department");
        deleteDeptButton.setDisable(true);
        deleteDeptButton.setOnAction(e -> {
            DepartmentTableItem selected = deptTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle("Delete Department");
                confirm.setHeaderText("Are you sure?");
                confirm.setContentText("Delete department '" + selected.name
                        + "'? Products in this department will have their department cleared.");
                DialogHelper.setAlertOwner(confirm, dialog.getDialogPane().getScene().getWindow());

                Optional<ButtonType> confirmResult = confirm.showAndWait();
                if (confirmResult.isPresent() && confirmResult.get() == ButtonType.OK) {
                    new Thread(() -> {
                        try {
                            productService.deleteDepartment(selected.id);
                            Platform.runLater(() -> {
                                refreshDepartments.run();
                                loadDepartments();
                                showAlert("Success", "Department deleted successfully");
                            });
                        } catch (SQLException ex) {
                            logger.error("Error deleting department", ex);
                            Platform.runLater(() -> {
                                showAlert("Error", "Failed to delete department: " + ex.getMessage());
                            });
                        }
                    }).start();
                }
            }
        });

        // Enable/disable edit and delete buttons based on selection
        deptTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            boolean hasSelection = newVal != null;
            editDeptButton.setDisable(!hasSelection);
            deleteDeptButton.setDisable(!hasSelection);
        });

        HBox buttonBar = new HBox(10, addDeptButton, editDeptButton, deleteDeptButton);
        buttonBar.setPadding(new Insets(10, 0, 0, 0));

        VBox content = new VBox(10, deptTable, buttonBar);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();

        // Restore scanner callback
        if (hardwareManager != null) {
            hardwareManager.setScanCallback(this::handleBarcodeScan);
        }
    }

    /**
     * Table item wrapper for departments
     */
    private static class DepartmentTableItem {
        private final String id;
        private final String name;
        private final String departmentType;
        private final String taxInfo;
        private final String features;

        public DepartmentTableItem(ProductManagementService.Department dept) {
            this.id = dept.id;
            this.name = dept.name;
            this.departmentType = dept.departmentType != null ? dept.departmentType : "PRODUCT";

            // Build tax info string
            if (dept.taxEnabled) {
                if (dept.taxRate != null) {
                    this.taxInfo = String.format("%.2f%%", dept.taxRate);
                } else {
                    this.taxInfo = "Store Default";
                }
            } else {
                this.taxInfo = "No Tax";
            }

            // Build features string
            StringBuilder featuresBuilder = new StringBuilder();
            if (dept.hideOnRegister)
                featuresBuilder.append("Hidden, ");
            if (dept.ebtEligible)
                featuresBuilder.append("EBT, ");
            if (dept.excludeFromGlobalPriceIncrease)
                featuresBuilder.append("No Price Inc, ");
            if (dept.noPointsEarning)
                featuresBuilder.append("No Points, ");
            if (dept.ageVerification != null)
                featuresBuilder.append("Age ").append(dept.ageVerification).append("+, ");

            if (featuresBuilder.length() > 0) {
                featuresBuilder.setLength(featuresBuilder.length() - 2); // Remove trailing ", "
                this.features = featuresBuilder.toString();
            } else {
                this.features = "-";
            }
        }
    }

    private void handleBarcodeScan(String barcode) {
        Platform.runLater(() -> {
            searchField.setText(barcode);
            searchProducts();
        });
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogHelper.setAlertOwner(alert, null);
        alert.showAndWait();
    }

    /**
     * Table item wrapper for products
     */
    private static class ProductTableItem {
        private final String id;
        private final String sku;
        private final Product product;
        private final String department;

        public ProductTableItem(ProductManagementService.ProductInfo info, String departmentName) {
            this.id = info.id;
            this.sku = info.sku != null ? info.sku : "";
            this.product = info.product;
            // Department name is resolved once per table load by the caller (from a
            // prebuilt id->name map) rather than querying the DB per row on the FX thread.
            this.department = departmentName != null ? departmentName : "";
        }

        public String getId() {
            return id;
        }

        public String getSku() {
            return sku;
        }

        public String getName() {
            return product.getName();
        }

        public String getBarcode() {
            return product.getBarcode() != null ? product.getBarcode() : "";
        }

        public String getCashPrice() {
            return product.getPrice() != null ? NumberFormat.getCurrencyInstance().format(product.getPrice()) : "";
        }

        public Integer getStock() {
            return product.getStock();
        }

        public String getDepartment() {
            return department;
        }

        public Product getProduct() {
            return product;
        }
    }
}
