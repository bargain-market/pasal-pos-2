package com.pos.api.dto;

/**
 * Sale submission response DTO
 */
public class SaleResponse {
    public SaleInfo sale;
    public Boolean stockUpdated;
    public String message;
    
    public static class SaleInfo {
        public String id;
        public String saleId;
        public Double total;
        public String createdAt;
    }
}

