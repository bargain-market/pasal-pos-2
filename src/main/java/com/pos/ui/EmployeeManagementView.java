package com.pos.ui;

import com.pos.model.Employee;
import com.pos.service.EmployeeService;
import com.pos.service.RoleBasedAccessService;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.dialogs.EmployeeFormDialog;
import com.pos.ui.dialogs.PinResetDialog;
import com.pos.util.DialogHelper;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Employee Management View - Full CRUD interface for employees
 */
public class EmployeeManagementView extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(EmployeeManagementView.class);

    private EmployeeService employeeService;
    private ObservableList<EmployeeTableItem> employeeList;
    private TableView<EmployeeTableItem> employeeTable;
    private com.pos.ui.components.TouchTextField searchField;
    private ComboBox<String> roleFilter;
    private ComboBox<String> statusFilter;
    private Label statsLabel;
    private Runnable onBackToSales;

    public EmployeeManagementView() {
        this(null);
    }

    public EmployeeManagementView(Runnable onBackToSales) {
        // Permission check - Admin only
        RoleBasedAccessService rbacService = RoleBasedAccessService.getInstance();
        if (!rbacService.isAdmin()) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Access Denied");
            alert.setHeaderText("Permission Denied");
            alert.setContentText("You do not have permission to manage employees. Admin role required.");
            DialogHelper.setAlertOwner(alert, null);
            alert.showAndWait();
            // Redirect to sales screen if callback available
            if (onBackToSales != null) {
                Platform.runLater(() -> onBackToSales.run());
            }
            return;
        }

        this.employeeService = EmployeeService.getInstance();
        this.employeeList = FXCollections.observableArrayList();
        this.onBackToSales = onBackToSales;

        initializeUI();
        loadEmployees();
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #f5f7fa;");

        // Top section - Title and search
        VBox topSection = createTopSection();
        setTop(topSection);

        // Center - Employee table
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setContent(createEmployeeTable());
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        setCenter(scrollPane);

        // Bottom - Action buttons
        FlowPane bottomSection = createBottomSection();
        setBottom(bottomSection);
    }

    private VBox createTopSection() {
        VBox topSection = new VBox(15);
        topSection.setPadding(new Insets(20));
        topSection.setStyle("-fx-background-color: white;");

        // Title and Back button row
        HBox titleRow = new HBox(15);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        // Back to Sales button
        if (onBackToSales != null) {
            Button backToSalesButton = new Button("\u2190 Back to Sales");
            backToSalesButton.setStyle(
                    "-fx-background-color: #2196F3; " +
                            "-fx-text-fill: white; " +
                            "-fx-font-size: 14px; " +
                            "-fx-font-weight: bold; " +
                            "-fx-padding: 8 20; " +
                            "-fx-background-radius: 5; " +
                            "-fx-cursor: hand;");
            backToSalesButton.setOnAction(e -> onBackToSales.run());
            titleRow.getChildren().add(backToSalesButton);
        }

        // Title
        Label titleLabel = new Label("Employee Management");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        titleRow.getChildren().add(titleLabel);

        // Search and filter section
        HBox searchSection = new HBox(10);
        searchSection.setAlignment(Pos.CENTER_LEFT);

        Label searchLabel = new Label("Search:");
        searchField = TouchScreenComponents.createTouchOnlyTextField("Search by name or username");
        searchField.setTextFieldPrefWidth(300);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> searchEmployees());

        Label roleLabel = new Label("Role:");
        roleFilter = new ComboBox<>();
        roleFilter.getItems().addAll("All Roles", "Cashier", "Manager", "Admin");
        roleFilter.setValue("All Roles");
        roleFilter.setPrefWidth(150);
        roleFilter.setOnAction(e -> filterEmployees());

        Label statusLabel = new Label("Status:");
        statusFilter = new ComboBox<>();
        statusFilter.getItems().addAll("All", "Active", "Inactive");
        statusFilter.setValue("All");
        statusFilter.setPrefWidth(120);
        statusFilter.setOnAction(e -> filterEmployees());

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> loadEmployees());

        Button syncButton = new Button("Sync from Backend");
        syncButton.setOnAction(e -> syncEmployees());

        searchSection.getChildren().addAll(searchLabel, searchField, roleLabel, roleFilter, statusLabel, statusFilter,
                refreshButton, syncButton);

        // Statistics
        statsLabel = new Label();
        statsLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        updateStatistics();

        topSection.getChildren().addAll(titleRow, searchSection, statsLabel);

        return topSection;
    }

    private TableView<EmployeeTableItem> createEmployeeTable() {
        employeeTable = new TableView<>();
        employeeTable.setItems(employeeList);
        employeeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // Username column
        TableColumn<EmployeeTableItem, String> usernameCol = new TableColumn<>("Username");
        usernameCol.setCellValueFactory(new PropertyValueFactory<>("username"));
        usernameCol.setPrefWidth(150);

        // Full Name column
        TableColumn<EmployeeTableItem, String> nameCol = new TableColumn<>("Full Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("fullName"));
        nameCol.setPrefWidth(200);

        // Role column
        TableColumn<EmployeeTableItem, String> roleCol = new TableColumn<>("Role");
        roleCol.setCellValueFactory(new PropertyValueFactory<>("role"));
        roleCol.setPrefWidth(100);
        roleCol.setCellFactory(column -> new TableCell<EmployeeTableItem, String>() {
            @Override
            protected void updateItem(String role, boolean empty) {
                super.updateItem(role, empty);
                if (empty || role == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(role);
                    // Color code roles
                    if ("Admin".equals(role)) {
                        setStyle("-fx-text-fill: #d32f2f; -fx-font-weight: bold;");
                    } else if ("Manager".equals(role)) {
                        setStyle("-fx-text-fill: #1976d2; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: #666;");
                    }
                }
            }
        });

        // Login Access column
        TableColumn<EmployeeTableItem, Boolean> statusCol = new TableColumn<>("Login Access");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("isActive"));
        statusCol.setPrefWidth(100);
        statusCol.setCellFactory(column -> new TableCell<EmployeeTableItem, Boolean>() {
            @Override
            protected void updateItem(Boolean isActive, boolean empty) {
                super.updateItem(isActive, empty);
                if (empty || isActive == null) {
                    setText(null);
                    setStyle("");
                } else {
                    if (isActive) {
                        setText("Can Login");
                        setStyle("-fx-text-fill: #4CAF50; -fx-font-weight: bold;");
                    } else {
                        setText("Access Denied");
                        setStyle("-fx-text-fill: #d32f2f;");
                    }
                }
            }
        });

        // Last Login column
        TableColumn<EmployeeTableItem, String> lastLoginCol = new TableColumn<>("Last Login");
        lastLoginCol.setCellValueFactory(new PropertyValueFactory<>("lastLoginAt"));
        lastLoginCol.setPrefWidth(150);

        @SuppressWarnings("unchecked")
        TableColumn<EmployeeTableItem, ?>[] columns = new TableColumn[] {
                usernameCol, nameCol, roleCol, statusCol, lastLoginCol
        };
        employeeTable.getColumns().addAll(columns);

        return employeeTable;
    }

    /**
     * Create a styled icon button with text label for responsive layout
     */
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
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 4, 0, 0, 1);"
        );
        
        button.setOnMouseEntered(ev -> button.setStyle(
            "-fx-background-color: derive(" + color + ", -10%);" +
            "-fx-text-fill: white;" +
            "-fx-background-radius: 8;" +
            "-fx-cursor: hand;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 6, 0, 0, 2);"
        ));
        button.setOnMouseExited(ev -> button.setStyle(
            "-fx-background-color: " + color + ";" +
            "-fx-text-fill: white;" +
            "-fx-background-radius: 8;" +
            "-fx-cursor: hand;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 4, 0, 0, 1);"
        ));
        
        return button;
    }

    private FlowPane createBottomSection() {
        FlowPane bottomSection = new FlowPane();
        bottomSection.setPadding(new Insets(15));
        bottomSection.setAlignment(Pos.CENTER);
        bottomSection.setHgap(10);
        bottomSection.setVgap(10);
        bottomSection.setStyle("-fx-background-color: white;");

        Button addButton = createIconButton("\u2795", "Add", "#4CAF50");
        addButton.setOnAction(e -> addEmployee());

        Button editButton = createIconButton("\u270E", "Edit", "#2196F3");
        editButton.setOnAction(e -> {
            EmployeeTableItem selected = employeeTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                editEmployee(selected);
            } else {
                showAlert("No Selection", "Please select an employee to edit");
            }
        });

        Button resetPinButton = createIconButton("\uD83D\uDD11", "Reset PIN", "#FF9800");
        resetPinButton.setOnAction(e -> {
            EmployeeTableItem selected = employeeTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                resetPin(selected);
            } else {
                showAlert("No Selection", "Please select an employee to reset PIN");
            }
        });

        Button deactivateButton = createIconButton("\uD83D\uDEAB", "Toggle", "#f44336");
        deactivateButton.setOnAction(e -> {
            EmployeeTableItem selected = employeeTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                toggleEmployeeStatus(selected);
            } else {
                showAlert("No Selection", "Please select an employee");
            }
        });

        bottomSection.getChildren().addAll(addButton, editButton, resetPinButton, deactivateButton);

        return bottomSection;
    }

    private void loadEmployees() {
        new Thread(() -> {
            try {
                List<Employee> employees = employeeService.getAllEmployees();
                Platform.runLater(() -> {
                    employeeList.clear();
                    for (Employee employee : employees) {
                        employeeList.add(new EmployeeTableItem(employee));
                    }
                    updateStatistics();
                    logger.info("Loaded {} employees", employees.size());
                });
            } catch (SQLException e) {
                logger.error("Error loading employees", e);
                Platform.runLater(() -> {
                    showAlert("Error", "Failed to load employees: " + e.getMessage());
                });
            }
        }).start();
    }

    private void searchEmployees() {
        String searchTerm = searchField.getText().trim();
        if (searchTerm.isEmpty()) {
            filterEmployees();
            return;
        }

        new Thread(() -> {
            try {
                List<Employee> employees = employeeService.searchEmployees(searchTerm);
                Platform.runLater(() -> {
                    employeeList.clear();
                    for (Employee employee : employees) {
                        employeeList.add(new EmployeeTableItem(employee));
                    }
                    applyFilters();
                });
            } catch (SQLException e) {
                logger.error("Error searching employees", e);
                Platform.runLater(() -> {
                    showAlert("Error", "Failed to search employees: " + e.getMessage());
                });
            }
        }).start();
    }

    private void filterEmployees() {
        String searchTerm = searchField.getText().trim();
        if (searchTerm.isEmpty()) {
            loadEmployees();
        } else {
            searchEmployees();
        }
    }

    private void applyFilters() {
        String roleFilterValue = roleFilter.getValue();
        String statusFilterValue = statusFilter.getValue();

        if ("All Roles".equals(roleFilterValue) && "All".equals(statusFilterValue)) {
            return; // No filtering needed
        }

        ObservableList<EmployeeTableItem> filtered = FXCollections.observableArrayList();
        for (EmployeeTableItem item : employeeList) {
            boolean roleMatch = "All Roles".equals(roleFilterValue) || roleFilterValue.equals(item.getRole());
            boolean statusMatch = "All".equals(statusFilterValue) ||
                    ("Active".equals(statusFilterValue) && item.isActive()) ||
                    ("Inactive".equals(statusFilterValue) && !item.isActive());

            if (roleMatch && statusMatch) {
                filtered.add(item);
            }
        }

        employeeTable.setItems(filtered);
    }

    private void addEmployee() {
        EmployeeFormDialog dialog = new EmployeeFormDialog(null);
        Optional<EmployeeFormDialog.EmployeeFormResult> result = dialog.showAndWait();

        result.ifPresent(formResult -> {
            new Thread(() -> {
                try {
                    Employee employee = employeeService.createEmployee(
                            formResult.username,
                            formResult.fullName,
                            formResult.role,
                            formResult.pin);

                    if (!formResult.isActive) {
                        employeeService.deactivateEmployee(employee.getId());
                    }

                    Platform.runLater(() -> {
                        loadEmployees();
                        showAlert("Success", "Employee added successfully");
                    });
                } catch (IllegalStateException e) {
                    logger.error("Validation error adding employee", e);
                    Platform.runLater(() -> {
                        showAlert("Validation Error", e.getMessage());
                    });
                } catch (SQLException e) {
                    logger.error("Error adding employee", e);
                    Platform.runLater(() -> {
                        showAlert("Error", "Failed to add employee: " + e.getMessage());
                    });
                }
            }).start();
        });
    }

    private void editEmployee(EmployeeTableItem item) {
        try {
            Employee employee = employeeService.getEmployeeById(item.getId());
            if (employee == null) {
                showAlert("Error", "Employee not found");
                return;
            }

            EmployeeFormDialog dialog = new EmployeeFormDialog(employee);
            Optional<EmployeeFormDialog.EmployeeFormResult> result = dialog.showAndWait();

            result.ifPresent(formResult -> {
                new Thread(() -> {
                    try {
                        employeeService.updateEmployee(
                                formResult.employeeId,
                                formResult.fullName,
                                formResult.role,
                                formResult.isActive);

                        Platform.runLater(() -> {
                            loadEmployees();
                            showAlert("Success", "Employee updated successfully");
                        });
                    } catch (IllegalStateException e) {
                        logger.error("Validation error updating employee", e);
                        Platform.runLater(() -> {
                            showAlert("Validation Error", e.getMessage());
                        });
                    } catch (SQLException e) {
                        logger.error("Error updating employee", e);
                        Platform.runLater(() -> {
                            showAlert("Error", "Failed to update employee: " + e.getMessage());
                        });
                    }
                }).start();
            });
        } catch (SQLException e) {
            logger.error("Error getting employee", e);
            showAlert("Error", "Failed to load employee: " + e.getMessage());
        }
    }

    private void resetPin(EmployeeTableItem item) {
        try {
            Employee employee = employeeService.getEmployeeById(item.getId());
            if (employee == null) {
                showAlert("Error", "Employee not found");
                return;
            }

            PinResetDialog dialog = new PinResetDialog(employee);
            Optional<PinResetDialog.PinResetResult> result = dialog.showAndWait();

            result.ifPresent(pinResult -> {
                new Thread(() -> {
                    try {
                        employeeService.resetPin(pinResult.employeeId, pinResult.newPin);
                        Platform.runLater(() -> {
                            showAlert("Success", "PIN reset successfully");
                        });
                    } catch (SQLException e) {
                        logger.error("Error resetting PIN", e);
                        Platform.runLater(() -> {
                            showAlert("Error", "Failed to reset PIN: " + e.getMessage());
                        });
                    }
                }).start();
            });
        } catch (SQLException e) {
            logger.error("Error getting employee", e);
            showAlert("Error", "Failed to load employee: " + e.getMessage());
        }
    }

    private void toggleEmployeeStatus(EmployeeTableItem item) {
        try {
            Employee employee = employeeService.getEmployeeById(item.getId());
            if (employee == null) {
                showAlert("Error", "Employee not found");
                return;
            }

            String action = employee.isActive() ? "deactivate" : "activate";
            Alert confirmDialog = new Alert(Alert.AlertType.CONFIRMATION);
            confirmDialog.setTitle("Confirm " + action.substring(0, 1).toUpperCase() + action.substring(1));
            confirmDialog.setHeaderText(null);
            confirmDialog.setContentText("Are you sure you want to " + action + " " + employee.getFullName() + "?");
            DialogHelper.setAlertOwner(confirmDialog, getScene().getWindow());

            Optional<ButtonType> result = confirmDialog.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                new Thread(() -> {
                    try {
                        if (employee.isActive()) {
                            employeeService.deactivateEmployee(employee.getId());
                        } else {
                            employeeService.activateEmployee(employee.getId());
                        }

                        Platform.runLater(() -> {
                            loadEmployees();
                            showAlert("Success", "Employee " + action + "d successfully");
                        });
                    } catch (IllegalStateException e) {
                        logger.error("Validation error toggling employee status", e);
                        Platform.runLater(() -> {
                            showAlert("Validation Error", e.getMessage());
                        });
                    } catch (SQLException e) {
                        logger.error("Error toggling employee status", e);
                        Platform.runLater(() -> {
                            showAlert("Error", "Failed to " + action + " employee: " + e.getMessage());
                        });
                    }
                }).start();
            }
        } catch (SQLException e) {
            logger.error("Error getting employee", e);
            showAlert("Error", "Failed to load employee: " + e.getMessage());
        }
    }

    private void syncEmployees() {
        new Thread(() -> {
            try {
                employeeService.syncFromBackend();
                Platform.runLater(() -> {
                    loadEmployees();
                    showAlert("Success", "Employees synced from backend successfully");
                });
            } catch (Exception e) {
                logger.error("Error syncing employees", e);
                Platform.runLater(() -> {
                    showAlert("Error", "Failed to sync employees: " + e.getMessage());
                });
            }
        }).start();
    }

    private void updateStatistics() {
        new Thread(() -> {
            try {
                int total = employeeService.getEmployeeCount();
                int active = employeeService.getActiveEmployeeCount();
                Platform.runLater(() -> {
                    statsLabel.setText(
                            String.format("Total: %d | Active: %d | Inactive: %d", total, active, total - active));
                });
            } catch (SQLException e) {
                logger.error("Error getting statistics", e);
            }
        }).start();
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogHelper.setAlertOwner(alert, getScene().getWindow());
        alert.showAndWait();
    }

    /**
     * Table item wrapper for Employee
     */
    public static class EmployeeTableItem {
        private final Employee employee;

        public EmployeeTableItem(Employee employee) {
            this.employee = employee;
        }

        public String getId() {
            return employee.getId();
        }

        public String getUsername() {
            return employee.getUsername();
        }

        public String getFullName() {
            return employee.getFullName();
        }

        public String getRole() {
            return employee.getRole();
        }

        public boolean isActive() {
            return employee.isActive();
        }

        public String getLastLoginAt() {
            String lastLogin = employee.getLastLoginAt();
            if (lastLogin == null || lastLogin.isEmpty()) {
                return "Never";
            }
            
            // Try to parse and format the timestamp for better readability
            try {
                Instant instant = Instant.parse(lastLogin);
                ZonedDateTime zdt = instant.atZone(java.time.ZoneId.systemDefault());
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm");
                return zdt.format(formatter);
            } catch (DateTimeParseException e) {
                // If parsing fails, return the raw value
                return lastLogin;
            }
        }
    }
}
