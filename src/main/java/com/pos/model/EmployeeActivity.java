package com.pos.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * DTO representing an activity performed by an employee during a shift.
 */
public class EmployeeActivity {
    
    public enum Type {
        SALE("Sale"),
        VOID("Void"),
        CASH_DROP("Cash Drop"),
        CASH_ADD("Cash Add"),
        NO_SALE("No Sale"),
        EXPENSE("Expense"),
        VENDOR_PAYOUT("Vendor Payout"),
        REFUND("Refund");

        private final String label;
        Type(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    private Type type;
    private String description;
    private BigDecimal amount;
    private LocalDateTime timestamp;
    private String referenceId;

    public EmployeeActivity(Type type, String description, BigDecimal amount, LocalDateTime timestamp, String referenceId) {
        this.type = type;
        this.description = description;
        this.amount = amount;
        this.timestamp = timestamp;
        this.referenceId = referenceId;
    }

    // Getters
    public Type getType() { return type; }
    public String getTypeLabel() { return type.getLabel(); }
    public String getDescription() { return description; }
    public BigDecimal getAmount() { return amount; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getReferenceId() { return referenceId; }

    public String getFormattedTimestamp() {
        if (timestamp == null) return "";
        return timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public String getFormattedAmount() {
        if (amount == null) return "-";
        return String.format("$%.2f", amount.abs());
    }
}
