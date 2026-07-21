package com.pos.ui.keyboard;

import com.pos.util.ResponsiveHelper;
import javafx.scene.Parent;
import javafx.scene.layout.Region;

/**
 * Computes on-screen keyboard dimensions for different display sizes (12–13" POS screens).
 */
final class FloatingKeyboardLayout {

    private static final double QWERTY_BASE_WIDTH = 600;
    private static final double QWERTY_BASE_HEIGHT = 268;
    private static final double NUMERIC_BASE_WIDTH = 320;
    private static final double NUMERIC_BASE_HEIGHT = 380;

    private static final String COMPACT_STYLE = "floating-keyboard-compact";
    private static final String NUMERIC_COMPACT_STYLE = "floating-keypad-compact";

    private FloatingKeyboardLayout() {
    }

    record Size(double width, double height, boolean compact) {
    }

    static Size qwertySize() {
        double screenW = ResponsiveHelper.getScreenWidth();
        double screenH = ResponsiveHelper.getScreenHeight();

        boolean compact = screenW < 1440 || screenH < 900;

        double width = QWERTY_BASE_WIDTH;
        if (compact) {
            width = Math.min(QWERTY_BASE_WIDTH, screenW * 0.96);
            width = Math.max(480, width);
        } else {
            width = Math.min(QWERTY_BASE_WIDTH, screenW * 0.92);
        }

        double height = compact ? 248 : QWERTY_BASE_HEIGHT;
        double maxHeight = screenH * 0.42;
        if (height > maxHeight) {
            height = Math.max(220, maxHeight);
        }

        return new Size(width, height, compact);
    }

    static Size numericSize() {
        double screenW = ResponsiveHelper.getScreenWidth();
        double screenH = ResponsiveHelper.getScreenHeight();

        boolean compact = screenW < 1440 || screenH < 900;

        double width = NUMERIC_BASE_WIDTH;
        if (compact) {
            width = Math.min(NUMERIC_BASE_WIDTH, screenW * 0.55);
            width = Math.max(280, width);
        }

        double height = compact ? 340 : NUMERIC_BASE_HEIGHT;
        double maxHeight = screenH * 0.48;
        if (height > maxHeight) {
            height = Math.max(300, maxHeight);
        }

        return new Size(width, height, compact);
    }

    static void applyQwerty(Parent root) {
        apply(root, qwertySize(), COMPACT_STYLE);
    }

    static void applyNumeric(Parent root) {
        apply(root, numericSize(), NUMERIC_COMPACT_STYLE);
    }

    private static void apply(Parent root, Size size, String compactStyleClass) {
        root.getStyleClass().remove(compactStyleClass);
        if (size.compact()) {
            root.getStyleClass().add(compactStyleClass);
        }

        if (root instanceof Region region) {
            region.setPrefWidth(size.width());
            region.setPrefHeight(size.height());
            region.setMinWidth(size.width());
            region.setMinHeight(size.height());
            region.setMaxWidth(size.width());
            region.setMaxHeight(size.height());
        }
    }
}
