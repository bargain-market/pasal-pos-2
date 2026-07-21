package com.pos.ui;

import com.pos.model.SaleItem;
import com.pos.service.SettingsService;
import com.pos.ui.components.NumericKeypad;
import com.pos.ui.components.ToastNotification;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.function.Consumer;

/**
 * A dedicated screen for editing a sale item's quantity and price.
 * Replaces the old ItemEditorDialog with a premium, full-screen experience.
 */
public class EditItemScreen extends BorderPane {
    private static final Logger logger = LoggerFactory.getLogger(EditItemScreen.class);
    private static final String BACK_ICON = "\u2190";
    private static final String BACKSPACE_KEY = "\u232B";
    private static final String ENTER_KEY = "\u21B5";
    private static final String MULTIPLY_SYMBOL = "\u00D7";
    private static final String HALF_SYMBOL = "\u00BD";
    private static final String DISPLAY_BASE_STYLE =
            "-fx-font-size: 56px; -fx-font-weight: 800; -fx-padding: 8 32; "
                    + "-fx-background-color: #f1f5f9; -fx-background-radius: 12; "
                    + "-fx-min-width: 260; -fx-alignment: center;";
    private static final String QUICK_BUTTON_BASE_STYLE =
            "-fx-background-color: white; -fx-border-color: #e2e8f0; -fx-border-radius: 8; "
                    + "-fx-background-radius: 8; -fx-font-weight: bold; -fx-text-fill: #475569; "
                    + "-fx-cursor: hand; -fx-font-size: 15px; -fx-alignment: center;";
    private static final String QUICK_BUTTON_HOVER_STYLE =
            "-fx-background-color: #f8fafc; -fx-border-color: #2563eb; -fx-border-radius: 8; "
                    + "-fx-background-radius: 8; -fx-font-weight: bold; -fx-text-fill: #2563eb; "
                    + "-fx-cursor: hand; -fx-font-size: 15px; -fx-alignment: center;";

    public enum EditMode {
        QUANTITY,
        PRICE
    }

    private final SaleItem item;
    private final Runnable onBack;
    private final Consumer<SaleItem> onApply;
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

    private EditMode currentMode;
    private final StringBuilder qtyBuffer = new StringBuilder();
    private final StringBuilder priceBuffer = new StringBuilder();

    private Label itemDetailsLabel;
    private Label modeHintLabel;
    private Label inputDisplayLabel;
    private Button qtyTabBtn;
    private Button priceTabBtn;
    private VBox quickActionsContainer;

    public EditItemScreen(SaleItem item, EditMode initialMode, Consumer<SaleItem> onApply, Runnable onBack) {
        this.item = item;
        this.currentMode = initialMode;
        this.onApply = onApply;
        this.onBack = onBack;

        initializeUI();
    }

    private void initializeUI() {
        getStyleClass().add("edit-item-screen");
        setPadding(new Insets(20, 24, 20, 24));
        setStyle("-fx-background-color: #f8fafc;");

        setTop(createHeader());

        VBox content = createContent();
        HBox footer = createFooter();
        VBox mainSection = new VBox(18, content, footer);
        mainSection.setAlignment(Pos.TOP_CENTER);
        mainSection.setPadding(new Insets(8, 0, 0, 0));
        setCenter(mainSection);

        updateModeDisplay();
    }

    private VBox createHeader() {
        VBox header = new VBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 16, 0));

        HBox titleRow = new HBox(15);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Button backBtn = new Button(BACK_ICON);
        backBtn.setStyle("-fx-font-size: 22px; -fx-background-color: transparent; -fx-text-fill: #64748b; -fx-cursor: hand; -fx-padding: 0 10 0 0;");
        backBtn.setOnAction(e -> onBack.run());

        Label title = new Label("Edit Item");
        title.setStyle("-fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        titleRow.getChildren().addAll(backBtn, title);

        Label itemNameLabel = new Label(item.getProductName());
        itemNameLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: 600; -fx-text-fill: #2563eb;");

        itemDetailsLabel = new Label();
        updateItemDetailsLabel();
        itemDetailsLabel.setStyle("-fx-font-size: 16px; -fx-text-fill: #64748b;");

        header.getChildren().addAll(titleRow, itemNameLabel, itemDetailsLabel);
        return header;
    }

    private void updateItemDetailsLabel() {
        itemDetailsLabel.setText(String.format("Current: %d units @ %s each",
                item.getQuantity(), currencyFormat.format(item.getPrice("CASH"))));
    }

    private VBox createContent() {
        VBox content = new VBox(0);
        content.setAlignment(Pos.TOP_CENTER);
        content.setMaxWidth(760);
        content.setPadding(new Insets(18));
        content.setStyle("-fx-background-color: white; -fx-background-radius: 16; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 15, 0, 0, 6);");

        HBox tabBar = new HBox(0);
        tabBar.setAlignment(Pos.CENTER);
        tabBar.setStyle("-fx-background-color: #f1f5f9; -fx-background-radius: 30; -fx-padding: 4;");
        tabBar.setMaxWidth(360);

        qtyTabBtn = createTabButton("QUANTITY", EditMode.QUANTITY);
        priceTabBtn = createTabButton("PRICE", EditMode.PRICE);

        tabBar.getChildren().addAll(qtyTabBtn, priceTabBtn);
        HBox.setHgrow(qtyTabBtn, Priority.ALWAYS);
        HBox.setHgrow(priceTabBtn, Priority.ALWAYS);

        VBox displayArea = new VBox(15);
        displayArea.setAlignment(Pos.CENTER);
        displayArea.setPadding(new Insets(28, 16, 16, 16));

        modeHintLabel = new Label("Enter new quantity");
        modeHintLabel.setStyle("-fx-font-size: 16px; -fx-text-fill: #64748b;");

        inputDisplayLabel = new Label("0");
        inputDisplayLabel.setStyle(DISPLAY_BASE_STYLE + "-fx-text-fill: #1e293b;");

        displayArea.getChildren().addAll(modeHintLabel, inputDisplayLabel);

        HBox interactionArea = new HBox(24);
        interactionArea.setAlignment(Pos.CENTER);
        interactionArea.setPadding(new Insets(12, 10, 6, 10));

        NumericKeypad keypad = new NumericKeypad(true, true, false);
        keypad.setListener(this::handleKeypadInput);
        keypad.setEnterListener(this::applyChanges);
        keypad.setPrefSize(320, 330);

        quickActionsContainer = createQuickActions();
        interactionArea.getChildren().addAll(keypad, quickActionsContainer);

        content.getChildren().addAll(tabBar, displayArea, interactionArea);
        return content;
    }

    private Button createTabButton(String text, EditMode mode) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setPrefHeight(50);
        btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #64748b; -fx-font-weight: bold; -fx-background-radius: 25; -fx-cursor: hand;");
        btn.setOnAction(e -> switchMode(mode));
        return btn;
    }

    private VBox createQuickActions() {
        VBox container = new VBox(15);
        container.setAlignment(Pos.TOP_CENTER);
        container.setMinWidth(132);

        Label label = new Label("QUICK ADJUST");
        label.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #94a3b8; -fx-letter-spacing: 1px;");

        VBox buttons = new VBox(10);
        buttons.setAlignment(Pos.CENTER);
        updateQuickActionButtons(buttons);

        container.getChildren().addAll(label, buttons);
        return container;
    }

    private void updateQuickActionButtons(VBox container) {
        container.getChildren().clear();
        if (currentMode == EditMode.QUANTITY) {
            container.getChildren().addAll(
                    createQuickButton("+1", () -> quickAdjustQty(1)),
                    createQuickButton("-1", () -> quickAdjustQty(-1)),
                    createQuickButton("+5", () -> quickAdjustQty(5)),
                    createQuickButton("2" + MULTIPLY_SYMBOL, () -> quickAdjustQty(item.getQuantity())),
                    createQuickButton("Clear", () -> {
                        qtyBuffer.setLength(0);
                        updateInputDisplay();
                    }));
        } else {
            container.getChildren().addAll(
                    createQuickButton("+$1", () -> adjustPrice(new BigDecimal("1"))),
                    createQuickButton("+$5", () -> adjustPrice(new BigDecimal("5"))),
                    createQuickButton("-10%", () -> adjustPricePercent(new BigDecimal("0.10"))),
                    createQuickButton("-20%", () -> adjustPricePercent(new BigDecimal("0.20"))),
                    createQuickButton(HALF_SYMBOL + " Price", this::halfPrice),
                    createQuickButton("Reset", () -> {
                        priceBuffer.setLength(0);
                        updateInputDisplay();
                    }));
        }
    }

    private Button createQuickButton(String text, Runnable action) {
        Button btn = new Button(text);
        btn.setPrefSize(116, 44);
        btn.setWrapText(false);
        btn.setStyle(QUICK_BUTTON_BASE_STYLE);
        btn.setOnAction(e -> action.run());
        btn.setOnMouseEntered(e -> btn.setStyle(QUICK_BUTTON_HOVER_STYLE));
        btn.setOnMouseExited(e -> btn.setStyle(QUICK_BUTTON_BASE_STYLE));
        return btn;
    }

    private HBox createFooter() {
        HBox footer = new HBox(20);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(0));

        Button cancelButton = new Button("Discard Changes");
        cancelButton.setPrefSize(180, 50);
        cancelButton.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #64748b; -fx-font-weight: bold; -fx-font-size: 16px; -fx-background-radius: 12; -fx-cursor: hand;");
        cancelButton.setOnAction(e -> onBack.run());

        Button applyBtn = new Button("Apply Changes");
        applyBtn.setPrefSize(220, 50);
        applyBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 18px; -fx-background-radius: 12; -fx-cursor: hand; -fx-effect: dropshadow(gaussian, rgba(37, 99, 235, 0.3), 10, 0, 0, 4);");
        applyBtn.setOnAction(e -> applyChanges());

        footer.getChildren().addAll(cancelButton, applyBtn);
        return footer;
    }

    private void switchMode(EditMode mode) {
        if (currentMode == mode) {
            return;
        }
        currentMode = mode;
        updateModeDisplay();

        if (quickActionsContainer != null && quickActionsContainer.getChildren().size() > 1) {
            VBox buttons = (VBox) quickActionsContainer.getChildren().get(1);
            updateQuickActionButtons(buttons);
        }
    }

    private void updateModeDisplay() {
        boolean isQty = currentMode == EditMode.QUANTITY;

        String activeTabStyle = "-fx-background-color: white; -fx-text-fill: #2563eb; -fx-font-weight: 800; -fx-font-size: 14px; -fx-background-radius: 25; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 5, 0, 0, 2);";
        String inactiveTabStyle = "-fx-background-color: transparent; -fx-text-fill: #64748b; -fx-font-weight: 600; -fx-font-size: 14px; -fx-background-radius: 25;";

        qtyTabBtn.setStyle(isQty ? activeTabStyle : inactiveTabStyle);
        priceTabBtn.setStyle(isQty ? inactiveTabStyle : activeTabStyle);
        modeHintLabel.setText(isQty ? "Enter new quantity" : "Enter new price");

        updateInputDisplay();
    }

    private void handleKeypadInput(String key) {
        StringBuilder buffer = currentMode == EditMode.QUANTITY ? qtyBuffer : priceBuffer;

        if ("C".equals(key)) {
            buffer.setLength(0);
        } else if (BACKSPACE_KEY.equals(key)) {
            if (buffer.length() > 0) {
                buffer.setLength(buffer.length() - 1);
            }
        } else if (ENTER_KEY.equals(key) || "ENTER".equals(key)) {
            return;
        } else if (".".equals(key)) {
            if (currentMode == EditMode.PRICE && !buffer.toString().contains(".")) {
                if (buffer.length() == 0) {
                    buffer.append("0");
                }
                buffer.append(".");
            }
        } else if (buffer.length() < 10) {
            buffer.append(key);
        }
        updateInputDisplay();
    }

    private void updateInputDisplay() {
        StringBuilder buffer = currentMode == EditMode.QUANTITY ? qtyBuffer : priceBuffer;

        if (buffer.length() == 0) {
            inputDisplayLabel.setText(currentMode == EditMode.QUANTITY
                    ? String.valueOf(item.getQuantity())
                    : currencyFormat.format(item.getPrice("CASH")));
            inputDisplayLabel.setStyle(DISPLAY_BASE_STYLE + "-fx-text-fill: #94a3b8;");
        } else {
            String val = buffer.toString();
            inputDisplayLabel.setText(currentMode == EditMode.PRICE ? "$" + val : val);
            inputDisplayLabel.setStyle(DISPLAY_BASE_STYLE + "-fx-text-fill: #1e293b;");
        }
    }

    private void quickAdjustQty(int delta) {
        if (currentMode != EditMode.QUANTITY) {
            switchMode(EditMode.QUANTITY);
        }

        int base = qtyBuffer.length() > 0 ? Integer.parseInt(qtyBuffer.toString()) : item.getQuantity();
        int newQty = base + delta;

        if (newQty > 0 && newQty < 9999) {
            qtyBuffer.setLength(0);
            qtyBuffer.append(newQty);
            updateInputDisplay();
        }
    }

    private void halfPrice() {
        adjustPricePercent(new BigDecimal("0.50"));
    }

    private void adjustPrice(BigDecimal delta) {
        if (currentMode != EditMode.PRICE) {
            switchMode(EditMode.PRICE);
        }

        BigDecimal base = priceBuffer.length() > 0 ? new BigDecimal(priceBuffer.toString()) : item.getPrice("CASH");
        BigDecimal result = base.add(delta);

        if (result.compareTo(BigDecimal.ZERO) >= 0) {
            priceBuffer.setLength(0);
            priceBuffer.append(result.setScale(2, RoundingMode.HALF_UP).toPlainString());
            updateInputDisplay();
        }
    }

    private void adjustPricePercent(BigDecimal discountPercent) {
        if (currentMode != EditMode.PRICE) {
            switchMode(EditMode.PRICE);
        }

        BigDecimal base = priceBuffer.length() > 0 ? new BigDecimal(priceBuffer.toString()) : item.getPrice("CASH");
        BigDecimal result = base.multiply(BigDecimal.ONE.subtract(discountPercent));

        if (result.compareTo(BigDecimal.ZERO) >= 0) {
            priceBuffer.setLength(0);
            priceBuffer.append(result.setScale(2, RoundingMode.HALF_UP).toPlainString());
            updateInputDisplay();
        }
    }

    private void applyChanges() {
        try {
            boolean changed = false;

            if (qtyBuffer.length() > 0) {
                int newQty = Integer.parseInt(qtyBuffer.toString());
                item.setQuantity(newQty);
                changed = true;
            }

            if (priceBuffer.length() > 0) {
                BigDecimal newPrice = new BigDecimal(priceBuffer.toString());
                SettingsService.CardSurchargeSettings cardSettings = SettingsService.getInstance().getCardSurchargeSettings();
                Double surcharge = cardSettings.enabled ? cardSettings.getPercentOrDefault() : null;
                item.setManualCashPrice(newPrice, surcharge);
                changed = true;
            }

            if (changed) {
                ToastNotification.showSuccess("Item updated", getScene().getWindow());
                onApply.accept(item);
            } else {
                onBack.run();
            }
        } catch (Exception e) {
            logger.error("Error applying changes", e);
            ToastNotification.showError("Invalid input", getScene().getWindow());
        }
    }
}
