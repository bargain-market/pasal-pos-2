package com.pos.util;

import com.pos.model.VendorPayout;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Builds printable receipts for vendor payouts.
 */
public final class VendorPayoutReceiptBuilder {
    private static final String DIVIDER = "--------------------------------\n";
    private static final DateTimeFormatter RECEIPT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter INPUT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private VendorPayoutReceiptBuilder() {
    }

    public static String buildReceipt(String storeName, VendorPayout payout) {
        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
        
        String vendorName = valueOrDefault(payout.getVendorName(), "Unknown Vendor");
        String paidBy = valueOrDefault(payout.getPaidBy(), "Unknown");
        String method = valueOrDefault(payout.getPaymentMethod(), "CASH");
        String reference = buildReferenceDisplay(payout);
        BigDecimal amount = payout.getAmountPaid() != null ? payout.getAmountPaid() : BigDecimal.ZERO;
        String dateStr = formatDateTime(payout.getPaidAt());

        StringBuilder receipt = new StringBuilder();
        receipt.append("      ").append(valueOrDefault(storeName, "POS Store")).append("\n");
        receipt.append(DIVIDER);
        receipt.append(center("VENDOR PAYOUT RECEIPT")).append("\n");
        receipt.append(DIVIDER).append("\n");
        receipt.append("Date:     ").append(dateStr).append("\n");
        receipt.append("Vendor:   ").append(vendorName).append("\n");
        receipt.append("Staff:    ").append(paidBy).append("\n");
        receipt.append("Method:   ").append(method).append("\n");
        receipt.append("Ref:      ").append(reference).append("\n");
        receipt.append(DIVIDER).append("\n");
        receipt.append("PAID AMOUNT: ").append(currencyFormat.format(amount)).append("\n");
        
        if (payout.getNotes() != null && !payout.getNotes().isBlank()) {
            receipt.append("NOTES: ").append(payout.getNotes()).append("\n");
        }
        
        receipt.append("\n").append(DIVIDER);
        receipt.append("         SIGNATURE BELOW        \n\n\n");
        receipt.append("X_______________________________\n");
        receipt.append("      Vendor Representative      \n\n");
        receipt.append(DIVIDER);
        receipt.append("\n\n\n\n");
        
        return receipt.toString();
    }

    private static String formatDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isBlank()) {
            return LocalDateTime.now().format(RECEIPT_DATE_FORMAT);
        }
        try {
            // PaidAt is usually in "yyyy-MM-dd HH:mm:ss" format
            LocalDateTime dt = LocalDateTime.parse(dateTimeStr, INPUT_DATE_FORMAT);
            return dt.format(RECEIPT_DATE_FORMAT);
        } catch (Exception ignored) {
            return dateTimeStr;
        }
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String buildReferenceDisplay(VendorPayout payout) {
        String paymentMethod = payout.getPaymentMethod() != null ? payout.getPaymentMethod().trim().toUpperCase() : "";
        String chequeNumber = payout.getChequeNumber();
        String invoiceNumber = payout.getPaymentReference();

        if ("CHEQUE".equals(paymentMethod)) {
            if (chequeNumber != null && !chequeNumber.isBlank() && invoiceNumber != null && !invoiceNumber.isBlank()) {
                return chequeNumber + " / Inv " + invoiceNumber;
            }
            if (chequeNumber != null && !chequeNumber.isBlank()) {
                return chequeNumber;
            }
            if (invoiceNumber != null && !invoiceNumber.isBlank()) {
                return "Inv " + invoiceNumber;
            }
            return "-";
        }

        String reference = invoiceNumber;
        if (reference == null || reference.isBlank()) {
            reference = chequeNumber;
        }
        return valueOrDefault(reference, "-");
    }

    private static String center(String text) {
        if (text == null || text.length() >= 32) {
            return text != null ? text : "";
        }
        int padding = (32 - text.length()) / 2;
        return " ".repeat(Math.max(0, padding)) + text;
    }
}
