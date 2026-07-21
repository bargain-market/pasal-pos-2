package com.pos.ui.util;

/**
 * Global Theme Constants for the Modern POS Design System.
 * Following the MASTER.md guidelines.
 */
public class ThemeConstants {
    // Colors
    public static final String BG_MAIN = "#0F172A";
    public static final String BG_GLASS = "rgba(30, 41, 59, 0.7)";
    public static final String PRIMARY = "#F59E0B";
    public static final String SUCCESS = "#10B981";
    public static final String DANGER = "#EF4444";
    public static final String TEXT_PRIMARY = "#F8FAFC";
    public static final String TEXT_SECONDARY = "#94A3B8";

    // CSS Classes
    public static final String CLASS_GLASS_PANE = "glass-pane";
    public static final String CLASS_BTN_PRIMARY = "btn-primary";
    public static final String CLASS_BTN_SUCCESS = "btn-success";
    public static final String CLASS_BTN_DANGER = "btn-danger";
    public static final String CLASS_HEADING_1 = "heading-1";
    public static final String CLASS_HEADING_2 = "heading-2";
    public static final String CLASS_NUMERIC = "numeric-data";

    // Styles for programmatic application if needed
    public static final String GLASS_STYLE = 
        "-fx-background-color: " + BG_GLASS + "; " +
        "-fx-background-radius: 12; " +
        "-fx-border-color: rgba(255, 255, 255, 0.1); " +
        "-fx-border-width: 1; " +
        "-fx-border-radius: 12;";
}
