package com.pos.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.RowConstraints;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Compact alphanumeric keypad designed to fit within dialogs or sidebars.
 * Updated with a high-contrast professional theme for maximum visibility.
 */
public class CompactAlphanumericKeypad extends VBox {

    public interface KeypadListener {
        void onKeyPressed(String key);
    }

    private KeypadListener listener;
    private boolean isUpperCase = true;
    private Button caseToggleButton;
    private GridPane keypadGrid;

    // Button sizes
    private static final double BUTTON_HEIGHT = 55;
    private static final double SPACING = 6;

    public CompactAlphanumericKeypad() {
        initializeKeypad();
    }

    public void setListener(KeypadListener listener) {
        this.listener = listener;
    }

    private void initializeKeypad() {
        setSpacing(SPACING);
        setAlignment(Pos.CENTER);
        setPadding(new Insets(10));
        setStyle("-fx-background-color: #1e293b; -fx-background-radius: 12;"); // Dark slate background
        setMaxWidth(Double.MAX_VALUE);
        setFillWidth(true);

        keypadGrid = new GridPane();
        keypadGrid.setHgap(SPACING);
        keypadGrid.setVgap(SPACING);
        keypadGrid.setAlignment(Pos.CENTER);
        keypadGrid.setMaxWidth(Double.MAX_VALUE);

        // Set column constraints for equal width distribution (11 columns)
        for (int i = 0; i < 11; i++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(100.0 / 11.0);
            col.setFillWidth(true);
            keypadGrid.getColumnConstraints().add(col);
        }

        // Set row constraints
        for (int i = 0; i < 5; i++) {
            RowConstraints row = new RowConstraints();
            row.setPrefHeight(BUTTON_HEIGHT);
            row.setMinHeight(BUTTON_HEIGHT);
            row.setFillHeight(true);
            keypadGrid.getRowConstraints().add(row);
        }

        // Row 0: Numbers 1-0 + Backspace
        String[] row0 = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "\u232B"};
        for (int i = 0; i < row0.length; i++) {
            Button btn;
            if (row0[i].equals("\u232B")) {
                btn = createSpecialButton("\u232B", "#475569");
                btn.setOnAction(e -> fireKey("\u232B"));
            } else {
                btn = createKeyButton(row0[i]);
            }
            keypadGrid.add(btn, i, 0);
        }

        // Row 1: Q W E R T Y U I O P (10 keys)
        String[] row1 = {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"};
        for (int i = 0; i < row1.length; i++) {
            Button btn = createKeyButton(row1[i]);
            keypadGrid.add(btn, i, 1);
        }

        // Row 2: A S D F G H J K L (9 keys)
        String[] row2 = {"A", "S", "D", "F", "G", "H", "J", "K", "L"};
        for (int i = 0; i < row2.length; i++) {
            Button btn = createKeyButton(row2[i]);
            keypadGrid.add(btn, i, 2);
        }
        // Add case toggle at end
        caseToggleButton = createSpecialButton("\u21E7", "#475569");
        caseToggleButton.setOnAction(e -> toggleCase());
        keypadGrid.add(caseToggleButton, 9, 2, 2, 1);

        // Row 3: Z X C V B N M + symbols
        String[] row3 = {"Z", "X", "C", "V", "B", "N", "M", "-", ".", "@"};
        for (int i = 0; i < row3.length; i++) {
            Button btn = createKeyButton(row3[i]);
            keypadGrid.add(btn, i, 3);
        }
        // Clear button at end
        Button clearBtn = createSpecialButton("C", "#991b1b"); // Dark red
        clearBtn.setTextFill(Color.WHITE);
        clearBtn.setOnAction(e -> fireKey("CLEAR"));
        keypadGrid.add(clearBtn, 10, 3);

        // Row 4: Space bar (wide) + Enter
        Button spaceBtn = createSpecialButton("SPACE", "#475569");
        spaceBtn.setOnAction(e -> fireKey(" "));
        keypadGrid.add(spaceBtn, 0, 4, 8, 1);

        Button enterBtn = createSpecialButton("\u21B5", "#2563eb"); // Royal blue
        enterBtn.setTextFill(Color.WHITE);
        enterBtn.setOnAction(e -> fireKey("\u23CE"));
        keypadGrid.add(enterBtn, 8, 4, 3, 1);

        getChildren().add(keypadGrid);
    }

    private Button createKeyButton(String key) {
        String displayKey = isUpperCase ? key.toUpperCase() : key.toLowerCase();
        Button btn = new Button(displayKey);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setMaxHeight(Double.MAX_VALUE);
        btn.setMinHeight(BUTTON_HEIGHT);
        btn.setPrefHeight(BUTTON_HEIGHT);
        btn.setFont(Font.font("System", FontWeight.BOLD, 18));
        btn.setTextFill(Color.WHITE);
        btn.setFocusTraversable(false);

        btn.setStyle(
            "-fx-background-color: #334155; " + // Slate-700
            "-fx-background-radius: 8; " +
            "-fx-border-color: rgba(255,255,255,0.1); " +
            "-fx-border-width: 1; " +
            "-fx-cursor: hand;"
        );

        btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: #475569; -fx-background-radius: 8; -fx-border-color: #2563eb; -fx-border-width: 1;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: #334155; -fx-background-radius: 8; -fx-border-color: rgba(255,255,255,0.1); -fx-border-width: 1;"));
        btn.setOnAction(e -> fireKey(isUpperCase ? key.toUpperCase() : key.toLowerCase()));

        return btn;
    }

    private Button createSpecialButton(String text, String bgColor) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setMaxHeight(Double.MAX_VALUE);
        btn.setMinHeight(BUTTON_HEIGHT);
        btn.setPrefHeight(BUTTON_HEIGHT);
        btn.setFont(Font.font("System", FontWeight.BOLD, 16));
        btn.setTextFill(Color.WHITE);
        btn.setFocusTraversable(false);

        btn.setStyle(
            "-fx-background-color: " + bgColor + "; " +
            "-fx-background-radius: 8; " +
            "-fx-border-color: rgba(255,255,255,0.2); " +
            "-fx-border-width: 1; " +
            "-fx-cursor: hand;"
        );

        btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: derive(" + bgColor + ", 15%); -fx-background-radius: 8; -fx-border-color: white; -fx-border-width: 1;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: " + bgColor + "; -fx-background-radius: 8; -fx-border-color: rgba(255,255,255,0.2); -fx-border-width: 1;"));

        return btn;
    }

    private void fireKey(String key) {
        if (listener != null) {
            listener.onKeyPressed(key);
        }
    }

    private void toggleCase() {
        isUpperCase = !isUpperCase;
        caseToggleButton.setText(isUpperCase ? "\u21E7" : "\u21E9");

        // Update all letter buttons in the grid
        for (javafx.scene.Node node : keypadGrid.getChildren()) {
            if (node instanceof Button) {
                Button btn = (Button) node;
                // Only update buttons that have a single letter
                String text = btn.getText();
                if (text != null && text.length() == 1 && Character.isLetter(text.charAt(0))) {
                    btn.setText(isUpperCase ? text.toUpperCase() : text.toLowerCase());
                }
            }
        }
    }
}
