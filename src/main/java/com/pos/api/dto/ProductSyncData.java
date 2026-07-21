package com.pos.api.dto;

/**
 * Product sync data DTO from backend
 */
public class ProductSyncData {
    public String id;
    public String name;
    public String sku;
    public String barcode;
    public String description;
    public Double price;
    public Double cost;
    public Double listPrice;
    public Integer stockQuantity;
    public Integer reorderLevel;
    public String imageUrl;
    public String status; // IN_STOCK, LOW_STOCK, OUT_OF_STOCK
    public Boolean ageRestricted;
    public Integer minimumAge;
    public Boolean soldByWeight;
    public String unitOfMeasure;
    public Double tareWeight;
    public String size;
    public Integer packSize;
    public Boolean taxable;
    public Boolean ebtEligible;
    public String brand;
    public String departmentId;
    public String departmentName;
    public String supplierId;
    public String supplierName;
    public String updatedAt;
    public Long syncVersion;
}

