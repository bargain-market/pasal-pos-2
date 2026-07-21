package com.pos.ui.dialogs;

import com.pos.model.Employee;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.TouchPasswordField;
import com.pos.util.DialogHelper;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * Dialog for resetting employee PIN
 */
public class PinResetDialog extends Dialog<PinResetDialog.PinResetResult> {

    private TouchPasswordField newPinField;
    private TouchPasswordField confirmPinField;
    private Label employeeInfoLabel;

    private Employee employee;

    public PinResetDialog(Employee employee) {
        this.employee = employee;

        setTitle("Reset PIN");
        setHeaderText("Reset PIN for employee");
        initModality(Modality.APPLICATION_MODAL);

        // Set owner window to ensure dialog appears on same screen
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

        // Create dialog pane
        DialogPane dialogPane = getDialogPane();
        dialogPane.setContent(createForm());
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Set button actions
        Button okButton = (Button) dialogPane.lookupButton(ButtonType.OK);
        okButton.setText("Reset PIN");
        okButton.setOnAction(e -> {
            if (validateForm()) {
                setResult(createResult());
            } else {
                e.consume();
            }
        });
    }

    private VBox createForm() {
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        // content.setPrefWidth(400);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.4, 0.5);

        // Employee info
        if (employee != null) {
            employeeInfoLabel = new Label();
            employeeInfoLabel.setText("Employee: " + employee.getFullName() + " (" + employee.getUsername() + ")");
            employeeInfoLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
            content.getChildren().add(employeeInfoLabel);
        }

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(15);
        grid.setPadding(new Insets(10));

        // New PIN
        Label newPinLabel = new Label("New PIN:");
        newPinField = TouchScreenComponents.createTouchOnlyPasswordField("Enter new PIN (4-8 digits)");
        newPinField.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(newPinField, javafx.scene.layout.Priority.ALWAYS);
        grid.add(newPinLabel, 0, 0);
        grid.add(newPinField, 1, 0);

        // Confirm PIN
        Label confirmPinLabel = new Label("Confirm PIN:");
        confirmPinField = TouchScreenComponents.createTouchOnlyPasswordField("Re-enter new PIN");
        confirmPinField.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(confirmPinField, javafx.scene.layout.Priority.ALWAYS);
        grid.add(confirmPinLabel, 0, 1);
        grid.add(confirmPinField, 1, 1);

        content.getChildren().add(grid);

        // Warning label
        Label warningLabel = new Label("Note: PIN must be 4-8 digits. This will update the local database only.");
        warningLabel.setStyle("-fx-text-fill: #d32f2f; -fx-font-size: 11px;");
        warningLabel.setWrapText(true);
        content.getChildren().add(warningLabel);

        return content;
    }

    private boolean validateForm() {
        String newPin = newPinField.getText();
        String confirmPin = confirmPinField.getText();

        if (newPin == null || newPin.trim().isEmpty()) {
            showAlert("Validation Error", "New PIN is required");
            return false;
        }

        if (newPin.length() < 4 || newPin.length() > 8) {
            showAlert("Validation Error", "PIN must be 4-8 digits");
            return false;
        }

        // Check if PIN contains only digits
        if (!newPin.matches("\\d+")) {
            showAlert("Validation Error", "PIN must contain only digits");
            return false;
        }

        if (!newPin.equals(confirmPin)) {
            showAlert("Validation Error", "PINs do not match");
            return false;
        }

        return true;
    }

    private PinResetResult createResult() {
        return new PinResetResult(
                employee != null ? employee.getId() : null,
                newPinField.getText());
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogHelper.setAlertOwner(alert, getDialogPane().getScene().getWindow());
        alert.showAndWait();
    }

    /**
     * Result class for PIN reset dialog
     */
    public static class PinResetResult {
        public final String employeeId;
        public final String newPin;

        public PinResetResult(String employeeId, String newPin) {
            this.employeeId = employeeId;
            this.newPin = newPin;
        }
    }
}
