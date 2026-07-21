package com.pos.ui;

import com.pos.service.PermissionManagementService;
import com.pos.service.RoleBasedAccessService;
import com.pos.ui.dialogs.RolePermissionManagementDialog;
import com.pos.util.DialogHelper;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Permissions Management View - Manage roles and the permissions granted to each role.
 */
public class PermissionsManagementView extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(PermissionsManagementView.class);

    private final PermissionManagementService permissionService;
    private final ObservableList<PermissionManagementService.RolePermissionInfo> roleList;
    private TableView<PermissionManagementService.RolePermissionInfo> rolesTable;
    private ComboBox<String> roleFilter;
    private Label statsLabel;
    private final Runnable onBackToSales;

    public PermissionsManagementView() {
        this(null);
    }

    public PermissionsManagementView(Runnable onBackToSales) {
        RoleBasedAccessService rbacService = RoleBasedAccessService.getInstance();
        if (!rbacService.isAdmin()) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Access Denied");
            alert.setHeaderText("Permission Denied");
            alert.setContentText("You do not have permission to manage role permissions. Admin role required.");
            DialogHelper.setAlertOwner(alert, null);
            alert.showAndWait();
            if (onBackToSales != null) {
                Platform.runLater(onBackToSales);
            }
            this.permissionService = PermissionManagementService.getInstance();
            this.roleList = FXCollections.observableArrayList();
            this.onBackToSales = onBackToSales;
            return;
        }

        this.permissionService = PermissionManagementService.getInstance();
        this.roleList = FXCollections.observableArrayList();
        this.onBackToSales = onBackToSales;

        initializeUI();
        loadRoles();
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #f5f7fa;");
        setTop(createTopSection());

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setContent(createRolesTable());
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        setCenter(scrollPane);

        setBottom(createBottomSection());
    }

    private VBox createTopSection() {
        VBox topSection = new VBox(15);
        topSection.setPadding(new Insets(20));
        topSection.setStyle("-fx-background-color: white;");

        HBox titleRow = new HBox(15);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        if (onBackToSales != null) {
            Button backButton = new Button("\u2190 Back to Sales");
            backButton.setStyle(
                    "-fx-background-color: #2196F3; " +
                            "-fx-text-fill: white; " +
                            "-fx-font-size: 14px; " +
                            "-fx-font-weight: bold; " +
                            "-fx-padding: 8 20; " +
                            "-fx-background-radius: 5; " +
                            "-fx-cursor: hand;");
            backButton.setOnAction(e -> onBackToSales.run());
            titleRow.getChildren().add(backButton);
        }

        Label titleLabel = new Label("Permissions Management");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        titleRow.getChildren().add(titleLabel);

        Label subtitleLabel = new Label("Manage permissions by role. Changes here affect every user assigned to the selected role.");
        subtitleLabel.setStyle("-fx-text-fill: #555; -fx-font-size: 13px;");

        HBox filterRow = new HBox(10);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        roleFilter = new ComboBox<>();
        roleFilter.getItems().add("All Roles");
        roleFilter.getItems().addAll(permissionService.getManageableRoles());
        roleFilter.setValue("All Roles");
        roleFilter.setOnAction(e -> filterRoles());

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> loadRoles());

        filterRow.getChildren().addAll(new Label("Role:"), roleFilter, refreshButton);

        statsLabel = new Label("Loading...");
        statsLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        topSection.getChildren().addAll(titleRow, subtitleLabel, filterRow, statsLabel);
        return topSection;
    }

    private TableView<PermissionManagementService.RolePermissionInfo> createRolesTable() {
        rolesTable = new TableView<>();
        rolesTable.setItems(roleList);
        rolesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<PermissionManagementService.RolePermissionInfo, String> roleCol = new TableColumn<>("Role");
        roleCol.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleStringProperty(cellData.getValue().roleName));
        roleCol.setPrefWidth(180);
        roleCol.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String role, boolean empty) {
                super.updateItem(role, empty);
                if (empty || role == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(role);
                    if (RoleBasedAccessService.ROLE_ADMIN.equals(role)) {
                        setStyle("-fx-text-fill: #d32f2f; -fx-font-weight: bold;");
                    } else if (RoleBasedAccessService.ROLE_MANAGER.equals(role)) {
                        setStyle("-fx-text-fill: #1976d2; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: #666;");
                    }
                }
            }
        });

        TableColumn<PermissionManagementService.RolePermissionInfo, Number> grantedCol =
                new TableColumn<>("Granted Permissions");
        grantedCol.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleIntegerProperty(cellData.getValue().grantedPermissionCount));
        grantedCol.setPrefWidth(170);

        TableColumn<PermissionManagementService.RolePermissionInfo, Number> overrideCol =
                new TableColumn<>("Role Overrides");
        overrideCol.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleIntegerProperty(cellData.getValue().overrideCount));
        overrideCol.setPrefWidth(150);
        overrideCol.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Number count, boolean empty) {
                super.updateItem(count, empty);
                if (empty || count == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(String.valueOf(count.intValue()));
                    setStyle(count.intValue() > 0
                            ? "-fx-text-fill: #9C27B0; -fx-font-weight: bold;"
                            : "-fx-text-fill: #999;");
                }
            }
        });

        TableColumn<PermissionManagementService.RolePermissionInfo, Number> membersCol =
                new TableColumn<>("Assigned Users");
        membersCol.setCellValueFactory(cellData ->
                new javafx.beans.property.SimpleIntegerProperty(cellData.getValue().memberCount));
        membersCol.setPrefWidth(130);

        TableColumn<PermissionManagementService.RolePermissionInfo, String> sourceCol =
                new TableColumn<>("Permission Source");
        sourceCol.setCellValueFactory(cellData -> new javafx.beans.property.SimpleStringProperty(
                cellData.getValue().overrideCount > 0 ? "Role Defaults + Overrides" : "Role Defaults"));
        sourceCol.setPrefWidth(180);
        sourceCol.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(value);
                    setStyle("-fx-text-fill: #2196F3; -fx-font-weight: bold;");
                }
            }
        });

        rolesTable.getColumns().addAll(roleCol, grantedCol, overrideCol, membersCol, sourceCol);
        return rolesTable;
    }

    private FlowPane createBottomSection() {
        FlowPane bottomSection = new FlowPane();
        bottomSection.setPadding(new Insets(15));
        bottomSection.setAlignment(Pos.CENTER);
        bottomSection.setHgap(10);
        bottomSection.setVgap(10);
        bottomSection.setStyle("-fx-background-color: white;");

        Button manageButton = createIconButton("\uD83D\uDD10", "Manage", "#9C27B0");
        manageButton.setOnAction(e -> {
            PermissionManagementService.RolePermissionInfo selected = rolesTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                manageRolePermissions(selected);
            } else {
                showAlert("No Selection", "Please select a role to manage permissions");
            }
        });

        Button viewButton = createIconButton("\uD83D\uDC41", "View", "#2196F3");
        viewButton.setOnAction(e -> {
            PermissionManagementService.RolePermissionInfo selected = rolesTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                viewRolePermissions(selected);
            } else {
                showAlert("No Selection", "Please select a role to view permissions");
            }
        });

        Button resetButton = createIconButton("\u21BB", "Reset", "#FF9800");
        resetButton.setOnAction(e -> {
            PermissionManagementService.RolePermissionInfo selected = rolesTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                resetRolePermissions(selected);
            } else {
                showAlert("No Selection", "Please select a role to reset");
            }
        });

        Button addRoleButton = createIconButton("\u2795", "Add Role", "#4CAF50");
        addRoleButton.setOnAction(e -> addRole());

        bottomSection.getChildren().addAll(addRoleButton, manageButton, viewButton, resetButton);
        return bottomSection;
    }

    private Button createIconButton(String icon, String text, String color) {
        VBox content = new VBox(4);
        content.setAlignment(Pos.CENTER);

        Label iconLabel = new Label(icon);
        iconLabel.setStyle("-fx-font-size: 20px; -fx-text-fill: white;");

        Label textLabel = new Label(text);
        textLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: white;");

        content.getChildren().addAll(iconLabel, textLabel);

        Button button = new Button();
        button.setGraphic(content);
        button.setMinWidth(75);
        button.setMinHeight(60);
        button.setPrefWidth(85);
        button.setPrefHeight(65);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setStyle(
                "-fx-background-color: " + color + ";" +
                        "-fx-text-fill: white;" +
                        "-fx-background-radius: 8;" +
                        "-fx-cursor: hand;" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 4, 0, 0, 1);");

        button.setOnMouseEntered(ev -> button.setStyle(
                "-fx-background-color: derive(" + color + ", -10%);" +
                        "-fx-text-fill: white;" +
                        "-fx-background-radius: 8;" +
                        "-fx-cursor: hand;" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 6, 0, 0, 2);"));
        button.setOnMouseExited(ev -> button.setStyle(
                "-fx-background-color: " + color + ";" +
                        "-fx-text-fill: white;" +
                        "-fx-background-radius: 8;" +
                        "-fx-cursor: hand;" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 4, 0, 0, 1);"));

        return button;
    }

    private void loadRoles() {
        new Thread(() -> {
            try {
                List<PermissionManagementService.RolePermissionInfo> roles = permissionService.getAllRolesWithPermissions();
                List<String> availableRoles = permissionService.getManageableRoles();
                Platform.runLater(() -> {
                    String selectedFilter = roleFilter.getValue();
                    roleList.clear();
                    roleList.addAll(roles);
                    roleFilter.getItems().setAll("All Roles");
                    roleFilter.getItems().addAll(availableRoles);
                    roleFilter.setValue(
                            selectedFilter != null && roleFilter.getItems().contains(selectedFilter)
                                    ? selectedFilter
                                    : "All Roles");
                    filterRoles();
                    updateStatistics();
                    logger.info("Loaded {} roles for permission management", roles.size());
                });
            } catch (SQLException e) {
                logger.error("Error loading roles", e);
                Platform.runLater(() -> showAlert("Error", "Failed to load roles: " + e.getMessage()));
            }
        }).start();
    }

    private void filterRoles() {
        String selectedRole = roleFilter.getValue();
        if (selectedRole == null || "All Roles".equals(selectedRole)) {
            rolesTable.setItems(roleList);
            return;
        }

        ObservableList<PermissionManagementService.RolePermissionInfo> filtered = FXCollections.observableArrayList();
        for (PermissionManagementService.RolePermissionInfo role : roleList) {
            if (selectedRole.equals(role.roleName)) {
                filtered.add(role);
            }
        }
        rolesTable.setItems(filtered);
    }

    private void manageRolePermissions(PermissionManagementService.RolePermissionInfo role) {
        RolePermissionManagementDialog dialog = new RolePermissionManagementDialog(role.roleName);
        Optional<RolePermissionManagementDialog.RolePermissionResult> result = dialog.showAndWait();
        result.ifPresent(dialogResult -> {
            if (dialogResult.success) {
                RoleBasedAccessService.getInstance().clearPermissionCache();
                loadRoles();
                showAlert("Success", "Role permissions updated successfully");
            }
        });
    }

    private void addRole() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Add Role");
        dialog.setHeaderText("Create New Role");
        dialog.setContentText("Role name:");
        DialogHelper.setDialogOwner(dialog, getScene() != null ? getScene().getWindow() : null);

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(roleName -> {
            String trimmedRole = roleName != null ? roleName.trim() : "";
            if (trimmedRole.isEmpty()) {
                showAlert("Validation Error", "Role name is required");
                return;
            }

            new Thread(() -> {
                try {
                    com.pos.service.UserAuthService userAuthService = com.pos.service.UserAuthService.getInstance();
                    String currentUserId = userAuthService.getCurrentUserId();
                    if (currentUserId == null && userAuthService.isPosUser()) {
                        currentUserId = userAuthService.getCurrentPosUserId();
                    }

                    String normalizedRole = permissionService.createRole(
                            trimmedRole,
                            currentUserId != null ? currentUserId : "system");

                    Platform.runLater(() -> {
                        loadRoles();
                        roleFilter.setValue(normalizedRole);
                        RolePermissionManagementDialog roleDialog = new RolePermissionManagementDialog(normalizedRole);
                        Optional<RolePermissionManagementDialog.RolePermissionResult> manageResult = roleDialog.showAndWait();
                        manageResult.ifPresent(dialogResult -> {
                            if (dialogResult.success) {
                                RoleBasedAccessService.getInstance().clearPermissionCache();
                                loadRoles();
                            }
                        });
                    });
                } catch (SQLException e) {
                    logger.error("Error creating role", e);
                    Platform.runLater(() -> showAlert("Error", "Failed to create role: " + e.getMessage()));
                }
            }).start();
        });
    }

    private void viewRolePermissions(PermissionManagementService.RolePermissionInfo role) {
        new Thread(() -> {
            try {
                Map<String, Boolean> effectivePermissions = permissionService.getRoleBasedPermissions(role.roleName);
                Map<String, Boolean> overrides = permissionService.getRolePermissionOverrides(role.roleName);

                Platform.runLater(() -> {
                    StringBuilder message = new StringBuilder();
                    message.append("Permissions for Role: ").append(role.roleName).append("\n\n");
                    message.append("Assigned Users: ").append(role.memberCount).append("\n");
                    message.append("Role Overrides: ").append(role.overrideCount).append("\n\n");
                    message.append("Permissions:\n");

                    for (String permission : permissionService.getAllPermissions()) {
                        boolean granted = effectivePermissions.getOrDefault(permission, false);
                        String source = overrides.containsKey(permission) ? "Role Override" : "Role Default";
                        message.append("  ")
                                .append(granted ? "[x]" : "[ ]")
                                .append(" ")
                                .append(getPermissionDisplayName(permission))
                                .append(" (").append(source).append(")\n");
                    }

                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Role Permissions");
                    alert.setHeaderText(null);
                    alert.setContentText(message.toString());
                    DialogHelper.setAlertOwner(alert, getScene() != null ? getScene().getWindow() : null);
                    alert.showAndWait();
                });
            } catch (SQLException e) {
                logger.error("Error viewing role permissions", e);
                Platform.runLater(() -> showAlert("Error", "Failed to load role permissions: " + e.getMessage()));
            }
        }).start();
    }

    private void resetRolePermissions(PermissionManagementService.RolePermissionInfo role) {
        Alert confirmDialog = new Alert(Alert.AlertType.CONFIRMATION);
        confirmDialog.setTitle("Reset Role Permissions");
        confirmDialog.setHeaderText("Reset " + role.roleName + " Permissions");
        confirmDialog.setContentText(
                "This will remove all custom overrides for the " + role.roleName
                        + " role and restore its default permissions. Continue?");
        DialogHelper.setAlertOwner(confirmDialog, getScene() != null ? getScene().getWindow() : null);

        Optional<ButtonType> result = confirmDialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            new Thread(() -> {
                try {
                    com.pos.service.UserAuthService userAuthService = com.pos.service.UserAuthService.getInstance();
                    String currentUserId = userAuthService.getCurrentUserId();
                    if (currentUserId == null && userAuthService.isPosUser()) {
                        currentUserId = userAuthService.getCurrentPosUserId();
                    }
                    permissionService.resetRolePermissions(role.roleName, currentUserId != null ? currentUserId : "system");

                    Platform.runLater(() -> {
                        RoleBasedAccessService.getInstance().clearPermissionCache();
                        loadRoles();
                        showAlert("Success", "Role permissions reset to defaults");
                    });
                } catch (SQLException e) {
                    logger.error("Error resetting role permissions", e);
                    Platform.runLater(() -> showAlert("Error", "Failed to reset role permissions: " + e.getMessage()));
                }
            }).start();
        }
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

    private void updateStatistics() {
        int totalRoles = roleList.size();
        int totalAssignedUsers = roleList.stream().mapToInt(role -> role.memberCount).sum();
        statsLabel.setText(String.format(
                "Total Roles: %d | Assigned Users: %d | Permission Model: Role-Based",
                totalRoles, totalAssignedUsers));
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogHelper.setAlertOwner(alert, getScene() != null ? getScene().getWindow() : null);
        alert.showAndWait();
    }
}
