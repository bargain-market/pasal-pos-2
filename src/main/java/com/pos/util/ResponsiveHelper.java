package com.pos.util;

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Window;
import javafx.scene.control.Dialog;

import javafx.stage.Stage;

/**
 * Utility class for handling responsive UI calculations and sizing.
 */
public class ResponsiveHelper {

    /**
     * Get the primary screen's visual bounds
     */
    public static Rectangle2D getScreenBounds() {
        String simulation = com.pos.config.ConfigManager.getInstance().getProperty("app.screen.simulation");
        if ("13-inch".equalsIgnoreCase(simulation)) {
            // Standard 13-inch resolution (Retina: 2560x1600 scaled to 1440x900 or 1280x800)
            // We'll use 1440x900 as the "simulation" bounds
            return new Rectangle2D(0, 0, 1440, 900);
        }
        return Screen.getPrimary().getVisualBounds();
    }

    /**
     * Get current screen width
     */
    public static double getScreenWidth() {
        return getScreenBounds().getWidth();
    }

    /**
     * Get current screen height
     */
    public static double getScreenHeight() {
        return getScreenBounds().getHeight();
    }

    /**
     * Get a responsive width based on percentage of screen width
     * 
     * @param percentage 0.0 to 1.0
     */
    public static double getResponsiveWidth(double percentage) {
        return getScreenWidth() * percentage;
    }

    /**
     * Get a responsive height based on percentage of screen height
     * 
     * @param percentage 0.0 to 1.0
     */
    public static double getResponsiveHeight(double percentage) {
        return getScreenHeight() * percentage;
    }

    /**
     * Configure a dialog to be responsive
     * 
     * @param dialog        The dialog to configure
     * @param widthPercent  Target width percentage (0.0 to 1.0)
     * @param heightPercent Target height percentage (0.0 to 1.0)
     */
    public static void setupResponsiveDialog(Dialog<?> dialog, double widthPercent, double heightPercent) {
        double width = getResponsiveWidth(widthPercent);
        double height = getResponsiveHeight(heightPercent);

        dialog.getDialogPane().setPrefWidth(width);
        dialog.getDialogPane().setPrefHeight(height);

        // Also set min sizes to avoid becoming too small on small screens
        dialog.getDialogPane().setMinWidth(Math.min(width, 400));
        dialog.getDialogPane().setMinHeight(Math.min(height, 300));

        // Ensure max size doesn't exceed screen
        dialog.getDialogPane().setMaxWidth(getScreenWidth());
        dialog.getDialogPane().setMaxHeight(getScreenHeight());

        Window window = dialog.getDialogPane().getScene().getWindow();
        if (window instanceof Stage) {
            ((Stage) window).setResizable(true);
        }
    }

    /**
     * Check if the screen is small (e.g. tablet or smaller)
     */
    public static boolean isSmallScreen() {
        return getScreenWidth() < 1024;
    }

    /**
     * Get the current scaling factor based on 13-inch reference resolution
     */
    public static double getScaleFactor() {
        if (!com.pos.config.ConfigManager.getInstance().getBooleanProperty("app.screen.autoScale", false)) {
            return 1.0;
        }

        double currentWidth = Screen.getPrimary().getVisualBounds().getWidth();
        // Reference 13-inch width is 1440
        double scale = currentWidth / 1440.0;

        // Cap scaling to reasonable limits (don't scale down too much or up excessively)
        return Math.max(0.8, Math.min(scale, 1.5));
    }
}
