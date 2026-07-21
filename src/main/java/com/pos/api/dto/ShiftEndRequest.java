package com.pos.api.dto;

import java.math.BigDecimal;

/**
 * Request DTO for ending a shift
 */
public class ShiftEndRequest {
    public BigDecimal actualCash;
    public String closingNote;
    
    public ShiftEndRequest() {}
    
    public ShiftEndRequest(BigDecimal actualCash) {
        this.actualCash = actualCash;
    }
}

