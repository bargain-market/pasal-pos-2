package com.pos.ui;

import com.pos.service.UserAuthService;
import com.pos.service.StoreService;
import com.pos.service.EmployeeService;
import com.pos.service.EmployeeShiftService;
import com.pos.service.DeviceRegistrationService;
import com.pos.model.Employee;
import com.pos.api.ApiClient;
import com.pos.config.ConfigManager;
import com.pos.hardware.HardwareManager;
import com.pos.ui.components.ToastNotification;
import com.pos.sync.SyncManager;
import com.pos.sync.SyncResult;
import com.pos.util.ErrorHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * User login screen for POS system - Redesigned UI/UX
 * Features an always-visible numeric keypad for better touch interaction.
 */
public class LoginScreen extends StackPane {
    private static final Logger logger = LoggerFactory.getLogger(LoginScreen.class);

    private ComboBox<Employee> userComboBox;
    private PasswordField pinDisplayField;
    private Button loginButton;
    private Label statusLabel;
    private Label storeNameLabel;
    private Label dateLabel;

    private final UserAuthService authService;
    private final StoreService storeService;
    private final EmployeeService employeeService;
    private final Stage stage;

    private StringBuilder currentPin = new StringBuilder();

    public LoginScreen(Stage stage) {
        this.stage = stage;
        this.authService = UserAuthService.getInstance();
        this.storeService = StoreService.getInstance();
        this.employeeService = EmployeeService.getInstance();
        initializeUI();
    }

    private void initializeUI() {
        getStyleClass().add("login-root");

        // Main Container - HBox for Split Layout
        HBox mainContainer = new HBox();
        mainContainer.setAlignment(Pos.CENTER);

        // Left Panel - Branding
        VBox leftPanel = createLeftPanel();
        HBox.setHgrow(leftPanel, Priority.ALWAYS);
        // Bind left panel to take 40% of available width instead of using initial stage
        // size
        leftPanel.prefWidthProperty().bind(mainContainer.widthProperty().multiply(0.4));

        // Right Panel - Login Form
        VBox rightPanel = createRightPanel();
        HBox.setHgrow(rightPanel, Priority.ALWAYS);
        // Bind right panel to take 60% of available width instead of using initial
        // stage size
        rightPanel.prefWidthProperty().bind(mainContainer.widthProperty().multiply(0.6));

        mainContainer.getChildren().addAll(leftPanel, rightPanel);
        getChildren().add(mainContainer);

        // Load employees for dropdown
        loadEmployees();
    }

    private VBox createLeftPanel() {
        VBox leftPanel = new VBox(30);
        leftPanel.getStyleClass().add("login-left-panel");
        leftPanel.setAlignment(Pos.CENTER);
        leftPanel.setPadding(new Insets(60, 40, 60, 40));

        // Icon Container with modern styling
        VBox iconContainer = new VBox();
        iconContainer.setAlignment(Pos.CENTER);

        javafx.scene.image.ImageView logoView = new javafx.scene.image.ImageView();
        try {
            logoView.setImage(new javafx.scene.image.Image(
                    getClass().getResourceAsStream("/images/app_icon.png")));
            logoView.setFitHeight(100);
            logoView.setPreserveRatio(true);
        } catch (Exception e) {
            logger.warn("Failed to load logo image", e);
        }

        iconContainer.getChildren().add(logoView);

        // Welcome Text
        VBox textContainer = new VBox(12);
        textContainer.setAlignment(Pos.CENTER);
        Label welcomeLabel = new Label("Welcome Back");
        welcomeLabel.getStyleClass().add("login-welcome-text");
        welcomeLabel.setStyle("-fx-font-size: 36px; -fx-font-weight: bold; -fx-text-fill: white;");

        // Subtitle
        Label subtitleLabel = new Label("Sign in to start your shift");
        subtitleLabel.getStyleClass().add("login-subtitle-text");
        subtitleLabel.setStyle("-fx-font-size: 18px; -fx-text-fill: rgba(255,255,255,0.8);");
        textContainer.getChildren().addAll(welcomeLabel, subtitleLabel);

        // Date/Time Display with modern card style
        VBox dateContainer = new VBox(8);
        dateContainer.setAlignment(Pos.CENTER);
        dateContainer.getStyleClass().add("login-date-container");
        dateLabel = new Label();
        dateLabel.getStyleClass().add("login-date-label");
        dateLabel.setStyle("-fx-font-size: 16px; -fx-text-fill: white;");
        updateDateTime();

        // Update time every minute
        javafx.animation.Timeline clock = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.ZERO, e -> updateDateTime()),
                new javafx.animation.KeyFrame(javafx.util.Duration.minutes(1)));
        clock.setCycleCount(javafx.animation.Animation.INDEFINITE);
        clock.play();

        dateContainer.getChildren().add(dateLabel);
        leftPanel.getChildren().addAll(iconContainer, textContainer, dateContainer);

        // Inline style for left panel background if css is missing
        leftPanel.setStyle("-fx-background-color: linear-gradient(to bottom right, #2c3e50, #3498db);");

        return leftPanel;
    }

    private void updateDateTime() {
        LocalDateTime now = LocalDateTime.now();
        dateLabel.setText(now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy • h:mm a")));
    }

    private VBox createRightPanel() {
        VBox rightPanel = new VBox(20);
        rightPanel.getStyleClass().add("login-right-panel");
        rightPanel.setAlignment(Pos.CENTER);
        rightPanel.setPadding(new Insets(40));
        rightPanel.setStyle("-fx-background-color: #f5f7fa;");

        // Store Badge
        HBox storeBadgeContainer = new HBox(10);
        storeBadgeContainer.setAlignment(Pos.CENTER);
        storeNameLabel = new Label("Store: Loading...");
        storeNameLabel.getStyleClass().add("login-store-badge");
        storeNameLabel.setStyle(
                "-fx-background-color: #e1e8ed; -fx-padding: 8 16; -fx-background-radius: 20; -fx-text-fill: #555;");

        // Sync Button
        Button syncButton = new Button("↻ Sync");
        syncButton.getStyleClass().add("login-sync-button");
        syncButton.setStyle(
                "-fx-background-color: #3498db; -fx-text-fill: white; -fx-background-radius: 20; -fx-padding: 8 16; -fx-font-weight: bold; -fx-cursor: hand;");

        syncButton.setOnAction(e -> handleSync(syncButton));

        storeBadgeContainer.getChildren().addAll(storeNameLabel, syncButton);
        updateStoreName();
        refreshStoreNameInBackground();

        // Form Container
        VBox formCard = new VBox(20);
        formCard.getStyleClass().add("login-form-card");
        formCard.setMaxWidth(480);
        formCard.setAlignment(Pos.CENTER);
        formCard.setPadding(new Insets(30));
        formCard.setStyle(
                "-fx-background-color: white; -fx-background-radius: 10; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.1), 10, 0, 0, 0);");

        // Title
        Label formTitle = new Label("Sign In");
        formTitle.getStyleClass().add("login-form-title");
        formTitle.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: #333;");

        // User Selection
        VBox userSection = new VBox(8);
        Label userLabel = new Label("Select User");
        userLabel.getStyleClass().add("login-field-label");
        userLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 14px;");

        userComboBox = new ComboBox<>();
        userComboBox.setPromptText("Select a user");
        userComboBox.setMaxWidth(Double.MAX_VALUE);
        userComboBox.setPrefHeight(50);
        userComboBox.getStyleClass().add("login-combo-box");
        userComboBox.setStyle("-fx-font-size: 16px; -fx-background-radius: 8;");
        userComboBox.setConverter(new StringConverter<Employee>() {
            @Override
            public String toString(Employee employee) {
                return employee != null ? employee.getFullName() : "";
            }

            @Override
            public Employee fromString(String string) {
                return null;
            }
        });

        userSection.getChildren().addAll(userLabel, userComboBox);

        // PIN Display
        VBox pinSection = new VBox(8);
        Label pinLabel = new Label("Enter PIN");
        pinLabel.getStyleClass().add("login-field-label");
        pinLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 14px;");

        pinDisplayField = new PasswordField();
        pinDisplayField.setPromptText("Enter PIN");
        pinDisplayField.setEditable(false); // Prevent keyboard input
        pinDisplayField.setFocusTraversable(false);
        pinDisplayField.setAlignment(Pos.CENTER);
        pinDisplayField.setPrefHeight(50);
        pinDisplayField.getStyleClass().add("pin-display-field");
        pinDisplayField.setStyle(
                "-fx-font-size: 24px; -fx-alignment: center; -fx-background-radius: 8; -fx-border-color: #ddd; -fx-border-radius: 8;");

        pinSection.getChildren().addAll(pinLabel, pinDisplayField);

        // Keypad
        GridPane keypad = createKeypad();

        // Status Label
        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setAlignment(Pos.CENTER);
        statusLabel.getStyleClass().add("login-status-label");
        statusLabel.setStyle("-fx-text-fill: #e74c3c;");

        formCard.getChildren().addAll(formTitle, userSection, pinSection, keypad, statusLabel);
        rightPanel.getChildren().addAll(storeBadgeContainer, formCard);

        return rightPanel;
    }

    private GridPane createKeypad() {
        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(15);
        grid.setAlignment(Pos.CENTER);
        grid.setPadding(new Insets(20, 0, 0, 0));

        // 1-9
        for (int i = 1; i <= 9; i++) {
            String val = String.valueOf(i);
            Button btn = createKeypadButton(val);
            btn.setOnAction(e -> appendPin(val));
            grid.add(btn, (i - 1) % 3, (i - 1) / 3);
        }

        // Clear
        Button clearBtn = createKeypadButton("C");
        clearBtn.getStyleClass().add("keypad-button-clear");
        clearBtn.setStyle(
                "-fx-font-size: 20px; -fx-font-weight: bold; -fx-background-radius: 40; -fx-min-width: 70px; -fx-min-height: 70px; -fx-background-color: #ef5350; -fx-text-fill: white;");
        clearBtn.setOnAction(e -> clearPin());
        grid.add(clearBtn, 0, 3);

        // 0
        Button zeroBtn = createKeypadButton("0");
        zeroBtn.setOnAction(e -> appendPin("0"));
        grid.add(zeroBtn, 1, 3);

        // Enter/Login
        Button enterBtn = createKeypadButton("➜");
        enterBtn.getStyleClass().add("keypad-button-enter");
        enterBtn.setStyle(
                "-fx-font-size: 24px; -fx-font-weight: bold; -fx-background-radius: 40; -fx-min-width: 70px; -fx-min-height: 70px; -fx-background-color: #66bb6a; -fx-text-fill: white;");
        enterBtn.setOnAction(e -> handleLogin());
        loginButton = enterBtn;
        grid.add(enterBtn, 2, 3);

        return grid;
    }

    private Button createKeypadButton(String text) {
        Button btn = new Button(text);
        btn.setPrefSize(70, 70);
        btn.setStyle(
                "-fx-font-size: 24px; -fx-font-weight: bold; -fx-background-radius: 40; -fx-background-color: #f0f2f5; -fx-text-fill: #333;");
        btn.getStyleClass().add("keypad-button");

        // Hover effect
        btn.setOnMouseEntered(e -> {
            if (!text.equals("C") && !text.equals("➜"))
                btn.setStyle(
                        "-fx-font-size: 24px; -fx-font-weight: bold; -fx-background-radius: 40; -fx-background-color: #e1e4e8; -fx-text-fill: #333;");
        });
        btn.setOnMouseExited(e -> {
            if (!text.equals("C") && !text.equals("➜"))
                btn.setStyle(
                        "-fx-font-size: 24px; -fx-font-weight: bold; -fx-background-radius: 40; -fx-background-color: #f0f2f5; -fx-text-fill: #333;");
        });

        return btn;
    }

    private void appendPin(String digit) {
        if (currentPin.length() < 6) {
            currentPin.append(digit);
            updatePinDisplay();
            statusLabel.setText(""); // Clear error on typing
        }
    }

    private void clearPin() {
        currentPin.setLength(0);
        updatePinDisplay();
        statusLabel.setText("");
    }

    private void updatePinDisplay() {
        pinDisplayField.setText(currentPin.toString());
    }

    private void loadEmployees() {
        // Show loading state
        javafx.application.Platform.runLater(() -> {
            userComboBox.setPromptText("Loading users...");
            userComboBox.setDisable(true);
        });

        new Thread(() -> {
            try {
                // First attempt: try to load from local database
                List<Employee> employees = employeeService.getActiveEmployees();

                // If no employees found, try syncing from backend
                if (employees == null || employees.isEmpty()) {
                    logger.info("No employees found locally, attempting to sync from backend...");
                    try {
                        // Trigger user sync
                        com.pos.service.UserSyncService userSyncService = com.pos.service.UserSyncService.getInstance();
                        userSyncService.syncPosUsers();

                        // Wait a moment for sync to complete
                        Thread.sleep(500);

                        // Try loading again
                        employees = employeeService.getActiveEmployees();
                    } catch (Exception syncError) {
                        logger.warn("Failed to sync users from backend: {}", syncError.getMessage());
                    }
                }

                final List<Employee> finalEmployees = employees;
                javafx.application.Platform.runLater(() -> {
                    userComboBox.setDisable(false);
                    if (finalEmployees != null && !finalEmployees.isEmpty()) {
                        userComboBox.getItems().addAll(finalEmployees);
                        if (finalEmployees.size() == 1) {
                            userComboBox.getSelectionModel().selectFirst();
                        }
                        userComboBox.setPromptText("Select a user");
                        statusLabel.setText("");
                    } else {
                        userComboBox.setPromptText("No users available");
                        statusLabel.setText("No users found. Please check your connection and try again.");
                        statusLabel.setStyle("-fx-text-fill: #e74c3c;");
                        logger.warn("No employees found after sync attempt");
                    }
                });
            } catch (Exception e) {
                logger.error("Failed to load employees", e);
                javafx.application.Platform.runLater(() -> {
                    userComboBox.setDisable(false);
                    userComboBox.setPromptText("Error loading users");
                    statusLabel.setText("Failed to load users: " + e.getMessage());
                    statusLabel.setStyle("-fx-text-fill: #e74c3c;");
                });
            }
        }).start();
    }

    private void updateStoreName() {
        String storeName = storeService.getStoreName();
        if (storeName == null || storeName.isEmpty()) {
            storeName = "Retail Store";
        }
        storeNameLabel.setText(storeName);
    }

    public void refreshStoreName() {
        updateStoreName();
    }

    private void refreshStoreNameInBackground() {
        if (!DeviceRegistrationService.getInstance().isDeviceRegistered()) {
            return;
        }

        Thread storeRefreshThread = new Thread(() -> {
            try {
                int timeoutMs = ConfigManager.getInstance()
                        .getIntProperty("backend.api.timeout.startup", 4000);
                storeService.fetchStoreInfo(timeoutMs);
                javafx.application.Platform.runLater(this::refreshStoreName);
            } catch (ApiClient.ApiException e) {
                logger.debug("Store info refresh skipped: {}", e.getMessage());
            } catch (Exception e) {
                logger.debug("Store info refresh failed", e);
            }
        }, "LoginStoreInfoRefresh");
        storeRefreshThread.setDaemon(true);
        storeRefreshThread.start();
    }

    private void handleLogin() {
        Employee selected = userComboBox.getSelectionModel().getSelectedItem();
        String username = selected != null ? selected.getUsername() : "";
        String pin = currentPin.toString();

        if (username.isEmpty()) {
            ToastNotification.showWarning("Please select a user", stage);
            userComboBox.requestFocus();
            return;
        }

        if (pin.isEmpty()) {
            ToastNotification.showWarning("PIN is required", stage);
            return;
        }

        // Validate PIN format (4-6 digits)
        if (!pin.matches("\\d{4,6}")) {
            ToastNotification.showWarning("PIN must be 4-6 digits", stage);
            clearPin();
            return;
        }

        // Disable button during login
        loginButton.setDisable(true);
        statusLabel.setText("Logging in...");
        statusLabel.setStyle("-fx-text-fill: #3498db;");

        // Perform login in background thread
        final String finalUsername = username;
        new Thread(() -> {
            try {
                authService.loginPosUser(finalUsername, pin);

                // Clock in the employee (track shift start time)
                try {
                    EmployeeShiftService employeeShiftService = EmployeeShiftService.getInstance();
                    String employeeId = authService.getCurrentPosUserId();
                    String employeeName = authService.getCurrentUserName();
                    if (employeeId != null) {
                        employeeShiftService.clockIn(employeeId, employeeName);
                        logger.info("Employee clocked in: {} ({})", employeeName, employeeId);
                    }
                } catch (Exception clockInError) {
                    logger.warn("Failed to clock in employee (non-blocking): {}", clockInError.getMessage());
                    // Don't block login if clock-in fails
                }

                // Close any stale shifts from previous days before proceeding
                try {
                    com.pos.service.ShiftService shiftService = com.pos.service.ShiftService.getInstance();
                    int closedCount = shiftService.closeAllStaleShifts();
                    if (closedCount > 0) {
                        logger.info("Auto-closed {} stale shift(s) from previous days on login", closedCount);
                    }
                } catch (Exception staleShiftError) {
                    logger.warn("Failed to close stale shifts on login (non-blocking): {}", staleShiftError.getMessage());
                }

                // Check if login was offline
                String newToken = authService.getAccessToken();
                boolean isOfflineLogin = (newToken != null && newToken.startsWith("offline_token_"));

                // Show success on JavaFX thread
                javafx.application.Platform.runLater(() -> {
                    String successMessage = "Login successful! Loading POS system...";
                    if (isOfflineLogin) {
                        successMessage = "Login successful (Offline Mode)! Syncing with backend...";
                    }
                    ToastNotification.showSuccess(successMessage, stage);

                    // Proceed to main window
                    proceedToMainWindow();
                });
            } catch (ApiClient.ApiException e) {
                javafx.application.Platform.runLater(() -> {
                    ErrorHandler.handleErrorWithToast(e, "Login failed", stage);
                    loginButton.setDisable(false);
                    clearPin();
                    statusLabel.setText("Login failed. Please try again.");
                    statusLabel.setStyle("-fx-text-fill: #e74c3c;");
                });
            } catch (IllegalStateException e) {
                javafx.application.Platform.runLater(() -> {
                    ErrorHandler.handleErrorWithToast(e, "Login failed", stage);
                    loginButton.setDisable(false);
                    statusLabel.setText(e.getMessage());
                    statusLabel.setStyle("-fx-text-fill: #e74c3c;");
                });
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> {
                    ErrorHandler.handleErrorWithToast(e, "Login failed", stage);
                    loginButton.setDisable(false);
                    statusLabel.setText("An unexpected error occurred.");
                    statusLabel.setStyle("-fx-text-fill: #e74c3c;");
                });
            }
        }).start();
    }



    /**
     * Proceed to main window after login and session setup
     */
    private void proceedToMainWindow() {
        try {
            // Get current screen dimensions
            javafx.geometry.Rectangle2D screenBounds = javafx.stage.Screen.getPrimary().getVisualBounds();

            // Create main window
            MainWindow mainWindow = new MainWindow(HardwareManager.getInstance(), stage);

            // Create scene with explicit dimensions
            Scene mainScene = new Scene(mainWindow, screenBounds.getWidth(), screenBounds.getHeight());

            // Load stylesheet
            try {
                mainScene.getStylesheets().add(
                        getClass().getResource("/styles/application.css").toExternalForm());
            } catch (Exception e) {
                logger.warn("Stylesheet not found, using default styles", e);
            }

            // Set the new scene
            stage.setScene(mainScene);
            stage.setTitle("Pasal POS 2 - Point of Sale");

            // Ensure stage is maximized and visible
            stage.setMaximized(true);
            stage.show();
            stage.toFront();

            // Set fullscreen with a small delay (required for macOS)
            javafx.application.Platform.runLater(() -> {
                stage.setFullScreen(true);
                stage.toFront();
                stage.requestFocus();
                logger.info("Main window displayed successfully");

                // Refresh menu bar after a delay to ensure permissions are loaded from database
                new Thread(() -> {
                    try {
                        Thread.sleep(500); // Wait for permissions to be fully loaded
                        javafx.application.Platform.runLater(() -> {
                            mainWindow.refreshMenuBar();
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            });
        } catch (Exception e) {
            ErrorHandler.handleErrorWithToast(e, "Failed to load main window", stage);
            loginButton.setDisable(false);
        }
    }

    private void handleSync(Button syncButton) {
        syncButton.setDisable(true);
        syncButton.setText("↻ Syncing...");

        ToastNotification.showInfo("Starting sync...", stage);

        new Thread(() -> {
            try {
                // Perform sync
                SyncResult result = SyncManager.getInstance().performInboundSync();

                javafx.application.Platform.runLater(() -> {
                    if (result.isSuccess()) {
                        ToastNotification.showSuccess("Sync completed successfully!", stage);
                        // Reload employees to reflect any new users
                        loadEmployees();
                    } else {
                        ToastNotification.showError("Sync completed with warnings: " + result.getError(), stage);
                    }
                });
            } catch (Exception e) {
                logger.error("Manual sync failed", e);
                javafx.application.Platform.runLater(() -> {
                    ToastNotification.showError("Sync failed: " + e.getMessage(), stage);
                });
            } finally {
                javafx.application.Platform.runLater(() -> {
                    syncButton.setDisable(false);
                    syncButton.setText("↻ Sync");
                });
            }
        }).start();
    }
}
