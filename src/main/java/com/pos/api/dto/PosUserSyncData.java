package com.pos.api.dto;

import java.util.List;

/**
 * POS User sync data DTO (from backend)
 */
public class PosUserSyncData {
    public String id;
    public String username;
    public String fullName;
    public String storeId;
    public String storeName;
    public String role; // Role: "Cashier", "Manager", or "Admin"
    public List<String> permissions; // Permissions based on role (from backend)
    public boolean isActive;
    public String lastLoginAt;
    public String createdAt;
}

