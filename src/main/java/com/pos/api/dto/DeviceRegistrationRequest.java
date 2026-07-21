package com.pos.api.dto;

/**
 * Device registration request DTO
 */
public class DeviceRegistrationRequest {
    public String deviceId;
    public String deviceName;
    public String storeId;
    public String registerNumber;
    public String locationInfo;
    public HardwareInfo hardwareInfo;
    
    public DeviceRegistrationRequest() {}
    
    public DeviceRegistrationRequest(String deviceId, String deviceName, String storeId, HardwareInfo hardwareInfo) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.storeId = storeId;
        this.hardwareInfo = hardwareInfo;
    }
    
    public static class HardwareInfo {
        public String model;
        public String manufacturer;
        public String serialNumber;
        public String androidVersion;
        public String appVersion;
        public String buildNumber;
        public String screenResolution;
        public Boolean hasBarcodeScan;
        public Boolean hasPrinter;
        public Boolean hasCardReader;
        
        public HardwareInfo() {}
    }
}

