package com.pos.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Reusable alphanumeric keypad component for touch-screen input
 * Supports letters A-Z (with case toggle), numbers 0-9, common symbols, space,
 * clear, and backspace
 */
public class AlphanumericKeypad extends VBox {

    public interface KeypadListener {
        void onKeyPressed(String key);
    }

    private KeypadListener listener;
    private boolean isUpperCase = true;
    private Button caseToggleButton;

    public AlphanumericKeypad() {
        initializeKeypad();
    }

    public void setListener(KeypadListener listener) {
        this.listener = listener;
    }

    private void initializeKeypad() {
        setSpacing(10);
        setAlignment(Pos.CENTER);
        setPadding(new Insets(20, 0, 20, 0));

        // Row 1: Q W E R T Y U I O P
        HBox row1 = createKeypadRow("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P");

        // Row 2: A S D F G H J K L
        HBox row2 = createKeypadRow("A", "S", "D", "F", "G", "H", "J", "K", "L");

        // Row 3: Z X C V B N M
        HBox row3 = createKeypadRow("Z", "X", "C", "V", "B", "N", "M");

        // Row 4: Numbers 1-9, 0
        HBox row4 = createKeypadRow("1", "2", "3", "4", "5", "6", "7", "8", "9", "0");

        // Row 5: Common symbols and actions
        HBox row5 = new HBox(10);
        row5.setAlignment(Pos.CENTER);
        row5.setSpacing(10);

        // Symbols
        Button dashBtn = createKeypadButton("-");
        Button underscoreBtn = createKeypadButton("_");
        Button dotBtn = createKeypadButton(".");
        Button atBtn = createKeypadButton("@");
        Button spaceBtn = createKeypadButton(" ");
        spaceBtn.setText("SPACE");
        spaceBtn.setPrefWidth(120);

        // Case toggle
        caseToggleButton = new Button("ABC");
        caseToggleButton.getStyleClass().add("keypad-action-button");
        caseToggleButton.setPrefWidth(80);
        caseToggleButton.setFocusTraversable(false);
        caseToggleButton.setOnAction(e -> {
            e.consume();
            toggleCase();
        });
        caseToggleButton.setOnMousePressed(e -> e.consume());
        caseToggleButton.setOnMouseReleased(e -> e.consume());

        // Backspace
        Button backspaceBtn = createKeypadButton("\u232B");
        backspaceBtn.getStyleClass().add("keypad-action-button");
        backspaceBtn.setPrefWidth(80);

        row5.getChildren().addAll(dashBtn, underscoreBtn, dotBtn, atBtn, spaceBtn, caseToggleButton, backspaceBtn);

        // Row 6: Clear button
        HBox row6 = new HBox();
        row6.setAlignment(Pos.CENTER);
        Button clearBtn = createKeypadButton("C");
        clearBtn.getStyleClass().add("keypad-action-button");
        clearBtn.setPrefWidth(200);
        row6.getChildren().add(clearBtn);

        getChildren().addAll(row1, row2, row3, row4, row5, row6);
    }

    private HBox createKeypadRow(String... keys) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER);
        row.setSpacing(10);

        for (String key : keys) {
            Button btn = createKeypadButton(key);
            row.getChildren().add(btn);
        }

        return row;
    }

    private Button createKeypadButton(String key) {
        Button keyBtn = new Button(getDisplayKey(key));
        keyBtn.getStyleClass().add("keypad-button");
        keyBtn.setPrefWidth(60);
        keyBtn.setPrefHeight(60);

        // Prevent button from requesting focus when clicked
        keyBtn.setFocusTraversable(false);

        keyBtn.setOnAction(e -> {
            e.consume(); // Consume the event to prevent focus changes
            if (listener != null) {
                listener.onKeyPressed(getActualKey(key));
            }
        });

        // Prevent focus on mouse press and release
        keyBtn.setOnMousePressed(e -> {
            e.consume();
        });

        keyBtn.setOnMouseReleased(e -> {
            e.consume();
        });

        return keyBtn;
    }

    private String getDisplayKey(String key) {
        if (key.length() == 1 && Character.isLetter(key.charAt(0))) {
            return isUpperCase ? key.toUpperCase() : key.toLowerCase();
        }
        return key;
    }

    private String getActualKey(String key) {
        if (key.length() == 1 && Character.isLetter(key.charAt(0))) {
            return isUpperCase ? key.toUpperCase() : key.toLowerCase();
        }
        return key;
    }

    private void toggleCase() {
        isUpperCase = !isUpperCase;
        caseToggleButton.setText(isUpperCase ? "ABC" : "abc");

        // Update all letter buttons
        for (javafx.scene.Node node : getChildren()) {
            if (node instanceof HBox) {
                updateRowCase((HBox) node);
            }
        }
    }

    private void updateRowCase(HBox row) {
        for (javafx.scene.Node node : row.getChildren()) {
            if (node instanceof Button) {
                Button btn = (Button) node;
                String text = btn.getText();
                if (text.length() == 1 && Character.isLetter(text.charAt(0))) {
                    btn.setText(isUpperCase ? text.toUpperCase() : text.toLowerCase());
                }
            }
        }
    }
}
