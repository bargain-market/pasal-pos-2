package com.pos.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Model class representing an age verification record for compliance tracking.
 * Used when selling age-restricted products (alcohol, tobacco, etc.).
 */
public class AgeVerification {

    private String id;
    private String saleId;
    private String departmentId;
    private int requiredAge;
    private LocalDate customerDob;
    private int customerAge;
    private String idLastFour;
    private LocalDate idExpiration;
    private VerificationMethod verificationMethod;
    private String verifiedBy;
    private LocalDateTime verifiedAt;

    /**
     * Verification method enum
     */
    public enum VerificationMethod {
        SCAN,   // ID barcode was scanned (PDF417)
        MANUAL, // Cashier manually verified and entered DOB
        VISUAL  // Cashier visually verified ID - no data entry required
    }

    /**
     * Default constructor
     */
    public AgeVerification() {
        this.verifiedAt = LocalDateTime.now();
    }

    /**
     * Full constructor
     */
    public AgeVerification(String id, String saleId, String departmentId, int requiredAge,
            LocalDate customerDob, int customerAge, String idLastFour,
            LocalDate idExpiration, VerificationMethod verificationMethod,
            String verifiedBy, LocalDateTime verifiedAt) {
        this.id = id;
        this.saleId = saleId;
        this.departmentId = departmentId;
        this.requiredAge = requiredAge;
        this.customerDob = customerDob;
        this.customerAge = customerAge;
        this.idLastFour = idLastFour;
        this.idExpiration = idExpiration;
        this.verificationMethod = verificationMethod;
        this.verifiedBy = verifiedBy;
        this.verifiedAt = verifiedAt != null ? verifiedAt : LocalDateTime.now();
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSaleId() {
        return saleId;
    }

    public void setSaleId(String saleId) {
        this.saleId = saleId;
    }

    public String getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(String departmentId) {
        this.departmentId = departmentId;
    }

    public int getRequiredAge() {
        return requiredAge;
    }

    public void setRequiredAge(int requiredAge) {
        this.requiredAge = requiredAge;
    }

    public LocalDate getCustomerDob() {
        return customerDob;
    }

    public void setCustomerDob(LocalDate customerDob) {
        this.customerDob = customerDob;
    }

    public int getCustomerAge() {
        return customerAge;
    }

    public void setCustomerAge(int customerAge) {
        this.customerAge = customerAge;
    }

    public String getIdLastFour() {
        return idLastFour;
    }

    public void setIdLastFour(String idLastFour) {
        this.idLastFour = idLastFour;
    }

    public LocalDate getIdExpiration() {
        return idExpiration;
    }

    public void setIdExpiration(LocalDate idExpiration) {
        this.idExpiration = idExpiration;
    }

    public VerificationMethod getVerificationMethod() {
        return verificationMethod;
    }

    public void setVerificationMethod(VerificationMethod verificationMethod) {
        this.verificationMethod = verificationMethod;
    }

    public String getVerifiedBy() {
        return verifiedBy;
    }

    public void setVerifiedBy(String verifiedBy) {
        this.verifiedBy = verifiedBy;
    }

    public LocalDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(LocalDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    /**
     * Check if the customer meets the age requirement
     */
    public boolean meetsAgeRequirement() {
        return customerAge >= requiredAge;
    }

    /**
     * Check if the ID is expired
     */
    public boolean isIdExpired() {
        if (idExpiration == null) {
            return false;
        }
        return idExpiration.isBefore(LocalDate.now());
    }

    @Override
    public String toString() {
        return "AgeVerification{" +
                "id='" + id + '\'' +
                ", departmentId='" + departmentId + '\'' +
                ", requiredAge=" + requiredAge +
                ", customerAge=" + customerAge +
                ", verificationMethod=" + verificationMethod +
                ", verifiedBy='" + verifiedBy + '\'' +
                ", verifiedAt=" + verifiedAt +
                '}';
    }
}
