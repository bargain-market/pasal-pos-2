package com.pos.ui.dialogs;

import com.pos.model.EmployeeShift;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Dialog for clocking out an employee - shows shift summary with duration
 */
public class ClockOutDialog extends Dialog<ClockOutDialog.ClockOutResult> {

    private Label clockInTimeLabel;
    private Label clockOutTimeLabel;
    private Label durationLabel;
    private Label employeeNameLabel;

    private EmployeeShift employeeShift;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");

    public ClockOutDialog(EmployeeShift employeeShift) {
        this(employeeShift, null);
    }

    public ClockOutDialog(EmployeeShift employeeShift, Window owner) {
        this.employeeShift = employeeShift;
        setTitle("Clock Out");
        setHeaderText("End of Shift Summary");
        initModality(Modality.APPLICATION_MODAL);

        // Set owner window to ensure dialog appears on same screen
        if (owner != null) {
            initOwner(owner);
        } else {
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
        }

        // Create dialog pane
        DialogPane dialogPane = getDialogPane();
        dialogPane.setContent(createForm());
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.45, 0.6);
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Style the dialog
        dialogPane.setStyle("-fx-background-color: #f5f5f5;");

        // Set button actions
        Button okButton = (Button) dialogPane.lookupButton(ButtonType.OK);
        okButton.setText("Clock Out");
        okButton.setStyle(
                "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 10 20;");
        okButton.setOnAction(e -> {
            setResult(createResult());
        });

        Button cancelButton = (Button) dialogPane.lookupButton(ButtonType.CANCEL);
        cancelButton.setText("Stay Clocked In");
        cancelButton.setStyle("-fx-font-size: 14px; -fx-padding: 10 20;");
    }

    private VBox createForm() {
        VBox mainContainer = new VBox(20);
        mainContainer.setPadding(new Insets(20));
        mainContainer.setAlignment(Pos.TOP_CENTER);
        mainContainer.setStyle("-fx-background-color: white; -fx-background-radius: 8;");

        // Header with employee name
        VBox headerSection = createHeaderSection();

        // Shift time details
        GridPane timeGrid = createTimeGrid();

        // Duration highlight
        VBox durationSection = createDurationSection();

        mainContainer.getChildren().addAll(headerSection, timeGrid, durationSection);

        return mainContainer;
    }

    private VBox createHeaderSection() {
        VBox header = new VBox(8);
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(0, 0, 10, 0));

        // Clock icon
        Label clockIcon = new Label("🕐");
        clockIcon.setStyle("-fx-font-size: 48px;");

        // Employee name
        employeeNameLabel = new Label(employeeShift != null ? employeeShift.getEmployeeName() : "Employee");
        employeeNameLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #333;");

        // Date
        Label dateLabel = new Label(LocalDateTime.now().format(DATE_FORMATTER));
        dateLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #666;");

        header.getChildren().addAll(clockIcon, employeeNameLabel, dateLabel);
        return header;
    }

    private GridPane createTimeGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(20);
        grid.setVgap(12);
        grid.setPadding(new Insets(15));
        grid.setStyle("-fx-background-color: #f8f9fa; -fx-background-radius: 8;");

        // Clock In Time
        Label clockInLabel = new Label("Clock In:");
        clockInLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #666;");
        grid.add(clockInLabel, 0, 0);

        LocalDateTime clockInTime = employeeShift != null ? employeeShift.getClockInAt() : null;
        clockInTimeLabel = new Label(clockInTime != null ? clockInTime.format(DATETIME_FORMATTER) : "-");
        clockInTimeLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #4CAF50;");
        grid.add(clockInTimeLabel, 1, 0);

        // Clock Out Time (current time)
        Label clockOutLabel = new Label("Clock Out:");
        clockOutLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #666;");
        grid.add(clockOutLabel, 0, 1);

        LocalDateTime clockOutTime = LocalDateTime.now();
        clockOutTimeLabel = new Label(clockOutTime.format(DATETIME_FORMATTER));
        clockOutTimeLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #f44336;");
        grid.add(clockOutTimeLabel, 1, 1);

        return grid;
    }

    private VBox createDurationSection() {
        VBox section = new VBox(8);
        section.setAlignment(Pos.CENTER);
        section.setPadding(new Insets(15));
        section.setStyle(
                "-fx-background-color: #e3f2fd; -fx-background-radius: 8; -fx-border-color: #2196F3; -fx-border-radius: 8; -fx-border-width: 2;");

        Label durationTitle = new Label("Total Shift Duration");
        durationTitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #1565C0; -fx-font-weight: bold;");

        // Calculate duration
        String durationText = calculateDuration();
        durationLabel = new Label(durationText);
        durationLabel.setStyle("-fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: #1565C0;");

        section.getChildren().addAll(durationTitle, durationLabel);
        return section;
    }

    private String calculateDuration() {
        if (employeeShift == null || employeeShift.getClockInAt() == null) {
            return "0h 0m";
        }

        LocalDateTime clockIn = employeeShift.getClockInAt();
        LocalDateTime clockOut = LocalDateTime.now();
        Duration duration = Duration.between(clockIn, clockOut);

        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();

        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else {
            return String.format("%dm", minutes);
        }
    }

    private ClockOutResult createResult() {
        return new ClockOutResult("", true);
    }

    /**
     * Result class for clock out dialog
     */
    public static class ClockOutResult {
        public final String notes;
        public final boolean confirmed;

        public ClockOutResult(String notes, boolean confirmed) {
            this.notes = notes;
            this.confirmed = confirmed;
        }
    }
}
