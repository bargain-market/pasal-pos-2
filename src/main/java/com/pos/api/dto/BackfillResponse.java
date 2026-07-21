package com.pos.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response DTO for GET /pos/backfill?days=N.
 *
 * <p>Carries the store's sales, refunds, shifts (+ cash operations) and vendor
 * payouts recorded over the last N days, shaped to match the POS local database
 * tables. Consumed by {@code BackfillInboundSync} to restore data lost from the
 * local H2 database.</p>
 *
 * <p>Field names mirror the JSON keys emitted by the backend controller
 * ({@code getBackfillData}). Expenses are not synced to the backend and are
 * therefore absent here.</p>
 */
public class BackfillResponse {
    public String fromDate;
    public Integer days;
    public Counts counts;

    public List<Sale> sales;
    public List<Refund> refunds;
    public List<Shift> shifts;
    public List<VendorPayout> vendorPayouts;

    public static class Counts {
        public Integer sales;
        public Integer refunds;
        public Integer shifts;
        public Integer vendorPayouts;
    }

    public static class Sale {
        public String id;
        public String saleId;
        public BigDecimal subtotal;
        public BigDecimal discount;
        public BigDecimal tax;
        public BigDecimal total;
        public String paymentMethod;
        public Boolean isSplitPayment;
        public String cashierName;
        public String cashierId;
        public String posUserId;
        public String timestamp;
        public String createdAt;
        public BigDecimal amountReceived;
        public BigDecimal change;
        public BigDecimal gpi;
        public BigDecimal ebtFee;
        public Boolean voided;
        public String voidedAt;
        public String voidedBy;
        public String voidReason;
        public List<SaleItem> items;
        public List<SalePayment> payments;
    }

    public static class SaleItem {
        public String productId;
        public String sku;
        public String name;
        public BigDecimal price;
        public Integer quantity;
        public BigDecimal subtotal;
        public BigDecimal discount;
        public String departmentId;
        public String departmentName;
    }

    public static class SalePayment {
        public String paymentMethod;
        public BigDecimal amount;
    }

    public static class Refund {
        public String id;
        public String refundId;
        public String originalSaleId;
        public BigDecimal refundAmount;
        public BigDecimal refundTax;
        public BigDecimal totalRefund;
        public String paymentMethod;
        public String refundMethod;
        public String cashierName;
        public String posUserId;
        public String timestamp;
        public String createdAt;
        public List<RefundItem> items;
    }

    public static class RefundItem {
        public String productId;
        public String sku;
        public String name;
        public BigDecimal originalPrice;
        public Integer originalQuantity;
        public Integer refundQuantity;
        public BigDecimal refundAmount;
    }

    public static class Shift {
        public String id;
        public String shiftId;
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
        public String createdAt;
        public String updatedAt;
        public List<CashOperation> cashOperations;
    }

    public static class CashOperation {
        public String id;
        public String shiftId;
        public String type;
        public BigDecimal amount;
        public String note;
        public String performedBy;
        public String verifiedBy;
        public String createdAt;
    }

    public static class VendorPayout {
        public String id;
        public String vendorId;
        public String vendorName;
        public String periodStart;
        public String periodEnd;
        public BigDecimal totalSales;
        public BigDecimal totalCost;
        public BigDecimal totalPayout;
        public BigDecimal commissionRate;
        public Integer itemCount;
        public Integer transactionCount;
        public String status;
        public String paidAt;
        public String paidBy;
        public String paymentMethod;
        public String paymentReference;
        public String notes;
        public String createdAt;
        public String updatedAt;
        public List<VendorPayoutItem> items;
    }

    public static class VendorPayoutItem {
        public String id;
        public String saleId;
        public Integer saleItemId;
        public String productId;
        public String productName;
        public String productSku;
        public Integer quantity;
        public BigDecimal unitPrice;
    }
}
