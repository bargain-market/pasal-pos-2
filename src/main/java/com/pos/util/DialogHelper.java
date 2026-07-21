package com.pos.util;

import javafx.scene.control.Alert;
import javafx.scene.control.Dialog;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * Utility class for managing dialog windows to ensure they appear on the same
 * screen
 */
public class DialogHelper {

    /**
     * Sets the owner window for a Dialog to ensure it appears on the same screen.
     * If owner is provided, uses it; otherwise tries to find the current showing
     * window.
     * 
     * @param dialog The dialog to set the owner for
     * @param owner  Optional owner window (can be null)
     */
    public static void setDialogOwner(Dialog<?> dialog, Window owner) {
        if (owner != null) {
            dialog.initOwner(owner);
        } else {
            // Try to get the current window from the scene graph
            try {
                Window currentWindow = javafx.stage.Stage.getWindows().stream()
                        .filter(Window::isShowing)
                        .findFirst()
                        .orElse(null);
                if (currentWindow != null) {
                    dialog.initOwner(currentWindow);
                }
            } catch (Exception e) {
                // Ignore if we can't set owner
            }
        }

        // Apply responsive sizing if not already set (using defaults)
        // This provides a baseline responsiveness for all dialogs
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(dialog, 0.5, 0.4);

        // Ensure modality is set
        if (dialog.getDialogPane().getScene() != null && dialog.getDialogPane().getScene().getWindow() != null) {
            // Modality should already be set, but ensure it's APPLICATION_MODAL
            if (dialog.getDialogPane().getScene().getWindow() instanceof javafx.stage.Stage) {
                ((javafx.stage.Stage) dialog.getDialogPane().getScene().getWindow())
                        .initModality(Modality.APPLICATION_MODAL);
            }
        }
    }

    /**
     * Sets the owner window for an Alert to ensure it appears on the same screen.
     * If owner is provided, uses it; otherwise tries to find the current showing
     * window.
     * 
     * @param alert The alert to set the owner for
     * @param owner Optional owner window (can be null)
     */
    public static void setAlertOwner(Alert alert, Window owner) {
        if (owner != null) {
            alert.initOwner(owner);
        } else {
            // Try to get the current window from the scene graph
            try {
                Window currentWindow = javafx.stage.Stage.getWindows().stream()
                        .filter(Window::isShowing)
                        .findFirst()
                        .orElse(null);
                if (currentWindow != null) {
                    alert.initOwner(currentWindow);
                }
            } catch (Exception e) {
                // Ignore if we can't set owner
            }
        }

        // Apply responsive sizing for alerts
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(alert, 0.4, 0.3);
    }

    /**
     * Shows an error alert ensuring it appears on the same screen.
     * 
     * @param title   The title of the alert
     * @param content The content message
     * @param owner   Optional owner window
     */
    public static void showError(String title, String content, Window owner) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        setAlertOwner(alert, owner);
        alert.showAndWait();
    }

    /**
     * Shows an error alert ensuring it appears on the same screen (auto-detects window).
     * 
     * @param title   The title of the alert
     * @param content The content message
     */
    public static void showError(String title, String content) {
        showError(title, content, getCurrentWindow());
    }

    /**
     * Gets the current showing window, or null if none found.
     * 
     * @return The current showing window, or null
     */
    public static Window getCurrentWindow() {
        try {
            return javafx.stage.Stage.getWindows().stream()
                    .filter(Window::isShowing)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
