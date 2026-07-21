package com.pos.ui;

import com.pos.api.dto.PosUserLoginResponse;
import com.pos.model.EmployeeShift;
import com.pos.service.EmployeeShiftService;
import com.pos.service.SettingsService;
import com.pos.service.UserAuthService;
import com.pos.ui.components.ToastNotification;
import com.pos.ui.components.TouchPasswordField;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.util.ReceiptPrintHelper;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Full-screen manual timesheet entry experience for the sales workflow.
 */
public class TimesheetScreen extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(TimesheetScreen.class);
    private static final String MANUAL_TIMESHEET_NOTE_PREFIX = "Manual timesheet entry from Timesheet screen";

    private final Runnable onBack;
    private final Consumer<PosUserLoginResponse.PosUserInfo> onEditTimesheetRequest;
    private final String preferredUserId;
    private final UserAuthService authService;
    private final EmployeeShiftService employeeShiftService;
    private final SettingsService settingsService;
    private final ObservableList<EmployeeShift> allUserShifts = FXCollections.observableArrayList();
    private final ObservableList<EmployeeShift> recentShifts = FXCollections.observableArrayList();
    private static final ObservableList<String> TIME_HOURS = FXCollections.observableArrayList(
            "00", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11",
            "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23");
    private static final ObservableList<String> TIME_MINUTES = FXCollections.observableArrayList(
            "00", "05", "10", "15", "20", "25", "30", "35", "40", "45", "50", "55");
    private static final ObservableList<String> PRINT_RANGE_OPTIONS = FXCollections.observableArrayList(
            "Today", "Tomorrow", "Last Week", "Last Two Weeks", "Custom Range");

    private ComboBox<PosUserLoginResponse.PosUserInfo> userSelector;
    private DatePicker timesheetDatePicker;
    private TimeSelector clockInSelector;
    private TimeSelector clockOutSelector;
    private CheckBox leaveClockOutOpen;
    private TouchPasswordField passwordField;
    private Label passwordHintLabel;
    private Label statusLabel;
    private Label selectedUserValue;
    private Label modeValue;
    private Label dateValue;
    private Button saveButton;
    private Button headerEditPageButton;
    private ComboBox<String> printRangeSelector;
    private DatePicker printStartDatePicker;
    private DatePicker printEndDatePicker;
    private Button viewReceiptButton;
    private Button printButton;
    public TimesheetScreen(Runnable onBack, Consumer<PosUserLoginResponse.PosUserInfo> onEditTimesheetRequest) {
        this(onBack, onEditTimesheetRequest, null);
    }

    public TimesheetScreen(
            Runnable onBack,
            Consumer<PosUserLoginResponse.PosUserInfo> onEditTimesheetRequest,
            String preferredUserId) {
        this.onBack = onBack;
        this.onEditTimesheetRequest = onEditTimesheetRequest;
        this.preferredUserId = preferredUserId;
        this.authService = UserAuthService.getInstance();
        this.employeeShiftService = EmployeeShiftService.getInstance();
        this.settingsService = SettingsService.getInstance();

        initializeUI();
        loadUsers();
    }

    private void initializeUI() {
        setPadding(new Insets(28));
        setStyle("-fx-background-color: linear-gradient(to bottom, #f8fafc 0%, #eef2ff 100%);");

        setTop(createHeader());
        setCenter(createContent());
    }

    private VBox createHeader() {
        VBox header = new VBox(16);
        header.setPadding(new Insets(0, 0, 24, 0));

        HBox titleRow = new HBox(16);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Button backButton = new Button("< Back to Sale");
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
        Label title = new Label("Timesheet Manager");
        title.setStyle("-fx-font-size: 32px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");
        Label subtitle = new Label("Create a new timesheet here, or open your edit page from the top-right.");
        subtitle.setStyle("-fx-font-size: 15px; -fx-text-fill: #64748b; -fx-font-weight: 500;");
        titleBox.getChildren().addAll(title, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        headerEditPageButton = new Button("Edit My Timesheet");
        headerEditPageButton.setStyle(
                "-fx-background-color: linear-gradient(to right, #dbeafe 0%, #bfdbfe 100%); " +
                        "-fx-text-fill: #1e3a8a; " +
                        "-fx-font-size: 15px; " +
                        "-fx-font-weight: 800; " +
                        "-fx-background-radius: 16; " +
                        "-fx-padding: 12 22; " +
                        "-fx-cursor: hand;");
        headerEditPageButton.setOnAction(e -> openEditPage());

        Button refreshButton = new Button("Refresh");
        refreshButton.setStyle(
                "-fx-background-color: #dbeafe; " +
                        "-fx-text-fill: #1d4ed8; " +
                        "-fx-font-size: 15px; " +
                        "-fx-font-weight: 700; " +
                        "-fx-background-radius: 14; " +
                        "-fx-padding: 12 18; " +
                        "-fx-cursor: hand;");
        refreshButton.setOnAction(e -> refreshRecentShifts());

        titleRow.getChildren().addAll(backButton, titleBox, spacer, headerEditPageButton, refreshButton);

        HBox statsRow = new HBox(16);
        selectedUserValue = createStatCard(statsRow, "Selected User");
        modeValue = createStatCard(statsRow, "Entry Mode");
        dateValue = createStatCard(statsRow, "Work Date");

        header.getChildren().addAll(titleRow, statsRow);
        return header;
    }

    private Label createStatCard(HBox parent, String labelText) {
        VBox card = new VBox(6);
        card.setPadding(new Insets(18));
        card.setPrefWidth(220);
        card.setStyle(
                "-fx-background-color: white; " +
                        "-fx-background-radius: 20; " +
                        "-fx-border-color: #e2e8f0; " +
                        "-fx-border-width: 1; " +
                        "-fx-border-radius: 20;");

        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #64748b;");

        Label value = new Label("--");
        value.setWrapText(true);
        value.setStyle("-fx-font-size: 22px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        card.getChildren().addAll(label, value);
        parent.getChildren().add(card);
        return value;
    }

    private HBox createContent() {
        HBox content = new HBox(22);
        content.setAlignment(Pos.TOP_LEFT);
        content.setFillHeight(true);

        VBox formCard = createFormCard();
        VBox historyCard = createHistoryCard();

        HBox.setHgrow(formCard, Priority.ALWAYS);
        HBox.setHgrow(historyCard, Priority.ALWAYS);
        formCard.setMaxWidth(Double.MAX_VALUE);
        historyCard.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(historyCard, Priority.ALWAYS);

        content.getChildren().addAll(formCard, historyCard);
        return content;
    }

    private VBox createFormCard() {
        VBox card = new VBox(12);
        card.setPadding(new Insets(20));
        card.setStyle(
                "-fx-background-color: white; " +
                        "-fx-background-radius: 24; " +
                        "-fx-border-color: #e2e8f0; " +
                        "-fx-border-width: 1; " +
                        "-fx-border-radius: 24;");

        Label sectionTitle = new Label("Create Timesheet");
        sectionTitle.setStyle("-fx-font-size: 22px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        Label sectionHint = new Label("Use 24-hour time. Use the top-right edit button to open your dedicated timesheet edit page.");
        sectionHint.setWrapText(true);
        sectionHint.setStyle("-fx-font-size: 14px; -fx-text-fill: #64748b;");

        userSelector = new ComboBox<>();
        userSelector.setMaxWidth(Double.MAX_VALUE);
        userSelector.setPromptText("Select user");
        userSelector.setConverter(new StringConverter<>() {
            @Override
            public String toString(PosUserLoginResponse.PosUserInfo user) {
                return formatPosUserLabel(user);
            }

            @Override
            public PosUserLoginResponse.PosUserInfo fromString(String string) {
                return null;
            }
        });
        userSelector.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(PosUserLoginResponse.PosUserInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatPosUserLabel(item));
            }
        });
        userSelector.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(PosUserLoginResponse.PosUserInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "Select user" : formatPosUserLabel(item));
            }
        });
        userSelector.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateFormState();
            refreshRecentShifts();
        });

        timesheetDatePicker = new DatePicker(LocalDate.now());
        timesheetDatePicker.setMaxWidth(Double.MAX_VALUE);
        timesheetDatePicker.valueProperty().addListener((obs, oldVal, newVal) -> updateSummaryCards());
        timesheetDatePicker.setPrefHeight(48);

        clockInSelector = createTimeSelector();
        clockOutSelector = createTimeSelector();

        leaveClockOutOpen = new CheckBox("Clock in only");
        leaveClockOutOpen.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #334155;");
        leaveClockOutOpen.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            clockOutSelector.setDisabled(isSelected);
            if (isSelected) {
                clockOutSelector.clear();
            }
            updateSummaryCards();
        });

        passwordField = TouchScreenComponents.createTouchOnlyPasswordField("PIN for selected user");
        passwordField.setPasswordFieldMaxWidth(Double.MAX_VALUE);
        passwordField.getPasswordField().setPrefHeight(48);
        passwordField.getPasswordField().setMinHeight(48);
        applyTimesheetPasswordFieldStyle(false);
        passwordField.getPasswordField().focusedProperty().addListener((obs, wasFocused, isFocused) ->
                applyTimesheetPasswordFieldStyle(isFocused));

        passwordHintLabel = new Label();
        passwordHintLabel.setWrapText(true);
        passwordHintLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");

        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #0f766e;");

        saveButton = new Button("Save Timesheet");
        saveButton.setMaxWidth(Double.MAX_VALUE);
        saveButton.setPrefHeight(48);
        saveButton.setStyle(
                "-fx-background-color: linear-gradient(to right, #0f766e 0%, #2563eb 100%); " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 15px; " +
                        "-fx-font-weight: 800; " +
                        "-fx-background-radius: 16;");
        saveButton.setOnAction(e -> submitTimesheet());

        GridPane formGrid = new GridPane();
        formGrid.setHgap(14);
        formGrid.setVgap(12);
        formGrid.setMaxWidth(Double.MAX_VALUE);

        javafx.scene.layout.ColumnConstraints col1 = new javafx.scene.layout.ColumnConstraints();
        col1.setPercentWidth(50);
        col1.setHgrow(Priority.ALWAYS);
        javafx.scene.layout.ColumnConstraints col2 = new javafx.scene.layout.ColumnConstraints();
        col2.setPercentWidth(50);
        col2.setHgrow(Priority.ALWAYS);
        formGrid.getColumnConstraints().addAll(col1, col2);

        VBox userField = labeledField("User", userSelector);
        VBox dateField = labeledField("Date", timesheetDatePicker);
        VBox clockInBox = labeledField("Clock In", clockInSelector.container());
        VBox clockOutBox = labeledField("Clock Out", clockOutSelector.container());
        VBox passwordBox = labeledField("PIN", passwordField);

        GridPane.setHgrow(userField, Priority.ALWAYS);
        GridPane.setHgrow(dateField, Priority.ALWAYS);
        GridPane.setHgrow(clockInBox, Priority.ALWAYS);
        GridPane.setHgrow(clockOutBox, Priority.ALWAYS);
        GridPane.setHgrow(passwordBox, Priority.ALWAYS);

        formGrid.add(userField, 0, 0);
        formGrid.add(dateField, 1, 0);
        formGrid.add(clockInBox, 0, 1);
        formGrid.add(clockOutBox, 1, 1);

        card.getChildren().addAll(
                sectionTitle,
                sectionHint,
                formGrid,
                leaveClockOutOpen,
                passwordBox,
                passwordHintLabel,
                saveButton,
                statusLabel);

        return card;
    }

    private VBox createHistoryCard() {
        VBox card = new VBox(14);
        card.setPadding(new Insets(24));
        card.setStyle(
                "-fx-background-color: white; " +
                        "-fx-background-radius: 24; " +
                        "-fx-border-color: #e2e8f0; " +
                        "-fx-border-width: 1; " +
                        "-fx-border-radius: 24;");

        Label sectionTitle = new Label("Recent Entries");
        sectionTitle.setStyle("-fx-font-size: 22px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        Label helper = new Label("Only manual timesheet entries created from this screen appear here. Use the edit page button to pick a date for this user, or use the date range section below to filter, preview, or print.");
        helper.setWrapText(true);
        helper.setStyle("-fx-font-size: 14px; -fx-text-fill: #64748b;");

        VBox printCard = createPrintCard();

        TableView<EmployeeShift> table = new TableView<>(recentShifts);
        table.setPlaceholder(new Label("Select a user to see recent timesheets."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<EmployeeShift, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(data -> new SimpleStringProperty(
                data.getValue().getClockInAt() != null
                        ? data.getValue().getClockInAt().toLocalDate().format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                        : "-"));

        TableColumn<EmployeeShift, String> inCol = new TableColumn<>("Clock In");
        inCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getClockInTime()));

        TableColumn<EmployeeShift, String> outCol = new TableColumn<>("Clock Out");
        outCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getClockOutTime()));

        TableColumn<EmployeeShift, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getStatus()));

        TableColumn<EmployeeShift, String> hoursCol = new TableColumn<>("Hours");
        hoursCol.setCellValueFactory(data -> {
            BigDecimal totalHours = data.getValue().getTotalHours();
            return new SimpleStringProperty(totalHours != null ? totalHours.toPlainString() : "-");
        });

        table.getColumns().addAll(dateCol, inCol, outCol, statusCol, hoursCol);
        card.getChildren().addAll(sectionTitle, helper, printCard, table);
        return card;
    }

    private VBox createPrintCard() {
        VBox card = new VBox(10);
        card.setPadding(new Insets(16));
        card.setStyle(
                "-fx-background-color: #f8fafc; " +
                        "-fx-background-radius: 18; " +
                        "-fx-border-color: #dbeafe; " +
                        "-fx-border-width: 1; " +
                        "-fx-border-radius: 18;");

        Label title = new Label("Timesheet Range");
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        Label hint = new Label("Use one date range to filter the manual-entry list, preview the receipt, or print it.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");

        printRangeSelector = new ComboBox<>(PRINT_RANGE_OPTIONS);
        printRangeSelector.setMaxWidth(Double.MAX_VALUE);
        printRangeSelector.setValue("Today");
        printRangeSelector.setPrefHeight(44);
        printRangeSelector.valueProperty().addListener((obs, oldVal, newVal) -> {
            updatePrintRangeControls();
            applyHistoryFilter();
        });

        printStartDatePicker = new DatePicker(LocalDate.now());
        printStartDatePicker.setMaxWidth(Double.MAX_VALUE);
        printStartDatePicker.setPrefHeight(44);
        printStartDatePicker.valueProperty().addListener((obs, oldVal, newVal) -> {
            if ("Custom Range".equals(printRangeSelector.getValue())) {
                applyHistoryFilter();
            }
        });

        printEndDatePicker = new DatePicker(LocalDate.now());
        printEndDatePicker.setMaxWidth(Double.MAX_VALUE);
        printEndDatePicker.setPrefHeight(44);
        printEndDatePicker.valueProperty().addListener((obs, oldVal, newVal) -> {
            if ("Custom Range".equals(printRangeSelector.getValue())) {
                applyHistoryFilter();
            }
        });

        HBox datesRow = new HBox(10,
                labeledField("From", printStartDatePicker),
                labeledField("To", printEndDatePicker));
        HBox.setHgrow(datesRow.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(datesRow.getChildren().get(1), Priority.ALWAYS);

        viewReceiptButton = new Button("View Receipt");
        viewReceiptButton.setMaxWidth(Double.MAX_VALUE);
        viewReceiptButton.setPrefHeight(44);
        viewReceiptButton.setStyle(
                "-fx-background-color: #e0f2fe; " +
                        "-fx-text-fill: #0f172a; " +
                        "-fx-font-size: 14px; " +
                        "-fx-font-weight: 800; " +
                        "-fx-background-radius: 14;");
        viewReceiptButton.setOnAction(e -> viewTimesheetRangeReceipt());

        printButton = new Button("Print Range Receipt");
        printButton.setMaxWidth(Double.MAX_VALUE);
        printButton.setPrefHeight(44);
        printButton.setStyle(
                "-fx-background-color: linear-gradient(to right, #1d4ed8 0%, #0f766e 100%); " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 14px; " +
                        "-fx-font-weight: 800; " +
                        "-fx-background-radius: 14;");
        printButton.setOnAction(e -> printTimesheetRangeReceipt());

        HBox actionRow = new HBox(10, viewReceiptButton, printButton);
        HBox.setHgrow(viewReceiptButton, Priority.ALWAYS);
        HBox.setHgrow(printButton, Priority.ALWAYS);

        card.getChildren().addAll(
                title,
                hint,
                labeledField("Range", printRangeSelector),
                datesRow,
                actionRow);
        updatePrintRangeControls();
        return card;
    }

    private VBox labeledField(String labelText, javafx.scene.Node field) {
        VBox wrapper = new VBox(8);
        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #334155;");
        wrapper.getChildren().addAll(label, field);
        return wrapper;
    }

    private void loadUsers() {
        List<PosUserLoginResponse.PosUserInfo> users = authService.getAllPosUsers();
        userSelector.setItems(FXCollections.observableArrayList(users));

        String targetUserId = preferredUserId != null && !preferredUserId.isBlank()
                ? preferredUserId
                : authService.getCurrentPosUserId();
        if (targetUserId != null) {
            users.stream()
                    .filter(user -> targetUserId.equals(user.id))
                    .findFirst()
                    .ifPresent(userSelector::setValue);
        }
        if (userSelector.getValue() == null && !users.isEmpty()) {
            userSelector.setValue(users.get(0));
        }

        updateFormState();
        refreshRecentShifts();
    }

    private void updateFormState() {
        PosUserLoginResponse.PosUserInfo selectedUser = userSelector.getValue();
        String currentPosUserId = authService.getCurrentPosUserId();
        boolean requiresPassword = selectedUser != null
                && (currentPosUserId == null || !currentPosUserId.equals(selectedUser.id));

        passwordField.setVisible(requiresPassword);
        passwordField.setManaged(requiresPassword);
        passwordHintLabel.setText(requiresPassword
                ? "Enter the selected user's PIN before saving."
                : "You can save your own timesheet without entering the PIN again.");
        if (viewReceiptButton != null) {
            viewReceiptButton.setDisable(selectedUser == null);
        }
        if (printButton != null) {
            printButton.setDisable(selectedUser == null);
        }
        if (headerEditPageButton != null) {
            headerEditPageButton.setDisable(resolveCurrentPosUser() == null);
        }
        updateSummaryCards();
    }

    private void updateSummaryCards() {
        PosUserLoginResponse.PosUserInfo selectedUser = userSelector.getValue();
        selectedUserValue.setText(selectedUser != null ? formatPosUserLabel(selectedUser) : "--");
        modeValue.setText(leaveClockOutOpen.isSelected() ? "Clock In Only" : "Clock In + Out");
        dateValue.setText(timesheetDatePicker.getValue() != null
                ? timesheetDatePicker.getValue().format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                : "--");
    }

    private void refreshRecentShifts() {
        PosUserLoginResponse.PosUserInfo selectedUser = userSelector.getValue();
        allUserShifts.clear();
        recentShifts.clear();
        if (selectedUser == null || selectedUser.id == null || selectedUser.id.isBlank()) {
            return;
        }

        new Thread(() -> {
            try {
                List<EmployeeShift> shifts = employeeShiftService.getAllShiftsForEmployee(selectedUser.id)
                        .stream()
                        .filter(shift -> shift.getClockInAt() != null)
                        .filter(this::isManualTimesheetEntry)
                        .sorted(Comparator.comparing(EmployeeShift::getClockInAt).reversed())
                        .collect(Collectors.toList());
                Platform.runLater(() -> {
                    allUserShifts.setAll(shifts);
                    applyHistoryFilter();
                });
            } catch (Exception e) {
                logger.error("Failed to load recent shifts for {}", selectedUser.id, e);
            }
        }).start();
    }

    private void applyHistoryFilter() {
        DateRange range = resolvePrintRange();
        LocalDate startDate = range.startDate();
        LocalDate endDate = range.endDate();

        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            setError("Filter end date cannot be earlier than the start date.");
            return;
        }

        List<EmployeeShift> filtered = allUserShifts.stream()
                .filter(shift -> isShiftWithinFilter(shift, startDate, endDate))
                .collect(Collectors.toList());
        recentShifts.setAll(filtered);

        if (allUserShifts.isEmpty()) {
            setNeutral("No timesheets found for the selected user.");
        } else if (filtered.isEmpty()) {
            setNeutral("No timesheets found for the selected filter.");
        } else {
            setNeutral("Timesheet filter applied.");
        }
    }

    private boolean isShiftWithinFilter(EmployeeShift shift, LocalDate startDate, LocalDate endDate) {
        if (shift.getClockInAt() == null) {
            return false;
        }

        LocalDate shiftDate = shift.getClockInAt().toLocalDate();
        if (startDate != null && shiftDate.isBefore(startDate)) {
            return false;
        }
        if (endDate != null && shiftDate.isAfter(endDate)) {
            return false;
        }
        return true;
    }

    private boolean isManualTimesheetEntry(EmployeeShift shift) {
        String notes = shift.getNotes();
        return notes != null && notes.startsWith(MANUAL_TIMESHEET_NOTE_PREFIX);
    }

    private void openEditPage() {
        PosUserLoginResponse.PosUserInfo currentUser = resolveCurrentPosUser();
        if (currentUser == null) {
            setError("Current user was not found for timesheet editing.");
            return;
        }
        if (onEditTimesheetRequest == null) {
            setError("Timesheet edit page is not available.");
            return;
        }

        onEditTimesheetRequest.accept(currentUser);
    }

    private PosUserLoginResponse.PosUserInfo resolveCurrentPosUser() {
        PosUserLoginResponse.PosUserInfo currentUser = authService.getCurrentPosUser();
        if (currentUser != null && currentUser.id != null && !currentUser.id.isBlank()) {
            return currentUser;
        }

        String currentPosUserId = authService.getCurrentPosUserId();
        if (currentPosUserId == null || currentPosUserId.isBlank()) {
            return null;
        }

        return authService.getAllPosUsers().stream()
                .filter(user -> currentPosUserId.equals(user.id))
                .findFirst()
                .orElse(null);
    }

    private void submitTimesheet() {
        PosUserLoginResponse.PosUserInfo selectedUser = userSelector.getValue();
        if (selectedUser == null) {
            setError("Select a user first.");
            return;
        }
        if (timesheetDatePicker.getValue() == null) {
            setError("Pick a work date.");
            return;
        }

        final LocalDateTime clockInAt;
        final LocalDateTime clockOutAt;
        try {
            LocalDate workDate = timesheetDatePicker.getValue();
            LocalTime clockInTime = clockInSelector.getTime("Clock in");
            clockInAt = LocalDateTime.of(workDate, clockInTime);
            clockOutAt = leaveClockOutOpen.isSelected()
                    ? null
                    : resolveClockOutDateTime(workDate, clockInTime, clockOutSelector.getTime("Clock out"));
        } catch (IllegalArgumentException e) {
            setError(e.getMessage());
            return;
        }

        String currentPosUserId = authService.getCurrentPosUserId();
        boolean requiresPassword = currentPosUserId == null || !currentPosUserId.equals(selectedUser.id);
        if (requiresPassword && (passwordField.getText() == null || passwordField.getText().isBlank())) {
            setError("Enter the selected user's PIN.");
            return;
        }

        saveButton.setDisable(true);
        setNeutral("Saving timesheet...");

        new Thread(() -> {
            try {
                if (requiresPassword && !authService.verifyPosUserCredentials(selectedUser.id, passwordField.getText())) {
                    throw new IllegalArgumentException("Incorrect PIN for the selected user.");
                }

                String employeeName = selectedUser.fullName != null && !selectedUser.fullName.isBlank()
                        ? selectedUser.fullName
                        : selectedUser.username;
                String actorName = authService.getCurrentUserName();
                String note = actorName != null && !actorName.isBlank()
                        ? "Manual timesheet entry from Timesheet screen by " + actorName
                        : "Manual timesheet entry from Timesheet screen";

                employeeShiftService.createManualShift(
                        selectedUser.id,
                        employeeName,
                        clockInAt,
                        clockOutAt,
                        note);

                Platform.runLater(() -> {
                    clockInSelector.clear();
                    clockOutSelector.clear();
                    passwordField.clear();
                    leaveClockOutOpen.setSelected(false);
                    setSuccess(clockOutAt == null
                            ? "Clock-in saved. Clock-out can be added later."
                            : "Timesheet saved successfully.");
                    saveButton.setDisable(false);
                    refreshRecentShifts();
                    ToastNotification.showSuccess("Timesheet saved successfully.", getSceneWindow());
                });
            } catch (Exception e) {
                logger.error("Failed to save manual timesheet", e);
                Platform.runLater(() -> {
                    setError(e.getMessage() != null ? e.getMessage() : "Failed to save timesheet.");
                    saveButton.setDisable(false);
                    ToastNotification.showError(
                            e.getMessage() != null ? e.getMessage() : "Failed to save timesheet.",
                            getSceneWindow());
                });
            }
        }).start();
    }

    private LocalDateTime resolveClockOutDateTime(LocalDate workDate, LocalTime clockInTime, LocalTime clockOutTime) {
        LocalDateTime clockInAt = LocalDateTime.of(workDate, clockInTime);
        LocalDateTime clockOutAt = LocalDateTime.of(workDate, clockOutTime);

        // Only roll to the next day for true overnight selections such as midnight or morning hours.
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

    private void applyTimesheetPasswordFieldStyle(boolean focused) {
        passwordField.getPasswordField().setStyle(
                "-fx-alignment: center-left; " +
                        "-fx-background-radius: 14; " +
                        "-fx-border-radius: 14; " +
                        "-fx-border-color: " + (focused ? "#667eea" : "#e0e7ef") + "; " +
                        "-fx-border-width: " + (focused ? "3" : "2") + "; " +
                        "-fx-background-color: " + (focused ? "white" : "#fafafa") + "; " +
                        "-fx-padding: 10px 16px; " +
                        "-fx-font-size: 16px; " +
                        "-fx-letter-spacing: 0px; " +
                        (focused ? "-fx-effect: dropshadow(gaussian, rgba(102,126,234,0.2), 10, 0, 0, 2);" : ""));
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

    private void updatePrintRangeControls() {
        if (printRangeSelector == null || printStartDatePicker == null || printEndDatePicker == null) {
            return;
        }

        String selectedRange = printRangeSelector.getValue();
        boolean customRange = "Custom Range".equals(selectedRange);
        printStartDatePicker.setDisable(!customRange);
        printEndDatePicker.setDisable(!customRange);

        if (!customRange) {
            DateRange range = resolvePrintRange();
            printStartDatePicker.setValue(range.startDate());
            printEndDatePicker.setValue(range.endDate());
        }
    }

    private DateRange resolvePrintRange() {
        LocalDate today = LocalDate.now();
        String selectedRange = printRangeSelector != null ? printRangeSelector.getValue() : "Today";
        if ("Tomorrow".equals(selectedRange)) {
            LocalDate tomorrow = today.plusDays(1);
            return new DateRange(tomorrow, tomorrow, "Tomorrow");
        }
        if ("Last Week".equals(selectedRange)) {
            return new DateRange(today.minusDays(6), today, "Last Week");
        }
        if ("Last Two Weeks".equals(selectedRange)) {
            return new DateRange(today.minusDays(13), today, "Last Two Weeks");
        }
        if ("Custom Range".equals(selectedRange)) {
            LocalDate start = printStartDatePicker.getValue();
            LocalDate end = printEndDatePicker.getValue();
            return new DateRange(start, end, "Custom Range");
        }
        return new DateRange(today, today, "Today");
    }

    private void printTimesheetRangeReceipt() {
        PosUserLoginResponse.PosUserInfo selectedUser = userSelector.getValue();
        if (selectedUser == null) {
            setError("Select a user before printing.");
            return;
        }

        DateRange range = resolvePrintRange();
        if (range.startDate() == null || range.endDate() == null) {
            setError("Choose both start and end dates for the print range.");
            return;
        }
        if (range.endDate().isBefore(range.startDate())) {
            setError("Print end date cannot be earlier than the start date.");
            return;
        }

        setPrintActionsDisabled(true);
        setNeutral("Preparing timesheet print...");

        new Thread(() -> {
            try {
                String receiptText = buildTimesheetRangeReceipt(selectedUser, range);
                Platform.runLater(() -> {
                    ReceiptPrintHelper.printReceiptConditionally(
                            receiptText,
                            null,
                            getSceneWindow(),
                            settingsService);
                    setSuccess("Timesheet range sent to the receipt printer.");
                    setPrintActionsDisabled(false);
                    ToastNotification.showSuccess("Timesheet range printed.", getSceneWindow());
                });
            } catch (Exception e) {
                logger.error("Failed to print timesheet range", e);
                Platform.runLater(() -> {
                    setError(e.getMessage() != null ? e.getMessage() : "Failed to print timesheet range.");
                    setPrintActionsDisabled(false);
                    ToastNotification.showError(
                            e.getMessage() != null ? e.getMessage() : "Failed to print timesheet range.",
                            getSceneWindow());
                });
            }
        }).start();
    }

    private void viewTimesheetRangeReceipt() {
        PosUserLoginResponse.PosUserInfo selectedUser = userSelector.getValue();
        if (selectedUser == null) {
            setError("Select a user before viewing the receipt.");
            return;
        }

        DateRange range = resolvePrintRange();
        if (range.startDate() == null || range.endDate() == null) {
            setError("Choose both start and end dates for the receipt view.");
            return;
        }
        if (range.endDate().isBefore(range.startDate())) {
            setError("Receipt end date cannot be earlier than the start date.");
            return;
        }

        setPrintActionsDisabled(true);
        setNeutral("Loading receipt preview...");

        new Thread(() -> {
            try {
                String receiptText = buildTimesheetRangeReceipt(selectedUser, range);
                Platform.runLater(() -> {
                    ReceiptPrintHelper.showReceiptPreview(
                            receiptText,
                            getSceneWindow(),
                            "TIMESHEET RANGE",
                            "Timesheet Range Preview");
                    setSuccess("Receipt preview opened in a new window.");
                    setPrintActionsDisabled(false);
                });
            } catch (Exception e) {
                logger.error("Failed to build timesheet receipt preview", e);
                Platform.runLater(() -> {
                    setError(e.getMessage() != null ? e.getMessage() : "Failed to load receipt preview.");
                    setPrintActionsDisabled(false);
                });
            }
        }).start();
    }

    private String buildTimesheetRangeReceipt(PosUserLoginResponse.PosUserInfo selectedUser, DateRange range) throws Exception {
        List<EmployeeShift> shifts = employeeShiftService.getAllShiftsForEmployee(selectedUser.id)
                .stream()
                .filter(shift -> shift.getClockInAt() != null)
                .filter(this::isManualTimesheetEntry)
                .filter(shift -> {
                    LocalDate shiftDate = shift.getClockInAt().toLocalDate();
                    return !shiftDate.isBefore(range.startDate()) && !shiftDate.isAfter(range.endDate());
                })
                .sorted(Comparator.comparing(EmployeeShift::getClockInAt))
                .collect(Collectors.toList());
        return generateTimesheetRangeReceiptText(selectedUser, range, shifts);
    }

    private void setPrintActionsDisabled(boolean disabled) {
        if (viewReceiptButton != null) {
            viewReceiptButton.setDisable(disabled);
        }
        if (printButton != null) {
            printButton.setDisable(disabled);
        }
    }

    private String generateTimesheetRangeReceiptText(
            PosUserLoginResponse.PosUserInfo selectedUser,
            DateRange range,
            List<EmployeeShift> shifts) {
        StringBuilder sb = new StringBuilder();
        String storeName = settingsService.getStoreName();
        String header = settingsService.getReceiptHeader();
        String footer = settingsService.getReceiptFooter();
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("h:mm a");
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy h:mm a");
        String userLabel = formatPosUserLabel(selectedUser);
        BigDecimal totalHours = shifts.stream()
                .map(this::getPrintableShiftHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long openEntries = shifts.stream().filter(shift -> shift.getClockOutAt() == null).count();

        sb.append(centerText(storeName, 42)).append("\n");
        if (header != null && !header.isBlank()) {
            sb.append(centerText(header, 42)).append("\n");
        }
        sb.append("-".repeat(42)).append("\n");
        sb.append(centerText("TIMESHEET RANGE", 42)).append("\n");
        sb.append("-".repeat(42)).append("\n");

        sb.append("User: ").append(userLabel.isBlank() ? "N/A" : userLabel).append("\n");
        sb.append("User ID: ").append(selectedUser.id != null ? selectedUser.id : "N/A").append("\n");
        sb.append("Range: ").append(range.label()).append("\n");
        sb.append("From: ").append(range.startDate().format(dateFormatter)).append("\n");
        sb.append("To: ").append(range.endDate().format(dateFormatter)).append("\n");
        sb.append("Entries: ").append(shifts.size()).append("\n");
        sb.append("Open: ").append(openEntries).append("\n");
        sb.append("Hours: ").append(totalHours.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()).append("\n");
        sb.append("-".repeat(42)).append("\n");

        if (shifts.isEmpty()) {
            sb.append("No timesheet entries in selected range.\n");
        } else {
            for (EmployeeShift shift : shifts) {
                sb.append(shift.getClockInAt().toLocalDate().format(dateFormatter)).append("\n");
                sb.append("In: ")
                        .append(shift.getClockInAt().format(timeFormatter))
                        .append("  Out: ")
                        .append(shift.getClockOutAt() != null ? shift.getClockOutAt().format(timeFormatter) : "OPEN")
                        .append("\n");
                sb.append("Status: ")
                        .append(shift.getStatus() != null ? shift.getStatus() : "N/A")
                        .append("  Hours: ")
                        .append(getPrintableShiftHours(shift).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
                        .append("\n");
                sb.append("-".repeat(42)).append("\n");
            }
        }

        sb.append("Printed: ").append(LocalDateTime.now().format(dateTimeFormatter)).append("\n");
        if (footer != null && !footer.isBlank()) {
            sb.append("-".repeat(42)).append("\n");
            sb.append(centerText(footer, 42)).append("\n");
            sb.append("-".repeat(42)).append("\n");
        }

        return sb.toString();
    }

    private BigDecimal getPrintableShiftHours(EmployeeShift shift) {
        if (shift.getTotalHours() != null) {
            return shift.getTotalHours();
        }
        if (shift.getClockInAt() != null && shift.getClockOutAt() != null) {
            return shift.calculateTotalHours();
        }
        return BigDecimal.ZERO;
    }

    private String buildTimeFrameLabel(EmployeeShift shift, DateTimeFormatter timeFormatter) {
        if (shift.getClockInAt() == null) {
            return "N/A";
        }
        String start = shift.getClockInAt().format(timeFormatter);
        String end = shift.getClockOutAt() != null ? shift.getClockOutAt().format(timeFormatter) : "OPEN";
        return start + " - " + end;
    }

    private String centerText(String text, int width) {
        if (text == null || text.length() >= width) {
            return text != null ? text : "";
        }
        int padding = (width - text.length()) / 2;
        return " ".repeat(Math.max(0, padding)) + text;
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
        combo.setStyle(
                "-fx-background-radius: 14; " +
                        "-fx-font-size: 15px; " +
                        "-fx-font-weight: 700;");
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

            int hour = Integer.parseInt(hourText);
            int minute = Integer.parseInt(minuteText);
            return LocalTime.of(hour, minute);
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
            int roundedMinute = (time.getMinute() / 5) * 5;
            minuteCombo.setValue(String.format("%02d", roundedMinute));
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

    private record DateRange(LocalDate startDate, LocalDate endDate, String label) {
    }
}
