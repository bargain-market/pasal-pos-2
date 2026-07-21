package com.pos.ui.dialogs;

import com.pos.ui.components.ToastNotification;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.TouchTextField;
import com.pos.util.ResponsiveHelper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Dialog for processing cash payments with amount received and change
 * calculation
 */
public class CashPaymentDialog extends Dialog<CashPaymentDialog.CashPaymentResult> {

    private static final Logger logger = LoggerFactory.getLogger(CashPaymentDialog.class);

    private BigDecimal totalAmount;
    private TouchTextField amountReceivedField;
    private Label totalLabel;
    private Label changeLabel;
    private Button completeButton;

    public CashPaymentDialog(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
        initializeDialog();
    }

    private void initializeDialog() {
        setTitle("Cash Payment");
        setHeaderText("Enter amount received from customer");
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

        // Create main content - Touch-screen optimized size
        VBox content = TouchScreenComponents.createTouchContainer(25);
        // Responsive sizing handled by dialog pane
        content.setMaxWidth(Double.MAX_VALUE);
        content.setMaxHeight(Double.MAX_VALUE);

        // Total Amount Display (Large and Prominent)
        VBox totalSection = new VBox(10);
        totalSection.setAlignment(Pos.CENTER);
        Label totalTitleLabel = TouchScreenComponents.createSectionLabel("Total Amount Due");

        totalLabel = TouchScreenComponents.createLargeAmountLabel(
                NumberFormat.getCurrencyInstance().format(totalAmount),
                "#2a5298");

        totalSection.getChildren().addAll(totalTitleLabel, totalLabel);

        // Amount Received Input Section
        VBox receivedSection = new VBox(15);
        receivedSection.setAlignment(Pos.CENTER);
        Label receivedTitleLabel = TouchScreenComponents.createMediumLabel("Amount Received", "#333");

        amountReceivedField = TouchScreenComponents.createTouchOnlyNumericField("0.00");
        amountReceivedField.setTextFieldPrefWidth(400);
        HBox.setMargin(amountReceivedField, new Insets(0, 0, 0, 0));

        // Format input as user types
        amountReceivedField.textProperty().addListener((obs, oldVal, newVal) -> {
            formatCashInput(amountReceivedField.getTextField(), newVal);
            updateChange();
        });

        // Handle Enter key
        amountReceivedField.setOnAction(e -> {
            if (isValidAmount()) {
                completePayment();
            }
        });

        receivedSection.getChildren().addAll(receivedTitleLabel, amountReceivedField);

        // Quick Amount Buttons - Touch optimized
        VBox quickButtonsSection = new VBox(15);
        quickButtonsSection.setAlignment(Pos.CENTER);
        Label quickButtonsLabel = TouchScreenComponents.createSectionLabel("Quick Amount");

        // First row: Exact Amount and Next Dollar
        HBox row1 = TouchScreenComponents.createButtonRow(15);

        Button exactBtn = TouchScreenComponents.createPrimaryButton("Exact", () -> {
            amountReceivedField.setText(totalAmount.toString());
            amountReceivedField.focusTextField();
            amountReceivedField.getTextField().selectAll();
            updateChange();
        });
        exactBtn.setPrefWidth(190);

        // Calculate next dollar (or next $5/10 if needed, but next dollar is standard)
        BigDecimal nextDollar = totalAmount.setScale(0, java.math.RoundingMode.UP);
        if (nextDollar.compareTo(totalAmount) == 0) {
            nextDollar = nextDollar.add(BigDecimal.ONE);
        }
        final BigDecimal nextDollarAmount = nextDollar;

        Button nextDollarBtn = TouchScreenComponents.createSecondaryButton("Next $" + nextDollarAmount, () -> {
            amountReceivedField.setText(nextDollarAmount.toString());
            amountReceivedField.focusTextField();
            updateChange();
        });
        nextDollarBtn.setPrefWidth(190);

        row1.getChildren().addAll(exactBtn, nextDollarBtn);

        // Second row: Smart Suggestions
        HBox row2 = TouchScreenComponents.createButtonRow(15);

        List<BigDecimal> suggestions = getSuggestedAmounts(totalAmount);

        // Add first batch of suggestions to row 2
        for (int i = 0; i < Math.min(suggestions.size(), 3); i++) {
            BigDecimal amount = suggestions.get(i);
            NumberFormat nf = NumberFormat.getCurrencyInstance();
            if (amount.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
                nf.setMaximumFractionDigits(0);
            }
            Button btn = TouchScreenComponents.createSecondaryButton(nf.format(amount), () -> setAmount(amount));
            row2.getChildren().add(btn);
        }

        // Third row: More Suggestions (if any) and Clear
        HBox row3 = TouchScreenComponents.createButtonRow(15);

        // Add remaining suggestions to row 3 (up to 2 more)
        for (int i = 3; i < Math.min(suggestions.size(), 5); i++) {
            BigDecimal amount = suggestions.get(i);
            NumberFormat nf = NumberFormat.getCurrencyInstance();
            if (amount.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
                nf.setMaximumFractionDigits(0);
            }
            Button btn = TouchScreenComponents.createSecondaryButton(nf.format(amount), () -> setAmount(amount));
            row3.getChildren().add(btn);
        }

        Button clearBtn = TouchScreenComponents.createDangerButton("Clear", () -> {
            amountReceivedField.setText("0.00");
            amountReceivedField.focusTextField();
            updateChange();
        });
        clearBtn.setPrefWidth(150);
        row3.getChildren().add(clearBtn);

        quickButtonsSection.getChildren().addAll(quickButtonsLabel, row1, row2, row3);

        // Change Display (Large and Prominent)
        VBox changeSection = new VBox(10);
        changeSection.setAlignment(Pos.CENTER);
        Label changeTitleLabel = TouchScreenComponents.createSectionLabel("Change");

        changeLabel = TouchScreenComponents.createLargeAmountLabel("$0.00", "#4CAF50");

        changeSection.getChildren().addAll(changeTitleLabel, changeLabel);

        content.getChildren().addAll(totalSection, receivedSection, quickButtonsSection, changeSection);

        // Set dialog content
        getDialogPane().setContent(content);

        // Buttons
        ButtonType completeButtonType = new ButtonType("Complete Payment", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(completeButtonType, cancelButtonType);

        // Get the complete button and disable it initially - Touch optimized
        completeButton = (Button) getDialogPane().lookupButton(completeButtonType);
        completeButton.setDisable(true);
        completeButton.setPrefHeight(TouchScreenComponents.TOUCH_BUTTON_HEIGHT);
        completeButton.setMinHeight(TouchScreenComponents.TOUCH_BUTTON_HEIGHT);
        completeButton.setPrefWidth(TouchScreenComponents.TOUCH_BUTTON_WIDTH_XLARGE);
        completeButton.setStyle(
                "-fx-font-size: " + TouchScreenComponents.TOUCH_BUTTON_FONT_SIZE + "px; " +
                        "-fx-font-weight: bold; " +
                        "-fx-background-color: #4CAF50; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-padding: 15 40; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 8, 0, 0, 2);");

        // Update cancel button style too
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
            if (dialogButton == completeButtonType) {
                return processPayment();
            }
            return null;
        });

        // Focus amount field when dialog opens
        javafx.application.Platform.runLater(() -> {
            amountReceivedField.focusTextField();
            amountReceivedField.getTextField().selectAll();
        });

        // Set dialog size for touch screens using ResponsiveHelper
        // 80% width, 85% height for payment screen to ensure keypad is large enough
        ResponsiveHelper.setupResponsiveDialog(this, 0.8, 0.85);
    }

    /**
     * Generate smart suggestions for cash amounts based on total
     */
    private List<BigDecimal> getSuggestedAmounts(BigDecimal total) {
        Set<BigDecimal> amounts = new TreeSet<>();
        BigDecimal nextDollar = total.setScale(0, RoundingMode.UP);
        if (nextDollar.compareTo(total) == 0) {
            nextDollar = nextDollar.add(BigDecimal.ONE);
        }

        // Add next multiples
        int[] denominations = { 5, 10, 20, 50, 100 };
        for (int bill : denominations) {
            BigDecimal billAmt = new BigDecimal(bill);
            BigDecimal multiple = total.divide(billAmt, 0, RoundingMode.CEILING).multiply(billAmt);

            if (multiple.compareTo(nextDollar) > 0) {
                amounts.add(multiple);
            } else {
                BigDecimal nextMultiple = multiple.add(billAmt);
                if (nextMultiple.compareTo(nextDollar) > 0) {
                    amounts.add(nextMultiple);
                }
            }
        }

        return new ArrayList<>(amounts);
    }

    private void setAmount(BigDecimal amount) {
        amountReceivedField.setText(amount.toString());
        amountReceivedField.focusTextField();
        updateChange();
    }

    private void formatCashInput(javafx.scene.control.TextField field, String newValue) {
        if (newValue == null || newValue.isEmpty()) {
            updateChange();
            return;
        }

        // Remove any currency symbols or commas
        String cleaned = newValue.replaceAll("[^0-9.]", "");

        // Ensure only one decimal point
        int dotIndex = cleaned.indexOf('.');
        if (dotIndex >= 0) {
            String beforeDot = cleaned.substring(0, dotIndex);
            String afterDot = cleaned.substring(dotIndex + 1);
            // Limit to 2 decimal places
            if (afterDot.length() > 2) {
                afterDot = afterDot.substring(0, 2);
            }
            cleaned = beforeDot + "." + afterDot;
        }

        // Update field if value changed (avoid infinite loop)
        if (!cleaned.equals(newValue)) {
            int caretPosition = field.getCaretPosition();
            field.setText(cleaned);
            // Try to maintain cursor position
            if (caretPosition <= cleaned.length()) {
                field.positionCaret(caretPosition);
            }
        }
    }

    private void updateChange() {
        try {
            String amountStr = amountReceivedField.getText();
            if (amountStr == null || amountStr.trim().isEmpty()) {
                changeLabel.setText("$0.00");
                changeLabel.setStyle(
                        "-fx-font-size: " + TouchScreenComponents.TOUCH_XLARGE_LABEL_FONT_SIZE + "px; " +
                                "-fx-font-weight: bold; " +
                                "-fx-text-fill: #666;");
                completeButton.setDisable(true);
                return;
            }

            BigDecimal amountReceived = new BigDecimal(amountStr.replaceAll("[^0-9.]", ""));
            BigDecimal change = amountReceived.subtract(totalAmount);

            NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
            changeLabel.setText(currencyFormat.format(change));

            // Update button state and change color
            if (change.compareTo(BigDecimal.ZERO) >= 0) {
                // Valid payment - green
                changeLabel.setStyle(
                        "-fx-font-size: " + TouchScreenComponents.TOUCH_XLARGE_LABEL_FONT_SIZE + "px; " +
                                "-fx-font-weight: bold; " +
                                "-fx-text-fill: #4CAF50;");
                completeButton.setDisable(false);
            } else {
                // Insufficient payment - red
                changeLabel.setStyle(
                        "-fx-font-size: " + TouchScreenComponents.TOUCH_XLARGE_LABEL_FONT_SIZE + "px; " +
                                "-fx-font-weight: bold; " +
                                "-fx-text-fill: #f44336;");
                completeButton.setDisable(true);
            }
        } catch (Exception e) {
            // Invalid input
            changeLabel.setText("$0.00");
            changeLabel.setStyle(
                    "-fx-font-size: " + TouchScreenComponents.TOUCH_XLARGE_LABEL_FONT_SIZE + "px; " +
                            "-fx-font-weight: bold; " +
                            "-fx-text-fill: #666;");
            completeButton.setDisable(true);
        }
    }

    private boolean isValidAmount() {
        try {
            String amountStr = amountReceivedField.getText();
            if (amountStr == null || amountStr.trim().isEmpty()) {
                return false;
            }
            BigDecimal amountReceived = new BigDecimal(amountStr.replaceAll("[^0-9.]", ""));
            return amountReceived.compareTo(totalAmount) >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void completePayment() {
        if (isValidAmount()) {
            CashPaymentResult result = processPayment();
            if (result != null) {
                setResult(result);
            }
        }
    }

    private CashPaymentResult processPayment() {
        try {
            String amountStr = amountReceivedField.getText();
            if (amountStr == null || amountStr.trim().isEmpty()) {
                showError("Please enter the amount received");
                return null;
            }

            BigDecimal amountReceived = new BigDecimal(amountStr.replaceAll("[^0-9.]", ""));

            if (amountReceived.compareTo(BigDecimal.ZERO) <= 0) {
                showError("Amount received must be greater than zero");
                return null;
            }

            if (amountReceived.compareTo(totalAmount) < 0) {
                showError("Amount received must be at least equal to the total amount");
                return null;
            }

            BigDecimal change = amountReceived.subtract(totalAmount);

            CashPaymentResult result = new CashPaymentResult(amountReceived, change);
            logger.info("Cash payment processed: received={}, change={}", amountReceived, change);
            return result;
        } catch (NumberFormatException e) {
            showError("Invalid amount format. Please enter a valid number.");
            return null;
        } catch (Exception e) {
            logger.error("Error processing cash payment", e);
            showError("Error processing payment: " + e.getMessage());
            return null;
        }
    }

    private void showError(String message) {
        Window ownerWindow = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
        ToastNotification.showError(message, ownerWindow);
    }

    /**
     * Result class for cash payment
     */
    public static class CashPaymentResult {
        public final BigDecimal amountReceived;
        public final BigDecimal change;

        public CashPaymentResult(BigDecimal amountReceived, BigDecimal change) {
            this.amountReceived = amountReceived;
            this.change = change;
        }
    }
}
