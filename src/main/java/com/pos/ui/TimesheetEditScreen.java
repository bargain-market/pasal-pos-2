package com.pos.ui;

import com.pos.api.dto.PosUserLoginResponse;
import com.pos.model.EmployeeShift;
import com.pos.service.EmployeeShiftService;
import com.pos.service.UserAuthService;
import com.pos.ui.components.ToastNotification;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class TimesheetEditScreen extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(TimesheetEditScreen.class);
    private static final String MANUAL_TIMESHEET_NOTE_PREFIX = "Manual timesheet entry from Timesheet screen";
    private static final ObservableList<String> TIME_HOURS = FXCollections.observableArrayList(
            "00", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11",
            "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23");
    private static final ObservableList<String> TIME_MINUTES = FXCollections.observableArrayList(
            "00", "05", "10", "15", "20", "25", "30", "35", "40", "45", "50", "55");

    private final Runnable onBack;
    private final PosUserLoginResponse.PosUserInfo selectedUser;
    private final UserAuthService authService;
    private final EmployeeShiftService employeeShiftService;
    private final ObservableList<EmployeeShift> shiftsForDate = FXCollections.observableArrayList();

    private DatePicker datePicker;
    private TableView<EmployeeShift> shiftsTable;
    private TimeSelector clockInSelector;
    private TimeSelector clockOutSelector;
    private CheckBox leaveClockOutOpen;
    private Label statusLabel;
    private Label selectedDateValue;
    private Label selectedEntryValue;
    private Button saveButton;
    private EmployeeShift editingShift;

    public TimesheetEditScreen(Runnable onBack, PosUserLoginResponse.PosUserInfo selectedUser) {
        this.onBack = onBack;
        this.selectedUser = selectedUser;
        this.authService = UserAuthService.getInstance();
        this.employeeShiftService = EmployeeShiftService.getInstance();

        initializeUI();
        loadShiftsForDate();
    }

    private void initializeUI() {
        setPadding(new Insets(28));
        setStyle("-fx-background-color: linear-gradient(to bottom, #f8fafc 0%, #e0f2fe 100%);");
        setTop(createHeader());
        setCenter(createContent());
    }

    private VBox createHeader() {
        VBox header = new VBox(16);
        header.setPadding(new Insets(0, 0, 24, 0));

        HBox titleRow = new HBox(16);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Button backButton = new Button("< Back to Timesheet");
        backButton.setStyle(
                "-fx-background-color: #e2e8f0; " +
                        "-fx-text-fill: #334155; " +
                        "-fx-font-size: 15px; " +
                        "-fx-font-weight: 700; " +
                        "-fx-background-radius: 14; " +
                        "-fx-padding: 12 18; " +
                        "-fx-cursor: hand;");
        backButton.setOnAction(e -> onBack.run());

        VBox titleBox = new VBox(4);
        Label title = new Label("Edit Timesheet");
        title.setStyle("-fx-font-size: 32px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");
        Label subtitle = new Label("Select a date to view and edit only your manual timesheet entries.");
        subtitle.setStyle("-fx-font-size: 15px; -fx-text-fill: #64748b; -fx-font-weight: 500;");
        titleBox.getChildren().addAll(title, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refreshButton = new Button("Refresh");
        refreshButton.setStyle(
                "-fx-background-color: #dbeafe; " +
                        "-fx-text-fill: #1d4ed8; " +
                        "-fx-font-size: 15px; " +
                        "-fx-font-weight: 700; " +
                        "-fx-background-radius: 14; " +
                        "-fx-padding: 12 18; " +
                        "-fx-cursor: hand;");
        refreshButton.setOnAction(e -> loadShiftsForDate());

        titleRow.getChildren().addAll(backButton, titleBox, spacer, refreshButton);

        HBox statsRow = new HBox(16);
        createStatCard(statsRow, "User", formatPosUserLabel(selectedUser));
        selectedDateValue = createStatCard(statsRow, "Selected Date", LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy")));
        selectedEntryValue = createStatCard(statsRow, "Selected Entry", "None");

        header.getChildren().addAll(titleRow, statsRow);
        return header;
    }

    private Label createStatCard(HBox parent, String labelText, String initialValue) {
        VBox card = new VBox(6);
        card.setPadding(new Insets(18));
        card.setPrefWidth(240);
        card.setStyle(
                "-fx-background-color: white; " +
                        "-fx-background-radius: 20; " +
                        "-fx-border-color: #e2e8f0; " +
                        "-fx-border-width: 1; " +
                        "-fx-border-radius: 20;");

        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #64748b;");

        Label value = new Label(initialValue);
        value.setWrapText(true);
        value.setStyle("-fx-font-size: 20px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        card.getChildren().addAll(label, value);
        parent.getChildren().add(card);
        return value;
    }

    private HBox createContent() {
        HBox content = new HBox(22);
        content.setAlignment(Pos.TOP_LEFT);

        VBox selectorCard = createSelectorCard();
        VBox formCard = createFormCard();

        HBox.setHgrow(selectorCard, Priority.ALWAYS);
        HBox.setHgrow(formCard, Priority.ALWAYS);
        VBox.setVgrow(selectorCard, Priority.ALWAYS);

        content.getChildren().addAll(selectorCard, formCard);
        return content;
    }

    private VBox createSelectorCard() {
        VBox card = new VBox(14);
        card.setPadding(new Insets(24));
        card.setStyle(
                "-fx-background-color: white; " +
                        "-fx-background-radius: 24; " +
                        "-fx-border-color: #e2e8f0; " +
                        "-fx-border-width: 1; " +
                        "-fx-border-radius: 24;");

        Label title = new Label("Pick A Date");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        Label hint = new Label("Choose the work date first. Manual entries for that employee and date will load below.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 14px; -fx-text-fill: #64748b;");

        datePicker = new DatePicker(LocalDate.now());
        datePicker.setMaxWidth(Double.MAX_VALUE);
        datePicker.setPrefHeight(48);
        datePicker.valueProperty().addListener((obs, oldVal, newVal) -> {
            selectedDateValue.setText(newVal != null
                    ? newVal.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                    : "--");
            loadShiftsForDate();
        });

        shiftsTable = new TableView<>(shiftsForDate);
        shiftsTable.setPlaceholder(new Label("No manual entries found for the selected date."));
        shiftsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(shiftsTable, Priority.ALWAYS);

        TableColumn<EmployeeShift, String> timeCol = new TableColumn<>("Time");
        timeCol.setCellValueFactory(data -> new SimpleStringProperty(buildTimeFrameLabel(data.getValue())));

        TableColumn<EmployeeShift, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getStatus()));

        TableColumn<EmployeeShift, String> hoursCol = new TableColumn<>("Hours");
        hoursCol.setCellValueFactory(data -> new SimpleStringProperty(
                data.getValue().getTotalHours() != null ? data.getValue().getTotalHours().toPlainString() : "-"));

        shiftsTable.getColumns().addAll(timeCol, statusCol, hoursCol);
        shiftsTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            editingShift = newVal;
            populateFormFromShift(newVal);
            selectedEntryValue.setText(newVal != null ? buildTimeFrameLabel(newVal) : "None");
            updateFormState();
        });

        card.getChildren().addAll(title, hint, labeledField("Work Date", datePicker), shiftsTable);
        return card;
    }

    private VBox createFormCard() {
        VBox card = new VBox(12);
        card.setPadding(new Insets(24));
        card.setStyle(
                "-fx-background-color: white; " +
                        "-fx-background-radius: 24; " +
                        "-fx-border-color: #e2e8f0; " +
                        "-fx-border-width: 1; " +
                        "-fx-border-radius: 24;");

        Label title = new Label("Edit Selected Entry");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        Label hint = new Label("Select one entry from the date list, update the time values, then save the change.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 14px; -fx-text-fill: #64748b;");

        clockInSelector = createTimeSelector();
        clockOutSelector = createTimeSelector();

        leaveClockOutOpen = new CheckBox("Clock in only");
        leaveClockOutOpen.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #334155;");
        leaveClockOutOpen.selectedProperty().addListener((obs, oldVal, newVal) -> {
            clockOutSelector.setDisabled(newVal);
            if (newVal) {
                clockOutSelector.clear();
            }
        });

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #334155;");

        saveButton = new Button("Update Timesheet");
        saveButton.setMaxWidth(Double.MAX_VALUE);
        saveButton.setPrefHeight(48);
        saveButton.setStyle(
                "-fx-background-color: linear-gradient(to right, #0f766e 0%, #2563eb 100%); " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 15px; " +
                        "-fx-font-weight: 800; " +
                        "-fx-background-radius: 16;");
        saveButton.setOnAction(e -> saveChanges());

        card.getChildren().addAll(
                title,
                hint,
                labeledField("Clock In", clockInSelector.container()),
                labeledField("Clock Out", clockOutSelector.container()),
                leaveClockOutOpen,
                saveButton,
                statusLabel);

        updateFormState();
        return card;
    }

    private VBox labeledField(String labelText, Node field) {
        VBox wrapper = new VBox(8);
        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #334155;");
        wrapper.getChildren().addAll(label, field);
        return wrapper;
    }

    private void loadShiftsForDate() {
        LocalDate selectedDate = datePicker != null ? datePicker.getValue() : LocalDate.now();
        if (selectedDate == null || selectedUser == null || selectedUser.id == null) {
            shiftsForDate.clear();
            editingShift = null;
            updateFormState();
            return;
        }

        setNeutral("Loading timesheets for " + selectedDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy")) + "...");

        new Thread(() -> {
            try {
                List<EmployeeShift> matchingShifts = employeeShiftService.getAllShiftsForEmployee(selectedUser.id)
                        .stream()
                        .filter(this::isManualTimesheetEntry)
                        .filter(shift -> shift.getClockInAt() != null && selectedDate.equals(shift.getClockInAt().toLocalDate()))
                        .sorted(Comparator.comparing(EmployeeShift::getClockInAt))
                        .collect(Collectors.toList());

                Platform.runLater(() -> {
                    shiftsForDate.setAll(matchingShifts);
                    editingShift = null;
                    shiftsTable.getSelectionModel().clearSelection();
                    clearForm();
                    updateFormState();
                    if (matchingShifts.isEmpty()) {
                        setNeutral("No manual timesheet entries found for that date.");
                    } else {
                        setNeutral("Select the entry you want to edit.");
                    }
                });
            } catch (Exception e) {
                logger.error("Failed to load timesheets for edit screen", e);
                Platform.runLater(() -> setError("Failed to load timesheets for the selected date."));
            }
        }).start();
    }

    private void populateFormFromShift(EmployeeShift shift) {
        if (shift == null || shift.getClockInAt() == null) {
            clearForm();
            return;
        }

        clockInSelector.setTime(shift.getClockInAt().toLocalTime());
        if (shift.getClockOutAt() != null) {
            leaveClockOutOpen.setSelected(false);
            clockOutSelector.setDisabled(false);
            clockOutSelector.setTime(shift.getClockOutAt().toLocalTime());
        } else {
            leaveClockOutOpen.setSelected(true);
            clockOutSelector.clear();
        }
    }

    private void clearForm() {
        clockInSelector.clear();
        clockOutSelector.clear();
        leaveClockOutOpen.setSelected(false);
        clockOutSelector.setDisabled(false);
        selectedEntryValue.setText("None");
    }

    private void updateFormState() {
        if (datePicker != null) {
            datePicker.setDisable(false);
        }
        if (shiftsTable != null) {
            shiftsTable.setDisable(false);
        }
        leaveClockOutOpen.setDisable(false);
        clockInSelector.setDisabled(false);
        clockOutSelector.setDisabled(leaveClockOutOpen.isSelected());
        saveButton.setDisable(editingShift == null);
    }

    private boolean isManualTimesheetEntry(EmployeeShift shift) {
        String notes = shift.getNotes();
        return notes != null && notes.startsWith(MANUAL_TIMESHEET_NOTE_PREFIX);
    }

    private void saveChanges() {
        String currentPosUserId = authService.getCurrentPosUserId();
        if (editingShift == null) {
            setError("Select a timesheet entry to edit.");
            return;
        }
        if (datePicker.getValue() == null) {
            setError("Select a work date.");
            return;
        }

        final LocalDateTime clockInAt;
        final LocalDateTime clockOutAt;
        try {
            LocalDate workDate = datePicker.getValue();
            LocalTime clockInTime = clockInSelector.getTime("Clock in");
            clockInAt = LocalDateTime.of(workDate, clockInTime);
            clockOutAt = leaveClockOutOpen.isSelected()
                    ? null
                    : resolveClockOutDateTime(workDate, clockInTime, clockOutSelector.getTime("Clock out"));
        } catch (IllegalArgumentException e) {
            setError(e.getMessage());
            return;
        }

        saveButton.setDisable(true);
        setNeutral("Updating timesheet...");
        String shiftId = editingShift.getId();

        new Thread(() -> {
            try {
                String employeeName = selectedUser.fullName != null && !selectedUser.fullName.isBlank()
                        ? selectedUser.fullName
                        : selectedUser.username;
                String actorName = authService.getCurrentUserName();
                String note = actorName != null && !actorName.isBlank()
                        ? MANUAL_TIMESHEET_NOTE_PREFIX + " edited by " + actorName
                        : MANUAL_TIMESHEET_NOTE_PREFIX;

                employeeShiftService.updateManualShift(
                        shiftId,
                        selectedUser.id,
                        employeeName,
                        clockInAt,
                        clockOutAt,
                        note);

                Platform.runLater(() -> {
                    setSuccess("Timesheet updated successfully.");
                    ToastNotification.showSuccess("Timesheet updated successfully.", getSceneWindow());
                    loadShiftsForDate();
                });
            } catch (Exception e) {
                logger.error("Failed to update manual timesheet", e);
                Platform.runLater(() -> {
                    setError(e.getMessage() != null ? e.getMessage() : "Failed to update timesheet.");
                    saveButton.setDisable(false);
                    ToastNotification.showError(
                            e.getMessage() != null ? e.getMessage() : "Failed to update timesheet.",
                            getSceneWindow());
                    updateFormState();
                });
            }
        }).start();
    }

    private LocalDateTime resolveClockOutDateTime(LocalDate workDate, LocalTime clockInTime, LocalTime clockOutTime) {
        LocalDateTime clockInAt = LocalDateTime.of(workDate, clockInTime);
        LocalDateTime clockOutAt = LocalDateTime.of(workDate, clockOutTime);

        if (clockOutAt.isBefore(clockInAt)) {
            if (clockOutTime.isBefore(LocalTime.NOON)) {
                clockOutAt = clockOutAt.plusDays(1);
            } else {
                throw new IllegalArgumentException(
                        "Clock out looks earlier than clock in. If the shift ended after midnight, use 00:00-11:55.");
            }
        }

        return clockOutAt;
    }

    private String formatPosUserLabel(PosUserLoginResponse.PosUserInfo user) {
        if (user == null) {
            return "";
        }

        String fullName = user.fullName != null && !user.fullName.isBlank() ? user.fullName : user.username;
        String username = user.username != null && !user.username.isBlank() ? user.username : "";
        return username.isBlank() || fullName.equalsIgnoreCase(username)
                ? fullName
                : fullName + " (" + username + ")";
    }

    private String buildTimeFrameLabel(EmployeeShift shift) {
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("h:mm a");
        if (shift == null || shift.getClockInAt() == null) {
            return "N/A";
        }
        String start = shift.getClockInAt().format(timeFormatter);
        String end = shift.getClockOutAt() != null ? shift.getClockOutAt().format(timeFormatter) : "OPEN";
        return start + " - " + end;
    }

    private void setSuccess(String message) {
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #0f766e;");
        statusLabel.setText(message);
    }

    private void setError(String message) {
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #b91c1c;");
        statusLabel.setText(message);
    }

    private void setNeutral(String message) {
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #334155;");
        statusLabel.setText(message);
    }

    private TimeSelector createTimeSelector() {
        ComboBox<String> hourCombo = createTimeCombo(TIME_HOURS, "Hour", "09");
        ComboBox<String> minuteCombo = createTimeCombo(TIME_MINUTES, "Min", "00");

        Label colonLabel = new Label(":");
        colonLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: #475569;");

        HBox box = new HBox(8, hourCombo, colonLabel, minuteCombo);
        box.setAlignment(Pos.CENTER_LEFT);
        return new TimeSelector(box, hourCombo, minuteCombo);
    }

    private ComboBox<String> createTimeCombo(ObservableList<String> options, String promptText, String defaultValue) {
        ComboBox<String> combo = new ComboBox<>(options);
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setPrefWidth(88);
        combo.setPromptText(promptText);
        combo.setValue(defaultValue);
        HBox.setHgrow(combo, Priority.ALWAYS);
        return combo;
    }

    private record TimeSelector(
            HBox container,
            ComboBox<String> hourCombo,
            ComboBox<String> minuteCombo) {

        private LocalTime getTime(String fieldName) {
            String hourText = hourCombo.getValue();
            String minuteText = minuteCombo.getValue();

            if (hourText == null || minuteText == null) {
                throw new IllegalArgumentException(fieldName + " time is required.");
            }

            return LocalTime.of(Integer.parseInt(hourText), Integer.parseInt(minuteText));
        }

        private void clear() {
            hourCombo.setValue("09");
            minuteCombo.setValue("00");
        }

        private void setTime(LocalTime time) {
            if (time == null) {
                clear();
                return;
            }

            hourCombo.setValue(String.format("%02d", time.getHour()));
            minuteCombo.setValue(String.format("%02d", (time.getMinute() / 5) * 5));
        }

        private void setDisabled(boolean disabled) {
            container.setDisable(disabled);
            hourCombo.setDisable(disabled);
            minuteCombo.setDisable(disabled);
        }
    }

    private javafx.stage.Window getSceneWindow() {
        return getScene() != null ? getScene().getWindow() : null;
    }
}
