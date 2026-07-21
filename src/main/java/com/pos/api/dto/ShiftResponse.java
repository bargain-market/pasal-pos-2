package com.pos.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response DTO for shift operations
 */
public class ShiftResponse {
    public ShiftData shift;
    
    public static class ShiftData {
        public String id;
        public String storeId;
        public String cashierName;
        public String cashierId;
        public String registerId;
        public Integer shiftNumber;
        public String shiftStartedAt;
        public String shiftEndedAt;
        public String status;
        public BigDecimal openingCash;
        public String openingNote;
        public BigDecimal expectedCash;
        public BigDecimal actualCash;
        public BigDecimal cashDifference;
        public String closingNote;
        public BigDecimal totalCashSales;
        public BigDecimal totalCardSales;
        public BigDecimal totalEbtSales;
        public BigDecimal totalOtherSales;
        public Integer transactionCount;
        public BigDecimal grossSales;
        public BigDecimal netSales;
        public BigDecimal totalDiscounts;
        public BigDecimal totalTax;
        public List<CashOperationData> cashOperations;
    }
    
    public static class CashOperationData {
        public String id;
        public String shiftId;
        public String type;
        public BigDecimal amount;
        public String note;
        public String performedBy;
        public String verifiedBy;
        public String createdAt;
    }
}

