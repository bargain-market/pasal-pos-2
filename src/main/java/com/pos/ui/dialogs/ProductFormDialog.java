package com.pos.ui.dialogs;

import com.pos.hardware.HardwareManager;
import com.pos.model.Product;
import com.pos.service.ProductManagementService;

import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.TouchTextField;
import com.pos.ui.keyboard.KeyboardManager;
import com.pos.util.DialogHelper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import com.pos.util.ResponsiveHelper;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Dialog for adding/editing products
 */
public class ProductFormDialog extends Dialog<ProductFormDialog.ProductFormResult> {

    private static final Logger logger = LoggerFactory.getLogger(ProductFormDialog.class);

    private TouchTextField nameField;
    private TouchTextField skuField;
    private TouchTextField barcodeField;
    private TouchTextField priceField;
    private TouchTextField stockField;
    private ComboBox<String> departmentCombo;
    private List<ProductManagementService.Department> departments;

    private Product existingProduct;
    private String existingProductId;
    private String existingSku;
    private ProductManagementService productService;
    private HardwareManager hardwareManager;

    public ProductFormDialog(Product product, String productId, String sku) {
        this(product, productId, sku, null);
    }

    public ProductFormDialog(Product product, String productId, String sku, HardwareManager hardwareManager) {
        this(product, productId, sku, hardwareManager, null);
    }

    public ProductFormDialog(Product product, String productId, String sku, HardwareManager hardwareManager,
            String preFilledBarcode) {
        this.existingProduct = product;
        this.existingProductId = productId;
        this.existingSku = sku;
        this.productService = ProductManagementService.getInstance();
        this.hardwareManager = hardwareManager;

        setTitle(product == null ? "Add Product" : "Edit Product");
        setHeaderText(product == null ? "Enter product details" : "Edit product details");
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

        // Create dialog pane
        DialogPane dialogPane = getDialogPane();
        dialogPane.setContent(createForm());
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Set button actions
        // Set validation on OK button
        Button okButton = (Button) dialogPane.lookupButton(ButtonType.OK);
        okButton.setDefaultButton(true);
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (!validateForm()) {
                e.consume();
            }
        });

        // Add Enter key handler to all input fields so Enter submits the form
        setupEnterKeyHandler(nameField, okButton);
        setupEnterKeyHandler(skuField, okButton);
        setupEnterKeyHandler(barcodeField, okButton);
        setupEnterKeyHandler(priceField, okButton);
        setupEnterKeyHandler(stockField, okButton);

        // Convert result
        setResultConverter(dialogButton -> {
            if (dialogButton == ButtonType.OK) {
                return createResult();
            }
            return null;
        });

        // Load departments
        loadDepartments();

        // Populate fields if editing
        if (product != null) {
            populateFields();
        }

        // Set up barcode scanner callback if hardware manager is available
        if (hardwareManager != null && product == null) { // Only for new products
            setupBarcodeScanner();
        }

        // Pre-fill barcode if provided
        if (preFilledBarcode != null && !preFilledBarcode.isEmpty()) {
            barcodeField.setText(preFilledBarcode);
            // Also focus the name field since barcode is already provided
            Platform.runLater(() -> nameField.getTextField().requestFocus());
        }

        // Clean up scanner callback when dialog is closed
        setOnCloseRequest(e -> cleanupBarcodeScanner());

        // Ensure touch fields use the global floating keyboard
        setupKeyboardSupport();

        // Make responsive - 60% width, 80% height
        ResponsiveHelper.setupResponsiveDialog(this, 0.6, 0.8);
    }

    private GridPane createForm() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        // Name
        grid.add(new Label("Name:"), 0, 0);
        nameField = TouchScreenComponents.createTouchOnlyTextField("Product name");
        // nameField.setTextFieldPrefWidth(300); // Let layout handle width
        grid.add(nameField, 1, 0);
        GridPane.setHgrow(nameField, Priority.ALWAYS);

        // SKU
        grid.add(new Label("SKU:"), 0, 1);
        skuField = TouchScreenComponents.createTouchOnlyTextField("Stock Keeping Unit");
        // skuField.setTextFieldPrefWidth(300);
        grid.add(skuField, 1, 1);
        GridPane.setHgrow(skuField, Priority.ALWAYS);

        // Barcode with Scan button
        grid.add(new Label("Barcode:"), 0, 2);
        HBox barcodeContainer = new HBox(10);
        barcodeContainer.setAlignment(Pos.CENTER_LEFT);
        barcodeField = TouchScreenComponents.createTouchOnlyTextField("Barcode");
        // barcodeField.setTextFieldPrefWidth(250);
        // Barcode field inside container, grow container
        HBox.setHgrow(barcodeField, Priority.ALWAYS);
        // Enable keyboard input for barcode field to support USB barcode scanners in
        // keyboard wedge mode
        barcodeField.setKeyboardEnabled(true);
        // Handle Enter key press (barcode scanner typically sends Enter after barcode)
        barcodeField.getTextField().setOnAction(e -> {
            String barcode = barcodeField.getText();
            if (barcode != null && !barcode.trim().isEmpty() && existingProduct == null) {
                checkBarcodeAndNotify(barcode.trim());
            }
        });
        barcodeContainer.getChildren().add(barcodeField);

        // Add Scan Barcode button (only for new products)
        if (existingProduct == null && hardwareManager != null) {
            Button scanButton = new Button("Scan");
            scanButton.setStyle(
                    "-fx-background-color: #2196F3; " +
                            "-fx-text-fill: white; " +
                            "-fx-font-size: 12px; " +
                            "-fx-padding: 8 16; " +
                            "-fx-background-radius: 5; " +
                            "-fx-cursor: hand;");
            scanButton.setOnAction(e -> handleScanButtonClick());
            barcodeContainer.getChildren().add(scanButton);
        }

        grid.add(barcodeContainer, 1, 2);

        // Cash Price
        grid.add(new Label("Cash Price:"), 0, 3);
        priceField = TouchScreenComponents.createTouchOnlyNumericField("0.00");
        // priceField.setTextFieldPrefWidth(300);
        grid.add(priceField, 1, 3);
        GridPane.setHgrow(priceField, Priority.ALWAYS);

        // Stock
        grid.add(new Label("Stock:"), 0, 4);
        stockField = TouchScreenComponents.createTouchOnlyNumericField("0");
        // stockField.setTextFieldPrefWidth(300);
        grid.add(stockField, 1, 4);
        GridPane.setHgrow(stockField, Priority.ALWAYS);

        // Department
        grid.add(new Label("Department:"), 0, 5);
        departmentCombo = new ComboBox<>();
        departmentCombo.setPromptText("Select department");
        departmentCombo.setMaxWidth(Double.MAX_VALUE); // Fill width
        grid.add(departmentCombo, 1, 5);
        GridPane.setHgrow(departmentCombo, Priority.ALWAYS);

        return grid;
    }

    /**
     * Set up Enter key handler for a TouchTextField to submit the form instead of
     * typing Enter into the field.
     */
    private void setupEnterKeyHandler(TouchTextField field, Button okButton) {
        if (field == null || okButton == null) {
            return;
        }
        field.getTextField().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                e.consume();
                okButton.fire();
            }
        });
    }

    /**
     * Wire touch text fields to show the global floating keyboard.
     */
    private void setupKeyboardSupport() {
        if (nameField != null) {
            nameField.getTextField().setOnMouseClicked(e -> {
                KeyboardManager.showFor(nameField.getTextField());
            });
        }
        if (skuField != null) {
            skuField.getTextField().setOnMouseClicked(e -> {
                KeyboardManager.showFor(skuField.getTextField());
            });
        }
        if (barcodeField != null) {
            barcodeField.getTextField().setOnMouseClicked(e -> {
                KeyboardManager.showFor(barcodeField.getTextField());
            });
        }
        if (priceField != null) {
            priceField.getTextField().setOnMouseClicked(e -> {
                KeyboardManager.showFor(priceField.getTextField());
            });
        }

        if (stockField != null) {
            stockField.getTextField().setOnMouseClicked(e -> {
                KeyboardManager.showFor(stockField.getTextField());
            });
        }
    }

    private void loadDepartments() {
        try {
            this.departments = productService.getDepartments();
            departmentCombo.getItems().clear();
            departmentCombo.getItems().add(""); // Empty option
            if (this.departments != null) {
                for (ProductManagementService.Department dept : this.departments) {
                    departmentCombo.getItems().add(dept.name);
                }
            }
        } catch (SQLException e) {
            logger.error("Error loading departments", e);
        }
    }

    private void populateFields() {
        if (existingProduct != null) {
            nameField.setText(existingProduct.getName());
            skuField.setText(existingSku != null ? existingSku : "");
            barcodeField.setText(existingProduct.getBarcode() != null ? existingProduct.getBarcode() : "");
            priceField.setText(existingProduct.getPrice() != null ? existingProduct.getPrice().toString() : "");
            stockField.setText(String.valueOf(existingProduct.getStock()));

            // Set department
            if (existingProduct.getDepartmentId() != null && this.departments != null) {
                for (ProductManagementService.Department dept : this.departments) {
                    if (dept.id.equals(existingProduct.getDepartmentId())) {
                        departmentCombo.setValue(dept.name);
                        break;
                    }
                }
            }
        }
    }

    private boolean validateForm() {
        // Validate product name
        String nameText = nameField.getText();
        // #region agent log
        try {
            PrintWriter logWriter = new PrintWriter(new FileWriter(
                    "/Users/sanjog/projects/retail_solutions/product/mobile/pos-system/.cursor/debug.log", true));
            logWriter.println("{\"id\":\"log_" + System.currentTimeMillis() + "_product_validate\",\"timestamp\":"
                    + System.currentTimeMillis()
                    + ",\"location\":\"ProductFormDialog.java:179\",\"message\":\"Validating product form\",\"data\":{\"nameText\":\""
                    + (nameText != null ? nameText : "null") + "\",\"priceText\":\""
                    + (priceField.getText() != null ? priceField.getText() : "null") + "\",\"stockText\":\""
                    + (stockField.getText() != null ? stockField.getText() : "null")
                    + "\",\"runId\":\"validation-fix\"},\"sessionId\":\"debug-session\"}");
            logWriter.close();
        } catch (Exception e) {
        }
        // #endregion
        if (nameText == null || nameText.trim().isEmpty()) {
            showAlert("Validation Error", "Product name is required and cannot be empty");
            return false;
        }

        String trimmedName = nameText.trim();
        if (trimmedName.isEmpty()) {
            showAlert("Validation Error", "Product name cannot be only whitespace");
            return false;
        }

        // Validate cash price
        String priceText = priceField.getText();
        if (priceText == null || priceText.trim().isEmpty()) {
            showAlert("Validation Error", "Cash price is required and cannot be empty");
            return false;
        }

        try {
            BigDecimal price = new BigDecimal(priceText.trim());
            if (price.compareTo(BigDecimal.ZERO) < 0) {
                showAlert("Validation Error", "Price cannot be negative");
                return false;
            }
        } catch (NumberFormatException e) {
            showAlert("Validation Error", "Invalid price format");
            return false;
        }

        // Validate stock (defaults to 0 if empty). Negative values mean stock is not
        // tracked/updated for this product.
        String stockText = stockField.getText();
        if (stockText != null && !stockText.trim().isEmpty()) {
            try {
                Integer.parseInt(stockText.trim());
            } catch (NumberFormatException e) {
                showAlert("Validation Error", "Invalid stock format");
                return false;
            }
        }

        // Validate barcode uniqueness
        String barcodeText = barcodeField.getText();
        if (barcodeText != null && !barcodeText.trim().isEmpty()) {
            try {
                String barcode = barcodeText.trim();
                if (productService.isBarcodeDuplicate(barcode, existingProductId)) {
                    showAlert("Validation Error",
                            "A product with barcode '" + barcode + "' already exists. Barcodes must be unique.");
                    return false;
                }
            } catch (SQLException e) {
                logger.error("Error checking barcode uniqueness", e);
                showAlert("Validation Error", "Failed to validate barcode: " + e.getMessage());
                return false;
            }
        }

        return true;
    }

    private ProductFormResult createResult() {
        String name = nameField.getText().trim();
        if (name == null || name.isEmpty()) {
            throw new IllegalStateException("Product name cannot be null or empty");
        }

        String sku = skuField.getText() != null ? skuField.getText().trim() : "";
        String barcode = barcodeField.getText() != null ? barcodeField.getText().trim() : "";

        String priceText = priceField.getText().trim();
        if (priceText == null || priceText.isEmpty()) {
            throw new IllegalStateException("Cash price cannot be null or empty");
        }
        BigDecimal price = new BigDecimal(priceText);

        int stock = 0;
        String stockText = stockField.getText();
        if (stockText != null && !stockText.trim().isEmpty()) {
            try {
                stock = Integer.parseInt(stockText.trim());
            } catch (NumberFormatException e) {
                stock = 0;
            }
        }

        String selectedDeptName = departmentCombo.getValue();
        String departmentId = null;
        if (selectedDeptName != null && !selectedDeptName.isEmpty() && this.departments != null) {
            for (ProductManagementService.Department dept : this.departments) {
                if (dept.name.equals(selectedDeptName)) {
                    departmentId = dept.id;
                    break;
                }
            }
        }

        Product product = new Product(barcode, name, price, stock, departmentId);
        return new ProductFormResult(product, sku, existingProductId);
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogHelper.setAlertOwner(alert,
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
        alert.showAndWait();
    }

    private void showInfoAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogHelper.setAlertOwner(alert,
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
        alert.showAndWait();
    }

    /**
     * Set up barcode scanner callback
     */
    private void setupBarcodeScanner() {
        if (hardwareManager == null) {
            return;
        }

        // Save previous callback to restore later
        try {
            // We can't directly get the previous callback, so we'll just set ours
            // The caller should manage the callback lifecycle
            hardwareManager.setScanCallback(this::handleBarcodeScan);
        } catch (Exception e) {
            logger.warn("Could not set up barcode scanner: {}", e.getMessage());
        }
    }

    /**
     * Clean up barcode scanner callback
     */
    private void cleanupBarcodeScanner() {
        if (hardwareManager != null) {
            // Clear the callback by setting it to null
            hardwareManager.setScanCallback(null);
        }
    }

    /**
     * Handle scan button click - prompts user to scan
     */
    private void handleScanButtonClick() {
        if (hardwareManager == null || !hardwareManager.hasScanner()) {
            showInfoAlert("Scanner Not Available",
                    "Barcode scanner is not available. Please enter the barcode manually.");
            return;
        }

        showInfoAlert("Ready to Scan",
                "Please scan the barcode now. The scanner will automatically detect it.");
    }

    /**
     * Handle barcode scan event
     */
    private void handleBarcodeScan(String barcode) {
        if (barcode == null || barcode.trim().isEmpty()) {
            return;
        }

        Platform.runLater(() -> {
            // Check if product already exists
            new Thread(() -> {
                try {
                    Product existingProduct = productService.getProductByBarcode(barcode.trim());

                    Platform.runLater(() -> {
                        if (existingProduct != null) {
                            // Product exists - show alert and offer to edit
                            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                            alert.setTitle("Product Exists");
                            alert.setHeaderText("Product Found");
                            alert.setContentText(
                                    "A product with barcode '" + barcode + "' already exists.\n" +
                                            "Product: " + existingProduct.getName() + "\n\n" +
                                            "Would you like to edit this product instead?");
                            DialogHelper.setAlertOwner(alert,
                                    getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);

                            Optional<ButtonType> result = alert.showAndWait();
                            if (result.isPresent() && result.get() == ButtonType.OK) {
                                // User wants to edit - close this dialog and let caller handle editing
                                // We'll need to signal this somehow, but for now just close
                                close();
                            }
                            // If user clicks Cancel, they can continue adding a new product
                        } else {
                            // Product doesn't exist - pre-fill barcode field
                            barcodeField.setText(barcode.trim());
                            logger.info("Barcode scanned and pre-filled: {}", barcode);
                        }
                    });
                } catch (SQLException e) {
                    logger.error("Error checking product existence for barcode: {}", barcode, e);
                    Platform.runLater(() -> {
                        showAlert("Error", "Failed to check if product exists: " + e.getMessage());
                    });
                }
            }).start();
        });
    }

    /**
     * Check if scanned barcode already exists and notify user (for keyboard wedge
     * mode scanners)
     */
    private void checkBarcodeAndNotify(String barcode) {
        new Thread(() -> {
            try {
                Product existingProduct = productService.getProductByBarcode(barcode);

                Platform.runLater(() -> {
                    if (existingProduct != null) {
                        // Product exists - show alert and offer to edit
                        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                        alert.setTitle("Product Exists");
                        alert.setHeaderText("Product Found");
                        alert.setContentText(
                                "A product with barcode '" + barcode + "' already exists.\n" +
                                        "Product: " + existingProduct.getName() + "\n\n" +
                                        "Would you like to edit this product instead?");
                        DialogHelper.setAlertOwner(alert,
                                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);

                        Optional<ButtonType> result = alert.showAndWait();
                        if (result.isPresent() && result.get() == ButtonType.OK) {
                            close();
                        }
                    } else {
                        // Product doesn't exist - show success info
                        logger.info("Barcode scanned and verified (new product): {}", barcode);
                    }
                });
            } catch (SQLException e) {
                logger.error("Error checking product existence for barcode: {}", barcode, e);
            }
        }).start();
    }

    /**
     * Result class for product form dialog
     */
    public static class ProductFormResult {
        public final Product product;
        public final String sku;
        public final String productId; // null for new products

        public ProductFormResult(Product product, String sku, String productId) {
            this.product = product;
            this.sku = sku;
            this.productId = productId;
        }
    }
}
