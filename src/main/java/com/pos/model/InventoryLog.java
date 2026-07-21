package com.pos.model;

import java.time.LocalDateTime;

/**
 * Inventory log entry model for tracking stock adjustments
 */
public class InventoryLog {
    private String id;
    private String productId;
    private String productName;
    private String changeType; // ADJUSTMENT, RECEIVE, TRANSFER, RECONCILIATION, SALE
    private int previousQuantity;
    private int newQuantity;
    private int change; // difference (can be positive or negative)
    private String reason;
    private String userId;
    private String userName;
    private LocalDateTime createdAt;
    private boolean synced;

    public InventoryLog() {
    }

    public InventoryLog(String id, String productId, String productName, String changeType,
            int previousQuantity, int newQuantity, int change, String reason,
            String userId, String userName, LocalDateTime createdAt) {
        this(id, productId, productName, changeType, previousQuantity, newQuantity, change, reason, userId, userName,
                createdAt, false);
    }

    public InventoryLog(String id, String productId, String productName, String changeType,
            int previousQuantity, int newQuantity, int change, String reason,
            String userId, String userName, LocalDateTime createdAt, boolean synced) {
        this.id = id;
        this.productId = productId;
        this.productName = productName;
        this.changeType = changeType;
        this.previousQuantity = previousQuantity;
        this.newQuantity = newQuantity;
        this.change = change;
        this.reason = reason;
        this.userId = userId;
        this.userName = userName;
        this.createdAt = createdAt;
        this.synced = synced;
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public String getChangeType() {
        return changeType;
    }

    public int getPreviousQuantity() {
        return previousQuantity;
    }

    public int getNewQuantity() {
        return newQuantity;
    }

    public int getChange() {
        return change;
    }

    public String getReason() {
        return reason;
    }

    public String getUserId() {
        return userId;
    }

    public String getUserName() {
        return userName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean isSynced() {
        return synced;
    }

    // Setters
    public void setId(String id) {
        this.id = id;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public void setChangeType(String changeType) {
        this.changeType = changeType;
    }

    public void setPreviousQuantity(int previousQuantity) {
        this.previousQuantity = previousQuantity;
    }

    public void setNewQuantity(int newQuantity) {
        this.newQuantity = newQuantity;
    }

    public void setChange(int change) {
        this.change = change;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setSynced(boolean synced) {
        this.synced = synced;
    }
}
