package com.pos.util;

import com.pos.config.ConfigManager;
import com.pos.hardware.HardwareManager;
import com.pos.service.SettingsService;
import com.pos.ui.components.ToastNotification;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper utility for printing receipts to receipt printer only
 */
public class ReceiptPrintHelper {
    private static final Logger logger = LoggerFactory.getLogger(ReceiptPrintHelper.class);

    /**
     * Print receipt based on the global print mode setting (AUTO or MANUAL).
     * If MANUAL, shows a confirmation dialog before printing.
     * 
     * @param receiptText     The receipt text to print
     * @param saleId          Optional sale ID
     * @param ownerWindow     Window owner for the prompt and notifications
     * @param settingsService Service to check the current print mode
     */
    public static void printReceiptConditionally(String receiptText, String saleId, Window ownerWindow,
            SettingsService settingsService) {
        String printMode = settingsService.getReceiptPrintMode();

        if ("MANUAL".equals(printMode)) {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.initOwner(ownerWindow);
                alert.setTitle("Receipt Printing");
                alert.setHeaderText("Transaction Completed Successfully");
                alert.setContentText("Would you like to print a receipt? (Auto-closes in 5 seconds)");

                // Custom styling for the alert to match POS theme
                DialogPane dialogPane = alert.getDialogPane();
                dialogPane.setStyle("-fx-background-color: #1a3a5c;");
                dialogPane.lookupAll(".label").forEach(node -> node.setStyle("-fx-text-fill: white;"));

                ButtonType printButton = new ButtonType("Print Receipt", ButtonBar.ButtonData.OK_DONE);
                ButtonType noPrintButton = new ButtonType("No, Thanks", ButtonBar.ButtonData.CANCEL_CLOSE);
                alert.getButtonTypes().setAll(printButton, noPrintButton);

                // Auto-close after 5 seconds if user doesn't respond
                PauseTransition autoClose = new PauseTransition(Duration.seconds(5));
                autoClose.setOnFinished(event -> {
                    if (alert.isShowing()) {
                        alert.setResult(noPrintButton);
                        alert.close();
                    }
                });
                autoClose.play();

                alert.showAndWait().ifPresent(response -> {
                    autoClose.stop();
                    if (response == printButton) {
                        printReceiptWithPDF(receiptText, saleId, ownerWindow);
                    }
                });
            });
        } else {
            printReceiptWithPDF(receiptText, saleId, ownerWindow);
        }
    }

    /**
     * Print receipt to receipt printer only
     * In testing mode, receipts are saved as PDF and a success message is shown.
     * 
     * @param receiptText The receipt text to print
     * @param saleId      Optional sale ID (kept for backward compatibility, not
     *                    used)
     * @param ownerWindow Window owner for error dialogs
     */
    public static void printReceiptWithPDF(String receiptText, String saleId, Window ownerWindow) {
        logger.info("printReceipt called for sale: {}", saleId);
        HardwareManager hardwareManager = HardwareManager.getInstance();
        boolean isTestingMode = ConfigManager.getInstance().isTestingMode();

        hardwareManager.printReceipt(receiptText, saleId).thenAccept(result -> {
            logger.info("Print result received - printed: {}, printerAvailable: {}, pdfSaved: {}, testingMode: {}",
                    result.printed, result.printerAvailable, result.pdfSaved, isTestingMode);

            Platform.runLater(() -> {
                if (result.printed) {
                    // Receipt printed successfully
                    logger.info("Receipt printed successfully");
                    ToastNotification.showSuccess("Receipt printed successfully", ownerWindow);
                } else if (isTestingMode && result.pdfSaved) {
                    // Testing mode: PDF saved successfully, show success instead of error
                    logger.info("Testing mode: Receipt saved as PDF at {}", result.pdfPath);
                    ToastNotification.showSuccess(
                            "Receipt saved as PDF (Testing Mode)", ownerWindow);
                } else if (result.pdfSaved) {
                    // Production mode but printer not available - PDF saved as fallback
                    if (!result.printerAvailable) {
                        logger.warn("Printer not available, receipt saved as PDF");
                        ToastNotification.showWarning(
                                "Printer not available. Receipt saved as PDF.", ownerWindow);
                    } else {
                        // Printer error but PDF saved
                        logger.error("Printer error occurred, receipt saved as PDF");
                        ToastNotification.showWarning(
                                "Printer error. Receipt saved as PDF.", ownerWindow);
                    }
                } else {
                    // Both printing and PDF save failed
                    logger.error("Both printing and PDF save failed");
                    ToastNotification.showError(
                            "Failed to print or save receipt. Please try again.", ownerWindow);
                }
            });
        }).exceptionally(ex -> {
            logger.error("Exception during receipt printing", ex);
            Platform.runLater(() -> {
                ToastNotification.showError("Receipt printing failed: " + ex.getMessage(), ownerWindow);
            });
            return null;
        });
    }

    /**
     * Show receipt preview dialog with print option
     * 
     * @param receiptText The receipt text content
     * @param ownerWindow Parent window
     * @param title       Dialog title
     * @param headerText  Header text for the dialog
     */
    public static void showReceiptPreview(String receiptText, Window ownerWindow, String title, String headerText) {
        Dialog<Void> previewDialog = new Dialog<>();
        previewDialog.setTitle(title != null ? title : "Receipt Preview");
        previewDialog.setHeaderText(headerText != null ? headerText : "Receipt Preview");

        // Set owner using DialogHelper if available or directly
        DialogHelper.setDialogOwner(previewDialog, ownerWindow);

        // Parse for barcode marker
        String saleId = null;
        String textBeforeBarcode = receiptText;
        String textAfterBarcode = "";

        Pattern p = Pattern.compile("^SALE-([A-Z0-9-]+)\\s*$", Pattern.MULTILINE);
        Matcher m = p.matcher(receiptText);

        if (m.find()) {
            saleId = "SALE-" + m.group(1);
            int markerStart = m.start();
            int markerEnd = m.end();

            textBeforeBarcode = receiptText.substring(0, markerStart).trim();
            textAfterBarcode = receiptText.substring(markerEnd).trim();
        }

        VBox contentBox = new VBox(10);
        contentBox.setStyle("-fx-background-color: white;");
        contentBox.setPadding(new javafx.geometry.Insets(10));

        // Create content area with the report text
        TextArea reportTextArea = new TextArea(textBeforeBarcode);
        reportTextArea.setEditable(false);
        reportTextArea.setWrapText(false);
        reportTextArea.setStyle(
                "-fx-font-family: 'Courier New', Consolas, monospace; " +
                        "-fx-font-size: 13px; " +
                        "-fx-background-color: white; " +
                        "-fx-control-inner-background: white; " +
                        "-fx-background-insets: 0; " +
                        "-fx-padding: 0;");

        // Approximate height based on line count (receipts are usually short enough)
        int lineCount = textBeforeBarcode.split("\n").length;
        reportTextArea.setPrefHeight(Math.max(200, lineCount * 18));
        reportTextArea.setPrefWidth(450);
        contentBox.getChildren().add(reportTextArea);

        // Add graphical barcode if saleId found
        if (saleId != null) {
            VBox barcodeBox = new VBox(5);
            barcodeBox.setAlignment(Pos.CENTER);
            barcodeBox.setPadding(new javafx.geometry.Insets(10, 0, 10, 0));

            try {
                BufferedImage bImg = com.pos.util.BarcodeGenerator.generateCode128Barcode(saleId, 300, 60);
                if (bImg != null) {
                    Image fxImage = javafx.embed.swing.SwingFXUtils.toFXImage(bImg, null);
                    ImageView imageView = new ImageView(fxImage);

                    Label idLabel = new Label(saleId);
                    idLabel.setStyle("-fx-font-family: 'Courier New'; -fx-font-size: 12px;");

                    barcodeBox.getChildren().addAll(imageView, idLabel);
                    contentBox.getChildren().add(barcodeBox);
                }
            } catch (Exception e) {
                logger.warn("Failed to generate barcode for preview: {}", e.getMessage());
            }
        }

        // Add text after barcode if it exists
        if (!textAfterBarcode.isEmpty()) {
            TextArea footerArea = new TextArea(textAfterBarcode);
            footerArea.setEditable(false);
            footerArea.setWrapText(false);
            footerArea.setStyle(
                    "-fx-font-family: 'Courier New', Consolas, monospace; " +
                            "-fx-font-size: 13px; " +
                            "-fx-background-color: white; " +
                            "-fx-control-inner-background: white;");
            int footerLines = textAfterBarcode.split("\n").length;
            footerArea.setPrefHeight(Math.max(50, footerLines * 18));
            contentBox.getChildren().add(footerArea);
        }

        // Create a scroll pane wrapper
        ScrollPane scrollPane = new ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(600);
        scrollPane.setStyle("-fx-background-color: white;");

        previewDialog.getDialogPane().setContent(scrollPane);
        previewDialog.getDialogPane().setPrefWidth(550);

        // Add buttons
        ButtonType printButtonType = new ButtonType("Print", ButtonBar.ButtonData.OK_DONE);
        ButtonType closeButtonType = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);

        previewDialog.getDialogPane().getButtonTypes().addAll(printButtonType, closeButtonType);

        // Style the print button
        Button printBtn = (Button) previewDialog.getDialogPane().lookupButton(printButtonType);
        if (printBtn != null) {
            printBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        }

        // Handle button actions
        previewDialog.setResultConverter(buttonType -> {
            if (buttonType == printButtonType) {
                // Print the receipt
                printReceiptWithPDF(receiptText, null, ownerWindow);
            }
            return null;
        });

        previewDialog.showAndWait();
    }
}
