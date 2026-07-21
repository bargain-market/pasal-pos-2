package com.pos.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Refund model - represents a refund transaction
 */
public class Refund {
    private String id;
    private String refundId; // Unique refund ID
    private String originalSaleId; // Sale being refunded
    private BigDecimal refundAmount;
    private BigDecimal refundTax;
    private BigDecimal totalRefund;
    private String paymentMethod; // Original payment method (CASH, CARD, etc.)
    private String refundMethod; // How refund is being processed
    private String reason;
    private String cashierName;
    private String posUserId;
    private String timestamp;
    private boolean synced;
    private List<RefundItem> items;
    
    public Refund() {
        this.items = new ArrayList<>();
        this.refundAmount = BigDecimal.ZERO;
        this.refundTax = BigDecimal.ZERO;
        this.totalRefund = BigDecimal.ZERO;
        this.synced = false;
    }
    
    /**
     * Refund item - represents an item being refunded
     */
    public static class RefundItem {
        private String productId;
        private String sku;
        private String name;
        private BigDecimal originalPrice;
        private int originalQuantity;
        private int refundQuantity; // Quantity being refunded
        private BigDecimal refundAmount; // Amount refunded for this item
        
        public RefundItem() {
            this.refundAmount = BigDecimal.ZERO;
        }
        
        // Getters and setters
        public String getProductId() { return productId; }
        public void setProductId(String productId) { this.productId = productId; }
        
        public String getSku() { return sku; }
        public void setSku(String sku) { this.sku = sku; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public BigDecimal getOriginalPrice() { return originalPrice; }
        public void setOriginalPrice(BigDecimal originalPrice) { this.originalPrice = originalPrice; }
        
        public int getOriginalQuantity() { return originalQuantity; }
        public void setOriginalQuantity(int originalQuantity) { this.originalQuantity = originalQuantity; }
        
        public int getRefundQuantity() { return refundQuantity; }
        public void setRefundQuantity(int refundQuantity) { this.refundQuantity = refundQuantity; }
        
        public BigDecimal getRefundAmount() { return refundAmount; }
        public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }
    }
    
    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getRefundId() { return refundId; }
    public void setRefundId(String refundId) { this.refundId = refundId; }
    
    public String getOriginalSaleId() { return originalSaleId; }
    public void setOriginalSaleId(String originalSaleId) { this.originalSaleId = originalSaleId; }
    
    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }
    
    public BigDecimal getRefundTax() { return refundTax; }
    public void setRefundTax(BigDecimal refundTax) { this.refundTax = refundTax; }
    
    public BigDecimal getTotalRefund() { return totalRefund; }
    public void setTotalRefund(BigDecimal totalRefund) { this.totalRefund = totalRefund; }
    
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    
    public String getRefundMethod() { return refundMethod; }
    public void setRefundMethod(String refundMethod) { this.refundMethod = refundMethod; }
    
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    
    public String getCashierName() { return cashierName; }
    public void setCashierName(String cashierName) { this.cashierName = cashierName; }
    
    public String getPosUserId() { return posUserId; }
    public void setPosUserId(String posUserId) { this.posUserId = posUserId; }
    
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    
    public boolean isSynced() { return synced; }
    public void setSynced(boolean synced) { this.synced = synced; }
    
    public List<RefundItem> getItems() { return items; }
    public void setItems(List<RefundItem> items) { this.items = items; }
}

