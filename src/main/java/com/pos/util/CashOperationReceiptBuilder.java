package com.pos.util;

import com.pos.service.ShiftService;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Builds printable receipts for shift cash operations.
 */
public final class CashOperationReceiptBuilder {
    private static final String DIVIDER = "--------------------------------\n";
    private static final DateTimeFormatter RECEIPT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CashOperationReceiptBuilder() {
    }

    public static String buildReceipt(String storeName, ShiftService.CashOperationInfo operation) {
        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
        String type = normalizeType(operation != null ? operation.type : null);
        String title = "DROP".equals(type) ? "CASH DROP RECEIPT" : "CASH ADD RECEIPT";
        String operationLabel = "DROP".equals(type) ? "SAFE DROP" : "CASH ADD";
        String amountLabel = "DROP".equals(type) ? "DROP AMOUNT" : "ADD AMOUNT";
        String performedBy = valueOrDefault(operation != null ? operation.performedByName : null, "Unknown");
        String note = valueOrDefault(operation != null ? operation.note : null, "-");
        String shiftId = valueOrDefault(operation != null ? operation.shiftId : null, "-");
        BigDecimal amount = operation != null && operation.amount != null ? operation.amount : BigDecimal.ZERO;

        StringBuilder receipt = new StringBuilder();
        receipt.append("      ").append(valueOrDefault(storeName, "POS Store")).append("\n");
        receipt.append(DIVIDER);
        receipt.append(center(title)).append("\n");
        receipt.append(DIVIDER).append("\n");
        receipt.append("Date:     ").append(formatCreatedAt(operation != null ? operation.createdAt : null)).append("\n");
        receipt.append("Staff:    ").append(performedBy).append("\n");
        receipt.append("Type:     ").append(operationLabel).append("\n");
        receipt.append("Shift ID: ").append(shiftId).append("\n");
        receipt.append(DIVIDER).append("\n");
        receipt.append(amountLabel).append(": ").append(currencyFormat.format(amount)).append("\n");
        receipt.append("NOTE: ").append(note).append("\n\n");
        receipt.append(DIVIDER);
        receipt.append("         SIGNATURE BELOW        \n\n\n");
        receipt.append("X_______________________________\n");
        receipt.append("      Manager Signature         \n\n");
        receipt.append(DIVIDER);
        receipt.append("\n\n\n\n");
        return receipt.toString();
    }

    private static String normalizeType(String type) {
        return "ADD".equalsIgnoreCase(type) ? "ADD" : "DROP";
    }

    private static String formatCreatedAt(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) {
            return LocalDateTime.now().format(RECEIPT_DATE_FORMAT);
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(createdAt), ZoneId.systemDefault()).format(RECEIPT_DATE_FORMAT);
        } catch (Exception ignored) {
            return createdAt;
        }
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String center(String text) {
        if (text == null || text.length() >= 32) {
            return text != null ? text : "";
        }
        int padding = (32 - text.length()) / 2;
        return " ".repeat(Math.max(0, padding)) + text;
    }
}
