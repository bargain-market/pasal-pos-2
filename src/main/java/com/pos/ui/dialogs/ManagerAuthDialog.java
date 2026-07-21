package com.pos.ui.dialogs;

import com.pos.ui.components.NumericKeypad;
import com.pos.ui.components.CompactAlphanumericKeypad;
import com.pos.util.DialogHelper;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Touch-optimized dialog for manager authentication/approval.
 * Features: Large input fields, embedded keyboards, clear visual feedback.
 */
public class ManagerAuthDialog extends Dialog<ManagerAuthDialog.ManagerAuthResult> {

    private static final Logger logger = LoggerFactory.getLogger(ManagerAuthDialog.class);

    // Touch-screen optimized sizes
    private static final double INPUT_HEIGHT = 60.0;
    private static final double BUTTON_HEIGHT = 65.0;
    private static final double INPUT_FONT_SIZE = 24.0;
    private static final double LABEL_FONT_SIZE = 16.0;

    // Colors
    private static final String PRIMARY_COLOR = "#1565C0";
    private static final String SUCCESS_COLOR = "#4CAF50";
    private static final String DANGER_COLOR = "#f44336";
    private static final String NEUTRAL_COLOR = "#757575";
    private static final String WARNING_COLOR = "#FF9800";
    private static final String CARD_BG_COLOR = "#ffffff";
    private static final String DIALOG_BG_COLOR = "#f5f7fa";

    // UI Components
    private TextField usernameField;
    private PasswordField pinField;
    private Label pinDotsLabel;
    private Label errorLabel;
    private Button approveButton;
    private Button cancelButton;
    private VBox usernameInputSection;
    private VBox pinInputSection;
    private CompactAlphanumericKeypad alphaKeypad;
    private NumericKeypad numericKeypad;

    private StringBuilder pinBuffer = new StringBuilder();
    private boolean isUsernameMode = true;
    private static final int MAX_PIN_LENGTH = 6;

    public ManagerAuthDialog() {
        initializeDialog();
    }

    private void initializeDialog() {
        setTitle("Manager Approval Required");
        initModality(Modality.APPLICATION_MODAL);
        initStyle(StageStyle.UNDECORATED);

        // Set owner window
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

        // Main container
        VBox mainContainer = new VBox(0);
        // mainContainer.setPrefWidth(500);
        // mainContainer.setMaxWidth(550);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.5, 0.6);
        mainContainer.setStyle(
                "-fx-background-color: " + DIALOG_BG_COLOR + "; " +
                        "-fx-background-radius: 16; " +
                        "-fx-border-radius: 16; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 20, 0, 0, 5);");

        // Header
        VBox header = createHeader();

        // Content sections
        VBox contentSection = createContentSection();

        // Action buttons
        HBox actionButtons = createActionButtons();

        mainContainer.getChildren().addAll(header, contentSection, actionButtons);

        // Configure dialog pane
        getDialogPane().setContent(mainContainer);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().lookupButton(ButtonType.CLOSE).setVisible(false);
        getDialogPane().setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        getDialogPane().getScene().setFill(Color.TRANSPARENT);

        // Set result converter (handled by custom buttons)
        setResultConverter(dialogButton -> null);

        // Focus username field on open
        javafx.application.Platform.runLater(() -> usernameField.requestFocus());
    }

    private VBox createHeader() {
        VBox header = new VBox(8);
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(25, 20, 20, 20));
        header.setStyle(
                "-fx-background-color: " + PRIMARY_COLOR + "; " +
                        "-fx-background-radius: 16 16 0 0;");

        // Lock icon
        Label iconLabel = new Label("🔐");
        iconLabel.setFont(Font.font("System", FontWeight.NORMAL, 40));

        // Title
        Label titleLabel = new Label("Manager Approval Required");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 22));
        titleLabel.setTextFill(Color.WHITE);

        // Subtitle
        Label subtitleLabel = new Label("Enter manager credentials to authorize this action");
        subtitleLabel.setFont(Font.font("System", FontWeight.NORMAL, 14));
        subtitleLabel.setTextFill(Color.web("#ffffff", 0.9));
        subtitleLabel.setWrapText(true);
        subtitleLabel.setAlignment(Pos.CENTER);

        header.getChildren().addAll(iconLabel, titleLabel, subtitleLabel);
        return header;
    }

    private VBox createContentSection() {
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setAlignment(Pos.CENTER);

        // Username input section
        usernameInputSection = createUsernameSection();

        // PIN input section
        pinInputSection = createPinSection();
        pinInputSection.setVisible(false);
        pinInputSection.setManaged(false);

        // Error label
        errorLabel = new Label();
        errorLabel.setFont(Font.font("System", FontWeight.NORMAL, 14));
        errorLabel.setTextFill(Color.web(DANGER_COLOR));
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setWrapText(true);
        errorLabel.setAlignment(Pos.CENTER);

        content.getChildren().addAll(usernameInputSection, pinInputSection, errorLabel);
        return content;
    }

    private VBox createUsernameSection() {
        VBox section = new VBox(12);
        section.setAlignment(Pos.CENTER);

        // Username label
        Label usernameLabel = new Label("👤 Manager Username");
        usernameLabel.setFont(Font.font("System", FontWeight.BOLD, LABEL_FONT_SIZE));
        usernameLabel.setTextFill(Color.web("#333333"));

        // Username field
        usernameField = new TextField();
        usernameField.setPromptText("Enter username");
        usernameField.setPrefHeight(INPUT_HEIGHT);
        usernameField.setFont(Font.font("System", FontWeight.NORMAL, INPUT_FONT_SIZE));
        usernameField.setAlignment(Pos.CENTER);
        usernameField.setStyle(
                "-fx-background-color: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-border-color: " + PRIMARY_COLOR + "; " +
                        "-fx-border-width: 2; " +
                        "-fx-border-radius: 12; " +
                        "-fx-padding: 10 15 10 15;");
        usernameField.setEditable(false); // Use on-screen keyboard only

        // Alphanumeric keyboard
        alphaKeypad = new CompactAlphanumericKeypad();
        alphaKeypad.setListener(this::handleAlphaKeypadInput);
        alphaKeypad.setMaxWidth(460);

        section.getChildren().addAll(usernameLabel, usernameField, alphaKeypad);
        return section;
    }

    private VBox createPinSection() {
        VBox section = new VBox(12);
        section.setAlignment(Pos.CENTER);

        // PIN label
        Label pinLabel = new Label("🔑 Enter PIN");
        pinLabel.setFont(Font.font("System", FontWeight.BOLD, LABEL_FONT_SIZE));
        pinLabel.setTextFill(Color.web("#333333"));

        // PIN dots display
        HBox pinDotsContainer = new HBox(5);
        pinDotsContainer.setAlignment(Pos.CENTER);
        pinDotsContainer.setPadding(new Insets(15, 30, 15, 30));
        pinDotsContainer.setStyle(
                "-fx-background-color: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-border-color: " + PRIMARY_COLOR + "; " +
                        "-fx-border-width: 2; " +
                        "-fx-border-radius: 12;");

        pinDotsLabel = new Label("● ● ● ● ● ●");
        pinDotsLabel.setFont(Font.font("System", FontWeight.BOLD, 32));
        pinDotsLabel.setTextFill(Color.web("#cccccc"));

        pinDotsContainer.getChildren().add(pinDotsLabel);

        // Hidden PIN field for actual value storage
        pinField = new PasswordField();
        pinField.setVisible(false);
        pinField.setManaged(false);

        // Numeric keypad for PIN
        numericKeypad = new NumericKeypad(false, true, false);
        numericKeypad.setListener(this::handlePinKeypadInput);
        numericKeypad.setEnterListener(this::handlePinEnter);
        numericKeypad.setMaxWidth(300);
        numericKeypad.setPrefHeight(280);

        section.getChildren().addAll(pinLabel, pinDotsContainer, pinField, numericKeypad);
        return section;
    }

    private HBox createActionButtons() {
        HBox buttonContainer = new HBox(15);
        buttonContainer.setAlignment(Pos.CENTER);
        buttonContainer.setPadding(new Insets(15, 20, 20, 20));
        buttonContainer.setStyle(
                "-fx-background-color: " + CARD_BG_COLOR + "; " +
                        "-fx-background-radius: 0 0 16 16; " +
                        "-fx-border-color: #e0e0e0; " +
                        "-fx-border-width: 1 0 0 0;");

        // Cancel button
        cancelButton = new Button("Cancel");
        cancelButton.setPrefWidth(140);
        cancelButton.setPrefHeight(BUTTON_HEIGHT);
        cancelButton.setFont(Font.font("System", FontWeight.BOLD, 18));
        cancelButton.setFocusTraversable(false);
        cancelButton.setStyle(
                "-fx-background-color: " + NEUTRAL_COLOR + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-cursor: hand; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.15), 4, 0, 0, 1);");
        cancelButton.setOnAction(e -> {
            playPressAnimation(cancelButton);
            setResult(null);
            close();
        });

        // Approve/Next button
        approveButton = new Button("Next →");
        approveButton.setPrefWidth(180);
        approveButton.setPrefHeight(BUTTON_HEIGHT);
        approveButton.setFont(Font.font("System", FontWeight.BOLD, 18));
        approveButton.setFocusTraversable(false);
        approveButton.setStyle(
                "-fx-background-color: " + PRIMARY_COLOR + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-cursor: hand; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 6, 0, 0, 2);");
        approveButton.setOnAction(e -> {
            playPressAnimation(approveButton);
            if (isUsernameMode) {
                switchToPinMode();
            } else {
                handleApprove();
            }
        });

        buttonContainer.getChildren().addAll(cancelButton, approveButton);
        return buttonContainer;
    }

    private void handleAlphaKeypadInput(String key) {
        clearError();

        if ("⌫".equals(key)) {
            String current = usernameField.getText();
            if (current.length() > 0) {
                usernameField.setText(current.substring(0, current.length() - 1));
            }
        } else if ("␣".equals(key)) {
            // Space - generally not used in usernames, skip
        } else if ("⏎".equals(key) || "↵".equals(key)) {
            // Enter - switch to PIN mode
            switchToPinMode();
        } else if ("C".equals(key)) {
            usernameField.setText("");
        } else {
            usernameField.setText(usernameField.getText() + key);
        }
    }

    private void handlePinKeypadInput(String key) {
        clearError();

        if ("C".equals(key)) {
            pinBuffer.setLength(0);
        } else if ("⌫".equals(key)) {
            if (pinBuffer.length() > 0) {
                pinBuffer.setLength(pinBuffer.length() - 1);
            }
        } else if (key.matches("[0-9]")) {
            if (pinBuffer.length() < MAX_PIN_LENGTH) {
                pinBuffer.append(key);
            }
        }

        updatePinDisplay();
    }

    private void handlePinEnter() {
        handleApprove();
    }

    private void updatePinDisplay() {
        StringBuilder display = new StringBuilder();
        for (int i = 0; i < MAX_PIN_LENGTH; i++) {
            if (i < pinBuffer.length()) {
                display.append("●");
            } else {
                display.append("○");
            }
            if (i < MAX_PIN_LENGTH - 1) {
                display.append(" ");
            }
        }
        pinDotsLabel.setText(display.toString());

        // Update colors - filled dots are blue, empty are gray
        if (pinBuffer.length() > 0) {
            pinDotsLabel.setTextFill(Color.web(PRIMARY_COLOR));
        } else {
            pinDotsLabel.setTextFill(Color.web("#cccccc"));
        }
    }

    private void switchToPinMode() {
        String username = usernameField.getText().trim();
        if (username.isEmpty()) {
            showError("Please enter a username");
            return;
        }

        isUsernameMode = false;
        usernameInputSection.setVisible(false);
        usernameInputSection.setManaged(false);
        pinInputSection.setVisible(true);
        pinInputSection.setManaged(true);

        approveButton.setText("✓ Approve");
        approveButton.setStyle(
                "-fx-background-color: " + SUCCESS_COLOR + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-cursor: hand; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 6, 0, 0, 2);");

        // Add back button
        cancelButton.setText("← Back");
        cancelButton.setOnAction(e -> {
            playPressAnimation(cancelButton);
            switchToUsernameMode();
        });

        clearError();
    }

    private void switchToUsernameMode() {
        isUsernameMode = true;
        pinBuffer.setLength(0);
        updatePinDisplay();

        usernameInputSection.setVisible(true);
        usernameInputSection.setManaged(true);
        pinInputSection.setVisible(false);
        pinInputSection.setManaged(false);

        approveButton.setText("Next →");
        approveButton.setStyle(
                "-fx-background-color: " + PRIMARY_COLOR + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-cursor: hand; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 6, 0, 0, 2);");

        cancelButton.setText("Cancel");
        cancelButton.setOnAction(e -> {
            playPressAnimation(cancelButton);
            setResult(null);
            close();
        });

        clearError();
    }

    private void handleApprove() {
        String username = usernameField.getText().trim();
        String pin = pinBuffer.toString();

        if (username.isEmpty()) {
            showError("Username is required");
            return;
        }

        if (pin.isEmpty()) {
            showError("PIN is required");
            return;
        }

        // Verify manager credentials
        try {
            com.pos.config.ConfigManager config = com.pos.config.ConfigManager.getInstance();
            String deviceStoreId = config.getProperty("store.id");
            if (deviceStoreId == null || deviceStoreId.isEmpty()) {
                showError("Device not registered to a store");
                return;
            }

            com.pos.database.DatabaseManager dbManager = com.pos.database.DatabaseManager.getInstance();
            String sql = "SELECT id, username, pin_hash, full_name, is_active, role FROM pos_users WHERE username = ? AND store_id = ?";

            try (java.sql.Connection conn = dbManager.getConnection();
                    java.sql.PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, username);
                stmt.setString(2, deviceStoreId);
                java.sql.ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    boolean isActive = rs.getBoolean("is_active");
                    if (!isActive) {
                        showError("User account is inactive");
                        return;
                    }

                    String role = rs.getString("role");
                    if (role == null || role.isEmpty()) {
                        role = "Cashier";
                    }

                    if (!"Manager".equalsIgnoreCase(role) && !"Admin".equalsIgnoreCase(role)) {
                        showError("Manager or Admin role required");
                        logger.warn("Manager approval denied - user {} has role: {}", username, role);
                        return;
                    }

                    String pinHash = rs.getString("pin_hash");

                    if (pinHash != null && !pinHash.isEmpty()) {
                        if (com.pos.util.PasswordHasher.verifyPassword(pin, pinHash)) {
                            ManagerAuthResult result = new ManagerAuthResult();
                            result.approved = true;
                            result.managerUsername = username;
                            result.managerName = rs.getString("full_name");
                            logger.info("Manager approval granted by: {} (role: {})", username, role);
                            setResult(result);
                            close();
                        } else {
                            showError("Invalid PIN");
                            pinBuffer.setLength(0);
                            updatePinDisplay();
                        }
                    } else {
                        showError("Credentials not available offline");
                    }
                } else {
                    showError("Invalid username");
                    switchToUsernameMode();
                }
            }
        } catch (Exception e) {
            logger.error("Error verifying manager credentials", e);
            showError("Authentication failed: " + e.getMessage());
        }
    }

    private void showError(String message) {
        errorLabel.setText("⚠ " + message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);

        FadeTransition fade = new FadeTransition(Duration.millis(200), errorLabel);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();

        // Shake animation for error feedback
        ScaleTransition shake = new ScaleTransition(Duration.millis(100), errorLabel);
        shake.setFromX(0.95);
        shake.setToX(1.0);
        shake.play();
    }

    private void clearError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    private void playPressAnimation(ButtonBase button) {
        ScaleTransition pressDown = new ScaleTransition(Duration.millis(50), button);
        pressDown.setToX(0.95);
        pressDown.setToY(0.95);

        ScaleTransition pressUp = new ScaleTransition(Duration.millis(100), button);
        pressUp.setToX(1.0);
        pressUp.setToY(1.0);

        pressDown.setOnFinished(e -> pressUp.play());
        pressDown.play();
    }

    /**
     * Result class for manager authentication
     */
    public static class ManagerAuthResult {
        public boolean approved = false;
        public String managerUsername;
        public String managerName;
    }
}
