package com.pos.ui.dialogs;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import com.pos.model.Product;
import com.pos.service.SalesService;
import com.pos.ui.components.NumericKeypad;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;
import java.util.List;
import java.util.Optional;

/**
 * Modern-style full-screen product search dialog.
 * Opens when user presses the "Search" button in the function panel.
 */
public class ProductSearchDialog extends Dialog<Product> {

    private static final Logger logger = LoggerFactory.getLogger(ProductSearchDialog.class);
    private final SalesService salesService;

    private TextField searchField;
    private FlowPane resultsGrid;
    private Label statusLabel;
    private ScrollPane resultsScroll;
    private StringBuilder keyboardBuffer = new StringBuilder();
    private VBox keyboardContainer;
    private boolean keyboardVisible = false;

    public ProductSearchDialog(Window owner) {
        this.salesService = SalesService.getInstance();

        initOwner(owner);
        initModality(Modality.APPLICATION_MODAL);
        initStyle(StageStyle.UNDECORATED);

        setTitle("Product Search");

        getDialogPane().getStylesheets().add(
                getClass().getResource("/styles/application.css").toExternalForm());
        getDialogPane().getStyleClass().add("pos-modern-search-dialog");

        // Make dialog nearly full-screen
        // getDialogPane().setPrefWidth(900);
        // getDialogPane().setPrefHeight(700);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.9, 0.9);

        buildUI();
        setupResultConverter();
    }

    private void buildUI() {
        VBox mainContainer = new VBox(0);
        mainContainer.getStyleClass().add("pos-modern-search-container");

        // Header with title and close button
        HBox header = createHeader();

        // Search input area
        HBox searchArea = createSearchArea();

        // Results grid
        VBox resultsArea = createResultsArea();
        VBox.setVgrow(resultsArea, Priority.ALWAYS);

        // On-screen keyboard
        keyboardContainer = createOnScreenKeyboard();
        keyboardContainer.setVisible(true);
        keyboardContainer.setManaged(true);

        mainContainer.getChildren().addAll(header, searchArea, resultsArea, keyboardContainer);

        getDialogPane().setContent(mainContainer);

        // Add a hidden close button type - required for programmatic closing
        getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        // Hide the actual button since we have our own close button in the header
        Button hiddenCancelBtn = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
        if (hiddenCancelBtn != null) {
            hiddenCancelBtn.setVisible(false);
            hiddenCancelBtn.setManaged(false);
        }

        // Initial focus on search field
        javafx.application.Platform.runLater(() -> searchField.requestFocus());
    }

    private HBox createHeader() {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(20, 25, 15, 25));
        header.getStyleClass().add("pos-modern-search-header");

        Label titleLabel = new Label("🔍 Product Search");
        titleLabel.getStyleClass().add("pos-modern-search-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().add("pos-modern-close-button");
        closeBtn.setFocusTraversable(true);
        closeBtn.setMouseTransparent(false);
        closeBtn.setOnAction(e -> {
            logger.debug("Close button clicked");
            e.consume();
            setResult(null);
            hide();
        });

        header.getChildren().addAll(titleLabel, spacer, closeBtn);
        return header;
    }

    private HBox createSearchArea() {
        HBox searchArea = new HBox(15);
        searchArea.setAlignment(Pos.CENTER_LEFT);
        searchArea.setPadding(new Insets(15, 25, 15, 25));
        searchArea.getStyleClass().add("pos-modern-search-input-area");

        searchField = new TextField();
        searchField.setPromptText("Enter product name, barcode, or SKU...");
        searchField.getStyleClass().add("pos-modern-search-field");
        searchField.setPrefHeight(60);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        // Listen for text changes
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.length() >= 2) {
                performSearch(newVal);
            } else {
                clearResults();
            }
        });

        // Enter key triggers search
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                performSearch(searchField.getText());
            } else if (e.getCode() == KeyCode.ESCAPE) {
                setResult(null);
                close();
            }
        });

        Button searchBtn = new Button("Search");
        searchBtn.getStyleClass().add("pos-modern-search-button");
        searchBtn.setPrefHeight(60);
        searchBtn.setPrefWidth(120);
        searchBtn.setOnAction(e -> performSearch(searchField.getText()));

        Button clearBtn = new Button("Clear");
        clearBtn.getStyleClass().add("pos-modern-clear-button");
        clearBtn.setPrefHeight(60);
        clearBtn.setPrefWidth(100);
        clearBtn.setOnAction(e -> {
            searchField.clear();
            clearResults();
        });

        searchArea.getChildren().addAll(searchField, searchBtn, clearBtn);
        return searchArea;
    }

    private VBox createResultsArea() {
        VBox container = new VBox(10);
        container.setPadding(new Insets(10, 25, 10, 25));

        statusLabel = new Label("Enter at least 2 characters to search");
        statusLabel.getStyleClass().add("pos-modern-search-status");

        resultsGrid = new FlowPane();
        resultsGrid.setHgap(15);
        resultsGrid.setVgap(15);
        resultsGrid.setPadding(new Insets(10));
        resultsGrid.getStyleClass().add("pos-modern-search-results-grid");

        resultsScroll = new ScrollPane(resultsGrid);
        resultsScroll.setFitToWidth(true);
        resultsScroll.getStyleClass().add("pos-modern-search-results-scroll");
        VBox.setVgrow(resultsScroll, Priority.ALWAYS);

        container.getChildren().addAll(statusLabel, resultsScroll);
        return container;
    }

    private VBox createOnScreenKeyboard() {
        VBox container = new VBox(6);
        container.setPadding(new Insets(10, 15, 15, 15));
        container.getStyleClass().add("pos-modern-keyboard-container");
        container.setMaxWidth(Double.MAX_VALUE);
        container.setFillWidth(true);

        // QWERTY keyboard layout
        String[][] rows = {
                { "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "⌫" },
                { "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P" },
                { "A", "S", "D", "F", "G", "H", "J", "K", "L" },
                { "Z", "X", "C", "V", "B", "N", "M", "␣", "CLR" }
        };

        for (String[] row : rows) {
            HBox rowBox = new HBox(5);
            rowBox.setAlignment(Pos.CENTER);
            rowBox.setMaxWidth(Double.MAX_VALUE);

            for (String key : row) {
                Button keyBtn = createKeyboardButton(key);
                HBox.setHgrow(keyBtn, Priority.ALWAYS);
                rowBox.getChildren().add(keyBtn);
            }

            container.getChildren().add(rowBox);
        }

        return container;
    }

    private Button createKeyboardButton(String key) {
        Button btn = new Button(key);
        btn.getStyleClass().add("pos-modern-keyboard-key");

        // Responsive sizing - min/pref/max widths
        btn.setMinWidth(35);
        btn.setPrefWidth(55);
        btn.setMaxWidth(80);
        btn.setMinHeight(45);
        btn.setPrefHeight(50);
        btn.setMaxHeight(55);

        if (key.equals("␣")) {
            // Space button - wider
            btn.setMinWidth(100);
            btn.setPrefWidth(180);
            btn.setMaxWidth(250);
            btn.setOnAction(e -> appendToSearch(" "));
        } else if (key.equals("⌫")) {
            btn.getStyleClass().add("pos-modern-keyboard-special");
            btn.setOnAction(e -> {
                String text = searchField.getText();
                if (text != null && !text.isEmpty()) {
                    searchField.setText(text.substring(0, text.length() - 1));
                }
            });
        } else if (key.equals("CLR")) {
            btn.getStyleClass().add("pos-modern-keyboard-special");
            btn.setOnAction(e -> searchField.clear());
        } else {
            btn.setOnAction(e -> appendToSearch(key));
        }

        return btn;
    }

    private void appendToSearch(String text) {
        String current = searchField.getText();
        searchField.setText(current + text);
        searchField.positionCaret(searchField.getText().length());
    }

    private void performSearch(String query) {
        if (query == null || query.trim().length() < 2) {
            statusLabel.setText("Enter at least 2 characters to search");
            clearResults();
            return;
        }

        statusLabel.setText("Searching...");

        new Thread(() -> {
            try {
                List<Product> results = salesService.searchProducts(query.trim());

                javafx.application.Platform.runLater(() -> {
                    displayResults(results);
                });
            } catch (Exception e) {
                logger.error("Search error", e);
                javafx.application.Platform.runLater(() -> {
                    statusLabel.setText("Search error: " + e.getMessage());
                });
            }
        }).start();
    }

    private void displayResults(List<Product> products) {
        resultsGrid.getChildren().clear();

        if (products.isEmpty()) {
            statusLabel.setText("No products found");
            return;
        }

        statusLabel.setText("Found " + products.size() + " product(s)");

        for (Product product : products) {
            Button card = createProductCard(product);
            resultsGrid.getChildren().add(card);
        }
    }

    private Button createProductCard(Product product) {
        Button btn = new Button();
        btn.setPrefSize(160, 100);
        btn.getStyleClass().add("pos-modern-search-product-card");

        VBox content = new VBox(5);
        content.setAlignment(Pos.CENTER);

        Label nameLabel = new Label(product.getName());
        nameLabel.setWrapText(true);
        nameLabel.getStyleClass().add("pos-modern-search-product-name");
        nameLabel.setMaxWidth(150);

        Label priceLabel = new Label(NumberFormat.getCurrencyInstance().format(product.getPrice()));
        priceLabel.getStyleClass().add("pos-modern-search-product-price");

        Label barcodeLabel = new Label(product.getBarcode());
        barcodeLabel.getStyleClass().add("pos-modern-search-product-barcode");

        content.getChildren().addAll(nameLabel, priceLabel, barcodeLabel);
        btn.setGraphic(content);

        btn.setOnAction(e -> {
            setResult(product);
            close();
        });

        return btn;
    }

    private void clearResults() {
        resultsGrid.getChildren().clear();
    }

    private void setupResultConverter() {
        setResultConverter(dialogButton -> null);
    }

    /**
     * Shows the dialog and returns the selected product, if any.
     */
    public Optional<Product> showAndGetResult() {
        return showAndWait();
    }
}
