package com.pos.ui;

import com.pos.service.SalesService;
import com.pos.ui.components.ToastNotification;
import com.pos.util.DialogHelper;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * Full-screen experience for recalling and deleting held sales.
 */
public class RecallHeldSalesScreen extends BorderPane {

    private final Runnable onBack;
    private final Consumer<String> onRecall;
    private final SalesService salesService;
    private final ObservableList<SalesService.HeldSaleInfo> heldSales = FXCollections.observableArrayList();
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
    private final DateTimeFormatter heldAtFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");

    private TableView<SalesService.HeldSaleInfo> heldSalesTable;
    private Label countValueLabel;
    private Label totalValueLabel;

    public RecallHeldSalesScreen(Runnable onBack, Consumer<String> onRecall) {
        this.onBack = onBack;
        this.onRecall = onRecall;
        this.salesService = SalesService.getInstance();

        initializeUI();
        refreshHeldSales();
    }

    private void initializeUI() {
        setPadding(new Insets(30));
        setStyle("-fx-background-color: #f8fafc;");

        setTop(createHeader());
        setCenter(createContent());
        setBottom(createFooter());
    }

    private VBox createHeader() {
        VBox header = new VBox(16);
        header.setPadding(new Insets(0, 0, 24, 0));

        HBox titleRow = new HBox(16);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Button backButton = new Button("< Back to Sale");
        backButton.setStyle(
                "-fx-background-color: #e2e8f0; " +
                        "-fx-text-fill: #334155; " +
                        "-fx-font-size: 15px; " +
                        "-fx-font-weight: 700; " +
                        "-fx-background-radius: 14; " +
                        "-fx-padding: 12 18; " +
                        "-fx-cursor: hand;");
        backButton.setOnAction(e -> onBack.run());

        VBox titleBox = new VBox(4);
        Label title = new Label("Recall Held Sales");
        title.setStyle("-fx-font-size: 32px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");
        Label subtitle = new Label("Bring back a parked cart or remove old held orders.");
        subtitle.setStyle("-fx-font-size: 15px; -fx-text-fill: #64748b; -fx-font-weight: 500;");
        titleBox.getChildren().addAll(title, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refreshButton = new Button("Refresh");
        refreshButton.setStyle(
                "-fx-background-color: #dbeafe; " +
                        "-fx-text-fill: #1d4ed8; " +
                        "-fx-font-size: 15px; " +
                        "-fx-font-weight: 700; " +
                        "-fx-background-radius: 14; " +
                        "-fx-padding: 12 18; " +
                        "-fx-cursor: hand;");
        refreshButton.setOnAction(e -> refreshHeldSales());

        titleRow.getChildren().addAll(backButton, titleBox, spacer, refreshButton);

        HBox statsRow = new HBox(16);
        countValueLabel = createStatCard(statsRow, "Held Orders");
        totalValueLabel = createStatCard(statsRow, "Held Value");

        header.getChildren().addAll(titleRow, statsRow);
        return header;
    }

    private Label createStatCard(HBox parent, String labelText) {
        VBox card = new VBox(6);
        card.setPadding(new Insets(18));
        card.setPrefWidth(220);
        card.setStyle(
                "-fx-background-color: white; " +
                        "-fx-background-radius: 20; " +
                        "-fx-border-color: #e2e8f0; " +
                        "-fx-border-width: 1; " +
                        "-fx-border-radius: 20;");

        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 12px; -fx-font-weight: 700; -fx-text-fill: #64748b;");

        Label value = new Label("--");
        value.setStyle("-fx-font-size: 24px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        card.getChildren().addAll(label, value);
        parent.getChildren().add(card);
        return value;
    }

    private VBox createContent() {
        VBox content = new VBox(18);

        Label helper = new Label("Double-click any row to recall it instantly.");
        helper.setStyle("-fx-font-size: 14px; -fx-text-fill: #475569; -fx-font-weight: 600;");

        heldSalesTable = new TableView<>(heldSales);
        heldSalesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        heldSalesTable.setPlaceholder(createEmptyState());
        heldSalesTable.setStyle(
                "-fx-background-color: white; " +
                        "-fx-background-radius: 20; " +
                        "-fx-border-color: #e2e8f0; " +
                        "-fx-border-width: 1; " +
                        "-fx-border-radius: 20;");
        VBox.setVgrow(heldSalesTable, Priority.ALWAYS);

        TableColumn<SalesService.HeldSaleInfo, String> holdIdCol = new TableColumn<>("Hold ID");
        holdIdCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getHoldId()));

        TableColumn<SalesService.HeldSaleInfo, String> customerCol = new TableColumn<>("Customer");
        customerCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getCustomerName()));

        TableColumn<SalesService.HeldSaleInfo, String> totalCol = new TableColumn<>("Total");
        totalCol.setCellValueFactory(
                data -> new SimpleStringProperty(currencyFormat.format(data.getValue().getTotal())));

        TableColumn<SalesService.HeldSaleInfo, String> itemsCol = new TableColumn<>("Items");
        itemsCol.setCellValueFactory(
                data -> new SimpleStringProperty(String.valueOf(data.getValue().getItemCount())));

        TableColumn<SalesService.HeldSaleInfo, String> heldAtCol = new TableColumn<>("Held At");
        heldAtCol.setCellValueFactory(
                data -> new SimpleStringProperty(data.getValue().getHeldAt().format(heldAtFormatter)));

        TableColumn<SalesService.HeldSaleInfo, String> noteCol = new TableColumn<>("Note");
        noteCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getNote()));

        heldSalesTable.getColumns().addAll(holdIdCol, customerCol, totalCol, itemsCol, heldAtCol, noteCol);
        heldSalesTable.setRowFactory(table -> {
            TableRow<SalesService.HeldSaleInfo> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    handleRecall();
                }
            });
            return row;
        });

        content.getChildren().addAll(helper, heldSalesTable);
        return content;
    }

    private VBox createEmptyState() {
        VBox emptyState = new VBox(10);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.setPadding(new Insets(40));

        Label title = new Label("No held sales");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: 800; -fx-text-fill: #0f172a;");

        Label subtitle = new Label("Held orders will appear here after you park a sale.");
        subtitle.setStyle("-fx-font-size: 14px; -fx-font-weight: 500; -fx-text-fill: #64748b;");

        emptyState.getChildren().addAll(title, subtitle);
        return emptyState;
    }

    private HBox createFooter() {
        HBox footer = new HBox(12);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(24, 0, 0, 0));

        Button backButton = new Button("Back");
        backButton.setStyle(
                "-fx-background-color: #e2e8f0; " +
                        "-fx-text-fill: #334155; " +
                        "-fx-font-size: 15px; " +
                        "-fx-font-weight: 700; " +
                        "-fx-background-radius: 14; " +
                        "-fx-padding: 14 22; " +
                        "-fx-cursor: hand;");
        backButton.setOnAction(e -> onBack.run());

        Button deleteButton = new Button("Delete Selected");
        deleteButton.setStyle(
                "-fx-background-color: #fee2e2; " +
                        "-fx-text-fill: #b91c1c; " +
                        "-fx-font-size: 15px; " +
                        "-fx-font-weight: 700; " +
                        "-fx-background-radius: 14; " +
                        "-fx-padding: 14 22; " +
                        "-fx-cursor: hand;");
        deleteButton.setOnAction(e -> handleDelete());

        Button recallButton = new Button("Recall Selected");
        recallButton.setStyle(
                "-fx-background-color: linear-gradient(to right, #2563eb 0%, #1d4ed8 100%); " +
                        "-fx-text-fill: white; " +
                        "-fx-font-size: 15px; " +
                        "-fx-font-weight: 800; " +
                        "-fx-background-radius: 14; " +
                        "-fx-padding: 14 22; " +
                        "-fx-cursor: hand;");
        recallButton.setOnAction(e -> handleRecall());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        footer.getChildren().addAll(spacer, backButton, deleteButton, recallButton);
        return footer;
    }

    private void refreshHeldSales() {
        salesService.cleanupExpiredHeldSales();
        List<SalesService.HeldSaleInfo> latestHeldSales = salesService.getHeldSales();
        heldSales.setAll(latestHeldSales);

        BigDecimal totalHeldValue = latestHeldSales.stream()
                .map(SalesService.HeldSaleInfo::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        countValueLabel.setText(String.valueOf(latestHeldSales.size()));
        totalValueLabel.setText(currencyFormat.format(totalHeldValue));
    }

    private void handleRecall() {
        SalesService.HeldSaleInfo selected = heldSalesTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            ToastNotification.showWarning("Select a held sale to recall.", getScene() != null ? getScene().getWindow() : null);
            return;
        }
        onRecall.accept(selected.getHoldId());
    }

    private void handleDelete() {
        SalesService.HeldSaleInfo selected = heldSalesTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            ToastNotification.showWarning("Select a held sale to delete.", getScene() != null ? getScene().getWindow() : null);
            return;
        }

        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle("Delete Held Sale");
        confirmAlert.setHeaderText("Delete held sale " + selected.getHoldId() + "?");
        confirmAlert.setContentText("This will permanently remove the parked order.");
        DialogHelper.setAlertOwner(confirmAlert, getScene() != null ? getScene().getWindow() : null);

        if (confirmAlert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        if (salesService.deleteHeldSale(selected.getHoldId())) {
            ToastNotification.showSuccess("Deleted held sale", getScene() != null ? getScene().getWindow() : null);
            refreshHeldSales();
        } else {
            ToastNotification.showError("Failed to delete held sale", getScene() != null ? getScene().getWindow() : null);
        }
    }
}
