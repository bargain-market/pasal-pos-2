package com.pos.api.dto;

import java.util.List;

/**
 * Request to sync departments from POS to backend
 */
public class BatchDepartmentSyncRequest {
    public List<DepartmentSyncToBackend> departments;
    public String deviceId;
    public String syncTimestamp;
    
    public BatchDepartmentSyncRequest(List<DepartmentSyncToBackend> departments, String deviceId, String syncTimestamp) {
        this.departments = departments;
        this.deviceId = deviceId;
        this.syncTimestamp = syncTimestamp;
    }
    
    /**
     * Department data to sync to backend
     */
    public static class DepartmentSyncToBackend {
        public String id;
        public String name;
        public String icon;
        public String parentId;
        public String departmentType;
        public Boolean taxEnabled;
        public Double taxRate;
        public Boolean hideOnRegister;
        public Boolean ebtEligible;
        public Boolean excludeFromGlobalPriceIncrease;
        public Boolean noPointsEarning;
        public Integer ageVerification;
        public Boolean multipackEnabled;
        public String multipackDiscountType;
        public Double multipackDiscountValue;
        public Integer multipackMinQuantity;
        public Boolean multipackRequiresApproval;
        public String updatedAt;
        
        public DepartmentSyncToBackend() {}
        
        public DepartmentSyncToBackend(String id, String name, String icon, String parentId,
                                       String departmentType, Boolean taxEnabled, Double taxRate,
                                       Boolean hideOnRegister, Boolean ebtEligible,
                                       Boolean excludeFromGlobalPriceIncrease, Boolean noPointsEarning,
                                       Integer ageVerification, Boolean multipackEnabled,
                                       String multipackDiscountType, Double multipackDiscountValue,
                                       Integer multipackMinQuantity, Boolean multipackRequiresApproval,
                                       String updatedAt) {
            this.id = id;
            this.name = name;
            this.icon = icon;
            this.parentId = parentId;
            this.departmentType = departmentType;
            this.taxEnabled = taxEnabled;
            this.taxRate = taxRate;
            this.hideOnRegister = hideOnRegister;
            this.ebtEligible = ebtEligible;
            this.excludeFromGlobalPriceIncrease = excludeFromGlobalPriceIncrease;
            this.noPointsEarning = noPointsEarning;
            this.ageVerification = ageVerification;
            this.multipackEnabled = multipackEnabled;
            this.multipackDiscountType = multipackDiscountType;
            this.multipackDiscountValue = multipackDiscountValue;
            this.multipackMinQuantity = multipackMinQuantity;
            this.multipackRequiresApproval = multipackRequiresApproval;
            this.updatedAt = updatedAt;
        }
    }
}

