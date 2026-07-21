package com.pos.ui.dialogs;

import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.TouchTextField;
import com.pos.util.DialogHelper;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import javafx.stage.Window;

/**
 * Dialog for setting reorder level
 */
public class ReorderLevelDialog extends Dialog<ReorderLevelDialog.ReorderLevelResult> {

    private TouchTextField reorderLevelField;
    private Label currentStockLabel;

    public ReorderLevelDialog(int currentStock, int currentReorderLevel) {
        setTitle("Set Reorder Level");
        setHeaderText("Set reorder level for product");
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
        dialogPane.setContent(createForm(currentStock, currentReorderLevel));
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.4, 0.5);
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

    private GridPane createForm(int currentStock, int currentReorderLevel) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        // Current Stock
        grid.add(new Label("Current Stock:"), 0, 0);
        currentStockLabel = new Label(String.valueOf(currentStock));
        currentStockLabel.setStyle("-fx-font-weight: bold;");
        grid.add(currentStockLabel, 1, 0);

        // Reorder Level
        grid.add(new Label("Reorder Level:"), 0, 1);
        reorderLevelField = TouchScreenComponents.createTouchOnlyNumericField("Enter reorder level");
        reorderLevelField.setText(String.valueOf(currentReorderLevel));
        // reorderLevelField.setTextFieldPrefWidth(300);
        reorderLevelField.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(reorderLevelField, javafx.scene.layout.Priority.ALWAYS);
        grid.add(reorderLevelField, 1, 1);

        // Info label
        Label infoLabel = new Label("Product will show LOW_STOCK when quantity falls to or below this level");
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        infoLabel.setWrapText(true);
        grid.add(infoLabel, 0, 2, 2, 1);

        return grid;
    }

    private boolean validateForm() {
        if (reorderLevelField.getText().trim().isEmpty()) {
            showAlert("Validation Error", "Reorder level is required");
            return false;
        }

        try {
            int reorderLevel = Integer.parseInt(reorderLevelField.getText().trim());
            if (reorderLevel < 0) {
                showAlert("Validation Error", "Reorder level cannot be negative");
                return false;
            }
        } catch (NumberFormatException e) {
            showAlert("Validation Error", "Invalid reorder level format");
            return false;
        }

        return true;
    }

    private ReorderLevelResult createResult() {
        int reorderLevel = Integer.parseInt(reorderLevelField.getText().trim());
        return new ReorderLevelResult(reorderLevel);
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
     * Result class for reorder level dialog
     */
    public static class ReorderLevelResult {
        public final int reorderLevel;

        public ReorderLevelResult(int reorderLevel) {
            this.reorderLevel = reorderLevel;
        }
    }
}
