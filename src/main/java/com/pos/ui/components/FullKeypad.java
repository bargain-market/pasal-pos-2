package com.pos.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Complete keypad component for multi-line text input (TextArea)
 * Includes all alphanumeric characters, punctuation, space, enter (newline),
 * clear, and backspace
 */
public class FullKeypad extends VBox {

    public interface KeypadListener {
        void onKeyPressed(String key);
    }

    private KeypadListener listener;
    private boolean isUpperCase = true;
    private Button caseToggleButton;

    public FullKeypad() {
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

        // Row 5: Common punctuation
        HBox row5 = createKeypadRow("!", "?", ".", ",", ":", ";", "-", "_", "@", "#");

        // Row 6: More symbols and special characters
        HBox row6 = createKeypadRow("$", "%", "&", "*", "(", ")", "[", "]", "{", "}");

        // Row 7: Space, Enter, Case toggle, Backspace
        HBox row7 = new HBox(10);
        row7.setAlignment(Pos.CENTER);
        row7.setSpacing(10);

        Button spaceBtn = createKeypadButton(" ");
        spaceBtn.setText("SPACE");
        spaceBtn.setPrefWidth(120);

        Button enterBtn = createKeypadButton("\n");
        enterBtn.setText("ENTER");
        enterBtn.getStyleClass().add("keypad-action-button");
        enterBtn.setPrefWidth(120);

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

        Button backspaceBtn = createKeypadButton("\u232B");
        backspaceBtn.getStyleClass().add("keypad-action-button");
        backspaceBtn.setPrefWidth(80);

        row7.getChildren().addAll(spaceBtn, enterBtn, caseToggleButton, backspaceBtn);

        // Row 8: Clear button
        HBox row8 = new HBox();
        row8.setAlignment(Pos.CENTER);
        Button clearBtn = createKeypadButton("C");
        clearBtn.getStyleClass().add("keypad-action-button");
        clearBtn.setPrefWidth(200);
        row8.getChildren().add(clearBtn);

        getChildren().addAll(row1, row2, row3, row4, row5, row6, row7, row8);
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
        String displayText = getDisplayKey(key);
        if (" ".equals(key)) {
            displayText = "SPACE";
        } else if ("\n".equals(key)) {
            displayText = "ENTER";
        }

        Button keyBtn = new Button(displayText);
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
