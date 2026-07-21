package com.pos.api.dto;

/**
 * Device registration response DTO
 */
public class DeviceRegistrationResponse {
    public DeviceInfo device;
    public String message;
    
    public static class DeviceInfo {
        public String id;
        public String deviceId;
        public String deviceName;
        public String storeId;
        public String storeName;
        public String registerNumber;
        public String apiKey;
        public String apiSecret;
    }
}

