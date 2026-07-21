package com.pos.ui.dialogs;

import com.pos.service.ProductManagementService;
import com.pos.util.InventoryCSVParser;
import com.pos.util.InventoryCSVParser.ParsedProduct;
import com.pos.util.InventoryCSVParser.ParseResult;
import com.pos.ui.components.ToastNotification;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Dialog for importing inventory/products from CSV files.
 * Supports the specific format from the inventory export system.
 */
public class InventoryImportDialog extends Dialog<Boolean> {

    private static final Logger logger = LoggerFactory.getLogger(InventoryImportDialog.class);

    private File selectedFile;
    private ParseResult parseResult;
    private Label fileLabel;
    private Label previewLabel;
    private TableView<ParsedProduct> previewTable;
    private CheckBox updateExistingCheckbox;
    private ProgressIndicator progressIndicator;
    private VBox progressBox;
    private Label progressLabel;
    private Button importButton;

    public InventoryImportDialog(Window owner) {
        setTitle("Import Inventory");
        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);

        DialogPane dialogPane = getDialogPane();
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.8, 0.8);
        dialogPane.setStyle("-fx-background-color: #f5f7fa;");

        // Create content
        VBox content = createContent();
        dialogPane.setContent(content);

        // Add buttons
        ButtonType importButtonType = new ButtonType("Import", ButtonBar.ButtonData.OK_DONE);
        dialogPane.getButtonTypes().addAll(importButtonType, ButtonType.CANCEL);

        // Get the import button and configure it
        importButton = (Button) dialogPane.lookupButton(importButtonType);
        importButton.setDisable(true);
        importButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");

        // Handle result
        setResultConverter(buttonType -> {
            if (buttonType == importButtonType) {
                return performImport();
            }
            return false;
        });
    }

    private ComboBox<InventoryCSVParser.ImportFormat> formatComboBox;

    private VBox createContent() {
        VBox content = new VBox(20);
        content.setPadding(new Insets(20));
        content.setStyle("-fx-background-color: white; -fx-background-radius: 5;");

        // Title
        Label titleLabel = new Label("Import Inventory from CSV");
        titleLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #333;");

        // Instructions
        Label instructionsLabel = new Label(
                "Select a CSV file to import products. Ensure you select the correct source format.");
        instructionsLabel.setWrapText(true);
        instructionsLabel.setStyle("-fx-text-fill: #666;");

        // Format selection section
        VBox formatSection = createFormatSection();

        // File selection section
        HBox fileSection = createFileSection();

        // Options section
        HBox optionsSection = createOptionsSection();

        // Preview section
        VBox previewSection = createPreviewSection();
        VBox.setVgrow(previewSection, Priority.ALWAYS);

        // Progress section (hidden initially)
        progressBox = createProgressSection();
        progressBox.setVisible(false);
        progressBox.setManaged(false);

        content.getChildren().addAll(
                titleLabel,
                instructionsLabel,
                formatSection,
                fileSection,
                optionsSection,
                previewSection,
                progressBox);

        return content;
    }

    private VBox createFormatSection() {
        VBox formatSection = new VBox(5);
        Label formatLabel = new Label("Import Source:");
        formatLabel.setStyle("-fx-font-weight: bold;");

        formatComboBox = new ComboBox<>();
        formatComboBox.getItems().addAll(InventoryCSVParser.ImportFormat.values());
        formatComboBox.setValue(InventoryCSVParser.ImportFormat.GENERIC); // Default
        formatComboBox.setMaxWidth(Double.MAX_VALUE);
        formatComboBox.setOnAction(e -> {
            // Re-parse (if file selected) or update instructions based on format
            if (selectedFile != null) {
                parseFile();
            }
        });

        formatSection.getChildren().addAll(formatLabel, formatComboBox);
        return formatSection;
    }

    private HBox createFileSection() {
        HBox fileSection = new HBox(15);
        fileSection.setAlignment(Pos.CENTER_LEFT);

        Button selectFileButton = new Button("📁 Select CSV File");
        selectFileButton.setStyle(
                "-fx-background-color: #2196F3; " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 14px; " +
                        "-fx-font-weight: bold; " +
                        "-fx-padding: 10 20; " +
                        "-fx-background-radius: 5; " +
                        "-fx-cursor: hand;");
        selectFileButton.setOnAction(e -> selectFile());

        fileLabel = new Label("No file selected");
        fileLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 14px;");
        HBox.setHgrow(fileLabel, Priority.ALWAYS);

        fileSection.getChildren().addAll(selectFileButton, fileLabel);
        return fileSection;
    }

    private HBox createOptionsSection() {
        HBox optionsSection = new HBox(20);
        optionsSection.setAlignment(Pos.CENTER_LEFT);
        optionsSection.setPadding(new Insets(10, 0, 10, 0));

        updateExistingCheckbox = new CheckBox("Update existing products (based on barcode)");
        updateExistingCheckbox.setSelected(false);
        updateExistingCheckbox.setStyle("-fx-font-size: 14px;");

        Label noteLabel = new Label("💡 If unchecked, products with duplicate barcodes will be skipped");
        noteLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 12px;");

        optionsSection.getChildren().addAll(updateExistingCheckbox, noteLabel);
        return optionsSection;
    }

    private VBox createPreviewSection() {
        VBox previewSection = new VBox(10);

        previewLabel = new Label("Preview (first 100 products)");
        previewLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        // Create preview table
        previewTable = new TableView<>();
        previewTable.setPlaceholder(new Label("Select a CSV file to preview products"));
        previewTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // SKU column
        TableColumn<ParsedProduct, String> skuCol = new TableColumn<>("SKU/Barcode");
        skuCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().sku));
        skuCol.setPrefWidth(150);

        // Name column
        TableColumn<ParsedProduct, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(data.getValue().name));
        nameCol.setPrefWidth(250);

        // Price column
        TableColumn<ParsedProduct, String> priceCol = new TableColumn<>("Price");
        priceCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                String.format("$%.2f", data.getValue().price)));
        priceCol.setPrefWidth(100);

        // Stock column
        TableColumn<ParsedProduct, String> stockCol = new TableColumn<>("Stock");
        stockCol.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                String.valueOf(data.getValue().stock)));
        stockCol.setPrefWidth(80);

        previewTable.getColumns().addAll(skuCol, nameCol, priceCol, stockCol);
        VBox.setVgrow(previewTable, Priority.ALWAYS);

        previewSection.getChildren().addAll(previewLabel, previewTable);
        return previewSection;
    }

    private VBox createProgressSection() {
        VBox progressSection = new VBox(15);
        progressSection.setAlignment(Pos.CENTER);
        progressSection.setPadding(new Insets(20));
        progressSection.setStyle("-fx-background-color: rgba(0,0,0,0.05); -fx-background-radius: 5;");

        progressIndicator = new ProgressIndicator();
        progressIndicator.setPrefSize(60, 60);

        progressLabel = new Label("Importing products...");
        progressLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #333;");

        progressSection.getChildren().addAll(progressIndicator, progressLabel);
        return progressSection;
    }

    private void selectFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Inventory CSV File");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("CSV Files", "*.csv"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));

        // Set initial directory
        String userHome = System.getProperty("user.home");
        File initialDir = new File(userHome);
        if (initialDir.exists()) {
            fileChooser.setInitialDirectory(initialDir);
        }

        Window owner = getOwner();
        File file = fileChooser.showOpenDialog(owner);

        if (file != null) {
            selectedFile = file;
            fileLabel.setText(file.getName());
            parseFile();
        }
    }

    private void parseFile() {
        if (selectedFile == null)
            return;

        previewTable.getItems().clear();
        previewLabel.setText("Parsing file...");
        importButton.setDisable(true);

        new Thread(() -> {
            try {
                // Get format from UI thread before passing to background thread
                InventoryCSVParser.ImportFormat format = formatComboBox.getValue();
                parseResult = InventoryCSVParser.parseCSV(selectedFile, format);

                Platform.runLater(() -> {
                    if (parseResult.products.isEmpty()) {
                        previewLabel.setText("No products found in file");
                        fileLabel.setText(selectedFile.getName() + " (no valid products)");
                        fileLabel.setStyle("-fx-text-fill: #f44336; -fx-font-size: 14px;");

                        if (parseResult.hasErrors()) {
                            ToastNotification.showWarning(
                                    "Parse errors: " + parseResult.errors.size() + " lines had issues",
                                    getOwner());
                        }
                    } else {
                        // Show preview (first 100 products)
                        int previewCount = Math.min(100, parseResult.products.size());
                        previewTable.getItems().addAll(parseResult.products.subList(0, previewCount));

                        previewLabel.setText(String.format(
                                "Preview (showing %d of %d products)",
                                previewCount,
                                parseResult.products.size()));

                        fileLabel.setText(String.format(
                                "%s (%d products found)",
                                selectedFile.getName(),
                                parseResult.products.size()));
                        fileLabel.setStyle("-fx-text-fill: #4CAF50; -fx-font-size: 14px;");

                        importButton.setDisable(false);

                        if (parseResult.hasErrors()) {
                            logger.warn("Parse completed with {} errors", parseResult.errors.size());
                        }
                    }
                });
            } catch (Exception e) {
                logger.error("Failed to parse CSV file", e);
                Platform.runLater(() -> {
                    previewLabel.setText("Error parsing file");
                    fileLabel.setText(selectedFile.getName() + " (error)");
                    fileLabel.setStyle("-fx-text-fill: #f44336; -fx-font-size: 14px;");
                    ToastNotification.showError("Failed to parse file: " + e.getMessage(), getOwner());
                });
            }
        }).start();
    }

    private boolean performImport() {
        if (parseResult == null || parseResult.products.isEmpty()) {
            ToastNotification.showWarning("No products to import", getOwner());
            return false;
        }

        // Show progress
        progressBox.setVisible(true);
        progressBox.setManaged(true);
        importButton.setDisable(true);
        getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(true);

        // Run import in background
        new Thread(() -> {
            try {
                ProductManagementService productService = ProductManagementService.getInstance();

                // Get or create "Auto" department
                String autoDepartmentId = productService.getOrCreateDepartmentByName("Auto");

                // Perform import with progress tracking
                boolean updateExisting = updateExistingCheckbox.isSelected();
                ProductManagementService.ImportResult result = productService.importParsedProducts(
                        parseResult.products,
                        autoDepartmentId,
                        updateExisting,
                        (processed, total) -> {
                            Platform.runLater(() -> {
                                double progress = (double) processed / total;
                                progressIndicator.setProgress(progress);
                                progressLabel.setText(String.format("Importing: %d / %d products (%.0f%%)",
                                        processed, total, progress * 100));
                            });
                        });

                Platform.runLater(() -> {
                    // Hide progress
                    progressBox.setVisible(false);
                    progressBox.setManaged(false);
                    getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(false);

                    // Show results
                    showImportResults(result);

                    // If at least some imported, we return success to refresh parent
                    if (result.successCount > 0) {
                        setResult(true);
                        close();
                    }
                });

            } catch (Exception e) {
                logger.error("Import failed", e);
                Platform.runLater(() -> {
                    progressBox.setVisible(false);
                    progressBox.setManaged(false);
                    getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(false);
                    ToastNotification.showError("Import failed: " + e.getMessage(), getOwner());
                });
            }
        }).start();

        return false; // Don't close immediately
    }

    private void showImportResults(ProductManagementService.ImportResult result) {
        StringBuilder message = new StringBuilder();
        message.append("Import Complete!\n\n");
        message.append(String.format("✅ Successfully imported: %d\n", result.successCount));
        message.append(String.format("⏭️ Skipped (duplicates): %d\n", result.skippedCount));
        message.append(String.format("❌ Failed: %d\n", result.failedCount));

        Alert.AlertType alertType = result.failedCount > 0 ? Alert.AlertType.WARNING : Alert.AlertType.INFORMATION;

        Alert alert = new Alert(alertType);
        alert.setTitle("Import Results");
        alert.setHeaderText(null);
        alert.setContentText(message.toString());
        alert.initOwner(getOwner());

        if (result.hasErrors() && result.errors.size() <= 10) {
            // Show first few errors in detail
            StringBuilder errors = new StringBuilder("Errors:\n");
            for (String error : result.errors) {
                errors.append("• ").append(error).append("\n");
            }
            alert.setContentText(message.toString() + "\n" + errors.toString());
        }

        alert.showAndWait();
    }
}
