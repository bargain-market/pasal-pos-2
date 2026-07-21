package com.pos.ui.dialogs;

import com.pos.ui.components.TouchScreenComponents;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.text.NumberFormat;

/**
 * Dialog for selecting payment method (Cash, Card, EBT, Digital)
 */
public class PaymentMethodSelectionDialog extends Dialog<PaymentMethodSelectionDialog.PaymentMethodResult> {

    private java.util.List<com.pos.model.SaleItem> cartItems;
    private BigDecimal totalAmount;

    public PaymentMethodSelectionDialog(BigDecimal totalAmount, java.util.List<com.pos.model.SaleItem> cartItems) {
        this.totalAmount = totalAmount;
        this.cartItems = cartItems;
        initializeDialog();
    }

    private void initializeDialog() {
        setTitle("Select Payment Method");
        setHeaderText("Choose how the customer will pay");
        initModality(Modality.APPLICATION_MODAL);

        // Set owner window
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

        // Create main content - Touch-screen optimized
        // Create main content - Touch-screen optimized
        VBox content = TouchScreenComponents.createTouchContainer(25);
        // content.setPrefWidth(700);
        // content.setPrefHeight(600);
        // content.setMinWidth(650);
        // content.setMinHeight(550);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.7, 0.8);

        // Total Amount Display
        VBox totalSection = new VBox(10);
        totalSection.setAlignment(Pos.CENTER);
        Label totalTitleLabel = TouchScreenComponents.createSectionLabel("Total Amount");
        Label totalLabel = TouchScreenComponents.createLargeAmountLabel(
                NumberFormat.getCurrencyInstance().format(totalAmount),
                "#2a5298");
        totalSection.getChildren().addAll(totalTitleLabel, totalLabel);

        // Payment Method Buttons
        VBox buttonsSection = new VBox(20);
        buttonsSection.setAlignment(Pos.CENTER);
        buttonsSection.setPadding(new Insets(20, 0, 20, 0));

        Label methodLabel = TouchScreenComponents.createSectionLabel("Select Payment Method");
        buttonsSection.getChildren().add(methodLabel);

        // Create payment method buttons in a grid
        GridPane buttonGrid = new GridPane();
        buttonGrid.setHgap(20);
        buttonGrid.setVgap(20);
        buttonGrid.setAlignment(Pos.CENTER);
        buttonGrid.setPadding(new Insets(20));

        // Cash button (green - primary)
        Button cashBtn = TouchScreenComponents.createTouchButton("CASH", "#4CAF50", () -> {
            setResult(new PaymentMethodResult("CASH"));
            close();
        });
        cashBtn.setPrefWidth(250);
        cashBtn.setPrefHeight(100);

        // Card button (blue)
        Button cardBtn = TouchScreenComponents.createTouchButton("CARD", "#2196F3", () -> {
            setResult(new PaymentMethodResult("CARD"));
            close();
        });
        cardBtn.setPrefWidth(250);
        cardBtn.setPrefHeight(100);

        // EBT button (orange)
        boolean isEbtEligible = com.pos.service.ProductManagementService.getInstance()
                .isCartEbtEligible(cartItems);

        Button ebtBtn = TouchScreenComponents.createTouchButton("EBT", "#FF9800", () -> {
            setResult(new PaymentMethodResult("EBT"));
            close();
        });
        ebtBtn.setPrefWidth(250);
        ebtBtn.setPrefHeight(100);

        if (!isEbtEligible) {
            ebtBtn.setDisable(true);
            ebtBtn.setStyle(ebtBtn.getStyle() + "-fx-opacity: 0.5;");
        }

        // Digital button (purple)
        Button digitalBtn = TouchScreenComponents.createTouchButton("DIGITAL", "#9C27B0", () -> {
            setResult(new PaymentMethodResult("DIGITAL"));
            close();
        });
        digitalBtn.setPrefWidth(250);
        digitalBtn.setPrefHeight(100);

        // Add buttons to grid (2 columns)
        buttonGrid.add(cashBtn, 0, 0);
        buttonGrid.add(cardBtn, 1, 0);
        buttonGrid.add(ebtBtn, 0, 1);
        buttonGrid.add(digitalBtn, 1, 1);

        buttonsSection.getChildren().add(buttonGrid);

        content.getChildren().addAll(totalSection, buttonsSection);

        // Set dialog content
        getDialogPane().setContent(content);

        // Buttons
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().add(cancelButtonType);

        // Style cancel button
        Button cancelButton = (Button) getDialogPane().lookupButton(cancelButtonType);
        if (cancelButton != null) {
            cancelButton.setPrefHeight(TouchScreenComponents.TOUCH_BUTTON_HEIGHT);
            cancelButton.setMinHeight(TouchScreenComponents.TOUCH_BUTTON_HEIGHT);
            cancelButton.setPrefWidth(TouchScreenComponents.TOUCH_BUTTON_WIDTH_LARGE);
            cancelButton.setStyle(
                    "-fx-font-size: " + TouchScreenComponents.TOUCH_BUTTON_FONT_SIZE + "px; " +
                            "-fx-font-weight: bold; " +
                            "-fx-background-color: #757575; " +
                            "-fx-text-fill: white; " +
                            "-fx-background-radius: 12; " +
                            "-fx-padding: 15 40; " +
                            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 8, 0, 0, 2);");
        }

        // Style
        getDialogPane().setStyle("-fx-background-color: #f5f7fa;");

        // Set result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == cancelButtonType) {
                return null;
            }
            return null; // Results are set by button actions
        });

        // Set dialog size for touch screens
        // getDialogPane().setMinWidth(650);
        // getDialogPane().setMinHeight(550);
    }

    /**
     * Result class for payment method selection
     */
    public static class PaymentMethodResult {
        public final String paymentMethod;

        public PaymentMethodResult(String paymentMethod) {
            this.paymentMethod = paymentMethod;
        }
    }
}
