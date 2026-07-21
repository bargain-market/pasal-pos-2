package com.pos.ui.dialogs;

import com.pos.model.Employee;
import com.pos.service.EmployeeService;
import com.pos.service.PermissionManagementService;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.TouchTextField;
import com.pos.ui.components.TouchPasswordField;
import com.pos.util.DialogHelper;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * Dialog for adding/editing employees
 */
public class EmployeeFormDialog extends Dialog<EmployeeFormDialog.EmployeeFormResult> {

    private static final Logger logger = LoggerFactory.getLogger(EmployeeFormDialog.class);

    private TouchTextField usernameField;
    private TouchTextField fullNameField;
    private TouchPasswordField pinField;
    private TouchPasswordField confirmPinField;
    private ComboBox<String> roleCombo;
    private CheckBox activeCheckbox;

    private Employee existingEmployee;
    private EmployeeService employeeService;
    private PermissionManagementService permissionService;

    public EmployeeFormDialog(Employee employee) {
        this.existingEmployee = employee;
        this.employeeService = EmployeeService.getInstance();
        this.permissionService = PermissionManagementService.getInstance();

        setTitle(employee == null ? "Add Employee" : "Edit Employee");
        setHeaderText(employee == null ? "Enter employee details" : "Edit employee details");
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
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.5, 0.55);
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Set button actions
        Button okButton = (Button) dialogPane.lookupButton(ButtonType.OK);
        okButton.setOnAction(e -> {
            if (validateForm()) {
                setResult(createResult());
            } else {
                e.consume();
            }
        });

        // Populate fields if editing
        if (employee != null) {
            populateFields();
            // Disable username editing for existing employees
            usernameField.getTextField().setEditable(false);
            usernameField.getTextField().setStyle("-fx-background-color: #f0f0f0;");
        }
    }

    private GridPane createForm() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        // Username
        grid.add(new Label("Username:"), 0, 0);
        usernameField = TouchScreenComponents.createTouchOnlyTextField("Employee username");
        // usernameField.setTextFieldPrefWidth(300);
        usernameField.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(usernameField, javafx.scene.layout.Priority.ALWAYS);
        grid.add(usernameField, 1, 0);

        // Full Name
        grid.add(new Label("Full Name:"), 0, 1);
        fullNameField = TouchScreenComponents.createTouchOnlyTextField("Employee full name");
        fullNameField.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(fullNameField, javafx.scene.layout.Priority.ALWAYS);
        grid.add(fullNameField, 1, 1);

        int nextRow = 2;

        // PIN fields (only for new employees)
        if (existingEmployee == null) {
            grid.add(new Label("PIN:"), 0, nextRow);
            pinField = TouchScreenComponents.createTouchOnlyPasswordField("Enter PIN (4-8 digits)");
            pinField.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHgrow(pinField, javafx.scene.layout.Priority.ALWAYS);
            grid.add(pinField, 1, nextRow);
            nextRow++;

            grid.add(new Label("Confirm PIN:"), 0, nextRow);
            confirmPinField = TouchScreenComponents.createTouchOnlyPasswordField("Re-enter PIN");
            confirmPinField.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHgrow(confirmPinField, javafx.scene.layout.Priority.ALWAYS);
            grid.add(confirmPinField, 1, nextRow);
            nextRow++;
        }

        // Role
        grid.add(new Label("Role:"), 0, nextRow);
        roleCombo = new ComboBox<>();
        roleCombo.getItems().addAll(permissionService.getManageableRoles());
        roleCombo.setValue("Cashier");
        roleCombo.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(roleCombo, javafx.scene.layout.Priority.ALWAYS);
        grid.add(roleCombo, 1, nextRow);
        nextRow++;

        // Active Status
        grid.add(new Label("Status:"), 0, nextRow);
        activeCheckbox = new CheckBox("Active");
        activeCheckbox.setSelected(true);
        grid.add(activeCheckbox, 1, nextRow);

        return grid;
    }

    private void populateFields() {
        if (existingEmployee != null) {
            usernameField.setText(existingEmployee.getUsername());
            fullNameField.setText(existingEmployee.getFullName());
            roleCombo.setValue(existingEmployee.getRole() != null ? existingEmployee.getRole() : "Cashier");
            activeCheckbox.setSelected(existingEmployee.isActive());
        }
    }

    private boolean validateForm() {
        if (usernameField.getText().trim().isEmpty()) {
            showAlert("Validation Error", "Username is required");
            return false;
        }

        if (fullNameField.getText().trim().isEmpty()) {
            showAlert("Validation Error", "Full name is required");
            return false;
        }

        // Validate PIN for new employees
        if (existingEmployee == null) {
            String pin = pinField.getText();
            String confirmPin = confirmPinField.getText();

            if (pin == null || pin.trim().isEmpty()) {
                showAlert("Validation Error", "PIN is required");
                return false;
            }

            if (pin.length() < 4 || pin.length() > 8) {
                showAlert("Validation Error", "PIN must be 4-8 digits");
                return false;
            }

            if (!pin.matches("\\d+")) {
                showAlert("Validation Error", "PIN must contain only digits");
                return false;
            }

            if (!pin.equals(confirmPin)) {
                showAlert("Validation Error", "PINs do not match");
                return false;
            }
        }

        // Check if username already exists (only for new employees)
        if (existingEmployee == null) {
            try {
                if (employeeService.usernameExists(usernameField.getText().trim())) {
                    showAlert("Validation Error", "Username already exists");
                    return false;
                }
            } catch (SQLException e) {
                logger.error("Error checking username", e);
                showAlert("Error", "Could not validate username: " + e.getMessage());
                return false;
            }
        }

        return true;
    }

    private EmployeeFormResult createResult() {
        String username = usernameField.getText().trim();
        String fullName = fullNameField.getText().trim();
        String role = roleCombo.getValue() != null ? roleCombo.getValue() : "Cashier";
        boolean isActive = activeCheckbox.isSelected();
        String pin = (existingEmployee == null && pinField != null) ? pinField.getText() : null;

        if (existingEmployee != null) {
            return new EmployeeFormResult(
                    existingEmployee.getId(),
                    username,
                    fullName,
                    role,
                    isActive,
                    null);
        } else {
            return new EmployeeFormResult(
                    null,
                    username,
                    fullName,
                    role,
                    isActive,
                    pin);
        }
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
     * Result class for employee form dialog
     */
    public static class EmployeeFormResult {
        public final String employeeId;
        public final String username;
        public final String fullName;
        public final String role;
        public final boolean isActive;
        public final String pin;

        public EmployeeFormResult(String employeeId, String username, String fullName, String role, boolean isActive, String pin) {
            this.employeeId = employeeId;
            this.username = username;
            this.fullName = fullName;
            this.role = role;
            this.isActive = isActive;
            this.pin = pin;
        }
    }
}
