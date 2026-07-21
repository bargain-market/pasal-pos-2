package com.pos.ui.components;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Popup;
import com.pos.model.Product;
import com.pos.service.SalesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;

/**
 * Product search field with auto-complete suggestions
 * Shows real-time suggestions as user types with debounce
 */
public class ProductSearchField extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(ProductSearchField.class);
    private static final int DEBOUNCE_DELAY_MS = 300;
    private static final int MAX_SUGGESTIONS = 10;

    private final TextField searchField;
    private final ListView<Product> suggestionList;
    private final Popup suggestionPopup;
    private final SalesService salesService;
    private final ObservableList<Product> suggestions;
    
    private Timer debounceTimer;
    private Consumer<Product> onProductSelected;
    private boolean isSearching = false;

    public ProductSearchField() {
        this.salesService = SalesService.getInstance();
        this.suggestions = FXCollections.observableArrayList();
        
        // Search field
        searchField = new TextField();
        searchField.setPromptText("🔍 Search products by name, barcode, or SKU...");
        searchField.setStyle(
            "-fx-font-size: 14px; " +
            "-fx-padding: 10 15; " +
            "-fx-background-color: white; " +
            "-fx-background-radius: 20; " +
            "-fx-border-color: #e0e0e0; " +
            "-fx-border-radius: 20; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 4, 0, 0, 1);"
        );
        searchField.setMaxWidth(Double.MAX_VALUE);
        
        // Suggestion list
        suggestionList = new ListView<>(suggestions);
        suggestionList.setPrefHeight(300);
        suggestionList.setMaxHeight(400);
        suggestionList.setCellFactory(lv -> new ProductSuggestionCell());
        suggestionList.setStyle(
            "-fx-background-color: white; " +
            "-fx-background-radius: 8; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 10, 0, 0, 4);"
        );
        
        // Popup for suggestions
        suggestionPopup = new Popup();
        suggestionPopup.setAutoHide(true);
        suggestionPopup.getContent().add(suggestionList);
        
        // Event handlers
        setupEventHandlers();
        
        // Layout
        setSpacing(5);
        getChildren().add(searchField);
        HBox.setHgrow(this, Priority.ALWAYS);
    }

    private void setupEventHandlers() {
        // Text change with debounce
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (debounceTimer != null) {
                debounceTimer.cancel();
            }
            
            if (newVal == null || newVal.trim().isEmpty()) {
                hideSuggestions();
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
        
        // Keyboard navigation
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN && suggestionPopup.isShowing()) {
                suggestionList.requestFocus();
                if (!suggestions.isEmpty()) {
                    suggestionList.getSelectionModel().selectFirst();
                }
            } else if (e.getCode() == KeyCode.ESCAPE) {
                hideSuggestions();
            } else if (e.getCode() == KeyCode.ENTER && !suggestions.isEmpty()) {
                selectProduct(suggestions.get(0));
            }
        });
        
        // List selection
        suggestionList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                Product selected = suggestionList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    selectProduct(selected);
                }
            } else if (e.getCode() == KeyCode.ESCAPE) {
                hideSuggestions();
                searchField.requestFocus();
            }
        });
        
        suggestionList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 1) {
                Product selected = suggestionList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    selectProduct(selected);
                }
            }
        });
        
        // Focus handling
        searchField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused && !suggestionList.isFocused()) {
                // Small delay to allow click on suggestion
                Platform.runLater(() -> {
                    if (!suggestionList.isFocused()) {
                        hideSuggestions();
                    }
                });
            }
        });
    }

    private void performSearch(String query) {
        if (isSearching || query.isEmpty()) {
            return;
        }
        
        isSearching = true;
        
        new Thread(() -> {
            try {
                List<Product> results = salesService.searchProducts(query);
                
                Platform.runLater(() -> {
                    suggestions.clear();
                    if (!results.isEmpty()) {
                        suggestions.addAll(results.subList(0, Math.min(results.size(), MAX_SUGGESTIONS)));
                        showSuggestions();
                    } else {
                        hideSuggestions();
                    }
                    isSearching = false;
                });
            } catch (Exception e) {
                logger.error("Error searching products", e);
                Platform.runLater(() -> {
                    isSearching = false;
                    hideSuggestions();
                });
            }
        }).start();
    }

    private void showSuggestions() {
        if (!suggestionPopup.isShowing() && !suggestions.isEmpty()) {
            // Position below search field
            double x = searchField.localToScreen(0, 0).getX();
            double y = searchField.localToScreen(0, 0).getY() + searchField.getHeight() + 5;
            
            suggestionList.setPrefWidth(searchField.getWidth());
            suggestionPopup.show(searchField.getScene().getWindow(), x, y);
        }
    }

    private void hideSuggestions() {
        suggestionPopup.hide();
    }

    private void selectProduct(Product product) {
        if (onProductSelected != null) {
            onProductSelected.accept(product);
        }
        searchField.clear();
        hideSuggestions();
        searchField.requestFocus();
    }

    public void setOnProductSelected(Consumer<Product> handler) {
        this.onProductSelected = handler;
    }

    public void clear() {
        searchField.clear();
        hideSuggestions();
    }

    public void requestSearchFocus() {
        searchField.requestFocus();
    }

    /**
     * Custom cell for product suggestions
     */
    private static class ProductSuggestionCell extends ListCell<Product> {
        private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
        
        @Override
        protected void updateItem(Product product, boolean empty) {
            super.updateItem(product, empty);
            
            if (empty || product == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            
            HBox container = new HBox(10);
            container.setAlignment(Pos.CENTER_LEFT);
            container.setPadding(new Insets(8, 12, 8, 12));
            
            // Product info
            VBox info = new VBox(2);
            info.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(info, Priority.ALWAYS);
            
            Label nameLabel = new Label(product.getName());
            nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
            
            Label detailLabel = new Label(
                (product.getBarcode() != null ? "SKU: " + product.getBarcode() : "") 
            );
            detailLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
            
            info.getChildren().addAll(nameLabel, detailLabel);
            
            // Price
            Label priceLabel = new Label(currencyFormat.format(product.getPrice()));
            priceLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #1E88E5; -fx-font-size: 14px;");
            
            // Stock indicator
            Label stockLabel = new Label();
            int stock = product.getStock();
            if (stock < 0) {
                stockLabel.setText("Stock Not Updated");
                stockLabel.setStyle("-fx-text-fill: #607d8b; -fx-font-size: 11px; -fx-font-weight: bold;");
            } else if (stock == 0) {
                stockLabel.setText("Out of Stock");
                stockLabel.setStyle("-fx-text-fill: #e53935; -fx-font-size: 11px; -fx-font-weight: bold;");
            } else if (stock <= 5) {
                stockLabel.setText("Low: " + stock);
                stockLabel.setStyle("-fx-text-fill: #FF9800; -fx-font-size: 11px; -fx-font-weight: bold;");
            } else {
                stockLabel.setText("In Stock: " + stock);
                stockLabel.setStyle("-fx-text-fill: #43a047; -fx-font-size: 11px;");
            }
            
            VBox priceBox = new VBox(2);
            priceBox.setAlignment(Pos.CENTER_RIGHT);
            priceBox.getChildren().addAll(priceLabel, stockLabel);
            
            container.getChildren().addAll(info, priceBox);
            
            setGraphic(container);
            setStyle("-fx-padding: 0; -fx-background-color: transparent;");
            
            // Hover effect
            setOnMouseEntered(e -> setStyle("-fx-background-color: #e3f2fd; -fx-padding: 0;"));
            setOnMouseExited(e -> setStyle("-fx-background-color: transparent; -fx-padding: 0;"));
        }
    }
}

