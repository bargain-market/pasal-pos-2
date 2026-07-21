package com.pos.ui.keyboard;

import com.pos.config.ConfigManager;
import com.pos.util.ResponsiveHelper;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.GridPane;
import javafx.scene.input.MouseEvent;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Global manager for floating on-screen input components.
 * <p>
 * Uses {@link Popup} (not {@link javafx.stage.Stage}) so the active text field keeps
 * keyboard focus and physical keyboard input continues to work.
 */
public final class KeyboardManager {

    private static Popup keyboardPopup;
    private static Popup numericKeypadPopup;
    private static Parent keyboardRoot;
    private static Parent numericKeypadRoot;
    private static WeakReference<TextInputControl> activeControlRef;
    private static FloatingKeyboardController keyboardController;
    private static FloatingNumericKeypadController numericKeypadController;

    private static final Map<TextInputControl, InputType> controlInputTypes = new WeakHashMap<>();
    private static InputType currentInputType = InputType.TEXT;
    private static boolean globallyDisabled = false;

    private KeyboardManager() {
    }

    public static void setGloballyDisabled(boolean disabled) {
        globallyDisabled = disabled;
        if (disabled) {
            hide();
        }
    }

    public static void registerInputType(TextInputControl control, InputType inputType) {
        if (control != null && inputType != null) {
            controlInputTypes.put(control, inputType);
        }
    }

    public static void unregisterInputType(TextInputControl control) {
        if (control != null) {
            controlInputTypes.remove(control);
        }
    }

    public static InputType getInputType(TextInputControl control) {
        return controlInputTypes.getOrDefault(control, InputType.TEXT);
    }

    /**
     * Pre-load keyboard popups. Safe to call from application startup.
     */
    public static void initialize(Window owner) {
        ensureKeyboardLoaded();
        ensureNumericKeypadLoaded();
    }

    public static void showFor(TextInputControl control) {
        if (control == null) {
            return;
        }
        showFor(control, getInputType(control));
    }

    public static void showFor(TextInputControl control, InputType inputType) {
        if (control == null || globallyDisabled) {
            return;
        }

        boolean onscreenEnabled = ConfigManager.getInstance().getBooleanProperty("app.keyboard.onscreen.enabled", true);
        if (!onscreenEnabled) {
            return;
        }

        ensureKeyboardLoaded();
        ensureNumericKeypadLoaded();

        currentInputType = inputType;
        activeControlRef = new WeakReference<>(control);

        Platform.runLater(() -> {
            control.requestFocus();

            if (inputType.isNumeric()) {
                hideKeyboardPopup();
                showNumericKeypad(control, inputType);
            } else {
                hideNumericKeypadPopup();
                showKeyboard(control);
            }
            refocusActiveControl();
        });
    }

    private static void showKeyboard(TextInputControl control) {
        if (keyboardPopup == null || control.getScene() == null) {
            return;
        }

        FloatingKeyboardLayout.applyQwerty(keyboardRoot);
        FloatingKeyboardLayout.Size size = FloatingKeyboardLayout.qwertySize();

        Window window = control.getScene().getWindow();
        Point2D position = computeScreenPosition(control, size.width(), size.height());
        if (position == null) {
            return;
        }

        if (keyboardPopup.isShowing()) {
            keyboardPopup.setX(position.getX());
            keyboardPopup.setY(position.getY());
        } else {
            keyboardPopup.show(window, position.getX(), position.getY());
        }
        refocusActiveControl();
    }

    private static void showNumericKeypad(TextInputControl control, InputType inputType) {
        if (numericKeypadPopup == null) {
            showKeyboard(control);
            return;
        }

        if (numericKeypadController != null) {
            numericKeypadController.setAllowDecimal(inputType.allowsDecimal());
            numericKeypadController.setShowEnterButton(inputType.isPinEntry() || inputType == InputType.DECIMAL);
            switch (inputType) {
                case PIN -> numericKeypadController.setTitle("Enter PIN");
                case DECIMAL -> numericKeypadController.setTitle("Enter Amount");
                case NUMERIC -> numericKeypadController.setTitle("Enter Number");
                default -> numericKeypadController.setTitle("Keypad");
            }
        }

        if (control.getScene() == null) {
            return;
        }

        FloatingKeyboardLayout.applyNumeric(numericKeypadRoot);
        FloatingKeyboardLayout.Size size = FloatingKeyboardLayout.numericSize();

        Window window = control.getScene().getWindow();
        Point2D position = computeScreenPosition(control, size.width(), size.height());
        if (position == null) {
            return;
        }

        if (numericKeypadPopup.isShowing()) {
            numericKeypadPopup.setX(position.getX());
            numericKeypadPopup.setY(position.getY());
        } else {
            numericKeypadPopup.show(window, position.getX(), position.getY());
        }
        refocusActiveControl();
    }

    public static void hide() {
        Platform.runLater(() -> {
            hideKeyboardPopup();
            hideNumericKeypadPopup();
            clearTarget();
        });
    }

    public static void toggleFor(TextInputControl control) {
        if (keyboardPopup != null && keyboardPopup.isShowing()) {
            TextInputControl current = getActiveControl();
            if (current == control) {
                hide();
                return;
            }
        }
        showFor(control);
    }

    public static void handleKeyPress(String key) {
        TextInputControl control = getActiveControl();
        if (control == null || key == null) {
            return;
        }

        switch (key) {
            case "BACKSPACE" -> {
                int caret = control.getCaretPosition();
                if (caret > 0) {
                    String text = control.getText();
                    control.setText(text.substring(0, caret - 1) + text.substring(caret));
                    control.positionCaret(caret - 1);
                }
            }
            case "CLEAR" -> control.clear();
            case "SPACE" -> insertText(control, " ");
            case "ENTER" -> hide();
            default -> insertText(control, key);
        }

        if (!"ENTER".equals(key)) {
            refocusActiveControl();
        }
    }

    public static void clearTarget() {
        if (activeControlRef != null) {
            activeControlRef.clear();
            activeControlRef = null;
        }
    }

    public static boolean isShowing() {
        return (keyboardPopup != null && keyboardPopup.isShowing())
                || (numericKeypadPopup != null && numericKeypadPopup.isShowing());
    }

    public static InputType getCurrentInputType() {
        return currentInputType;
    }

    private static void insertText(TextInputControl control, String text) {
        int caret = control.getCaretPosition();
        String current = control.getText();
        if (current == null) {
            current = "";
        }
        String newText = current.substring(0, caret) + text + current.substring(caret);
        control.setText(newText);
        control.positionCaret(caret + text.length());
    }

    private static TextInputControl getActiveControl() {
        return activeControlRef == null ? null : activeControlRef.get();
    }

    private static void refocusActiveControl() {
        TextInputControl control = getActiveControl();
        if (control == null) {
            return;
        }
        Platform.runLater(() -> {
            if (!control.isDisabled() && control.getScene() != null) {
                control.requestFocus();
            }
            int caret = control.getCaretPosition();
            if (caret < 0 && control.getText() != null) {
                control.positionCaret(control.getText().length());
            }
        });
    }

    private static void hideKeyboardPopup() {
        if (keyboardPopup != null && keyboardPopup.isShowing()) {
            keyboardPopup.hide();
        }
    }

    private static void hideNumericKeypadPopup() {
        if (numericKeypadPopup != null && numericKeypadPopup.isShowing()) {
            numericKeypadPopup.hide();
        }
    }

    private static synchronized void ensureKeyboardLoaded() {
        if (keyboardPopup != null) {
            return;
        }

        try {
            URL fxml = KeyboardManager.class.getResource("/fxml/FloatingKeyboard.fxml");
            if (fxml == null) {
                throw new IllegalStateException("FloatingKeyboard.fxml not found in /fxml");
            }

            FXMLLoader loader = new FXMLLoader(fxml);
            keyboardRoot = loader.load();
            wireKeyboardInteraction(keyboardRoot);
            FloatingKeyboardLayout.applyQwerty(keyboardRoot);

            keyboardController = loader.getController();
            keyboardPopup = new Popup();
            keyboardPopup.setAutoHide(false);
            keyboardPopup.getContent().add(keyboardRoot);
            keyboardController.setPopup(keyboardPopup);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load FloatingKeyboard.fxml", e);
        }
    }

    private static synchronized void ensureNumericKeypadLoaded() {
        if (numericKeypadPopup != null) {
            return;
        }

        try {
            URL fxml = KeyboardManager.class.getResource("/fxml/FloatingNumericKeypad.fxml");
            if (fxml == null) {
                System.err.println("FloatingNumericKeypad.fxml not found, using keyboard for all input types");
                return;
            }

            FXMLLoader loader = new FXMLLoader(fxml);
            numericKeypadRoot = loader.load();
            wireKeyboardInteraction(numericKeypadRoot);
            FloatingKeyboardLayout.applyNumeric(numericKeypadRoot);

            numericKeypadController = loader.getController();
            numericKeypadPopup = new Popup();
            numericKeypadPopup.setAutoHide(false);
            numericKeypadPopup.getContent().add(numericKeypadRoot);
            numericKeypadController.setPopup(numericKeypadPopup);
        } catch (IOException e) {
            System.err.println("Failed to load FloatingNumericKeypad.fxml: " + e.getMessage());
        }
    }

    /**
     * Popups must not take focus; buttons use mouse-pressed so the text field stays active.
     */
    private static void wireKeyboardInteraction(Parent root) {
        for (Node node : root.lookupAll("*")) {
            node.setFocusTraversable(false);
            if (node instanceof Button button) {
                button.setMaxWidth(Double.MAX_VALUE);
                button.setMaxHeight(Double.MAX_VALUE);
                button.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> refocusActiveControl());
            }
            if (node instanceof GridPane grid) {
                grid.setMaxWidth(Double.MAX_VALUE);
                grid.setMaxHeight(Double.MAX_VALUE);
            }
        }
    }

    private static Point2D computeScreenPosition(TextInputControl control, double defaultWidth, double defaultHeight) {
        if (control.getScene() == null) {
            return null;
        }

        Point2D bottomLeft = control.localToScreen(0, control.getHeight());
        Point2D topLeft = control.localToScreen(0, 0);
        if (bottomLeft == null || topLeft == null) {
            return null;
        }

        double popupWidth = defaultWidth;
        double popupHeight = defaultHeight;
        Rectangle2D bounds = ResponsiveHelper.getScreenBounds();

        double x = bottomLeft.getX();
        double y = bottomLeft.getY() + 8;

        if (y + popupHeight > bounds.getMaxY()) {
            y = topLeft.getY() - popupHeight - 8;
        }
        if (x + popupWidth > bounds.getMaxX()) {
            x = bounds.getMaxX() - popupWidth - 8;
        }
        if (x < bounds.getMinX()) {
            x = bounds.getMinX() + 8;
        }

        return new Point2D(x, y);
    }
}
