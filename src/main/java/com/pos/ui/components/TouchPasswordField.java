package com.pos.ui.components;

import com.pos.config.ConfigManager;
import com.pos.ui.keyboard.InputType;
import com.pos.ui.keyboard.KeyboardManager;
import javafx.event.EventHandler;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;

/**
 * Touch-screen optimized PasswordField that disables keyboard input
 * and uses on-screen numeric keypad for PIN entry.
 * Enhanced with PIN progress indicator and Enter button for better UX.
 */
public class TouchPasswordField extends VBox {

    private PasswordField passwordField;
    private NumericKeypad numericKeypad;
    private boolean showKeypad = true;
    private boolean allowDecimal = false; // PINs typically don't need decimals
    private boolean showEnterButton = true; // Show Enter button by default for login
    private boolean showPinIndicator = true; // Show PIN dots by default
    private int maxPinLength = 6;
    private EventHandler<javafx.event.ActionEvent> onActionHandler;

    public TouchPasswordField() {
        this(false);
    }

    public TouchPasswordField(boolean allowDecimal) {
        this.allowDecimal = allowDecimal;
        initialize();
    }

    public TouchPasswordField(String promptText) {
        this(false);
        setPromptText(promptText);
    }

    /**
     * Full constructor with all options
     */
    public TouchPasswordField(boolean allowDecimal, boolean showEnterButton, boolean showPinIndicator) {
        this.allowDecimal = allowDecimal;
        this.showEnterButton = showEnterButton;
        this.showPinIndicator = showPinIndicator;
        initialize();
    }

    private void initialize() {
        setSpacing(15);
        getStyleClass().add("touch-password-field-container");

        // Create the password field
        passwordField = new PasswordField();
        passwordField.setPrefHeight(TouchScreenComponents.TOUCH_INPUT_HEIGHT);
        passwordField.setMinHeight(TouchScreenComponents.TOUCH_INPUT_HEIGHT);
        passwordField.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD,
                TouchScreenComponents.TOUCH_INPUT_FONT_SIZE));
        passwordField.getStyleClass().add("touch-password-input");
        passwordField.setStyle(
                "-fx-alignment: center; " +
                        "-fx-background-radius: 16; " +
                        "-fx-border-radius: 16; " +
                        "-fx-border-color: #e0e0e0; " +
                        "-fx-border-width: 2; " +
                        "-fx-background-color: #fafafa; " +
                        "-fx-padding: 18px 24px;" +
                        "-fx-font-size: 24px;" +
                        "-fx-letter-spacing: 8px;");

        // Disable keyboard input
        disableKeyboardInput();

        // Focus style - visual only
        passwordField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (isNowFocused) {
                passwordField.setStyle(
                        "-fx-alignment: center; " +
                                "-fx-background-radius: 16; " +
                                "-fx-border-radius: 16; " +
                                "-fx-border-color: #667eea; " +
                                "-fx-border-width: 3; " +
                                "-fx-background-color: white; " +
                                "-fx-padding: 18px 24px; " +
                                "-fx-font-size: 24px;" +
                                "-fx-letter-spacing: 8px;" +
                                "-fx-effect: dropshadow(gaussian, rgba(102,126,234,0.3), 15, 0, 0, 4);");
            } else {
                passwordField.setStyle(
                        "-fx-alignment: center; " +
                                "-fx-background-radius: 16; " +
                                "-fx-border-radius: 16; " +
                                "-fx-border-color: #e0e0e0; " +
                                "-fx-border-width: 2; " +
                                "-fx-background-color: #fafafa; " +
                                "-fx-padding: 18px 24px;" +
                                "-fx-font-size: 24px;" +
                                "-fx-letter-spacing: 8px;");
            }
        });

        boolean onscreenKeyboard = ConfigManager.getInstance()
                .getBooleanProperty("app.keyboard.onscreen.enabled", true);

        passwordField.setOnMouseClicked(e -> {
            passwordField.requestFocus();
            if (onscreenKeyboard) {
                KeyboardManager.registerInputType(passwordField, InputType.PIN);
                KeyboardManager.showFor(passwordField, InputType.PIN);
            }
        });

        getChildren().add(passwordField);

        // Create numeric keypad with enhanced features (kept but hidden by default)
        numericKeypad = new NumericKeypad(allowDecimal, showEnterButton, showPinIndicator);
        numericKeypad.setListener(key -> handleKeypadInput(key));
        numericKeypad.setEnterListener(() -> {
            // Trigger the onAction handler when Enter is pressed
            if (onActionHandler != null) {
                onActionHandler.handle(new javafx.event.ActionEvent(this, null));
            }
        });
        numericKeypad.setMaxPinLength(maxPinLength);
        numericKeypad.setVisible(false);
        numericKeypad.setManaged(false);
        numericKeypad.setMouseTransparent(true); // Start as mouse-transparent

        // Prevent keypad from being focusable
        numericKeypad.setFocusTraversable(false);

        // Prevent keypad clicks from stealing focus
        numericKeypad.setOnMouseClicked(e -> {
            // Keep focus on password field when clicking keypad
            javafx.application.Platform.runLater(() -> {
                passwordField.requestFocus();
            });
        });

        getChildren().add(numericKeypad);

        // Listen for text changes to update PIN indicator
        passwordField.textProperty().addListener((obs, oldText, newText) -> {
            numericKeypad.updatePinLength(newText != null ? newText.length() : 0);
        });

        // Set up scene-level handler to detect clicks outside and hide keypad
        // immediately
        // This ensures login button clicks work even when keypad is visible
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                // Use MOUSE_PRESSED to catch clicks before they're processed
                newScene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                    javafx.scene.Node target = (javafx.scene.Node) e.getTarget();

                    // Check if target is a button (like login button) - if so, hide keypad
                    // immediately
                    if (target instanceof javafx.scene.control.Button) {
                        javafx.scene.control.Button button = (javafx.scene.control.Button) target;
                        // Check if it's NOT a keypad button
                        boolean isKeypadButton = isNodeInKeypad(button);

                        if (!isKeypadButton && numericKeypad.isVisible()) {
                            // Hide immediately (synchronously) so button clicks can proceed
                            hideKeypad();
                        }
                    } else if (target != passwordField &&
                            target != numericKeypad &&
                            !isNodeInKeypad(target)) {
                        // Click is outside password field and keypad
                        if (numericKeypad.isVisible()) {
                            // Hide immediately (synchronously) so clicks can proceed
                            hideKeypad();
                        }
                    }
                });
            }
        });
    }

    /**
     * Check if a node is part of the keypad (handles nested structure)
     */
    private boolean isNodeInKeypad(javafx.scene.Node node) {
        javafx.scene.Node current = node;
        while (current != null) {
            if (current == numericKeypad) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private void disableKeyboardInput() {
        boolean physicalEnabled = ConfigManager.getInstance()
                .getBooleanProperty("app.keyboard.physical.enabled", true);
        passwordField.setEditable(physicalEnabled);
        passwordField.setFocusTraversable(true);
    }

    private void showKeypad() {
        numericKeypad.setVisible(true);
        numericKeypad.setManaged(true);
        // Make keypad interactive when visible
        numericKeypad.setMouseTransparent(false);
        // Update PIN indicator with current length
        numericKeypad.updatePinLength(passwordField.getText() != null ? passwordField.getText().length() : 0);
    }

    private void hideKeypad() {
        // Hide immediately and make mouse-transparent to prevent blocking clicks
        numericKeypad.setMouseTransparent(true);
        numericKeypad.setVisible(false);
        numericKeypad.setManaged(false);
    }

    private void handleKeypadInput(String key) {
        if ("C".equals(key)) {
            passwordField.clear();
        } else if ("⌫".equals(key)) {
            String text = passwordField.getText();
            if (!text.isEmpty()) {
                passwordField.setText(text.substring(0, text.length() - 1));
                passwordField.positionCaret(passwordField.getText().length());
            }
        } else {
            // Only allow up to maxPinLength digits
            if (passwordField.getText().length() < maxPinLength) {
                passwordField.appendText(key);
            }
        }
        // Keep focus on password field and ensure keypad stays visible
        javafx.application.Platform.runLater(() -> {
            passwordField.requestFocus();
            if (showKeypad) {
                showKeypad();
            }
        });
    }

    // Delegate methods to PasswordField
    public String getText() {
        return passwordField.getText();
    }

    public void setText(String text) {
        passwordField.setText(text);
    }

    public void clear() {
        passwordField.clear();
    }

    public void setPromptText(String promptText) {
        passwordField.setPromptText(promptText);
    }

    public String getPromptText() {
        return passwordField.getPromptText();
    }

    public void setPasswordFieldPrefWidth(double width) {
        passwordField.setPrefWidth(width);
    }

    public void setPasswordFieldMaxWidth(double width) {
        passwordField.setMaxWidth(width);
    }

    public void focusPasswordField() {
        javafx.application.Platform.runLater(() -> passwordField.requestFocus());
    }

    public void setOnAction(EventHandler<javafx.event.ActionEvent> handler) {
        this.onActionHandler = handler;
        passwordField.setOnAction(handler);
    }

    public javafx.beans.property.StringProperty textProperty() {
        return passwordField.textProperty();
    }

    public javafx.beans.property.ReadOnlyBooleanProperty getPasswordFieldFocusedProperty() {
        return passwordField.focusedProperty();
    }

    public void setShowKeypad(boolean show) {
        this.showKeypad = show;
        if (!show) {
            hideKeypad();
        }
    }

    public PasswordField getPasswordField() {
        return passwordField;
    }

    /**
     * Set whether to show the Enter button on the keypad
     */
    public void setShowEnterButton(boolean show) {
        this.showEnterButton = show;
        numericKeypad.setShowEnterButton(show);
    }

    /**
     * Set whether to show the PIN progress indicator
     */
    public void setShowPinIndicator(boolean show) {
        this.showPinIndicator = show;
        numericKeypad.setShowPinIndicator(show);
    }

    /**
     * Set the maximum PIN length (for indicator display)
     */
    public void setMaxPinLength(int length) {
        this.maxPinLength = length;
        numericKeypad.setMaxPinLength(length);
    }
}
