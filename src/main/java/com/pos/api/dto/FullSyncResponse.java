package com.pos.api.dto;

import java.util.List;

/**
 * Full sync response DTO
 */
public class FullSyncResponse {
    public List<ProductSyncData> products;
    public List<DepartmentSyncData> departments;
    public String syncTimestamp;
    public Integer totalProducts;
    public Integer totalDepartments;

    /**
     * Department sync data matching backend schema.
     * Fields match the backend's DepartmentSyncData interface in pos.types.ts
     */
    public static class DepartmentSyncData {
        public String id;
        public String name;
        public String icon;
        public String parentId;

        // Department features - matching backend schema
        public String departmentType; // PRODUCT or SERVICE
        public Boolean taxEnabled; // Whether tax is enabled for this department
        public Double taxRate; // Tax rate percentage (e.g., 8.00 for 8%)
        public Boolean hideOnRegister; // Hide department on POS register
        public Boolean ebtEligible; // EBT/SNAP/Food Stamp eligible
        public Boolean excludeFromGlobalPriceIncrease; // Exclude from global price increases
        public Boolean noPointsEarning; // No loyalty points earning
        public Integer ageVerification; // Minimum age required (e.g., 21 for alcohol)

        // Multipack settings
        public Boolean multipackEnabled; // Enable multipack discount
        public String multipackDiscountType; // PERCENTAGE or FIXED_AMOUNT
        public Double multipackDiscountValue; // Discount value
        public Integer multipackMinQuantity; // Minimum quantity for discount
        public Boolean multipackRequiresApproval; // Manager approval required

        // Hierarchy
        public List<DepartmentSyncData> children;
        public Integer productCount;

        public String updatedAt;
    }
}
