package com.pos.ui.dialogs;

import com.pos.service.CashCheckLimitService;
import com.pos.util.DialogHelper;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for managing role-based cash/check operation limits.
 * Allows admin to configure daily limits per role.
 */
public class RoleCashCheckLimitDialog extends Dialog<Boolean> {

    private static final Logger logger = LoggerFactory.getLogger(RoleCashCheckLimitDialog.class);

    private final CashCheckLimitService limitService;
    private TableView<RoleLimitRow> roleTable;
    private ObservableList<RoleLimitRow> roleData;
    private Label statusLabel;

    public RoleCashCheckLimitDialog() {
        this.limitService = CashCheckLimitService.getInstance();

        initializeDialog();
        loadRoleLimits();
    }

    private void initializeDialog() {
        setTitle("Role Cash/Check Limits");
        setHeaderText("Configure daily cash/check operation limits per role");
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
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.6, 0.7);

        // Info label
        Label infoLabel = new Label(
            "Configure daily limits for manual cash operations (Add, Drop, Payout, Adjustment).\n" +
            "Users exceeding their limit will need an admin PIN to continue.\n" +
            "Regular sales do not count toward this limit."
        );
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        // Status label
        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setVisible(false);

        // Role table
        roleTable = new TableView<>();
        roleData = FXCollections.observableArrayList();
        roleTable.setItems(roleData);

        // Role name column
        TableColumn<RoleLimitRow, String> nameCol = new TableColumn<>("Role");
        nameCol.setCellValueFactory(cellData -> cellData.getValue().roleNameProperty());
        nameCol.setPrefWidth(150);
        nameCol.setEditable(false);

        // Enable limit column
        TableColumn<RoleLimitRow, Boolean> enabledCol = new TableColumn<>("Limit Enabled");
        enabledCol.setCellValueFactory(cellData -> cellData.getValue().limitEnabledProperty());
        enabledCol.setCellFactory(col -> new CheckBoxTableCell<>());
        enabledCol.setPrefWidth(120);
        enabledCol.setEditable(true);

        // Daily limit column
        TableColumn<RoleLimitRow, Integer> limitCol = new TableColumn<>("Daily Limit");
        limitCol.setCellValueFactory(cellData -> cellData.getValue().dailyLimitProperty().asObject());
        limitCol.setCellFactory(col -> new TextFieldTableCell<>(new javafx.util.converter.IntegerStringConverter()) {
            @Override
            public void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (!empty && getTableRow() != null) {
                    RoleLimitRow row = (RoleLimitRow) getTableRow().getItem();
                    if (row != null && !row.isLimitEnabled()) {
                        setDisable(true);
                        setOpacity(0.5);
                    } else {
                        setDisable(false);
                        setOpacity(1.0);
                    }
                }
            }
        });
        limitCol.setPrefWidth(120);
        limitCol.setEditable(true);

        // Usage column (read-only)
        TableColumn<RoleLimitRow, String> usageCol = new TableColumn<>("Today's Usage");
        usageCol.setCellValueFactory(cellData -> {
            RoleLimitRow row = cellData.getValue();
            return new SimpleStringProperty(row.getCurrentUsage() + " / " + row.getDailyLimit());
        });
        usageCol.setPrefWidth(120);
        usageCol.setEditable(false);

        roleTable.getColumns().addAll(nameCol, enabledCol, limitCol, usageCol);
        roleTable.setEditable(true);
        VBox.setVgrow(roleTable, Priority.ALWAYS);

        // Refresh button
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_LEFT);
        Button refreshButton = new Button("Refresh Usage");
        refreshButton.setStyle(
            "-fx-background-color: #2196F3; " +
            "-fx-text-fill: white; " +
            "-fx-font-weight: bold;"
        );
        refreshButton.setOnAction(e -> loadRoleLimits());
        buttonBox.getChildren().add(refreshButton);

        content.getChildren().addAll(infoLabel, buttonBox, roleTable, statusLabel);

        getDialogPane().setContent(content);

        // Buttons
        ButtonType saveButtonType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(saveButtonType, cancelButtonType);

        // Style the save button
        Button saveButton = (Button) getDialogPane().lookupButton(saveButtonType);
        saveButton.setStyle(
            "-fx-background-color: #4CAF50; " +
            "-fx-text-fill: white; " +
            "-fx-font-weight: bold;"
        );

        // Set result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                return saveRoleLimits();
            }
            return false;
        });
    }

    private void loadRoleLimits() {
        roleData.clear();

        try (Connection conn = com.pos.database.DatabaseManager.getInstance().getConnection()) {
            // Load role limits from local database
            String sql = "SELECT role_id, role_name, limit_enabled, daily_limit FROM role_cash_check_limits ORDER BY role_name";
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {

                while (rs.next()) {
                    String roleId = rs.getString("role_id");
                    String roleName = rs.getString("role_name");
                    boolean limitEnabled = rs.getBoolean("limit_enabled");
                    int dailyLimit = rs.getInt("daily_limit");

                    // Get current usage (simplified - assumes single user per role for display)
                    int currentUsage = getTodayUsageForRole(conn, roleName);

                    roleData.add(new RoleLimitRow(roleId, roleName, limitEnabled, dailyLimit, currentUsage));
                }
            }

            // If no roles configured, add default roles
            if (roleData.isEmpty()) {
                addDefaultRoles();
            }

        } catch (SQLException e) {
            logger.error("Error loading role limits", e);
            showError("Error loading role limits: " + e.getMessage());
        }
    }

    private int getTodayUsageForRole(Connection conn, String roleName) throws SQLException {
        // This is simplified - in reality you'd need to track per-user and aggregate
        // For now, return 0 as usage is tracked per-user
        return 0;
    }

    private void addDefaultRoles() {
        // Add default POS roles
        roleData.add(new RoleLimitRow("admin", "Admin", false, 5, 0));
        roleData.add(new RoleLimitRow("manager", "Manager", false, 5, 0));
        roleData.add(new RoleLimitRow("cashier", "Cashier", true, 5, 0));
    }

    private boolean saveRoleLimits() {
        try (Connection conn = com.pos.database.DatabaseManager.getInstance().getConnection()) {
            conn.setAutoCommit(false);

            String upsertSql = "MERGE INTO role_cash_check_limits (role_id, role_name, limit_enabled, daily_limit, updated_at) " +
                "KEY (role_id) VALUES (?, ?, ?, ?, ?)";

            try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
                for (RoleLimitRow row : roleData) {
                    stmt.setString(1, row.getRoleId());
                    stmt.setString(2, row.getRoleName());
                    stmt.setBoolean(3, row.isLimitEnabled());
                    stmt.setInt(4, row.getDailyLimit());
                    stmt.setString(5, Instant.now().toString());
                    stmt.executeUpdate();
                }
            }

            conn.commit();

            // Trigger sync to backend
            com.pos.sync.SyncManager.getInstance().triggerOutboundSync();

            showInfo("Role limits saved successfully. Changes will sync to backend.");
            return true;

        } catch (SQLException e) {
            logger.error("Error saving role limits", e);
            showError("Error saving role limits: " + e.getMessage());
            return false;
        }
    }

    private void showError(String message) {
        statusLabel.setText(message);
        statusLabel.setStyle("-fx-text-fill: #f44336;");
        statusLabel.setVisible(true);
    }

    private void showInfo(String message) {
        statusLabel.setText(message);
        statusLabel.setStyle("-fx-text-fill: #4CAF50;");
        statusLabel.setVisible(true);
    }

    /**
     * Data class for role limit row in table
     */
    public static class RoleLimitRow {
        private final SimpleStringProperty roleId;
        private final SimpleStringProperty roleName;
        private final SimpleBooleanProperty limitEnabled;
        private final SimpleIntegerProperty dailyLimit;
        private int currentUsage;

        public RoleLimitRow(String roleId, String roleName, boolean limitEnabled, int dailyLimit, int currentUsage) {
            this.roleId = new SimpleStringProperty(roleId);
            this.roleName = new SimpleStringProperty(roleName);
            this.limitEnabled = new SimpleBooleanProperty(limitEnabled);
            this.dailyLimit = new SimpleIntegerProperty(dailyLimit);
            this.currentUsage = currentUsage;

            // When limit is disabled, set daily limit to 0 for display
            this.limitEnabled.addListener((obs, oldVal, newVal) -> {
                if (!newVal) {
                    this.dailyLimit.set(0);
                } else if (this.dailyLimit.get() == 0) {
                    this.dailyLimit.set(5); // Default when enabling
                }
            });
        }

        public String getRoleId() { return roleId.get(); }
        public SimpleStringProperty roleIdProperty() { return roleId; }

        public String getRoleName() { return roleName.get(); }
        public SimpleStringProperty roleNameProperty() { return roleName; }

        public boolean isLimitEnabled() { return limitEnabled.get(); }
        public SimpleBooleanProperty limitEnabledProperty() { return limitEnabled; }

        public int getDailyLimit() { return dailyLimit.get(); }
        public SimpleIntegerProperty dailyLimitProperty() { return dailyLimit; }

        public int getCurrentUsage() { return currentUsage; }
        public void setCurrentUsage(int usage) { this.currentUsage = usage; }
    }
}
