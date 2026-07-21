package com.pos.api.dto;

import java.util.List;

/**
 * Request to sync products from POS to backend
 */
public class BatchProductSyncRequest {
    public List<ProductSyncToBackend> products;
    public String deviceId;
    public String syncTimestamp;
    
    public BatchProductSyncRequest(List<ProductSyncToBackend> products, String deviceId, String syncTimestamp) {
        this.products = products;
        this.deviceId = deviceId;
        this.syncTimestamp = syncTimestamp;
    }
    
    /**
     * Product data to sync to backend
     */
    public static class ProductSyncToBackend {
        public String id;
        public String name;
        public String sku;
        public String barcode;
        public Double price;
        public Double listPrice;
        public Integer stockQuantity;
        public String status;
        public String departmentId;
        public String updatedAt;
        
        public ProductSyncToBackend() {}
        
        public ProductSyncToBackend(String id, String name, String sku, String barcode,
                                    Double price, Double listPrice, Integer stockQuantity,
                                    String status, String departmentId, String updatedAt) {
            this.id = id;
            this.name = name;
            this.sku = sku;
            this.barcode = barcode;
            this.price = price;
            this.listPrice = listPrice;
            this.stockQuantity = stockQuantity;
            this.status = status;
            this.departmentId = departmentId;
            this.updatedAt = updatedAt;
        }
    }
}

