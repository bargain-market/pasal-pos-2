package com.pos.ui.keyboard;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Popup;

/**
 * Controller for the floating on-screen numeric keypad.
 */
public class FloatingNumericKeypadController {

    @FXML
    private HBox titleBar;

    @FXML
    private Label titleLabel;

    @FXML
    private GridPane keypadGrid;

    @FXML
    private Button decimalBtn;

    @FXML
    private Button doubleZeroBtn;

    @FXML
    private Button enterBtn;

    private Popup popup;
    private double dragOffsetX;
    private double dragOffsetY;
    private boolean allowDecimal = true;
    private boolean showEnterButton = true;

    void setPopup(Popup popup) {
        this.popup = popup;
        initDragging();
    }

    public void setAllowDecimal(boolean allow) {
        this.allowDecimal = allow;
        if (decimalBtn != null) {
            decimalBtn.setVisible(allow);
            decimalBtn.setManaged(allow);
        }
    }

    public void setShowEnterButton(boolean show) {
        this.showEnterButton = show;
        if (enterBtn != null) {
            enterBtn.setVisible(show);
            enterBtn.setManaged(show);
        }
    }

    public void setTitle(String title) {
        if (titleLabel != null) {
            titleLabel.setText(title);
        }
    }

    private void initDragging() {
        if (titleBar == null) {
            return;
        }

        titleBar.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (popup == null) {
                return;
            }
            dragOffsetX = event.getScreenX() - popup.getX();
            dragOffsetY = event.getScreenY() - popup.getY();
        });

        titleBar.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            if (popup == null) {
                return;
            }
            popup.setX(event.getScreenX() - dragOffsetX);
            popup.setY(event.getScreenY() - dragOffsetY);
        });
    }

    @FXML
    private void onCloseClicked() {
        KeyboardManager.hide();
    }

    @FXML
    private void onKeyClicked(javafx.event.ActionEvent event) {
        if (event.getSource() instanceof Button) {
            Button btn = (Button) event.getSource();
            String key = btn.getText();

            if (".".equals(key) && !allowDecimal) {
                return;
            }

            KeyboardManager.handleKeyPress(key);
        }
    }

    @FXML
    private void onBackspaceClicked() {
        KeyboardManager.handleKeyPress("BACKSPACE");
    }

    @FXML
    private void onClearClicked() {
        KeyboardManager.handleKeyPress("CLEAR");
    }

    @FXML
    private void onEnterClicked() {
        KeyboardManager.handleKeyPress("ENTER");
    }

    @FXML
    private void onDoubleZeroClicked() {
        KeyboardManager.handleKeyPress("0");
        KeyboardManager.handleKeyPress("0");
    }
}
