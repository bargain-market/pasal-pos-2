package com.pos.ui;

import com.pos.service.ProductManagementService;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.dialogs.CategoryFormDialog;
import com.pos.util.DialogHelper;
import com.pos.service.SettingsService;
import java.math.BigDecimal;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Department Management View - Full CRUD interface for departments
 */
public class DepartmentManagementView extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(DepartmentManagementView.class);

    private ProductManagementService productService;
    private ObservableList<DepartmentTableItem> departmentList;
    private FilteredList<DepartmentTableItem> filteredDepartments;
    private TableView<DepartmentTableItem> departmentTable;
    private com.pos.ui.components.TouchTextField searchField;
    private Label statsLabel;
    private Runnable onBackToSales;

    public DepartmentManagementView() {
        this(null);
    }

    public DepartmentManagementView(Runnable onBackToSales) {
        this.productService = ProductManagementService.getInstance();
        this.departmentList = FXCollections.observableArrayList();
        this.filteredDepartments = new FilteredList<>(departmentList);
        this.onBackToSales = onBackToSales;

        initializeUI();
        loadDepartments();
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #f5f7fa;");

        // Top section - Title and search
        VBox topSection = createTopSection();
        setTop(topSection);

        // Center - Department table
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setContent(createDepartmentTable());
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
        Label titleLabel = new Label("Department Management");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        titleRow.getChildren().add(titleLabel);

        // Search and filter section
        HBox searchSection = new HBox(10);
        searchSection.setAlignment(Pos.CENTER_LEFT);

        Label searchLabel = new Label("Search:");
        searchField = TouchScreenComponents.createTouchOnlyTextField("Search by department name");
        // searchField.setTextFieldPrefWidth(300);
        // Responsive width - e.g. 40% of screen but constrained
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> filterDepartments());

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> loadDepartments());

        searchSection.getChildren().addAll(searchLabel, searchField, refreshButton);

        // Statistics
        statsLabel = new Label();
        statsLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        updateStatistics();

        topSection.getChildren().addAll(titleRow, searchSection, statsLabel);

        return topSection;
    }

    private TableView<DepartmentTableItem> createDepartmentTable() {
        departmentTable = new TableView<>();
        departmentTable.setItems(filteredDepartments);
        departmentTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // Name column
        TableColumn<DepartmentTableItem, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(cellData -> {
            DepartmentTableItem item = cellData.getValue();
            return item != null ? new SimpleStringProperty(item.getName()) : new SimpleStringProperty("");
        });
        nameCol.setPrefWidth(200);

        // Type column
        TableColumn<DepartmentTableItem, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(cellData -> {
            DepartmentTableItem item = cellData.getValue();
            return item != null ? new SimpleStringProperty(item.getType()) : new SimpleStringProperty("");
        });
        typeCol.setPrefWidth(100);

        // Tax Info column
        TableColumn<DepartmentTableItem, String> taxCol = new TableColumn<>("Tax Info");
        taxCol.setCellValueFactory(cellData -> {
            DepartmentTableItem item = cellData.getValue();
            return item != null ? new SimpleStringProperty(item.getTaxInfo()) : new SimpleStringProperty("");
        });
        taxCol.setPrefWidth(120);

        // Features column
        TableColumn<DepartmentTableItem, String> featuresCol = new TableColumn<>("Features");
        featuresCol.setCellValueFactory(cellData -> {
            DepartmentTableItem item = cellData.getValue();
            return item != null ? new SimpleStringProperty(item.getFeatures()) : new SimpleStringProperty("");
        });
        featuresCol.setPrefWidth(250);

        // Parent Department column
        TableColumn<DepartmentTableItem, String> parentCol = new TableColumn<>("Parent Department");
        parentCol.setCellValueFactory(cellData -> {
            DepartmentTableItem item = cellData.getValue();
            return item != null ? new SimpleStringProperty(item.getParentName()) : new SimpleStringProperty("");
        });
        parentCol.setPrefWidth(180);

        @SuppressWarnings("unchecked")
        TableColumn<DepartmentTableItem, ?>[] columns = new TableColumn[] {
                nameCol, typeCol, taxCol, featuresCol, parentCol
        };
        departmentTable.getColumns().addAll(columns);

        // Double-click to edit
        departmentTable.setRowFactory(tv -> {
            TableRow<DepartmentTableItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    editDepartment(row.getItem());
                }
            });
            return row;
        });

        return departmentTable;
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

    private FlowPane createBottomSection() {
        FlowPane bottomSection = new FlowPane();
        bottomSection.setPadding(new Insets(15));
        bottomSection.setAlignment(Pos.CENTER);
        bottomSection.setHgap(10);
        bottomSection.setVgap(10);
        bottomSection.setStyle("-fx-background-color: white;");

        Button addButton = createIconButton("\u2795", "Add", "#4CAF50");
        addButton.setOnAction(e -> addDepartment());

        Button editButton = createIconButton("\u270E", "Edit", "#2196F3");
        editButton.setDisable(true);
        editButton.setOnAction(e -> {
            DepartmentTableItem selected = departmentTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                editDepartment(selected);
            } else {
                showAlert("No Selection", "Please select a department to edit");
            }
        });

        Button deleteButton = createIconButton("\uD83D\uDDD1", "Delete", "#f44336");
        deleteButton.setDisable(true);
        deleteButton.setOnAction(e -> {
            DepartmentTableItem selected = departmentTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                deleteDepartment(selected);
            } else {
                showAlert("No Selection", "Please select a department to delete");
            }
        });

        Button refreshButton = createIconButton("\u21BB", "Refresh", "#607D8B");
        refreshButton.setOnAction(e -> loadDepartments());

        // Enable/disable edit and delete buttons based on selection
        departmentTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            boolean hasSelection = newVal != null;
            editButton.setDisable(!hasSelection);
            deleteButton.setDisable(!hasSelection);
        });

        bottomSection.getChildren().addAll(addButton, editButton, deleteButton, refreshButton);

        return bottomSection;
    }

    private void loadDepartments() {
        new Thread(() -> {
            try {
                List<ProductManagementService.Department> departments = productService.getDepartments();
                // Build the id->name map once on this background thread so each row's parent
                // name is resolved in-memory instead of querying the DB per cell repaint.
                Map<String, String> deptNameById = new HashMap<>();
                for (ProductManagementService.Department dept : departments) {
                    deptNameById.put(dept.id, dept.name);
                }
                Platform.runLater(() -> {
                    departmentList.clear();
                    for (ProductManagementService.Department dept : departments) {
                        String parentName = dept.parentId != null ? deptNameById.get(dept.parentId) : null;
                        departmentList.add(new DepartmentTableItem(dept, parentName));
                    }
                    updateStatistics();
                    logger.info("Loaded {} departments", departments.size());
                });
            } catch (SQLException e) {
                logger.error("Error loading departments", e);
                Platform.runLater(() -> {
                    showAlert("Error", "Failed to load departments: " + e.getMessage());
                });
            }
        }).start();
    }

    private void filterDepartments() {
        String searchTerm = searchField.getText().trim().toLowerCase();
        filteredDepartments.setPredicate(dept -> {
            if (searchTerm.isEmpty()) {
                return true;
            }
            return dept.getName().toLowerCase().contains(searchTerm);
        });
        updateStatistics();
    }

    private void addDepartment() {
        CategoryFormDialog dialog = new CategoryFormDialog(null, null);
        Optional<CategoryFormDialog.CategoryFormResult> result = dialog.showAndWait();

        result.ifPresent(formResult -> {
            new Thread(() -> {
                try {
                    productService.createDepartment(
                            formResult.name,
                            formResult.icon,
                            formResult.parentId,
                            formResult.departmentType,
                            formResult.taxEnabled,
                            formResult.taxRate,
                            formResult.hideOnRegister,
                            formResult.ebtEligible,
                            formResult.excludeFromGlobalPriceIncrease,
                            formResult.noPointsEarning,
                            formResult.ageVerification,
                            formResult.multipackEnabled,
                            formResult.multipackDiscountType,
                            formResult.multipackDiscountValue,
                            formResult.multipackMinQuantity,
                            formResult.multipackRequiresApproval);
                    Platform.runLater(() -> {
                        loadDepartments();
                        showAlert("Success", "Department created successfully");
                    });
                } catch (SQLException e) {
                    logger.error("Error creating department", e);
                    Platform.runLater(() -> {
                        showAlert("Error", "Failed to create department: " + e.getMessage());
                    });
                }
            }).start();
        });
    }

    private void editDepartment(DepartmentTableItem item) {
        CategoryFormDialog dialog = new CategoryFormDialog(item.getId(), item.getName());
        Optional<CategoryFormDialog.CategoryFormResult> result = dialog.showAndWait();

        result.ifPresent(formResult -> {
            new Thread(() -> {
                try {
                    productService.updateDepartment(
                            item.getId(),
                            formResult.name,
                            formResult.icon,
                            formResult.parentId,
                            formResult.departmentType,
                            formResult.taxEnabled,
                            formResult.taxRate,
                            formResult.hideOnRegister,
                            formResult.ebtEligible,
                            formResult.excludeFromGlobalPriceIncrease,
                            formResult.noPointsEarning,
                            formResult.ageVerification,
                            formResult.multipackEnabled,
                            formResult.multipackDiscountType,
                            formResult.multipackDiscountValue,
                            formResult.multipackMinQuantity,
                            formResult.multipackRequiresApproval);
                    Platform.runLater(() -> {
                        loadDepartments();
                        showAlert("Success", "Department updated successfully");
                    });
                } catch (SQLException e) {
                    logger.error("Error updating department", e);
                    Platform.runLater(() -> {
                        showAlert("Error", "Failed to update department: " + e.getMessage());
                    });
                }
            }).start();
        });
    }

    private void deleteDepartment(DepartmentTableItem item) {
        Alert confirmDialog = new Alert(Alert.AlertType.CONFIRMATION);
        confirmDialog.setTitle("Delete Department");
        confirmDialog.setHeaderText("Confirm Deletion");
        confirmDialog.setContentText("Are you sure you want to delete '" + item.getName() + "'?\n\n" +
                "Products in this department will have their department cleared.");
        DialogHelper.setAlertOwner(confirmDialog, getScene().getWindow());

        Optional<ButtonType> result = confirmDialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            new Thread(() -> {
                try {
                    productService.deleteDepartment(item.getId());
                    Platform.runLater(() -> {
                        loadDepartments();
                        showAlert("Success", "Department deleted successfully");
                    });
                } catch (SQLException e) {
                    logger.error("Error deleting department", e);
                    Platform.runLater(() -> {
                        showAlert("Error", "Failed to delete department: " + e.getMessage());
                    });
                }
            }).start();
        }
    }

    private void updateStatistics() {
        Platform.runLater(() -> {
            int total = departmentList.size();
            int visible = filteredDepartments.size();
            if (total == visible) {
                statsLabel.setText(String.format("Total: %d department%s", total, total != 1 ? "s" : ""));
            } else {
                statsLabel
                        .setText(String.format("Showing %d of %d department%s", visible, total, total != 1 ? "s" : ""));
            }
        });
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
     * Table item wrapper for Department
     */
    private static class DepartmentTableItem {
        private final ProductManagementService.Department department;
        private final String parentName;

        public DepartmentTableItem(ProductManagementService.Department department,
                String parentName) {
            this.department = department;
            // Parent name is resolved once per table load by the caller (from a prebuilt
            // id->name map). Previously this class queried the DB on every cell repaint.
            this.parentName = parentName;
        }

        public String getId() {
            return department.id;
        }

        public String getName() {
            return department.name;
        }

        public String getType() {
            return department.departmentType != null ? department.departmentType : "PRODUCT";
        }

        public String getTaxInfo() {
            if (department.taxEnabled) {
                BigDecimal rate = SettingsService.getInstance().getDefaultTaxRate();
                return String.format("%.2f%% (Global)", rate.multiply(new BigDecimal(100)));
            } else {
                return "No Tax";
            }
        }

        public String getFeatures() {
            StringBuilder featuresBuilder = new StringBuilder();
            if (department.hideOnRegister)
                featuresBuilder.append("Hidden, ");
            if (department.ebtEligible)
                featuresBuilder.append("EBT, ");
            if (department.excludeFromGlobalPriceIncrease)
                featuresBuilder.append("No Price Inc, ");
            if (department.noPointsEarning)
                featuresBuilder.append("No Points, ");
            if (department.ageVerification != null)
                featuresBuilder.append("Age ").append(department.ageVerification).append("+, ");

            if (featuresBuilder.length() > 0) {
                featuresBuilder.setLength(featuresBuilder.length() - 2); // Remove trailing ", "
                return featuresBuilder.toString();
            } else {
                return "-";
            }
        }

        public String getParentName() {
            if (department.parentId == null || department.parentId.isEmpty()) {
                return "-";
            }
            // Pure in-memory read — safe to call on every cell repaint.
            return (parentName != null && !parentName.isEmpty()) ? parentName : "-";
        }
    }
}
