package com.pos.ui.components;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Popup;
import com.pos.model.Product;
import com.pos.service.SalesService;
import com.pos.ui.keyboard.KeyboardManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;

/**
 * Touch-only product search field with auto-complete suggestions.
 * Uses the global floating on-screen keyboard for input.
 */
public class TouchProductSearchField extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(TouchProductSearchField.class);
    private static final int DEBOUNCE_DELAY_MS = 400;
    private static final int MAX_SUGGESTIONS = 10;

    private final TextField searchField;
    private final HBox searchRow;
    private final ListView<Product> suggestionList;
    private final Popup suggestionPopup;
    private final SalesService salesService;
    private final ObservableList<Product> suggestions;

    private Timer debounceTimer;
    private Consumer<Product> onProductSelected;
    private boolean isSearching = false;

    public TouchProductSearchField() {
        this.salesService = SalesService.getInstance();
        this.suggestions = FXCollections.observableArrayList();

        searchField = new TextField();
        searchField.setPromptText("🔍 Tap to search products...");
        searchField.setPrefHeight(60);
        searchField.setMinHeight(60);
        searchField.setStyle(
                "-fx-font-size: 18px; " +
                        "-fx-padding: 15 20; " +
                        "-fx-background-color: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-border-color: #e0e0e0; " +
                        "-fx-border-radius: 12; " +
                        "-fx-border-width: 2; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 4, 0, 0, 1);");
        searchField.setMaxWidth(Double.MAX_VALUE);
        searchField.setEditable(true);

        setupEventHandlers();

        searchRow = new HBox(8);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchRow.getChildren().add(searchField);

        setSpacing(10);
        getChildren().add(searchRow);
        HBox.setHgrow(this, Priority.ALWAYS);

        suggestionList = new ListView<>(suggestions);
        suggestionList.setPrefHeight(350);
        suggestionList.setMaxHeight(450);
        suggestionList.setCellFactory(lv -> new TouchProductSuggestionCell());
        suggestionList.setStyle(
                "-fx-background-color: white; " +
                        "-fx-background-radius: 8; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 10, 0, 0, 4);");

        suggestionPopup = new Popup();
        suggestionPopup.setAutoHide(false);
        suggestionPopup.getContent().add(suggestionList);
    }

    private void setupEventHandlers() {
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

        searchField.setOnMouseClicked(e -> {
            if (!searchField.isFocused()) {
                searchField.requestFocus();
            }
            KeyboardManager.showFor(searchField);
        });

        searchField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                searchField.setStyle(
                        "-fx-font-size: 18px; " +
                                "-fx-padding: 15 20; " +
                                "-fx-background-color: white; " +
                                "-fx-background-radius: 12; " +
                                "-fx-border-color: #1E88E5; " +
                                "-fx-border-radius: 12; " +
                                "-fx-border-width: 3; " +
                                "-fx-effect: dropshadow(gaussian, rgba(30,136,229,0.3), 8, 0, 0, 2);");
            } else {
                searchField.setStyle(
                        "-fx-font-size: 18px; " +
                                "-fx-padding: 15 20; " +
                                "-fx-background-color: white; " +
                                "-fx-background-radius: 12; " +
                                "-fx-border-color: #e0e0e0; " +
                                "-fx-border-radius: 12; " +
                                "-fx-border-width: 2; " +
                                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 4, 0, 0, 1);");

                Platform.runLater(() -> {
                    // Don't reclaim focus if the user tapped another text input field;
                    // let that field take over the on-screen keyboard instead of fighting it.
                    if (KeyboardManager.isShowing()
                            && !TouchTextField.focusMovedToAnotherInput(searchField)) {
                        searchField.requestFocus();
                    } else if (!searchField.isFocused() && !suggestionList.isFocused()) {
                        hideSuggestions();
                    }
                });
            }
        });

        suggestionList.setOnMouseClicked(e -> {
            Product selected = suggestionList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                selectProduct(selected);
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
        if (!suggestionPopup.isShowing() && !suggestions.isEmpty() && searchField.getScene() != null) {
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
        KeyboardManager.hide();
    }

    public void setOnProductSelected(Consumer<Product> handler) {
        this.onProductSelected = handler;
    }

    public void clear() {
        searchField.clear();
        hideSuggestions();
        KeyboardManager.hide();
    }

    public void requestSearchFocus() {
        searchField.requestFocus();
        KeyboardManager.showFor(searchField);
    }

    /**
     * Touch-optimized cell for product suggestions with larger touch targets
     */
    private static class TouchProductSuggestionCell extends ListCell<Product> {
        private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

        @Override
        protected void updateItem(Product product, boolean empty) {
            super.updateItem(product, empty);

            if (empty || product == null) {
                setText(null);
                setGraphic(null);
                setStyle("");
            } else {
                VBox content = new VBox(4);
                content.setPadding(new javafx.geometry.Insets(12, 16, 12, 16));

                Label nameLabel = new Label(product.getName());
                nameLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

                HBox details = new HBox(15);
                details.setAlignment(Pos.CENTER_LEFT);

                if (product.getSku() != null && !product.getSku().isEmpty()) {
                    Label skuLabel = new Label("SKU: " + product.getSku());
                    skuLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");
                    details.getChildren().add(skuLabel);
                }

                Label priceLabel = new Label(currencyFormat.format(product.getPrice()));
                priceLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #2E7D32;");
                details.getChildren().add(priceLabel);

                Label stockLabel = new Label("Stock: " + product.getStock());
                stockLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");
                details.getChildren().add(stockLabel);

                content.getChildren().addAll(nameLabel, details);
                setGraphic(content);
                setStyle(
                        "-fx-background-color: white; " +
                                "-fx-border-color: #f0f0f0; " +
                                "-fx-border-width: 0 0 1 0;");
            }
        }
    }
}
