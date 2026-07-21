package com.pos.ui.components;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Reusable Toast notification component for JavaFX
 * Displays temporary messages at the top or bottom of the screen
 */
public class ToastNotification {

    public enum ToastType {
        SUCCESS("#4CAF50", "white", "#45a049"),
        ERROR("#f44336", "white", "#da190b"),
        WARNING("#FF9800", "white", "#f57c00"),
        INFO("#2196F3", "white", "#1976D2");

        private final String backgroundColor;
        private final String textColor;
        private final String borderColor;

        ToastType(String backgroundColor, String textColor, String borderColor) {
            this.backgroundColor = backgroundColor;
            this.textColor = textColor;
            this.borderColor = borderColor;
        }

        public String getBackgroundColor() {
            return backgroundColor;
        }

        public String getTextColor() {
            return textColor;
        }

        public String getBorderColor() {
            return borderColor;
        }
    }

    private static final double TOAST_WIDTH = 400;
    private static final double TOAST_HEIGHT = 60;
    private static final Duration DISPLAY_DURATION = Duration.seconds(3);
    private static final Duration ANIMATION_DURATION = Duration.millis(300);

    /**
     * Show a toast notification
     * 
     * @param message     The message to display
     * @param type        The type of toast (SUCCESS, ERROR, WARNING, INFO)
     * @param ownerWindow The window to show the toast relative to (can be null)
     */
    public static void show(String message, ToastType type, Window ownerWindow) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }

        // Get the primary stage if ownerWindow is null
        Stage stage = null;
        if (ownerWindow instanceof Stage) {
            stage = (Stage) ownerWindow;
        } else if (ownerWindow != null) {
            stage = (Stage) ownerWindow.getScene().getWindow();
        } else {
            // Try to find any showing stage
            stage = (Stage) javafx.stage.Stage.getWindows().stream()
                    .filter(Window::isShowing)
                    .filter(w -> w instanceof Stage)
                    .findFirst()
                    .orElse(null);
        }

        if (stage == null) {
            // Fallback: create a temporary stage
            return;
        }

        // Create popup
        Popup popup = new Popup();
        popup.setAutoHide(false);
        popup.setHideOnEscape(false);

        // Create content container
        VBox container = new VBox();
        container.setAlignment(Pos.CENTER);
        container.setPrefWidth(TOAST_WIDTH);
        container.setMinHeight(TOAST_HEIGHT);
        container.setMaxHeight(TOAST_HEIGHT);
        container.setPadding(new Insets(15, 20, 15, 20));

        // Set background color based on type
        String backgroundColor = type.getBackgroundColor();
        String textColor = type.getTextColor();
        String borderColor = type.getBorderColor();

        container.setStyle(
                "-fx-background-color: " + backgroundColor + "; " +
                        "-fx-background-radius: 8; " +
                        "-fx-border-color: " + borderColor + "; " +
                        "-fx-border-width: 2; " +
                        "-fx-border-radius: 8; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 10, 0, 0, 2);");

        // Create label with message
        Label messageLabel = new Label(message);
        messageLabel.setWrapText(true);
        messageLabel.setStyle(
                "-fx-text-fill: " + textColor + "; " +
                        "-fx-font-size: 14px; " +
                        "-fx-font-weight: bold; " +
                        "-fx-alignment: center;");
        messageLabel.setMaxWidth(TOAST_WIDTH - 40);

        container.getChildren().add(messageLabel);

        // Set content
        popup.getContent().add(container);

        // Calculate position (top center of the stage)
        double x = stage.getX() + (stage.getWidth() / 2) - (TOAST_WIDTH / 2);
        double y = stage.getY() + 50; // 50px from top

        // Show popup
        popup.show(stage, x, y);

        // Create animations
        // Initial state: above visible area
        container.setTranslateY(-TOAST_HEIGHT - 20);
        container.setOpacity(0);

        // Slide in animation
        TranslateTransition slideIn = new TranslateTransition(ANIMATION_DURATION, container);
        slideIn.setToY(0);

        FadeTransition fadeIn = new FadeTransition(ANIMATION_DURATION, container);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);

        ParallelTransition showAnimation = new ParallelTransition(slideIn, fadeIn);

        // Slide out animation
        TranslateTransition slideOut = new TranslateTransition(ANIMATION_DURATION, container);
        slideOut.setToY(-TOAST_HEIGHT - 20);

        FadeTransition fadeOut = new FadeTransition(ANIMATION_DURATION, container);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);

        ParallelTransition hideAnimation = new ParallelTransition(slideOut, fadeOut);
        hideAnimation.setOnFinished(e -> popup.hide());

        // Sequential: show -> wait -> hide
        SequentialTransition sequence = new SequentialTransition(
                showAnimation,
                new javafx.animation.PauseTransition(DISPLAY_DURATION),
                hideAnimation);

        sequence.play();
    }

    /**
     * Show toast on JavaFX Application Thread
     */
    public static void showOnFxThread(String message, ToastType type, Window ownerWindow) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            show(message, type, ownerWindow);
        } else {
            javafx.application.Platform.runLater(() -> show(message, type, ownerWindow));
        }
    }

    /**
     * Show success toast
     */
    public static void showSuccess(String message, Window ownerWindow) {
        showOnFxThread(message, ToastType.SUCCESS, ownerWindow);
    }

    /**
     * Show error toast
     */
    public static void showError(String message, Window ownerWindow) {
        showOnFxThread(message, ToastType.ERROR, ownerWindow);
    }

    /**
     * Show warning toast
     */
    public static void showWarning(String message, Window ownerWindow) {
        showOnFxThread(message, ToastType.WARNING, ownerWindow);
    }

    /**
     * Show info toast
     */
    public static void showInfo(String message, Window ownerWindow) {
        showOnFxThread(message, ToastType.INFO, ownerWindow);
    }

}
