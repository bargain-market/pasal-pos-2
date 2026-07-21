package com.pos.model;

import java.math.BigDecimal;

/**
 * Payment model for split payments
 */
public class Payment {
    private String paymentMethod; // CASH, CARD, DIGITAL, EBT
    private BigDecimal amount;
    
    public Payment(String paymentMethod, BigDecimal amount) {
        this.paymentMethod = paymentMethod;
        this.amount = amount;
    }
    
    public String getPaymentMethod() {
        return paymentMethod;
    }
    
    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }
    
    public BigDecimal getAmount() {
        return amount;
    }
    
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}

