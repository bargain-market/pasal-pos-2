package com.pos.ui.components;

import com.pos.config.ConfigManager;
import com.pos.ui.keyboard.KeyboardManager;
import javafx.geometry.Pos;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Touch-screen optimized TextArea that disables keyboard input
 * and uses on-screen full keypad for multi-line input
 */
public class TouchTextArea extends VBox {

    private TextArea textArea;
    private HBox inputRow;
    private FullKeypad fullKeypad;
    private boolean showKeypad = true;

    public TouchTextArea() {
        initialize();
    }

    public TouchTextArea(String promptText) {
        this();
        setPromptText(promptText);
    }

    private void initialize() {
        setSpacing(10);

        // Create the text area
        textArea = new TextArea();
        textArea.setPrefRowCount(3);
        textArea.setWrapText(true);
        textArea.setFont(javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD,
                TouchScreenComponents.TOUCH_INPUT_FONT_SIZE));
        textArea.setStyle(
                "-fx-background-radius: 12; " +
                        "-fx-border-radius: 12; " +
                        "-fx-border-color: #ddd; " +
                        "-fx-border-width: 3; " +
                        "-fx-background-color: white; " +
                        "-fx-text-fill: #0f172a; " +
                        "-fx-prompt-text-fill: #94a3b8; " +
                        "-fx-highlight-fill: #2563eb; " +
                        "-fx-highlight-text-fill: white; " +
                        "-fx-padding: 15px 20px;");

        // Disable keyboard input
        disableKeyboardInput();

        showKeypad = ConfigManager.getInstance()
                .getBooleanProperty("app.keyboard.onscreen.enabled", true);

        // Focus style - only change visual style
        textArea.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (isNowFocused) {
                textArea.setStyle(
                        "-fx-background-radius: 12; " +
                                "-fx-border-radius: 12; " +
                                "-fx-border-color: #2a5298; " +
                                "-fx-border-width: 4; " +
                                "-fx-background-color: white; " +
                                "-fx-text-fill: #0f172a; " +
                                "-fx-prompt-text-fill: #94a3b8; " +
                                "-fx-highlight-fill: #2563eb; " +
                                "-fx-highlight-text-fill: white; " +
                                "-fx-padding: 15px 20px; " +
                                "-fx-effect: dropshadow(gaussian, rgba(42,82,152,0.3), 8, 0, 0, 2);");
            } else {
                textArea.setStyle(
                        "-fx-background-radius: 12; " +
                                "-fx-border-radius: 12; " +
                                "-fx-border-color: #ddd; " +
                                "-fx-border-width: 3; " +
                                "-fx-background-color: white; " +
                                "-fx-text-fill: #0f172a; " +
                                "-fx-prompt-text-fill: #94a3b8; " +
                                "-fx-highlight-fill: #2563eb; " +
                                "-fx-highlight-text-fill: white; " +
                                "-fx-padding: 15px 20px;");
            }
        });

        // JavaFX synthesizes mouse clicks from touch — do not also bind touch handlers.
        textArea.setOnMouseClicked(e -> {
            textArea.requestFocus();
            showInputKeyboard();
        });

        inputRow = new HBox(8);
        inputRow.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(textArea, Priority.ALWAYS);
        textArea.setMaxWidth(Double.MAX_VALUE);
        inputRow.getChildren().add(textArea);
        getChildren().add(inputRow);

        // Create full keypad
        fullKeypad = new FullKeypad();
        fullKeypad.setListener(key -> handleKeypadInput(key));
        fullKeypad.setVisible(false);
        fullKeypad.setManaged(false);
        fullKeypad.setFocusTraversable(false);

        // Prevent keypad clicks from stealing focus
        fullKeypad.setOnMouseClicked(e -> {
            javafx.application.Platform.runLater(() -> textArea.requestFocus());
        });

        getChildren().add(fullKeypad);

        // Add mouse pressed handler to detect clicks outside and hide keypad
        // immediately
        // This ensures button clicks work even when keypad is visible
        setOnMousePressed(e -> {
            javafx.scene.Node target = (javafx.scene.Node) e.getTarget();
            // If click is not on text area or keypad, hide keypad immediately
            if (target != textArea && target != fullKeypad &&
                    !fullKeypad.getChildren().contains(target) &&
                    !textArea.equals(target)) {
                if (fullKeypad.isVisible()) {
                    // Hide immediately (synchronously) so button clicks can proceed
                    hideKeypad();
                }
            }
        });
    }

    private void disableKeyboardInput() {
        boolean physicalEnabled = ConfigManager.getInstance()
                .getBooleanProperty("app.keyboard.physical.enabled", true);
        textArea.setEditable(physicalEnabled);
        textArea.setFocusTraversable(true);
    }

    private void showInputKeyboard() {
        if (showKeypad) {
            KeyboardManager.showFor(textArea);
        }
    }

    private void showKeypad() {
        fullKeypad.setVisible(true);
        fullKeypad.setManaged(true);
        // Make keypad interactive when visible
        fullKeypad.setMouseTransparent(false);
    }

    private void hideKeypad() {
        fullKeypad.setVisible(false);
        fullKeypad.setManaged(false);
        // Make keypad mouse-transparent when hidden to prevent blocking clicks
        fullKeypad.setMouseTransparent(true);
    }

    private void handleKeypadInput(String key) {
        if ("C".equals(key)) {
            textArea.clear();
        } else if ("⌫".equals(key)) {
            String text = textArea.getText();
            if (!text.isEmpty()) {
                textArea.setText(text.substring(0, text.length() - 1));
                textArea.positionCaret(textArea.getText().length());
            }
        } else if ("\n".equals(key)) {
            textArea.appendText("\n");
        } else {
            textArea.appendText(key);
        }
        // Keep focus on text area and ensure keypad stays visible
        javafx.application.Platform.runLater(() -> {
            textArea.requestFocus();
            if (showKeypad && textArea.isFocused()) {
                showKeypad();
            }
        });
    }

    // Delegate methods to TextArea
    public String getText() {
        return textArea.getText();
    }

    public void setText(String text) {
        textArea.setText(text);
    }

    public void clear() {
        textArea.clear();
    }

    public void setPromptText(String promptText) {
        textArea.setPromptText(promptText);
    }

    public String getPromptText() {
        return textArea.getPromptText();
    }

    public void setTextAreaPrefWidth(double width) {
        textArea.setPrefWidth(width);
    }

    public void setPrefRowCount(int rows) {
        textArea.setPrefRowCount(rows);
    }

    public void setWrapText(boolean wrap) {
        textArea.setWrapText(wrap);
    }

    public void focusTextArea() {
        javafx.application.Platform.runLater(() -> textArea.requestFocus());
    }

    public javafx.beans.property.StringProperty textProperty() {
        return textArea.textProperty();
    }

    public javafx.beans.property.ReadOnlyBooleanProperty getTextAreaFocusedProperty() {
        return textArea.focusedProperty();
    }

    public void setShowKeypad(boolean show) {
        this.showKeypad = show;
        if (!show) {
            hideKeypad();
        }
    }

    public TextArea getTextArea() {
        return textArea;
    }
}
