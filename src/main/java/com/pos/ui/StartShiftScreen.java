package com.pos.ui;

import com.pos.api.dto.ShiftResponse;
import com.pos.service.ShiftService;
import com.pos.service.UserAuthService;
import com.pos.ui.components.NumericKeypad;
import com.pos.ui.components.ToastNotification;
import com.pos.util.ErrorHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.text.DecimalFormat;

/**
 * Premium Start Shift screen with touch-optimized cash entry.
 */
public class StartShiftScreen extends BorderPane {
    private static final Logger logger = LoggerFactory.getLogger(StartShiftScreen.class);
    private static final DecimalFormat CURRENCY_FORMAT = new DecimalFormat("#,##0.00");

    private final Runnable onShiftStarted;
    private final ShiftService shiftService;
    private final UserAuthService authService;

    private Label cashDisplayLabel;
    private TextField registerIdField;
    private TextArea noteField;
    private StringBuilder currentAmount = new StringBuilder();
    private BigDecimal amount = BigDecimal.ZERO;

    public StartShiftScreen(Runnable onShiftStarted) {
        this.onShiftStarted = onShiftStarted;
        this.shiftService = ShiftService.getInstance();
        this.authService = UserAuthService.getInstance();
        
        this.amount = shiftService.getPreviousShiftActualCash();
        if (this.amount.compareTo(BigDecimal.ZERO) > 0) {
            this.currentAmount.append(this.amount.toPlainString());
        }
        
        initializeUI();
    }

    private void initializeUI() {
        getStyleClass().add("start-shift-root");
        setStyle("-fx-background-color: #f8fafc;");

        // --- Header ---
        HBox header = createHeader();
        setTop(header);

        // --- Left Sidebar (Details) ---
        VBox sidebar = createSidebar();
        setLeft(sidebar);

        // --- Main Content (Cash Entry) ---
        VBox mainContent = createMainContent();
        setCenter(mainContent);

        // --- Footer (Action) ---
        HBox footer = createFooter();
        setBottom(footer);
    }

    private HBox createHeader() {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(20, 40, 20, 40));
        header.setStyle("-fx-background-color: white; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.05), 5, 0, 0, 0);");

        VBox titleBox = new VBox(4);
        Label title = new Label("Start Cash Session");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");
        Label subtitle = new Label("Initialize your drawer and verify opening balance");
        subtitle.setStyle("-fx-font-size: 14px; -fx-text-fill: #64748b;");
        
        titleBox.getChildren().addAll(title, subtitle);
        header.getChildren().add(titleBox);

        return header;
    }

    private VBox createSidebar() {
        VBox sidebar = new VBox(30);
        sidebar.setPadding(new Insets(40));
        sidebar.setPrefWidth(400);
        sidebar.setStyle("-fx-background-color: white; -fx-border-color: #e2e8f0; -fx-border-width: 0 1 0 0;");

        // User Info Section
        VBox userSection = new VBox(10);
        Label userHeader = new Label("CASHIER");
        userHeader.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #94a3b8; -fx-letter-spacing: 1px;");
        
        HBox userCard = new HBox(15);
        userCard.setAlignment(Pos.CENTER_LEFT);
        userCard.setPadding(new Insets(15));
        userCard.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 12;");
        
        Circle avatar = new Circle(20, Color.web("#3b82f6"));
        Label initials = new Label(getInitials(authService.getCurrentUserName()));
        initials.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        StackPane avatarStack = new StackPane(avatar, initials);
        
        VBox userInfo = new VBox(2);
        Label userName = new Label(authService.getCurrentUserName());
        userName.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");
        Label userRole = new Label(authService.getCurrentUserRole());
        userRole.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");
        
        userInfo.getChildren().addAll(userName, userRole);
        userCard.getChildren().addAll(avatarStack, userInfo);
        userSection.getChildren().addAll(userHeader, userCard);

        // Register ID Section
        VBox registerSection = new VBox(10);
        Label registerHeader = new Label("REGISTER ID");
        registerHeader.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #94a3b8; -fx-letter-spacing: 1px;");
        
        registerIdField = new TextField("REG001");
        registerIdField.setPrefHeight(50);
        registerIdField.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 8; -fx-border-color: transparent; -fx-font-size: 16px; -fx-padding: 0 15;");
        registerSection.getChildren().addAll(registerHeader, registerIdField);

        // Notes Section
        VBox notesSection = new VBox(10);
        Label notesHeader = new Label("OPENING NOTE");
        notesHeader.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #94a3b8; -fx-letter-spacing: 1px;");
        
        noteField = new TextArea();
        noteField.setPromptText("Optional notes about this shift...");
        noteField.setWrapText(true);
        noteField.setPrefRowCount(4);
        noteField.setStyle("-fx-control-inner-background: #f1f5f9; -fx-background-color: transparent; -fx-background-radius: 8; -fx-border-color: transparent; -fx-font-size: 14px;");
        notesSection.getChildren().addAll(notesHeader, noteField);

        sidebar.getChildren().addAll(userSection, registerSection, notesSection);
        return sidebar;
    }

    private VBox createMainContent() {
        VBox main = new VBox(40);
        main.setAlignment(Pos.CENTER);
        main.setPadding(new Insets(40));

        // Display Area
        VBox displayArea = new VBox(10);
        displayArea.setAlignment(Pos.CENTER);
        Label displayLabel = new Label("OPENING CASH AMOUNT");
        displayLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #64748b; -fx-letter-spacing: 2px;");
        
        cashDisplayLabel = new Label("$" + CURRENCY_FORMAT.format(amount));
        cashDisplayLabel.setStyle("-fx-font-size: 72px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        
        displayArea.getChildren().addAll(displayLabel, cashDisplayLabel);

        // Quick Actions & Keypad Layout
        HBox entryContainer = new HBox(60);
        entryContainer.setAlignment(Pos.CENTER);

        // Quick Actions
        VBox quickActions = new VBox(15);
        quickActions.setAlignment(Pos.CENTER);
        Label quickLabel = new Label("QUICK ADD");
        quickLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #94a3b8;");
        
        GridPane quickGrid = new GridPane();
        quickGrid.setHgap(15);
        quickGrid.setVgap(15);
        
        String[] presets = {"50", "100", "200", "500", "1000", "2000"};
        for (int i = 0; i < presets.length; i++) {
            Button btn = createPresetButton("$" + presets[i]);
            final String amountStr = presets[i];
            btn.setOnAction(e -> setAmount(amountStr));
            quickGrid.add(btn, i % 2, i / 2);
        }
        
        quickActions.getChildren().addAll(quickLabel, quickGrid);

        // Keypad
        NumericKeypad keypad = new NumericKeypad(false, false, false);
        keypad.setPrefSize(350, 400);
        keypad.setListener(this::handleKeypress);

        entryContainer.getChildren().addAll(quickActions, keypad);
        main.getChildren().addAll(displayArea, entryContainer);

        return main;
    }

    private Button createPresetButton(String text) {
        Button btn = new Button(text);
        btn.setPrefSize(120, 80);
        btn.setStyle("-fx-background-color: white; -fx-border-color: #e2e8f0; -fx-border-radius: 12; -fx-background-radius: 12; -fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #1e293b; -fx-cursor: hand;");
        
        btn.setOnMouseEntered(e -> btn.setStyle(btn.getStyle() + "-fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: white; -fx-border-color: #e2e8f0; -fx-border-radius: 12; -fx-background-radius: 12; -fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #1e293b; -fx-cursor: hand;"));
        
        return btn;
    }

    private HBox createFooter() {
        HBox footer = new HBox();
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(30, 40, 40, 40));
        footer.setStyle("-fx-background-color: white; -fx-border-color: #e2e8f0; -fx-border-width: 1 0 0 0;");

        Button startButton = new Button("START SHIFT");
        startButton.setPrefWidth(400);
        startButton.setPrefHeight(60);
        startButton.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-size: 20px; -fx-font-weight: bold; -fx-background-radius: 30; -fx-cursor: hand;");
        
        startButton.setOnMouseEntered(e -> startButton.setStyle(startButton.getStyle() + "-fx-background-color: #2563eb;"));
        startButton.setOnMouseExited(e -> startButton.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-size: 20px; -fx-font-weight: bold; -fx-background-radius: 30; -fx-cursor: hand;"));
        
        startButton.setOnAction(e -> handleStartShift());
        
        footer.getChildren().add(startButton);
        return footer;
    }

    private void handleKeypress(String key) {
        if ("C".equals(key)) {
            currentAmount.setLength(0);
        } else if ("⌫".equals(key)) {
            if (currentAmount.length() > 0) {
                currentAmount.deleteCharAt(currentAmount.length() - 1);
            }
        } else if (key.matches("\\d")) {
            if (currentAmount.length() < 9) {
                currentAmount.append(key);
            }
        }
        
        updateDisplay();
    }

    private void setAmount(String amt) {
        currentAmount.setLength(0);
        currentAmount.append(amt);
        updateDisplay();
    }

    private void updateDisplay() {
        if (currentAmount.length() == 0) {
            amount = BigDecimal.ZERO;
            cashDisplayLabel.setText("$0.00");
        } else {
            amount = new BigDecimal(currentAmount.toString());
            cashDisplayLabel.setText("$" + CURRENCY_FORMAT.format(amount));
        }
    }

    private void handleStartShift() {
        String registerId = registerIdField.getText().trim();
        if (registerId.isEmpty()) {
            ToastNotification.showWarning("Register ID is required", null);
            return;
        }

        // Action
        new Thread(() -> {
            try {
                String cashierName = authService.getCurrentUserName();
                ShiftResponse.ShiftData newShift = shiftService.startShift(
                        cashierName,
                        registerId,
                        amount,
                        noteField.getText().trim());
                
                logger.info("Cash session opened via StartShiftScreen: {}", newShift.id);
                
                javafx.application.Platform.runLater(() -> {
                    ToastNotification.showSuccess("Shift started successfully!", null);
                    if (onShiftStarted != null) {
                        onShiftStarted.run();
                    }
                });
            } catch (Exception e) {
                logger.error("Failed to open cash session", e);
                javafx.application.Platform.runLater(() -> {
                    ErrorHandler.handleError(e, "Failed to start shift", null, false);
                });
            }
        }).start();
    }

    private String getInitials(String name) {
        if (name == null || name.isEmpty()) return "??";
        String[] parts = name.split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase();
    }
}
