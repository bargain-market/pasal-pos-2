package com.pos.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV parser utility for importing inventory data from CSV files.
 * Supports multiple formats including Generic, NRS, and our Pasal POS 2.
 */
public class InventoryCSVParser {

    private static final Logger logger = LoggerFactory.getLogger(InventoryCSVParser.class);

    public enum ImportFormat {
        GENERIC("Generic (Others)"),
        NRS("NRS POS"),
        POS_SYSTEM("This Pasal POS 2 (Store Transfer)");

        private final String label;

        ImportFormat(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Parsed product data from CSV
     */
    public static class ParsedProduct {
        public String sku;
        public String barcode;
        public String name;
        public BigDecimal price;
        public int stock;
        public String departmentName;
        public Boolean taxEnabled;
        public BigDecimal taxRate;
        public Boolean ebtEligible;
        public Integer ageVerification;
        public String rawLine; // For debugging
        public int lineNumber;

        public ParsedProduct(String sku, String name, BigDecimal price, int stock) {
            this.sku = sku;
            this.barcode = sku; // Use SKU as barcode by default
            this.name = name;
            this.price = price;
            this.stock = stock;
        }

        @Override
        public String toString() {
            return String.format("Product[sku=%s, name=%s, price=%s, stock=%d]",
                    sku, name, price, stock);
        }
    }

    /**
     * Parse result with products and any errors
     */
    public static class ParseResult {
        public List<ParsedProduct> products = new ArrayList<>();
        public List<String> errors = new ArrayList<>();
        public int totalLinesProcessed = 0;
        public int skippedLines = 0;

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /**
     * Parse a CSV file and return the list of parsed products
     */
    public static ParseResult parseCSV(File file, ImportFormat format) throws IOException {
        ParseResult result = new ParseResult();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNumber = 0;
            Map<String, Integer> headerMap = null;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                result.totalLinesProcessed++;

                // Skip empty lines
                if (line.trim().isEmpty() || line.replaceAll(",", "").trim().isEmpty()) {
                    result.skippedLines++;
                    continue;
                }

                String[] columns = parseCSVLine(line);

                // Handle header detection for dynamic formats
                if (format != ImportFormat.GENERIC && headerMap == null) {
                    if (isHeaderRow(columns, format)) {
                        headerMap = mapHeaders(columns);
                        result.skippedLines++; // Skip header row
                        continue;
                    } else if (lineNumber <= 5) {
                        // Assume first few lines might be metadata if not header
                        result.skippedLines++;
                        continue;
                    }
                    // If we haven't found header by line 5, try to parse anyway or fail?
                    // Let's assume if we are here, we didn't find a header yet but maybe this is
                    // data?
                    // Ideally we need a header for non-generic formats.
                    if (headerMap == null) {
                        result.errors.add("Could not detect valid header row for " + format);
                        break;
                    }
                }

                // Parse the CSV line based on format
                try {
                    ParsedProduct product = null;
                    if (format == ImportFormat.GENERIC) {
                        product = parseGenericProduct(columns, lineNumber);
                    } else {
                        // For NRS and POS_SYSTEM, we rely on the header map
                        if (headerMap != null) {
                            product = parseDynamicProduct(columns, lineNumber, headerMap, format);
                        }
                    }

                    if (product != null) {
                        result.products.add(product);
                    } else {
                        result.skippedLines++;
                    }
                } catch (Exception e) {
                    String errorMsg = String.format("Line %d: %s", lineNumber, e.getMessage());
                    result.errors.add(errorMsg);
                    logger.warn("Error parsing line {}: {}", lineNumber, e.getMessage());
                    result.skippedLines++;
                }
            }
        }

        logger.info("Parsed {} products from CSV using format {} ({} lines processed, {} skipped)",
                result.products.size(), format, result.totalLinesProcessed, result.skippedLines);

        return result;
    }

    // Default legacy method for backward compatibility
    public static ParseResult parseCSV(File file) throws IOException {
        return parseCSV(file, ImportFormat.GENERIC);
    }

    private static boolean isHeaderRow(String[] columns, ImportFormat format) {
        if (columns == null || columns.length == 0)
            return false;

        String lineStr = String.join(" ", columns).toLowerCase();

        if (format == ImportFormat.NRS) {
            // NRS typically has UPC, Description, Price, Quantity
            return (lineStr.contains("upc") || lineStr.contains("barcode")) &&
                    (lineStr.contains("description") || lineStr.contains("name")) &&
                    (lineStr.contains("price") || lineStr.contains("amount"));
        } else if (format == ImportFormat.POS_SYSTEM) {
            // Our system export likely has
            return (lineStr.contains("sku") || lineStr.contains("barcode")) &&
                    (lineStr.contains("name") || lineStr.contains("product"));
        }
        return false;
    }

    private static Map<String, Integer> mapHeaders(String[] columns) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < columns.length; i++) {
            map.put(cleanValue(columns[i]).toLowerCase(), i);
        }
        return map;
    }

    private static ParsedProduct parseDynamicProduct(String[] columns, int lineNumber, Map<String, Integer> headerMap,
            ImportFormat format) {
        String sku = "";
        String name = "";
        BigDecimal price = BigDecimal.ZERO;
        int stock = 0;

        // Find columns based on flexible keyword matching in headers
        int skuIdx = findColumnIndex(headerMap, "sku", "barcode", "upc", "item #", "item no");
        int nameIdx = findColumnIndex(headerMap, "name", "description", "desc", "product");
        int priceIdx = findColumnIndex(headerMap, "price", "retail", "selling price", "amount");
        int stockIdx = findColumnIndex(headerMap, "stock", "qty", "quantity", "on hand", "inventory");
        int deptIdx = findColumnIndex(headerMap, "department", "dept", "category", "group");
        int taxEnabledIdx = findColumnIndex(headerMap, "dept tax enabled", "tax enabled");
        int taxRateIdx = findColumnIndex(headerMap, "dept tax rate", "tax rate");
        int ebtIdx = findColumnIndex(headerMap, "dept ebt eligible", "ebt eligible");
        int ageIdx = findColumnIndex(headerMap, "dept age vr", "age verification");

        if (skuIdx != -1 && skuIdx < columns.length)
            sku = cleanSku(columns[skuIdx]);
        if (nameIdx != -1 && nameIdx < columns.length)
            name = cleanValue(columns[nameIdx]);
        if (priceIdx != -1 && priceIdx < columns.length)
            price = parsePrice(columns[priceIdx]);
        if (stockIdx != -1 && stockIdx < columns.length)
            stock = parseStock(columns[stockIdx]);

        if (sku.isEmpty() && name.isEmpty())
            return null; // Skip empty rows

        // If name is empty, use SKU
        if (name.isEmpty())
            name = sku;

        ParsedProduct product = new ParsedProduct(sku, name, price, stock);
        product.lineNumber = lineNumber;

        // Parse department info
        if (deptIdx != -1 && deptIdx < columns.length) {
            product.departmentName = cleanValue(columns[deptIdx]);
        }

        if (taxEnabledIdx != -1 && taxEnabledIdx < columns.length) {
            String val = cleanValue(columns[taxEnabledIdx]).toLowerCase();
            product.taxEnabled = val.equals("true") || val.equals("yes") || val.equals("1");
        }

        if (taxRateIdx != -1 && taxRateIdx < columns.length) {
            product.taxRate = parsePrice(columns[taxRateIdx]);
        }

        if (ebtIdx != -1 && ebtIdx < columns.length) {
            String val = cleanValue(columns[ebtIdx]).toLowerCase();
            product.ebtEligible = val.equals("true") || val.equals("yes") || val.equals("1");
        }

        if (ageIdx != -1 && ageIdx < columns.length) {
            product.ageVerification = parseStock(columns[ageIdx]);
        }

        return product;
    }

    private static int findColumnIndex(Map<String, Integer> headerMap, String... keywords) {
        for (String keyword : keywords) {
            for (String header : headerMap.keySet()) {
                if (header.contains(keyword)) {
                    return headerMap.get(header);
                }
            }
        }
        return -1;
    }

    private static ParsedProduct parseGenericProduct(String[] columns, int lineNumber) {
        // Original hardcoded logic
        // Column indices for the specific CSV format
        int COL_SKU = 0;
        int COL_DESCRIPTION = 5;
        int COL_ON_HAND = 13;
        int COL_PRICE = 21; // CAREFUL: Original code said 22 in constant def but code comment said 21?
        // Looking at file content:
        // private static final int COL_PRICE = 22;
        // private static final int COL_ON_HAND = 13;
        // private static final int COL_DESCRIPTION = 5;
        // private static final int COL_SKU = 0;

        // Re-aligning with previous file content
        COL_PRICE = 22;

        if (columns.length <= COL_PRICE)
            return null;

        String sku = cleanSku(columns[COL_SKU]);
        if (sku.isEmpty())
            return null;

        String name = cleanValue(columns[COL_DESCRIPTION]);
        if (name.isEmpty())
            name = sku;

        BigDecimal price = parsePrice(columns[COL_PRICE]);
        int stock = parseStock(columns[COL_ON_HAND]);

        ParsedProduct product = new ParsedProduct(sku, name, price, stock);
        product.lineNumber = lineNumber;
        return product;
    }

    /**
     * Parse a single CSV line handling quoted values
     */
    private static String[] parseCSVLine(String line) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                columns.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        columns.add(current.toString()); // Add last column

        return columns.toArray(new String[0]);
    }

    private static String cleanValue(String value) {
        if (value == null)
            return "";
        value = value.trim();
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.trim();
    }

    private static String cleanSku(String sku) {
        if (sku == null || sku.isEmpty())
            return "";
        sku = sku.replaceAll("^[`~!@#$%^&*()\\-_+=\\[\\]{}|\\\\:;\"'<>,.?/\\s]+", "");
        sku = sku.replaceAll("[`~!@#$%^&*()\\-_+=\\[\\]{}|\\\\:;\"'<>,.?/\\s]+$", "");
        if (sku.toLowerCase().contains("http") || sku.toLowerCase().contains("bit.ly"))
            return "";
        return sku.trim();
    }

    private static BigDecimal parsePrice(String priceStr) {
        if (priceStr == null || priceStr.trim().isEmpty())
            return BigDecimal.ZERO;
        try {
            String cleaned = priceStr.replaceAll("[\"'$€£¥,\\s]", "").trim();
            if (cleaned.startsWith("-"))
                cleaned = cleaned.substring(1);
            if (cleaned.isEmpty())
                return BigDecimal.ZERO;
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static int parseStock(String stockStr) {
        if (stockStr == null || stockStr.trim().isEmpty())
            return 0;
        try {
            String cleaned = stockStr.replaceAll("[\"',\\s]", "").trim();
            if (cleaned.isEmpty())
                return 0;
            double value = Double.parseDouble(cleaned);
            return value < 0 ? 0 : (int) value;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
