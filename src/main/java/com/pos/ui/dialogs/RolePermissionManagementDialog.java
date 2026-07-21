package com.pos.ui.dialogs;

import com.pos.service.PermissionManagementService;
import com.pos.service.RoleBasedAccessService;
import com.pos.service.UserAuthService;
import com.pos.util.DialogHelper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dialog for managing permissions assigned to a role.
 */
public class RolePermissionManagementDialog extends Dialog<RolePermissionManagementDialog.RolePermissionResult> {

    private static final Logger logger = LoggerFactory.getLogger(RolePermissionManagementDialog.class);

    private final String roleName;
    private final PermissionManagementService permissionService;
    private final UserAuthService userAuthService;

    private final Map<String, CheckBox> permissionCheckboxes = new HashMap<>();
    private final Map<String, Label> permissionSourceLabels = new HashMap<>();
    private Label summaryLabel;

    public RolePermissionManagementDialog(String roleName) {
        this.roleName = roleName;
        this.permissionService = PermissionManagementService.getInstance();
        this.userAuthService = UserAuthService.getInstance();

        initializeDialog();
        loadRolePermissions();
    }

    private void initializeDialog() {
        setTitle("Manage Role Permissions");
        setHeaderText("Manage Permissions for Role: " + roleName);
        initModality(Modality.APPLICATION_MODAL);

        try {
            Window currentWindow = javafx.stage.Stage.getWindows().stream()
                    .filter(Window::isShowing)
                    .findFirst()
                    .orElse(null);
            if (currentWindow != null) {
                initOwner(currentWindow);
            }
        } catch (Exception ignored) {
        }

        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.55, 0.82);

        summaryLabel = new Label("Loading role permissions...");
        summaryLabel.setStyle("-fx-text-fill: #2196F3; -fx-font-weight: bold;");

        Label permissionsTitle = new Label("Permissions:");
        permissionsTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        scrollPane.setStyle("-fx-background-color: #f5f5f5;");

        VBox permissionsContainer = new VBox(10);
        permissionsContainer.setPadding(new Insets(10));
        scrollPane.setContent(permissionsContainer);

        List<String> allPermissions = permissionService.getAllPermissions();
        for (String permission : allPermissions) {
            HBox row = new HBox(10);
            row.setPadding(new Insets(5));
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            CheckBox checkbox = new CheckBox(getPermissionDisplayName(permission));
            checkbox.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(checkbox, Priority.ALWAYS);
            permissionCheckboxes.put(permission, checkbox);

            Label sourceLabel = new Label();
            sourceLabel.setPrefWidth(220);
            sourceLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
            permissionSourceLabels.put(permission, sourceLabel);

            row.getChildren().addAll(checkbox, sourceLabel);
            permissionsContainer.getChildren().add(row);
        }

        Button resetButton = new Button("Reset Role Defaults");
        resetButton.setStyle("-fx-background-color: #FF9800; -fx-text-fill: white;");
        resetButton.setOnAction(e -> resetToDefaults());

        content.getChildren().addAll(summaryLabel, permissionsTitle, scrollPane, resetButton);
        getDialogPane().setContent(content);

        ButtonType saveButtonType = new ButtonType("Save Changes", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(cancelButtonType, saveButtonType);
        setResultConverter(dialogButton -> dialogButton == saveButtonType ? savePermissions() : null);

        getDialogPane().setStyle("-fx-background-color: #f5f7fa;");
    }

    private void loadRolePermissions() {
        new Thread(() -> {
            try {
                Map<String, Boolean> effectivePermissions = permissionService.getRoleBasedPermissions(roleName);
                Map<String, Boolean> defaults = permissionService.getDefaultRolePermissions(roleName);
                Map<String, Boolean> overrides = permissionService.getRolePermissionOverrides(roleName);

                Platform.runLater(() -> updatePermissionDisplay(effectivePermissions, defaults, overrides));
            } catch (SQLException e) {
                logger.error("Error loading role permissions", e);
                Platform.runLater(() -> showError("Failed to load role permissions: " + e.getMessage()));
            }
        }).start();
    }

    private void updatePermissionDisplay(Map<String, Boolean> effectivePermissions,
                                         Map<String, Boolean> defaults,
                                         Map<String, Boolean> overrides) {
        int grantedCount = 0;
        for (String permission : permissionCheckboxes.keySet()) {
            boolean hasPermission = effectivePermissions.getOrDefault(permission, false);
            boolean defaultValue = defaults.getOrDefault(permission, false);
            boolean isOverride = overrides.containsKey(permission);

            CheckBox checkbox = permissionCheckboxes.get(permission);
            Label sourceLabel = permissionSourceLabels.get(permission);

            checkbox.setSelected(hasPermission);
            if (hasPermission) {
                grantedCount++;
            }

            if (isOverride) {
                sourceLabel.setText(overrides.get(permission) ? "(Role Override: Granted)" : "(Role Override: Revoked)");
                sourceLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #9C27B0;");
            } else if (defaultValue) {
                sourceLabel.setText("(Default for " + roleName + ")");
                sourceLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #2196F3;");
            } else {
                sourceLabel.setText("(Not granted by default)");
                sourceLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #999;");
            }
        }

        summaryLabel.setText(String.format(
                "%s currently grants %d permissions. Changes here affect every user with this role.",
                roleName, grantedCount));
    }

    private RolePermissionResult savePermissions() {
        try {
            Map<String, Boolean> selectedPermissions = new HashMap<>();
            for (Map.Entry<String, CheckBox> entry : permissionCheckboxes.entrySet()) {
                selectedPermissions.put(entry.getKey(), entry.getValue().isSelected());
            }

            String changedBy = getCurrentActorId();
            permissionService.updateRolePermissions(roleName, selectedPermissions, changedBy);
            RoleBasedAccessService.getInstance().clearPermissionCache();

            RolePermissionResult result = new RolePermissionResult();
            result.success = true;
            result.roleName = roleName;
            return result;
        } catch (SQLException e) {
            logger.error("Error saving role permissions", e);
            showError("Failed to save role permissions: " + e.getMessage());
            return null;
        }
    }

    private void resetToDefaults() {
        Alert confirmDialog = new Alert(Alert.AlertType.CONFIRMATION);
        confirmDialog.setTitle("Reset Role Permissions");
        confirmDialog.setHeaderText("Reset " + roleName + " Permissions");
        confirmDialog.setContentText("This will remove all custom overrides for this role and restore the default permission set. Continue?");
        DialogHelper.setAlertOwner(confirmDialog,
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);

        Optional<ButtonType> result = confirmDialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            new Thread(() -> {
                try {
                    permissionService.resetRolePermissions(roleName, getCurrentActorId());
                    RoleBasedAccessService.getInstance().clearPermissionCache();
                    Platform.runLater(() -> {
                        loadRolePermissions();
                        showSuccess("Role permissions reset to defaults");
                    });
                } catch (SQLException e) {
                    logger.error("Error resetting role permissions", e);
                    Platform.runLater(() -> showError("Failed to reset role permissions: " + e.getMessage()));
                }
            }).start();
        }
    }

    private String getCurrentActorId() {
        String currentUserId = userAuthService.getCurrentUserId();
        if (currentUserId == null && userAuthService.isPosUser()) {
            currentUserId = userAuthService.getCurrentPosUserId();
        }
        return currentUserId != null ? currentUserId : "system";
    }

    private String getPermissionDisplayName(String permission) {
        switch (permission) {
            case RoleBasedAccessService.PERMISSION_MANAGE_EMPLOYEES:
                return "Manage Employees";
            case RoleBasedAccessService.PERMISSION_MANAGE_SETTINGS:
                return "Manage Settings";
            case RoleBasedAccessService.PERMISSION_MANAGE_HARDWARE:
                return "Manage Hardware";
            case RoleBasedAccessService.PERMISSION_VOID_TRANSACTION:
                return "Void Transaction";
            case RoleBasedAccessService.PERMISSION_PROCESS_REFUND:
                return "Process Refund";
            case RoleBasedAccessService.PERMISSION_APPROVE_DISCOUNT:
                return "Approve Discount";
            case RoleBasedAccessService.PERMISSION_ADJUST_STOCK:
                return "Adjust Stock";
            case RoleBasedAccessService.PERMISSION_MANAGE_PRODUCTS:
                return "Manage Products";
            case RoleBasedAccessService.PERMISSION_CASH_OPERATIONS:
                return "Cash Operations";
            case RoleBasedAccessService.PERMISSION_NO_SALE:
                return "No Sale / Open Drawer";
            case RoleBasedAccessService.PERMISSION_VIEW_REPORTS:
                return "View Reports";
            case RoleBasedAccessService.PERMISSION_MANAGE_INVENTORY:
                return "Manage Inventory";
            case RoleBasedAccessService.PERMISSION_EXPENSES:
                return "Expenses";
            case RoleBasedAccessService.PERMISSION_VIEW_SHIFT_REPORT:
                return "View Shift Report";
            case RoleBasedAccessService.PERMISSION_END_DAY:
                return "End Day";
            default:
                return permission;
        }
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogHelper.setAlertOwner(alert,
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
        alert.showAndWait();
    }

    private void showSuccess(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Success");
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogHelper.setAlertOwner(alert,
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
        alert.showAndWait();
    }

    public static class RolePermissionResult {
        public boolean success = false;
        public String roleName;
    }
}
