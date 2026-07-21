package com.pos.api.dto;

import java.math.BigDecimal;

/**
 * Request DTO for starting a shift
 */
public class ShiftStartRequest {
    public String storeId;
    public String cashierName;
    public String cashierId;
    public String registerId;
    public Integer shiftNumber;
    public BigDecimal openingCash;
    public String openingNote;
    
    public ShiftStartRequest() {}
    
    public ShiftStartRequest(String cashierName, String registerId, BigDecimal openingCash) {
        this.cashierName = cashierName;
        this.registerId = registerId;
        this.openingCash = openingCash;
    }
}

