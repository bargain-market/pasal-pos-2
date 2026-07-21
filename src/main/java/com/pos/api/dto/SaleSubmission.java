package com.pos.api.dto;

import java.util.List;

/**
 * Sale submission DTO
 */
public class SaleSubmission {
    public String saleId;
    public String posSaleId;
    public List<SaleItemInput> items;
    public Double subtotal;
    public Double discount;
    public Double tax;
    public Double total;
    public Double gpi;
    public Double ebtFee;
    public String paymentMethod; // CASH, CARD, DIGITAL, EBT, or SPLIT for split payments
    public Boolean isSplitPayment; // True if this is a split payment sale
    public List<SplitPaymentDetail> splitPayments; // Details of each split payment
    public PaymentDetails paymentDetails;
    public String cashierId; // Legacy field
    public String cashierName;
    public String posUserId; // POS user ID (for proper authentication)
    public String customerId;
    public String timestamp;
    public String localDate; // Local date in YYYY-MM-DD format for proper date-based filtering
    // Cash payment details (only for CASH payments)
    public Double amountReceived; // Amount received from customer
    public Double change; // Change given to customer

    // Void details
    public Boolean voided;
    public String voidedAt;
    public String voidedBy;
    public String voidReason;

    public static class SaleItemInput {
        public String productId;
        public String sku;
        public String name;
        public Double price;
        public Integer quantity;
        public Double subtotal;
        public Double discount;
        public Double gpi;
        public String discountReason;
        public String imageUrl;

        // Open department item fields
        public Boolean isOpenDepartmentItem; // True if this is an open department/price item
        public String departmentId; // Department ID for open department items
        public String departmentName; // Department name for reporting

        // Vendor fields for payout tracking
        public String vendorId; // Vendor/Supplier ID
        public String vendorName; // Vendor name for reporting
        public Double cost; // Cost price from vendor
    }

    public static class PaymentDetails {
        public String cardType;
        public String cardLastFour;
        public String cardNetwork;
        public String transactionRef;
        public String authCode;
        public String entryMode;
        public String paymentProcessor;
        public String approvalStatus;
        public String ebtType;
        public String digitalWallet;
    }

    /**
     * Split payment detail for tracking individual payments in a split payment sale
     */
    public static class SplitPaymentDetail {
        public String paymentMethod; // CASH, CARD, DIGITAL, EBT
        public Double amount;

        public SplitPaymentDetail() {
        }

        public SplitPaymentDetail(String paymentMethod, Double amount) {
            this.paymentMethod = paymentMethod;
            this.amount = amount;
        }
    }
}
