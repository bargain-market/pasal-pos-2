package com.pos.ui.dialogs;

import com.pos.service.ShiftService;
import com.pos.service.UserAuthService;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.TouchTextField;
import com.pos.ui.components.TouchTextArea;
import com.pos.util.DialogHelper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.math.BigDecimal;

/**
 * Dialog for opening a cash session (shift)
 */
public class CashSessionOpenDialog extends Dialog<CashSessionOpenDialog.CashSessionOpenResult> {

    private TouchTextField openingCashField;
    private TouchTextField registerIdField;
    private TouchTextArea openingNoteField;
    private Label cashierNameLabel;

    public CashSessionOpenDialog() {
        this(null);
    }

    public CashSessionOpenDialog(Window owner) {
        setTitle("Open Cash Session");
        setHeaderText("Enter opening cash amount");
        initModality(Modality.APPLICATION_MODAL);

        // Set owner window to ensure dialog appears on same screen
        if (owner != null) {
            initOwner(owner);
        } else {
            // Try to get the current window from the scene graph
            try {
                Window currentWindow = javafx.stage.Stage.getWindows().stream()
                        .filter(Window::isShowing)
                        .findFirst()
                        .orElse(null);
                if (currentWindow != null) {
                    initOwner(currentWindow);
                }
            } catch (Exception e) {
                // Ignore if we can't set owner
            }
        }

        // Create dialog pane
        DialogPane dialogPane = getDialogPane();
        dialogPane.setContent(createForm());
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.5, 0.6);
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Set result converter to handle both OK and CANCEL
        setResultConverter(dialogButton -> {
            if (dialogButton == ButtonType.OK) {
                if (validateForm()) {
                    return createResult();
                }
                // Validation failed - return null to keep dialog open
                return null;
            }
            // Cancel or close returns null
            return null;
        });

        // Set button actions
        Button okButton = (Button) dialogPane.lookupButton(ButtonType.OK);
        okButton.setText("Open Session");
        okButton.setOnAction(e -> {
            if (!validateForm()) {
                // Validation failed - consume event to prevent dialog from closing
                e.consume();
            }
            // If validation passes, let the result converter handle setting the result
        });

        // Explicitly handle cancel button to ensure proper cleanup
        Button cancelButton = (Button) dialogPane.lookupButton(ButtonType.CANCEL);
        if (cancelButton != null) {
            cancelButton.setOnAction(e -> {
                // Cancel button will trigger result converter with ButtonType.CANCEL
                // which returns null, properly closing the dialog
            });
        }
    }

    private javafx.scene.Node createForm() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        // Set column constraints to prevent label truncation
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setMinWidth(160);
        col1.setPrefWidth(180);

        ColumnConstraints col2 = new ColumnConstraints();
        col2.setHgrow(Priority.ALWAYS);

        grid.getColumnConstraints().addAll(col1, col2);

        // Cashier Name (read-only)
        Label cashierLabel = new Label("Cashier:");
        grid.add(cashierLabel, 0, 0);
        cashierNameLabel = new Label();
        try {
            UserAuthService authService = UserAuthService.getInstance();
            String currentUserName = authService.getCurrentUserName();
            cashierNameLabel
                    .setText(currentUserName != null && !currentUserName.isEmpty() ? currentUserName : "Cashier");
        } catch (Exception e) {
            cashierNameLabel.setText("Cashier");
        }
        cashierNameLabel.setStyle("-fx-font-weight: bold;");
        grid.add(cashierNameLabel, 1, 0);

        // Register ID
        Label registerLabel = new Label("Register ID:");
        grid.add(registerLabel, 0, 1);
        registerIdField = TouchScreenComponents.createCompactTouchTextField("Register ID (e.g., REG001)");
        registerIdField.setText("REG001");
        registerIdField.setMaxWidth(Double.MAX_VALUE);
        grid.add(registerIdField, 1, 1);

        // Opening Cash - Make it prominent
        Label openingCashLabel = new Label("Opening Cash Amount:");
        openingCashLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        openingCashLabel.setWrapText(true);
        grid.add(openingCashLabel, 0, 2);

        openingCashField = TouchScreenComponents.createCompactTouchNumericField("Enter amount (e.g., 100.00)");
        openingCashField.setMaxWidth(Double.MAX_VALUE);
        // Enable enter button on the keypad and connect to submit action
        openingCashField.setShowEnterButton(true);
        openingCashField.setEnterListener(() -> {
            if (validateForm()) {
                setResult(createResult());
                close();
            }
        });
        // Pre-fill with previous shift's actual cash
        BigDecimal prevCash = ShiftService.getInstance().getPreviousShiftActualCash();
        if (prevCash.compareTo(BigDecimal.ZERO) > 0) {
            openingCashField.setText(prevCash.toPlainString());
        }

        // Add input formatting listener
        openingCashField.textProperty().addListener((observable, oldValue, newValue) -> {
            formatCashInput(openingCashField.getTextField(), newValue);
        });
        grid.add(openingCashField, 1, 2);

        // Opening Note
        Label noteLabel = new Label("Opening Note:");
        grid.add(noteLabel, 0, 3);
        openingNoteField = TouchScreenComponents
                .createTouchOnlyTextArea("Optional note about opening cash (e.g., 'Verified by manager')");
        openingNoteField.setPrefRowCount(2); // Reduced from 3 to save space
        openingNoteField.setMaxWidth(Double.MAX_VALUE);
        openingNoteField.setWrapText(true);
        grid.add(openingNoteField, 1, 3);

        // Info label with clearer instructions
        Label infoLabel = new Label(
                "⚠ IMPORTANT: Count and enter the exact amount of cash in the drawer at the start of your shift.\n" +
                        "This amount will be used to calculate cash variance at the end of your shift.");
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #d32f2f; -fx-font-weight: bold;");
        infoLabel.setWrapText(true);
        grid.add(infoLabel, 0, 4, 2, 1);

        // Set focus to opening cash field
        Platform.runLater(() -> openingCashField.focusTextField());

        // Wrap grid in a ScrollPane for better responsiveness on small screens
        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-padding: 0;");

        return scrollPane;
    }

    private boolean validateForm() {
        // Validate register ID
        if (registerIdField.getText().trim().isEmpty()) {
            showAlert("Validation Error", "Register ID is required");
            registerIdField.requestFocus();
            return false;
        }

        // Validate opening cash
        if (openingCashField.getText().trim().isEmpty()) {
            showAlert("Validation Error", "Opening cash amount is required");
            openingCashField.requestFocus();
            return false;
        }

        try {
            BigDecimal openingCash = new BigDecimal(openingCashField.getText().trim());
            if (openingCash.compareTo(BigDecimal.ZERO) < 0) {
                showAlert("Validation Error", "Opening cash cannot be negative");
                openingCashField.requestFocus();
                return false;
            }
        } catch (NumberFormatException e) {
            showAlert("Validation Error", "Invalid cash amount format. Please enter a valid number (e.g., 100.00)");
            openingCashField.requestFocus();
            return false;
        }

        return true;
    }

    private CashSessionOpenResult createResult() {
        String cashierName = cashierNameLabel.getText();
        String registerId = registerIdField.getText().trim();
        BigDecimal openingCash = new BigDecimal(openingCashField.getText().trim());
        String openingNote = openingNoteField.getText().trim();

        return new CashSessionOpenResult(cashierName, registerId, openingCash, openingNote);
    }

    /**
     * Format cash input as user types (remove currency symbols, allow only numbers
     * and decimal)
     */
    private void formatCashInput(javafx.scene.control.TextField field, String newValue) {
        if (newValue == null || newValue.isEmpty()) {
            return;
        }

        // Remove any currency symbols or commas that might be added
        String cleaned = newValue.replaceAll("[^0-9.]", "");

        // Ensure only one decimal point
        int dotIndex = cleaned.indexOf('.');
        if (dotIndex >= 0) {
            String beforeDot = cleaned.substring(0, dotIndex);
            String afterDot = cleaned.substring(dotIndex + 1);
            // Limit to 2 decimal places
            if (afterDot.length() > 2) {
                afterDot = afterDot.substring(0, 2);
            }
            cleaned = beforeDot + "." + afterDot;
        }

        // Update field if value changed (avoid infinite loop)
        if (!cleaned.equals(newValue)) {
            int caretPosition = field.getCaretPosition();
            field.setText(cleaned);
            // Try to maintain cursor position
            if (caretPosition <= cleaned.length()) {
                field.positionCaret(caretPosition);
            }
        }
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogHelper.setAlertOwner(alert,
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
        alert.showAndWait();
    }

    /**
     * Result class for cash session open dialog
     */
    public static class CashSessionOpenResult {
        public final String cashierName;
        public final String registerId;
        public final BigDecimal openingCash;
        public final String openingNote;

        public CashSessionOpenResult(String cashierName, String registerId, BigDecimal openingCash,
                String openingNote) {
            this.cashierName = cashierName;
            this.registerId = registerId;
            this.openingCash = openingCash;
            this.openingNote = openingNote;
        }
    }
}
