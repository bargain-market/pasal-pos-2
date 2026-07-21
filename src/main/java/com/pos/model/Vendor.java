package com.pos.model;

import javafx.beans.property.*;
import java.math.BigDecimal;

/**
 * Vendor/Supplier model for tracking product suppliers and their payment details.
 * Vendors supply products to the store and receive payouts based on sales.
 */
public class Vendor {
    private final StringProperty id;
    private final StringProperty name;
    private final StringProperty contactName;
    private final StringProperty email;
    private final StringProperty phone;
    private final StringProperty address;
    private final StringProperty paymentTerms;
    private final ObjectProperty<BigDecimal> commissionRate; // Percentage of sale price vendor receives
    private final ObjectProperty<BigDecimal> defaultCostMargin; // Default cost margin if not specified per product
    private final BooleanProperty isActive;
    private final StringProperty bankAccountInfo;
    private final StringProperty notes;
    private final StringProperty createdAt;
    private final StringProperty updatedAt;
    private final BooleanProperty synced;

    public Vendor() {
        this(null, null);
    }

    public Vendor(String id, String name) {
        this.id = new SimpleStringProperty(id);
        this.name = new SimpleStringProperty(name);
        this.contactName = new SimpleStringProperty();
        this.email = new SimpleStringProperty();
        this.phone = new SimpleStringProperty();
        this.address = new SimpleStringProperty();
        this.paymentTerms = new SimpleStringProperty("NET30");
        this.commissionRate = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.defaultCostMargin = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.isActive = new SimpleBooleanProperty(true);
        this.bankAccountInfo = new SimpleStringProperty();
        this.notes = new SimpleStringProperty();
        this.createdAt = new SimpleStringProperty();
        this.updatedAt = new SimpleStringProperty();
        this.synced = new SimpleBooleanProperty(false);
    }

    // ID
    public String getId() { return id.get(); }
    public void setId(String id) { this.id.set(id); }
    public StringProperty idProperty() { return id; }

    // Name
    public String getName() { return name.get(); }
    public void setName(String name) { this.name.set(name); }
    public StringProperty nameProperty() { return name; }

    // Contact Name
    public String getContactName() { return contactName.get(); }
    public void setContactName(String contactName) { this.contactName.set(contactName); }
    public StringProperty contactNameProperty() { return contactName; }

    // Email
    public String getEmail() { return email.get(); }
    public void setEmail(String email) { this.email.set(email); }
    public StringProperty emailProperty() { return email; }

    // Phone
    public String getPhone() { return phone.get(); }
    public void setPhone(String phone) { this.phone.set(phone); }
    public StringProperty phoneProperty() { return phone; }

    // Address
    public String getAddress() { return address.get(); }
    public void setAddress(String address) { this.address.set(address); }
    public StringProperty addressProperty() { return address; }

    // Payment Terms
    public String getPaymentTerms() { return paymentTerms.get(); }
    public void setPaymentTerms(String paymentTerms) { this.paymentTerms.set(paymentTerms); }
    public StringProperty paymentTermsProperty() { return paymentTerms; }

    // Commission Rate
    public BigDecimal getCommissionRate() { return commissionRate.get(); }
    public void setCommissionRate(BigDecimal rate) { this.commissionRate.set(rate); }
    public ObjectProperty<BigDecimal> commissionRateProperty() { return commissionRate; }

    // Default Cost Margin
    public BigDecimal getDefaultCostMargin() { return defaultCostMargin.get(); }
    public void setDefaultCostMargin(BigDecimal margin) { this.defaultCostMargin.set(margin); }
    public ObjectProperty<BigDecimal> defaultCostMarginProperty() { return defaultCostMargin; }

    // Is Active
    public boolean isActive() { return isActive.get(); }
    public void setActive(boolean active) { this.isActive.set(active); }
    public BooleanProperty isActiveProperty() { return isActive; }

    // Bank Account Info
    public String getBankAccountInfo() { return bankAccountInfo.get(); }
    public void setBankAccountInfo(String info) { this.bankAccountInfo.set(info); }
    public StringProperty bankAccountInfoProperty() { return bankAccountInfo; }

    // Notes
    public String getNotes() { return notes.get(); }
    public void setNotes(String notes) { this.notes.set(notes); }
    public StringProperty notesProperty() { return notes; }

    // Created At
    public String getCreatedAt() { return createdAt.get(); }
    public void setCreatedAt(String createdAt) { this.createdAt.set(createdAt); }
    public StringProperty createdAtProperty() { return createdAt; }

    // Updated At
    public String getUpdatedAt() { return updatedAt.get(); }
    public void setUpdatedAt(String updatedAt) { this.updatedAt.set(updatedAt); }
    public StringProperty updatedAtProperty() { return updatedAt; }

    // Synced
    public boolean isSynced() { return synced.get(); }
    public void setSynced(boolean synced) { this.synced.set(synced); }
    public BooleanProperty syncedProperty() { return synced; }

    @Override
    public String toString() {
        return "Vendor{" +
                "id='" + getId() + '\'' +
                ", name='" + getName() + '\'' +
                ", email='" + getEmail() + '\'' +
                ", isActive=" + isActive() +
                '}';
    }
}
