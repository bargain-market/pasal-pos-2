package com.pos.ui.dialogs;

import com.pos.hardware.HardwareManager;
import com.pos.model.AgeVerification;
import com.pos.service.AgeVerificationService;
import com.pos.service.UserAuthService;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
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
 * Touch-optimized dialog for age verification when selling age-restricted
 * products.
 * Supports ID barcode scanning (PDF417) and cashier visual verification.
 */
public class AgeVerificationDialog extends Dialog<AgeVerificationDialog.AgeVerificationResult> {

    private static final Logger logger = LoggerFactory.getLogger(AgeVerificationDialog.class);

    // Touch-screen optimized sizes
    private static final double BUTTON_HEIGHT = 65.0;

    // Colors
    private static final String PRIMARY_COLOR = "#1565C0";
    private static final String SUCCESS_COLOR = "#4CAF50";
    private static final String DANGER_COLOR = "#f44336";
    private static final String WARNING_COLOR = "#FF9800";
    private static final String CARD_BG_COLOR = "#ffffff";
    private static final String DIALOG_BG_COLOR = "#f5f7fa";
    private static final String AGE_VERIFIED_COLOR = "#2E7D32";
    private static final String AGE_DENIED_COLOR = "#C62828";

    // Services
    private final AgeVerificationService ageVerificationService;
    private final HardwareManager hardwareManager;

    // Dialog state
    private final String productName;
    private final String departmentId;
    private final int requiredAge;

    // UI Components
    private Label statusLabel;
    private Label ageResultLabel;
    private Button approveButton;
    private Button denyButton;

    // Scan result
    private AgeVerification currentVerification;

    /**
     * Create age verification dialog
     * 
     * @param productName  Name of the product being sold
     * @param departmentId Department ID for the product
     * @param requiredAge  Minimum age required (e.g., 21, 18)
     */
    public AgeVerificationDialog(String productName, String departmentId, int requiredAge) {
        this.productName = productName;
        this.departmentId = departmentId;
        this.requiredAge = requiredAge;
        this.ageVerificationService = AgeVerificationService.getInstance();
        this.hardwareManager = new HardwareManager();

        initializeDialog();
        setupScannerListener();
    }

    private void initializeDialog() {
        setTitle("Age Verification Required");
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
        // mainContainer.setPrefWidth(550);
        // mainContainer.setMaxWidth(600);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.5, 0.6);
        mainContainer.setStyle(
                "-fx-background-color: " + DIALOG_BG_COLOR + "; " +
                        "-fx-background-radius: 16; " +
                        "-fx-border-radius: 16; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 20, 0, 0, 5);");

        // Header
        VBox header = createHeader();

        // Content area (scan mode only - no tabs needed)
        VBox contentArea = createScanModeContent();

        // Result display
        VBox resultDisplay = createResultDisplay();

        // Action buttons
        HBox actionButtons = createActionButtons();

        mainContainer.getChildren().addAll(header, contentArea, resultDisplay, actionButtons);

        // Configure dialog pane
        getDialogPane().setContent(mainContainer);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().lookupButton(ButtonType.CLOSE).setVisible(false);
        getDialogPane().setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        getDialogPane().getScene().setFill(Color.TRANSPARENT);

        // Set result converter
        setResultConverter(dialogButton -> null);
    }

    private VBox createHeader() {
        VBox header = new VBox(8);
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(25, 20, 20, 20));
        header.setStyle(
                "-fx-background-color: " + WARNING_COLOR + "; " +
                        "-fx-background-radius: 16 16 0 0;");

        // Warning icon
        Label iconLabel = new Label("🔞");
        iconLabel.setFont(Font.font("System", FontWeight.NORMAL, 48));

        // Title
        Label titleLabel = new Label("Age Verification Required");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 22));
        titleLabel.setTextFill(Color.WHITE);

        // Product and age info
        Label productLabel = new Label("Product: " + productName);
        productLabel.setFont(Font.font("System", FontWeight.NORMAL, 14));
        productLabel.setTextFill(Color.web("#ffffff", 0.9));

        Label ageLabel = new Label("Minimum Age: " + requiredAge + "+");
        ageLabel.setFont(Font.font("System", FontWeight.BOLD, 18));
        ageLabel.setTextFill(Color.WHITE);
        ageLabel.setStyle("-fx-background-color: rgba(0,0,0,0.2); -fx-padding: 8 16; -fx-background-radius: 20;");

        header.getChildren().addAll(iconLabel, titleLabel, productLabel, ageLabel);
        return header;
    }

    /**
     * Handle quick visual verification - cashier confirms they visually checked
     * customer's ID
     */
    private void handleQuickVerify() {
        logger.info("Quick age verification - cashier visual confirmation for {} (age {}+)", productName, requiredAge);

        // Get current user
        String verifiedBy = getCurrentUserName();

        // Create verification record for visual confirmation
        currentVerification = ageVerificationService.verifyVisually(requiredAge, departmentId, verifiedBy);

        if (currentVerification != null) {
            // For visual verification, we trust the cashier - immediately approve
            AgeVerificationResult result = new AgeVerificationResult();
            result.approved = true;
            result.verification = currentVerification;
            setResult(result);
            cleanupAndClose();
        }
    }

    private VBox createScanModeContent() {
        VBox content = new VBox(20);
        content.setAlignment(Pos.CENTER);
        content.setPadding(new Insets(20));

        // Scan icon/animation
        Label scanIcon = new Label("🪪");
        scanIcon.setFont(Font.font("System", FontWeight.NORMAL, 80));

        // Instructions
        Label instructionLabel = new Label("Scan the back of customer's ID");
        instructionLabel.setFont(Font.font("System", FontWeight.BOLD, 18));
        instructionLabel.setTextFill(Color.web("#333333"));

        Label subInstructionLabel = new Label("Position the barcode under the scanner");
        subInstructionLabel.setFont(Font.font("System", FontWeight.NORMAL, 14));
        subInstructionLabel.setTextFill(Color.web("#666666"));

        // Status indicator
        statusLabel = new Label("Waiting for scan...");
        statusLabel.setFont(Font.font("System", FontWeight.NORMAL, 16));
        statusLabel.setTextFill(Color.web(PRIMARY_COLOR));
        statusLabel.setStyle("-fx-background-color: #e3f2fd; -fx-padding: 12 24; -fx-background-radius: 8;");

        // Quick verify hint
        Label hintLabel = new Label("Or press 'Verify Age' if you've checked customer's ID");
        hintLabel.setFont(Font.font("System", FontWeight.NORMAL, 12));
        hintLabel.setTextFill(Color.web("#999999"));

        content.getChildren().addAll(scanIcon, instructionLabel, subInstructionLabel, statusLabel, hintLabel);
        return content;
    }

    private VBox createResultDisplay() {
        VBox resultBox = new VBox(8);
        resultBox.setAlignment(Pos.CENTER);
        resultBox.setPadding(new Insets(15));
        resultBox.setStyle("-fx-background-color: #fafafa; -fx-border-color: #e0e0e0; -fx-border-width: 1 0 0 0;");

        ageResultLabel = new Label("");
        ageResultLabel.setFont(Font.font("System", FontWeight.BOLD, 20));
        ageResultLabel.setVisible(false);
        ageResultLabel.setManaged(false);

        resultBox.getChildren().add(ageResultLabel);
        return resultBox;
    }

    private HBox createActionButtons() {
        HBox buttonContainer = new HBox(15);
        buttonContainer.setAlignment(Pos.CENTER);
        buttonContainer.setPadding(new Insets(15, 20, 20, 20));
        buttonContainer.setStyle(
                "-fx-background-color: " + CARD_BG_COLOR + "; " +
                        "-fx-background-radius: 0 0 16 16;");

        // Deny/Cancel button
        denyButton = new Button("✕ Deny Sale");
        denyButton.setPrefWidth(160);
        denyButton.setPrefHeight(BUTTON_HEIGHT);
        denyButton.setFont(Font.font("System", FontWeight.BOLD, 16));
        denyButton.setStyle(
                "-fx-background-color: " + DANGER_COLOR + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-cursor: hand;");
        denyButton.setOnAction(e -> {
            playPressAnimation(denyButton);
            AgeVerificationResult result = new AgeVerificationResult();
            result.approved = false;
            result.cancelled = false;
            setResult(result);
            cleanupAndClose();
        });

        // Verify button - now acts as quick visual verification (no data entry
        // required)
        Button verifyButton = new Button("✓ Verify Age");
        verifyButton.setPrefWidth(160);
        verifyButton.setPrefHeight(BUTTON_HEIGHT);
        verifyButton.setFont(Font.font("System", FontWeight.BOLD, 16));
        verifyButton.setStyle(
                "-fx-background-color: " + PRIMARY_COLOR + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-cursor: hand;");
        verifyButton.setOnAction(e -> {
            playPressAnimation(verifyButton);
            // Quick verify - cashier confirms they visually checked the ID
            handleQuickVerify();
        });

        // Approve button (shown after successful scan verification)
        approveButton = new Button("✓ Approve Sale");
        approveButton.setPrefWidth(180);
        approveButton.setPrefHeight(BUTTON_HEIGHT);
        approveButton.setFont(Font.font("System", FontWeight.BOLD, 16));
        approveButton.setStyle(
                "-fx-background-color: " + SUCCESS_COLOR + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-background-radius: 12; " +
                        "-fx-cursor: hand;");
        approveButton.setVisible(false);
        approveButton.setManaged(false);
        approveButton.setOnAction(e -> {
            playPressAnimation(approveButton);
            if (currentVerification != null && currentVerification.meetsAgeRequirement()) {
                AgeVerificationResult result = new AgeVerificationResult();
                result.approved = true;
                result.verification = currentVerification;
                setResult(result);
                cleanupAndClose();
            }
        });

        buttonContainer.getChildren().addAll(denyButton, verifyButton, approveButton);
        return buttonContainer;
    }

    private void setupScannerListener() {
        try {
            // Store original callback to restore later
            // Set up scanner callback for this dialog
            hardwareManager.setScanCallback(barcodeData -> {
                Platform.runLater(() -> handleScannedBarcode(barcodeData));
            });
        } catch (Exception e) {
            logger.warn("Could not set up scanner listener: {}", e.getMessage());
        }
    }

    private void handleScannedBarcode(String barcodeData) {
        logger.info("Received barcode scan for age verification");

        // Check if it might be a driver's license
        if (!ageVerificationService.mightBeDriversLicense(barcodeData)) {
            statusLabel.setText("Not a valid ID barcode. Try again.");
            statusLabel.setStyle("-fx-background-color: #ffebee; -fx-padding: 12 24; -fx-background-radius: 8;");
            return;
        }

        statusLabel.setText("Processing ID...");
        statusLabel.setStyle("-fx-background-color: #fff3e0; -fx-padding: 12 24; -fx-background-radius: 8;");

        // Get current user
        String verifiedBy = getCurrentUserName();

        // Attempt verification
        currentVerification = ageVerificationService.verifyWithScan(
                barcodeData, requiredAge, departmentId, verifiedBy);

        if (currentVerification != null) {
            displayVerificationResult(currentVerification);
        } else {
            statusLabel.setText("Could not read ID. Try again or press Verify Age.");
            statusLabel.setStyle("-fx-background-color: #ffebee; -fx-padding: 12 24; -fx-background-radius: 8;");
        }
    }

    private void displayVerificationResult(AgeVerification verification) {
        int customerAge = verification.getCustomerAge();
        boolean approved = verification.meetsAgeRequirement();

        ageResultLabel.setVisible(true);
        ageResultLabel.setManaged(true);

        if (approved) {
            ageResultLabel.setText("✓ Customer is " + customerAge + " years old - APPROVED");
            ageResultLabel.setTextFill(Color.web(AGE_VERIFIED_COLOR));
            ageResultLabel.setStyle("-fx-background-color: #e8f5e9; -fx-padding: 12 20; -fx-background-radius: 8;");

            // Show approve button
            approveButton.setVisible(true);
            approveButton.setManaged(true);

            // Update status
            statusLabel.setText("ID Verified Successfully!");
            statusLabel.setStyle("-fx-background-color: #e8f5e9; -fx-padding: 12 24; -fx-background-radius: 8;");

            // Flash success animation
            FadeTransition fade = new FadeTransition(Duration.millis(200), ageResultLabel);
            fade.setFromValue(0);
            fade.setToValue(1);
            fade.play();

        } else {
            ageResultLabel.setText("✕ Customer is " + customerAge + " years old - UNDERAGE");
            ageResultLabel.setTextFill(Color.web(AGE_DENIED_COLOR));
            ageResultLabel.setStyle("-fx-background-color: #ffebee; -fx-padding: 12 20; -fx-background-radius: 8;");

            // Hide approve button
            approveButton.setVisible(false);
            approveButton.setManaged(false);

            // Update status
            statusLabel.setText("Customer does not meet age requirement");
            statusLabel.setStyle("-fx-background-color: #ffebee; -fx-padding: 12 24; -fx-background-radius: 8;");
        }
    }

    private void clearResult() {
        ageResultLabel.setVisible(false);
        ageResultLabel.setManaged(false);
        approveButton.setVisible(false);
        approveButton.setManaged(false);
        currentVerification = null;

        statusLabel.setText("Waiting for scan...");
        statusLabel.setStyle("-fx-background-color: #e3f2fd; -fx-padding: 12 24; -fx-background-radius: 8;");
    }

    private String getCurrentUserName() {
        try {
            UserAuthService authService = UserAuthService.getInstance();
            String name = authService.getCurrentUserName();
            return name != null ? name : "Unknown";
        } catch (Exception e) {
            return "Unknown";
        }
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

    private void cleanupAndClose() {
        // Restore original scanner callback if needed
        try {
            hardwareManager.setScanCallback(null);
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * Result class for age verification dialog
     */
    public static class AgeVerificationResult {
        public boolean approved = false;
        public boolean cancelled = false;
        public AgeVerification verification;
    }
}
