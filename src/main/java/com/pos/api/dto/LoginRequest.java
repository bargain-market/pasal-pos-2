package com.pos.api.dto;

/**
 * Login request DTO
 */
public class LoginRequest {
    public String email;
    public String password;
    public Boolean rememberMe;
    
    public LoginRequest() {
        this.rememberMe = false;
    }
    
    public LoginRequest(String email, String password) {
        this.email = email;
        this.password = password;
        this.rememberMe = false;
    }
    
    public LoginRequest(String email, String password, Boolean rememberMe) {
        this.email = email;
        this.password = password;
        this.rememberMe = rememberMe != null ? rememberMe : false;
    }
}

