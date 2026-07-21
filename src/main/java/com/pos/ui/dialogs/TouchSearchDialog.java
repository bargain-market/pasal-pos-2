package com.pos.ui.dialogs;

import com.pos.model.Product;
import com.pos.service.SalesService;
import com.pos.ui.components.CompactAlphanumericKeypad;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.text.NumberFormat;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Touch-friendly dialog for searching and selecting products.
 * Uses compact on-screen keyboard that fits within the dialog.
 * Designed for adding favorites and other product selection scenarios.
 */
public class TouchSearchDialog extends Dialog<Product> {

    private static final int DEBOUNCE_DELAY_MS = 400;
    private static final int MAX_RESULTS = 8;

    private TextField searchField;
    private CompactAlphanumericKeypad keypad;
    private ListView<Product> resultsList;
    private ObservableList<Product> searchResults;
    private Timer debounceTimer;
    private SalesService salesService;
    private Label statusLabel;
    private Button selectButton;

    public TouchSearchDialog(String title, String headerText) {
        this.salesService = SalesService.getInstance();
        this.searchResults = FXCollections.observableArrayList();
        initializeDialog(title, headerText);
    }

    public TouchSearchDialog(Window owner, String title, String headerText) {
        this.salesService = SalesService.getInstance();
        this.searchResults = FXCollections.observableArrayList();
        initOwner(owner);
        initializeDialog(title, headerText);
    }

    private void initializeDialog(String title, String headerText) {
        setTitle(title);
        setHeaderText(headerText);
        initModality(Modality.APPLICATION_MODAL);
        setResizable(true);

        // Set owner window to ensure dialog appears on same screen
        try {
            if (getOwner() == null) {
                Window currentWindow = javafx.stage.Stage.getWindows().stream()
                        .filter(Window::isShowing)
                        .findFirst()
                        .orElse(null);
                if (currentWindow != null) {
                    initOwner(currentWindow);
                }
            }
        } catch (Exception e) {
            // Ignore if we can't set owner
        }

        // Handle window close request (X button)
        setOnCloseRequest(event -> {
            setResult(null);
        });

        // Create main content with ScrollPane for responsiveness
        VBox content = new VBox(10);
        content.setPadding(new Insets(12));
        content.setAlignment(Pos.TOP_CENTER);
        content.setFillWidth(true);

        // Search input - Touch optimized but without embedded keyboard
        Label searchLabel = new Label("Enter product name, barcode, or SKU...");
        searchLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");

        searchField = new TextField();
        searchField.setPromptText("Enter product name or barcode...");
        searchField.setPrefHeight(50);
        searchField.setMinHeight(50);
        searchField.setMaxWidth(Double.MAX_VALUE);
        searchField.setStyle(
                "-fx-font-size: 16px; " +
                        "-fx-padding: 12 15; " +
                        "-fx-background-color: white; " +
                        "-fx-background-radius: 8; " +
                        "-fx-border-color: #1E88E5; " +
                        "-fx-border-radius: 8; " +
                        "-fx-border-width: 2;");

        // Disable physical keyboard input
        searchField.addEventFilter(KeyEvent.KEY_TYPED, Event::consume);
        searchField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != KeyCode.TAB) {
                e.consume();
            }
        });

        // Add search listener with debounce
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (debounceTimer != null) {
                debounceTimer.cancel();
            }

            if (newVal == null || newVal.trim().isEmpty()) {
                searchResults.clear();
                updateStatus("Enter a search term to find products");
                return;
            }

            debounceTimer = new Timer();
            debounceTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    Platform.runLater(() -> performSearch(newVal.trim()));
                }
            }, DEBOUNCE_DELAY_MS);
        });

        // Compact keyboard that fits within dialog - set to fill width
        keypad = new CompactAlphanumericKeypad();
        keypad.setListener(this::handleKeypadInput);
        keypad.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(keypad, Priority.NEVER);

        // Status label
        statusLabel = new Label("Enter a search term to find products");
        statusLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 13px;");

        // Results list with touch-friendly cells
        resultsList = new ListView<>(searchResults);
        resultsList.setPrefHeight(180);
        resultsList.setMinHeight(120);
        resultsList.setCellFactory(lv -> new ProductSearchCell());
        resultsList.setStyle(
                "-fx-background-color: white; " +
                        "-fx-background-radius: 8; " +
                        "-fx-border-color: #e0e0e0; " +
                        "-fx-border-radius: 8; " +
                        "-fx-border-width: 1;");
        VBox.setVgrow(resultsList, Priority.ALWAYS);

        // Handle selection
        resultsList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            updateSelectButton();
        });

        // Single click to select (touch-friendly)
        resultsList.setOnMouseClicked(e -> {
            Product selected = resultsList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                if (e.getClickCount() == 2) {
                    setResult(selected);
                    close();
                }
            }
        });

        // Layout: Search field, keyboard, status, results
        content.getChildren().addAll(searchLabel, searchField, keypad, statusLabel, resultsList);

        // Wrap in ScrollPane for smaller screens
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        // scrollPane.setMinWidth(600);
        // scrollPane.setPrefWidth(800);
        VBox.setVgrow(scrollPane, javafx.scene.layout.Priority.ALWAYS);

        // Set dialog content
        getDialogPane().setContent(scrollPane);

        // Buttons - Touch optimized
        ButtonType selectButtonType = new ButtonType("Select Product", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(selectButtonType, cancelButtonType);

        // Style buttons for touch
        selectButton = (Button) getDialogPane().lookupButton(selectButtonType);
        styleButton(selectButton, "#4CAF50", true);
        selectButton.setDisable(true);

        Button cancelButton = (Button) getDialogPane().lookupButton(cancelButtonType);
        styleButton(cancelButton, "#757575", false);
        // Ensure cancel button works
        cancelButton.setOnAction(e -> {
            setResult(null);
            close();
        });

        // Style dialog - responsive sizing
        getDialogPane().setStyle("-fx-background-color: #f5f7fa;");
        // getDialogPane().setMinWidth(650);
        // getDialogPane().setPrefWidth(850);
        // getDialogPane().setMaxWidth(1000);
        // getDialogPane().setMinHeight(550);
        // getDialogPane().setPrefHeight(650);
        // getDialogPane().setMaxHeight(800);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.8, 0.8);

        // Set result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == selectButtonType) {
                return resultsList.getSelectionModel().getSelectedItem();
            }
            return null;
        });

        // Focus search field when dialog opens
        Platform.runLater(() -> searchField.requestFocus());
    }

    private void handleKeypadInput(String key) {
        if ("CLEAR".equals(key)) {
            searchField.clear();
        } else if ("⌫".equals(key)) {
            String text = searchField.getText();
            if (!text.isEmpty()) {
                searchField.setText(text.substring(0, text.length() - 1));
                searchField.positionCaret(searchField.getText().length());
            }
        } else {
            searchField.appendText(key);
        }
    }

    private void performSearch(String query) {
        updateStatus("Searching...");

        new Thread(() -> {
            try {
                List<Product> results = salesService.searchProducts(query);

                Platform.runLater(() -> {
                    searchResults.clear();
                    if (!results.isEmpty()) {
                        searchResults.addAll(results.subList(0, Math.min(results.size(), MAX_RESULTS)));
                        updateStatus(results.size() + " product(s) found" +
                                (results.size() > MAX_RESULTS ? " (showing first " + MAX_RESULTS + ")" : ""));
                    } else {
                        updateStatus("No products found for: " + query);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    searchResults.clear();
                    updateStatus("Error searching products");
                });
            }
        }).start();
    }

    private void updateStatus(String message) {
        statusLabel.setText(message);
    }

    private void updateSelectButton() {
        selectButton.setDisable(resultsList.getSelectionModel().getSelectedItem() == null);
    }

    private void styleButton(Button button, String color, boolean isPrimary) {
        button.setPrefHeight(50);
        button.setMinHeight(50);
        button.setPrefWidth(isPrimary ? 180 : 120);
        button.setStyle(
                "-fx-font-size: 15px; " +
                        "-fx-font-weight: bold; " +
                        "-fx-background-color: " + color + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 8; " +
                        "-fx-padding: 10 20; " +
                        "-fx-cursor: hand;");
    }

    /**
     * Compact touch-friendly cell for product search results
     */
    private static class ProductSearchCell extends ListCell<Product> {
        private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

        @Override
        protected void updateItem(Product product, boolean empty) {
            super.updateItem(product, empty);

            if (empty || product == null) {
                setGraphic(null);
                setText(null);
                setStyle("-fx-background-color: transparent;");
                return;
            }

            HBox container = new HBox(10);
            container.setAlignment(Pos.CENTER_LEFT);
            container.setPadding(new Insets(8, 10, 8, 10));

            // Product info
            VBox info = new VBox(2);
            info.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(info, Priority.ALWAYS);

            Label nameLabel = new Label(product.getName());
            nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #333;");

            Label barcodeLabel = new Label(
                    (product.getBarcode() != null ? "SKU: " + product.getBarcode() : "No SKU"));
            barcodeLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

            info.getChildren().addAll(nameLabel, barcodeLabel);

            // Price
            Label priceLabel = new Label(currencyFormat.format(product.getPrice()));
            priceLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #1E88E5; -fx-font-size: 14px;");

            container.getChildren().addAll(info, priceLabel);

            setGraphic(container);
            setText(null);

            // Compact row height
            setPrefHeight(50);
            setMinHeight(45);

            setStyle("-fx-background-color: transparent; -fx-padding: 0;");

            setOnMouseEntered(e -> {
                if (!isSelected()) {
                    setStyle("-fx-background-color: #e3f2fd; -fx-padding: 0;");
                }
            });
            setOnMouseExited(e -> {
                if (!isSelected()) {
                    setStyle("-fx-background-color: transparent; -fx-padding: 0;");
                }
            });

            selectedProperty().addListener((obs, wasSelected, isSelected) -> {
                if (isSelected) {
                    setStyle("-fx-background-color: #bbdefb; -fx-padding: 0;");
                } else {
                    setStyle("-fx-background-color: transparent; -fx-padding: 0;");
                }
            });
        }
    }
}
