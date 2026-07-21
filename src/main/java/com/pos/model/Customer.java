package com.pos.model;

import javafx.beans.property.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Customer model for loyalty and customer lookup
 */
public class Customer {
    private final StringProperty id;
    private final StringProperty firstName;
    private final StringProperty lastName;
    private final StringProperty email;
    private final StringProperty phone;
    private final ObjectProperty<BigDecimal> loyaltyPoints;
    private final ObjectProperty<BigDecimal> totalSpent;
    private final IntegerProperty visitCount;
    private final ObjectProperty<LocalDateTime> lastVisit;
    private final StringProperty membershipTier;
    private final BooleanProperty isActive;
    private final StringProperty notes;

    public Customer() {
        this(null, null, null, null, null);
    }

    public Customer(String id, String firstName, String lastName, String email, String phone) {
        this.id = new SimpleStringProperty(id);
        this.firstName = new SimpleStringProperty(firstName);
        this.lastName = new SimpleStringProperty(lastName);
        this.email = new SimpleStringProperty(email);
        this.phone = new SimpleStringProperty(phone);
        this.loyaltyPoints = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.totalSpent = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.visitCount = new SimpleIntegerProperty(0);
        this.lastVisit = new SimpleObjectProperty<>(null);
        this.membershipTier = new SimpleStringProperty("STANDARD");
        this.isActive = new SimpleBooleanProperty(true);
        this.notes = new SimpleStringProperty("");
    }

    // Full name helper
    public String getFullName() {
        String first = firstName.get();
        String last = lastName.get();
        if (first == null && last == null) return "N/A";
        if (first == null) return last;
        if (last == null) return first;
        return first + " " + last;
    }

    // Getters
    public String getId() { return id.get(); }
    public String getFirstName() { return firstName.get(); }
    public String getLastName() { return lastName.get(); }
    public String getEmail() { return email.get(); }
    public String getPhone() { return phone.get(); }
    public BigDecimal getLoyaltyPoints() { return loyaltyPoints.get(); }
    public BigDecimal getTotalSpent() { return totalSpent.get(); }
    public int getVisitCount() { return visitCount.get(); }
    public LocalDateTime getLastVisit() { return lastVisit.get(); }
    public String getMembershipTier() { return membershipTier.get(); }
    public boolean isActive() { return isActive.get(); }
    public String getNotes() { return notes.get(); }

    // Property getters
    public StringProperty idProperty() { return id; }
    public StringProperty firstNameProperty() { return firstName; }
    public StringProperty lastNameProperty() { return lastName; }
    public StringProperty emailProperty() { return email; }
    public StringProperty phoneProperty() { return phone; }
    public ObjectProperty<BigDecimal> loyaltyPointsProperty() { return loyaltyPoints; }
    public ObjectProperty<BigDecimal> totalSpentProperty() { return totalSpent; }
    public IntegerProperty visitCountProperty() { return visitCount; }
    public ObjectProperty<LocalDateTime> lastVisitProperty() { return lastVisit; }
    public StringProperty membershipTierProperty() { return membershipTier; }
    public BooleanProperty isActiveProperty() { return isActive; }
    public StringProperty notesProperty() { return notes; }

    // Setters
    public void setId(String id) { this.id.set(id); }
    public void setFirstName(String firstName) { this.firstName.set(firstName); }
    public void setLastName(String lastName) { this.lastName.set(lastName); }
    public void setEmail(String email) { this.email.set(email); }
    public void setPhone(String phone) { this.phone.set(phone); }
    public void setLoyaltyPoints(BigDecimal points) { this.loyaltyPoints.set(points); }
    public void setTotalSpent(BigDecimal spent) { this.totalSpent.set(spent); }
    public void setVisitCount(int count) { this.visitCount.set(count); }
    public void setLastVisit(LocalDateTime visit) { this.lastVisit.set(visit); }
    public void setMembershipTier(String tier) { this.membershipTier.set(tier); }
    public void setActive(boolean active) { this.isActive.set(active); }
    public void setNotes(String notes) { this.notes.set(notes); }

    /**
     * Get discount percentage based on membership tier
     */
    public BigDecimal getTierDiscount() {
        String tier = membershipTier.get();
        if (tier == null) return BigDecimal.ZERO;
        
        return switch (tier.toUpperCase()) {
            case "GOLD" -> new BigDecimal("0.10"); // 10%
            case "SILVER" -> new BigDecimal("0.05"); // 5%
            case "BRONZE" -> new BigDecimal("0.02"); // 2%
            default -> BigDecimal.ZERO;
        };
    }

    /**
     * Get tier display name with emoji
     */
    public String getTierDisplayName() {
        String tier = membershipTier.get();
        if (tier == null) return "Standard";
        
        return switch (tier.toUpperCase()) {
            case "GOLD" -> "🥇 Gold";
            case "SILVER" -> "🥈 Silver";
            case "BRONZE" -> "🥉 Bronze";
            default -> "Standard";
        };
    }
}

