package com.pos.api.dto;

import java.util.List;

/**
 * Login response DTO
 */
public class LoginResponse {
    public UserInfo user;
    public List<StoreInfo> stores;
    public List<String> permissions;
    public TokenInfo tokens;
}

