package com.pos.hardware;

import com.pos.config.ConfigManager;
import com.pos.service.ReceiptPDFService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.print.*;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Manages all POS hardware devices using Windows Print API
 * Supports RONGTA RP80 thermal receipt printer and cash drawer via ESC/POS
 */
public class HardwareManager {

    private static final Logger logger = LoggerFactory.getLogger(HardwareManager.class);

    /**
     * Default Windows queue name when {@code printer.name} and per-store overrides
     * are not set. Change via Hardware Settings or {@code application.properties}.
     */
    public static final String DEFAULT_WINDOWS_PRINTER_QUEUE = "POS-58C";

    // ESC/POS Commands for thermal printers
    private static final byte[] ESC_INIT = { 0x1B, 0x40 }; // Initialize printer
    private static final byte[] ESC_CUT = { 0x1D, 0x56, 0x00 }; // Full cut
    private static final byte[] ESC_PARTIAL_CUT = { 0x1D, 0x56, 0x01 }; // Partial cut
    private static final byte[] ESC_FEED_LINES = { 0x1B, 0x64, 0x05 }; // Feed 5 lines
    private static final byte[] ESC_ALIGN_CENTER = { 0x1B, 0x61, 0x01 }; // Center align
    private static final byte[] ESC_ALIGN_LEFT = { 0x1B, 0x61, 0x00 }; // Left align
    private static final byte[] ESC_BOLD_ON = { 0x1B, 0x45, 0x01 }; // Bold on
    private static final byte[] ESC_BOLD_OFF = { 0x1B, 0x45, 0x00 }; // Bold off

    // Cash drawer kick commands (works with most RJ11-connected cash drawers)
    private static final byte[] CASH_DRAWER_KICK_PIN2 = { 0x1B, 0x70, 0x00, 0x19, (byte) 0xFA }; // Kick pin 2
    private static final byte[] CASH_DRAWER_KICK_PIN5 = { 0x1B, 0x70, 0x01, 0x19, (byte) 0xFA }; // Kick pin 5

    private PrintService printerService;
    private String printerName;
    private Consumer<String> scanCallback;
    private boolean isInitialized = false;
    private boolean keyboardModeEnabled = false;
    private ConfigManager configManager;

    public HardwareManager() {
        this.configManager = ConfigManager.getInstance();
    }

    /**
     * Initialize all hardware devices
     */
    public void initialize() {
        if (isInitialized) {
            logger.warn("Hardware already initialized");
            return;
        }

        // Initialize hardware devices
        initializePrinter();
        initializeScanner();
        initializeCashDrawer();

        isInitialized = true;
        logger.info("Hardware initialization completed (some devices may not be available)");
    }

    /**
     * Reinitialize all hardware devices (cleanup and reinitialize)
     */
    public void reinitialize() {
        logger.info("Reinitializing hardware devices...");
        cleanup();
        isInitialized = false;
        initialize();
    }

    /**
     * Initialize receipt printer using Windows Print API.
     * <p>
     * Selects the queue whose name matches {@code printer.name} (comma-separated
     * candidates tried in order). Does not prefer the Windows default printer when
     * another queue matches the same pattern (e.g. use a non-default 80mm queue
     * when Windows default is a different model).
     */
    private void initializePrinter() {
        try {
            printerName = configManager.getResolvedPrinterName(DEFAULT_WINDOWS_PRINTER_QUEUE);
            logger.info("Looking for configured printer name(s) (per store if set): {}", printerName);

            PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
            PrintService defaultService = PrintServiceLookup.lookupDefaultPrintService();
            if (defaultService != null) {
                logger.info("Windows default print queue (informational only): {}", defaultService.getName());
            }
            logger.info("Found {} print services", services.length);
            for (PrintService service : services) {
                logger.debug("Available printer: {}", service.getName());
            }

            printerService = selectPrintService(services, defaultService, printerName);

            if (printerService != null) {
                boolean isDefault = defaultService != null
                        && samePrintQueue(printerService, defaultService);
                logger.info("Printer selected: {}{}", printerService.getName(),
                        isDefault ? " (also Windows default)" : " (not the Windows default queue)");
            } else {
                logger.warn("No matching printer for '{}'. Available printers:", printerName);
                for (PrintService service : services) {
                    logger.warn("  - {}", service.getName());
                }
            }
        } catch (Exception e) {
            logger.warn("Printer initialization failed: {}", e.getMessage());
            printerService = null;
        }
    }

    private static boolean samePrintQueue(PrintService a, PrintService b) {
        return a != null && b != null && a.getName().equals(b.getName());
    }

    /**
     * Drivers like OneNote / PDF / XPS are not receipt printers.
     */
    private static boolean isVirtualOrDocumentPrinter(String queueName) {
        String n = queueName.toLowerCase();
        return n.contains("onenote")
                || n.contains("xps document")
                || n.contains("microsoft print to pdf")
                || n.contains("print to pdf")
                || n.equals("fax");
    }

    private static boolean matchesConfiguredName(String queueName, String configuredFragment) {
        if (configuredFragment == null || configuredFragment.isEmpty()) {
            return false;
        }
        String q = queueName.toLowerCase();
        String c = configuredFragment.toLowerCase().trim();
        if (q.equals(c)) {
            return true;
        }
        // Avoid substring "POS" matching every POS-* queue; require a distinct name.
        if (c.length() < 4) {
            return false;
        }
        return q.contains(c);
    }

    /**
     * When several queues match the same configured fragment, prefer one that is
     * not the Windows default so we hit the intended physical printer.
     */
    private static PrintService preferNonDefault(List<PrintService> matches, PrintService defaultService) {
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (defaultService == null) {
            return matches.get(0);
        }
        for (PrintService s : matches) {
            if (!samePrintQueue(s, defaultService)) {
                return s;
            }
        }
        return matches.get(0);
    }

    private PrintService selectPrintService(PrintService[] services, PrintService defaultService,
            String configuredNamesRaw) {
        List<String> nameCandidates = new ArrayList<>();
        if (configuredNamesRaw != null && !configuredNamesRaw.trim().isEmpty()) {
            for (String part : configuredNamesRaw.split(",")) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    nameCandidates.add(p);
                }
            }
        }

        // 1) Configured name(s) only — try each comma-separated pattern in order
            for (String candidate : nameCandidates) {
            List<PrintService> matches = new ArrayList<>();
            for (PrintService service : services) {
                String name = service.getName();
                if (!matchesConfiguredName(name, candidate)) {
                    continue;
                }
                if (isVirtualOrDocumentPrinter(name) && !name.equalsIgnoreCase(candidate.trim())) {
                    continue;
                }
                matches.add(service);
            }
            String cTrim = candidate.trim();
            // An explicitly selected queue must win, including the Windows default.
            // Otherwise a stale "(Copy 1)" queue can override the replacement printer.
            for (PrintService match : matches) {
                if (match.getName().equalsIgnoreCase(cTrim)) {
                    return match;
                }
            }
            matches.sort((a, b) -> {
                boolean ea = a.getName().equalsIgnoreCase(cTrim);
                boolean eb = b.getName().equalsIgnoreCase(cTrim);
                if (ea != eb) {
                    return ea ? -1 : 1;
                }
                return Integer.compare(b.getName().length(), a.getName().length());
            });
            PrintService chosen = preferNonDefault(matches, defaultService);
            if (chosen != null) {
                return chosen;
            }
        }

        // 2) Fallback: thermal / POS-style queues only; skip virtual drivers; if both
        // default and non-default match, take non-default
        List<PrintService> thermalMatches = new ArrayList<>();
        for (PrintService service : services) {
            String name = service.getName();
            if (isVirtualOrDocumentPrinter(name)) {
                continue;
            }
            String n = name.toLowerCase();
            boolean thermalLike = n.contains("pos-")
                    || n.contains("pos ")
                    || n.contains("thermal")
                    || n.contains("receipt")
                    || n.contains("rongta")
                    || n.contains("rp80")
                    || n.contains("citizen")
                    || n.contains("ct-s")
                    || n.contains("escpos")
                    || n.contains("epson");
            if (thermalLike) {
                thermalMatches.add(service);
            }
        }
        return preferNonDefault(thermalMatches, defaultService);
    }

    /**
     * Initialize barcode scanner (supports keyboard wedge mode for USB HID
     * scanners)
     */
    private void initializeScanner() {
        // USB HID scanners (like HP) work as keyboard input - no initialization needed
        boolean useKeyboardMode = Boolean.parseBoolean(
                configManager.getProperty("scanner.useKeyboardMode", "true"));

        if (useKeyboardMode) {
            logger.info("Scanner configured for keyboard wedge mode (USB HID)");
            logger.info("Scanned barcodes will be received as keyboard input");
            keyboardModeEnabled = true;
        } else {
            // Fallback to keyboard mode anyway since JavaPOS is not used
            logger.info("Scanner defaulting to keyboard wedge mode");
            keyboardModeEnabled = true;
        }
    }

    /**
     * Initialize cash drawer (connected via printer RJ11 port)
     */
    private void initializeCashDrawer() {
        // Cash drawer is connected to printer via RJ11 - no separate initialization
        // needed
        // It will be opened by sending ESC/POS command through the printer
        if (printerService != null) {
            logger.info("Cash drawer initialized (via printer RJ11 port)");
        } else {
            logger.warn("Cash drawer requires printer - printer not available");
        }
    }

    /**
     * Flag to indicate keyboard wedge mode is active
     */
    public boolean isKeyboardModeEnabled() {
        return keyboardModeEnabled;
    }

    /**
     * Process barcode from keyboard input (for keyboard wedge mode)
     * Call this from UI when text field receives barcode input
     */
    public void processKeyboardBarcode(String barcode) {
        if (barcode == null || barcode.isEmpty()) {
            return;
        }
        String cleanBarcode = barcode.trim();
        logger.info("Barcode received (keyboard mode): {}", cleanBarcode);
        if (scanCallback != null) {
            scanCallback.accept(cleanBarcode);
        }
    }

    /**
     * Print receipt and save as PDF automatically
     * 
     * @param receiptText The receipt text to print
     * @param saleId      Optional sale ID for PDF filename (if null, uses
     *                    timestamp)
     * @return CompletableFuture with PrintResult containing print status and PDF
     *         path
     */
    public CompletableFuture<PrintResult> printReceipt(String receiptText, String saleId) {
        logger.info("printReceipt called for sale: {}, receiptText length: {}",
                saleId, receiptText != null ? receiptText.length() : 0);
        return CompletableFuture.supplyAsync(() -> {
            PrintResult result = new PrintResult();
            ReceiptPDFService pdfService = ReceiptPDFService.getInstance();

            // Try to print if printer is available
            try {
                if (printerService == null) {
                    logger.warn("Printer not available, saving PDF as fallback");
                    result.printed = false;
                    result.printerAvailable = false;
                    // Save PDF only when printer is not available
                    Path pdfPath = pdfService.saveReceiptAsPDF(receiptText, saleId);
                    result.pdfPath = pdfPath;
                    result.pdfSaved = pdfPath != null;
                    logger.info("PDF save result: success={}, path={}", result.pdfSaved, pdfPath);
                    return result;
                }

                logger.info("Attempting to print receipt via Windows Print API...");

                // Build ESC/POS data
                byte[] printData = buildEscPosData(receiptText);

                // Create print job
                DocPrintJob job = printerService.createPrintJob();
                DocFlavor flavor = DocFlavor.INPUT_STREAM.AUTOSENSE;
                Doc doc = new SimpleDoc(new ByteArrayInputStream(printData), flavor, null);
                PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();

                job.print(doc, attrs);

                logger.info("Receipt printed successfully via {}", printerService.getName());
                result.printed = true;
                result.printerAvailable = true;
                // Don't save PDF when printer is connected and print succeeds
                result.pdfSaved = false;
                result.pdfPath = null;
                return result;
            } catch (Exception e) {
                logger.error("Failed to print receipt", e);
                result.printed = false;
                result.printerAvailable = printerService != null;
                // Save PDF as fallback when printing fails
                logger.info("Saving PDF as fallback due to print failure");
                Path pdfPath = pdfService.saveReceiptAsPDF(receiptText, saleId);
                result.pdfPath = pdfPath;
                result.pdfSaved = pdfPath != null;
                logger.info("PDF save result: success={}, path={}", result.pdfSaved, pdfPath);
                return result;
            }
        });
    }

    /**
     * Build ESC/POS byte array from receipt text
     * Detects SALE-{saleId} markers and converts them to actual barcode commands
     */
    byte[] buildEscPosData(String receiptText) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

            // Initialize printer
            baos.write(ESC_INIT);

            // Check for barcode marker (SALE-{saleId})
            // The marker is formatted as "SALE-{saleId}" at the end of the receipt
            // Use a more specific pattern that only matches if the line STARTS with SALE-
            // this avoids false positives with "Receipt #: SALE-..." at the top of the
            // receipt
            java.util.regex.Pattern barcodePattern = java.util.regex.Pattern.compile("^SALE-([A-Z0-9-]+)\\s*$",
                    java.util.regex.Pattern.MULTILINE);
            java.util.regex.Matcher matcher = barcodePattern.matcher(receiptText);

            logger.debug("Building ESC/POS data for receipt (text length={})", receiptText.length());

            if (matcher.find()) {
                // Found a barcode marker - split text and insert actual barcode
                String saleId = "SALE-" + matcher.group(1).trim();
                String textBeforeBarcode = receiptText.substring(0, matcher.start());
                String textAfterBarcode = receiptText.substring(matcher.end());

                logger.info("Found barcode marker at position {}, sale ID: {}, text before barcode length: {}",
                        matcher.start(), saleId, textBeforeBarcode.length());

                // Write the text before the barcode
                byte[] textBytes = textBeforeBarcode.getBytes(StandardCharsets.UTF_8);
                baos.write(textBytes);
                logger.debug("Wrote {} bytes of receipt text before barcode", textBytes.length);

                // Generate and write actual ESC/POS barcode commands
                try {
                    String barcodeCommands = com.pos.util.BarcodeGenerator.generateESCPOSBarcode(saleId);
                    byte[] barcodeBytes = barcodeCommands.getBytes(StandardCharsets.ISO_8859_1);
                    baos.write(barcodeBytes);
                    logger.info("Added ESC/POS barcode for sale ID: {} (barcode bytes={})", saleId,
                            barcodeBytes.length);
                } catch (Exception barcodeEx) {
                    // If barcode generation fails, just print the sale ID as text
                    logger.warn("Barcode generation failed, printing sale ID as text: {}", barcodeEx.getMessage());
                    baos.write(("\n\n" + saleId + "\n\n").getBytes(StandardCharsets.UTF_8));
                }

                // Write any remaining text after the barcode marker
                if (textAfterBarcode != null && !textAfterBarcode.isEmpty()) {
                    byte[] afterBytes = textAfterBarcode.getBytes(StandardCharsets.UTF_8);
                    baos.write(afterBytes);
                    logger.debug("Wrote {} bytes of receipt text after barcode", afterBytes.length);
                }
            } else {
                // No barcode marker, write receipt as plain text
                logger.debug("No barcode marker found in receipt text, writing as plain text");
                baos.write(receiptText.getBytes(StandardCharsets.UTF_8));
            }

            // Feed lines and cut
            baos.write(ESC_FEED_LINES);
            baos.write(ESC_PARTIAL_CUT);

            byte[] result = baos.toByteArray();
            logger.info("Built ESC/POS data: {} total bytes to send to printer", result.length);
            return result;
        } catch (Exception e) {
            logger.error("Error building ESC/POS data, falling back to plain text", e);
            return receiptText.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Print receipt (backward compatibility - no sale ID)
     */
    public CompletableFuture<PrintResult> printReceipt(String receiptText) {
        return printReceipt(receiptText, null);
    }

    /**
     * Result of print operation
     */
    public static class PrintResult {
        public boolean printed = false;
        public boolean printerAvailable = false;
        public boolean pdfSaved = false;
        public Path pdfPath = null;
    }

    /**
     * Open cash drawer by sending ESC/POS kick command through printer
     */
    public CompletableFuture<Boolean> openCashDrawer() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (printerService == null) {
                    logger.warn("Cash drawer requires printer - printer not available");
                    return false;
                }

                logger.info("Opening cash drawer via ESC/POS command...");

                // Build cash drawer kick command
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                baos.write(ESC_INIT);
                baos.write(CASH_DRAWER_KICK_PIN2); // Try pin 2 first (most common)

                // Create print job to send cash drawer kick
                DocPrintJob job = printerService.createPrintJob();
                DocFlavor flavor = DocFlavor.INPUT_STREAM.AUTOSENSE;
                Doc doc = new SimpleDoc(new ByteArrayInputStream(baos.toByteArray()), flavor, null);
                PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();

                job.print(doc, attrs);

                logger.info("Cash drawer kick command sent via {}", printerService.getName());
                return true;
            } catch (Exception e) {
                logger.error("Failed to open cash drawer", e);
                return false;
            }
        });
    }

    /**
     * Open cash drawer using specific pin (pin 2 or pin 5)
     */
    public CompletableFuture<Boolean> openCashDrawer(int pin) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (printerService == null) {
                    logger.warn("Cash drawer requires printer - printer not available");
                    return false;
                }

                logger.info("Opening cash drawer via pin {} ESC/POS command...", pin);

                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                baos.write(ESC_INIT);

                if (pin == 5) {
                    baos.write(CASH_DRAWER_KICK_PIN5);
                } else {
                    baos.write(CASH_DRAWER_KICK_PIN2);
                }

                DocPrintJob job = printerService.createPrintJob();
                DocFlavor flavor = DocFlavor.INPUT_STREAM.AUTOSENSE;
                Doc doc = new SimpleDoc(new ByteArrayInputStream(baos.toByteArray()), flavor, null);
                PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();

                job.print(doc, attrs);

                logger.info("Cash drawer kick command (pin {}) sent via {}", pin, printerService.getName());
                return true;
            } catch (Exception e) {
                logger.error("Failed to open cash drawer", e);
                return false;
            }
        });
    }

    /**
     * Set callback for barcode scans
     */
    public void setScanCallback(Consumer<String> callback) {
        this.scanCallback = callback;
    }

    /**
     * Get printer status
     */
    public boolean isPrinterReady() {
        return printerService != null;
    }

    /**
     * Get scanner status (supports keyboard mode)
     */
    public boolean isScannerReady() {
        return keyboardModeEnabled;
    }

    /**
     * Get cash drawer status (ready if printer is ready)
     */
    public boolean isCashDrawerReady() {
        return printerService != null;
    }

    /**
     * Check if scanner is available (includes keyboard mode)
     */
    public boolean hasScanner() {
        return keyboardModeEnabled;
    }

    /**
     * Check if printer is available
     */
    public boolean hasPrinter() {
        return printerService != null;
    }

    /**
     * Get device information for printer
     */
    public DeviceInfo getPrinterInfo() {
        DeviceInfo info = new DeviceInfo();
        info.available = printerService != null;
        info.enabled = printerService != null;
        info.logicalName = printerName;
        if (printerService != null) {
            info.physicalName = printerService.getName();
        } else {
            info.physicalName = "Not connected";
        }
        return info;
    }

    /**
     * Get device information for scanner
     */
    public DeviceInfo getScannerInfo() {
        DeviceInfo info = new DeviceInfo();
        info.available = keyboardModeEnabled;
        info.enabled = keyboardModeEnabled;
        info.logicalName = "Barcode Scanner";
        info.physicalName = "USB HID Scanner (Keyboard Mode)";
        return info;
    }

    /**
     * Get device information for cash drawer
     */
    public DeviceInfo getCashDrawerInfo() {
        DeviceInfo info = new DeviceInfo();
        info.available = printerService != null;
        info.enabled = printerService != null;
        info.logicalName = "CashDrawer";
        if (printerService != null) {
            info.physicalName = "Cash Drawer via " + printerService.getName();
        } else {
            info.physicalName = "Not connected (requires printer)";
        }
        return info;
    }

    /**
     * Test printer by printing a test receipt
     */
    public CompletableFuture<Boolean> testPrinter() {
        String testReceipt = "=== TEST RECEIPT ===\n\n" +
                "This is a test print.\n" +
                "If you can read this,\n" +
                "your printer is working!\n\n" +
                "RONGTA RP80 Printer\n" +
                "========================\n";
        return printReceipt(testReceipt).thenApply(result -> result.printed);
    }

    /**
     * Test cash drawer by opening it
     */
    public CompletableFuture<Boolean> testCashDrawer() {
        return openCashDrawer();
    }

    /**
     * Test cash drawer using specific pin
     */
    public CompletableFuture<Boolean> testCashDrawer(int pin) {
        return openCashDrawer(pin);
    }

    /**
     * Device information class
     */
    public static class DeviceInfo {
        public boolean available;
        public boolean enabled;
        public String logicalName;
        public String physicalName;
    }

    /**
     * Get singleton instance
     */
    private static HardwareManager instance;

    public static synchronized HardwareManager getInstance() {
        if (instance == null) {
            instance = new HardwareManager();
        }
        return instance;
    }

    /**
     * Cleanup all hardware devices
     */
    public void cleanup() {
        logger.info("Hardware cleanup performed");
        printerService = null;
        isInitialized = false;
    }

    /**
     * List all available printers (utility method)
     */
    public static void listAvailablePrinters() {
        PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
        logger.info("Available printers ({}):", services.length);
        for (PrintService service : services) {
            logger.info("  - {}", service.getName());
        }
    }
}
