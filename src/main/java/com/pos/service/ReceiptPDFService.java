package com.pos.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for generating and saving receipt PDFs
 */
public class ReceiptPDFService {
    private static final Logger logger = LoggerFactory.getLogger(ReceiptPDFService.class);
    private static ReceiptPDFService instance;

    private ReceiptPDFService() {
    }

    public static synchronized ReceiptPDFService getInstance() {
        if (instance == null) {
            instance = new ReceiptPDFService();
        }
        return instance;
    }

    /**
     * Get the receipts directory, organized by date
     * Receipts are saved in the user's Documents folder for easy access
     */
    private Path getReceiptsDirectory() throws IOException {
        String userHome = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();
        Path documentsDir;

        // Get the Documents directory based on OS
        if (os.contains("win")) {
            // Windows: Use Documents folder
            String documentsPath = System.getenv("USERPROFILE");
            if (documentsPath != null) {
                documentsDir = Paths.get(documentsPath, "Documents", "Pasal POS 2");
            } else {
                documentsDir = Paths.get(userHome, "Documents", "Pasal POS 2");
            }
        } else if (os.contains("mac")) {
            // macOS: Use Documents folder
            documentsDir = Paths.get(userHome, "Documents", "Pasal POS 2");
        } else {
            // Linux: Use Documents folder (common convention)
            documentsDir = Paths.get(userHome, "Documents", "Pasal POS 2");
        }

        // Create receipts directory organized by date (YYYY-MM-DD)
        LocalDate today = LocalDate.now();
        Path receiptsDir = documentsDir.resolve("receipts").resolve(today.format(DateTimeFormatter.ISO_DATE));

        if (!Files.exists(receiptsDir)) {
            Files.createDirectories(receiptsDir);
        }

        return receiptsDir;
    }

    /**
     * Remove control characters from receipt text that cannot be encoded in PDF
     * fonts
     * Control characters like ESC/POS commands (ESC, GS, etc.) are used for thermal
     * printers but cannot be displayed in PDF fonts
     * 
     * @param receiptText The original receipt text
     * @return Cleaned receipt text without control characters
     */
    private String removeControlCharacters(String receiptText) {
        if (receiptText == null) {
            return "";
        }

        // Remove all control characters (0x00-0x1F and 0x7F-0x9F)
        // Keep only printable characters, newlines (\n), and tabs (\t)
        return receiptText.replaceAll("[\\p{Cntrl}&&[^\n\t]]", "");
    }

    /**
     * Save receipt text as PDF with improved formatting
     * 
     * @param receiptText The receipt text content
     * @param saleId      Optional sale ID for filename (if null, uses timestamp)
     * @return Path to saved PDF file, or null if failed
     */
    public Path saveReceiptAsPDF(String receiptText, String saleId) {
        logger.info("saveReceiptAsPDF called for sale: {}, receiptText length: {}",
                saleId, receiptText != null ? receiptText.length() : 0);
        try {
            Path receiptsDir = getReceiptsDirectory();
            logger.debug("Receipts directory: {}", receiptsDir);

            // Generate filename
            String filename;
            if (saleId != null && !saleId.isEmpty()) {
                // Clean sale ID for filename (remove invalid characters)
                filename = saleId.replaceAll("[^a-zA-Z0-9-_]", "_") + ".pdf";
            } else {
                filename = "receipt_" + System.currentTimeMillis() + ".pdf";
            }

            Path pdfPath = receiptsDir.resolve(filename);
            logger.info("Saving receipt PDF to: {}", pdfPath);

            // Remove control characters (ESC/POS codes) before PDF generation
            String cleanedReceiptText = removeControlCharacters(receiptText);
            if (receiptText != null) {
                logger.debug("Cleaned receipt text, removed {} control characters",
                        receiptText.length() - cleanedReceiptText.length());
            }

            // Create PDF document
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);

                // renderFormattedReceipt now handles content streams internally
                renderFormattedReceipt(document, cleanedReceiptText, page);

                // Save document
                document.save(pdfPath.toFile());
            }

            logger.info("Receipt PDF saved successfully: {}", pdfPath);
            return pdfPath;
        } catch (IOException e) {
            logger.error("Failed to save receipt as PDF", e);
            return null;
        }
    }

    /**
     * Inner class to represent a parsed receipt line
     */
    private static class ReceiptLine {
        LineType type;
        String text;

        ReceiptLine(LineType type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    /**
     * Enum for line types
     */
    private enum LineType {
        SEPARATOR_EQUALS,
        SEPARATOR_DASHES,
        HEADER,
        TITLE,
        TABLE_HEADER,
        TABLE_ROW,
        TOTAL,
        BODY,
        EMPTY
    }

    /**
     * Save receipt to a user-selected location
     * 
     * @param receiptText The receipt text content
     * @param saleId      Optional sale ID for default filename
     * @return Path to saved PDF file, or null if cancelled/failed
     */
    public Path saveReceiptToLocation(String receiptText, String saleId) {
        try {
            javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
            fileChooser.setTitle("Save Receipt as PDF");
            fileChooser.getExtensionFilters().add(
                    new javafx.stage.FileChooser.ExtensionFilter("PDF Files", "*.pdf"));

            // Set default filename
            String defaultFilename;
            if (saleId != null && !saleId.isEmpty()) {
                defaultFilename = saleId.replaceAll("[^a-zA-Z0-9-_]", "_") + ".pdf";
            } else {
                defaultFilename = "receipt_" + System.currentTimeMillis() + ".pdf";
            }
            fileChooser.setInitialFileName(defaultFilename);

            // Show save dialog (must be called on JavaFX thread)
            File selectedFile = fileChooser.showSaveDialog(null);
            if (selectedFile == null) {
                return null; // User cancelled
            }

            Path pdfPath = selectedFile.toPath();

            // Remove control characters (ESC/POS codes) before PDF generation
            String cleanedReceiptText = removeControlCharacters(receiptText);

            // Create PDF document
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);

                // renderFormattedReceipt now handles content streams internally
                renderFormattedReceipt(document, cleanedReceiptText, page);

                document.save(pdfPath.toFile());
            }

            logger.info("Receipt PDF saved to user-selected location: {}", pdfPath);
            return pdfPath;
        } catch (Exception e) {
            logger.error("Failed to save receipt to selected location", e);
            return null;
        }
    }

    /**
     * Render receipt with improved formatting
     */
    private void renderFormattedReceipt(PDDocument document, String receiptText,
            PDPage page) throws IOException {

        // Fonts
        PDType1Font headerFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDType1Font titleFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDType1Font bodyFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font monospaceFont = new PDType1Font(Standard14Fonts.FontName.COURIER);
        PDType1Font monospaceBoldFont = new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD);

        // Page dimensions
        float pageWidth = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();
        float margin = 60;
        float contentWidth = pageWidth - (2 * margin);

        // Font sizes
        float titleSize = 18;
        float headerSize = 14;
        float bodySize = 10;

        // Line heights
        float titleLineHeight = 22;
        float headerLineHeight = 16;
        float bodyLineHeight = 13;

        float yPosition = pageHeight - margin;
        PDPage currentPage = page;
        PDPageContentStream currentContentStream = new PDPageContentStream(document, currentPage);

        String[] lines = receiptText.split("\n");
        List<ReceiptLine> parsedLines = parseReceiptLines(lines);

        for (ReceiptLine line : parsedLines) {
            // Check if we need a new page
            if (yPosition < margin + 50) {
                currentContentStream.close();
                currentPage = new PDPage(PDRectangle.A4);
                document.addPage(currentPage);
                currentContentStream = new PDPageContentStream(document, currentPage);
                yPosition = pageHeight - margin;
            }

            switch (line.type) {
                case SEPARATOR_EQUALS:
                    // Print as text to ensure it matches the printed receipt
                    currentContentStream.beginText();
                    currentContentStream.setFont(monospaceFont, bodySize);
                    currentContentStream.newLineAtOffset(margin, yPosition);
                    currentContentStream.showText(line.text);
                    currentContentStream.endText();
                    yPosition -= bodyLineHeight;
                    break;

                case SEPARATOR_DASHES:
                    // Print as text to ensure it matches the printed receipt
                    currentContentStream.beginText();
                    currentContentStream.setFont(monospaceFont, bodySize);
                    currentContentStream.newLineAtOffset(margin, yPosition);
                    currentContentStream.showText(line.text);
                    currentContentStream.endText();
                    yPosition -= bodyLineHeight;
                    break;

                case HEADER:
                    // Left-aligned, bold, larger font
                    String headerText = line.text.trim();
                    if (!headerText.isEmpty()) {
                        currentContentStream.beginText();
                        currentContentStream.setFont(headerFont, headerSize);
                        currentContentStream.newLineAtOffset(margin, yPosition);
                        currentContentStream.showText(headerText);
                        currentContentStream.endText();
                        yPosition -= headerLineHeight;
                    } else {
                        yPosition -= 8;
                    }
                    break;

                case TITLE:
                    // Left-aligned, bold, largest font
                    String titleText = line.text.trim();
                    if (!titleText.isEmpty()) {
                        currentContentStream.beginText();
                        currentContentStream.setFont(titleFont, titleSize);
                        currentContentStream.newLineAtOffset(margin, yPosition);
                        currentContentStream.showText(titleText);
                        currentContentStream.endText();
                        yPosition -= titleLineHeight;
                    } else {
                        yPosition -= 10;
                    }
                    break;

                case TABLE_HEADER:
                    // Bold table headers - use Monospace to align with rows
                    currentContentStream.beginText();
                    currentContentStream.setFont(monospaceBoldFont, bodySize);
                    currentContentStream.newLineAtOffset(margin, yPosition);
                    currentContentStream.showText(line.text);
                    currentContentStream.endText();
                    yPosition -= bodyLineHeight;
                    break;

                case TABLE_ROW:
                    // Monospace font for aligned columns
                    currentContentStream.beginText();
                    currentContentStream.setFont(monospaceFont, bodySize);
                    currentContentStream.newLineAtOffset(margin, yPosition);
                    currentContentStream.showText(line.text);
                    currentContentStream.endText();
                    yPosition -= bodyLineHeight;
                    break;

                case TOTAL:
                    // Bold for totals - use Monospace to align with values
                    currentContentStream.beginText();
                    currentContentStream.setFont(monospaceBoldFont, bodySize);
                    currentContentStream.newLineAtOffset(margin, yPosition);
                    currentContentStream.showText(line.text);
                    currentContentStream.endText();
                    yPosition -= bodyLineHeight;
                    break;

                case EMPTY:
                    yPosition -= 6;
                    break;

                default:
                    // Regular body text
                    if (!line.text.trim().isEmpty()) {
                        // Handle long lines by wrapping
                        List<String> wrappedLines = wrapTextToWidth(line.text, contentWidth, bodyFont, bodySize);
                        for (String wrappedLine : wrappedLines) {
                            if (yPosition < margin + 50) {
                                currentContentStream.close();
                                currentPage = new PDPage(PDRectangle.A4);
                                document.addPage(currentPage);
                                currentContentStream = new PDPageContentStream(document, currentPage);
                                yPosition = pageHeight - margin;
                            }
                            currentContentStream.beginText();
                            currentContentStream.setFont(bodyFont, bodySize);
                            currentContentStream.newLineAtOffset(margin, yPosition);
                            currentContentStream.showText(wrappedLine.trim());
                            currentContentStream.endText();
                            yPosition -= bodyLineHeight;
                        }
                    } else {
                        yPosition -= 6;
                    }
                    break;
            }
        }

        // Add barcode if sale ID is present
        String saleId = extractSaleIdFromReceipt(receiptText);
        if (saleId != null && !saleId.isEmpty()) {
            yPosition -= 15;
            if (yPosition < margin + 70) {
                currentContentStream.close();
                currentPage = new PDPage(PDRectangle.A4);
                document.addPage(currentPage);
                currentContentStream = new PDPageContentStream(document, currentPage);
                yPosition = pageHeight - margin;
            }

            // Generate and add barcode image - smaller size, left aligned
            try {
                BufferedImage barcodeImage = com.pos.util.BarcodeGenerator.generateCode128Barcode(saleId, 100, 25);
                if (barcodeImage != null) {
                    PDImageXObject pdImage = LosslessFactory.createFromImage(document, barcodeImage);
                    float imageWidth = 100;
                    float imageHeight = 25;
                    currentContentStream.drawImage(pdImage, margin, yPosition - imageHeight, imageWidth, imageHeight);
                    yPosition -= imageHeight + 3;
                }
            } catch (Exception e) {
                logger.warn("Failed to add barcode to PDF: {}", e.getMessage());
            }

            // Note: We intentionally do not render the raw sale ID text below the barcode
            // in the PDF
        }

        // Always close the content stream
        currentContentStream.close();
    }

    /**
     * Extract sale ID from receipt text
     */
    private String extractSaleIdFromReceipt(String receiptText) {
        String[] lines = receiptText.split("\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            // Look for "Scan for refund/exchange:" followed by the sale ID
            // Sale ID may have extra characters before it due to ESC/POS commands
            if (trimmed.contains("SALE-")) {
                // Extract just the SALE-XXXXXXXXXX-XXXXXXXX part
                int saleIndex = trimmed.indexOf("SALE-");
                return trimmed.substring(saleIndex);
            }
        }
        return null;
    }

    /**
     * Parse receipt lines and identify their types
     */
    private List<ReceiptLine> parseReceiptLines(String[] lines) {
        List<ReceiptLine> parsedLines = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                parsedLines.add(new ReceiptLine(LineType.EMPTY, ""));
                continue;
            }

            // Skip barcode-related lines (we'll add the barcode separately)
            if (trimmed.contains("Scan for refund/exchange") ||
                    trimmed.contains("SALE-")) {
                continue;
            }

            // Check for separators
            if (trimmed.matches("^=+$")) {
                parsedLines.add(new ReceiptLine(LineType.SEPARATOR_EQUALS, trimmed));
                continue;
            }

            if (trimmed.matches("^-+$")) {
                parsedLines.add(new ReceiptLine(LineType.SEPARATOR_DASHES, trimmed));
                continue;
            }

            // Check for "Thank you" or similar greeting (title)
            if (trimmed.toLowerCase().contains("thank you") ||
                    trimmed.toLowerCase().contains("have a great day")) {
                parsedLines.add(new ReceiptLine(LineType.TITLE, trimmed));
                continue;
            }

            // Check for table headers. Handles both the standard "Item Qty Price Total"
            // header and the dual-pricing "Item Qty Card Price Cash Price" header.
            if (trimmed.contains("Item") && trimmed.contains("Qty")
                    && (trimmed.contains("Price") || trimmed.contains("Total"))) {
                parsedLines.add(new ReceiptLine(LineType.TABLE_HEADER, line));
                continue;
            }

            // Check for totals (TOTAL, Subtotal, Tax, etc.)
            // Allow leading spaces for indented totals
            if (trimmed.matches(
                    "^\\s*(TOTAL|Subtotal|Tax|Discount|Payment|Amount Received|Change|Message|Cash Received|Cash Paid|Card/EBT Paid|Card/EBT Payment):.*")
                    ||
                    trimmed.matches("^\\s*TOTAL:.*")) {
                parsedLines.add(new ReceiptLine(LineType.TOTAL, line));
                continue;
            }

            // Check for split payment header
            if (trimmed.contains("***") && trimmed.contains("SPLIT PAYMENT")) {
                parsedLines.add(new ReceiptLine(LineType.HEADER, trimmed));
                continue;
            }

            // Check for receipt header (store name, etc.)
            if (parsedLines.size() <= 3 && !trimmed.contains("Date:") &&
                    !trimmed.contains("Cashier:") && !trimmed.contains("Customer:")) {
                parsedLines.add(new ReceiptLine(LineType.HEADER, trimmed));
                continue;
            }

            // Check for table rows (formatted with columns)
            if (line.matches(".*\\s+\\d+\\s+\\d+\\.\\d{2}\\s+\\d+\\.\\d{2}.*") ||
                    line.matches(".*\\s+\\d+\\s+\\d+\\.\\d{2}.*")) {
                parsedLines.add(new ReceiptLine(LineType.TABLE_ROW, line));
                continue;
            }

            // Default to body text
            parsedLines.add(new ReceiptLine(LineType.BODY, line));
        }

        return parsedLines;
    }

    /**
     * Wrap text to fit within specified width
     */
    private List<String> wrapTextToWidth(String text, float maxWidth, PDType1Font font, float fontSize) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }

        // Approximate character width (Helvetica average)
        // Using approximate width calculation (0.6 * fontSize is typical for Helvetica)
        float avgCharWidth = fontSize * 0.6f;
        int maxChars = (int) (maxWidth / avgCharWidth);

        if (text.length() <= maxChars) {
            lines.add(text);
            return lines;
        }

        String[] words = text.split("\\s+");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            String testLine = currentLine.length() == 0 ? word : currentLine + " " + word;
            if (testLine.length() <= maxChars) {
                currentLine = new StringBuilder(testLine);
            } else {
                if (currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                    currentLine = new StringBuilder(word);
                } else {
                    // Word is too long, split it
                    lines.add(word.substring(0, Math.min(maxChars, word.length())));
                    if (word.length() > maxChars) {
                        currentLine = new StringBuilder(word.substring(maxChars));
                    }
                }
            }
        }

        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }

        return lines;
    }
}
