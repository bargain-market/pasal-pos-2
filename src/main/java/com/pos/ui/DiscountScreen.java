package com.pos.ui;

import com.pos.ui.components.NumericKeypad;
import com.pos.ui.components.ToastNotification;
import com.pos.ui.dialogs.ManagerAuthDialog;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.function.Consumer;

/**
 * A dedicated full-screen component for applying discounts to a sale.
 * Replaces the legacy DiscountDialog with a premium, touch-optimized experience.
 */
public class DiscountScreen extends BorderPane {
    private static final Logger logger = LoggerFactory.getLogger(DiscountScreen.class);

    // Thresholds for manager approval (migrated from DiscountDialog)
    private static final BigDecimal PERCENTAGE_THRESHOLD = new BigDecimal("20.0");
    private static final BigDecimal AMOUNT_THRESHOLD = new BigDecimal("50.0");

    private final BigDecimal subtotal;
    private final Consumer<DiscountResult> onApply;
    private final Runnable onCancel;

    private boolean isPercentageMode = true;
    private StringBuilder inputBuffer = new StringBuilder();
    private String selectedReason = "General Discount";

    // UI Components
    private Label subtotalLabel;
    private Label inputDisplayLabel;
    private Label previewDiscountLabel;
    private Label previewTotalLabel;
    private Button percentTabBtn;
    private Button amountTabBtn;
    private TextField customReasonField;
    private FlowPane reasonFlowPane;
    private FlowPane quickPresetsContainer;

    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

    public DiscountScreen(BigDecimal subtotal, Consumer<DiscountResult> onApply, Runnable onCancel) {
        this.subtotal = subtotal;
        this.onApply = onApply;
        this.onCancel = onCancel;

        initializeUI();
    }

    private void initializeUI() {
        getStyleClass().add("discount-screen");
        setPadding(new Insets(30));
        setStyle("-fx-background-color: #f8fafc;");

        // Header Section
        VBox header = createHeader();
        setTop(header);

        // Center Content Section
        HBox content = createContent();
        setCenter(content);

        // Footer Section
        HBox footer = createFooter();
        setBottom(footer);

        updateModeDisplay();
        updateCalculationPreview();
    }

    private VBox createHeader() {
        VBox header = new VBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 30, 0));

        HBox titleRow = new HBox(15);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Button backBtn = new Button("←");
        backBtn.setStyle("-fx-font-size: 28px; -fx-background-color: transparent; -fx-text-fill: #64748b; -fx-cursor: hand; -fx-padding: 0 10 0 0;");
        backBtn.setOnAction(e -> onCancel.run());

        Label title = new Label("Apply Discount");
        title.setStyle("-fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        subtotalLabel = new Label("Subtotal: " + currencyFormat.format(subtotal));
        subtotalLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: 600; -fx-text-fill: #2563eb; -fx-background-color: #dbeafe; -fx-padding: 10 20; -fx-background-radius: 10;");

        titleRow.getChildren().addAll(backBtn, title, spacer, subtotalLabel);

        header.getChildren().add(titleRow);
        return header;
    }

    private HBox createContent() {
        HBox mainContent = new HBox(40);
        mainContent.setAlignment(Pos.CENTER);
        mainContent.setPadding(new Insets(10));

        // LEFT: Keypad and Input
        VBox leftSection = new VBox(12); // Reduced spacing for tighter layout
        leftSection.setAlignment(Pos.TOP_CENTER);
        leftSection.setMinWidth(380); // Adjusted for tighter layout

        // Mode Navigation (Tabs)
        HBox modeBar = new HBox(0);
        modeBar.setAlignment(Pos.CENTER);
        modeBar.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 30; -fx-padding: 4;");
        modeBar.setMaxWidth(350);

        percentTabBtn = createTabButton("Percentage (%)", true);
        amountTabBtn = createTabButton("Fixed Amount ($)", false);
        modeBar.getChildren().addAll(percentTabBtn, amountTabBtn);
        HBox.setHgrow(percentTabBtn, Priority.ALWAYS);
        HBox.setHgrow(amountTabBtn, Priority.ALWAYS);

        // Input Display
        inputDisplayLabel = new Label("0");
        inputDisplayLabel.setStyle("-fx-font-size: 48px; -fx-font-weight: 800; -fx-text-fill: #1e293b; -fx-min-width: 300; -fx-alignment: center; -fx-background-color: white; -fx-background-radius: 16; -fx-padding: 20; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 10, 0, 0, 2);");

        // Quick Presets
        quickPresetsContainer = new FlowPane(10, 10);
        quickPresetsContainer.setAlignment(Pos.CENTER);
        quickPresetsContainer.setPadding(new Insets(10, 0, 10, 0));

        // Keypad
        NumericKeypad keypad = new NumericKeypad(true, true, false);
        keypad.setListener(this::handleKeypadInput);
        keypad.setEnterListener(this::handleApply);
        keypad.setPrefSize(350, 400);

        leftSection.getChildren().addAll(modeBar, inputDisplayLabel, quickPresetsContainer, keypad);

        // RIGHT: Reasons and Preview
        VBox rightSection = new VBox(25);
        rightSection.setAlignment(Pos.TOP_LEFT);
        rightSection.setPrefWidth(500);

        // Reason Selection
        Label reasonHeading = new Label("SELECT REASON");
        reasonHeading.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #94a3b8; -fx-letter-spacing: 1.5px;");

        reasonFlowPane = new FlowPane(10, 10);
        String[] reasons = {"General Discount", "Sales Event", "Loyalty Reward", "Damaged Item", "Customer Satisfaction", "Employee Discount"};
        for (String r : reasons) {
            reasonFlowPane.getChildren().add(createReasonChip(r));
        }

        Label customReasonLabel = new Label("Custom Reason:");
        customReasonLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #64748b;");
        customReasonField = new TextField();
        customReasonField.setPromptText("Enter custom reason...");
        customReasonField.setStyle("-fx-font-size: 18px; -fx-padding: 12; -fx-background-radius: 10; -fx-border-color: #e2e8f0; -fx-border-radius: 10;");
        customReasonField.textProperty().addListener((obs, oldV, newV) -> {
            if (newV != null && !newV.trim().isEmpty()) {
                selectedReason = newV.trim();
                deselectAllChips();
            }
        });

        // Calculation Preview Card
        VBox previewCard = new VBox(15);
        previewCard.setPadding(new Insets(25));
        previewCard.setStyle("-fx-background-color: #1e293b; -fx-background-radius: 16;");

        Label previewTitle = new Label("PREVIEW");
        previewTitle.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 12px; -fx-font-weight: bold;");

        HBox discountRow = new HBox();
        Label discountLabel = new Label("Discount:");
        discountLabel.setStyle("-fx-text-fill: white; -fx-font-size: 18px;");
        Region spacer1 = new Region();
        HBox.setHgrow(spacer1, Priority.ALWAYS);
        previewDiscountLabel = new Label("-$0.00");
        previewDiscountLabel.setStyle("-fx-text-fill: #fbbf24; -fx-font-size: 20px; -fx-font-weight: bold;");
        discountRow.getChildren().addAll(discountLabel, spacer1, previewDiscountLabel);

        Separator sep = new Separator();
        sep.setOpacity(0.1);

        HBox totalRow = new HBox();
        Label totalLabel = new Label("New Total:");
        totalLabel.setStyle("-fx-text-fill: white; -fx-font-size: 22px; -fx-font-weight: bold;");
        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);
        previewTotalLabel = new Label("$0.00");
        previewTotalLabel.setStyle("-fx-text-fill: #10b981; -fx-font-size: 24px; -fx-font-weight: 800;");
        totalRow.getChildren().addAll(totalLabel, spacer2, previewTotalLabel);

        previewCard.getChildren().addAll(previewTitle, discountRow, sep, totalRow);

        rightSection.getChildren().addAll(reasonHeading, reasonFlowPane, customReasonLabel, customReasonField, previewCard);

        mainContent.getChildren().addAll(leftSection, rightSection);
        return mainContent;
    }

    private Button createTabButton(String text, boolean mode) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setPrefHeight(50);
        btn.setOnAction(e -> {
            isPercentageMode = mode;
            inputBuffer.setLength(0);
            updateModeDisplay();
            updateCalculationPreview();
        });
        return btn;
    }

    private ToggleButton createReasonChip(String text) {
        ToggleButton chip = new ToggleButton(text);
        chip.getStyleClass().add("reason-chip");
        chip.setPrefHeight(40);
        chip.getStyleClass().add("reason-chip");
        
        chip.setOnAction(e -> {
            if (chip.isSelected()) {
                selectedReason = text;
                customReasonField.clear();
                deselectAllChipsExcept(chip);
            }
        });

        // Default selection
        if ("General Discount".equals(text)) {
            chip.setSelected(true);
        }

        return chip;
    }

    private void deselectAllChipsExcept(ToggleButton active) {
        for (javafx.scene.Node node : reasonFlowPane.getChildren()) {
            if (node instanceof ToggleButton chip && chip != active) {
                chip.setSelected(false);
            }
        }
    }

    private void deselectAllChips() {
        for (javafx.scene.Node node : reasonFlowPane.getChildren()) {
            if (node instanceof ToggleButton chip) {
                chip.setSelected(false);
            }
        }
    }

    private void updateModeDisplay() {
        String activeStyle = "-fx-background-color: white; -fx-text-fill: #2563eb; -fx-font-weight: 800; -fx-background-radius: 25; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 5, 0, 0, 2);";
        String inactiveStyle = "-fx-background-color: transparent; -fx-text-fill: #64748b; -fx-font-weight: 600; -fx-background-radius: 25;";

        percentTabBtn.setStyle(isPercentageMode ? activeStyle : inactiveStyle);
        amountTabBtn.setStyle(!isPercentageMode ? activeStyle : inactiveStyle);

        updateQuickPresets();
        updateInputDisplay();
    }

    private void updateQuickPresets() {
        quickPresetsContainer.getChildren().clear();
        String[] presets;
        if (isPercentageMode) {
            presets = new String[]{"5", "10", "15", "20", "25", "50"};
        } else {
            presets = new String[]{"5", "10", "20", "50", "100"};
        }

        for (String val : presets) {
            Button btn = new Button(isPercentageMode ? val + "%" : "$" + val);
            btn.setPrefSize(95, 50); // Increased width to prevent $100 truncation
            btn.setStyle("-fx-background-color: white; -fx-border-color: #cbd5e1; -fx-border-radius: 8; -fx-background-radius: 8; -fx-font-weight: bold; -fx-text-fill: #475569; -fx-cursor: hand; -fx-font-size: 16px;");
            
            // Hover effect
            btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: #f1f5f9; -fx-border-color: #2563eb; -fx-border-radius: 8; -fx-background-radius: 8; -fx-font-weight: bold; -fx-text-fill: #2563eb; -fx-cursor: hand; -fx-font-size: 16px;"));
            btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: white; -fx-border-color: #cbd5e1; -fx-border-radius: 8; -fx-background-radius: 8; -fx-font-weight: bold; -fx-text-fill: #475569; -fx-cursor: hand; -fx-font-size: 16px;"));
            
            btn.setOnAction(e -> {
                inputBuffer.setLength(0);
                inputBuffer.append(val);
                updateInputDisplay();
                updateCalculationPreview();
            });
            quickPresetsContainer.getChildren().add(btn);
        }
    }

    private void handleKeypadInput(String key) {
        if ("C".equals(key)) {
            inputBuffer.setLength(0);
        } else if ("⌫".equals(key)) {
            if (inputBuffer.length() > 0) {
                inputBuffer.setLength(inputBuffer.length() - 1);
            }
        } else if (".".equals(key)) {
            if (!inputBuffer.toString().contains(".")) {
                if (inputBuffer.length() == 0) inputBuffer.append("0");
                inputBuffer.append(".");
            }
        } else if ("⏎".equals(key) || "Enter".equals(key)) {
            // Handled by enter listener, ignore here to prevent adding to buffer
            return;
        } else {
            // Prevent too many digits
            if (inputBuffer.length() < 7) {
                inputBuffer.append(key);
            }
        }
        updateInputDisplay();
        updateCalculationPreview();
    }

    private void updateInputDisplay() {
        String val = inputBuffer.length() == 0 ? "0" : inputBuffer.toString();
        inputDisplayLabel.setText(isPercentageMode ? val + "%" : "$" + val);
    }

    private void updateCalculationPreview() {
        BigDecimal value = inputBuffer.length() == 0 ? BigDecimal.ZERO : new BigDecimal(inputBuffer.toString());
        BigDecimal discountAmount;

        if (isPercentageMode) {
            discountAmount = subtotal.multiply(value).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        } else {
            discountAmount = value;
        }

        BigDecimal newTotal = subtotal.subtract(discountAmount).max(BigDecimal.ZERO);

        previewDiscountLabel.setText("-" + currencyFormat.format(discountAmount));
        previewTotalLabel.setText(currencyFormat.format(newTotal));
    }

    private HBox createFooter() {
        HBox footer = new HBox(20);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(30, 0, 0, 0));

        Button cancelButton = new Button("Discard");
        cancelButton.setPrefSize(200, 60);
        cancelButton.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #64748b; -fx-font-weight: bold; -fx-font-size: 18px; -fx-background-radius: 12; -fx-cursor: hand;");
        cancelButton.setOnAction(e -> onCancel.run());

        Button applyButton = new Button("Apply Discount");
        applyButton.setPrefSize(300, 60);
        applyButton.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 20px; -fx-background-radius: 12; -fx-cursor: hand; -fx-effect: dropshadow(gaussian, rgba(37, 99, 235, 0.3), 10, 0, 0, 4);");
        applyButton.setOnAction(e -> {
            playPressAnimation(applyButton);
            handleApply();
        });

        footer.getChildren().addAll(cancelButton, applyButton);
        return footer;
    }

    private void handleApply() {
        try {
            BigDecimal value = inputBuffer.length() == 0 ? BigDecimal.ZERO : new BigDecimal(inputBuffer.toString());

            if (value.compareTo(BigDecimal.ZERO) <= 0) {
                ToastNotification.showWarning("Discount value must be greater than zero", getScene().getWindow());
                return;
            }

            BigDecimal discountAmount;
            BigDecimal discountPercent = BigDecimal.ZERO;

            if (isPercentageMode) {
                if (value.compareTo(new BigDecimal("100")) > 0) {
                    ToastNotification.showWarning("Percentage cannot exceed 100%", getScene().getWindow());
                    return;
                }
                discountPercent = value;
                discountAmount = subtotal.multiply(value).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            } else {
                if (value.compareTo(subtotal) > 0) {
                    ToastNotification.showWarning("Discount cannot exceed subtotal", getScene().getWindow());
                    return;
                }
                discountAmount = value;
            }

            // Manager approval check
            boolean requiresApproval = false;
            if (isPercentageMode && value.compareTo(PERCENTAGE_THRESHOLD) > 0) {
                requiresApproval = true;
            } else if (!isPercentageMode && discountAmount.compareTo(AMOUNT_THRESHOLD) > 0) {
                requiresApproval = true;
            }

            DiscountResult result = new DiscountResult();
            result.discountAmount = discountAmount;
            result.discountPercent = discountPercent;
            result.discountReason = selectedReason;
            result.isPercentage = isPercentageMode;
            result.requiresApproval = requiresApproval;

            if (requiresApproval) {
                ManagerAuthDialog authDialog = new ManagerAuthDialog();
                ManagerAuthDialog.ManagerAuthResult authResult = authDialog.showAndWait().orElse(null);

                if (authResult == null || !authResult.approved) {
                    logger.info("Manager approval denied or cancelled for discount");
                    return;
                }

                result.approvedBy = authResult.managerName;
                result.approvedByUsername = authResult.managerUsername;
            }

            onApply.accept(result);

        } catch (NumberFormatException e) {
            ToastNotification.showError("Invalid number format", getScene().getWindow());
        } catch (Exception e) {
            logger.error("Error applying discount", e);
            ToastNotification.showError("Failed to apply discount: " + e.getMessage(), getScene().getWindow());
        }
    }

    private void playPressAnimation(Button button) {
        ScaleTransition st = new ScaleTransition(Duration.millis(100), button);
        st.setFromX(1.0);
        st.setFromY(1.0);
        st.setToX(0.95);
        st.setToY(0.95);
        st.setAutoReverse(true);
        st.setCycleCount(2);
        st.play();
    }

    /**
     * Data result for applied discounts
     */
    public static class DiscountResult {
        public BigDecimal discountAmount = BigDecimal.ZERO;
        public BigDecimal discountPercent = BigDecimal.ZERO;
        public String discountReason = "";
        public boolean isPercentage = false;
        public boolean requiresApproval = false;
        public String approvedBy;
        public String approvedByUsername;
    }
}
