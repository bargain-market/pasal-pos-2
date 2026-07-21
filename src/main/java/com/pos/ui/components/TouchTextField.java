package com.pos.ui.components;

import com.pos.config.ConfigManager;
import com.pos.ui.keyboard.KeyboardManager;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Touch-screen optimized TextField that disables keyboard input
 * and uses on-screen keypad for input
 */
public class TouchTextField extends VBox {

    public enum KeypadType {
        NUMERIC,
        ALPHANUMERIC
    }

    public interface EnterListener {
        void onEnterPressed();
    }

    private TextField textField;
    private HBox inputRow;
    private NumericKeypad numericKeypad;
    private AlphanumericKeypad alphanumericKeypad;
    private KeypadType keypadType;
    private boolean showKeypad = true;
    private boolean keyboardEnabled = false;
    private boolean showEnterButton = false;
    private EnterListener enterListener;

    public TouchTextField() {
        this(KeypadType.ALPHANUMERIC);
    }

    public TouchTextField(KeypadType keypadType) {
        this.keypadType = keypadType;
        initialize();
    }

    public TouchTextField(String promptText) {
        this(KeypadType.ALPHANUMERIC);
        setPromptText(promptText);
    }

    public TouchTextField(String promptText, KeypadType keypadType) {
        this(keypadType);
        setPromptText(promptText);
    }

    private void initialize() {
        setSpacing(10);

        // Create the text field
        textField = new TextField();
        textField.setPrefHeight(TouchScreenComponents.TOUCH_INPUT_HEIGHT);
        textField.setMinHeight(TouchScreenComponents.TOUCH_INPUT_HEIGHT);
        textField.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD,
                TouchScreenComponents.TOUCH_INPUT_FONT_SIZE));
        textField.setStyle(
                "-fx-alignment: center-left; " +
                        "-fx-background-radius: 12; " +
                        "-fx-border-radius: 12; " +
                        "-fx-border-color: #ddd; " +
                        "-fx-border-width: 3; " +
                        "-fx-background-color: white; " +
                        "-fx-padding: 15px 20px;");

        // Initialize configuration from properties
        ConfigManager config = ConfigManager.getInstance();
        this.showKeypad = config.getBooleanProperty("app.keyboard.onscreen.enabled", true);
        this.keyboardEnabled = config.getBooleanProperty("app.keyboard.physical.enabled", false);

        // Disable keyboard input (unless enabled by config)
        disableKeyboardInput();

        // Focus style - only change visual style (keyboard focus listener added after showKeypad is set)

        // Use updateStyle to set initial style
        updateStyle(false);

        // When user clicks/touches the field, show the configured keyboard.
        // JavaFX synthesizes mouse clicks from touch — do not also bind touch handlers.
        textField.setOnMouseClicked(e -> {
            if (!textField.isFocused()) {
                textField.requestFocus();
            }
            showInputKeyboard();
        });

        // Keep focus on the field when the floating keyboard is open (physical keyboard input).
        textField.focusedProperty().addListener((obs, wasFocused, nowFocused) -> {
            if (!nowFocused && showKeypad && KeyboardManager.isShowing()) {
                Platform.runLater(() -> {
                    if (!KeyboardManager.isShowing()) {
                        return;
                    }
                    // Don't reclaim focus if the user moved to another text input field;
                    // that field should take over the on-screen keyboard instead of us
                    // fighting it for focus.
                    if (focusMovedToAnotherInput(textField)) {
                        return;
                    }
                    textField.requestFocus();
                });
            }
        });

        textField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            updateStyle(isNowFocused);
        });

        inputRow = new HBox(8);
        inputRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(textField, Priority.ALWAYS);
        textField.setMaxWidth(Double.MAX_VALUE);
        inputRow.getChildren().add(textField);
        getChildren().add(inputRow);

        // Create keypads
        createKeypads();
    }

    private boolean isCompact = false;

    public void setCompactMode(boolean compact) {
        this.isCompact = compact;
        double height = compact ? TouchScreenComponents.TOUCH_INPUT_HEIGHT_COMPACT
                : TouchScreenComponents.TOUCH_INPUT_HEIGHT;
        double fontSize = compact ? TouchScreenComponents.TOUCH_INPUT_FONT_SIZE_COMPACT
                : TouchScreenComponents.TOUCH_INPUT_FONT_SIZE;

        textField.setPrefHeight(height);
        textField.setMinHeight(height);
        textField.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, fontSize));

        updateStyle(textField.isFocused());
    }

    private void updateStyle(boolean isFocused) {
        String padding = isCompact ? "8px 10px" : "15px 20px";
        if (isFocused) {
            textField.setStyle(
                    "-fx-alignment: center-left; " +
                            "-fx-background-radius: 12; " +
                            "-fx-border-radius: 12; " +
                            "-fx-border-color: #2a5298; " +
                            "-fx-border-width: 4; " +
                            "-fx-background-color: white; " +
                            "-fx-text-fill: #0f172a; " +
                            "-fx-prompt-text-fill: #94a3b8; " +
                            "-fx-highlight-fill: #2563eb; " +
                            "-fx-highlight-text-fill: white; " +
                            "-fx-padding: " + padding + "; " +
                            "-fx-effect: dropshadow(gaussian, rgba(42,82,152,0.3), 8, 0, 0, 2);");
            // If keypad is disabled, ensure keyboard is hidden
            if (!showKeypad) {
                KeyboardManager.hide();
            }
        } else {
            textField.setStyle(
                    "-fx-alignment: center-left; " +
                            "-fx-background-radius: 12; " +
                            "-fx-border-radius: 12; " +
                            "-fx-border-color: #ddd; " +
                            "-fx-border-width: 3; " +
                            "-fx-background-color: white; " +
                            "-fx-text-fill: #0f172a; " +
                            "-fx-prompt-text-fill: #94a3b8; " +
                            "-fx-highlight-fill: #2563eb; " +
                            "-fx-highlight-text-fill: white; " +
                            "-fx-padding: " + padding + ";");
        }
    }

    private javafx.event.EventHandler<KeyEvent> keyTypedFilter;
    private javafx.event.EventHandler<KeyEvent> keyPressedFilter;

    private void disableKeyboardInput() {
        textField.setEditable(keyboardEnabled);
        textField.setFocusTraversable(true);
    }

    private void createKeypads() {
        // Numeric keypad - kept for backward compatibility but not shown by default
        numericKeypad = new NumericKeypad(true, showEnterButton, false);
        numericKeypad.setListener(key -> handleKeypadInput(key));
        numericKeypad.setEnterListener(() -> {
            if (enterListener != null) {
                enterListener.onEnterPressed();
            }
        });
        numericKeypad.setVisible(false);
        numericKeypad.setManaged(false);

        // Alphanumeric keypad - kept for backward compatibility but not shown by
        // default
        alphanumericKeypad = new AlphanumericKeypad();
        alphanumericKeypad.setListener(key -> handleKeypadInput(key));
        alphanumericKeypad.setVisible(false);
        alphanumericKeypad.setManaged(false);

        getChildren().addAll(numericKeypad, alphanumericKeypad);

        // Add mouse pressed handler to detect clicks outside and hide keypad
        // immediately
        // This ensures button clicks work even when keypad is visible
        setOnMousePressed(e -> {
            javafx.scene.Node target = (javafx.scene.Node) e.getTarget();
            // If click is not on text field or keypad, hide keypad immediately
            if (target != textField && target != numericKeypad && target != alphanumericKeypad &&
                    !numericKeypad.getChildren().contains(target) &&
                    !alphanumericKeypad.getChildren().contains(target) &&
                    !textField.equals(target)) {
                if (numericKeypad.isVisible() || alphanumericKeypad.isVisible()) {
                    // Hide immediately (synchronously) so button clicks can proceed
                    hideKeypad();
                }
            }
        });
    }

    private void showKeypad() {
        if (keypadType == KeypadType.NUMERIC) {
            numericKeypad.setVisible(true);
            numericKeypad.setManaged(true);
            numericKeypad.setMouseTransparent(false);
            alphanumericKeypad.setVisible(false);
            alphanumericKeypad.setManaged(false);
            alphanumericKeypad.setMouseTransparent(true);
        } else {
            alphanumericKeypad.setVisible(true);
            alphanumericKeypad.setManaged(true);
            alphanumericKeypad.setMouseTransparent(false);
            numericKeypad.setVisible(false);
            numericKeypad.setManaged(false);
            numericKeypad.setMouseTransparent(true);
        }
    }

    private void hideKeypad() {
        numericKeypad.setVisible(false);
        numericKeypad.setManaged(false);
        numericKeypad.setMouseTransparent(true);
        alphanumericKeypad.setVisible(false);
        alphanumericKeypad.setManaged(false);
        alphanumericKeypad.setMouseTransparent(true);
    }

    private void handleKeypadInput(String key) {
        if ("C".equals(key)) {
            textField.clear();
        } else if ("⌫".equals(key)) {
            String text = textField.getText();
            if (!text.isEmpty()) {
                textField.setText(text.substring(0, text.length() - 1));
                textField.positionCaret(textField.getText().length());
            }
        } else {
            textField.appendText(key);
        }
        // Keep focus on text field and ensure keypad stays visible
        javafx.application.Platform.runLater(() -> {
            textField.requestFocus();
            if (showKeypad && textField.isFocused()) {
                showKeypad();
            }
        });
    }

    // Delegate methods to TextField
    public String getText() {
        return textField.getText();
    }

    public void setText(String text) {
        textField.setText(text);
    }

    public void clear() {
        textField.clear();
    }

    public void setPromptText(String promptText) {
        textField.setPromptText(promptText);
    }

    public String getPromptText() {
        return textField.getPromptText();
    }

    public void setTextFieldPrefWidth(double width) {
        textField.setPrefWidth(width);
    }

    public void setTextFieldMaxWidth(double width) {
        textField.setMaxWidth(width);
    }

    public void focusTextField() {
        javafx.application.Platform.runLater(() -> textField.requestFocus());
    }

    public void setOnAction(EventHandler<javafx.event.ActionEvent> handler) {
        textField.setOnAction(handler);
    }

    public javafx.beans.property.StringProperty textProperty() {
        return textField.textProperty();
    }

    public javafx.beans.property.ReadOnlyBooleanProperty getTextFieldFocusedProperty() {
        return textField.focusedProperty();
    }

    private void showInputKeyboard() {
        if (showKeypad) {
            KeyboardManager.showFor(textField);
        }
    }

    /**
     * Returns true if keyboard focus has moved to a different text input control
     * (i.e. the user tapped another field), meaning we should not steal it back.
     */
    static boolean focusMovedToAnotherInput(javafx.scene.control.TextInputControl self) {
        if (self.getScene() == null) {
            return false;
        }
        javafx.scene.Node focusOwner = self.getScene().getFocusOwner();
        return focusOwner instanceof javafx.scene.control.TextInputControl && focusOwner != self;
    }

    public void setShowKeypad(boolean show) {
        this.showKeypad = show;
        if (!show) {
            hideKeypad();
            // Also hide the floating keyboard if it's showing
            KeyboardManager.hide();
        }
    }

    /**
     * Enable keyboard input alongside touch keypad
     * This allows users to type via physical keyboard or on-screen keypad
     */
    public void setKeyboardEnabled(boolean enabled) {
        this.keyboardEnabled = enabled;
        if (enabled) {
            // Remove the event filters that block keyboard input
            if (keyTypedFilter != null) {
                textField.removeEventFilter(KeyEvent.KEY_TYPED, keyTypedFilter);
            }
            if (keyPressedFilter != null) {
                textField.removeEventFilter(KeyEvent.KEY_PRESSED, keyPressedFilter);
            }
            textField.setEditable(true);
        } else {
            // Re-disable keyboard input
            disableKeyboardInput();
        }
    }

    /**
     * Check if keyboard input is enabled
     */
    public boolean isKeyboardEnabled() {
        return keyboardEnabled;
    }

    /**
     * Enable or disable the Enter button on the numeric keypad
     */
    public void setShowEnterButton(boolean show) {
        this.showEnterButton = show;
        numericKeypad.setShowEnterButton(show);
    }

    /**
     * Set the listener for when Enter is pressed on the keypad
     */
    public void setEnterListener(EnterListener listener) {
        this.enterListener = listener;
        numericKeypad.setEnterListener(() -> {
            if (enterListener != null) {
                enterListener.onEnterPressed();
            }
        });
    }

    public TextField getTextField() {
        return textField;
    }
}
