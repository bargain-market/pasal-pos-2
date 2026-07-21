package com.pos.ui.dialogs;

import com.pos.service.ProductManagementService;
import com.pos.service.SettingsService;
import java.math.BigDecimal;
import com.pos.ui.components.TouchScreenComponents;
import com.pos.ui.components.TouchTextField;
import com.pos.util.DialogHelper;
import com.pos.util.ResponsiveHelper;
import com.pos.ui.keyboard.KeyboardManager;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.List;

/**
 * Dialog for adding/editing departments/categories.
 * Matches backend schema with all department features.
 */
public class CategoryFormDialog extends Dialog<CategoryFormDialog.CategoryFormResult> {

    private static final Logger logger = LoggerFactory.getLogger(CategoryFormDialog.class);

    // Basic fields
    private TouchTextField nameField;
    private TouchTextField iconField;
    private ComboBox<String> parentCombo;

    // Department type
    private ComboBox<String> departmentTypeCombo;

    // Tax settings
    private CheckBox taxEnabledCheck;

    // Feature flags
    private CheckBox hideOnRegisterCheck;
    private CheckBox ebtEligibleCheck;
    private CheckBox excludeFromGlobalPriceIncreaseCheck;
    private CheckBox noPointsEarningCheck;

    // Age verification
    private TouchTextField ageVerificationField;

    // Multi-pack discount settings
    private CheckBox multipackEnabledCheck;
    private ToggleGroup multipackDiscountTypeGroup;
    private RadioButton multipackPercentRadio;
    private RadioButton multipackAmountRadio;
    private TouchTextField multipackDiscountValueField;
    private TouchTextField multipackMinQuantityField;
    private CheckBox multipackRequiresApprovalCheck;

    private ProductManagementService productService;
    private String existingDepartmentId;
    private ProductManagementService.Department existingDepartment;

    public CategoryFormDialog(String departmentId, String departmentName) {
        this.existingDepartmentId = departmentId;
        this.productService = ProductManagementService.getInstance();

        setTitle(departmentId == null ? "Add Department" : "Edit Department");
        setHeaderText(departmentId == null ? "Enter department details" : "Edit department details");
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

        // Load existing department if editing
        if (departmentId != null) {
            try {
                existingDepartment = productService.getDepartmentById(departmentId);
            } catch (SQLException e) {
                logger.error("Error loading department", e);
            }
        }

        // Create dialog pane
        DialogPane dialogPane = getDialogPane();
        dialogPane.setContent(createForm());
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Make responsive - 60% width, 85% height
        ResponsiveHelper.setupResponsiveDialog(this, 0.6, 0.85);

        // Set button actions
        // Set validation on OK button
        Button okButton = (Button) dialogPane.lookupButton(ButtonType.OK);
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (!validateForm()) {
                e.consume();
            }
        });

        // Convert result
        setResultConverter(dialogButton -> {
            if (dialogButton == ButtonType.OK) {
                return createResult();
            }
            return null;
        });

        // Load departments for parent selection
        loadDepartments();

        // Populate fields if editing
        populateFields(departmentName);

        // Setup field interactions
        setupFieldInteractions();

        // Ensure touch fields use the global floating keyboard
        setupKeyboardSupport();
    }

    private ScrollPane createForm() {
        VBox mainContainer = new VBox(15);
        mainContainer.setPadding(new Insets(20));
        // Ensure scrollable content fits
        mainContainer.setMaxWidth(Double.MAX_VALUE);

        // Basic Information Section
        TitledPane basicSection = new TitledPane("Basic Information", createBasicInfoGrid());
        basicSection.setCollapsible(false);

        // Tax Settings Section
        TitledPane taxSection = new TitledPane("Tax Settings", createTaxSettingsGrid());
        taxSection.setCollapsible(false);

        // Features Section
        TitledPane featuresSection = new TitledPane("Department Features", createFeaturesGrid());
        featuresSection.setCollapsible(false);

        // Multi-Pack Discount Section
        TitledPane multipackSection = new TitledPane("Multi-Pack Discount Settings", createMultipackDiscountGrid());
        multipackSection.setCollapsible(false);

        // Age Verification Section
        TitledPane ageSection = new TitledPane("Age Verification", createAgeVerificationGrid());
        ageSection.setCollapsible(false);

        mainContainer.getChildren().addAll(basicSection, taxSection, featuresSection, multipackSection, ageSection);

        // WRAP IN SCROLLPANE for responsiveness
        ScrollPane scrollPane = new ScrollPane(mainContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.getStyleClass().add("edge-to-edge-scroll-pane"); // Optional: for styling if needed

        return scrollPane;
    }

    private GridPane createBasicInfoGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        int row = 0;

        // Name
        grid.add(new Label("Department Name:*"), 0, row);
        nameField = TouchScreenComponents.createTouchOnlyTextField("Enter department name");
        // nameField.setTextFieldPrefWidth(300); // Dynamic width
        grid.add(nameField, 1, row++);
        GridPane.setHgrow(nameField, Priority.ALWAYS);

        // Icon
        grid.add(new Label("Icon:"), 0, row);
        iconField = TouchScreenComponents.createTouchOnlyTextField("Icon name or emoji (optional)");
        // iconField.setTextFieldPrefWidth(300);
        grid.add(iconField, 1, row++);
        GridPane.setHgrow(iconField, Priority.ALWAYS);

        // Parent Department
        grid.add(new Label("Parent Department:"), 0, row);
        parentCombo = new ComboBox<>();
        parentCombo.setPromptText("None (top-level department)");
        parentCombo.setMaxWidth(Double.MAX_VALUE);
        grid.add(parentCombo, 1, row++);
        GridPane.setHgrow(parentCombo, Priority.ALWAYS);

        // Department Type
        grid.add(new Label("Department Type:"), 0, row);
        departmentTypeCombo = new ComboBox<>();
        departmentTypeCombo.getItems().addAll("PRODUCT", "SERVICE");
        departmentTypeCombo.setValue("PRODUCT");
        departmentTypeCombo.setMaxWidth(Double.MAX_VALUE);
        grid.add(departmentTypeCombo, 1, row++);
        GridPane.setHgrow(departmentTypeCombo, Priority.ALWAYS);

        return grid;
    }

    /**
     * Wire touch text fields to show the global floating keyboard.
     */
    private void setupKeyboardSupport() {
        if (nameField != null) {
            nameField.getTextField().setOnMouseClicked(e -> {
                KeyboardManager.showFor(nameField.getTextField());
            });
        }
        if (iconField != null) {
            iconField.getTextField().setOnMouseClicked(e -> {
                KeyboardManager.showFor(iconField.getTextField());
            });
        }

        if (ageVerificationField != null) {
            ageVerificationField.getTextField().setOnMouseClicked(e -> {
                KeyboardManager.showFor(ageVerificationField.getTextField());
            });
        }
    }

    private GridPane createTaxSettingsGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        int row = 0;

        // Tax Enabled
        taxEnabledCheck = new CheckBox("Tax Enabled");
        taxEnabledCheck.setSelected(true);
        grid.add(taxEnabledCheck, 0, row);

        BigDecimal rate = SettingsService.getInstance().getDefaultTaxRate();
        Label taxHint = new Label(String.format("(Uses store rate: %.2f%%)", rate.multiply(new BigDecimal(100))));
        taxHint.setStyle("-fx-text-fill: #666; -fx-font-size: 13px; -fx-font-style: italic;");
        grid.add(taxHint, 1, row++);

        return grid;
    }

    private GridPane createFeaturesGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        int row = 0;

        // Hide on Register
        hideOnRegisterCheck = new CheckBox("Hide on Register");
        Tooltip hideTooltip = new Tooltip("Hide this department from the POS register screen");
        hideOnRegisterCheck.setTooltip(hideTooltip);
        grid.add(hideOnRegisterCheck, 0, row++, 2, 1);

        // EBT Eligible
        ebtEligibleCheck = new CheckBox("EBT/SNAP Eligible");
        Tooltip ebtTooltip = new Tooltip("Products in this department are eligible for EBT/SNAP/Food Stamps");
        ebtEligibleCheck.setTooltip(ebtTooltip);
        grid.add(ebtEligibleCheck, 0, row++, 2, 1);

        // Exclude from Global Price Increase
        excludeFromGlobalPriceIncreaseCheck = new CheckBox("Exclude from Global Price Increase");
        Tooltip priceTooltip = new Tooltip("Products in this department won't be affected by global price adjustments");
        excludeFromGlobalPriceIncreaseCheck.setTooltip(priceTooltip);
        grid.add(excludeFromGlobalPriceIncreaseCheck, 0, row++, 2, 1);

        // No Points Earning
        noPointsEarningCheck = new CheckBox("No Loyalty Points Earning");
        Tooltip pointsTooltip = new Tooltip("Purchases in this department won't earn loyalty points");
        noPointsEarningCheck.setTooltip(pointsTooltip);
        grid.add(noPointsEarningCheck, 0, row++, 2, 1);

        return grid;
    }

    private GridPane createMultipackDiscountGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        int row = 0;

        // Enable Multi-Pack Discount
        multipackEnabledCheck = new CheckBox("Enable Multi-Pack Discount");
        Tooltip multipackTooltip = new Tooltip(
                "Enable automatic discount when customers buy multiple items from this department");
        multipackEnabledCheck.setTooltip(multipackTooltip);
        grid.add(multipackEnabledCheck, 0, row++, 2, 1);

        // Discount Type
        grid.add(new Label("Discount Type:"), 0, row);
        HBox discountTypeBox = new HBox(10);
        multipackDiscountTypeGroup = new ToggleGroup();
        multipackPercentRadio = new RadioButton("Percentage (%)");
        multipackPercentRadio.setToggleGroup(multipackDiscountTypeGroup);
        multipackPercentRadio.setSelected(true);
        multipackAmountRadio = new RadioButton("Fixed Amount ($)");
        multipackAmountRadio.setToggleGroup(multipackDiscountTypeGroup);
        discountTypeBox.getChildren().addAll(multipackPercentRadio, multipackAmountRadio);
        grid.add(discountTypeBox, 1, row++);

        // Discount Value
        grid.add(new Label("Discount Value:"), 0, row);
        HBox discountValueBox = new HBox(5);
        multipackDiscountValueField = TouchScreenComponents.createTouchOnlyNumericField("e.g., 10.00");
        multipackDiscountValueField.setTextFieldPrefWidth(120);
        Label discountValueHint = new Label("Enter percentage (e.g., 10) or amount (e.g., 5.00)");
        discountValueHint.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");
        discountValueBox.getChildren().addAll(multipackDiscountValueField, discountValueHint);
        grid.add(discountValueBox, 1, row++);

        // Minimum Quantity
        grid.add(new Label("Minimum Quantity:"), 0, row);
        HBox minQtyBox = new HBox(5);
        multipackMinQuantityField = TouchScreenComponents.createTouchOnlyNumericField("e.g., 2");
        multipackMinQuantityField.setTextFieldPrefWidth(80);
        Label minQtyHint = new Label("Minimum items required to qualify");
        minQtyHint.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");
        minQtyBox.getChildren().addAll(multipackMinQuantityField, minQtyHint);
        grid.add(minQtyBox, 1, row++);

        // Requires Approval
        multipackRequiresApprovalCheck = new CheckBox("Requires Manager Approval");
        Tooltip approvalTooltip = new Tooltip("Require manager authentication before applying discount");
        multipackRequiresApprovalCheck.setTooltip(approvalTooltip);
        grid.add(multipackRequiresApprovalCheck, 0, row++, 2, 1);

        // Disable fields when multipack is not enabled
        multipackPercentRadio.disableProperty().bind(multipackEnabledCheck.selectedProperty().not());
        multipackAmountRadio.disableProperty().bind(multipackEnabledCheck.selectedProperty().not());
        multipackDiscountValueField.disableProperty().bind(multipackEnabledCheck.selectedProperty().not());
        multipackMinQuantityField.disableProperty().bind(multipackEnabledCheck.selectedProperty().not());
        multipackRequiresApprovalCheck.disableProperty().bind(multipackEnabledCheck.selectedProperty().not());

        return grid;
    }

    private GridPane createAgeVerificationGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        int row = 0;

        grid.add(new Label("Minimum Age Required:"), 0, row);
        HBox ageBox = new HBox(5);
        ageVerificationField = TouchScreenComponents.createTouchOnlyNumericField("e.g., 21");
        ageVerificationField.setTextFieldPrefWidth(80);
        Label ageHint = new Label("Leave empty for no age restriction");
        ageHint.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");
        ageBox.getChildren().addAll(ageVerificationField, ageHint);
        grid.add(ageBox, 1, row++);

        return grid;
    }

    private void loadDepartments() {
        try {
            List<ProductManagementService.Department> departments = productService.getDepartments();
            parentCombo.getItems().clear();
            parentCombo.getItems().add(""); // Empty option for no parent

            // Filter out the current department if editing (to prevent circular reference)
            for (ProductManagementService.Department dept : departments) {
                if (existingDepartmentId == null || !dept.id.equals(existingDepartmentId)) {
                    parentCombo.getItems().add(dept.name);
                }
            }
        } catch (SQLException e) {
            logger.error("Error loading departments", e);
        }
    }

    private void populateFields(String departmentName) {
        if (existingDepartment != null) {
            // Populate from existing department
            nameField.setText(existingDepartment.name);
            iconField.setText(existingDepartment.icon != null ? existingDepartment.icon : "");
            departmentTypeCombo.setValue(
                    existingDepartment.departmentType != null ? existingDepartment.departmentType : "PRODUCT");
            taxEnabledCheck.setSelected(existingDepartment.taxEnabled);

            hideOnRegisterCheck.setSelected(existingDepartment.hideOnRegister);
            ebtEligibleCheck.setSelected(existingDepartment.ebtEligible);
            excludeFromGlobalPriceIncreaseCheck.setSelected(existingDepartment.excludeFromGlobalPriceIncrease);
            noPointsEarningCheck.setSelected(existingDepartment.noPointsEarning);
            if (existingDepartment.ageVerification != null) {
                ageVerificationField.setText(String.valueOf(existingDepartment.ageVerification));
            }

            // Populate multipack discount fields
            if (multipackEnabledCheck != null) {
                multipackEnabledCheck.setSelected(existingDepartment.multipackEnabled);
            }
            if (existingDepartment.multipackDiscountType != null) {
                if ("PERCENT".equalsIgnoreCase(existingDepartment.multipackDiscountType)) {
                    multipackPercentRadio.setSelected(true);
                } else if ("AMOUNT".equalsIgnoreCase(existingDepartment.multipackDiscountType)) {
                    multipackAmountRadio.setSelected(true);
                }
            }
            if (existingDepartment.multipackDiscountValue != null) {
                multipackDiscountValueField.setText(String.format("%.2f", existingDepartment.multipackDiscountValue));
            }
            if (existingDepartment.multipackMinQuantity != null) {
                multipackMinQuantityField.setText(String.valueOf(existingDepartment.multipackMinQuantity));
            }
            if (multipackRequiresApprovalCheck != null) {
                multipackRequiresApprovalCheck.setSelected(existingDepartment.multipackRequiresApproval);
            }

            // Set parent department
            if (existingDepartment.parentId != null) {
                try {
                    List<ProductManagementService.Department> departments = productService.getDepartments();
                    for (ProductManagementService.Department dept : departments) {
                        if (dept.id.equals(existingDepartment.parentId)) {
                            parentCombo.setValue(dept.name);
                            break;
                        }
                    }
                } catch (SQLException e) {
                    logger.error("Error finding parent department", e);
                }
            }
        } else if (departmentName != null) {
            // Backward compatibility: just set the name
            nameField.setText(departmentName);
        }
    }

    private void setupFieldInteractions() {
    }

    private boolean validateForm() {
        // Validate department name (required)
        String nameText = nameField.getText();
        // #region agent log
        try {
            PrintWriter logWriter = new PrintWriter(new FileWriter(
                    "/Users/sanjog/projects/retail_solutions/product/mobile/pos-system/.cursor/debug.log", true));
            logWriter.println("{\"id\":\"log_" + System.currentTimeMillis() + "_dept_validate\",\"timestamp\":"
                    + System.currentTimeMillis()
                    + ",\"location\":\"CategoryFormDialog.java:314\",\"message\":\"Validating department form\",\"data\":{\"nameText\":\""
                    + (nameText != null ? nameText : "null") + "\",\"departmentType\":\""
                    + (departmentTypeCombo.getValue() != null ? departmentTypeCombo.getValue() : "null")
                    + "\",\"taxEnabled\":" + taxEnabledCheck.isSelected()
                    + ",\"runId\":\"dept-validation-fix\"},\"sessionId\":\"debug-session\"}");
            logWriter.close();
        } catch (Exception e) {
        }
        // #endregion
        if (nameText == null || nameText.trim().isEmpty()) {
            showAlert("Validation Error", "Department name is required and cannot be empty");
            return false;
        }

        String trimmedName = nameText.trim();
        if (trimmedName.isEmpty()) {
            showAlert("Validation Error", "Department name cannot be only whitespace");
            return false;
        }

        // Validate department type (required)
        String departmentType = departmentTypeCombo.getValue();
        if (departmentType == null || (!departmentType.equals("PRODUCT") && !departmentType.equals("SERVICE"))) {
            showAlert("Validation Error", "Department type must be either PRODUCT or SERVICE");
            return false;
        }

        // Validate age verification if provided
        String ageText = ageVerificationField.getText();
        if (ageText != null && !ageText.trim().isEmpty()) {
            try {
                int age = Integer.parseInt(ageText.trim());
                if (age < 0 || age > 100) {
                    showAlert("Validation Error", "Age must be between 0 and 100");
                    return false;
                }
            } catch (NumberFormatException e) {
                showAlert("Validation Error", "Invalid age. Please enter a valid number (e.g., 21).");
                return false;
            }
        }

        // Validate multipack discount settings if enabled
        if (multipackEnabledCheck != null && multipackEnabledCheck.isSelected()) {
            // Validate discount value
            String discountValueText = multipackDiscountValueField.getText();
            if (discountValueText == null || discountValueText.trim().isEmpty()) {
                showAlert("Validation Error", "Discount value is required when multi-pack discount is enabled");
                return false;
            }
            try {
                double discountValue = Double.parseDouble(discountValueText.trim());
                if (discountValue <= 0) {
                    showAlert("Validation Error", "Discount value must be greater than 0");
                    return false;
                }
            } catch (NumberFormatException e) {
                showAlert("Validation Error", "Invalid discount value. Please enter a valid number.");
                return false;
            }

            // Validate minimum quantity
            String minQtyText = multipackMinQuantityField.getText();
            if (minQtyText == null || minQtyText.trim().isEmpty()) {
                showAlert("Validation Error", "Minimum quantity is required when multi-pack discount is enabled");
                return false;
            }
            try {
                int minQty = Integer.parseInt(minQtyText.trim());
                if (minQty < 2) {
                    showAlert("Validation Error", "Minimum quantity must be at least 2");
                    return false;
                }
            } catch (NumberFormatException e) {
                showAlert("Validation Error", "Invalid minimum quantity. Please enter a valid number.");
                return false;
            }
        }

        return true;
    }

    private CategoryFormResult createResult() {
        // Name is already validated, but add safety check
        String nameText = nameField.getText();
        if (nameText == null || nameText.trim().isEmpty()) {
            throw new IllegalStateException("Department name cannot be null or empty");
        }
        String name = nameText.trim();

        // Icon is optional
        String iconText = iconField.getText();
        String icon = (iconText == null || iconText.trim().isEmpty()) ? null : iconText.trim();

        String parentId = null;

        // Get parent ID from selected name
        String selectedParent = parentCombo.getValue();
        if (selectedParent != null && !selectedParent.isEmpty()) {
            try {
                List<ProductManagementService.Department> departments = productService.getDepartments();
                for (ProductManagementService.Department dept : departments) {
                    if (dept.name.equals(selectedParent)) {
                        parentId = dept.id;
                        break;
                    }
                }
            } catch (SQLException e) {
                logger.error("Error getting parent department ID", e);
            }
        }

        // Department type is already validated
        String departmentType = departmentTypeCombo.getValue();
        if (departmentType == null) {
            departmentType = "PRODUCT"; // Default fallback
        }

        boolean taxEnabled = taxEnabledCheck.isSelected();

        // Tax rate - auto-added from global store settings if enabled
        Double taxRate = null;

        boolean hideOnRegister = hideOnRegisterCheck.isSelected();
        boolean ebtEligible = ebtEligibleCheck.isSelected();
        boolean excludeFromGlobalPriceIncrease = excludeFromGlobalPriceIncreaseCheck.isSelected();
        boolean noPointsEarning = noPointsEarningCheck.isSelected();

        // Age verification - optional
        Integer ageVerification = null;
        String ageText = ageVerificationField.getText();
        if (ageText != null && !ageText.trim().isEmpty()) {
            try {
                ageVerification = Integer.parseInt(ageText.trim());
            } catch (NumberFormatException e) {
                // Already validated, shouldn't happen
                logger.warn("Failed to parse age verification despite validation: {}", ageText);
            }
        }

        // Multi-pack discount settings
        Boolean multipackEnabled = multipackEnabledCheck != null && multipackEnabledCheck.isSelected();
        String multipackDiscountType = null;
        Double multipackDiscountValue = null;
        Integer multipackMinQuantity = null;
        Boolean multipackRequiresApproval = false;

        if (multipackEnabled) {
            // Get discount type
            if (multipackPercentRadio != null && multipackPercentRadio.isSelected()) {
                multipackDiscountType = "PERCENT";
            } else if (multipackAmountRadio != null && multipackAmountRadio.isSelected()) {
                multipackDiscountType = "AMOUNT";
            }

            // Get discount value
            String discountValueText = multipackDiscountValueField.getText();
            if (discountValueText != null && !discountValueText.trim().isEmpty()) {
                try {
                    multipackDiscountValue = Double.parseDouble(discountValueText.trim());
                } catch (NumberFormatException e) {
                    logger.warn("Failed to parse multipack discount value: {}", discountValueText);
                }
            }

            // Get minimum quantity
            String minQtyText = multipackMinQuantityField.getText();
            if (minQtyText != null && !minQtyText.trim().isEmpty()) {
                try {
                    multipackMinQuantity = Integer.parseInt(minQtyText.trim());
                } catch (NumberFormatException e) {
                    logger.warn("Failed to parse multipack min quantity: {}", minQtyText);
                }
            }

            // Get requires approval
            multipackRequiresApproval = multipackRequiresApprovalCheck != null
                    && multipackRequiresApprovalCheck.isSelected();
        }

        return new CategoryFormResult(
                existingDepartmentId,
                name,
                icon,
                parentId,
                departmentType,
                taxEnabled,
                taxRate,
                hideOnRegister,
                ebtEligible,
                excludeFromGlobalPriceIncrease,
                noPointsEarning,
                ageVerification,
                multipackEnabled,
                multipackDiscountType,
                multipackDiscountValue,
                multipackMinQuantity,
                multipackRequiresApproval);
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
     * Result class for category form dialog - matches backend schema
     */
    public static class CategoryFormResult {
        public final String departmentId; // null for new departments
        public final String name;
        public final String icon;
        public final String parentId;
        public final String departmentType; // PRODUCT or SERVICE
        public final boolean taxEnabled;
        public final Double taxRate;
        public final boolean hideOnRegister;
        public final boolean ebtEligible;
        public final boolean excludeFromGlobalPriceIncrease;
        public final boolean noPointsEarning;
        public final Integer ageVerification;
        public final Boolean multipackEnabled;
        public final String multipackDiscountType;
        public final Double multipackDiscountValue;
        public final Integer multipackMinQuantity;
        public final Boolean multipackRequiresApproval;

        public CategoryFormResult(String departmentId, String name, String icon, String parentId,
                String departmentType, boolean taxEnabled, Double taxRate,
                boolean hideOnRegister, boolean ebtEligible,
                boolean excludeFromGlobalPriceIncrease, boolean noPointsEarning,
                Integer ageVerification, Boolean multipackEnabled, String multipackDiscountType,
                Double multipackDiscountValue, Integer multipackMinQuantity, Boolean multipackRequiresApproval) {
            this.departmentId = departmentId;
            this.name = name;
            this.icon = icon;
            this.parentId = parentId;
            this.departmentType = departmentType;
            this.taxEnabled = taxEnabled;
            this.taxRate = taxRate;
            this.hideOnRegister = hideOnRegister;
            this.ebtEligible = ebtEligible;
            this.excludeFromGlobalPriceIncrease = excludeFromGlobalPriceIncrease;
            this.noPointsEarning = noPointsEarning;
            this.ageVerification = ageVerification;
            this.multipackEnabled = multipackEnabled;
            this.multipackDiscountType = multipackDiscountType;
            this.multipackDiscountValue = multipackDiscountValue;
            this.multipackMinQuantity = multipackMinQuantity;
            this.multipackRequiresApproval = multipackRequiresApproval;
        }
    }
}
