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
 * Dialog for reconciling stock (setting actual quantity)
 */
public class StockReconciliationDialog extends Dialog<StockReconciliationDialog.StockReconciliationResult> {

    private TouchTextField actualQuantityField;
    private TouchTextArea reasonField;
    private Label currentStockLabel;

    public StockReconciliationDialog(int currentStock) {
        setTitle("Stock Reconciliation");
        setHeaderText("Set actual stock quantity");
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
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.5, 0.6);
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        // Prevent ClassCastException on Cancel by explicit null conversion
        setResultConverter(dialogButton -> null);

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

        // Actual Quantity
        grid.add(new Label("Actual Quantity:"), 0, 1);
        actualQuantityField = TouchScreenComponents.createTouchOnlyNumericField("Enter actual counted quantity");
        // actualQuantityField.setTextFieldPrefWidth(300);
        actualQuantityField.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(actualQuantityField, javafx.scene.layout.Priority.ALWAYS);
        grid.add(actualQuantityField, 1, 1);

        // Reason
        grid.add(new Label("Reason:"), 0, 2);
        reasonField = TouchScreenComponents
                .createTouchOnlyTextArea("Reason for reconciliation (e.g., 'Physical count', 'Inventory audit')");
        reasonField.setPrefRowCount(3);
        // reasonField.setTextAreaPrefWidth(300);
        reasonField.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(reasonField, javafx.scene.layout.Priority.ALWAYS);
        grid.add(reasonField, 1, 2);

        return grid;
    }

    private boolean validateForm() {
        if (actualQuantityField.getText().trim().isEmpty()) {
            showAlert("Validation Error", "Actual quantity is required");
            return false;
        }

        try {
            int actualQuantity = Integer.parseInt(actualQuantityField.getText().trim());
            if (actualQuantity < 0) {
                showAlert("Validation Error", "Quantity cannot be negative");
                return false;
            }
        } catch (NumberFormatException e) {
            showAlert("Validation Error", "Invalid quantity format");
            return false;
        }

        if (reasonField.getText().trim().isEmpty()) {
            showAlert("Validation Error", "Reason is required");
            return false;
        }

        return true;
    }

    private StockReconciliationResult createResult() {
        int actualQuantity = Integer.parseInt(actualQuantityField.getText().trim());
        String reason = reasonField.getText().trim();
        return new StockReconciliationResult(actualQuantity, reason);
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
     * Result class for stock reconciliation dialog
     */
    public static class StockReconciliationResult {
        public final int actualQuantity;
        public final String reason;

        public StockReconciliationResult(int actualQuantity, String reason) {
            this.actualQuantity = actualQuantity;
            this.reason = reason;
        }
    }
}
