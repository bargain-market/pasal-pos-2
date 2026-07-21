package com.pos.ui.dialogs;

import com.pos.model.EmployeeActivity;
import com.pos.model.EmployeeShift;
import com.pos.service.EmployeeShiftService;
import com.pos.util.DialogHelper;
import com.pos.util.ResponsiveHelper;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Dialog to display detailed activities for an employee shift.
 */
public class ShiftDetailsDialog extends Dialog<Void> {
    private static final Logger logger = LoggerFactory.getLogger(ShiftDetailsDialog.class);

    private final EmployeeShift shift;
    private final EmployeeShiftService employeeShiftService;
    private final ObservableList<EmployeeActivity> activityList = FXCollections.observableArrayList();

    private Label totalSalesValueLabel;
    private Label totalDropsValueLabel;
    private Label totalExpensesValueLabel;
    private Label totalActionsLabel;

    public ShiftDetailsDialog(EmployeeShift shift) {
        this.shift = Objects.requireNonNull(shift, "shift must not be null");
        this.employeeShiftService = EmployeeShiftService.getInstance();

        initializeDialog();
        loadActivities();
    }

    private void initializeDialog() {
        setTitle("Shift Details - " + shift.getEmployeeName());
        setHeaderText("Activities for shift from " + shift.getFormattedClockIn());
        initModality(Modality.APPLICATION_MODAL);

        // Set owner window
        try {
            Window currentWindow = Window.getWindows().stream()
                    .filter(Window::isShowing)
                    .findFirst()
                    .orElse(null);
            if (currentWindow != null) {
                initOwner(currentWindow);
            }
        } catch (Exception e) {
            logger.debug("Could not set dialog owner: {}", e.getMessage());
        }

        ResponsiveHelper.setupResponsiveDialog(this, 0.8, 0.85);

        VBox content = new VBox(20);
        content.setPadding(new Insets(20));
        content.setStyle("-fx-background-color: #f5f7fa;");

        // 1. Shift Info Header
        GridPane infoGrid = createShiftInfoGrid();
        
        // 2. Activities Table
        TableView<EmployeeActivity> table = createActivitiesTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        // 3. Summary Section
        HBox summaryBox = createSummarySection();

        content.getChildren().addAll(infoGrid, table, summaryBox);

        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        var styleUrl = getClass().getResource("/styles/style.css");
        if (styleUrl != null) {
            if (getDialogPane().getScene() != null) {
                getDialogPane().getScene().getStylesheets().add(styleUrl.toExternalForm());
            }
            getDialogPane().sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene != null && !newScene.getStylesheets().contains(styleUrl.toExternalForm())) {
                    newScene.getStylesheets().add(styleUrl.toExternalForm());
                }
            });
        }
    }

    private GridPane createShiftInfoGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(30);
        grid.setVgap(10);
        grid.setPadding(new Insets(15));
        grid.setStyle("-fx-background-color: white; -fx-background-radius: 5; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.05), 5, 0, 0, 0);");

        addInfoRow(grid, "Employee:", shift.getEmployeeName(), 0);
        addInfoRow(grid, "Clock In:", shift.getFormattedClockIn(), 1);
        addInfoRow(grid, "Clock Out:", shift.getFormattedClockOut(), 2);
        addInfoRow(grid, "Duration:", shift.getFormattedDuration(), 3);
        
        addRow(grid, "Status:", shift.getStatus(), 0, 1);
        addRow(grid, "Total Hours:", shift.getTotalHours() != null ? shift.getTotalHours().toString() : "0.00", 1, 1);

        // Summary Totals
        totalSalesValueLabel = new Label("$0.00");
        totalSalesValueLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #2196F3;");
        addRow(grid, "Total Sales:", totalSalesValueLabel, 2, 1);

        totalDropsValueLabel = new Label("$0.00");
        totalDropsValueLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #FF9800;");
        addRow(grid, "Total Drops:", totalDropsValueLabel, 3, 1);

        totalExpensesValueLabel = new Label("$0.00");
        totalExpensesValueLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #f44336;");
        addRow(grid, "Total Exp:", totalExpensesValueLabel, 4, 1);

        return grid;
    }

    private void addInfoRow(GridPane grid, String label, String value, int row) {
        addRow(grid, label, value, row, 0);
    }

    private void addRow(GridPane grid, String label, String value, int row, int colOffset) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #666;");
        Label val = new Label(value != null ? value : "N/A");
        val.setStyle("-fx-font-weight: bold; -fx-text-fill: #333;");
        
        grid.add(lbl, colOffset * 2, row);
        grid.add(val, colOffset * 2 + 1, row);
    }

    private void addRow(GridPane grid, String label, Label valueLabel, int row, int colOffset) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #666;");
        
        grid.add(lbl, colOffset * 2, row);
        grid.add(valueLabel, colOffset * 2 + 1, row);
    }

    private void addInfoRow(GridPane grid, String label, Label valueLabel, int row, int colOffset) {
        Label lbl = new Label(label);
        lbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #666;");
        
        grid.add(lbl, colOffset * 2, row);
        grid.add(valueLabel, colOffset * 2 + 1, row);
    }

    private TableView<EmployeeActivity> createActivitiesTable() {
        TableView<EmployeeActivity> table = new TableView<>(activityList);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No activities found for this shift"));

        TableColumn<EmployeeActivity, String> typeCol = new TableColumn<>("Action Type");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("typeLabel"));
        typeCol.setPrefWidth(120);

        TableColumn<EmployeeActivity, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(new PropertyValueFactory<>("description"));
        descCol.setPrefWidth(300);

        TableColumn<EmployeeActivity, String> amountCol = new TableColumn<>("Amount");
        amountCol.setCellValueFactory(new PropertyValueFactory<>("formattedAmount"));
        amountCol.setPrefWidth(100);
        amountCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<EmployeeActivity, String> timeCol = new TableColumn<>("Time");
        timeCol.setCellValueFactory(new PropertyValueFactory<>("formattedTimestamp"));
        timeCol.setPrefWidth(180);

        table.getColumns().addAll(List.of(typeCol, descCol, amountCol, timeCol));
        
        // Color coding for types
        typeCol.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    if (item.contains("Sale")) setStyle("-fx-text-fill: #2196F3; -fx-font-weight: bold;");
                    else if (item.contains("Void") || item.contains("Refund")) setStyle("-fx-text-fill: #f44336; -fx-font-weight: bold;");
                    else if (item.contains("Drop") || item.contains("Expense")) setStyle("-fx-text-fill: #FF9800; -fx-font-weight: bold;");
                    else if (item.contains("Add")) setStyle("-fx-text-fill: #4CAF50; -fx-font-weight: bold;");
                    else setStyle("");
                }
            }
        });

        return table;
    }

    private HBox createSummarySection() {
        HBox summary = new HBox(20);
        summary.setPadding(new Insets(15));
        summary.setAlignment(Pos.CENTER_RIGHT);
        summary.setStyle("-fx-background-color: white; -fx-background-radius: 5;");

        totalActionsLabel = new Label("Total Actions: 0");
        totalActionsLabel.setStyle("-fx-font-weight: bold;");
        
        summary.getChildren().add(totalActionsLabel);
        return summary;
    }

    private void loadActivities() {
        Thread loaderThread = new Thread(() -> {
            try {
                List<EmployeeActivity> activities = employeeShiftService.getActivitiesForShift(shift);
                
                // Calculate totals
                BigDecimal totalSales = activities.stream()
                        .filter(a -> a.getType() == EmployeeActivity.Type.SALE)
                        .map(EmployeeActivity::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                        
                BigDecimal totalDrops = activities.stream()
                        .filter(a -> a.getType() == EmployeeActivity.Type.CASH_DROP)
                        .map(EmployeeActivity::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal totalExpenses = activities.stream()
                        .filter(a -> a.getType() == EmployeeActivity.Type.EXPENSE)
                        .map(EmployeeActivity::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                java.text.NumberFormat currencyFormat = java.text.NumberFormat.getCurrencyInstance();

                Platform.runLater(() -> {
                    activityList.setAll(activities);
                    totalSalesValueLabel.setText(currencyFormat.format(totalSales));
                    totalDropsValueLabel.setText(currencyFormat.format(totalDrops));
                    totalExpensesValueLabel.setText(currencyFormat.format(totalExpenses));
                    totalActionsLabel.setText("Total Actions: " + activities.size());
                });
            } catch (SQLException e) {
                logger.error("Error loading activities for shift: {}", shift.getId(), e);
                Platform.runLater(() -> DialogHelper.showError("Database Error", "Failed to load shift activities."));
            }
        }, "shift-details-loader");
        loaderThread.setDaemon(true);
        loaderThread.start();
    }
}
