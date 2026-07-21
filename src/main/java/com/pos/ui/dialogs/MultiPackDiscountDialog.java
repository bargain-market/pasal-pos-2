package com.pos.ui.dialogs;

import com.pos.util.ResponsiveHelper;
import com.pos.model.SaleItem;
import com.pos.service.ProductManagementService;
import com.pos.service.SettingsService;
import com.pos.service.SettingsService.MultiPackDiscountSettings;
import javafx.animation.ScaleTransition;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Touch-optimized dialog for applying multi-pack discounts to eligible cart
 * items.
 * Shows items from configured departments that meet the minimum quantity
 * threshold.
 * Applies the configured discount (percentage or fixed amount) to selected
 * items.
 */
public class MultiPackDiscountDialog extends Dialog<MultiPackDiscountDialog.MultiPackDiscountResult> {

    private static final Logger logger = LoggerFactory.getLogger(MultiPackDiscountDialog.class);

    // Touch-screen optimized sizes
    private static final double ACTION_BUTTON_HEIGHT = 70.0;
    private static final double ITEM_ROW_HEIGHT = 80.0;

    // Colors - Green theme for multi-pack discount
    private static final String PRIMARY_COLOR = "#16A34A"; // Green
    private static final String SUCCESS_COLOR = "#4CAF50";
    private static final String NEUTRAL_COLOR = "#757575";
    private static final String CARD_BG_COLOR = "#ffffff";
    private static final String DIALOG_BG_COLOR = "#f5f7fa";
    private static final String SELECTED_BG = "#dcfce7"; // Light green

    // Services
    private final SettingsService settingsService;
    private final ProductManagementService productManagementService;

    // UI Components
    private VBox itemListContainer;
    private Label discountInfoLabel;
    private Label errorLabel;
    private Button applyButton;
    private Button cancelButton;

    // Data
    private final ObservableList<SaleItem> cartItems;
    private final List<SaleItem> eligibleItems;
    private SaleItem selectedItem = null;
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

    public MultiPackDiscountDialog(Window owner, ObservableList<SaleItem> cartItems) {
        this.cartItems = cartItems;
        this.settingsService = SettingsService.getInstance();
        this.productManagementService = ProductManagementService.getInstance();
        this.eligibleItems = new ArrayList<>();

        initOwner(owner);
        initializeDialog();
    }

    private void initializeDialog() {
        setTitle("Multi-Pack Discount");
        initModality(Modality.APPLICATION_MODAL);
        initStyle(StageStyle.UNDECORATED);

        // Filter eligible items
        filterEligibleItems();

        // Main container
        VBox mainContainer = new VBox(0);
        // Responsive width - let DialogPane handle size via ResponsiveHelper
        mainContainer.setMaxWidth(Double.MAX_VALUE);
        mainContainer.setStyle(
                "-fx-background-color: " + DIALOG_BG_COLOR + "; " +
                        "-fx-background-radius: 16; " +
                        "-fx-border-radius: 16; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 20, 0, 0, 5);");

        // Header
        VBox header = createHeader();

        // Content
        VBox content = createContent();

        // Action buttons
        HBox actionButtons = createActionButtons();

        mainContainer.getChildren().addAll(header, content, actionButtons);

        // Configure dialog pane
        getDialogPane().setContent(mainContainer);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().lookupButton(ButtonType.CLOSE).setVisible(false);
        getDialogPane().setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        getDialogPane().getScene().setFill(Color.TRANSPARENT);

        // Set result converter
        setResultConverter(dialogButton -> null);

        // Update apply button state
        updateApplyButtonState();

        // Make responsive - 50% width, 60% height
        ResponsiveHelper.setupResponsiveDialog(this, 0.5, 0.6);
    }

    private void filterEligibleItems() {
        eligibleItems.clear();

        for (SaleItem item : cartItems) {
            String deptId = item.getProduct().getDepartmentId();
            if (deptId == null) {
                continue;
            }

            // Get department-specific multipack settings
            MultiPackDiscountSettings deptSettings = settingsService.getMultiPackDiscountSettingsForDepartment(deptId);

            // Check if multipack is enabled for this department and quantity meets minimum
            if (deptSettings.enabled && item.getQuantity() >= deptSettings.minimumQuantity) {
                // Check if item doesn't already have a multi-pack discount applied
                String existingReason = item.getDiscountReason();
                if (existingReason == null || !existingReason.contains("Multi-Pack")) {
                    eligibleItems.add(item);
                }
            }
        }

        logger.info("Found {} eligible items for multi-pack discount", eligibleItems.size());
    }

    private VBox createHeader() {
        VBox header = new VBox(5);
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(20, 20, 15, 20));
        header.setStyle(
                "-fx-background-color: " + PRIMARY_COLOR + "; " +
                        "-fx-background-radius: 16 16 0 0;");

        // Title with icon
        Label titleLabel = new Label("📦 Multi-Pack Discount");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 22));
        titleLabel.setTextFill(Color.WHITE);

        // Discount info - show generic message since different items may have different
        // settings
        String discountText = "Select an item to apply multi-pack discount";
        if (!eligibleItems.isEmpty()) {
            // Show first item's settings as example
            SaleItem firstItem = eligibleItems.get(0);
            String deptId = firstItem.getProduct().getDepartmentId();
            if (deptId != null) {
                MultiPackDiscountSettings firstSettings = settingsService
                        .getMultiPackDiscountSettingsForDepartment(deptId);
                discountText = "Apply " + firstSettings.getDiscountDisplayString() + " discount";
                if (firstSettings.minimumQuantity > 1) {
                    discountText += " (min qty: " + firstSettings.minimumQuantity + ")";
                }
            }
        }
        discountInfoLabel = new Label(discountText);
        discountInfoLabel.setFont(Font.font("System", FontWeight.NORMAL, 14));
        discountInfoLabel.setTextFill(Color.web("#ffffff", 0.9));
        discountInfoLabel.setPadding(new Insets(8, 16, 8, 16));
        discountInfoLabel.setStyle(
                "-fx-background-color: rgba(255,255,255,0.2); " +
                        "-fx-background-radius: 8;");

        header.getChildren().addAll(titleLabel, discountInfoLabel);
        return header;
    }

    private VBox createContent() {
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setAlignment(Pos.TOP_CENTER);

        // Section label
        Label sectionLabel = new Label("Select Item to Discount");
        sectionLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        sectionLabel.setTextFill(Color.web("#333333"));

        // Item list container
        itemListContainer = new VBox(10);
        itemListContainer.setAlignment(Pos.TOP_CENTER);
        itemListContainer.setFillWidth(true);

        if (eligibleItems.isEmpty()) {
            // No eligible items message
            VBox emptyState = createEmptyState();
            itemListContainer.getChildren().add(emptyState);
        } else {
            // Create item rows
            for (SaleItem item : eligibleItems) {
                HBox itemRow = createItemRow(item);
                itemListContainer.getChildren().add(itemRow);
            }
        }

        // Scrollable container for items
        ScrollPane scrollPane = new ScrollPane(itemListContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setPrefViewportHeight(300);
        scrollPane.setMaxHeight(350);

        // Error label (hidden by default)
        errorLabel = new Label();
        errorLabel.setFont(Font.font("System", FontWeight.NORMAL, 14));
        errorLabel.setTextFill(Color.web("#f44336"));
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        content.getChildren().addAll(sectionLabel, scrollPane, errorLabel);
        return content;
    }

    private VBox createEmptyState() {
        VBox emptyState = new VBox(10);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.setPadding(new Insets(40, 20, 40, 20));
        emptyState.setStyle(
                "-fx-background-color: #fef3c7; " +
                        "-fx-background-radius: 12;");

        Label iconLabel = new Label("⚠️");
        iconLabel.setFont(Font.font("System", 40));

        Label messageLabel = new Label("No eligible items found");
        messageLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        messageLabel.setTextFill(Color.web("#92400e"));

        // Get global settings for display purposes
        @SuppressWarnings("deprecation")
        MultiPackDiscountSettings globalSettings = settingsService.getMultiPackDiscountSettings();

        String detailText = "Items must be in an eligible department";
        if (globalSettings.minimumQuantity > 1) {
            detailText += " with quantity of " + globalSettings.minimumQuantity + " or more";
        }
        Label detailLabel = new Label(detailText);
        detailLabel.setFont(Font.font("System", FontWeight.NORMAL, 13));
        detailLabel.setTextFill(Color.web("#a16207"));
        detailLabel.setWrapText(true);
        detailLabel.setMaxWidth(400);
        detailLabel.setAlignment(Pos.CENTER);

        // Show eligible departments
        if (!globalSettings.eligibleDepartmentIds.isEmpty()) {
            StringBuilder deptNames = new StringBuilder("Eligible departments: ");
            List<String> names = new ArrayList<>();
            for (String deptId : globalSettings.eligibleDepartmentIds) {
                try {
                    ProductManagementService.Department dept = productManagementService.getDepartmentById(deptId);
                    if (dept != null) {
                        names.add(dept.name);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to get department name for id: {}", deptId);
                }
            }
            if (!names.isEmpty()) {
                deptNames.append(String.join(", ", names));
                Label deptLabel = new Label(deptNames.toString());
                deptLabel.setFont(Font.font("System", FontWeight.NORMAL, 12));
                deptLabel.setTextFill(Color.web("#78350f"));
                deptLabel.setWrapText(true);
                deptLabel.setMaxWidth(400);
                emptyState.getChildren().addAll(iconLabel, messageLabel, detailLabel, deptLabel);
            } else {
                emptyState.getChildren().addAll(iconLabel, messageLabel, detailLabel);
            }
        } else {
            Label noDeptLabel = new Label("No departments configured for multi-pack discount");
            noDeptLabel.setFont(Font.font("System", FontWeight.NORMAL, 12));
            noDeptLabel.setTextFill(Color.web("#78350f"));
            emptyState.getChildren().addAll(iconLabel, messageLabel, noDeptLabel);
        }

        return emptyState;
    }

    private HBox createItemRow(SaleItem item) {
        HBox row = new HBox(15);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(15));
        row.setMinHeight(ITEM_ROW_HEIGHT);
        row.setMaxHeight(ITEM_ROW_HEIGHT);
        row.setStyle(
                "-fx-background-color: " + CARD_BG_COLOR + "; " +
                        "-fx-background-radius: 12; " +
                        "-fx-border-color: #e0e0e0; " +
                        "-fx-border-width: 2; " +
                        "-fx-border-radius: 12; " +
                        "-fx-cursor: hand;");

        // Selection indicator
        Region selectionIndicator = new Region();
        selectionIndicator.setMinSize(8, 50);
        selectionIndicator.setMaxSize(8, 50);
        selectionIndicator.setStyle("-fx-background-color: transparent; -fx-background-radius: 4;");

        // Product info
        VBox productInfo = new VBox(4);
        productInfo.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(productInfo, Priority.ALWAYS);

        Label nameLabel = new Label(item.getProductName());
        nameLabel.setFont(Font.font("System", FontWeight.BOLD, 15));
        nameLabel.setTextFill(Color.web("#333333"));
        nameLabel.setMaxWidth(280);
        nameLabel.setWrapText(false);
        nameLabel.setEllipsisString("...");

        // Get department name
        String deptName = "Unknown";
        String deptId = item.getProduct().getDepartmentId();
        if (deptId != null) {
            try {
                ProductManagementService.Department dept = productManagementService.getDepartmentById(deptId);
                if (dept != null) {
                    deptName = dept.name;
                }
            } catch (Exception e) {
                logger.warn("Failed to get department name for id: {}", deptId);
            }
        }
        Label deptLabel = new Label(deptName);
        deptLabel.setFont(Font.font("System", FontWeight.NORMAL, 12));
        deptLabel.setTextFill(Color.web("#666666"));

        productInfo.getChildren().addAll(nameLabel, deptLabel);

        // Quantity badge
        Label qtyLabel = new Label("×" + item.getQuantity());
        qtyLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        qtyLabel.setTextFill(Color.WHITE);
        qtyLabel.setPadding(new Insets(6, 12, 6, 12));
        qtyLabel.setStyle(
                "-fx-background-color: " + PRIMARY_COLOR + "; " +
                        "-fx-background-radius: 20;");

        // Price info
        VBox priceInfo = new VBox(4);
        priceInfo.setAlignment(Pos.CENTER_RIGHT);
        priceInfo.setMinWidth(100);

        BigDecimal currentTotal = item.getBaseTotal();
        Label currentPriceLabel = new Label(currencyFormat.format(currentTotal));
        currentPriceLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        currentPriceLabel.setTextFill(Color.web("#333333"));

        // Calculate discount preview
        BigDecimal discountAmount = calculateDiscount(item);
        Label discountPreview = new Label("-" + currencyFormat.format(discountAmount));
        discountPreview.setFont(Font.font("System", FontWeight.NORMAL, 13));
        discountPreview.setTextFill(Color.web(SUCCESS_COLOR));

        priceInfo.getChildren().addAll(currentPriceLabel, discountPreview);

        row.getChildren().addAll(selectionIndicator, productInfo, qtyLabel, priceInfo);

        // Click handler
        row.setOnMouseClicked(e -> {
            selectItem(item, row, selectionIndicator);
        });

        // Hover effect
        row.setOnMouseEntered(e -> {
            if (selectedItem != item) {
                row.setStyle(
                        "-fx-background-color: #f8f9fa; " +
                                "-fx-background-radius: 12; " +
                                "-fx-border-color: #e0e0e0; " +
                                "-fx-border-width: 2; " +
                                "-fx-border-radius: 12; " +
                                "-fx-cursor: hand;");
            }
        });

        row.setOnMouseExited(e -> {
            if (selectedItem != item) {
                row.setStyle(
                        "-fx-background-color: " + CARD_BG_COLOR + "; " +
                                "-fx-background-radius: 12; " +
                                "-fx-border-color: #e0e0e0; " +
                                "-fx-border-width: 2; " +
                                "-fx-border-radius: 12; " +
                                "-fx-cursor: hand;");
            }
        });

        return row;
    }

    private void selectItem(SaleItem item, HBox row, Region indicator) {
        // Deselect previous
        if (selectedItem != null) {
            for (var node : itemListContainer.getChildren()) {
                if (node instanceof HBox) {
                    HBox prevRow = (HBox) node;
                    prevRow.setStyle(
                            "-fx-background-color: " + CARD_BG_COLOR + "; " +
                                    "-fx-background-radius: 12; " +
                                    "-fx-border-color: #e0e0e0; " +
                                    "-fx-border-width: 2; " +
                                    "-fx-border-radius: 12; " +
                                    "-fx-cursor: hand;");
                    if (prevRow.getChildren().size() > 0 && prevRow.getChildren().get(0) instanceof Region) {
                        Region prevIndicator = (Region) prevRow.getChildren().get(0);
                        prevIndicator.setStyle("-fx-background-color: transparent; -fx-background-radius: 4;");
                    }
                }
            }
        }

        // Select new
        selectedItem = item;
        row.setStyle(
                "-fx-background-color: " + SELECTED_BG + "; " +
                        "-fx-background-radius: 12; " +
                        "-fx-border-color: " + PRIMARY_COLOR + "; " +
                        "-fx-border-width: 2; " +
                        "-fx-border-radius: 12; " +
                        "-fx-cursor: hand;");
        indicator.setStyle("-fx-background-color: " + PRIMARY_COLOR + "; -fx-background-radius: 4;");

        // Play selection animation
        playPressAnimation(row);

        updateApplyButtonState();
    }

    private BigDecimal calculateDiscount(SaleItem item) {
        String deptId = item.getProduct().getDepartmentId();
        if (deptId == null) {
            return BigDecimal.ZERO;
        }

        MultiPackDiscountSettings deptSettings = settingsService.getMultiPackDiscountSettingsForDepartment(deptId);
        if (!deptSettings.enabled) {
            return BigDecimal.ZERO;
        }

        BigDecimal baseTotal = item.getBaseTotal();

        if (deptSettings.discountType == MultiPackDiscountSettings.DiscountType.PERCENT) {
            return baseTotal.multiply(deptSettings.discountValue)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        } else {
            // Fixed amount discount - apply per item or per line?
            // For multi-pack, we apply to the whole line
            return deptSettings.discountValue.min(baseTotal);
        }
    }

    private HBox createActionButtons() {
        HBox buttonContainer = new HBox(15);
        buttonContainer.setAlignment(Pos.CENTER);
        buttonContainer.setPadding(new Insets(20));
        buttonContainer.setStyle(
                "-fx-background-color: " + CARD_BG_COLOR + "; " +
                        "-fx-background-radius: 0 0 16 16; " +
                        "-fx-border-color: #e0e0e0; " +
                        "-fx-border-width: 1 0 0 0;");

        // Cancel button
        cancelButton = new Button("Cancel");
        cancelButton.setPrefWidth(150);
        cancelButton.setPrefHeight(ACTION_BUTTON_HEIGHT);
        cancelButton.setFont(Font.font("System", FontWeight.BOLD, 18));
        cancelButton.setFocusTraversable(false);
        cancelButton.setStyle(
                "-fx-background-color: " + NEUTRAL_COLOR + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-cursor: hand; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 4, 0, 0, 1);");
        cancelButton.setOnAction(e -> {
            playPressAnimation(cancelButton);
            setResult(null);
            close();
        });

        // Apply button
        applyButton = new Button("Apply Discount");
        applyButton.setPrefWidth(200);
        applyButton.setPrefHeight(ACTION_BUTTON_HEIGHT);
        applyButton.setFont(Font.font("System", FontWeight.BOLD, 18));
        applyButton.setFocusTraversable(false);
        applyButton.setDisable(true);
        updateApplyButtonStyle(false);

        applyButton.setOnAction(e -> {
            playPressAnimation(applyButton);
            MultiPackDiscountResult result = processDiscount();
            if (result != null) {
                setResult(result);
                close();
            }
        });

        buttonContainer.getChildren().addAll(cancelButton, applyButton);
        return buttonContainer;
    }

    private void updateApplyButtonState() {
        boolean canApply = selectedItem != null && !eligibleItems.isEmpty();
        updateApplyButtonStyle(canApply);
    }

    private void updateApplyButtonStyle(boolean enabled) {
        if (enabled) {
            applyButton.setStyle(
                    "-fx-background-color: " + PRIMARY_COLOR + "; " +
                            "-fx-text-fill: white; " +
                            "-fx-background-radius: 12; " +
                            "-fx-cursor: hand; " +
                            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 6, 0, 0, 2);");
        } else {
            applyButton.setStyle(
                    "-fx-background-color: #bdbdbd; " +
                            "-fx-text-fill: white; " +
                            "-fx-background-radius: 12; " +
                            "-fx-cursor: default;");
        }
        applyButton.setDisable(!enabled);
    }

    private MultiPackDiscountResult processDiscount() {
        if (selectedItem == null) {
            showError("Please select an item to discount");
            return null;
        }

        try {
            String deptId = selectedItem.getProduct().getDepartmentId();
            if (deptId == null) {
                showError("Item has no department");
                return null;
            }

            MultiPackDiscountSettings deptSettings = settingsService.getMultiPackDiscountSettingsForDepartment(deptId);
            if (!deptSettings.enabled) {
                showError("Multi-pack discount is not enabled for this department");
                return null;
            }

            BigDecimal discountAmount = calculateDiscount(selectedItem);
            BigDecimal discountPercent = BigDecimal.ZERO;

            if (deptSettings.discountType == MultiPackDiscountSettings.DiscountType.PERCENT) {
                discountPercent = deptSettings.discountValue;
            }

            MultiPackDiscountResult result = new MultiPackDiscountResult();
            result.item = selectedItem;
            result.discountAmount = discountAmount;
            result.discountPercent = discountPercent;
            result.discountReason = "Multi-Pack Discount (" + deptSettings.getDiscountDisplayString() + ")";
            result.isPercentage = (deptSettings.discountType == MultiPackDiscountSettings.DiscountType.PERCENT);

            // Check if manager approval is required
            if (deptSettings.requiresApproval) {
                ManagerAuthDialog authDialog = new ManagerAuthDialog();
                ManagerAuthDialog.ManagerAuthResult authResult = authDialog.showAndWait().orElse(null);

                if (authResult == null || !authResult.approved) {
                    logger.info("Manager approval denied or cancelled for multi-pack discount");
                    return null;
                }

                result.approvedBy = authResult.managerName;
                result.approvedByUsername = authResult.managerUsername;
                logger.info("Manager approval granted by: {} for multi-pack discount: {}",
                        authResult.managerUsername, discountAmount);
            }

            logger.info("Multi-pack discount applied: {} {} to {}",
                    result.isPercentage ? discountPercent + "%" : "$" + discountAmount,
                    selectedItem.getProductName());

            return result;
        } catch (Exception e) {
            logger.error("Error processing multi-pack discount", e);
            showError("Error: " + e.getMessage());
            return null;
        }
    }

    private void showError(String message) {
        errorLabel.setText("⚠ " + message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void playPressAnimation(Region node) {
        ScaleTransition pressDown = new ScaleTransition(Duration.millis(50), node);
        pressDown.setToX(0.95);
        pressDown.setToY(0.95);

        ScaleTransition pressUp = new ScaleTransition(Duration.millis(100), node);
        pressUp.setToX(1.0);
        pressUp.setToY(1.0);

        pressDown.setOnFinished(e -> pressUp.play());
        pressDown.play();
    }

    /**
     * Check if there are any eligible items in the cart
     */
    public boolean hasEligibleItems() {
        return !eligibleItems.isEmpty();
    }

    /**
     * Get the number of eligible items
     */
    public int getEligibleItemCount() {
        return eligibleItems.size();
    }

    /**
     * Result class for multi-pack discount application
     */
    public static class MultiPackDiscountResult {
        public SaleItem item;
        public BigDecimal discountAmount = BigDecimal.ZERO;
        public BigDecimal discountPercent = BigDecimal.ZERO;
        public String discountReason = "";
        public boolean isPercentage = false;
        public String approvedBy;
        public String approvedByUsername;
    }
}
