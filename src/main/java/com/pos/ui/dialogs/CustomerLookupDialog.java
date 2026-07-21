package com.pos.ui.dialogs;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Window;
import com.pos.model.Customer;
import com.pos.service.CustomerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Dialog for looking up and selecting customers
 * Supports creating new customers and viewing loyalty info
 */
public class CustomerLookupDialog extends Dialog<Customer> {

    private static final Logger logger = LoggerFactory.getLogger(CustomerLookupDialog.class);
    private final CustomerService customerService;
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

    // UI Components
    private TextField searchField;
    private ListView<Customer> customerListView;
    private VBox customerDetailsPane;
    private ObservableList<Customer> foundCustomers;

    // New customer form
    private TextField firstNameField;
    private TextField lastNameField;
    private TextField emailField;
    private TextField phoneField;

    private Customer selectedCustomer;

    public CustomerLookupDialog() {
        this(null);
    }

    public CustomerLookupDialog(Window owner) {
        this.customerService = CustomerService.getInstance();
        this.foundCustomers = FXCollections.observableArrayList();

        // Set owner and modality to keep dialog in same window
        if (owner != null) {
            initOwner(owner);
        }
        initModality(Modality.APPLICATION_MODAL);

        setTitle("Customer Lookup");
        setHeaderText("Search for a customer or create a new one");

        initializeUI();
        setupResultConverter();
    }

    private void initializeUI() {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        // tabPane.setPrefWidth(700);
        // tabPane.setPrefHeight(500);
        com.pos.util.ResponsiveHelper.setupResponsiveDialog(this, 0.75, 0.85);

        // Search tab
        Tab searchTab = new Tab("Search Customer");
        searchTab.setContent(createSearchPane());

        // New customer tab
        Tab newTab = new Tab("New Customer");
        newTab.setContent(createNewCustomerPane());

        tabPane.getTabs().addAll(searchTab, newTab);

        getDialogPane().setContent(tabPane);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Disable OK until customer is selected
        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setText("Select Customer");
        okButton.setDisable(true);
    }

    private VBox createSearchPane() {
        VBox pane = new VBox(15);
        pane.setPadding(new Insets(20));

        // Search box
        HBox searchBox = new HBox(10);
        searchBox.setAlignment(Pos.CENTER_LEFT);

        searchField = new TextField();
        searchField.setPromptText("Search by name, phone, or email...");
        // searchField.setPrefWidth(350);
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        Button searchBtn = new Button("Search");
        searchBtn.setStyle("-fx-background-color: #1E88E5; -fx-text-fill: white;");
        searchBtn.setOnAction(e -> searchCustomers());
        searchField.setOnAction(e -> searchCustomers());

        searchBox.getChildren().addAll(searchField, searchBtn);

        // Split: List on left, Details on right
        HBox contentBox = new HBox(15);
        VBox.setVgrow(contentBox, Priority.ALWAYS);

        // Customer list
        VBox listPane = new VBox(10);
        listPane.setPrefWidth(280);

        Label listLabel = new Label("Results");
        listLabel.setStyle("-fx-font-weight: bold;");

        customerListView = new ListView<>(foundCustomers);
        customerListView.setCellFactory(lv -> new CustomerListCell());
        customerListView.getSelectionModel().selectedItemProperty().addListener((obs, old, newVal) -> {
            selectedCustomer = newVal;
            updateCustomerDetails(newVal);
            updateOkButton();
        });
        VBox.setVgrow(customerListView, Priority.ALWAYS);

        listPane.getChildren().addAll(listLabel, customerListView);

        // Customer details
        customerDetailsPane = createCustomerDetailsPane();
        HBox.setHgrow(customerDetailsPane, Priority.ALWAYS);

        contentBox.getChildren().addAll(listPane, customerDetailsPane);

        pane.getChildren().addAll(searchBox, contentBox);
        return pane;
    }

    private VBox createCustomerDetailsPane() {
        VBox pane = new VBox(10);
        pane.setPadding(new Insets(15));
        pane.setStyle(
                "-fx-background-color: #f5f5f5; " +
                        "-fx-background-radius: 8; " +
                        "-fx-border-color: #e0e0e0; " +
                        "-fx-border-radius: 8;");

        Label placeholder = new Label("Select a customer to view details");
        placeholder.setStyle("-fx-text-fill: #999;");
        pane.getChildren().add(placeholder);
        pane.setAlignment(Pos.CENTER);

        return pane;
    }

    private void updateCustomerDetails(Customer customer) {
        customerDetailsPane.getChildren().clear();

        if (customer == null) {
            Label placeholder = new Label("Select a customer to view details");
            placeholder.setStyle("-fx-text-fill: #999;");
            customerDetailsPane.getChildren().add(placeholder);
            customerDetailsPane.setAlignment(Pos.CENTER);
            return;
        }

        customerDetailsPane.setAlignment(Pos.TOP_LEFT);

        // Header with name and tier
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label(customer.getFullName());
        nameLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Label tierLabel = new Label(customer.getTierDisplayName());
        tierLabel.setStyle(
                "-fx-background-color: " + getTierColor(customer.getMembershipTier()) + "; " +
                        "-fx-text-fill: white; " +
                        "-fx-padding: 3 8; " +
                        "-fx-background-radius: 10; " +
                        "-fx-font-size: 11px;");

        header.getChildren().addAll(nameLabel, tierLabel);

        // Contact info
        VBox contactBox = new VBox(5);
        contactBox.setPadding(new Insets(10, 0, 10, 0));

        if (customer.getPhone() != null && !customer.getPhone().isEmpty()) {
            Label phoneLabel = new Label("📞 " + customer.getPhone());
            contactBox.getChildren().add(phoneLabel);
        }
        if (customer.getEmail() != null && !customer.getEmail().isEmpty()) {
            Label emailLabel = new Label("✉ " + customer.getEmail());
            contactBox.getChildren().add(emailLabel);
        }

        // Loyalty stats
        GridPane statsGrid = new GridPane();
        statsGrid.setHgap(20);
        statsGrid.setVgap(10);
        statsGrid.setPadding(new Insets(10, 0, 10, 0));

        // Points
        VBox pointsBox = createStatBox("Loyalty Points",
                String.format("%.0f pts", customer.getLoyaltyPoints()),
                "Worth " + currencyFormat.format(customerService.getPointsValue(customer.getLoyaltyPoints())));

        // Total spent
        VBox spentBox = createStatBox("Total Spent",
                currencyFormat.format(customer.getTotalSpent()),
                null);

        // Visits
        VBox visitsBox = createStatBox("Visits",
                String.valueOf(customer.getVisitCount()),
                null);

        statsGrid.add(pointsBox, 0, 0);
        statsGrid.add(spentBox, 1, 0);
        statsGrid.add(visitsBox, 2, 0);

        // Last visit
        Label lastVisitLabel = new Label();
        if (customer.getLastVisit() != null) {
            lastVisitLabel.setText("Last visit: " +
                    customer.getLastVisit().format(DateTimeFormatter.ofPattern("MMM d, yyyy")));
        } else {
            lastVisitLabel.setText("No previous visits");
        }
        lastVisitLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

        // Tier discount info
        BigDecimal discount = customer.getTierDiscount();
        Label discountLabel = new Label();
        if (discount.compareTo(BigDecimal.ZERO) > 0) {
            discountLabel.setText("🎁 " + discount.multiply(new BigDecimal("100")).intValue() +
                    "% member discount available");
            discountLabel.setStyle("-fx-text-fill: #43a047; -fx-font-weight: bold;");
        }

        customerDetailsPane.getChildren().addAll(header, contactBox, statsGrid, lastVisitLabel);
        if (discount.compareTo(BigDecimal.ZERO) > 0) {
            customerDetailsPane.getChildren().add(discountLabel);
        }
    }

    private VBox createStatBox(String label, String value, String subtext) {
        VBox box = new VBox(2);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(10));
        box.setStyle(
                "-fx-background-color: white; " +
                        "-fx-background-radius: 5; " +
                        "-fx-border-color: #e0e0e0; " +
                        "-fx-border-radius: 5;");

        Label valueLabel = new Label(value);
        valueLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #1E88E5;");

        Label labelLabel = new Label(label);
        labelLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");

        box.getChildren().addAll(valueLabel, labelLabel);

        if (subtext != null) {
            Label subtextLabel = new Label(subtext);
            subtextLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");
            box.getChildren().add(subtextLabel);
        }

        return box;
    }

    private String getTierColor(String tier) {
        if (tier == null)
            return "#9e9e9e";
        return switch (tier.toUpperCase()) {
            case "GOLD" -> "#ffc107";
            case "SILVER" -> "#9e9e9e";
            case "BRONZE" -> "#cd7f32";
            default -> "#607d8b";
        };
    }

    private VBox createNewCustomerPane() {
        VBox pane = new VBox(15);
        pane.setPadding(new Insets(20));
        pane.setAlignment(Pos.TOP_CENTER);

        Label title = new Label("Create New Customer");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        GridPane form = new GridPane();
        form.setHgap(15);
        form.setVgap(15);
        form.setAlignment(Pos.CENTER);

        firstNameField = new TextField();
        firstNameField.setPromptText("First Name");
        firstNameField.setPrefWidth(200);

        lastNameField = new TextField();
        lastNameField.setPromptText("Last Name");
        lastNameField.setPrefWidth(200);

        phoneField = new TextField();
        phoneField.setPromptText("Phone Number");
        phoneField.setPrefWidth(200);

        emailField = new TextField();
        emailField.setPromptText("Email (optional)");
        emailField.setPrefWidth(200);

        form.add(new Label("First Name:"), 0, 0);
        form.add(firstNameField, 1, 0);
        form.add(new Label("Last Name:"), 0, 1);
        form.add(lastNameField, 1, 1);
        form.add(new Label("Phone:"), 0, 2);
        form.add(phoneField, 1, 2);
        form.add(new Label("Email:"), 0, 3);
        form.add(emailField, 1, 3);

        Button createBtn = new Button("Create Customer");
        createBtn.setStyle(
                "-fx-background-color: #43a047; -fx-text-fill: white; " +
                        "-fx-font-weight: bold; -fx-padding: 10 20;");
        createBtn.setOnAction(e -> createNewCustomer());

        pane.getChildren().addAll(title, form, createBtn);
        return pane;
    }

    private void searchCustomers() {
        String query = searchField.getText().trim();
        if (query.isEmpty())
            return;

        new Thread(() -> {
            List<Customer> customers = customerService.searchCustomers(query);
            Platform.runLater(() -> {
                foundCustomers.clear();
                foundCustomers.addAll(customers);

                if (customers.isEmpty()) {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("No Results");
                    alert.setHeaderText("No customers found");
                    alert.setContentText("No customers match your search. You can create a new customer.");
                    alert.initOwner(getDialogPane().getScene().getWindow());
                    alert.initModality(Modality.APPLICATION_MODAL);
                    alert.showAndWait();
                }
            });
        }).start();
    }

    private void createNewCustomer() {
        String firstName = firstNameField.getText().trim();
        String lastName = lastNameField.getText().trim();
        String phone = phoneField.getText().trim();
        String email = emailField.getText().trim();

        if (firstName.isEmpty() && lastName.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Missing Information");
            alert.setHeaderText("Name required");
            alert.setContentText("Please enter at least a first or last name.");
            alert.initOwner(getDialogPane().getScene().getWindow());
            alert.initModality(Modality.APPLICATION_MODAL);
            alert.showAndWait();
            return;
        }

        if (phone.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Missing Information");
            alert.setHeaderText("Phone required");
            alert.setContentText("Please enter a phone number for the customer.");
            alert.initOwner(getDialogPane().getScene().getWindow());
            alert.initModality(Modality.APPLICATION_MODAL);
            alert.showAndWait();
            return;
        }

        Customer newCustomer = customerService.createCustomer(firstName, lastName, email, phone);
        if (newCustomer != null) {
            selectedCustomer = newCustomer;
            setResult(newCustomer);
            close();
        } else {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText("Failed to create customer");
            alert.setContentText("An error occurred while creating the customer.");
            alert.initOwner(getDialogPane().getScene().getWindow());
            alert.initModality(Modality.APPLICATION_MODAL);
            alert.showAndWait();
        }
    }

    private void updateOkButton() {
        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(selectedCustomer == null);
    }

    private void setupResultConverter() {
        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                return selectedCustomer;
            }
            return null;
        });
    }

    // Custom cell for customer list
    private class CustomerListCell extends ListCell<Customer> {
        @Override
        protected void updateItem(Customer customer, boolean empty) {
            super.updateItem(customer, empty);

            if (empty || customer == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            VBox content = new VBox(3);
            content.setPadding(new Insets(5));

            HBox nameBox = new HBox(5);
            nameBox.setAlignment(Pos.CENTER_LEFT);

            Label nameLabel = new Label(customer.getFullName());
            nameLabel.setStyle("-fx-font-weight: bold;");

            String tier = customer.getMembershipTier();
            if (tier != null && !tier.equals("STANDARD")) {
                Label tierBadge = new Label(tier.substring(0, 1));
                tierBadge.setStyle(
                        "-fx-background-color: " + getTierColor(tier) + "; " +
                                "-fx-text-fill: white; " +
                                "-fx-padding: 1 4; " +
                                "-fx-background-radius: 3; " +
                                "-fx-font-size: 9px;");
                nameBox.getChildren().addAll(nameLabel, tierBadge);
            } else {
                nameBox.getChildren().add(nameLabel);
            }

            Label phoneLabel = new Label(customer.getPhone() != null ? customer.getPhone() : "");
            phoneLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");

            Label pointsLabel = new Label(String.format("%.0f pts", customer.getLoyaltyPoints()));
            pointsLabel.setStyle("-fx-text-fill: #1E88E5; -fx-font-size: 10px;");

            content.getChildren().addAll(nameBox, phoneLabel, pointsLabel);
            setGraphic(content);
        }
    }
}
