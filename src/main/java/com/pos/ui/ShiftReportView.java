package com.pos.ui;

import com.pos.model.EmployeeShift;
import com.pos.service.EmployeeShiftService;
import com.pos.ui.dialogs.ShiftDetailsDialog;
import com.pos.util.DialogHelper;
import com.pos.util.ExportService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shift Report View - Employee clock-in/out tracking
 */
public class ShiftReportView extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(ShiftReportView.class);

    private final EmployeeShiftService employeeShiftService;
    private final Runnable onBack;

    // UI Components
    private ComboBox<String> dateRangeCombo;
    private DatePicker startDatePicker;
    private DatePicker endDatePicker;
    private ComboBox<String> employeeFilterCombo;
    private Button refreshButton;
    private Button exportButton;
    private Button viewDetailsButton;

    // Summary cards
    private Label totalShiftsLabel;
    private Label totalHoursLabel;
    private Label activeNowLabel;

    // Table
    private TableView<EmployeeShift> shiftTable;
    private final ObservableList<EmployeeShift> shiftList = FXCollections.observableArrayList();

    private LocalDate currentStartDate = LocalDate.now();
    private LocalDate currentEndDate = LocalDate.now();

    public ShiftReportView(Runnable onBack) {
        this.employeeShiftService = EmployeeShiftService.getInstance();
        this.onBack = onBack;

        initializeUI();
        loadReports();
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #f5f7fa;");

        // Top section
        VBox topSection = createTopSection();
        setTop(topSection);

        // Center section
        VBox centerSection = new VBox(20);
        centerSection.setPadding(new Insets(20));

        // Summary Cards
        FlowPane summaryCards = createSummaryCards();
        
        // Table
        VBox tableContainer = createTableSection();
        VBox.setVgrow(tableContainer, Priority.ALWAYS);

        centerSection.getChildren().addAll(summaryCards, tableContainer);
        setCenter(centerSection);
    }

    private VBox createTopSection() {
        VBox topBox = new VBox(15);
        topBox.setPadding(new Insets(20));
        topBox.setStyle("-fx-background-color: white; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.1), 10, 0, 0, 0);");

        // Title and Back button row
        HBox titleRow = new HBox(15);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Button backButton = new Button("← Back");
        backButton.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 20; -fx-background-radius: 5; -fx-cursor: hand;");
        backButton.setOnAction(e -> onBack.run());

        Label titleLabel = new Label("Employee Shift Report");
        titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        HBox.setHgrow(titleLabel, Priority.ALWAYS);

        titleRow.getChildren().addAll(backButton, titleLabel);

        // Controls row 1: Date selections
        HBox controlsRow1 = new HBox(15);
        controlsRow1.setAlignment(Pos.CENTER_LEFT);

        dateRangeCombo = new ComboBox<>();
        dateRangeCombo.getItems().addAll("Today", "Yesterday", "This Week", "This Month", "Custom");
        dateRangeCombo.setValue("Today");
        dateRangeCombo.setPrefWidth(120);
        dateRangeCombo.setOnAction(e -> handleDateRangeChange());

        startDatePicker = new DatePicker(currentStartDate);
        startDatePicker.setPrefWidth(140);
        startDatePicker.setDisable(true);
        startDatePicker.setOnAction(e -> { if (!startDatePicker.isDisable()) loadReports(); });

        endDatePicker = new DatePicker(currentEndDate);
        endDatePicker.setPrefWidth(140);
        endDatePicker.setDisable(true);
        endDatePicker.setOnAction(e -> { if (!endDatePicker.isDisable()) loadReports(); });

        controlsRow1.getChildren().addAll(new Label("Range:"), dateRangeCombo, new Label("From:"), startDatePicker, new Label("To:"), endDatePicker);

        // Controls row 2: Filters and Actions
        HBox controlsRow2 = new HBox(15);
        controlsRow2.setAlignment(Pos.CENTER_LEFT);

        employeeFilterCombo = new ComboBox<>();
        employeeFilterCombo.setPromptText("All Employees");
        employeeFilterCombo.setPrefWidth(200);
        employeeFilterCombo.setOnAction(e -> loadReports());

        refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> loadReports());

        exportButton = new Button("Export CSV");
        exportButton.setOnAction(e -> handleExport());

        viewDetailsButton = new Button("View Activities");
        viewDetailsButton.setStyle("-fx-background-color: #FF9800; -fx-text-fill: white; -fx-font-weight: bold;");
        viewDetailsButton.setDisable(true);
        viewDetailsButton.setOnAction(e -> handleViewDetails());

        controlsRow2.getChildren().addAll(new Label("Employee:"), employeeFilterCombo, refreshButton, exportButton, viewDetailsButton);

        topBox.getChildren().addAll(titleRow, controlsRow1, controlsRow2);
        return topBox;
    }

    private FlowPane createSummaryCards() {
        FlowPane pane = new FlowPane(15, 15);
        pane.setAlignment(Pos.CENTER_LEFT);

        totalShiftsLabel = new Label("0");
        totalHoursLabel = new Label("0.00");
        activeNowLabel = new Label("0");

        pane.getChildren().addAll(
            createStatCard("Total Shifts", totalShiftsLabel, "#2196F3"),
            createStatCard("Total Hours", totalHoursLabel, "#4CAF50"),
            createStatCard("Active Now", activeNowLabel, "#FF9800")
        );
        return pane;
    }

    private VBox createStatCard(String title, Label valueLabel, String color) {
        VBox card = new VBox(5);
        card.setPadding(new Insets(15));
        card.setPrefWidth(200);
        card.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 5;");
        
        valueLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: white;");
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: white;");
        
        card.getChildren().addAll(valueLabel, titleLabel);
        return card;
    }

    private VBox createTableSection() {
        VBox section = new VBox(10);
        section.setStyle("-fx-background-color: white; -fx-background-radius: 5; -fx-padding: 10;");

        shiftTable = new TableView<>();
        shiftTable.setItems(shiftList);
        shiftTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        // Selection listener
        shiftTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            viewDetailsButton.setDisable(newVal == null);
        });

        // Double-click listener
        shiftTable.setRowFactory(tv -> {
            TableRow<EmployeeShift> row = new TableRow<>();
            row.setCursor(javafx.scene.Cursor.HAND);
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty()) {
                    // Single click opens details as requested
                    handleViewDetails();
                }
            });
            return row;
        });

        TableColumn<EmployeeShift, String> nameCol = new TableColumn<>("Employee");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("employeeName"));

        TableColumn<EmployeeShift, String> clockInCol = new TableColumn<>("Clock In");
        clockInCol.setCellValueFactory(new PropertyValueFactory<>("formattedClockIn"));

        TableColumn<EmployeeShift, String> clockOutCol = new TableColumn<>("Clock Out");
        clockOutCol.setCellValueFactory(new PropertyValueFactory<>("formattedClockOut"));

        TableColumn<EmployeeShift, String> durationCol = new TableColumn<>("Duration");
        durationCol.setCellValueFactory(new PropertyValueFactory<>("formattedDuration"));

        TableColumn<EmployeeShift, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
        statusCol.setCellFactory(column -> new TableCell<EmployeeShift, String>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(status);
                    if (EmployeeShift.STATUS_ACTIVE.equals(status)) {
                        setStyle("-fx-text-fill: #4CAF50; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: #666;");
                    }
                }
            }
        });

        TableColumn<EmployeeShift, String> notesCol = new TableColumn<>("Notes");
        notesCol.setCellValueFactory(new PropertyValueFactory<>("notes"));

        @SuppressWarnings("unchecked")
        TableColumn<EmployeeShift, ?>[] columns = new TableColumn[] { nameCol, clockInCol, clockOutCol, durationCol, statusCol, notesCol };
        shiftTable.getColumns().addAll(columns);
        
        section.getChildren().add(shiftTable);
        return section;
    }

    private void handleDateRangeChange() {
        String selected = dateRangeCombo.getValue();
        LocalDate today = LocalDate.now();

        switch (selected) {
            case "Today" -> { currentStartDate = today; currentEndDate = today; }
            case "Yesterday" -> { currentStartDate = today.minusDays(1); currentEndDate = today.minusDays(1); }
            case "This Week" -> { currentStartDate = today.minusDays(today.getDayOfWeek().getValue() - 1); currentEndDate = today; }
            case "This Month" -> { currentStartDate = today.withDayOfMonth(1); currentEndDate = today; }
            case "Custom" -> { startDatePicker.setDisable(false); endDatePicker.setDisable(false); return; }
        }

        startDatePicker.setDisable(true);
        endDatePicker.setDisable(true);
        startDatePicker.setValue(currentStartDate);
        endDatePicker.setValue(currentEndDate);
        loadReports();
    }

    private void loadReports() {
        new Thread(() -> {
            try {
                // Load filters
                List<String> employees = employeeShiftService.getDistinctEmployeeNames();
                String selectedEmp = employeeFilterCombo.getValue();
                
                // Get data
                List<EmployeeShift> shifts = employeeShiftService.getShiftHistory(
                   (selectedEmp == null || selectedEmp.equals("All Employees")) ? null : selectedEmp, 
                    currentStartDate, currentEndDate
                );

                Platform.runLater(() -> {
                    // Update filter list if empty
                    if (employeeFilterCombo.getItems().isEmpty()) {
                        employeeFilterCombo.getItems().add("All Employees");
                        employeeFilterCombo.getItems().addAll(employees);
                        employeeFilterCombo.setValue("All Employees");
                    }

                    shiftList.setAll(shifts);
                    
                    // Update global active count separately from current view shifts
                    new Thread(() -> {
                        try {
                            List<EmployeeShift> activeShifts = employeeShiftService.getAllActiveShifts();
                            Platform.runLater(() -> {
                                activeNowLabel.setText(String.valueOf(activeShifts.size()));
                            });
                        } catch (SQLException e) {
                            logger.error("Error loading active shifts count", e);
                        }
                    }).start();

                    updateSummary(shifts);
                });
            } catch (SQLException e) {
                logger.error("Error loading shift reports", e);
            }
        }).start();
    }

    private void updateSummary(List<EmployeeShift> shifts) {
        totalShiftsLabel.setText(String.valueOf(shifts.size()));
        
        BigDecimal totalHours = shifts.stream()
            .filter(s -> s.getTotalHours() != null)
            .map(EmployeeShift::getTotalHours)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        totalHoursLabel.setText(totalHours.setScale(2, java.math.RoundingMode.HALF_UP).toString());
        
        // Note: activeNowLabel is updated separately in loadReports() to show GLOBAL active count
    }

    private void handleViewDetails() {
        EmployeeShift selected = shiftTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            new ShiftDetailsDialog(selected).showAndWait();
        }
    }

    private void handleExport() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Shift Report");
        fileChooser.setInitialFileName("shift_report_" + currentStartDate + "_" + currentEndDate + ".csv");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        
        File file = fileChooser.showSaveDialog(null);
        if (file != null) {
            new Thread(() -> {
                try {
                    List<Map<String, Object>> data = new ArrayList<>();
                    for (EmployeeShift s : shiftList) {
                        data.add(Map.of(
                            "Employee", s.getEmployeeName(),
                            "Clock In", s.getFormattedClockIn(),
                            "Clock Out", s.getFormattedClockOut(),
                            "Duration", s.getFormattedDuration(),
                            "Status", s.getStatus(),
                            "Notes", s.getNotes() != null ? s.getNotes() : ""
                        ));
                    }
                    ExportService.exportSalesToCSV(data, file.getAbsolutePath());
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION, "Report exported successfully to " + file.getName());
                        DialogHelper.setAlertOwner(alert, null);
                        alert.show();
                    });
                } catch (Exception e) {
                    logger.error("Failed to export shift report", e);
                }
            }).start();
        }
    }
}
