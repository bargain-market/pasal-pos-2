package com.pos.ui;

import com.pos.service.DeviceRegistrationService;
import com.pos.api.dto.DeviceRegistrationResponse;
import com.pos.api.ApiClient;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Device registration screen for first-time setup - Modern fullscreen design
 */
public class DeviceRegistrationScreen extends StackPane {
    private static final Logger logger = LoggerFactory.getLogger(DeviceRegistrationScreen.class);

    private com.pos.ui.components.TouchTextField deviceNameField;
    private com.pos.ui.components.TouchTextField storeIdField;
    private com.pos.ui.components.TouchTextField registerNumberField;
    private com.pos.ui.components.TouchTextField locationField;
    private com.pos.ui.components.TouchTextField backendUrlField;
    private Button registerButton;
    private Label statusLabel;
    private DeviceRegistrationService registrationService;
    private Stage stage;

    public DeviceRegistrationScreen(Stage stage) {
        this.stage = stage;
        this.registrationService = DeviceRegistrationService.getInstance();
        initializeUI();
    }

    private void initializeUI() {
        // Set fullscreen background with gradient
        setStyle(
                "-fx-background-color: linear-gradient(from 0% 0% to 100% 100%, #1e3c72 0%, #2a5298 50%, #7e8ba3 100%);");

        // Main container
        VBox mainContainer = new VBox(30);
        mainContainer.setAlignment(Pos.CENTER);
        mainContainer.setPadding(new Insets(40));
        mainContainer.setMaxWidth(600);

        // Logo/Icon area
        VBox logoArea = new VBox(15);
        logoArea.setAlignment(Pos.CENTER);

        // Icon
        javafx.scene.image.ImageView iconView = new javafx.scene.image.ImageView();
        try {
            iconView.setImage(new javafx.scene.image.Image(
                    getClass().getResourceAsStream("/images/app_icon.png")));
            iconView.setFitHeight(100);
            iconView.setPreserveRatio(true);
        } catch (Exception e) {
            logger.warn("Failed to load logo image", e);
        }

        // Main title
        Label titleLabel = new Label("Device Registration");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 42));
        titleLabel.setStyle("-fx-text-fill: white; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 10, 0, 0, 2);");

        // Subtitle
        Label subtitleLabel = new Label("Register your POS device");
        subtitleLabel.setFont(Font.font("System", FontWeight.NORMAL, 18));
        subtitleLabel.setStyle("-fx-text-fill: rgba(255,255,255,0.9);");

        logoArea.getChildren().addAll(iconView, titleLabel, subtitleLabel);

        // Form card with modern styling
        VBox formBox = new VBox(20);
        formBox.setPadding(new Insets(40, 35, 40, 35));
        formBox.setStyle(
                "-fx-background-color: white; " +
                        "-fx-background-radius: 20; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 20, 0, 0, 5);");
        formBox.setMaxWidth(550);

        // Backend URL section
        VBox backendUrlSection = new VBox(8);
        Label backendUrlLabel = new Label("BACKEND API URL");
        backendUrlLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
        backendUrlLabel.setStyle("-fx-text-fill: #666;");
        backendUrlField = com.pos.ui.components.TouchScreenComponents
                .createTouchOnlyTextField("https://your-backend-url.com/api/v1");
        backendUrlField.setPromptText("https://your-backend-url.com/api/v1");
        backendUrlField.setText("http://localhost:3000/api/v1");
        backendUrlField.setKeyboardEnabled(true);
        backendUrlField.setPrefHeight(50);
        backendUrlField.setStyle(
                "-fx-font-size: 16px; " +
                        "-fx-padding: 12px 15px; " +
                        "-fx-background-radius: 8; " +
                        "-fx-border-radius: 8; " +
                        "-fx-border-color: #ddd; " +
                        "-fx-border-width: 2; " +
                        "-fx-background-color: #f8f9fa;");
        backendUrlSection.getChildren().addAll(backendUrlLabel, backendUrlField);

        // Device Name section
        VBox deviceNameSection = new VBox(8);
        Label deviceNameLabel = new Label("DEVICE NAME");
        deviceNameLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
        deviceNameLabel.setStyle("-fx-text-fill: #666;");
        deviceNameField = com.pos.ui.components.TouchScreenComponents
                .createTouchOnlyTextField("e.g., Register 1, Front Counter");
        deviceNameField.setKeyboardEnabled(true);
        deviceNameField.getTextField().setPrefHeight(50);
        deviceNameSection.getChildren().addAll(deviceNameLabel, deviceNameField);

        // Store ID section
        VBox storeIdSection = new VBox(8);
        Label storeIdLabel = new Label("STORE ID");
        storeIdLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
        storeIdLabel.setStyle("-fx-text-fill: #666;");
        storeIdField = com.pos.ui.components.TouchScreenComponents.createTouchOnlyTextField("Enter your store ID");
        storeIdField.setKeyboardEnabled(true);
        storeIdField.getTextField().setPrefHeight(50);
        storeIdSection.getChildren().addAll(storeIdLabel, storeIdField);

        // Register Number section (Optional)
        VBox registerNumberSection = new VBox(8);
        Label registerNumberLabel = new Label("REGISTER NUMBER (OPTIONAL)");
        registerNumberLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
        registerNumberLabel.setStyle("-fx-text-fill: #666;");
        registerNumberField = com.pos.ui.components.TouchScreenComponents.createTouchOnlyTextField("e.g., REG001");
        registerNumberField.setKeyboardEnabled(true);
        registerNumberField.getTextField().setPrefHeight(50);
        registerNumberSection.getChildren().addAll(registerNumberLabel, registerNumberField);

        // Location section (Optional)
        VBox locationSection = new VBox(8);
        Label locationLabel = new Label("LOCATION (OPTIONAL)");
        locationLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
        locationLabel.setStyle("-fx-text-fill: #666;");
        locationField = com.pos.ui.components.TouchScreenComponents
                .createTouchOnlyTextField("e.g., Front Counter, Back Office");
        locationField.setKeyboardEnabled(true);
        locationField.getTextField().setPrefHeight(50);
        locationSection.getChildren().addAll(locationLabel, locationField);

        // Register button - large and prominent
        registerButton = new Button("REGISTER DEVICE");
        registerButton.setPrefWidth(Double.MAX_VALUE);
        registerButton.setPrefHeight(55);
        registerButton.setFont(Font.font("System", FontWeight.BOLD, 18));
        registerButton.setStyle(
                "-fx-background-color: #4CAF50; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 10; " +
                        "-fx-cursor: hand; " +
                        "-fx-effect: dropshadow(gaussian, rgba(76,175,80,0.4), 10, 0, 0, 3);");
        registerButton.setOnMouseEntered(e -> registerButton.setStyle(
                "-fx-background-color: #45a049; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 10; " +
                        "-fx-cursor: hand; " +
                        "-fx-effect: dropshadow(gaussian, rgba(76,175,80,0.5), 12, 0, 0, 4);"));
        registerButton.setOnMouseExited(e -> registerButton.setStyle(
                "-fx-background-color: #4CAF50; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 10; " +
                        "-fx-cursor: hand; " +
                        "-fx-effect: dropshadow(gaussian, rgba(76,175,80,0.4), 10, 0, 0, 3);"));
        registerButton.setOnAction(e -> handleRegistration());

        // Status label with better styling
        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setAlignment(Pos.CENTER);
        statusLabel.setPrefHeight(60);
        statusLabel.setFont(Font.font("System", FontWeight.NORMAL, 13));
        statusLabel.setStyle("-fx-text-fill: #666; -fx-padding: 10 0 0 0;");

        // Add fields to form
        formBox.getChildren().addAll(
                backendUrlSection,
                deviceNameSection,
                storeIdSection,
                registerNumberSection,
                locationSection,
                registerButton,
                statusLabel);

        // Add to main layout
        mainContainer.getChildren().addAll(logoArea, formBox);

        // Center everything
        // Center everything
        // Wrap in ScrollPane for responsiveness
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        // Transparent background for ScrollPane and its viewport to show the root
        // gradient
        scrollPane.setStyle(
                "-fx-background: transparent; -fx-background-color: transparent; -fx-viewport-border: transparent;");
        scrollPane.setPannable(true);

        // Wrapper to center the content when it's smaller than the view
        StackPane contentWrapper = new StackPane(mainContainer);
        contentWrapper.setStyle("-fx-background-color: transparent;");
        contentWrapper.setAlignment(Pos.CENTER);

        // Ensure the wrapper fills the scrollpane to allow centering
        contentWrapper.minHeightProperty().bind(scrollPane.heightProperty());

        scrollPane.setContent(contentWrapper);

        getChildren().add(scrollPane);
    }

    private void handleRegistration() {
        // Validate fields
        if (deviceNameField.getText().trim().isEmpty()) {
            showError("Device name is required");
            return;
        }

        if (storeIdField.getText().trim().isEmpty()) {
            showError("Store ID is required");
            return;
        }

        String backendUrl = backendUrlField.getText().trim();
        if (backendUrl.isEmpty()) {
            showError("Backend URL is required");
            return;
        }

        // Set backend URL
        ApiClient.getInstance().setBaseUrl(backendUrl);

        // Disable button during registration
        registerButton.setDisable(true);
        statusLabel.setText("Registering device...");
        statusLabel.setStyle("-fx-text-fill: #2196F3;");

        // Perform registration in background thread
        new Thread(() -> {
            try {
                DeviceRegistrationResponse response = registrationService.registerDevice(
                        deviceNameField.getText().trim(),
                        storeIdField.getText().trim(),
                        registerNumberField.getText().trim().isEmpty() ? null : registerNumberField.getText().trim(),
                        locationField.getText().trim().isEmpty() ? null : locationField.getText().trim());

                // Show success on JavaFX thread
                javafx.application.Platform.runLater(() -> {
                    showSuccess("Device registered successfully!\n" +
                            "API Key: " + response.device.apiKey + "\n" +
                            "Please save these credentials securely.\n" +
                            "Loading login screen...");
                    registerButton.setDisable(false);

                    // Fetch store info, sync users, and switch to login screen
                    new Thread(() -> {
                        try {
                            // Fetch store information first
                            com.pos.service.StoreService storeService = com.pos.service.StoreService
                                    .getInstance();
                            storeService.fetchStoreInfo();
                            logger.info("Store information fetched after registration");

                            // Trigger initial sync to fetch users and other data from backend
                            logger.info("Starting initial sync after device registration...");
                            com.pos.sync.SyncManager syncManager = com.pos.sync.SyncManager.getInstance();

                            // Perform full inbound sync to get users, products, etc.
                            com.pos.sync.SyncResult syncResult = syncManager.performFullInboundSync();

                            if (syncResult.isSuccess()) {
                                logger.info("Initial sync completed: {} items synced", syncResult.getSynced());
                            } else {
                                logger.warn("Initial sync completed with issues: {}", syncResult.getError());
                                // Continue anyway - users might sync later
                            }

                            // Explicitly verify user sync and ensure users are stored in database
                            logger.info("Verifying user sync after registration...");
                            com.pos.sync.inbound.UserInboundSync userInboundSync = com.pos.sync.inbound.UserInboundSync
                                    .getInstance();

                            // Explicitly sync users to ensure they're in database
                            try {
                                com.pos.sync.SyncResult userSyncResult = userInboundSync.sync(null);
                                if (userSyncResult.isSuccess()) {
                                    int userCount = userInboundSync.getLocalUserCount();
                                    logger.info("User sync verified: {} users synced and stored in database",
                                            userCount);

                                    if (userCount == 0) {
                                        logger.warn("No users found after sync. Retrying user sync...");
                                        // Retry once
                                        Thread.sleep(500);
                                        userSyncResult = userInboundSync.sync(null);
                                        userCount = userInboundSync.getLocalUserCount();
                                        if (userCount > 0) {
                                            logger.info("User sync retry successful: {} users now in database",
                                                    userCount);
                                        } else {
                                            logger.warn(
                                                    "No users found after retry. Users may not be available for this store.");
                                        }
                                    }
                                } else {
                                    logger.warn("User sync failed: {}", userSyncResult.getError());
                                    // Retry once
                                    Thread.sleep(500);
                                    try {
                                        userSyncResult = userInboundSync.sync(null);
                                        int userCount = userInboundSync.getLocalUserCount();
                                        if (userSyncResult.isSuccess() && userCount > 0) {
                                            logger.info("User sync retry successful: {} users synced", userCount);
                                        } else {
                                            logger.warn("User sync retry failed or no users found");
                                        }
                                    } catch (Exception retryEx) {
                                        logger.error("User sync retry failed", retryEx);
                                    }
                                }
                            } catch (Exception e) {
                                logger.error("Error during user sync verification", e);
                                // Continue to login screen anyway - users might sync later
                            }

                            // Wait a moment for UI to update and ensure store info is loaded
                            Thread.sleep(1000);

                            // Verify store info is loaded before showing login screen (reuse existing
                            // storeService)
                            String storeName = storeService.getStoreName();
                            if (storeName == null || storeName.isEmpty()) {
                                logger.info("Store info not yet loaded, waiting a bit more...");
                                Thread.sleep(500);
                                // Try fetching again
                                try {
                                    storeService.fetchStoreInfo();
                                    Thread.sleep(300);
                                } catch (Exception e) {
                                    logger.warn("Failed to fetch store info before showing login", e);
                                }
                            }

                            // Show login screen on JavaFX thread
                            javafx.application.Platform.runLater(() -> {
                                showLoginScreen();
                            });
                        } catch (InterruptedException e) {
                            logger.error("Error switching to login screen", e);
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            logger.error("Error during post-registration setup", e);
                            // Still show login screen even if sync fails
                            javafx.application.Platform.runLater(() -> {
                                showLoginScreen();
                            });
                        }
                    }).start();
                });
            } catch (Exception e) {
                logger.error("Registration failed", e);
                javafx.application.Platform.runLater(() -> {
                    showError("Registration failed: " + e.getMessage());
                    registerButton.setDisable(false);
                });
            }
        }).start();
    }

    /**
     * Show login screen with proper initialization matching PosApplication.start()
     * behavior.
     * This ensures the login screen is fully functional when navigating from
     * registration.
     */
    private void showLoginScreen() {
        try {
            Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
            LoginScreen loginScreen = new LoginScreen(stage);
            Scene loginScene = new Scene(loginScreen, screenBounds.getWidth(), screenBounds.getHeight());

            // Load stylesheet - CRITICAL for new UI to display correctly
            try {
                loginScene.getStylesheets().add(
                        getClass().getResource("/styles/application.css").toExternalForm());
                logger.info("Stylesheet loaded for login screen");
            } catch (Exception e) {
                logger.warn("Stylesheet not found, using default styles", e);
            }

            // Set scene and title
            stage.setScene(loginScene);
            stage.setTitle("Pasal POS 2 - Login");

            // Configure for fullscreen mode (matching PosApplication.start())
            stage.setFullScreenExitKeyCombination(javafx.scene.input.KeyCombination.NO_MATCH);
            stage.setFullScreenExitHint(""); // Remove exit hint

            // Prevent fullscreen exit by listening to fullscreen property changes
            stage.fullScreenProperty().addListener((obs, wasFullScreen, isNowFullScreen) -> {
                if (!isNowFullScreen && wasFullScreen) {
                    // Force back to fullscreen if user tries to exit
                    javafx.application.Platform.runLater(() -> {
                        stage.setFullScreen(true);
                    });
                }
            });

            // Maximize window first to get proper dimensions (matching
            // PosApplication.start())
            stage.setMaximized(true);

            // Show stage first, then set fullscreen (required for macOS)
            stage.show();
            stage.toFront();

            // Set fullscreen after showing (required for proper fullscreen on macOS)
            // Use a small delay to ensure the stage is fully shown
            javafx.application.Platform.runLater(() -> {
                PauseTransition delay = new PauseTransition(Duration.millis(100));
                delay.setOnFinished(e -> {
                    try {
                        if (stage.isShowing() && !stage.isFullScreen()) {
                            stage.setFullScreen(true);
                            stage.toFront();
                            stage.requestFocus();
                            logger.info("Login screen displayed successfully with full initialization");
                        }
                    } catch (IllegalStateException ex) {
                        logger.warn("Could not set fullscreen immediately, will retry", ex);
                        // Retry once more after a longer delay
                        javafx.application.Platform.runLater(() -> {
                            try {
                                if (stage.isShowing() && !stage.isFullScreen()) {
                                    stage.setFullScreen(true);
                                    logger.info("Login screen fullscreen set on retry");
                                }
                            } catch (Exception ex2) {
                                logger.warn("Failed to set fullscreen after retry, continuing without fullscreen", ex2);
                            }
                        });
                    }
                });
                delay.play();
            });
        } catch (Exception e) {
            logger.error("Error showing login screen", e);
            // Fallback: try basic login screen setup
            try {
                Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
                LoginScreen loginScreen = new LoginScreen(stage);
                Scene loginScene = new Scene(loginScreen, screenBounds.getWidth(), screenBounds.getHeight());

                // Try to load stylesheet even in fallback
                try {
                    loginScene.getStylesheets().add(
                            getClass().getResource("/styles/application.css").toExternalForm());
                } catch (Exception cssEx) {
                    logger.warn("Could not load stylesheet in fallback", cssEx);
                }

                stage.setScene(loginScene);
                stage.setTitle("Pasal POS 2 - Login");
                stage.setMaximized(true);
                stage.show();
                stage.toFront();

                javafx.application.Platform.runLater(() -> {
                    try {
                        stage.setFullScreen(true);
                    } catch (Exception ex) {
                        logger.warn("Could not set fullscreen in fallback", ex);
                    }
                });
            } catch (Exception fallbackEx) {
                logger.error("Failed to show login screen even in fallback", fallbackEx);
            }
        }
    }

    private void showError(String message) {
        statusLabel.setText(message);
        statusLabel.setStyle("-fx-text-fill: #f44336;");
    }

    private void showSuccess(String message) {
        statusLabel.setText(message);
        statusLabel.setStyle("-fx-text-fill: #4CAF50;");
    }
}
