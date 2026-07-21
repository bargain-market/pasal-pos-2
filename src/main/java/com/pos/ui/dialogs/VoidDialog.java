package com.pos.ui.dialogs;

import com.pos.service.SaleHistoryService.SaleRecord;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.TouchTextArea;
import com.pos.util.DialogHelper;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Dialog for voiding a transaction
 */
public class VoidDialog extends Dialog<VoidDialog.VoidResult> {

    private TouchTextArea reasonField;
    private NumberFormat currencyFormatter;

    public VoidDialog(SaleRecord sale) {
        this(sale, null);
    }

    public VoidDialog(SaleRecord sale, Window owner) {
        this.currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US);

        // Set owner and modality to keep dialog in same window
        if (owner != null) {
            initOwner(owner);
        } else {
            DialogHelper.setDialogOwner(this, null);
        }
        initModality(Modality.APPLICATION_MODAL);

        setTitle("Void Transaction");
        setHeaderText("Void Transaction");

        // Create content
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        // content.setPrefWidth(500);
        // content.setPrefWidth(500);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.6, 0.7);

        // Sale information
        Label detailsLabel = new Label("Transaction Details:");
        detailsLabel.setFont(Font.font(null, FontWeight.BOLD, 12));

        GridPane infoGrid = new GridPane();
        infoGrid.setHgap(15);
        infoGrid.setVgap(8);
        infoGrid.setPadding(new Insets(10));
        infoGrid.setStyle("-fx-background-color: #f5f5f5; -fx-background-radius: 5;");

        // Add column constraints
        ColumnConstraints col1 = new ColumnConstraints();
        col1.setMinWidth(160);
        col1.setPrefWidth(180);
        infoGrid.getColumnConstraints().add(col1);

        infoGrid.add(new Label("Sale ID:"), 0, 0);
        infoGrid.add(new Label(sale.saleId), 1, 0);

        infoGrid.add(new Label("Date:"), 0, 1);
        infoGrid.add(new Label(sale.saleDate != null ? sale.saleDate.toString() : "N/A"), 1, 1);

        infoGrid.add(new Label("Cashier:"), 0, 2);
        infoGrid.add(new Label(sale.cashierName != null ? sale.cashierName : "N/A"), 1, 2);

        infoGrid.add(new Label("Payment Method:"), 0, 3);
        infoGrid.add(new Label(sale.paymentMethod != null ? sale.paymentMethod : "N/A"), 1, 3);

        infoGrid.add(new Label("Total:"), 0, 4);
        Label totalLabel = new Label(currencyFormatter.format(sale.total));
        totalLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        infoGrid.add(totalLabel, 1, 4);

        // Warning label
        Label warningLabel = new Label("⚠ Warning: This action cannot be undone. Stock will be restored.");
        warningLabel.setStyle("-fx-text-fill: #f44336; -fx-font-weight: bold; -fx-font-size: 11px;");
        warningLabel.setWrapText(true);

        // Reason field
        Label reasonLabel = new Label("Reason for void (required):");
        reasonField = TouchScreenComponents.createTouchOnlyTextArea("Enter reason for voiding this transaction...");
        reasonField.setPrefRowCount(4); // Increased from 2
        reasonField.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(reasonField, javafx.scene.layout.Priority.ALWAYS);
        reasonField.setWrapText(true);

        content.getChildren().addAll(
                detailsLabel,
                infoGrid,
                warningLabel,
                reasonLabel,
                reasonField);

        // Wrap content in ScrollPane to ensure buttons are always visible
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        getDialogPane().setContent(scrollPane);

        // Buttons
        ButtonType voidButtonType = new ButtonType("Void Transaction", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

        getDialogPane().getButtonTypes().addAll(cancelButtonType, voidButtonType);

        // Style the void button as dangerous
        Button voidButton = (Button) getDialogPane().lookupButton(voidButtonType);
        voidButton.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-weight: bold;");

        // Validate reason before allowing void
        voidButton.setDisable(true);
        reasonField.textProperty().addListener((obs, oldVal, newVal) -> {
            voidButton.setDisable(newVal == null || newVal.trim().isEmpty());
        });

        // Set result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == voidButtonType) {
                VoidResult result = new VoidResult();
                result.saleId = sale.saleId;
                result.reason = reasonField.getText().trim();
                return result;
            }
            return null;
        });
    }

    /**
     * Result class for void dialog
     */
    public static class VoidResult {
        public String saleId;
        public String reason;
    }
}
