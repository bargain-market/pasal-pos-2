package com.pos.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Reusable touch-screen optimized components for POS systems
 * All components are designed for touch interaction with larger hit areas
 */
public class TouchScreenComponents {

    // Touch-screen optimized sizes
    public static final double TOUCH_BUTTON_HEIGHT = 70.0;
    public static final double TOUCH_BUTTON_WIDTH_MEDIUM = 150.0;
    public static final double TOUCH_BUTTON_WIDTH_LARGE = 200.0;
    public static final double TOUCH_BUTTON_WIDTH_XLARGE = 300.0;
    public static final double TOUCH_INPUT_HEIGHT = 70.0;
    public static final double TOUCH_INPUT_HEIGHT_COMPACT = 45.0; // Compact height
    public static final double TOUCH_INPUT_FONT_SIZE = 28.0;
    public static final double TOUCH_INPUT_FONT_SIZE_COMPACT = 16.0; // Compact font size
    public static final double TOUCH_BUTTON_FONT_SIZE = 18.0;
    public static final double TOUCH_LABEL_FONT_SIZE = 20.0;
    public static final double TOUCH_LARGE_LABEL_FONT_SIZE = 42.0;
    public static final double TOUCH_XLARGE_LABEL_FONT_SIZE = 56.0;

    /**
     * Create a touch-screen optimized button
     */
    public static Button createTouchButton(String text, String color, Runnable action) {
        Button btn = new Button(text);
        btn.setPrefHeight(TOUCH_BUTTON_HEIGHT);
        btn.setMinHeight(TOUCH_BUTTON_HEIGHT);
        btn.setFont(Font.font("System", FontWeight.BOLD, TOUCH_BUTTON_FONT_SIZE));
        btn.setStyle(
                "-fx-background-color: " + color + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-cursor: hand; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 8, 0, 0, 2);");

        // Hover effects for better feedback
        btn.setOnMouseEntered(e -> btn.setStyle(
                "-fx-background-color: " + darkenColor(color) + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-cursor: hand; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 10, 0, 0, 3);"));
        btn.setOnMouseExited(e -> btn.setStyle(
                "-fx-background-color: " + color + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-cursor: hand; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 8, 0, 0, 2);"));

        if (action != null) {
            btn.setOnAction(e -> action.run());
        }

        return btn;
    }

    /**
     * Create a primary action button (green, large)
     */
    public static Button createPrimaryButton(String text, Runnable action) {
        Button btn = createTouchButton(text, "#4CAF50", action);
        btn.setPrefWidth(TOUCH_BUTTON_WIDTH_XLARGE);
        return btn;
    }

    /**
     * Create a secondary action button (blue, medium)
     */
    public static Button createSecondaryButton(String text, Runnable action) {
        Button btn = createTouchButton(text, "#2196F3", action);
        btn.setPrefWidth(TOUCH_BUTTON_WIDTH_MEDIUM);
        return btn;
    }

    /**
     * Create a danger button (red, medium)
     */
    public static Button createDangerButton(String text, Runnable action) {
        Button btn = createTouchButton(text, "#f44336", action);
        btn.setPrefWidth(TOUCH_BUTTON_WIDTH_MEDIUM);
        return btn;
    }

    /**
     * Create a touch-screen optimized text field (legacy method - keyboard enabled)
     * 
     * @deprecated Use createTouchOnlyTextField instead for touch-only input
     */
    @Deprecated
    public static TextField createTouchTextField(String promptText) {
        TextField field = new TextField();
        field.setPromptText(promptText);
        field.setPrefHeight(TOUCH_INPUT_HEIGHT);
        field.setMinHeight(TOUCH_INPUT_HEIGHT);
        field.setFont(Font.font("System", FontWeight.BOLD, TOUCH_INPUT_FONT_SIZE));
        field.setStyle(
                "-fx-alignment: center; " +
                        "-fx-background-radius: 12; " +
                        "-fx-border-radius: 12; " +
                        "-fx-border-color: #ddd; " +
                        "-fx-border-width: 3; " +
                        "-fx-background-color: white; " +
                        "-fx-padding: 15px 20px;");

        // Focus style
        field.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (isNowFocused) {
                field.setStyle(
                        "-fx-alignment: center; " +
                                "-fx-background-radius: 12; " +
                                "-fx-border-radius: 12; " +
                                "-fx-border-color: #2a5298; " +
                                "-fx-border-width: 4; " +
                                "-fx-background-color: white; " +
                                "-fx-padding: 15px 20px; " +
                                "-fx-effect: dropshadow(gaussian, rgba(42,82,152,0.3), 8, 0, 0, 2);");
            } else {
                field.setStyle(
                        "-fx-alignment: center; " +
                                "-fx-background-radius: 12; " +
                                "-fx-border-radius: 12; " +
                                "-fx-border-color: #ddd; " +
                                "-fx-border-width: 3; " +
                                "-fx-background-color: white; " +
                                "-fx-padding: 15px 20px;");
            }
        });

        return field;
    }

    /**
     * Create a touch-only text field with numeric keypad
     */
    public static TouchTextField createTouchOnlyTextField(String promptText, TouchTextField.KeypadType keypadType) {
        return new TouchTextField(promptText, keypadType);
    }

    /**
     * Create a touch-only text field with alphanumeric keypad (default)
     */
    public static TouchTextField createTouchOnlyTextField(String promptText) {
        return new TouchTextField(promptText);
    }

    /**
     * Create a touch-only text field with numeric keypad
     */
    public static TouchTextField createTouchOnlyNumericField(String promptText) {
        return new TouchTextField(promptText, TouchTextField.KeypadType.NUMERIC);
    }

    /**
     * Create a compact touch-only text field (smaller height and font)
     */
    public static TouchTextField createCompactTouchTextField(String promptText) {
        TouchTextField field = new TouchTextField(promptText);
        field.setCompactMode(true);
        return field;
    }

    /**
     * Create a compact touch-only numeric text field
     */
    public static TouchTextField createCompactTouchNumericField(String promptText) {
        TouchTextField field = new TouchTextField(promptText, TouchTextField.KeypadType.NUMERIC);
        field.setCompactMode(true);
        return field;
    }

    /**
     * Create a touch-only text area with full keypad
     */
    public static TouchTextArea createTouchOnlyTextArea(String promptText) {
        return new TouchTextArea(promptText);
    }

    /**
     * Create a touch-only password field with numeric keypad
     */
    public static TouchPasswordField createTouchOnlyPasswordField(String promptText) {
        return new TouchPasswordField(promptText);
    }

    /**
     * Create a touch-only password field with numeric keypad (with decimal support)
     */
    public static TouchPasswordField createTouchOnlyPasswordField(String promptText, boolean allowDecimal) {
        return new TouchPasswordField(promptText);
    }

    /**
     * Create a large display label for amounts
     */
    public static Label createLargeAmountLabel(String text, String color) {
        Label label = new Label(text);
        label.setFont(Font.font("System", FontWeight.BOLD, TOUCH_XLARGE_LABEL_FONT_SIZE));
        label.setStyle("-fx-text-fill: " + color + ";");
        label.setAlignment(Pos.CENTER);
        return label;
    }

    /**
     * Create a medium display label
     */
    public static Label createMediumLabel(String text, String color) {
        Label label = new Label(text);
        label.setFont(Font.font("System", FontWeight.BOLD, TOUCH_LABEL_FONT_SIZE));
        label.setStyle("-fx-text-fill: " + color + ";");
        return label;
    }

    /**
     * Create a section title label
     */
    public static Label createSectionLabel(String text) {
        Label label = new Label(text);
        label.setFont(Font.font("System", FontWeight.NORMAL, 18));
        label.setStyle("-fx-text-fill: #666;");
        return label;
    }

    /**
     * Create a container with proper touch-screen spacing
     */
    public static VBox createTouchContainer(double spacing) {
        VBox container = new VBox(spacing);
        container.setPadding(new Insets(30));
        container.setAlignment(Pos.CENTER);
        return container;
    }

    /**
     * Create a horizontal button row with proper spacing
     */
    public static HBox createButtonRow(double spacing) {
        HBox row = new HBox(spacing);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(10));
        return row;
    }

    /**
     * Darken a hex color for hover effects
     */
    private static String darkenColor(String hexColor) {
        if (hexColor.startsWith("#")) {
            hexColor = hexColor.substring(1);
        }

        // Simple darkening by reducing RGB values by 15%
        try {
            int r = Integer.parseInt(hexColor.substring(0, 2), 16);
            int g = Integer.parseInt(hexColor.substring(2, 4), 16);
            int b = Integer.parseInt(hexColor.substring(4, 6), 16);

            r = Math.max(0, (int) (r * 0.85));
            g = Math.max(0, (int) (g * 0.85));
            b = Math.max(0, (int) (b * 0.85));

            return String.format("#%02X%02X%02X", r, g, b);
        } catch (Exception e) {
            // Return original color if parsing fails
            return hexColor.startsWith("#") ? hexColor : "#" + hexColor;
        }
    }
}
