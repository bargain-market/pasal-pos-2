package com.pos.ui.dialogs;

import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.TouchTextField;
import com.pos.ui.components.TouchTextArea;
import com.pos.util.DialogHelper;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * Dialog for adjusting product stock
 */
public class StockAdjustmentDialog extends Dialog<StockAdjustmentDialog.StockAdjustmentResult> {

    private TouchTextField adjustmentField;
    private TouchTextArea reasonField;
    private Label currentStockLabel;
    private ComboBox<String> changeTypeCombo;

    public StockAdjustmentDialog(int currentStock) {
        setTitle("Stock Adjustment");
        setHeaderText("Adjust product stock");
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

        // Create dialog pane
        DialogPane dialogPane = getDialogPane();
        dialogPane.setContent(createForm(currentStock));
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.5, 0.65);
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Set button actions
        Button okButton = (Button) dialogPane.lookupButton(ButtonType.OK);
        okButton.setOnAction(e -> {
            if (validateForm()) {
                setResult(createResult());
            } else {
                e.consume();
            }
        });
    }

    private GridPane createForm(int currentStock) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        // Current Stock
        grid.add(new Label("Current Stock:"), 0, 0);
        currentStockLabel = new Label(String.valueOf(currentStock));
        currentStockLabel.setStyle("-fx-font-weight: bold;");
        grid.add(currentStockLabel, 1, 0);

        // Change Type
        grid.add(new Label("Change Type:"), 0, 1);
        changeTypeCombo = new ComboBox<>();
        changeTypeCombo.getItems().addAll("ADJUSTMENT", "RECEIVE", "TRANSFER");
        changeTypeCombo.setValue("ADJUSTMENT");
        // changeTypeCombo.setPrefWidth(200);
        changeTypeCombo.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(changeTypeCombo, javafx.scene.layout.Priority.ALWAYS);
        grid.add(changeTypeCombo, 1, 1);

        // Adjustment
        grid.add(new Label("Adjustment:"), 0, 2);
        adjustmentField = TouchScreenComponents
                .createTouchOnlyNumericField("Enter positive number to add, negative to subtract");
        // adjustmentField.setTextFieldPrefWidth(300);
        adjustmentField.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(adjustmentField, javafx.scene.layout.Priority.ALWAYS);
        grid.add(adjustmentField, 1, 2);

        // Reason
        grid.add(new Label("Reason:"), 0, 3);
        reasonField = TouchScreenComponents
                .createTouchOnlyTextArea("Reason for adjustment (e.g., 'Damaged items', 'Received shipment')");
        reasonField.setPrefRowCount(3);
        // reasonField.setTextAreaPrefWidth(300);
        reasonField.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(reasonField, javafx.scene.layout.Priority.ALWAYS);
        grid.add(reasonField, 1, 3);

        return grid;
    }

    private boolean validateForm() {
        if (adjustmentField.getText().trim().isEmpty()) {
            showAlert("Validation Error", "Adjustment amount is required");
            return false;
        }

        try {
            int adjustment = Integer.parseInt(adjustmentField.getText().trim());
            if (adjustment == 0) {
                showAlert("Validation Error", "Adjustment cannot be zero");
                return false;
            }
        } catch (NumberFormatException e) {
            showAlert("Validation Error", "Invalid adjustment format");
            return false;
        }

        if (reasonField.getText().trim().isEmpty()) {
            showAlert("Validation Error", "Reason is required");
            return false;
        }

        return true;
    }

    private StockAdjustmentResult createResult() {
        int adjustment = Integer.parseInt(adjustmentField.getText().trim());
        String changeType = changeTypeCombo.getValue();
        String reason = reasonField.getText().trim();
        return new StockAdjustmentResult(adjustment, changeType, reason);
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogHelper.setAlertOwner(alert,
                getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null);
        alert.showAndWait();
    }

    /**
     * Result class for stock adjustment dialog
     */
    public static class StockAdjustmentResult {
        public final int adjustment;
        public final String changeType;
        public final String reason;

        public StockAdjustmentResult(int adjustment, String changeType, String reason) {
            this.adjustment = adjustment;
            this.changeType = changeType;
            this.reason = reason;
        }
    }
}
