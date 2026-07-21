package com.pos.ui.dialogs;

import com.pos.ui.components.NumericKeypad;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.util.DialogHelper;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
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

/**
 * Touch-optimized dialog for applying discounts to line items or entire sale.
 * Features: Large toggle buttons, embedded numeric keypad, preset buttons,
 * live discount preview, and quick reason selection chips.
 */
public class DiscountDialog extends Dialog<DiscountDialog.DiscountResult> {

    private static final Logger logger = LoggerFactory.getLogger(DiscountDialog.class);

    // Touch-screen optimized sizes
    private static final double TOUCH_BUTTON_HEIGHT = 70.0;
    private static final double TOUCH_BUTTON_WIDTH = 150.0;
    private static final double PRESET_BUTTON_SIZE = 65.0;
    private static final double REASON_CHIP_HEIGHT = 50.0;
    private static final double ACTION_BUTTON_HEIGHT = 70.0;
    private static final double DISPLAY_FONT_SIZE = 42.0;
    private static final double LABEL_FONT_SIZE = 18.0;

    // Colors
    private static final String PRIMARY_COLOR = "#2196F3";
    private static final String SUCCESS_COLOR = "#4CAF50";
    private static final String DANGER_COLOR = "#f44336";
    private static final String NEUTRAL_COLOR = "#757575";
    private static final String WARNING_COLOR = "#FF9800";
    private static final String INACTIVE_COLOR = "#e0e0e0";
    private static final String CARD_BG_COLOR = "#ffffff";
    private static final String DIALOG_BG_COLOR = "#f5f7fa";

    // Configuration: thresholds for manager approval
    private static final BigDecimal PERCENTAGE_THRESHOLD = new BigDecimal("20"); // 20%
    private static final BigDecimal AMOUNT_THRESHOLD = new BigDecimal("50"); // $50

    // Preset discount percentages
    private static final int[] PRESET_PERCENTAGES = { 5, 10, 15, 20, 25 };

    // Preset discount reasons
    private static final String[] PRESET_REASONS = { "Damaged", "Price Match", "Loyalty", "Promo", "Other" };

    // UI Components
    private ToggleButton percentageToggle;
    private ToggleButton amountToggle;
    private ToggleGroup discountTypeGroup;
    private Label valueDisplayLabel;
    private Label discountPreviewLabel;
    private Label newTotalPreviewLabel;
    private Label errorLabel;
    private NumericKeypad numericKeypad;
    private HBox presetButtonsBox;
    private TextField customReasonField;
    private VBox customReasonContainer;
    private Button applyButton;
    private Button cancelButton;

    private StringBuilder inputBuffer = new StringBuilder();
    private BigDecimal itemTotal;
    private boolean isSaleLevel;
    private String selectedReason = "";
    private boolean isPercentageMode = true;
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

    public DiscountDialog(BigDecimal itemTotal, String itemName, boolean isSaleLevel) {
        this.itemTotal = itemTotal;
        this.isSaleLevel = isSaleLevel;
        initializeDialog(itemName);
    }

    private void initializeDialog(String itemName) {
        setTitle(isSaleLevel ? "Apply Sale Discount" : "Apply Item Discount");
        initModality(Modality.APPLICATION_MODAL);
        initStyle(StageStyle.UNDECORATED);

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

        // Main container
        VBox mainContainer = new VBox(0);
        // mainContainer.setPrefWidth(580);
        // mainContainer.setMaxWidth(620);
        mainContainer.setMaxWidth(Double.MAX_VALUE);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.5, 0.8);
        mainContainer.setStyle(
                "-fx-background-color: " + DIALOG_BG_COLOR + "; " +
                        "-fx-background-radius: 16; " +
                        "-fx-border-radius: 16; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 20, 0, 0, 5);");

        // Header
        VBox header = createHeader(itemName);

        // Content sections
        VBox discountTypeSection = createDiscountTypeSection();
        VBox valueInputSection = createValueInputSection();
        VBox previewSection = createPreviewSection();
        VBox reasonSection = createReasonSection();
        HBox actionButtons = createActionButtons();

        // Scrollable content
        VBox scrollContent = new VBox(15);
        scrollContent.setPadding(new Insets(20));
        scrollContent.getChildren().addAll(
                discountTypeSection,
                valueInputSection,
                previewSection,
                reasonSection);

        ScrollPane scrollPane = new ScrollPane(scrollContent);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // scrollPane.setPrefViewportHeight(500);
        // scrollPane.setMaxHeight(550);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        mainContainer.getChildren().addAll(header, scrollPane, actionButtons);

        // Configure dialog pane
        getDialogPane().setContent(mainContainer);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().lookupButton(ButtonType.CLOSE).setVisible(false);
        getDialogPane().setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        getDialogPane().getScene().setFill(Color.TRANSPARENT);

        // Set result converter (handled by custom buttons)
        setResultConverter(dialogButton -> null);

        // Update preview on load
        javafx.application.Platform.runLater(this::updatePreview);
    }

    private VBox createHeader(String itemName) {
        VBox header = new VBox(5);
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(20, 20, 15, 20));
        header.setStyle(
                "-fx-background-color: " + PRIMARY_COLOR + "; " +
                        "-fx-background-radius: 16 16 0 0;");

        // Title
        Label titleLabel = new Label(isSaleLevel ? "💰 Apply Sale Discount" : "🏷️ Apply Item Discount");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 22));
        titleLabel.setTextFill(Color.WHITE);

        // Subtitle with total
        String subtitle = isSaleLevel ? "Discount entire sale" : "Item: " + itemName;
        Label subtitleLabel = new Label(subtitle);
        subtitleLabel.setFont(Font.font("System", FontWeight.NORMAL, 14));
        subtitleLabel.setTextFill(Color.web("#ffffff", 0.9));

        // Total display
        Label totalLabel = new Label("Current Total: " + currencyFormat.format(itemTotal));
        totalLabel.setFont(Font.font("System", FontWeight.BOLD, 20));
        totalLabel.setTextFill(Color.WHITE);
        totalLabel.setPadding(new Insets(8, 16, 8, 16));
        totalLabel.setStyle(
                "-fx-background-color: rgba(255,255,255,0.2); " +
                        "-fx-background-radius: 8;");

        header.getChildren().addAll(titleLabel, subtitleLabel, totalLabel);
        return header;
    }

    private VBox createDiscountTypeSection() {
        VBox section = new VBox(10);
        section.setAlignment(Pos.CENTER);

        Label sectionLabel = new Label("Discount Type");
        sectionLabel.setFont(Font.font("System", FontWeight.BOLD, LABEL_FONT_SIZE));
        sectionLabel.setTextFill(Color.web("#333333"));

        // Toggle buttons container
        HBox toggleContainer = new HBox(15);
        toggleContainer.setAlignment(Pos.CENTER);

        discountTypeGroup = new ToggleGroup();

        // Percentage toggle
        percentageToggle = createTypeToggleButton("  %  ", "Percentage", true);
        percentageToggle.setToggleGroup(discountTypeGroup);
        percentageToggle.setSelected(true);

        // Amount toggle
        amountToggle = createTypeToggleButton("  $  ", "Fixed Amount", false);
        amountToggle.setToggleGroup(discountTypeGroup);

        // Handle toggle changes
        discountTypeGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) {
                // Prevent deselection
                if (oldVal != null) {
                    discountTypeGroup.selectToggle(oldVal);
                }
                return;
            }
            isPercentageMode = (newVal == percentageToggle);
            updateToggleStyles();
            updatePresetButtons();
            inputBuffer.setLength(0);
            updateValueDisplay();
            updatePreview();
        });

        toggleContainer.getChildren().addAll(percentageToggle, amountToggle);
        section.getChildren().addAll(sectionLabel, toggleContainer);
        return section;
    }

    private ToggleButton createTypeToggleButton(String icon, String text, boolean active) {
        ToggleButton btn = new ToggleButton();

        VBox content = new VBox(2);
        content.setAlignment(Pos.CENTER);

        Label iconLabel = new Label(icon);
        iconLabel.setFont(Font.font("System", FontWeight.BOLD, 28));

        Label textLabel = new Label(text);
        textLabel.setFont(Font.font("System", FontWeight.NORMAL, 14));

        content.getChildren().addAll(iconLabel, textLabel);
        btn.setGraphic(content);

        btn.setPrefWidth(TOUCH_BUTTON_WIDTH);
        btn.setPrefHeight(TOUCH_BUTTON_HEIGHT + 10);
        btn.setMinHeight(TOUCH_BUTTON_HEIGHT + 10);
        btn.setFocusTraversable(false);

        updateToggleButtonStyle(btn, active);

        btn.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            updateToggleButtonStyle(btn, isSelected);
            playPressAnimation(btn);
        });

        return btn;
    }

    private void updateToggleButtonStyle(ToggleButton btn, boolean active) {
        String bgColor = active ? PRIMARY_COLOR : INACTIVE_COLOR;
        String textColor = active ? "white" : "#666666";
        String borderColor = active ? PRIMARY_COLOR : "#cccccc";

        btn.setStyle(
                "-fx-background-color: " + bgColor + "; " +
                        "-fx-background-radius: 12; " +
                        "-fx-border-color: " + borderColor + "; " +
                        "-fx-border-width: 2; " +
                        "-fx-border-radius: 12; " +
                        "-fx-cursor: hand;");

        if (btn.getGraphic() instanceof VBox) {
            VBox content = (VBox) btn.getGraphic();
            for (var node : content.getChildren()) {
                if (node instanceof Label) {
                    ((Label) node).setTextFill(Color.web(textColor));
                }
            }
        }
    }

    private void updateToggleStyles() {
        updateToggleButtonStyle(percentageToggle, percentageToggle.isSelected());
        updateToggleButtonStyle(amountToggle, amountToggle.isSelected());
    }

    private VBox createValueInputSection() {
        VBox section = new VBox(12);
        section.setAlignment(Pos.CENTER);
        section.setPadding(new Insets(10));
        section.setStyle(
                "-fx-background-color: " + CARD_BG_COLOR + "; " +
                        "-fx-background-radius: 12; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 8, 0, 0, 2);");

        // Value display
        HBox displayContainer = new HBox(5);
        displayContainer.setAlignment(Pos.CENTER);
        displayContainer.setPadding(new Insets(15, 20, 15, 20));
        displayContainer.setStyle(
                "-fx-background-color: #f8f9fa; " +
                        "-fx-background-radius: 10; " +
                        "-fx-border-color: " + PRIMARY_COLOR + "; " +
                        "-fx-border-width: 2; " +
                        "-fx-border-radius: 10;");

        valueDisplayLabel = new Label("0");
        valueDisplayLabel.setFont(Font.font("System", FontWeight.BOLD, DISPLAY_FONT_SIZE));
        valueDisplayLabel.setTextFill(Color.web("#333333"));
        valueDisplayLabel.setMinWidth(150);
        valueDisplayLabel.setAlignment(Pos.CENTER_RIGHT);

        Label suffixLabel = new Label("%");
        suffixLabel.setFont(Font.font("System", FontWeight.BOLD, 28));
        suffixLabel.setTextFill(Color.web(PRIMARY_COLOR));

        displayContainer.getChildren().addAll(valueDisplayLabel, suffixLabel);

        // Preset buttons
        Label presetLabel = new Label("Quick Select");
        presetLabel.setFont(Font.font("System", FontWeight.NORMAL, 14));
        presetLabel.setTextFill(Color.web("#666666"));

        presetButtonsBox = new HBox(8);
        presetButtonsBox.setAlignment(Pos.CENTER);
        presetButtonsBox.setFillHeight(true);
        updatePresetButtons();

        // Numeric keypad with Enter button
        numericKeypad = new NumericKeypad(true, true, false);
        numericKeypad.setListener(this::handleKeypadInput);
        numericKeypad.setEnterListener(this::handleEnterPressed);
        numericKeypad.setMaxWidth(350);
        numericKeypad.setPrefHeight(280);

        section.getChildren().addAll(displayContainer, presetLabel, presetButtonsBox, numericKeypad);
        return section;
    }

    private void updatePresetButtons() {
        presetButtonsBox.getChildren().clear();

        if (isPercentageMode) {
            for (int pct : PRESET_PERCENTAGES) {
                Button presetBtn = createPresetButton(pct + "%", String.valueOf(pct));
                presetButtonsBox.getChildren().add(presetBtn);
            }
        } else {
            // Fixed amount presets based on item total
            double total = itemTotal.doubleValue();
            int[] amounts;
            if (total <= 10) {
                amounts = new int[] { 1, 2, 3, 5 };
            } else if (total <= 50) {
                amounts = new int[] { 2, 5, 10, 15 };
            } else if (total <= 100) {
                amounts = new int[] { 5, 10, 20, 25 };
            } else {
                amounts = new int[] { 10, 25, 50, 100 };
            }
            for (int amt : amounts) {
                Button presetBtn = createPresetButton("$" + amt, String.valueOf(amt));
                presetButtonsBox.getChildren().add(presetBtn);
            }
        }
    }

    private Button createPresetButton(String label, String value) {
        Button btn = new Button(label);
        btn.setPrefHeight(55);
        btn.setMinHeight(55);
        btn.setMinWidth(60);
        btn.setPrefWidth(70);
        btn.setMaxWidth(90);
        btn.setFont(Font.font("System", FontWeight.BOLD, 18));
        btn.setFocusTraversable(false);
        btn.setWrapText(false);
        btn.setStyle(
                "-fx-background-color: " + PRIMARY_COLOR + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-padding: 8 12 8 12; " +
                        "-fx-cursor: hand; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 4, 0, 0, 1);");

        btn.setOnMouseEntered(e -> btn.setStyle(
                "-fx-background-color: #1976D2; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-padding: 8 12 8 12; " +
                        "-fx-cursor: hand; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 6, 0, 0, 2);"));

        btn.setOnMouseExited(e -> btn.setStyle(
                "-fx-background-color: " + PRIMARY_COLOR + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-padding: 8 12 8 12; " +
                        "-fx-cursor: hand; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 4, 0, 0, 1);"));

        btn.setOnAction(e -> {
            playPressAnimation(btn);
            inputBuffer.setLength(0);
            inputBuffer.append(value);
            updateValueDisplay();
            updatePreview();
        });

        return btn;
    }

    private VBox createPreviewSection() {
        VBox section = new VBox(8);
        section.setAlignment(Pos.CENTER);
        section.setPadding(new Insets(15));
        section.setStyle(
                "-fx-background-color: linear-gradient(to right, #e8f5e9, #c8e6c9); " +
                        "-fx-background-radius: 12;");

        Label previewTitle = new Label("Preview");
        previewTitle.setFont(Font.font("System", FontWeight.BOLD, 14));
        previewTitle.setTextFill(Color.web("#2E7D32"));

        discountPreviewLabel = new Label("Discount: $0.00");
        discountPreviewLabel.setFont(Font.font("System", FontWeight.BOLD, 20));
        discountPreviewLabel.setTextFill(Color.web(SUCCESS_COLOR));

        newTotalPreviewLabel = new Label("New Total: " + currencyFormat.format(itemTotal));
        newTotalPreviewLabel.setFont(Font.font("System", FontWeight.BOLD, 24));
        newTotalPreviewLabel.setTextFill(Color.web("#1B5E20"));

        // Error label (hidden by default)
        errorLabel = new Label();
        errorLabel.setFont(Font.font("System", FontWeight.NORMAL, 14));
        errorLabel.setTextFill(Color.web(DANGER_COLOR));
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);

        section.getChildren().addAll(previewTitle, discountPreviewLabel, newTotalPreviewLabel, errorLabel);
        return section;
    }

    private VBox createReasonSection() {
        VBox section = new VBox(10);
        section.setAlignment(Pos.CENTER_LEFT);

        Label reasonTitle = new Label("Reason for Discount");
        reasonTitle.setFont(Font.font("System", FontWeight.BOLD, LABEL_FONT_SIZE));
        reasonTitle.setTextFill(Color.web("#333333"));

        // Reason chips - using FlowPane for wrapping
        FlowPane chipsPane = new FlowPane(8, 8);
        chipsPane.setAlignment(Pos.CENTER);

        for (String reason : PRESET_REASONS) {
            Button chip = createReasonChip(reason);
            chipsPane.getChildren().add(chip);
        }

        // Custom reason input (shown when "Other" is selected)
        customReasonContainer = new VBox(8);
        customReasonContainer.setVisible(false);
        customReasonContainer.setManaged(false);
        customReasonContainer.setAlignment(Pos.CENTER);

        Label customLabel = new Label("Enter custom reason:");
        customLabel.setFont(Font.font("System", FontWeight.NORMAL, 14));
        customLabel.setTextFill(Color.web("#666666"));

        customReasonField = new TextField();
        customReasonField.setPromptText("Type reason here...");
        customReasonField.setPrefHeight(50);
        customReasonField.setFont(Font.font("System", 16));
        customReasonField.setStyle(
                "-fx-background-radius: 8; " +
                        "-fx-border-radius: 8; " +
                        "-fx-border-color: #cccccc; " +
                        "-fx-border-width: 1;");
        customReasonField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (selectedReason.equals("Other")) {
                updateApplyButtonState();
            }
        });

        customReasonContainer.getChildren().addAll(customLabel, customReasonField);

        section.getChildren().addAll(reasonTitle, chipsPane, customReasonContainer);
        return section;
    }

    private Button createReasonChip(String reason) {
        Button chip = new Button(reason);
        chip.setPrefHeight(REASON_CHIP_HEIGHT);
        chip.setMinHeight(REASON_CHIP_HEIGHT);
        chip.setPadding(new Insets(10, 20, 10, 20));
        chip.setFont(Font.font("System", FontWeight.NORMAL, 15));
        chip.setFocusTraversable(false);

        updateReasonChipStyle(chip, false);

        chip.setOnAction(e -> {
            playPressAnimation(chip);
            selectReason(reason, chip);
        });

        return chip;
    }

    private void updateReasonChipStyle(Button chip, boolean selected) {
        if (selected) {
            chip.setStyle(
                    "-fx-background-color: " + SUCCESS_COLOR + "; " +
                            "-fx-text-fill: white; " +
                            "-fx-background-radius: 25; " +
                            "-fx-cursor: hand; " +
                            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 4, 0, 0, 1);");
        } else {
            chip.setStyle(
                    "-fx-background-color: white; " +
                            "-fx-text-fill: #333333; " +
                            "-fx-background-radius: 25; " +
                            "-fx-border-color: #cccccc; " +
                            "-fx-border-width: 1; " +
                            "-fx-border-radius: 25; " +
                            "-fx-cursor: hand;");
        }
    }

    private void selectReason(String reason, Button selectedChip) {
        selectedReason = reason;

        // Update all chip styles
        if (selectedChip.getParent() instanceof FlowPane) {
            FlowPane parent = (FlowPane) selectedChip.getParent();
            for (var node : parent.getChildren()) {
                if (node instanceof Button) {
                    Button chip = (Button) node;
                    updateReasonChipStyle(chip, chip == selectedChip);
                }
            }
        }

        // Show/hide custom reason field
        boolean showCustom = "Other".equals(reason);
        customReasonContainer.setVisible(showCustom);
        customReasonContainer.setManaged(showCustom);

        if (showCustom) {
            javafx.application.Platform.runLater(() -> customReasonField.requestFocus());
        }

        updateApplyButtonState();
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
        cancelButton.setPrefWidth(TOUCH_BUTTON_WIDTH);
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
        applyButton.setPrefWidth(220);
        applyButton.setPrefHeight(ACTION_BUTTON_HEIGHT);
        applyButton.setFont(Font.font("System", FontWeight.BOLD, 18));
        applyButton.setFocusTraversable(false);
        applyButton.setDisable(true);
        updateApplyButtonStyle(false);

        applyButton.setOnAction(e -> {
            playPressAnimation(applyButton);
            DiscountResult result = processDiscount();
            if (result != null) {
                setResult(result);
                close();
            }
        });

        buttonContainer.getChildren().addAll(cancelButton, applyButton);
        return buttonContainer;
    }

    private void updateApplyButtonStyle(boolean enabled) {
        if (enabled) {
            applyButton.setStyle(
                    "-fx-background-color: " + SUCCESS_COLOR + "; " +
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

    private void handleKeypadInput(String key) {
        switch (key) {
            case "C":
                inputBuffer.setLength(0);
                break;
            case "⌫":
                if (inputBuffer.length() > 0) {
                    inputBuffer.setLength(inputBuffer.length() - 1);
                }
                break;
            case ".":
                if (!isPercentageMode && !inputBuffer.toString().contains(".")) {
                    if (inputBuffer.length() == 0) {
                        inputBuffer.append("0");
                    }
                    inputBuffer.append(".");
                }
                break;
            case "00":
                if (inputBuffer.length() < 6) {
                    inputBuffer.append("00");
                }
                break;
            default:
                // Limit input length
                if (inputBuffer.length() < 8) {
                    // Prevent leading zeros
                    if (inputBuffer.length() == 1 && inputBuffer.charAt(0) == '0' && !".".equals(key)) {
                        inputBuffer.setLength(0);
                    }
                    inputBuffer.append(key);
                }
                break;
        }
        updateValueDisplay();
        updatePreview();
    }

    private void handleEnterPressed() {
        // Only apply if the button is enabled
        if (!applyButton.isDisabled()) {
            playPressAnimation(applyButton);
            DiscountResult result = processDiscount();
            if (result != null) {
                setResult(result);
                close();
            }
        }
    }

    private void updateValueDisplay() {
        String displayText = inputBuffer.length() > 0 ? inputBuffer.toString() : "0";
        valueDisplayLabel.setText(displayText);

        // Update the suffix label
        HBox parent = (HBox) valueDisplayLabel.getParent();
        if (parent.getChildren().size() > 1) {
            Label suffix = (Label) parent.getChildren().get(1);
            suffix.setText(isPercentageMode ? "%" : "$");
        }
    }

    private void updatePreview() {
        clearError();
        BigDecimal discountAmount = BigDecimal.ZERO;
        BigDecimal newTotal = itemTotal;
        boolean valid = false;

        try {
            String valueStr = inputBuffer.toString();
            if (valueStr.isEmpty() || valueStr.equals("0")) {
                discountPreviewLabel.setText("Discount: $0.00");
                newTotalPreviewLabel.setText("New Total: " + currencyFormat.format(itemTotal));
                updateApplyButtonState();
                return;
            }

            BigDecimal value = new BigDecimal(valueStr);

            if (isPercentageMode) {
                if (value.compareTo(BigDecimal.ZERO) > 0 && value.compareTo(new BigDecimal("100")) <= 0) {
                    discountAmount = itemTotal.multiply(value)
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    valid = true;
                } else if (value.compareTo(new BigDecimal("100")) > 0) {
                    showError("Percentage cannot exceed 100%");
                }
            } else {
                if (value.compareTo(BigDecimal.ZERO) > 0 && value.compareTo(itemTotal) <= 0) {
                    discountAmount = value;
                    valid = true;
                } else if (value.compareTo(itemTotal) > 0) {
                    showError("Amount cannot exceed total");
                }
            }

            if (valid) {
                newTotal = itemTotal.subtract(discountAmount);
                discountPreviewLabel.setText("Discount: " + currencyFormat.format(discountAmount));
                discountPreviewLabel.setTextFill(Color.web(SUCCESS_COLOR));
                newTotalPreviewLabel.setText("New Total: " + currencyFormat.format(newTotal));

                // Highlight if approaching threshold
                if ((isPercentageMode && value.compareTo(PERCENTAGE_THRESHOLD) > 0) ||
                        (!isPercentageMode && discountAmount.compareTo(AMOUNT_THRESHOLD) > 0)) {
                    discountPreviewLabel.setTextFill(Color.web(WARNING_COLOR));
                }
            }
        } catch (NumberFormatException e) {
            // Invalid input, show zero
        }

        updateApplyButtonState();
    }

    private void updateApplyButtonState() {
        boolean hasValue = inputBuffer.length() > 0 && !inputBuffer.toString().equals("0");
        boolean hasReason = !selectedReason.isEmpty();

        if ("Other".equals(selectedReason)) {
            hasReason = customReasonField.getText() != null &&
                    !customReasonField.getText().trim().isEmpty();
        }

        boolean valid = hasValue && hasReason && !errorLabel.isVisible();
        updateApplyButtonStyle(valid);
    }

    private void showError(String message) {
        errorLabel.setText("⚠ " + message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);

        // Fade in animation
        FadeTransition fade = new FadeTransition(Duration.millis(200), errorLabel);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }

    private void clearError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    private DiscountResult processDiscount() {
        String valueStr = inputBuffer.toString();
        String reason = "Other".equals(selectedReason) ? customReasonField.getText().trim() : selectedReason;

        if (valueStr.isEmpty() || valueStr.equals("0")) {
            showError("Please enter a discount value");
            return null;
        }

        if (reason.isEmpty()) {
            showError("Please select a reason for the discount");
            return null;
        }

        try {
            BigDecimal value = new BigDecimal(valueStr);

            if (value.compareTo(BigDecimal.ZERO) <= 0) {
                showError("Discount value must be greater than zero");
                return null;
            }

            BigDecimal discountAmount;
            BigDecimal discountPercent = BigDecimal.ZERO;

            if (isPercentageMode) {
                if (value.compareTo(new BigDecimal("100")) > 0) {
                    showError("Percentage cannot exceed 100%");
                    return null;
                }
                discountPercent = value;
                discountAmount = itemTotal.multiply(value)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            } else {
                if (value.compareTo(itemTotal) > 0) {
                    showError("Discount cannot exceed total");
                    return null;
                }
                discountAmount = value;
            }

            // Check if manager approval is required
            boolean requiresApproval = false;
            if (isPercentageMode && value.compareTo(PERCENTAGE_THRESHOLD) > 0) {
                requiresApproval = true;
            } else if (!isPercentageMode && discountAmount.compareTo(AMOUNT_THRESHOLD) > 0) {
                requiresApproval = true;
            }

            DiscountResult result = new DiscountResult();
            result.discountAmount = discountAmount;
            result.discountPercent = discountPercent;
            result.discountReason = reason;
            result.isPercentage = isPercentageMode;
            result.requiresApproval = requiresApproval;

            // Request manager approval if needed
            if (requiresApproval) {
                ManagerAuthDialog authDialog = new ManagerAuthDialog();
                ManagerAuthDialog.ManagerAuthResult authResult = authDialog.showAndWait().orElse(null);

                if (authResult == null || !authResult.approved) {
                    logger.info("Manager approval denied or cancelled");
                    return null;
                }

                result.approvedBy = authResult.managerName;
                result.approvedByUsername = authResult.managerUsername;
                logger.info("Manager approval granted by: {} for discount: {}",
                        authResult.managerUsername, discountAmount);
            }

            return result;
        } catch (NumberFormatException e) {
            showError("Invalid number format");
            return null;
        } catch (Exception e) {
            logger.error("Error processing discount", e);
            showError("Error: " + e.getMessage());
            return null;
        }
    }

    private void playPressAnimation(ButtonBase button) {
        ScaleTransition pressDown = new ScaleTransition(Duration.millis(50), button);
        pressDown.setToX(0.95);
        pressDown.setToY(0.95);

        ScaleTransition pressUp = new ScaleTransition(Duration.millis(100), button);
        pressUp.setToX(1.0);
        pressUp.setToY(1.0);

        pressDown.setOnFinished(e -> pressUp.play());
        pressDown.play();
    }

    /**
     * Result class for discount application
     */
    public static class DiscountResult {
        public BigDecimal discountAmount = BigDecimal.ZERO;
        public BigDecimal discountPercent = BigDecimal.ZERO;
        public String discountReason = "";
        public boolean isPercentage = false;
        public boolean requiresApproval = false;
        public String approvedBy;
        public String approvedByUsername;
    }
}
