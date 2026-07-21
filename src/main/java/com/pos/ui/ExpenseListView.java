package com.pos.ui;

import com.pos.model.Expense;
import com.pos.model.ExpenseCategory;
import com.pos.service.ExpenseCategoryService;
import com.pos.service.ExpenseService;
import com.pos.ui.dialogs.ExpenseDialog;
import com.pos.util.ErrorHandler;
import com.pos.util.ExportService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Expense List View - Display and manage expenses
 */
public class ExpenseListView extends BorderPane {

    private static final Logger logger = LoggerFactory.getLogger(ExpenseListView.class);
    private static final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

    private final ExpenseService expenseService;
    private final ExpenseCategoryService categoryService;

    // UI Components
    private TableView<ExpenseRow> expenseTable;
    private ObservableList<ExpenseRow> expenseList;
    private DatePicker startDatePicker;
    private DatePicker endDatePicker;
    private ComboBox<String> categoryFilter;
    private Label totalExpensesLabel;
    private Button addExpenseButton;
    private Button refreshButton;
    private Button exportButton;

    private LocalDate currentStartDate;
    private LocalDate currentEndDate;
    private Runnable onBack;

    public ExpenseListView() {
        this(null);
    }

    public ExpenseListView(Runnable onBack) {
        this.expenseService = ExpenseService.getInstance();
        this.categoryService = ExpenseCategoryService.getInstance();
        this.expenseList = FXCollections.observableArrayList();
        this.onBack = onBack;

        // Default to current month
        LocalDate now = LocalDate.now();
        this.currentStartDate = now.withDayOfMonth(1);
        this.currentEndDate = now;

        initializeUI();
        loadExpenses();
        loadCategories();
    }

    private void initializeUI() {
        // Top section - Filters and controls
        VBox topSection = new VBox(10);
        topSection.setPadding(new Insets(15));
        topSection.setStyle("-fx-background-color: #f5f5f5;");

        // Date range controls
        HBox dateRangeBox = new HBox(10);
        dateRangeBox.setAlignment(Pos.CENTER_LEFT);

        Label startDateLabel = new Label("From:");
        startDatePicker = new DatePicker(currentStartDate);
        startDatePicker.setPrefWidth(150);

        Label endDateLabel = new Label("To:");
        endDatePicker = new DatePicker(currentEndDate);
        endDatePicker.setPrefWidth(150);

        // Category filter
        Label categoryLabel = new Label("Category:");
        categoryFilter = new ComboBox<>();
        categoryFilter.setPrefWidth(200);
        categoryFilter.getItems().add("All Categories");
        categoryFilter.setValue("All Categories");

        // Buttons
        refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> loadExpenses());

        addExpenseButton = new Button("Add Expense");
        addExpenseButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white;");
        addExpenseButton.setOnAction(e -> showAddExpenseDialog());

        exportButton = new Button("Export");
        exportButton.setOnAction(e -> exportExpenses());

        dateRangeBox.getChildren().addAll(
            startDateLabel, startDatePicker,
            endDateLabel, endDatePicker,
            categoryLabel, categoryFilter,
            refreshButton, addExpenseButton, exportButton
        );

        // Total expenses display
        HBox totalBox = new HBox(10);
        totalBox.setAlignment(Pos.CENTER_RIGHT);
        Label totalLabel = new Label("Total Expenses:");
        totalLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        totalExpensesLabel = new Label(currencyFormat.format(BigDecimal.ZERO));
        totalExpensesLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px; -fx-text-fill: #d32f2f;");
        totalBox.getChildren().addAll(totalLabel, totalExpensesLabel);

        topSection.getChildren().addAll(dateRangeBox, totalBox);

        // Center - Table
        expenseTable = createExpenseTable();
        ScrollPane scrollPane = new ScrollPane(expenseTable);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);

        // Bottom - Back button
        HBox bottomBox = new HBox();
        bottomBox.setPadding(new Insets(10));
        bottomBox.setAlignment(Pos.CENTER);
        if (onBack != null) {
            Button backButton = new Button("Back");
            backButton.setOnAction(e -> onBack.run());
            bottomBox.getChildren().add(backButton);
        }

        setTop(topSection);
        setCenter(scrollPane);
        setBottom(bottomBox);
    }

    private TableView<ExpenseRow> createExpenseTable() {
        TableView<ExpenseRow> table = new TableView<>();
        table.setItems(expenseList);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        // Date column
        TableColumn<ExpenseRow, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("date"));
        dateCol.setPrefWidth(150);

        // Category column
        TableColumn<ExpenseRow, String> categoryCol = new TableColumn<>("Category");
        categoryCol.setCellValueFactory(new PropertyValueFactory<>("category"));
        categoryCol.setPrefWidth(150);

        // Description column
        TableColumn<ExpenseRow, String> descriptionCol = new TableColumn<>("Description");
        descriptionCol.setCellValueFactory(new PropertyValueFactory<>("description"));
        descriptionCol.setPrefWidth(200);

        // Amount column
        TableColumn<ExpenseRow, String> amountCol = new TableColumn<>("Amount");
        amountCol.setCellValueFactory(new PropertyValueFactory<>("amount"));
        amountCol.setPrefWidth(100);
        amountCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        // Payment Method column
        TableColumn<ExpenseRow, String> paymentMethodCol = new TableColumn<>("Payment Method");
        paymentMethodCol.setCellValueFactory(new PropertyValueFactory<>("paymentMethod"));
        paymentMethodCol.setPrefWidth(120);

        // Created By column
        TableColumn<ExpenseRow, String> createdByCol = new TableColumn<>("Created By");
        createdByCol.setCellValueFactory(new PropertyValueFactory<>("createdBy"));
        createdByCol.setPrefWidth(120);

        table.getColumns().addAll(dateCol, categoryCol, descriptionCol, amountCol, paymentMethodCol, createdByCol);

        return table;
    }

    private void loadExpenses() {
        new Thread(() -> {
            try {
                LocalDate startDate = startDatePicker.getValue() != null ? startDatePicker.getValue() : currentStartDate;
                LocalDate endDate = endDatePicker.getValue() != null ? endDatePicker.getValue() : currentEndDate;

                List<Expense> expenses = expenseService.getExpensesForDateRange(startDate, endDate);

                // Apply category filter
                String selectedCategory = categoryFilter.getValue();
                if (selectedCategory != null && !selectedCategory.equals("All Categories")) {
                    expenses = expenses.stream()
                        .filter(e -> e.getCategoryName().equals(selectedCategory))
                        .toList();
                }

                // Calculate total
                BigDecimal total = expenses.stream()
                    .map(Expense::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                // Convert to table rows
                List<ExpenseRow> rows = expenses.stream()
                    .map(this::mapExpenseToRow)
                    .toList();

                Platform.runLater(() -> {
                    expenseList.clear();
                    expenseList.addAll(rows);
                    totalExpensesLabel.setText(currencyFormat.format(total));
                });

            } catch (SQLException e) {
                logger.error("Error loading expenses", e);
                Platform.runLater(() -> {
                    ErrorHandler.handleErrorWithToast(e, "Failed to load expenses",
                        getScene() != null ? getScene().getWindow() : null);
                });
            }
        }).start();
    }

    private void loadCategories() {
        new Thread(() -> {
            try {
                List<ExpenseCategory> categories = categoryService.getAllCategories();
                Platform.runLater(() -> {
                    categoryFilter.getItems().clear();
                    categoryFilter.getItems().add("All Categories");
                    for (ExpenseCategory category : categories) {
                        categoryFilter.getItems().add(category.getName());
                    }
                    categoryFilter.setValue("All Categories");
                });
            } catch (Exception e) {
                logger.error("Error loading categories", e);
            }
        }).start();
    }

    private ExpenseRow mapExpenseToRow(Expense expense) {
        ExpenseRow row = new ExpenseRow();
        row.setExpense(expense);
        
        // Format date
        try {
            if (expense.getTimestamp() != null && !expense.getTimestamp().isEmpty()) {
                LocalDate date = LocalDate.parse(expense.getTimestamp().substring(0, 10));
                row.setDate(date.format(DateTimeFormatter.ofPattern("MMM dd, yyyy")));
            } else {
                row.setDate("N/A");
            }
        } catch (Exception e) {
            row.setDate("N/A");
        }
        
        row.setCategory(expense.getCategoryName());
        row.setDescription(expense.getDescription() != null ? expense.getDescription() : "");
        row.setAmount(currencyFormat.format(expense.getAmount()));
        row.setPaymentMethod(expense.getPaymentMethod() != null ? expense.getPaymentMethod() : "N/A");
        row.setCreatedBy(expense.getCreatedByName() != null ? expense.getCreatedByName() : "N/A");
        
        return row;
    }

    private void showAddExpenseDialog() {
        ExpenseDialog dialog = new ExpenseDialog(getScene() != null ? getScene().getWindow() : null);
        dialog.showAndWait().ifPresent(expense -> {
            new Thread(() -> {
                try {
                    expenseService.createExpense(expense);
                    Platform.runLater(() -> {
                        loadExpenses();
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("Success");
                        alert.setHeaderText(null);
                        alert.setContentText("Expense added successfully");
                        alert.showAndWait();
                    });
                } catch (Exception e) {
                    logger.error("Error creating expense", e);
                    Platform.runLater(() -> {
                        ErrorHandler.handleErrorWithToast(e, "Failed to create expense",
                            getScene() != null ? getScene().getWindow() : null);
                    });
                }
            }).start();
        });
    }

    private void exportExpenses() {
        if (expenseList.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Export");
            alert.setHeaderText(null);
            alert.setContentText("No expenses to export");
            alert.showAndWait();
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Expenses");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("CSV Files", "*.csv")
        );
        fileChooser.setInitialFileName("expenses_export_" + LocalDate.now() + ".csv");

        File file = fileChooser.showSaveDialog(getScene().getWindow());
        if (file != null) {
            // Collect data on UI thread
            List<Expense> expensesToExport = expenseList.stream()
                .map(ExpenseRow::getExpense)
                .toList();

            // Export on background thread
            new Thread(() -> {
                try {
                    boolean success = ExportService.exportExpensesToCSV(expensesToExport, file.getAbsolutePath());

                    Platform.runLater(() -> {
                        if (success) {
                            Alert alert = new Alert(Alert.AlertType.INFORMATION);
                            alert.setTitle("Export Successful");
                            alert.setHeaderText(null);
                            alert.setContentText("Expenses exported successfully to " + file.getName());
                            alert.showAndWait();
                        } else {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setTitle("Export Failed");
                            alert.setHeaderText(null);
                            alert.setContentText("Failed to export expenses");
                            alert.showAndWait();
                        }
                    });
                } catch (Exception e) {
                    logger.error("Error exporting expenses", e);
                    Platform.runLater(() -> {
                        ErrorHandler.handleErrorWithToast(e, "Failed to export expenses",
                            getScene() != null ? getScene().getWindow() : null);
                    });
                }
            }).start();
        }
    }

    /**
     * Table row data class
     */
    public static class ExpenseRow {
        private Expense expense;
        private String date;
        private String category;
        private String description;
        private String amount;
        private String paymentMethod;
        private String createdBy;

        public Expense getExpense() { return expense; }
        public void setExpense(Expense expense) { this.expense = expense; }

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }

        public String getPaymentMethod() { return paymentMethod; }
        public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

        public String getCreatedBy() { return createdBy; }
        public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    }
}
