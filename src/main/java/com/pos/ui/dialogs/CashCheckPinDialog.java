package com.pos.ui.dialogs;

import com.pos.service.CashCheckLimitService;
import com.pos.util.DialogHelper;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * Dialog for setting or changing the admin PIN for cash/check limit override.
 * This allows the admin to set the PIN locally on the POS.
 */
public class CashCheckPinDialog extends Dialog<CashCheckPinDialog.PinResult> {

    private static final Logger logger = LoggerFactory.getLogger(CashCheckPinDialog.class);

    private final CashCheckLimitService limitService;
    private final boolean isPinConfigured;

    private PasswordField currentPinField;
    private PasswordField newPinField;
    private PasswordField confirmPinField;
    private Label statusLabel;

    public CashCheckPinDialog() {
        this.limitService = CashCheckLimitService.getInstance();
        this.isPinConfigured = limitService.isPinConfigured();

        initializeDialog();
    }

    private void initializeDialog() {
        setTitle(isPinConfigured ? "Change Admin PIN" : "Set Admin PIN");
        setHeaderText(isPinConfigured
            ? "Enter current PIN and new PIN"
            : "Set a PIN to override cash/check daily limits");
        initModality(Modality.APPLICATION_MODAL);

        try {
            Window currentWindow = javafx.stage.Stage.getWindows().stream()
                    .filter(Window::isShowing)
                    .findFirst()
                    .orElse(null);
            if (currentWindow != null) {
                initOwner(currentWindow);
            }
        } catch (Exception ignored) {
        }

        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.4, 0.5);

        // Info label
        Label infoLabel = new Label(
            "This PIN is required when a user exceeds their daily cash/check operation limit.\n" +
            "The PIN will be synced to the backend and other POS devices."
        );
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        // Status label
        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setVisible(false);

        // Current PIN (only if PIN is already configured)
        if (isPinConfigured) {
            HBox currentPinBox = new HBox(10);
            currentPinBox.setAlignment(Pos.CENTER_LEFT);
            Label currentPinLabel = new Label("Current PIN:");
            currentPinLabel.setPrefWidth(100);
            currentPinField = new PasswordField();
            currentPinField.setPromptText("Enter current PIN");
            currentPinField.setPrefWidth(200);
            HBox.setHgrow(currentPinField, Priority.ALWAYS);
            currentPinBox.getChildren().addAll(currentPinLabel, currentPinField);
            content.getChildren().add(currentPinBox);
        }

        // New PIN
        HBox newPinBox = new HBox(10);
        newPinBox.setAlignment(Pos.CENTER_LEFT);
        Label newPinLabel = new Label(isPinConfigured ? "New PIN:" : "PIN:");
        newPinLabel.setPrefWidth(100);
        newPinField = new PasswordField();
        newPinField.setPromptText(isPinConfigured ? "Enter new PIN" : "Set PIN (4-6 digits)");
        newPinField.setPrefWidth(200);
        HBox.setHgrow(newPinField, Priority.ALWAYS);
        newPinBox.getChildren().addAll(newPinLabel, newPinField);

        // Confirm PIN
        HBox confirmPinBox = new HBox(10);
        confirmPinBox.setAlignment(Pos.CENTER_LEFT);
        Label confirmPinLabel = new Label("Confirm PIN:");
        confirmPinLabel.setPrefWidth(100);
        confirmPinField = new PasswordField();
        confirmPinField.setPromptText("Confirm PIN");
        confirmPinField.setPrefWidth(200);
        HBox.setHgrow(confirmPinField, Priority.ALWAYS);
        confirmPinBox.getChildren().addAll(confirmPinLabel, confirmPinField);

        content.getChildren().addAll(infoLabel, newPinBox, confirmPinBox, statusLabel);

        getDialogPane().setContent(content);

        // Buttons
        ButtonType saveButtonType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(saveButtonType, cancelButtonType);

        // Style the save button
        Button saveButton = (Button) getDialogPane().lookupButton(saveButtonType);
        saveButton.setStyle(
            "-fx-background-color: #4CAF50; " +
            "-fx-text-fill: white; " +
            "-fx-font-weight: bold;"
        );

        // Set result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                return validateAndSave();
            }
            return null;
        });

        // Enter key handling
        Platform.runLater(() -> {
            if (isPinConfigured && currentPinField != null) {
                currentPinField.requestFocus();
            } else {
                newPinField.requestFocus();
            }
        });
    }

    private PinResult validateAndSave() {
        String currentPin = isPinConfigured ? currentPinField.getText() : null;
        String newPin = newPinField.getText();
        String confirmPin = confirmPinField.getText();

        // Validation
        if (isPinConfigured && (currentPin == null || currentPin.isEmpty())) {
            showError("Please enter the current PIN.");
            return null;
        }

        if (newPin == null || newPin.isEmpty()) {
            showError("Please enter a new PIN.");
            return null;
        }

        if (!newPin.equals(confirmPin)) {
            showError("New PIN and confirm PIN do not match.");
            return null;
        }

        // PIN format validation (4-6 digits)
        if (!newPin.matches("\\d{4,6}")) {
            showError("PIN must be 4-6 digits.");
            return null;
        }

        // Verify current PIN if configured
        if (isPinConfigured && !limitService.validatePin(currentPin)) {
            showError("Current PIN is incorrect.");
            return null;
        }

        try {
            // Hash the new PIN
            String pinHash = BCrypt.hashpw(newPin, BCrypt.gensalt());

            // Store in local database
            storePinHash(pinHash);

            // Trigger sync to backend
            com.pos.sync.SyncManager.getInstance().triggerOutboundSync();

            return new PinResult(true, pinHash, "PIN saved successfully.");

        } catch (Exception e) {
            logger.error("Error saving PIN", e);
            showError("Error saving PIN: " + e.getMessage());
            return null;
        }
    }

    private void storePinHash(String pinHash) throws SQLException {
        String sql = "MERGE INTO global_settings (id, manual_cash_check_pin_hash, updated_at) KEY (id) VALUES ('global', ?, CURRENT_TIMESTAMP)";

        try (java.sql.Connection conn = com.pos.database.DatabaseManager.getInstance().getConnection();
             java.sql.PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, pinHash);
            stmt.executeUpdate();
        }
    }

    private void showError(String message) {
        statusLabel.setText(message);
        statusLabel.setStyle("-fx-text-fill: #f44336;");
        statusLabel.setVisible(true);
    }

    /**
     * Result class for PIN dialog
     */
    public static class PinResult {
        public final boolean success;
        public final String pinHash;
        public final String message;

        public PinResult(boolean success, String pinHash, String message) {
            this.success = success;
            this.pinHash = pinHash;
            this.message = message;
        }
    }
}
