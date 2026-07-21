package com.pos.ui.dialogs;

import com.pos.service.PermissionManagementService;
import com.pos.service.RoleBasedAccessService;
import com.pos.service.UserAuthService;
import com.pos.util.DialogHelper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.*;

/**
 * Dialog for managing a user's role and previewing the permissions granted by that role.
 */
public class PermissionManagementDialog extends Dialog<PermissionManagementDialog.PermissionResult> {

    private static final Logger logger = LoggerFactory.getLogger(PermissionManagementDialog.class);

    private final String userId;
    private final String userType;
    private final String userName;
    private final PermissionManagementService permissionService;
    private final UserAuthService userAuthService;

    private ComboBox<String> roleCombo;
    private Map<String, CheckBox> permissionCheckboxes;
    private Map<String, Label> permissionSourceLabels;
    private VBox permissionsContainer;
    private Label rolePermissionsLabel;

    public PermissionManagementDialog(String userId, String userType, String userName) {
        this.userId = userId;
        this.userType = userType;
        this.userName = userName;
        this.permissionService = PermissionManagementService.getInstance();
        this.userAuthService = UserAuthService.getInstance();
        this.permissionCheckboxes = new HashMap<>();
        this.permissionSourceLabels = new HashMap<>();

        initializeDialog();
        loadUserPermissions();
    }

    private void initializeDialog() {
        setTitle("Manage Role Permissions");
        setHeaderText("Manage Role Permissions: " + userName);
        initModality(Modality.APPLICATION_MODAL);

        // Set owner window
        try {
            Window currentWindow = javafx.stage.Stage.getWindows().stream()
                    .filter(Window::isShowing)
                    .findFirst()
                    .orElse(null);
            if (currentWindow != null) {
                initOwner(currentWindow);
            }
        } catch (Exception e) {
            // Ignore
        }

        // Create content
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        // content.setPrefWidth(600);
        // content.setPrefHeight(700);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.6, 0.85);

        // Role selection
        HBox roleBox = new HBox(10);
        roleBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label roleLabel = new Label("Role:");
        roleCombo = new ComboBox<>();
        roleCombo.getItems().addAll(
                RoleBasedAccessService.ROLE_CASHIER,
                RoleBasedAccessService.ROLE_MANAGER,
                RoleBasedAccessService.ROLE_ADMIN);
        roleCombo.setPrefWidth(200);
        roleCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                updatePermissionDisplay();
            }
        });
        roleBox.getChildren().addAll(roleLabel, roleCombo);

        rolePermissionsLabel = new Label("Permissions below are assigned from the selected role.");
        rolePermissionsLabel.setStyle("-fx-text-fill: #2196F3; -fx-font-weight: bold;");

        // Permissions section
        Label permissionsTitle = new Label("Permissions:");
        permissionsTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        // scrollPane.setPrefHeight(450);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        scrollPane.setStyle("-fx-background-color: #f5f5f5;");

        permissionsContainer = new VBox(10);
        permissionsContainer.setPadding(new Insets(10));
        scrollPane.setContent(permissionsContainer);

        // Create permission checkboxes
        List<String> allPermissions = permissionService.getAllPermissions();
        for (String permission : allPermissions) {
            createPermissionRow(permission);
        }

        // Reset button
        Button resetButton = new Button("Clear Legacy Overrides");
        resetButton.setStyle("-fx-background-color: #FF9800; -fx-text-fill: white;");
        resetButton.setOnAction(e -> resetToRoleDefaults());

        content.getChildren().addAll(roleBox, rolePermissionsLabel, permissionsTitle, scrollPane, resetButton);

        getDialogPane().setContent(content);

        // Buttons
        ButtonType saveButtonType = new ButtonType("Save Changes", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(cancelButtonType, saveButtonType);

        // Set result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                return savePermissions();
            }
            return null;
        });

        // Style
        getDialogPane().setStyle("-fx-background-color: #f5f7fa;");
    }

    private void createPermissionRow(String permission) {
        HBox row = new HBox(10);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.setPadding(new Insets(5));

        CheckBox checkbox = new CheckBox(getPermissionDisplayName(permission));
        // checkbox.setPrefWidth(300);
        checkbox.setMaxWidth(Double.MAX_VALUE);
        checkbox.setDisable(true);
        HBox.setHgrow(checkbox, Priority.ALWAYS);
        permissionCheckboxes.put(permission, checkbox);

        Label sourceLabel = new Label();
        sourceLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        sourceLabel.setPrefWidth(200);
        permissionSourceLabels.put(permission, sourceLabel);

        row.getChildren().addAll(checkbox, sourceLabel);
        permissionsContainer.getChildren().add(row);
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

    private void loadUserPermissions() {
        new Thread(() -> {
            try {
                // Get current role - need to query database directly
                String currentRole = getUserRoleFromDB();
                if (currentRole == null) {
                    currentRole = RoleBasedAccessService.ROLE_CASHIER;
                }

                Map<String, Boolean> effectivePermissions = permissionService.getEffectivePermissions(userId, userType);

                final String finalRole = currentRole;
                Platform.runLater(() -> {
                    roleCombo.setValue(finalRole);
                    updatePermissionDisplayWithData(effectivePermissions, finalRole);
                });

            } catch (SQLException e) {
                logger.error("Error loading user permissions", e);
                Platform.runLater(() -> {
                    showError("Failed to load permissions: " + e.getMessage());
                });
            }
        }).start();
    }

    private void updatePermissionDisplay() {
        String selectedRole = roleCombo.getValue();
        if (selectedRole == null) {
            return;
        }

        Map<String, Boolean> rolePermissions = permissionService.getRoleBasedPermissions(selectedRole);
        updatePermissionDisplayWithData(rolePermissions, selectedRole);
    }

    private void updatePermissionDisplayWithData(Map<String, Boolean> effectivePermissions, String role) {
        for (String permission : permissionCheckboxes.keySet()) {
            CheckBox checkbox = permissionCheckboxes.get(permission);
            Label sourceLabel = permissionSourceLabels.get(permission);

            boolean hasPermission = effectivePermissions.getOrDefault(permission, false);

            checkbox.setSelected(hasPermission);
            if (hasPermission) {
                sourceLabel.setText("(Role: " + role + ")");
                sourceLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #2196F3;");
            } else {
                sourceLabel.setText("(Not granted by role)");
                sourceLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #999;");
            }
        }
    }

    private String getUserRoleFromDB() {
        com.pos.database.DatabaseManager dbManager = com.pos.database.DatabaseManager.getInstance();
        try (java.sql.Connection conn = dbManager.getConnection()) {
            String sql;

            if (PermissionManagementService.USER_TYPE_POS_USER.equals(userType)) {
                sql = "SELECT role FROM pos_users WHERE id = ?";
            } else {
                sql = "SELECT role FROM users WHERE id = ?";
            }

            try (java.sql.PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, userId);
                java.sql.ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    String role = rs.getString("role");
                    return role != null && !role.isEmpty() ? role : RoleBasedAccessService.ROLE_CASHIER;
                }
            }
        } catch (Exception e) {
            logger.error("Error getting user role", e);
        }

        return RoleBasedAccessService.ROLE_CASHIER;
    }

    private void resetToRoleDefaults() {
        Alert confirmDialog = new Alert(Alert.AlertType.CONFIRMATION);
        confirmDialog.setTitle("Clear Legacy Overrides");
        confirmDialog.setHeaderText("Clear Legacy Per-User Overrides");
        confirmDialog.setContentText(
                "This will remove any old user-specific permission overrides so this user follows role permissions only. Continue?");
        DialogHelper.setAlertOwner(confirmDialog,
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);

        Optional<ButtonType> result = confirmDialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            new Thread(() -> {
                try {
                    String currentUserId = userAuthService.getCurrentUserId();
                    if (currentUserId == null && userAuthService.isPosUser()) {
                        currentUserId = userAuthService.getCurrentPosUserId();
                    }
                    permissionService.resetToRolePermissions(userId, userType,
                            currentUserId != null ? currentUserId : "system");

                    Platform.runLater(() -> {
                        loadUserPermissions();
                        showSuccess("Legacy user-specific overrides cleared");
                    });
                } catch (SQLException e) {
                    logger.error("Error resetting permissions", e);
                    Platform.runLater(() -> {
                        showError("Failed to reset permissions: " + e.getMessage());
                    });
                }
            }).start();
        }
    }

    private PermissionResult savePermissions() {
        try {
            String currentUserId = userAuthService.getCurrentUserId();
            if (currentUserId == null && userAuthService.isPosUser()) {
                currentUserId = userAuthService.getCurrentPosUserId();
            }
            String changedBy = currentUserId != null ? currentUserId : "system";

            String newRole = roleCombo.getValue();
            String currentRole = getUserRoleFromDB();
            if (newRole != null && !newRole.equals(currentRole)) {
                permissionService.updateUserRole(userId, userType, newRole, changedBy);
            } else {
                permissionService.resetToRolePermissions(userId, userType, changedBy);
            }

            RoleBasedAccessService.getInstance().clearPermissionCache();

            PermissionResult result = new PermissionResult();
            result.success = true;
            result.roleUpdated = (newRole != null && !newRole.equals(currentRole));
            return result;

        } catch (SQLException e) {
            logger.error("Error saving permissions", e);
            showError("Failed to save permissions: " + e.getMessage());
            return null;
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

    /**
     * Result class for permission management dialog
     */
    public static class PermissionResult {
        public boolean success = false;
        public boolean roleUpdated = false;
    }
}
