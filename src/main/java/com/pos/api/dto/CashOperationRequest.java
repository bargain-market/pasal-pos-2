package com.pos.api.dto;

import java.math.BigDecimal;

/**
 * Request DTO for cash operations
 */
public class CashOperationRequest {
    public String type; // DROP, ADD, NO_SALE, PAYOUT, ADJUSTMENT
    public BigDecimal amount;
    public String note;
    public String performedBy;
    public String verifiedBy;
    public String overridePin; // PIN to override daily cash/check limit

    public CashOperationRequest() {}

    public CashOperationRequest(String type, BigDecimal amount) {
        this.type = type;
        this.amount = amount;
    }
}

