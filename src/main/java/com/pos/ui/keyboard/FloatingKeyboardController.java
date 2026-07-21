package com.pos.ui.keyboard;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.stage.Popup;

/**
 * Controller for the floating on‑screen keyboard.
 */
public class FloatingKeyboardController {

    @FXML
    private HBox titleBar;

    private Popup popup;
    private double dragOffsetX;
    private double dragOffsetY;

    void setPopup(Popup popup) {
        this.popup = popup;
        initDragging();
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
            String key = ((Button) event.getSource()).getText();
            KeyboardManager.handleKeyPress(key);
        }
    }

    @FXML
    private void onSpecialKeyClicked(javafx.event.ActionEvent event) {
        if (event.getSource() instanceof Button) {
            String label = ((Button) event.getSource()).getText();
            KeyboardManager.handleKeyPress(label);
        }
    }
}
