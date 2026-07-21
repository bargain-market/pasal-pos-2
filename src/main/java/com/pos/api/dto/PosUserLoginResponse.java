package com.pos.api.dto;

/**
 * POS User login response DTO
 */
public class PosUserLoginResponse {
    public PosUserInfo user;
    public String token;
    public String expiresAt;
    
    public static class PosUserInfo {
        public String id;
        public String username;
        public String fullName;
        public String storeId;
        public String role; // Admin, Manager, or Cashier
    }
}

