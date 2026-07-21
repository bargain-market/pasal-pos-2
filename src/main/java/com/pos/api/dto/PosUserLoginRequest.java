package com.pos.api.dto;

/**
 * POS User login request DTO (username/PIN)
 */
public class PosUserLoginRequest {
    public String username;
    public String pin;
    public String storeId;
    
    public PosUserLoginRequest() {
    }
    
    public PosUserLoginRequest(String username, String pin, String storeId) {
        this.username = username;
        this.pin = pin;
        this.storeId = storeId;
    }
}

