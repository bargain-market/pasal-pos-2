package com.pos.ui.dialogs;

import com.pos.model.Expense;
import com.pos.model.ExpenseCategory;
import com.pos.service.ExpenseCategoryService;
import com.pos.service.ShiftService;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.TouchTextArea;
import com.pos.ui.components.TouchTextField;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;

/**
 * Dialog for adding/editing expenses
 */
public class ExpenseDialog extends Dialog<Expense> {

    private static final Logger logger = LoggerFactory.getLogger(ExpenseDialog.class);
    private static final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

    private final ExpenseCategoryService categoryService;
    private final ShiftService shiftService;

    // UI Components
    private ComboBox<ExpenseCategory> categoryComboBox;
    private TouchTextField amountField;
    private TouchTextArea descriptionArea;
    private ComboBox<String> paymentMethodComboBox;
    private TouchTextField receiptNumberField;
    private TouchTextField vendorNameField;
    private Label availableCashLabel;

    private String shiftId;

    public ExpenseDialog() {
        this(null);
    }

    public ExpenseDialog(Window owner) {
        this.categoryService = ExpenseCategoryService.getInstance();
        this.shiftService = ShiftService.getInstance();

        setTitle("Add Expense");
        setHeaderText("Enter expense details");
        initModality(Modality.APPLICATION_MODAL);

        if (owner != null) {
            initOwner(owner);
        } else {
        }

        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.5, 0.75);

        // Get current shift ID if available
        try {
            com.pos.api.dto.ShiftResponse.ShiftData activeShift = shiftService.getActiveShiftFromLocal();
            if (activeShift != null) {
                this.shiftId = activeShift.id;
            }
        } catch (Exception e) {
            logger.debug("Could not get active shift: {}", e.getMessage());
        }

        DialogPane dialogPane = getDialogPane();
        dialogPane.setContent(createForm());
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Set button actions
        Button okButton = (Button) dialogPane.lookupButton(ButtonType.OK);
        okButton.setOnAction(e -> {
            if (validateForm()) {
                setResult(createExpense());
            } else {
                e.consume();
            }
        });

        // Load categories
        loadCategories();
    }

    private GridPane createForm() {
        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(15);
        grid.setPadding(new Insets(20));

        // Category
        Label categoryLabel = new Label("Category *:");
        categoryLabel.setStyle("-fx-font-weight: bold;");
        categoryComboBox = new ComboBox<>();
        // categoryComboBox.setPrefWidth(300);
        categoryComboBox.setMaxWidth(Double.MAX_VALUE);
        categoryComboBox.setEditable(false);
        GridPane.setHgrow(categoryComboBox, javafx.scene.layout.Priority.ALWAYS);
        grid.add(categoryLabel, 0, 0);
        grid.add(categoryComboBox, 1, 0);

        // Amount
        Label amountLabel = new Label("Amount *:");
        amountLabel.setStyle("-fx-font-weight: bold;");
        amountField = TouchScreenComponents.createTouchOnlyNumericField("0.00");
        // amountField.setTextFieldPrefWidth(300);
        amountField.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(amountField, javafx.scene.layout.Priority.ALWAYS);
        grid.add(amountLabel, 0, 1);
        grid.add(amountField, 1, 1);

        // Description
        Label descriptionLabel = new Label("Description:");
        descriptionArea = TouchScreenComponents.createTouchOnlyTextArea("Enter expense description");
        descriptionArea.setPrefRowCount(3);
        // descriptionArea.setPrefWidth(300);
        descriptionArea.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(descriptionArea, javafx.scene.layout.Priority.ALWAYS);
        grid.add(descriptionLabel, 0, 2);
        grid.add(descriptionArea, 1, 2);

        // Payment Method
        Label paymentMethodLabel = new Label("Payment Method:");
        paymentMethodComboBox = new ComboBox<>();
        paymentMethodComboBox.getItems().addAll("CASH", "CARD", "CHEQUE", "OTHER");
        paymentMethodComboBox.setValue("CASH");
        // paymentMethodComboBox.setPrefWidth(300);
        paymentMethodComboBox.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(paymentMethodComboBox, javafx.scene.layout.Priority.ALWAYS);
        paymentMethodComboBox.setOnAction(e -> updateAvailableCashDisplay());
        grid.add(paymentMethodLabel, 0, 3);
        grid.add(paymentMethodComboBox, 1, 3);

        // Available Cash (if paying in cash and shift is active)
        if (shiftId != null) {
            availableCashLabel = new Label();
            availableCashLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #2a5298;");
            updateAvailableCashDisplay();
            grid.add(new Label("Available Cash:"), 0, 4);
            grid.add(availableCashLabel, 1, 4);
        }

        // Receipt Number
        Label receiptNumberLabel = new Label("Receipt Number:");
        receiptNumberField = TouchScreenComponents.createTouchOnlyTextField("");
        // receiptNumberField.setTextFieldPrefWidth(300);
        receiptNumberField.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(receiptNumberField, javafx.scene.layout.Priority.ALWAYS);
        grid.add(receiptNumberLabel, 0, 5);
        grid.add(receiptNumberField, 1, 5);

        // Vendor Name
        Label vendorNameLabel = new Label("Vendor Name:");
        vendorNameField = TouchScreenComponents.createTouchOnlyTextField("");
        // vendorNameField.setTextFieldPrefWidth(300);
        vendorNameField.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(vendorNameField, javafx.scene.layout.Priority.ALWAYS);
        grid.add(vendorNameLabel, 0, 6);
        grid.add(vendorNameField, 1, 6);

        return grid;
    }

    private void loadCategories() {
        try {
            List<ExpenseCategory> categories = categoryService.getAllCategories();
            ObservableList<ExpenseCategory> categoryList = FXCollections.observableArrayList(categories);
            categoryComboBox.setItems(categoryList);

            if (!categoryList.isEmpty()) {
                categoryComboBox.setValue(categoryList.get(0));
            }
        } catch (Exception e) {
            logger.error("Error loading expense categories", e);
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Error");
                alert.setHeaderText("Failed to load expense categories");
                alert.setContentText(e.getMessage());
                alert.showAndWait();
            });
        }
    }

    private void updateAvailableCashDisplay() {
        if (availableCashLabel == null || shiftId == null) {
            return;
        }

        if ("CASH".equals(paymentMethodComboBox.getValue())) {
            try {
                BigDecimal availableCash = shiftService.calculateAvailableCash(shiftId);
                availableCashLabel.setText(currencyFormat.format(availableCash));
                availableCashLabel.setVisible(true);
            } catch (Exception e) {
                logger.debug("Could not calculate available cash: {}", e.getMessage());
                availableCashLabel.setVisible(false);
            }
        } else {
            availableCashLabel.setVisible(false);
        }
    }

    private boolean validateForm() {
        if (categoryComboBox.getValue() == null) {
            showAlert("Please select an expense category");
            return false;
        }

        String amountText = amountField.getText().trim();
        if (amountText.isEmpty()) {
            showAlert("Please enter an amount");
            return false;
        }

        try {
            BigDecimal amount = new BigDecimal(amountText);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                showAlert("Amount must be greater than zero");
                return false;
            }
        } catch (NumberFormatException e) {
            showAlert("Invalid amount format. Please enter a valid number.");
            return false;
        }

        return true;
    }

    private Expense createExpense() {
        Expense expense = new Expense();

        ExpenseCategory selectedCategory = categoryComboBox.getValue();
        expense.setCategoryId(selectedCategory.getId());
        expense.setCategoryName(selectedCategory.getName());

        expense.setAmount(new BigDecimal(amountField.getText().trim()));
        expense.setDescription(descriptionArea.getText().trim());
        expense.setPaymentMethod(paymentMethodComboBox.getValue());
        expense.setReceiptNumber(receiptNumberField.getText().trim());
        expense.setVendorName(vendorNameField.getText().trim());
        expense.setShiftId(shiftId);

        return expense;
    }

    private void showAlert(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Validation Error");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
}
