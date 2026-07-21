package com.pos.ui.components;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.TextAlignment;
import com.pos.model.Product;
import com.pos.service.FavoritesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;
import java.util.List;
import java.util.function.Consumer;

/**
 * Quick access favorites bar for frequently sold items
 * Displays configurable favorite products as buttons
 */
public class FavoritesBar extends HBox {

    private static final Logger logger = LoggerFactory.getLogger(FavoritesBar.class);
    private final FavoritesService favoritesService;
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
    
    private Consumer<Product> onProductSelected;
    private Consumer<Product> onAddFavoriteRequest;
    private String currentUserId;
    private boolean isEditing = false;

    public FavoritesBar() {
        this.favoritesService = FavoritesService.getInstance();
        
        setSpacing(8);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(8, 10, 8, 10));
        setStyle(
            "-fx-background-color: linear-gradient(to right, #f5f5f5, #eeeeee); " +
            "-fx-background-radius: 8; " +
            "-fx-border-color: #e0e0e0; " +
            "-fx-border-radius: 8;"
        );
        
        loadFavorites();
    }

    /**
     * Load favorites from database and display
     */
    public void loadFavorites() {
        new Thread(() -> {
            try {
                List<Product> favorites = favoritesService.getFavorites(currentUserId);
                
                Platform.runLater(() -> {
                    getChildren().clear();
                    
                    // Add label
                    Label label = new Label("★ Quick Access:");
                    label.setStyle("-fx-font-weight: bold; -fx-text-fill: #666; -fx-font-size: 12px;");
                    label.setMinWidth(Region.USE_PREF_SIZE);
                    getChildren().add(label);
                    
                    // Add favorite buttons
                    for (Product product : favorites) {
                        Button btn = createFavoriteButton(product);
                        getChildren().add(btn);
                    }
                    
                    // Add "+" button if not at max
                    if (favorites.size() < favoritesService.getMaxFavorites()) {
                        Button addBtn = createAddButton();
                        getChildren().add(addBtn);
                    }
                    
                    // Add edit button
                    if (!favorites.isEmpty()) {
                        Button editBtn = createEditButton();
                        getChildren().add(editBtn);
                    }
                });
            } catch (Exception e) {
                logger.error("Error loading favorites", e);
            }
        }).start();
    }

    private Button createFavoriteButton(Product product) {
        Button btn = new Button();
        btn.setPrefSize(100, 55);
        btn.setMinWidth(100);
        btn.setMaxWidth(120);
        
        VBox content = new VBox(2);
        content.setAlignment(Pos.CENTER);
        
        // Product name (truncated)
        String name = product.getName();
        if (name.length() > 12) {
            name = name.substring(0, 10) + "...";
        }
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        nameLabel.setWrapText(false);
        nameLabel.setTextAlignment(TextAlignment.CENTER);
        
        // Price
        Label priceLabel = new Label(currencyFormat.format(product.getPrice()));
        priceLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #1E88E5;");
        
        content.getChildren().addAll(nameLabel, priceLabel);
        btn.setGraphic(content);
        
        // Style based on stock
        String baseStyle = "-fx-background-radius: 6; -fx-cursor: hand; -fx-padding: 5;";
        if (product.getStock() <= 0) {
            btn.setStyle(baseStyle + 
                "-fx-background-color: #ffebee; " +
                "-fx-border-color: #ef9a9a; " +
                "-fx-border-radius: 6;");
        } else if (product.getStock() <= 5) {
            btn.setStyle(baseStyle + 
                "-fx-background-color: #fff3e0; " +
                "-fx-border-color: #ffcc80; " +
                "-fx-border-radius: 6;");
        } else {
            btn.setStyle(baseStyle + 
                "-fx-background-color: white; " +
                "-fx-border-color: #e0e0e0; " +
                "-fx-border-radius: 6; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.08), 2, 0, 0, 1);");
        }
        
        // Tooltip
        Tooltip tooltip = new Tooltip(
            product.getName() + "\n" +
            "Price: " + currencyFormat.format(product.getPrice()) + "\n" +
            "Stock: " + product.getStock()
        );
        Tooltip.install(btn, tooltip);
        
        // Click handler
        btn.setOnAction(e -> {
            if (isEditing) {
                // Remove from favorites
                removeFavorite(product);
            } else {
                // Select product
                if (onProductSelected != null) {
                    onProductSelected.accept(product);
                }
            }
        });
        
        // Context menu for removal
        ContextMenu contextMenu = new ContextMenu();
        MenuItem removeItem = new MenuItem("Remove from Favorites");
        removeItem.setOnAction(e -> removeFavorite(product));
        contextMenu.getItems().add(removeItem);
        btn.setContextMenu(contextMenu);
        
        return btn;
    }

    private Button createAddButton() {
        Button btn = new Button("+");
        btn.setPrefSize(50, 55);
        btn.setStyle(
            "-fx-background-color: #e8f5e9; " +
            "-fx-border-color: #a5d6a7; " +
            "-fx-border-style: dashed; " +
            "-fx-border-radius: 6; " +
            "-fx-background-radius: 6; " +
            "-fx-font-size: 20px; " +
            "-fx-text-fill: #4caf50; " +
            "-fx-cursor: hand;"
        );
        
        Tooltip.install(btn, new Tooltip("Add favorite product"));
        
        btn.setOnAction(e -> {
            if (onAddFavoriteRequest != null) {
                onAddFavoriteRequest.accept(null);
            }
        });
        
        return btn;
    }

    private Button createEditButton() {
        Button btn = new Button(isEditing ? "✓" : "✎");
        btn.setPrefSize(35, 55);
        btn.setStyle(
            "-fx-background-color: " + (isEditing ? "#e3f2fd" : "#fafafa") + "; " +
            "-fx-border-color: " + (isEditing ? "#90caf9" : "#e0e0e0") + "; " +
            "-fx-border-radius: 6; " +
            "-fx-background-radius: 6; " +
            "-fx-font-size: 14px; " +
            "-fx-cursor: hand;"
        );
        
        Tooltip.install(btn, new Tooltip(isEditing ? "Done editing" : "Edit favorites"));
        
        btn.setOnAction(e -> {
            isEditing = !isEditing;
            loadFavorites(); // Refresh to update button styles
        });
        
        return btn;
    }

    private void removeFavorite(Product product) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Remove Favorite");
        confirm.setHeaderText("Remove from favorites?");
        confirm.setContentText("Remove \"" + product.getName() + "\" from quick access?");
        
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                boolean removed = favoritesService.removeFavorite(product.getId(), currentUserId);
                if (removed) {
                    loadFavorites();
                    ToastNotification.showSuccess("Removed from favorites", getScene().getWindow());
                }
            }
        });
    }

    /**
     * Add a product to favorites
     */
    public boolean addFavorite(Product product) {
        if (product == null || product.getId() == null) {
            return false;
        }
        
        boolean added = favoritesService.addFavorite(product.getId(), currentUserId);
        if (added) {
            loadFavorites();
        }
        return added;
    }

    /**
     * Set callback for when a product is selected
     */
    public void setOnProductSelected(Consumer<Product> handler) {
        this.onProductSelected = handler;
    }

    /**
     * Set callback for when user wants to add a favorite
     */
    public void setOnAddFavoriteRequest(Consumer<Product> handler) {
        this.onAddFavoriteRequest = handler;
    }

    /**
     * Set current user for user-specific favorites
     */
    public void setCurrentUserId(String userId) {
        this.currentUserId = userId;
        loadFavorites();
    }

    /**
     * Refresh the favorites bar
     */
    public void refresh() {
        loadFavorites();
    }

    /**
     * Check if the favorites bar is empty
     */
    public boolean isEmpty() {
        return favoritesService.getFavoriteCount(currentUserId) == 0;
    }
}

