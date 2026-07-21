package com.pos.ui.dialogs;

import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.TouchTextField;
import com.pos.ui.components.TouchTextArea;
import javafx.geometry.Pos;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dialog for holding a sale (parking it temporarily)
 */
public class HoldSaleDialog extends Dialog<HoldSaleDialog.HoldResult> {

    private static final Logger logger = LoggerFactory.getLogger(HoldSaleDialog.class);

    private TouchTextField customerNameField;
    private TouchTextArea noteField;

    public HoldSaleDialog() {
        initializeDialog();
    }

    private void initializeDialog() {
        setTitle("Hold Sale");
        setHeaderText(null);
        initModality(Modality.APPLICATION_MODAL);

        // Set owner window to ensure dialog appears on same screen
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

        // Create content
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.4, 0.4);
        DialogPane dialogPane = getDialogPane();
        dialogPane.setHeader(null);
        dialogPane.setGraphic(null);
        dialogPane.setStyle("-fx-background-color: linear-gradient(to bottom, #eef4fb 0%, #f8fbff 100%);");

        VBox content = new VBox(20);
        content.setPadding(new Insets(24));
        content.setStyle(
                "-fx-background-color: white; " +
                        "-fx-background-radius: 24; " +
                        "-fx-border-color: #dbe7f3; " +
                        "-fx-border-width: 1; " +
                        "-fx-border-radius: 24; " +
                        "-fx-effect: dropshadow(gaussian, rgba(15,23,42,0.12), 24, 0.2, 0, 8);");

        Label eyebrow = new Label("PARK CURRENT ORDER");
        eyebrow.setStyle(
                "-fx-font-size: 11px; " +
                        "-fx-font-weight: 800; " +
                        "-fx-letter-spacing: 1.4px; " +
                        "-fx-text-fill: #2563eb;");

        Label titleLabel = new Label("Hold sale for later");
        titleLabel.setWrapText(true);
        titleLabel.setStyle(
                "-fx-font-size: 28px; " +
                        "-fx-font-weight: 800; " +
                        "-fx-text-fill: #0f172a;");

        Label descriptionLabel = new Label(
                "Add a customer name or quick note so this order is easy to find when you recall it.");
        descriptionLabel.setWrapText(true);
        descriptionLabel.setStyle(
                "-fx-font-size: 14px; " +
                        "-fx-font-weight: 500; " +
                        "-fx-text-fill: #475569;");

        VBox heroSection = new VBox(6, eyebrow, titleLabel, descriptionLabel);

        VBox customerSection = new VBox(8);
        Label customerLabel = new Label("Customer name");
        customerLabel.setStyle(
                "-fx-font-size: 14px; " +
                        "-fx-font-weight: 700; " +
                        "-fx-text-fill: #1e293b;");
        customerNameField = TouchScreenComponents.createCompactTouchTextField("Enter customer name (optional)");
        customerNameField.setMaxWidth(Double.MAX_VALUE);
        customerSection.getChildren().addAll(customerLabel, customerNameField);

        VBox noteSection = new VBox(8);
        Label noteLabel = new Label("Order note");
        noteLabel.setStyle(
                "-fx-font-size: 14px; " +
                        "-fx-font-weight: 700; " +
                        "-fx-text-fill: #1e293b;");
        noteField = TouchScreenComponents.createTouchOnlyTextArea("Enter any notes about this held sale (optional)");
        noteField.setPrefRowCount(3);
        noteField.setMaxWidth(Double.MAX_VALUE);
        noteField.setWrapText(true);
        noteSection.getChildren().addAll(noteLabel, noteField);

        Label hintLabel = new Label("Leave both fields empty if you just want to park the cart temporarily.");
        hintLabel.setWrapText(true);
        hintLabel.setStyle(
                "-fx-font-size: 12px; " +
                        "-fx-font-weight: 600; " +
                        "-fx-text-fill: #64748b;");

        content.getChildren().addAll(heroSection, new Separator(), customerSection, noteSection, hintLabel);
        VBox.setVgrow(noteSection, Priority.ALWAYS);

        // Set dialog content
        dialogPane.setContent(content);

        // Buttons
        ButtonType holdButtonType = new ButtonType("Hold Sale", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialogPane.getButtonTypes().addAll(holdButtonType, cancelButtonType);

        // Set result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == holdButtonType) {
                HoldResult result = new HoldResult();
                result.customerName = customerNameField.getText() != null ? customerNameField.getText().trim() : "";
                result.note = noteField.getText() != null ? noteField.getText().trim() : "";
                return result;
            }
            return null;
        });

        // Style action buttons
        Button holdButton = (Button) dialogPane.lookupButton(holdButtonType);
        Button cancelButton = (Button) dialogPane.lookupButton(cancelButtonType);
        holdButton.setDefaultButton(true);
        holdButton.setStyle(
                "-fx-background-color: linear-gradient(to right, #2563eb 0%, #1d4ed8 100%); " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 15px; " +
                        "-fx-font-weight: 800; " +
                        "-fx-background-radius: 14; " +
                        "-fx-padding: 14 24; " +
                        "-fx-cursor: hand;");
        cancelButton.setStyle(
                "-fx-background-color: #e2e8f0; " +
                        "-fx-text-fill: #334155; " +
                        "-fx-font-size: 15px; " +
                        "-fx-font-weight: 700; " +
                        "-fx-background-radius: 14; " +
                        "-fx-padding: 14 24; " +
                        "-fx-cursor: hand;");

        javafx.application.Platform.runLater(() -> {
            if (!(holdButton.getParent() instanceof HBox buttonBar)) {
                return;
            }

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            buttonBar.setAlignment(Pos.CENTER_RIGHT);
            buttonBar.setSpacing(12);
            buttonBar.getChildren().remove(cancelButton);
            buttonBar.getChildren().remove(holdButton);
            buttonBar.getChildren().addAll(spacer, cancelButton, holdButton);
            buttonBar.setPadding(new Insets(0, 24, 24, 24));
        });

        // Focus customer name field when dialog opens
        javafx.application.Platform.runLater(() -> customerNameField.focusTextField());
    }

    public void setCustomerName(String name) {
        if (name != null) {
            customerNameField.setText(name);
        }
    }

    /**
     * Result class for hold sale
     */
    public static class HoldResult {
        public String customerName = "";
        public String note = "";
    }
}
