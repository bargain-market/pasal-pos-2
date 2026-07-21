package com.pos.service;

import com.pos.api.ApiClient;
import com.pos.api.dto.DeviceRegistrationResponse;
import com.pos.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for managing store information
 */
public class StoreService {
    private static final Logger logger = LoggerFactory.getLogger(StoreService.class);
    private static StoreService instance;
    
    private final ApiClient apiClient;
    private final ConfigManager config;
    private DeviceRegistrationResponse.DeviceInfo storeInfo;
    
    private StoreService() {
        this.apiClient = ApiClient.getInstance();
        this.config = ConfigManager.getInstance();
    }
    
    public static synchronized StoreService getInstance() {
        if (instance == null) {
            instance = new StoreService();
        }
        return instance;
    }
    
    /**
     * Fetch store information from backend
     */
    public DeviceRegistrationResponse.DeviceInfo fetchStoreInfo() throws ApiClient.ApiException {
        return fetchStoreInfo(0);
    }

    /**
     * Fetch store information from backend with an explicit call timeout override.
     */
    public DeviceRegistrationResponse.DeviceInfo fetchStoreInfo(int timeoutMs) throws ApiClient.ApiException {
        logger.info("Fetching store information from backend");
        
        DeviceRegistrationService deviceService = DeviceRegistrationService.getInstance();
        DeviceRegistrationResponse.DeviceInfo deviceInfo = deviceService.getDeviceInfo(timeoutMs);
        
        this.storeInfo = deviceInfo;
        
        // Store store ID and name if available
        if (deviceInfo.storeId != null) {
            config.setProperty("store.id", deviceInfo.storeId);
        }
        if (deviceInfo.storeName != null) {
            config.setProperty("store.name", deviceInfo.storeName);
        }
        
        logger.info("Store information loaded: {} (ID: {})", deviceInfo.storeName, deviceInfo.storeId);
        return deviceInfo;
    }
    
    /**
     * Get store name
     */
    public String getStoreName() {
        if (storeInfo != null && storeInfo.storeName != null) {
            return storeInfo.storeName;
        }
        
        // Fallback to config
        String storeName = config.getProperty("store.name");
        if (storeName != null && !storeName.isEmpty()) {
            return storeName;
        }
        
        return "Store";
    }
    
    /**
     * Get store ID
     */
    public String getStoreId() {
        return config.getProperty("store.id");
    }

    /**
     * Update store information in-memory and in config
     */
    public void updateStoreInfo(String storeId, String storeName) {
        if (storeId != null) {
            config.setProperty("store.id", storeId);
        }
        if (storeName != null) {
            config.setProperty("store.name", storeName);
        }

        if (this.storeInfo != null) {
            this.storeInfo.storeId = storeId;
            this.storeInfo.storeName = storeName;
        } else {
            this.storeInfo = new DeviceRegistrationResponse.DeviceInfo();
            this.storeInfo.storeId = storeId;
            this.storeInfo.storeName = storeName;
        }
        logger.info("Store information updated in-memory: {} (ID: {})", storeName, storeId);
    }
}

