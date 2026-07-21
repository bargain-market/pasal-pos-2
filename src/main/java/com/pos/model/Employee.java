package com.pos.model;

import javafx.beans.property.*;

/**
 * Employee model (POS User)
 */
public class Employee {
    private final StringProperty id;
    private final StringProperty username;
    private final StringProperty fullName;
    private final StringProperty storeId;
    private final BooleanProperty isActive;
    private final StringProperty role; // Cashier, Manager, Admin (local management)
    private final StringProperty lastLoginAt;
    private final StringProperty createdAt;
    
    public Employee(String id, String username, String fullName, String storeId, boolean isActive) {
        this(id, username, fullName, storeId, isActive, "Cashier", null, null);
    }
    
    public Employee(String id, String username, String fullName, String storeId, boolean isActive, String role) {
        this(id, username, fullName, storeId, isActive, role, null, null);
    }
    
    public Employee(String id, String username, String fullName, String storeId, boolean isActive, String role, String lastLoginAt, String createdAt) {
        this.id = new SimpleStringProperty(id);
        this.username = new SimpleStringProperty(username);
        this.fullName = new SimpleStringProperty(fullName);
        this.storeId = new SimpleStringProperty(storeId);
        this.isActive = new SimpleBooleanProperty(isActive);
        this.role = new SimpleStringProperty(role != null ? role : "Cashier");
        this.lastLoginAt = new SimpleStringProperty(lastLoginAt);
        this.createdAt = new SimpleStringProperty(createdAt);
    }
    
    // Getters
    public String getId() { return id != null ? id.get() : null; }
    public String getUsername() { return username.get(); }
    public String getFullName() { return fullName.get(); }
    public String getStoreId() { return storeId.get(); }
    public boolean isActive() { return isActive.get(); }
    public String getRole() { return role.get(); }
    public String getLastLoginAt() { return lastLoginAt.get(); }
    public String getCreatedAt() { return createdAt.get(); }
    
    // Property getters for JavaFX binding
    public StringProperty idProperty() { return id; }
    public StringProperty usernameProperty() { return username; }
    public StringProperty fullNameProperty() { return fullName; }
    public StringProperty storeIdProperty() { return storeId; }
    public BooleanProperty isActiveProperty() { return isActive; }
    public StringProperty roleProperty() { return role; }
    public StringProperty lastLoginAtProperty() { return lastLoginAt; }
    public StringProperty createdAtProperty() { return createdAt; }
    
    // Setters
    public void setUsername(String username) { this.username.set(username); }
    public void setFullName(String fullName) { this.fullName.set(fullName); }
    public void setStoreId(String storeId) { this.storeId.set(storeId); }
    public void setIsActive(boolean isActive) { this.isActive.set(isActive); }
    public void setRole(String role) { this.role.set(role != null ? role : "Cashier"); }
    public void setLastLoginAt(String lastLoginAt) { this.lastLoginAt.set(lastLoginAt); }
    public void setCreatedAt(String createdAt) { this.createdAt.set(createdAt); }
}

