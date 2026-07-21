package com.pos.ui;

import com.pos.model.Vendor;
import com.pos.service.VendorService;
import com.pos.ui.components.CompactAlphanumericKeypad;
import com.pos.ui.components.ToastNotification;
import com.pos.ui.keyboard.KeyboardManager;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.math.BigDecimal;

/**
 * Premium, compact AddVendorScreen.
 * Optimized for touch interfaces with a stationary integrated keyboard.
 * Forces zero-overlap by suppressing global floating keyboards.
 */
public class AddVendorScreen extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(AddVendorScreen.class);
    
    private final VendorService vendorService;
    private final Runnable onBack;
    
    private TextField nameField;
    private TextField phoneField;
    private TextField emailField;
    private TextArea addressField;
    private TextArea notesField;
    
    private TextInputControl activeField;
    private CompactAlphanumericKeypad keypad;

    public AddVendorScreen(Runnable onBack) {
        this.vendorService = VendorService.getInstance();
        this.onBack = onBack;
        
        // Suppress and hide floating keyboard immediately
        KeyboardManager.setGloballyDisabled(true);
        KeyboardManager.hide();
        
        initializeUI();
        
        // Reinforce keyboard suppression after layout
        javafx.application.Platform.runLater(() -> {
            KeyboardManager.hide();
            if (getScene() != null) {
                getScene().focusOwnerProperty().addListener((obs, oldNode, newNode) -> {
                    if (newNode instanceof TextInputControl) {
                        KeyboardManager.hide(); // Double-safe: never let floating KB show here
                    }
                });
            }
        });
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #f8fafc;");
        setPadding(new Insets(10));

        // Top Header
        setTop(createHeader());

        // Body Content - 0 spacing between form and keyboard for tight fit
        HBox body = new HBox(0);
        body.setPadding(new Insets(10, 0, 0, 0));
        VBox.setVgrow(body, Priority.ALWAYS);

        // Left: Compact Scrollable Form
        VBox formColumn = createFormColumn();
        formColumn.setMinWidth(420);
        formColumn.setMaxWidth(450); // Keep form small and narrow

        // Right: Keyboard Sidebar (Takes remaining space)
        VBox keyboardSidebar = createKeyboardSidebar();
        HBox.setHgrow(keyboardSidebar, Priority.ALWAYS);

        body.getChildren().addAll(formColumn, keyboardSidebar);
        setCenter(body);
        
        // Initial setup
        javafx.application.Platform.runLater(() -> {
            nameField.requestFocus();
            activeField = nameField;
        });
    }

    private VBox createHeader() {
        VBox header = new VBox(0);
        header.setPadding(new Insets(10, 20, 10, 20));
        header.setStyle("-fx-background-color: #1e293b; -fx-background-radius: 12;");

        HBox topRow = new HBox();
        topRow.setAlignment(Pos.CENTER_LEFT);

        Button backBtn = new Button("←  Back");
        backBtn.setStyle("-fx-background-color: rgba(255,255,255,0.1); -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 8 15; -fx-cursor: hand;");
        backBtn.setOnAction(e -> {
            KeyboardManager.setGloballyDisabled(false); // Restore keyboard
            onBack.run();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        VBox titleBox = new VBox(1);
        titleBox.setAlignment(Pos.CENTER_RIGHT);
        Label mainTitle = new Label("Add Vendor");
        mainTitle.setStyle("-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: 900;");
        Label subTitle = new Label("Supplier Registration");
        subTitle.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 10px; -fx-font-weight: 600;");
        titleBox.getChildren().addAll(mainTitle, subTitle);

        topRow.getChildren().addAll(backBtn, spacer, titleBox);
        header.getChildren().add(topRow);
        return header;
    }

    private VBox createFormColumn() {
        VBox container = new VBox(0);
        container.setPadding(new Insets(0, 5, 0, 0));
        
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        
        VBox form = new VBox(10);
        form.setPadding(new Insets(15));
        form.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.02), 5, 0, 0, 1);");

        nameField = createStyledTextField("Company Name *", "Acme Corp");
        phoneField = createStyledTextField("Phone", "555-0100");
        emailField = createStyledTextField("Email", "email@example.com");
        addressField = createStyledTextArea("Address", "Street, City...");
        notesField = createStyledTextArea("Notes", "Payment terms...");

        form.getChildren().addAll(
            createSectionLabel("IDENTITY & CONTACT"),
            createFormGroup("NAME *", nameField),
            createFormGroup("PHONE", phoneField),
            createFormGroup("EMAIL", emailField),
            new Separator(),
            createSectionLabel("DETAILS"),
            createFormGroup("ADDRESS", addressField),
            createFormGroup("NOTES", notesField)
        );

        // Action Buttons
        HBox actions = new HBox(10);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new Insets(10, 0, 0, 0));

        Button discardBtn = new Button("Clear");
        discardBtn.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #64748b; -fx-font-weight: bold; -fx-font-size: 12px; -fx-background-radius: 8; -fx-padding: 8 15; -fx-cursor: hand;");
        discardBtn.setOnAction(e -> clearForm());

        Button saveBtn = new Button("SAVE VENDOR");
        saveBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: 900; -fx-font-size: 14px; -fx-background-radius: 8; -fx-padding: 8 25; -fx-cursor: hand;");
        saveBtn.setOnAction(e -> saveVendor());

        actions.getChildren().addAll(discardBtn, saveBtn);
        form.getChildren().add(actions);

        scrollPane.setContent(form);
        container.getChildren().add(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        
        return container;
    }

    private VBox createKeyboardSidebar() {
        VBox sidebar = new VBox(8);
        sidebar.setPadding(new Insets(10));
        sidebar.setStyle("-fx-background-color: #1e293b; -fx-background-radius: 12;");
        sidebar.setAlignment(Pos.CENTER);

        Label kbHeader = new Label("STATIONARY KEYBOARD");
        kbHeader.setStyle("-fx-text-fill: #475569; -fx-font-weight: 900; -fx-font-size: 10px; -fx-letter-spacing: 1px;");

        keypad = new CompactAlphanumericKeypad();
        keypad.setListener(this::handleKeypadInput);
        
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        HBox navRow = new HBox(10);
        navRow.setAlignment(Pos.CENTER);
        
        Button prevField = createNavButton("PREV", false);
        Button nextField = createNavButton("NEXT ⬇", true);
        
        navRow.getChildren().addAll(prevField, nextField);

        sidebar.getChildren().addAll(kbHeader, keypad, spacer, navRow);
        return sidebar;
    }

    private Button createNavButton(String text, boolean next) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(btn, Priority.ALWAYS);
        btn.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-text-fill: #94a3b8; -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 10; -fx-background-radius: 8; -fx-cursor: hand;");
        btn.setOnAction(e -> { if(next) focusNext(); else focusPrevious(); });
        return btn;
    }

    private TextField createStyledTextField(String label, String prompt) {
        TextField f = new TextField();
        f.setPromptText(prompt);
        f.setPrefHeight(42);
        f.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-width: 1; -fx-border-radius: 6; -fx-background-radius: 6; -fx-font-size: 14px; -fx-padding: 0 12;");
        f.focusedProperty().addListener((obs, ov, nv) -> {
            if (nv) {
                activeField = f;
                f.setStyle("-fx-background-color: white; -fx-border-color: #2563eb; -fx-border-width: 1.5; -fx-border-radius: 6; -fx-background-radius: 6; -fx-font-size: 14px; -fx-padding: 0 12;");
            } else {
                f.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-width: 1; -fx-border-radius: 6; -fx-background-radius: 6; -fx-font-size: 14px; -fx-padding: 0 12;");
            }
        });
        return f;
    }

    private TextArea createStyledTextArea(String label, String prompt) {
        TextArea a = new TextArea();
        a.setPromptText(prompt);
        a.setPrefHeight(70);
        a.setWrapText(true);
        a.setStyle("-fx-control-inner-background: #f8fafc; -fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-width: 1; -fx-border-radius: 6; -fx-background-radius: 6; -fx-font-size: 14px;");
        a.focusedProperty().addListener((obs, ov, nv) -> {
            if (nv) {
                activeField = a;
                a.setStyle("-fx-control-inner-background: white; -fx-background-color: white; -fx-border-color: #2563eb; -fx-border-width: 1.5; -fx-border-radius: 6; -fx-background-radius: 6; -fx-font-size: 14px;");
            } else {
                a.setStyle("-fx-control-inner-background: #f8fafc; -fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-width: 1; -fx-border-radius: 6; -fx-background-radius: 6; -fx-font-size: 14px;");
            }
        });
        return a;
    }

    private VBox createFormGroup(String labelText, Control field) {
        VBox group = new VBox(3);
        Label l = new Label(labelText);
        l.setStyle("-fx-text-fill: #64748b; -fx-font-weight: 800; -fx-font-size: 9px;");
        group.getChildren().addAll(l, field);
        HBox.setHgrow(group, Priority.ALWAYS);
        return group;
    }

    private Label createSectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #2563eb; -fx-font-weight: 900; -fx-font-size: 10px; -fx-letter-spacing: 0.5px; -fx-padding: 5 0;");
        return l;
    }

    private void handleKeypadInput(String key) {
        if (activeField == null) return;
        
        switch (key) {
            case "CLEAR": activeField.clear(); break;
            case "⌫": handleBackspace(); break;
            case "⏎": case "ENTER": focusNext(); break;
            case " ": insertText(" "); break;
            default: insertText(key); break;
        }
        activeField.requestFocus();
    }

    private void handleBackspace() {
        String current = activeField.getText();
        if (current != null && !current.isEmpty()) {
            int caret = activeField.getCaretPosition();
            if (caret > 0) {
                activeField.setText(current.substring(0, caret - 1) + current.substring(caret));
                activeField.positionCaret(caret - 1);
            }
        }
    }

    private void insertText(String text) {
        String current = activeField.getText() != null ? activeField.getText() : "";
        int caret = activeField.getCaretPosition();
        activeField.setText(current.substring(0, caret) + text + current.substring(caret));
        activeField.positionCaret(caret + text.length());
    }

    private void focusNext() {
        if (activeField == nameField) phoneField.requestFocus();
        else if (activeField == phoneField) emailField.requestFocus();
        else if (activeField == emailField) addressField.requestFocus();
        else if (activeField == addressField) notesField.requestFocus();
        else nameField.requestFocus();
    }

    private void focusPrevious() {
        if (activeField == notesField) addressField.requestFocus();
        else if (activeField == addressField) emailField.requestFocus();
        else if (activeField == emailField) phoneField.requestFocus();
        else if (activeField == phoneField) nameField.requestFocus();
        else notesField.requestFocus();
    }

    private void clearForm() {
        nameField.clear(); phoneField.clear(); emailField.clear();
        addressField.clear(); notesField.clear();
        nameField.requestFocus();
    }

    private void saveVendor() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            ToastNotification.showWarning("Name required", getScene().getWindow());
            return;
        }

        try {
            Vendor v = new Vendor();
            v.setId(UUID.randomUUID().toString());
            v.setName(name);
            v.setPhone(phoneField.getText().trim());
            v.setEmail(emailField.getText().trim());
            v.setAddress(addressField.getText().trim());
            v.setNotes(notesField.getText().trim());
            v.setActive(true);
            v.setCommissionRate(BigDecimal.ZERO);
            v.setDefaultCostMargin(BigDecimal.ZERO);

            vendorService.createVendor(v);
            ToastNotification.showSuccess("Vendor SAVED", getScene().getWindow());
            KeyboardManager.setGloballyDisabled(false);
            onBack.run();
        } catch (Exception e) {
            ToastNotification.showError("Error: " + e.getMessage(), getScene().getWindow());
        }
    }
}
