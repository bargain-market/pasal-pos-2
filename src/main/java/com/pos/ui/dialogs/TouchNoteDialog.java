package com.pos.ui.dialogs;

import com.pos.ui.components.CompactAlphanumericKeypad;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * Touch-friendly dialog for entering notes (order notes, item notes, etc.)
 * Uses compact on-screen keyboard that fits within the dialog.
 */
public class TouchNoteDialog extends Dialog<String> {

    private TextArea noteArea;
    private CompactAlphanumericKeypad keypad;
    private String initialNote;

    public TouchNoteDialog(String title, String headerText, String initialNote) {
        this.initialNote = initialNote != null ? initialNote : "";
        initializeDialog(title, headerText);
    }

    public TouchNoteDialog(Window owner, String title, String headerText, String initialNote) {
        this.initialNote = initialNote != null ? initialNote : "";
        initOwner(owner);
        initializeDialog(title, headerText);
    }

    private void initializeDialog(String title, String headerText) {
        setTitle(title);
        setHeaderText(headerText);
        initModality(Modality.APPLICATION_MODAL);
        setResizable(true);

        // Set owner window to ensure dialog appears on same screen
        try {
            if (getOwner() == null) {
                Window currentWindow = javafx.stage.Stage.getWindows().stream()
                        .filter(Window::isShowing)
                        .findFirst()
                        .orElse(null);
                if (currentWindow != null) {
                    initOwner(currentWindow);
                }
            }
        } catch (Exception e) {
            // Ignore if we can't set owner
        }

        // Create main content
        VBox content = new VBox(12);
        content.setPadding(new Insets(15));
        content.setAlignment(Pos.TOP_CENTER);

        // Note label
        Label noteLabel = new Label("Enter Note:");
        noteLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");
        noteLabel.setAlignment(Pos.CENTER_LEFT);

        // Note text area - Touch optimized
        noteArea = new TextArea();
        noteArea.setPromptText("Type your note here...");
        noteArea.setPrefRowCount(3);
        noteArea.setPrefHeight(100);
        noteArea.setMinHeight(80);
        noteArea.setWrapText(true);
        noteArea.setMaxWidth(Double.MAX_VALUE);
        noteArea.setStyle(
                "-fx-font-size: 15px; " +
                        "-fx-padding: 10; " +
                        "-fx-background-color: white; " +
                        "-fx-background-radius: 8; " +
                        "-fx-border-color: #1E88E5; " +
                        "-fx-border-radius: 8; " +
                        "-fx-border-width: 2;");

        // Disable physical keyboard input
        noteArea.addEventFilter(KeyEvent.KEY_TYPED, Event::consume);
        noteArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != KeyCode.TAB) {
                e.consume();
            }
        });

        // Set initial value
        if (!initialNote.isEmpty()) {
            noteArea.setText(initialNote);
        }

        // Compact keyboard that fits within dialog
        keypad = new CompactAlphanumericKeypad();
        keypad.setListener(this::handleKeypadInput);
        keypad.setMaxWidth(Double.MAX_VALUE);

        content.getChildren().addAll(noteLabel, noteArea, keypad);

        // Wrap in ScrollPane for smaller screens
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        // scrollPane.setPrefWidth(500);
        // scrollPane.setPrefHeight(420);
        VBox.setVgrow(scrollPane, javafx.scene.layout.Priority.ALWAYS);

        // Set dialog content
        getDialogPane().setContent(scrollPane);

        // Buttons - Touch optimized
        ButtonType saveButtonType = new ButtonType("Save Note", ButtonBar.ButtonData.OK_DONE);
        ButtonType clearButtonType = new ButtonType("Clear", ButtonBar.ButtonData.OTHER);
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(saveButtonType, clearButtonType, cancelButtonType);

        // Style buttons for touch
        Button saveButton = (Button) getDialogPane().lookupButton(saveButtonType);
        styleButton(saveButton, "#4CAF50", true);

        Button clearButton = (Button) getDialogPane().lookupButton(clearButtonType);
        styleButton(clearButton, "#FF9800", false);
        clearButton.setOnAction(e -> {
            noteArea.clear();
            e.consume();
        });

        Button cancelButton = (Button) getDialogPane().lookupButton(cancelButtonType);
        styleButton(cancelButton, "#757575", false);

        // Style dialog
        getDialogPane().setStyle("-fx-background-color: #f5f7fa;");
        // getDialogPane().setPrefWidth(530);
        // getDialogPane().setPrefHeight(500);
        // getDialogPane().setMinWidth(450);
        // getDialogPane().setMinHeight(420);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.5, 0.6);

        // Set result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                return noteArea.getText() != null ? noteArea.getText().trim() : "";
            }
            return null;
        });

        // Focus note area when dialog opens
        javafx.application.Platform.runLater(() -> noteArea.requestFocus());
    }

    private void handleKeypadInput(String key) {
        if ("CLEAR".equals(key)) {
            noteArea.clear();
        } else if ("⌫".equals(key)) {
            String text = noteArea.getText();
            if (!text.isEmpty()) {
                noteArea.setText(text.substring(0, text.length() - 1));
                noteArea.positionCaret(noteArea.getText().length());
            }
        } else {
            noteArea.appendText(key);
        }
    }

    private void styleButton(Button button, String color, boolean isPrimary) {
        button.setPrefHeight(50);
        button.setMinHeight(50);
        button.setPrefWidth(isPrimary ? 150 : 100);
        button.setStyle(
                "-fx-font-size: 14px; " +
                        "-fx-font-weight: bold; " +
                        "-fx-background-color: " + color + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 8; " +
                        "-fx-padding: 10 15; " +
                        "-fx-cursor: hand;");
    }
}
