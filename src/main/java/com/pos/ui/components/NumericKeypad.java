package com.pos.ui.components;

import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Reusable numeric keypad component for touch-screen input.
 */
public class NumericKeypad extends VBox {
    public static final String BACKSPACE_KEY = "\u232B";
    public static final String ENTER_KEY = "\u21B5";
    public static final String QTY_KEY = "QTY";

    public interface KeypadListener {
        void onKeyPressed(String key);
    }

    public interface EnterListener {
        void onEnterPressed();
    }

    private KeypadListener listener;
    private EnterListener enterListener;
    private boolean allowDecimal;
    private boolean showEnterButton;
    private boolean showPinIndicator;
    private boolean showQtyButton;
    private int maxPinLength = 6;
    private int currentPinLength = 0;
    private HBox pinIndicatorBox;
    private GridPane keypadGrid;

    public NumericKeypad() {
        this(true, false, false);
    }

    public NumericKeypad(boolean allowDecimal) {
        this(allowDecimal, false, false);
    }

    public NumericKeypad(boolean allowDecimal, boolean showEnterButton, boolean showPinIndicator) {
        this.allowDecimal = allowDecimal;
        this.showEnterButton = showEnterButton;
        this.showPinIndicator = showPinIndicator;
        initializeKeypad();
    }

    public void setListener(KeypadListener listener) {
        this.listener = listener;
    }

    public void setEnterListener(EnterListener enterListener) {
        this.enterListener = enterListener;
    }

    public void setShowEnterButton(boolean show) {
        this.showEnterButton = show;
        rebuildKeypad();
    }

    public void setShowPinIndicator(boolean show) {
        this.showPinIndicator = show;
        rebuildKeypad();
    }

    /**
     * When enabled, the (unused) decimal-point key in the decimal layout is
     * replaced with a QTY key. Pressing it emits {@link #QTY_KEY} so the host
     * screen can treat the current entry as a quantity multiplier.
     */
    public void setShowQtyButton(boolean show) {
        this.showQtyButton = show;
        rebuildKeypad();
    }

    public void setMaxPinLength(int length) {
        this.maxPinLength = length;
        if (showPinIndicator) {
            updatePinIndicator();
        }
    }

    public void updatePinLength(int length) {
        this.currentPinLength = Math.min(length, maxPinLength);
        if (showPinIndicator && pinIndicatorBox != null) {
            updatePinIndicator();
        }
    }

    private void rebuildKeypad() {
        getChildren().clear();
        initializeKeypad();
    }

    private void initializeKeypad() {
        getStyleClass().add("numeric-keypad");
        setAlignment(Pos.TOP_CENTER);
        setSpacing(6);
        setPadding(new Insets(6));
        setFillWidth(true);

        if (showPinIndicator) {
            pinIndicatorBox = new HBox(12);
            pinIndicatorBox.setAlignment(Pos.CENTER);
            pinIndicatorBox.getStyleClass().add("pin-indicator-container");
            pinIndicatorBox.setPadding(new Insets(0, 0, 8, 0));
            updatePinIndicator();
            getChildren().add(pinIndicatorBox);
        }

        keypadGrid = new GridPane();
        keypadGrid.getStyleClass().add("keypad-grid");
        keypadGrid.setHgap(6);
        keypadGrid.setVgap(6);
        keypadGrid.setAlignment(Pos.CENTER);
        keypadGrid.setMaxWidth(Double.MAX_VALUE);
        keypadGrid.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(keypadGrid, Priority.ALWAYS);

        if (allowDecimal) {
            buildDecimalLayout();
        } else {
            buildPinLayout();
        }

        getChildren().add(keypadGrid);
    }

    private void buildDecimalLayout() {
        addKeyButton("7", 0, 0);
        addKeyButton("8", 1, 0);
        addKeyButton("9", 2, 0);
        addKeyButton(BACKSPACE_KEY, 3, 0);

        addKeyButton("4", 0, 1);
        addKeyButton("5", 1, 1);
        addKeyButton("6", 2, 1);
        addKeyButton("C", 3, 1);

        addKeyButton("1", 0, 2);
        addKeyButton("2", 1, 2);
        addKeyButton("3", 2, 2);

        if (showEnterButton) {
            Button enterBtn = createKeypadButton(ENTER_KEY);
            enterBtn.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            GridPane.setRowSpan(enterBtn, 2);
            GridPane.setFillHeight(enterBtn, true);
            GridPane.setFillWidth(enterBtn, true);
            GridPane.setHgrow(enterBtn, Priority.ALWAYS);
            GridPane.setVgrow(enterBtn, Priority.ALWAYS);
            keypadGrid.add(enterBtn, 3, 2);
        }

        addKeyButton("0", 0, 3);
        addKeyButton("00", 1, 3);
        // The decimal point is a no-op (cents are handled automatically), so the
        // host screen can opt to reuse that slot for a QTY multiplier key.
        addKeyButton(showQtyButton ? QTY_KEY : ".", 2, 3);

        for (int i = 0; i < 4; i++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(25);
            col.setFillWidth(true);
            keypadGrid.getColumnConstraints().add(col);
        }

        for (int i = 0; i < 4; i++) {
            RowConstraints row = new RowConstraints();
            row.setPercentHeight(25);
            row.setFillHeight(true);
            keypadGrid.getRowConstraints().add(row);
        }
    }

    private void buildPinLayout() {
        String[][] keys = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"C", "0", BACKSPACE_KEY}
        };

        for (int row = 0; row < keys.length; row++) {
            for (int col = 0; col < keys[row].length; col++) {
                addKeyButton(keys[row][col], col, row);
            }
        }

        if (showEnterButton) {
            Button enterBtn = createKeypadButton("ENTER");
            enterBtn.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            GridPane.setFillWidth(enterBtn, true);
            GridPane.setFillHeight(enterBtn, true);
            GridPane.setHgrow(enterBtn, Priority.ALWAYS);
            GridPane.setVgrow(enterBtn, Priority.ALWAYS);
            keypadGrid.add(enterBtn, 0, 4, 3, 1);
        }

        for (int i = 0; i < 3; i++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(33.33);
            col.setFillWidth(true);
            keypadGrid.getColumnConstraints().add(col);
        }

        int rowCount = showEnterButton ? 5 : 4;
        for (int i = 0; i < rowCount; i++) {
            RowConstraints row = new RowConstraints();
            row.setPercentHeight(100.0 / rowCount);
            row.setFillHeight(true);
            keypadGrid.getRowConstraints().add(row);
        }
    }

    private void addKeyButton(String key, int col, int row) {
        Button btn = createKeypadButton(key);
        btn.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        GridPane.setFillWidth(btn, true);
        GridPane.setFillHeight(btn, true);
        GridPane.setHgrow(btn, Priority.ALWAYS);
        GridPane.setVgrow(btn, Priority.ALWAYS);
        keypadGrid.add(btn, col, row);
    }

    private void updatePinIndicator() {
        if (pinIndicatorBox == null) {
            return;
        }

        pinIndicatorBox.getChildren().clear();
        for (int i = 0; i < maxPinLength; i++) {
            Label dot = new Label();
            dot.getStyleClass().add("pin-dot");
            dot.getStyleClass().add(i < currentPinLength ? "pin-dot-filled" : "pin-dot-empty");
            pinIndicatorBox.getChildren().add(dot);
        }
    }

    private Button createKeypadButton(String key) {
        Button keyBtn = new Button(key);
        keyBtn.getStyleClass().add("keypad-button");
        keyBtn.setMinSize(10, 10);
        keyBtn.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        keyBtn.setWrapText(false);
        keyBtn.setFocusTraversable(false);
        keyBtn.setStyle("-fx-alignment: center;");

        if ("C".equals(key)) {
            keyBtn.getStyleClass().add("keypad-clear-button");
        } else if (BACKSPACE_KEY.equals(key)) {
            keyBtn.getStyleClass().add("keypad-backspace-button");
        } else if (ENTER_KEY.equals(key) || "ENTER".equals(key)) {
            keyBtn.getStyleClass().add("keypad-enter-button");
        } else if (QTY_KEY.equals(key)) {
            keyBtn.getStyleClass().add("keypad-qty-button");
        } else if (".".equals(key) || "00".equals(key)) {
            keyBtn.getStyleClass().add("keypad-decimal-button");
        } else {
            keyBtn.getStyleClass().add("keypad-number-button");
        }

        keyBtn.setOnAction(e -> {
            e.consume();
            playPressAnimation(keyBtn);
            if (listener != null) {
                listener.onKeyPressed(key);
            }
            if ((ENTER_KEY.equals(key) || "ENTER".equals(key)) && enterListener != null) {
                enterListener.onEnterPressed();
            }
        });

        keyBtn.setOnMousePressed(e -> e.consume());
        keyBtn.setOnMouseReleased(e -> e.consume());
        return keyBtn;
    }

    private void playPressAnimation(Button button) {
        ScaleTransition pressDown = new ScaleTransition(Duration.millis(50), button);
        pressDown.setToX(0.92);
        pressDown.setToY(0.92);

        ScaleTransition pressUp = new ScaleTransition(Duration.millis(100), button);
        pressUp.setToX(1.0);
        pressUp.setToY(1.0);

        pressDown.setOnFinished(e -> pressUp.play());
        pressDown.play();
    }
}
