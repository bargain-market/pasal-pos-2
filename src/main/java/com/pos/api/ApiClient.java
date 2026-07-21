package com.pos.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.pos.api.dto.BatchStockUpdateRequest;
import com.pos.api.dto.BatchStockUpdateResponse;
import com.pos.api.dto.StockAdjustmentRequest;
import com.pos.api.dto.StockReconciliationRequest;
import com.pos.api.dto.StockUpdateResponse;
import com.pos.config.ConfigManager;
import okhttp3.*;
import okhttp3.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client for backend API communication
 */
public class ApiClient {
    private static final Logger logger = LoggerFactory.getLogger(ApiClient.class);
    private static ApiClient instance;
    
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final ConfigManager config;
    private String apiKey;
    private String apiSecret;
    private String baseUrl;
    private String userToken;
    
    private ApiClient() {
        this.config = ConfigManager.getInstance();
        this.gson = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .create();
        
        int connectTimeout = Integer.parseInt(
            config.getProperty("backend.api.timeout.connect", "30000")
        );
        int readTimeout = Integer.parseInt(
            config.getProperty("backend.api.timeout.read", "60000")
        );
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .build();
        
        this.baseUrl = normalizeBaseUrl(config.getProperty("backend.api.url", "http://localhost:3000/api/v1"));
        this.apiKey = config.getProperty("api.key", "");
        this.apiSecret = config.getProperty("api.secret", "");
        this.userToken = config.getProperty("user.token", "");
    }
    
    public static synchronized ApiClient getInstance() {
        if (instance == null) {
            instance = new ApiClient();
        }
        return instance;
    }
    
    /**
     * Set API credentials
     */
    public void setCredentials(String apiKey, String apiSecret) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        // Update config
        config.setProperty("api.key", apiKey);
        config.setProperty("api.secret", apiSecret);
    }
    
    /**
     * Set base URL
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        config.setProperty("backend.api.url", this.baseUrl);
    }
    
    /**
     * Get base URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }
    
    /**
     * Check if device is registered
     */
    public boolean isRegistered() {
        return apiKey != null && !apiKey.isEmpty() && 
               apiSecret != null && !apiSecret.isEmpty();
    }
    
    /**
     * Set user JWT token for user-authenticated requests
     */
    public void setUserToken(String token) {
        this.userToken = token;
        if (token != null && !token.isEmpty()) {
            config.setProperty("user.token", token);
        } else {
            config.setProperty("user.token", "");
        }
    }
    
    /**
     * Get user token
     */
    public String getUserToken() {
        return userToken;
    }
    
    /**
     * Check if user is logged in
     */
    public boolean isUserLoggedIn() {
        return userToken != null && !userToken.isEmpty();
    }
    
    /**
     * Make POST request without authentication (for public endpoints like login)
     */
    public <T> ApiResponse<T> postPublic(String endpoint, Object body, Class<T> responseType) throws ApiException {
        String url = resolveRequestUrl(endpoint);
        Request.Builder builder = new Request.Builder().url(url);
        
        // Add headers (no authentication)
        builder.addHeader("Content-Type", "application/json");
        builder.addHeader("Accept", "application/json");
        
        // Add body
        if (body != null) {
            String jsonBody = gson.toJson(body);
            builder.method("POST", RequestBody.create(jsonBody, MediaType.get("application/json")));
        } else {
            builder.method("POST", null);
        }
        
        Request request = builder.build();
        return executeRequest(request, responseType);
    }
    
    /**
     * Make GET request
     */
    public <T> ApiResponse<T> get(String endpoint, Class<T> responseType) throws ApiException {
        Request request = buildRequest(endpoint, "GET", null);
        return executeRequest(request, responseType);
    }

    /**
     * Make GET request with an explicit call timeout override.
     */
    public <T> ApiResponse<T> get(String endpoint, Class<T> responseType, int callTimeoutMs) throws ApiException {
        Request request = buildRequest(endpoint, "GET", null);
        return executeRequest(request, responseType, resolveHttpClient(callTimeoutMs));
    }
    
    /**
     * Make POST request
     */
    public <T> ApiResponse<T> post(String endpoint, Object body, Class<T> responseType) throws ApiException {
        Request request = buildRequest(endpoint, "POST", body);
        return executeRequest(request, responseType);
    }
    
    /**
     * Make PUT request
     */
    public <T> ApiResponse<T> put(String endpoint, Object body, Class<T> responseType) throws ApiException {
        Request request = buildRequest(endpoint, "PUT", body);
        return executeRequest(request, responseType);
    }
    
    /**
     * Make PATCH request
     */
    public <T> ApiResponse<T> patch(String endpoint, Object body, Class<T> responseType) throws ApiException {
        Request request = buildRequest(endpoint, "PATCH", body);
        return executeRequest(request, responseType);
    }
    
    /**
     * Build request with authentication
     */
    private Request buildRequest(String endpoint, String method, Object body) throws ApiException {
        String url = resolveRequestUrl(endpoint);
        Request.Builder builder = new Request.Builder().url(url);
        
        // Add headers
        builder.addHeader("Content-Type", "application/json");
        builder.addHeader("Accept", "application/json");
        
        // Check if this is a POS endpoint (starts with /pos/)
        boolean isPosEndpoint = endpoint != null && endpoint.startsWith("/pos/");
        
        // For POS endpoints, use device authentication
        if (isPosEndpoint && isRegistered()) {
            // Get device ID and store ID from config
            String deviceId = config.getProperty("device.id");
            String storeId = config.getProperty("store.id");
            
            if (deviceId == null || deviceId.isEmpty()) {
                throw new ApiException("Device ID not found. Device may not be registered.");
            }
            
            if (storeId == null || storeId.isEmpty()) {
                throw new ApiException("Store ID not found. Device may not be registered.");
            }
            
            // Add POS device authentication headers as expected by backend
            builder.addHeader("Authorization", "Bearer " + apiKey);
            builder.addHeader("X-Device-Id", deviceId);
            builder.addHeader("X-Store-Id", storeId);
        } else if (!isPosEndpoint) {
            // For non-POS endpoints, use user JWT token if available
            if (userToken != null && !userToken.isEmpty()) {
                builder.addHeader("Authorization", "Bearer " + userToken);
            }
        }
        
        // Add body for POST/PUT/PATCH
        if (body != null && (method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))) {
            String jsonBody = gson.toJson(body);
            builder.method(method, RequestBody.create(jsonBody, MediaType.get("application/json")));
        } else {
            builder.method(method, null);
        }
        
        return builder.build();
    }

    /**
     * Strip trailing slashes so concatenating base URL + "/pos/..." does not produce ".../v1//pos/...".
     */
    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:3000/api/v1";
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** Full URL for a path (leading slash added if missing). */
    private String resolveRequestUrl(String endpoint) {
        String path = endpoint != null ? endpoint.trim() : "";
        if (path.isEmpty()) {
            return normalizeBaseUrl(baseUrl);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return normalizeBaseUrl(baseUrl) + path;
    }

    /**
     * Generate HMAC signature for request authentication
     */
    private String generateSignature(String method, String endpoint, Object body, String timestamp) 
            throws NoSuchAlgorithmException, InvalidKeyException {
        String payload = method + endpoint + timestamp;
        if (body != null) {
            payload += gson.toJson(body);
        }
        
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(
            apiSecret.getBytes(StandardCharsets.UTF_8), 
            "HmacSHA256"
        );
        mac.init(secretKeySpec);
        byte[] signatureBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signatureBytes);
    }
    
    /**
     * Execute request and parse response
     */
    private <T> ApiResponse<T> executeRequest(Request request, Class<T> responseType) throws ApiException {
        return executeRequest(request, responseType, httpClient);
    }

    private OkHttpClient resolveHttpClient(int callTimeoutMs) {
        if (callTimeoutMs <= 0) {
            return httpClient;
        }

        return httpClient.newBuilder()
                .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    private boolean isExpectedOfflineIOException(IOException e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return false;
        }

        String normalized = message.toLowerCase();
        return normalized.contains("connection refused")
                || normalized.contains("failed to connect")
                || normalized.contains("network is unreachable")
                || normalized.contains("no route to host")
                || normalized.contains("getsockopt")
                || normalized.contains("unreachable")
                || normalized.contains("unknown host");
    }

    private <T> ApiResponse<T> executeRequest(Request request, Class<T> responseType, OkHttpClient client)
            throws ApiException {
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (body == null) {
                throw new ApiException("Empty response body");
            }
            
            String responseBody = body.string();
            
            if (!response.isSuccessful()) {
                // Try to parse error response
                try {
                    ApiResponseWrapper errorWrapper = gson.fromJson(responseBody, ApiResponseWrapper.class);
                    if (errorWrapper.error != null) {
                        throw new ApiException(
                            errorWrapper.error.message != null ? errorWrapper.error.message : "API Error",
                            response.code(),
                            errorWrapper.error.code
                        );
                    } else {
                        throw new ApiException(
                            "API Error: " + responseBody,
                            response.code()
                        );
                    }
                } catch (ApiException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ApiException(
                        "API Error: " + responseBody,
                        response.code()
                    );
                }
            }
            
            // Parse successful response
            ApiResponseWrapper wrapper = gson.fromJson(responseBody, ApiResponseWrapper.class);
            if (wrapper.success && wrapper.data != null) {
                T data = gson.fromJson(gson.toJson(wrapper.data), responseType);
                return new ApiResponse<>(data, response.code());
            } else {
                throw new ApiException("API returned unsuccessful response");
            }
        } catch (IOException e) {
            if (isExpectedOfflineIOException(e)) {
                logger.debug("Backend connection failed (offline/unreachable): {}", e.getMessage());
            } else {
                logger.error("Network error", e);
            }
            throw new ApiException("Network error: " + e.getMessage(), e);
        }
    }
    
    /**
     * Response wrapper classes
     */
    private static class ApiResponseWrapper {
        boolean success;
        Object data;
        ApiErrorResponse error;
    }
    
    private static class ApiErrorResponse {
        String code;
        String message;
    }
    
    /**
     * API Response wrapper
     */
    public static class ApiResponse<T> {
        private final T data;
        private final int statusCode;
        
        public ApiResponse(T data, int statusCode) {
            this.data = data;
            this.statusCode = statusCode;
        }
        
        public T getData() {
            return data;
        }
        
        public int getStatusCode() {
            return statusCode;
        }
    }
    
    /**
     * API Exception
     */
    /**
     * Submit stock adjustment to backend
     */
    public ApiResponse<StockUpdateResponse> submitStockAdjustment(StockAdjustmentRequest request) throws ApiException {
        return post("/pos/inventory/adjustments", request, StockUpdateResponse.class);
    }
    
    /**
     * Submit stock reconciliation to backend
     */
    public ApiResponse<StockUpdateResponse> submitStockReconciliation(StockReconciliationRequest request) throws ApiException {
        return post("/pos/inventory/reconciliations", request, StockUpdateResponse.class);
    }
    
    /**
     * Submit batch stock updates to backend
     */
    public ApiResponse<BatchStockUpdateResponse> submitBatchStockUpdates(BatchStockUpdateRequest request) throws ApiException {
        return post("/pos/inventory/batch-updates", request, BatchStockUpdateResponse.class);
    }
    
    public static class ApiException extends Exception {
        private final int statusCode;
        private final String errorCode;
        
        public ApiException(String message) {
            super(message);
            this.statusCode = 0;
            this.errorCode = null;
        }
        
        public ApiException(String message, Throwable cause) {
            super(message, cause);
            this.statusCode = 0;
            this.errorCode = null;
        }
        
        public ApiException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
            this.errorCode = null;
        }
        
        public ApiException(String message, int statusCode, String errorCode) {
            super(message);
            this.statusCode = statusCode;
            this.errorCode = errorCode;
        }
        
        public int getStatusCode() {
            return statusCode;
        }
        
        public String getErrorCode() {
            return errorCode;
        }
    }
}

