package com.pos.service;

import com.pos.api.ApiClient;
import com.pos.api.dto.DeviceRegistrationRequest;
import com.pos.api.dto.DeviceRegistrationResponse;
import com.pos.config.ConfigManager;
import com.pos.hardware.HardwareManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Service for device registration with backend
 */
public class DeviceRegistrationService {
    private static final Logger logger = LoggerFactory.getLogger(DeviceRegistrationService.class);
    private static DeviceRegistrationService instance;

    private final ApiClient apiClient;
    private final ConfigManager config;
    private final HardwareManager hardwareManager;

    private DeviceRegistrationService() {
        this.apiClient = ApiClient.getInstance();
        this.config = ConfigManager.getInstance();
        this.hardwareManager = HardwareManager.getInstance();
    }

    public static synchronized DeviceRegistrationService getInstance() {
        if (instance == null) {
            instance = new DeviceRegistrationService();
        }
        return instance;
    }

    /**
     * Check if device is already registered
     */
    public boolean isDeviceRegistered() {
        return apiClient.isRegistered();
    }

    /**
     * Register device with backend
     */
    public DeviceRegistrationResponse registerDevice(
            String deviceName,
            String storeId,
            String registerNumber,
            String locationInfo) throws ApiClient.ApiException {
        // Check if this is a store change (re-registration to a different store)
        String previousStoreId = config.getProperty("store.id");
        boolean isStoreChange = previousStoreId != null && !previousStoreId.isEmpty()
                && !previousStoreId.equals(storeId);

        if (isStoreChange) {
            logger.info("Detected store change from {} to {}, clearing local store data", previousStoreId, storeId);
            clearStoreDataForReregistration();
        } else if (previousStoreId == null || previousStoreId.isEmpty()) {
            // Fresh registration - also clear any stale data from previous installations
            logger.info("Fresh device registration to store {}, clearing any stale local data", storeId);
            clearStoreDataForReregistration();
        }

        // Generate or get device ID
        String deviceId = getOrCreateDeviceId();

        // Collect hardware information
        DeviceRegistrationRequest.HardwareInfo hardwareInfo = collectHardwareInfo();

        // Create registration request
        DeviceRegistrationRequest request = new DeviceRegistrationRequest(
                deviceId,
                deviceName,
                storeId,
                hardwareInfo);
        request.registerNumber = registerNumber;
        request.locationInfo = locationInfo;

        // Call registration API
        ApiClient.ApiResponse<DeviceRegistrationResponse> response = apiClient.post(
                "/pos/devices/register",
                request,
                DeviceRegistrationResponse.class);

        DeviceRegistrationResponse registrationResponse = response.getData();

        // Store credentials
        if (registrationResponse.device != null) {
            apiClient.setCredentials(
                    registrationResponse.device.apiKey,
                    registrationResponse.device.apiSecret);

            // Store device info
            config.setProperty("device.id", deviceId);
            config.setProperty("store.id", storeId);
            config.setProperty("device.name", deviceName);
            if (registerNumber != null) {
                config.setProperty("register.number", registerNumber);
            }

            logger.info("Device registered successfully: {}", deviceId);
            try {
                hardwareManager.reinitialize();
            } catch (Exception e) {
                logger.warn("Could not reinitialize hardware after device registration: {}", e.getMessage());
            }
        }

        return registrationResponse;
    }

    /**
     * Clear store-specific data when registering to a new store.
     * This ensures a clean slate for the new store's data.
     */
    private void clearStoreDataForReregistration() {
        try {
            com.pos.database.DatabaseManager.getInstance().clearStoreData();
            logger.info("Store data cleared for re-registration");
        } catch (Exception e) {
            logger.error("Error clearing store data for re-registration", e);
            // Continue with registration even if clearing fails
        }
    }

    /**
     * Get or create device ID
     */
    private String getOrCreateDeviceId() {
        String deviceId = config.getProperty("device.id");
        if (deviceId == null || deviceId.isEmpty()) {
            // Generate new device ID (could use MAC address, serial number, or UUID)
            deviceId = generateDeviceId();
            config.setProperty("device.id", deviceId);
        }
        return deviceId;
    }

    /**
     * Generate unique device ID
     * In production, this should use hardware serial number or MAC address
     */
    private String generateDeviceId() {
        // Try to get MAC address or use UUID as fallback
        try {
            java.net.NetworkInterface network = java.net.NetworkInterface.getNetworkInterfaces().nextElement();
            byte[] mac = network.getHardwareAddress();
            if (mac != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < mac.length; i++) {
                    sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? "-" : ""));
                }
                return "POS-" + sb.toString();
            }
        } catch (Exception e) {
            logger.warn("Could not get MAC address, using UUID", e);
        }

        return "POS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * Collect hardware information
     */
    private DeviceRegistrationRequest.HardwareInfo collectHardwareInfo() {
        DeviceRegistrationRequest.HardwareInfo info = new DeviceRegistrationRequest.HardwareInfo();

        // Get system properties
        info.model = System.getProperty("os.name", "Unknown");
        info.manufacturer = System.getProperty("java.vendor", "Unknown");
        info.serialNumber = config.getProperty("device.id", "UNKNOWN");

        // Get JavaFX screen resolution if available
        try {
            javafx.stage.Screen screen = javafx.stage.Screen.getPrimary();
            if (screen != null) {
                javafx.geometry.Rectangle2D bounds = screen.getBounds();
                info.screenResolution = String.format("%dx%d",
                        (int) bounds.getWidth(), (int) bounds.getHeight());
            }
        } catch (Exception e) {
            logger.debug("Could not get screen resolution", e);
        }

        // Check hardware capabilities
        info.hasBarcodeScan = hardwareManager.hasScanner();
        info.hasPrinter = hardwareManager.hasPrinter();
        info.hasCardReader = false; // TODO: Implement card reader detection

        // App version
        info.appVersion = config.getProperty("app.version", "1.0.0");
        info.buildNumber = "1";

        return info;
    }

    /**
     * Get device info from backend
     */
    public DeviceRegistrationResponse.DeviceInfo getDeviceInfo() throws ApiClient.ApiException {
        return getDeviceInfo(0);
    }

    /**
     * Get device info from backend with an explicit call timeout override.
     */
    public DeviceRegistrationResponse.DeviceInfo getDeviceInfo(int timeoutMs) throws ApiClient.ApiException {
        ApiClient.ApiResponse<DeviceRegistrationResponse.DeviceInfo> response = apiClient.get(
                "/pos/devices/me",
                DeviceRegistrationResponse.DeviceInfo.class,
                timeoutMs);
        return response.getData();
    }

    /**
     * Result of device registration verification
     */
    public enum VerificationResult {
        VERIFIED, // Successfully verified with backend
        INVALID_CREDENTIALS, // Credentials rejected by backend (401/403)
        NETWORK_ERROR, // Could not reach backend (assume valid if local creds exist)
        NOT_REGISTERED // No local credentials
    }

    /**
     * Verify device registration with backend
     * Calls /pos/devices/me endpoint to validate that credentials are valid and
     * device is registered
     * 
     * @return VerificationResult indicating the status
     */
    public VerificationResult verifyDeviceRegistrationWithBackend() {
        return verifyDeviceRegistrationWithBackend(0);
    }

    /**
     * Verify device registration with backend using a bounded call timeout.
     */
    public VerificationResult verifyDeviceRegistrationWithBackend(int timeoutMs) {
        // First check if credentials exist locally
        if (!isDeviceRegistered()) {
            logger.debug("Device not registered locally, skipping backend verification");
            return VerificationResult.NOT_REGISTERED;
        }

        try {
            // Attempt to call the device info endpoint
            // This will fail if credentials are invalid, device is not registered, or
            // backend is unreachable
            getDeviceInfo(timeoutMs);
            logger.info("Device registration verified with backend");
            return VerificationResult.VERIFIED;
        } catch (ApiClient.ApiException e) {
            int statusCode = e.getStatusCode();
            if (statusCode == 401 || statusCode == 403) {
                // Invalid credentials or unauthorized
                logger.warn("Device registration verification failed: Invalid credentials (status: {})", statusCode);
                return VerificationResult.INVALID_CREDENTIALS;
            } else if (statusCode == 404) {
                // Device not found on backend (deleted?)
                logger.warn("Device registration verification failed: Device not found on backend");
                // Treat 404 as invalid credentials/registration
                return VerificationResult.INVALID_CREDENTIALS;
            } else if (statusCode >= 500) {
                // Server errors
                logger.warn("Device registration verification failed: Server error {} - {}", statusCode,
                        e.getMessage());
                return VerificationResult.NETWORK_ERROR;
            }

            // Other HTTP errors? Assume network/temporary issue if not explicitly auth
            // failure
            logger.warn("Device registration verification failed with HTTP error: {}", statusCode);
            return VerificationResult.NETWORK_ERROR;
        } catch (Exception e) {
            // Network errors or other exceptions
            logger.warn("Device registration verification failed due to network/exception: {}", e.getMessage());
            // This is the key change: Connection failures allow us to proceed offline
            return VerificationResult.NETWORK_ERROR;
        }
    }

    /**
     * Clear device registration credentials
     * Used when registration validation fails
     */
    public void clearRegistrationCredentials() {
        logger.info("Clearing device registration credentials");

        // Clear credentials in ApiClient
        apiClient.setCredentials("", "");

        // Clear all registration-related properties
        config.setProperty("api.key", "");
        config.setProperty("api.secret", "");
        config.setProperty("device.id", "");
        config.setProperty("store.id", "");
        config.setProperty("device.name", "");
        config.setProperty("register.number", "");
        config.setProperty("store.name", "");

        // Clear user token as well (since device registration is invalid)
        apiClient.setUserToken("");
        config.setProperty("user.token", "");

        // Also clear store-specific data from the database
        // This ensures a clean slate when re-registering
        try {
            com.pos.database.DatabaseManager.getInstance().clearStoreData();
            logger.info("Store data cleared along with registration credentials");
        } catch (Exception e) {
            logger.error("Error clearing store data during credential clear", e);
            // Continue even if this fails
        }

        logger.info("Device registration credentials cleared");
    }
}
