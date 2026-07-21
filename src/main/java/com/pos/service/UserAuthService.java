package com.pos.service;

import com.pos.api.ApiClient;
import com.pos.api.dto.LoginRequest;
import com.pos.api.dto.LoginResponse;
import com.pos.api.dto.StoreInfo;
import com.pos.api.dto.UserInfo;
import com.pos.api.dto.PosUserLoginRequest;
import com.pos.api.dto.PosUserLoginResponse;
import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.util.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Service for user authentication and session management
 */
public class UserAuthService {
    private static final Logger logger = LoggerFactory.getLogger(UserAuthService.class);
    private static UserAuthService instance;

    private final ApiClient apiClient;
    private final ConfigManager config;
    private final DatabaseManager dbManager;
    private UserInfo currentUser;
    private PosUserLoginResponse.PosUserInfo currentPosUser; // POS user info
    private String accessToken;
    private String refreshToken;
    private Long tokenExpiresAt; // Unix timestamp in seconds
    private boolean isPosUser = false; // Track if current user is POS user

    private UserAuthService() {
        this.apiClient = ApiClient.getInstance();
        this.config = ConfigManager.getInstance();
        this.dbManager = DatabaseManager.getInstance();
        loadSession();
    }

    public static synchronized UserAuthService getInstance() {
        if (instance == null) {
            instance = new UserAuthService();
        }
        return instance;
    }

    /**
     * Login POS user with username and PIN (offline-first capable)
     * This method supports offline login by checking local database first
     */
    public void loginPosUser(String username, String pin) throws ApiClient.ApiException {
        logger.info("Attempting POS user login: {}", username);

        // Get device store ID
        String deviceStoreId = config.getProperty("store.id");
        if (deviceStoreId == null || deviceStoreId.isEmpty()) {
            throw new IllegalStateException("Device not registered to a store");
        }

        // First, try local database (offline-first approach)
        PosUserLoginResponse.PosUserInfo localUser = authenticatePosUserFromLocalDB(username, pin);
        if (localUser != null) {
            // Successfully authenticated from local DB
            this.currentPosUser = localUser;
            this.accessToken = "offline_token_" + Instant.now().getEpochSecond();
            this.isPosUser = true;
            this.tokenExpiresAt = Instant.now().getEpochSecond() + (14 * 60 * 60); // 14 hours

            // Clear permission cache to ensure fresh permissions are loaded
            com.pos.service.RoleBasedAccessService.getInstance().clearPermissionCache();

            // Set token in ApiClient
            apiClient.setUserToken(accessToken);

            // Save session
            savePosUserSession();

            logger.info("POS user logged in offline from local DB: {} ({})", currentPosUser.fullName,
                    currentPosUser.username);

            // Try to sync with backend in background (non-blocking)
            trySyncWithBackend(username, pin, deviceStoreId);
            return;
        }

        // Local DB authentication failed, try backend
        logger.info("Local DB authentication failed, trying backend login");
        try {
            // Create POS user login request
            PosUserLoginRequest request = new PosUserLoginRequest(username, pin, deviceStoreId);

            // Try to login via API (requires device authentication)
            ApiClient.ApiResponse<PosUserLoginResponse> response = apiClient.post(
                    "/pos/users/login",
                    request,
                    PosUserLoginResponse.class);

            PosUserLoginResponse loginResponse = response.getData();

            // Store POS user session
            this.currentPosUser = loginResponse.user;
            this.accessToken = loginResponse.token;
            this.isPosUser = true;

            // Parse expiration time
            if (loginResponse.expiresAt != null && !loginResponse.expiresAt.isEmpty()) {
                try {
                    ZonedDateTime expiresAt = ZonedDateTime.parse(
                            loginResponse.expiresAt,
                            DateTimeFormatter.ISO_DATE_TIME);
                    this.tokenExpiresAt = expiresAt.toEpochSecond();
                } catch (Exception e) {
                    logger.warn("Failed to parse expiration time, using default 18 hours", e);
                    this.tokenExpiresAt = Instant.now().getEpochSecond() + (18 * 60 * 60); // 18 hours
                }
            } else {
                this.tokenExpiresAt = Instant.now().getEpochSecond() + (18 * 60 * 60); // Default 18 hours
            }

            // Save credentials to local database for offline login
            savePosUserToLocalDB(loginResponse.user, pin);

            // Clear permission cache to ensure fresh permissions are loaded
            com.pos.service.RoleBasedAccessService.getInstance().clearPermissionCache();

            // Store session
            savePosUserSession();

            // Set token in ApiClient
            apiClient.setUserToken(accessToken);

            logger.info("POS user logged in successfully via backend: {} ({})", currentPosUser.fullName,
                    currentPosUser.username);
        } catch (ApiClient.ApiException e) {
            // Backend login failed - if it's a network error, try offline again
            if (e.getStatusCode() >= 500 || e.getMessage().contains("network") || e.getMessage().contains("timeout")) {
                logger.warn("Backend login failed, attempting offline login again: {}", e.getMessage());
                if (tryOfflinePosUserLogin(username, pin)) {
                    logger.info("POS user logged in offline: {}", username);
                    return;
                }
            }
            throw e;
        }
    }

    /**
     * Try to sync with backend in background (non-blocking)
     * Updates token and syncs user data if backend is available
     */
    private void trySyncWithBackend(String username, String pin, String deviceStoreId) {
        // Run in background thread to avoid blocking
        Thread backendSyncThread = new Thread(() -> {
            try {
                PosUserLoginRequest request = new PosUserLoginRequest(username, pin, deviceStoreId);
                ApiClient.ApiResponse<PosUserLoginResponse> response = apiClient.post(
                        "/pos/users/login",
                        request,
                        PosUserLoginResponse.class);

                PosUserLoginResponse loginResponse = response.getData();

                // Update session with real token
                this.accessToken = loginResponse.token;
                this.isPosUser = true;

                // Parse expiration time
                if (loginResponse.expiresAt != null && !loginResponse.expiresAt.isEmpty()) {
                    try {
                        ZonedDateTime expiresAt = ZonedDateTime.parse(
                                loginResponse.expiresAt,
                                DateTimeFormatter.ISO_DATE_TIME);
                        this.tokenExpiresAt = expiresAt.toEpochSecond();
                    } catch (Exception e) {
                        logger.warn("Failed to parse expiration time", e);
                    }
                }

                // Update PIN hash in local DB
                savePosUserToLocalDB(loginResponse.user, pin);

                // Clear permission cache to ensure fresh permissions are loaded after sync
                com.pos.service.RoleBasedAccessService.getInstance().clearPermissionCache();

                // Update session
                savePosUserSession();
                apiClient.setUserToken(accessToken);

                logger.info("Background sync successful for POS user: {}", username);
            } catch (Exception e) {
                logger.debug("Background sync failed (expected if offline): {}", e.getMessage());
            }
        }, "PosUserBackgroundSync");
        backendSyncThread.setDaemon(true);
        backendSyncThread.start();
    }

    /**
     * Try offline POS user login using local database
     */
    private boolean tryOfflinePosUserLogin(String username, String pin) {
        try {
            PosUserLoginResponse.PosUserInfo user = authenticatePosUserFromLocalDB(username, pin);
            if (user != null) {
                // Restore POS user session
                this.currentPosUser = user;
                // Use a temporary token (will be validated when syncing)
                this.accessToken = "offline_token_" + Instant.now().getEpochSecond();
                this.isPosUser = true;
                this.tokenExpiresAt = Instant.now().getEpochSecond() + (18 * 60 * 60); // 18 hours

                // Clear permission cache to ensure fresh permissions are loaded
                com.pos.service.RoleBasedAccessService.getInstance().clearPermissionCache();

                // Set token in ApiClient
                apiClient.setUserToken(accessToken);

                // Save session
                savePosUserSession();

                logger.info("POS user logged in offline: {}", username);
                return true;
            }
        } catch (Exception e) {
            logger.error("Failed offline login attempt", e);
        }

        return false;
    }

    /**
     * Authenticate POS user from local database
     * 
     * @param username The username
     * @param pin      The PIN to verify
     * @return PosUserInfo if authentication succeeds, null otherwise
     */
    private PosUserLoginResponse.PosUserInfo authenticatePosUserFromLocalDB(String username, String pin) {
        try {
            String deviceStoreId = config.getProperty("store.id");
            if (deviceStoreId == null || deviceStoreId.isEmpty()) {
                return null;
            }

            String sql = "SELECT id, username, pin_hash, full_name, store_id, is_active, role " +
                    "FROM pos_users WHERE username = ? AND store_id = ?";

            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, username);
                stmt.setString(2, deviceStoreId);
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    // Check if user is active
                    boolean isActive = rs.getBoolean("is_active");
                    if (!isActive) {
                        logger.warn("POS user is inactive: {}", username);
                        return null;
                    }

                    // Get PIN hash
                    String pinHash = rs.getString("pin_hash");

                    // If PIN hash is empty, user hasn't logged in online yet
                    if (pinHash == null || pinHash.isEmpty()) {
                        logger.warn("POS user has no PIN hash cached - must login online first: {}", username);
                        return null;
                    }

                    // Verify PIN
                    if (!PasswordHasher.verifyPassword(pin, pinHash)) {
                        logger.warn("Invalid PIN for POS user: {}", username);
                        return null;
                    }

                    // Update last_login_at for successful login
                    String currentTime = Instant.now().toString();
                    String updateSql = "UPDATE pos_users SET last_login_at = ? WHERE id = ?";
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                        updateStmt.setString(1, currentTime);
                        updateStmt.setString(2, rs.getString("id"));
                        updateStmt.executeUpdate();
                        conn.commit();
                    }

                    // Create and return user info
                    PosUserLoginResponse.PosUserInfo userInfo = new PosUserLoginResponse.PosUserInfo();
                    userInfo.id = rs.getString("id");
                    userInfo.username = rs.getString("username");
                    userInfo.fullName = rs.getString("full_name");
                    userInfo.storeId = rs.getString("store_id");
                    userInfo.role = rs.getString("role"); // Retrieve role from database
                    if (userInfo.role == null || userInfo.role.isEmpty()) {
                        userInfo.role = "Cashier"; // Default to Cashier for backward compatibility
                    }

                    logger.info("POS user authenticated from local DB: {} (role: {})", username, userInfo.role);
                    return userInfo;
                }
            }
        } catch (SQLException e) {
            logger.error("Error authenticating POS user from local DB", e);
        }

        return null;
    }

    /**
     * Authenticate regular user from local database
     * 
     * @param email    The email
     * @param password The password to verify
     * @return UserInfo if authentication succeeds, null otherwise
     */
    private UserInfo authenticateUserFromLocalDB(String email, String password) {
        try {
            String deviceStoreId = config.getProperty("store.id");
            if (deviceStoreId == null || deviceStoreId.isEmpty()) {
                return null;
            }

            String sql = "SELECT id, email, password_hash, full_name, role, store_id " +
                    "FROM users WHERE email = ? AND (store_id = ? OR store_id IS NULL)";

            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, email.toLowerCase());
                stmt.setString(2, deviceStoreId);
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    // Get password hash
                    String passwordHash = rs.getString("password_hash");

                    // If password hash is empty, user hasn't logged in online yet
                    if (passwordHash == null || passwordHash.isEmpty()) {
                        logger.warn("User has no password hash cached - must login online first: {}", email);
                        return null;
                    }

                    // Verify password
                    if (!PasswordHasher.verifyPassword(password, passwordHash)) {
                        logger.warn("Invalid password for user: {}", email);
                        return null;
                    }

                    // Create and return user info
                    UserInfo userInfo = new UserInfo();
                    userInfo.id = rs.getString("id");
                    userInfo.email = rs.getString("email");
                    userInfo.fullName = rs.getString("full_name");
                    userInfo.role = rs.getString("role");

                    logger.info("User authenticated from local DB: {}", email);
                    return userInfo;
                }
            }
        } catch (SQLException e) {
            logger.error("Error authenticating user from local DB", e);
        }

        return null;
    }

    /**
     * Save POS user to local database with hashed PIN
     */
    private void savePosUserToLocalDB(PosUserLoginResponse.PosUserInfo user, String pin) {
        try {
            String pinHash = PasswordHasher.hashPassword(pin);

            // H2 uses MERGE INTO syntax for upsert operations
            String sql = "MERGE INTO pos_users " +
                    "(id, username, pin_hash, full_name, store_id, is_active, role, last_login_at, synced_at) " +
                    "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                String currentTime = Instant.now().toString();
                stmt.setString(1, user.id);
                stmt.setString(2, user.username);
                stmt.setString(3, pinHash);
                stmt.setString(4, user.fullName);
                stmt.setString(5, user.storeId);
                stmt.setBoolean(6, true); // Assume active if logging in
                stmt.setString(7, user.role != null ? user.role : "Cashier"); // Save role, default to Cashier
                stmt.setString(8, currentTime); // Update last_login_at
                stmt.setString(9, currentTime);
                stmt.executeUpdate();
                conn.commit();
            }

            // If this is the currently logged-in user, update the in-memory object
            if (isPosUser && currentPosUser != null && currentPosUser.id.equals(user.id)) {
                currentPosUser.role = user.role != null ? user.role : "Cashier";
                currentPosUser.fullName = user.fullName;
                currentPosUser.storeId = user.storeId;
                savePosUserSession();
                logger.debug("Updated in-memory POS user object for current user: {} (role: {})",
                        user.username, currentPosUser.role);
            }

            logger.info("Saved POS user to local DB: {}", user.username);
        } catch (SQLException e) {
            logger.error("Error saving POS user to local DB", e);
        }
    }

    /**
     * Save regular user to local database with hashed password
     */
    private void saveUserToLocalDB(UserInfo user, String password) {
        try {
            String passwordHash = PasswordHasher.hashPassword(password);

            // H2 uses MERGE INTO syntax for upsert operations
            String sql = "MERGE INTO users " +
                    "(id, email, password_hash, full_name, role, store_id, synced_at) " +
                    "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?)";

            // Get store ID from config (user's primary store)
            String storeId = config.getProperty("store.id");

            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, user.id);
                stmt.setString(2, user.email.toLowerCase());
                stmt.setString(3, passwordHash);
                stmt.setString(4, user.fullName);
                stmt.setString(5, user.role != null ? user.role : "");
                stmt.setString(6, storeId);
                stmt.setString(7, Instant.now().toString());
                stmt.executeUpdate();
                conn.commit();
            }

            logger.info("Saved user to local DB: {}", user.email);
        } catch (SQLException e) {
            logger.error("Error saving user to local DB", e);
        }
    }

    /**
     * Save POS user session
     */
    private void savePosUserSession() {
        if (currentPosUser != null && accessToken != null) {
            config.setProperty("pos.user.id", currentPosUser.id);
            config.setProperty("pos.user.username", currentPosUser.username);
            config.setProperty("pos.user.fullName", currentPosUser.fullName);
            config.setProperty("pos.user.storeId", currentPosUser.storeId);
            config.setProperty("pos.user.role", currentPosUser.role != null ? currentPosUser.role : "Cashier");
            config.setProperty("pos.user.token", accessToken);
            config.setProperty("pos.user.isPosUser", "true");
            if (tokenExpiresAt != null) {
                config.setProperty("pos.user.tokenExpiresAt", String.valueOf(tokenExpiresAt));
            }
        }
    }

    /**
     * Login user with email and password (offline-first capable)
     * This method supports offline login by checking local database first
     */
    public void login(String email, String password) throws ApiClient.ApiException {
        logger.info("Attempting login for user: {}", email);

        // Get device store ID
        String deviceStoreId = config.getProperty("store.id");
        if (deviceStoreId == null || deviceStoreId.isEmpty()) {
            throw new IllegalStateException("Device not registered to a store");
        }

        // First, try local database (offline-first approach)
        UserInfo localUser = authenticateUserFromLocalDB(email, password);
        if (localUser != null) {
            // Successfully authenticated from local DB
            this.currentUser = localUser;
            this.currentPosUser = null; // Clear POS user
            this.isPosUser = false;
            this.accessToken = "offline_token_" + Instant.now().getEpochSecond();
            this.refreshToken = null;
            this.tokenExpiresAt = Instant.now().getEpochSecond() + (24 * 60 * 60); // 24 hours

            // Set token in ApiClient
            apiClient.setUserToken(accessToken);

            // Save session
            saveSession();

            logger.info("User logged in offline from local DB: {} ({})", currentUser.fullName, currentUser.email);

            // Try to sync with backend in background (non-blocking)
            trySyncUserWithBackend(email, password, deviceStoreId);
            return;
        }

        // Local DB authentication failed, try backend
        logger.info("Local DB authentication failed, trying backend login");
        try {
            // Create login request
            LoginRequest request = new LoginRequest(email, password);

            // Call login API (public endpoint, no device auth needed)
            ApiClient.ApiResponse<LoginResponse> response = apiClient.postPublic(
                    "/auth/login",
                    request,
                    LoginResponse.class);

            LoginResponse loginResponse = response.getData();

            // Validate user belongs to the store
            if (!validateUserBelongsToStore(deviceStoreId, loginResponse.stores)) {
                throw new ApiClient.ApiException("User is not authorized for this store", 403, "POS_AUTH_003");
            }

            // Store session
            this.currentUser = loginResponse.user;
            this.currentPosUser = null; // Clear POS user
            this.isPosUser = false;
            this.accessToken = loginResponse.tokens.accessToken;
            this.refreshToken = loginResponse.tokens.refreshToken;

            // Set token expiration (default 24 hours, adjust based on backend)
            this.tokenExpiresAt = Instant.now().getEpochSecond() + (24 * 60 * 60);

            // Save credentials to local database for offline login
            saveUserToLocalDB(loginResponse.user, password);

            // Store in config
            saveSession();

            // Set token in ApiClient
            apiClient.setUserToken(accessToken);

            logger.info("User logged in successfully via backend: {} ({})", currentUser.fullName, currentUser.email);
        } catch (ApiClient.ApiException e) {
            // Backend login failed - if it's a network error, try offline again
            if (e.getStatusCode() >= 500 || e.getMessage().contains("network") || e.getMessage().contains("timeout")) {
                logger.warn("Backend login failed, attempting offline login again: {}", e.getMessage());
                UserInfo offlineUser = authenticateUserFromLocalDB(email, password);
                if (offlineUser != null) {
                    this.currentUser = offlineUser;
                    this.currentPosUser = null;
                    this.isPosUser = false;
                    this.accessToken = "offline_token_" + Instant.now().getEpochSecond();
                    this.refreshToken = null;
                    this.tokenExpiresAt = Instant.now().getEpochSecond() + (24 * 60 * 60);
                    apiClient.setUserToken(accessToken);
                    saveSession();
                    logger.info("User logged in offline: {}", email);
                    return;
                }
            }
            throw e;
        }
    }

    /**
     * Try to sync user with backend in background (non-blocking)
     * Updates token and syncs user data if backend is available
     */
    private void trySyncUserWithBackend(String email, String password, String deviceStoreId) {
        // Run in background thread to avoid blocking
        new Thread(() -> {
            try {
                LoginRequest request = new LoginRequest(email, password);
                ApiClient.ApiResponse<LoginResponse> response = apiClient.postPublic(
                        "/auth/login",
                        request,
                        LoginResponse.class);

                LoginResponse loginResponse = response.getData();

                // Validate user belongs to the store
                if (!validateUserBelongsToStore(deviceStoreId, loginResponse.stores)) {
                    logger.warn("User not authorized for store in background sync");
                    return;
                }

                // Update session with real token
                this.accessToken = loginResponse.tokens.accessToken;
                this.refreshToken = loginResponse.tokens.refreshToken;
                this.tokenExpiresAt = Instant.now().getEpochSecond() + (24 * 60 * 60);

                // Update password hash in local DB
                saveUserToLocalDB(loginResponse.user, password);

                // Update session
                saveSession();
                apiClient.setUserToken(accessToken);

                logger.info("Background sync successful for user: {}", email);
            } catch (Exception e) {
                logger.debug("Background sync failed (expected if offline): {}", e.getMessage());
            }
        }).start();
    }

    private final List<Runnable> logoutListeners = new java.util.ArrayList<>();

    /**
     * Add a listener to be notified when user logs out
     */
    public void addLogoutListener(Runnable listener) {
        if (listener != null) {
            logoutListeners.add(listener);
        }
    }

    /**
     * Logout current user
     */
    public void logout() {
        String userIdentifier = "unknown";
        if (isPosUser && currentPosUser != null) {
            userIdentifier = currentPosUser.username;
        } else if (currentUser != null) {
            userIdentifier = currentUser.email;
        }
        logger.info("Logging out user: {}", userIdentifier);

        // Clear session
        clearSession();

        // Clear token in ApiClient
        apiClient.setUserToken(null);

        // Clear permission cache
        com.pos.service.RoleBasedAccessService.getInstance().clearPermissionCache();

        // Notify listeners
        for (Runnable listener : logoutListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                logger.error("Error in logout listener", e);
            }
        }

        logger.info("User logged out successfully");
    }

    /**
     * Check if user is logged in (supports both regular users and POS users)
     */
    public boolean isLoggedIn() {
        // Check if either regular user or POS user is logged in
        boolean hasUser = (currentUser != null) || (isPosUser && currentPosUser != null);

        if (!hasUser || accessToken == null || accessToken.isEmpty()) {
            return false;
        }

        // Check token expiration
        if (tokenExpiresAt != null && Instant.now().getEpochSecond() >= tokenExpiresAt) {
            logger.warn("Session expired - force logout");
            logout();
            return false;
        }

        return true;
    }

    /**
     * Get current logged-in user
     */
    public UserInfo getCurrentUser() {
        return currentUser;
    }

    /**
     * Get current user ID
     */
    public String getCurrentUserId() {
        return currentUser != null ? currentUser.id : null;
    }

    /**
     * Get current user name (works for both regular users and POS users)
     */
    public String getCurrentUserName() {
        if (isPosUser && currentPosUser != null) {
            return currentPosUser.fullName;
        }
        return currentUser != null ? currentUser.fullName : null;
    }

    /**
     * Get current POS user
     */
    public PosUserLoginResponse.PosUserInfo getCurrentPosUser() {
        return currentPosUser;
    }

    /**
     * Get current POS user ID
     */
    public String getCurrentPosUserId() {
        return currentPosUser != null ? currentPosUser.id : null;
    }

    /**
     * Reload current POS user from database
     * Updates the in-memory user object with the latest data from the database
     * This is useful after sync operations to ensure the role and other fields are
     * up-to-date
     */
    public void reloadCurrentPosUserFromDB() {
        if (!isPosUser || currentPosUser == null) {
            return;
        }

        try {
            String sql = "SELECT id, username, pin_hash, full_name, store_id, is_active, role " +
                    "FROM pos_users WHERE id = ?";

            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, currentPosUser.id);
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    // Update in-memory user object with latest data from database
                    String newRole = rs.getString("role");
                    String oldRole = currentPosUser.role;

                    currentPosUser.role = newRole != null && !newRole.isEmpty() ? newRole : "Cashier";
                    currentPosUser.fullName = rs.getString("full_name");
                    currentPosUser.storeId = rs.getString("store_id");

                    // Update session to persist the new role
                    savePosUserSession();

                    if (!currentPosUser.role.equals(oldRole)) {
                        logger.info("Reloaded POS user role from DB: {} (role changed from {} to {})",
                                currentPosUser.username, oldRole, currentPosUser.role);
                    } else {
                        logger.debug("Reloaded POS user from DB: {} (role: {})",
                                currentPosUser.username, currentPosUser.role);
                    }
                } else {
                    logger.warn("POS user not found in database during reload: {}", currentPosUser.id);
                }
            }
        } catch (SQLException e) {
            logger.error("Error reloading POS user from DB", e);
        }
    }

    /**
     * Check if current user is a POS user
     */
    public boolean isPosUser() {
        return isPosUser && currentPosUser != null;
    }

    /**
     * Get current user email
     */
    public String getCurrentUserEmail() {
        return currentUser != null ? currentUser.email : null;
    }

    /**
     * Get current access token (for checking offline mode)
     */
    public String getAccessToken() {
        return accessToken;
    }

    /**
     * Check if session is valid
     */
    public boolean isSessionValid() {
        return isLoggedIn();
    }

    /**
     * Validate user belongs to store
     */
    private boolean validateUserBelongsToStore(String deviceStoreId, List<StoreInfo> userStores) {
        if (userStores == null || userStores.isEmpty()) {
            return false;
        }

        return userStores.stream()
                .anyMatch(store -> store.id != null && store.id.equals(deviceStoreId));
    }

    /**
     * Load session from config (supports both regular users and POS users)
     */
    private void loadSession() {
        try {
            // Check if POS user session exists
            String isPosUserStr = config.getProperty("pos.user.isPosUser");
            if ("true".equals(isPosUserStr)) {
                String posUserId = config.getProperty("pos.user.id");
                String posUsername = config.getProperty("pos.user.username");
                String posFullName = config.getProperty("pos.user.fullName");
                String posStoreId = config.getProperty("pos.user.storeId");
                String posRole = config.getProperty("pos.user.role");
                String storedToken = config.getProperty("pos.user.token");
                String expiresAtStr = config.getProperty("pos.user.tokenExpiresAt");

                if (posUserId != null && !posUserId.isEmpty() && storedToken != null && !storedToken.isEmpty()) {
                    this.accessToken = storedToken;
                    this.isPosUser = true;

                    if (expiresAtStr != null && !expiresAtStr.isEmpty()) {
                        try {
                            this.tokenExpiresAt = Long.parseLong(expiresAtStr);
                        } catch (NumberFormatException e) {
                            logger.warn("Invalid token expiration time", e);
                            this.tokenExpiresAt = null;
                        }
                    }

                    // Reconstruct POS user info
                    this.currentPosUser = new PosUserLoginResponse.PosUserInfo();
                    this.currentPosUser.id = posUserId;
                    this.currentPosUser.username = posUsername != null ? posUsername : "";
                    this.currentPosUser.fullName = posFullName != null ? posFullName : posUsername;
                    this.currentPosUser.storeId = posStoreId != null ? posStoreId : config.getProperty("store.id");
                    this.currentPosUser.role = posRole != null && !posRole.isEmpty() ? posRole : "Cashier";

                    // Set token in ApiClient
                    apiClient.setUserToken(accessToken);

                    logger.info("POS user session loaded: {} (role: {})", posUsername, this.currentPosUser.role);
                    return;
                }
            }

            // Try regular user session
            String userId = config.getProperty("user.id");
            String userEmail = config.getProperty("user.email");
            String userFullName = config.getProperty("user.fullName");
            String userRole = config.getProperty("user.role");
            String storedToken = config.getProperty("user.token");
            String expiresAtStr = config.getProperty("user.tokenExpiresAt");

            if (userId != null && !userId.isEmpty() && storedToken != null && !storedToken.isEmpty()) {
                this.accessToken = storedToken;
                this.refreshToken = config.getProperty("user.refreshToken", "");
                this.isPosUser = false;

                if (expiresAtStr != null && !expiresAtStr.isEmpty()) {
                    try {
                        this.tokenExpiresAt = Long.parseLong(expiresAtStr);
                    } catch (NumberFormatException e) {
                        logger.warn("Invalid token expiration time", e);
                        this.tokenExpiresAt = null;
                    }
                }

                // Reconstruct user info
                if (userEmail != null) {
                    this.currentUser = new UserInfo();
                    this.currentUser.id = userId;
                    this.currentUser.email = userEmail;
                    this.currentUser.fullName = userFullName;
                    this.currentUser.role = userRole;

                    // Set token in ApiClient
                    apiClient.setUserToken(accessToken);

                    logger.info("Session loaded for user: {}", userEmail);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load session", e);
            clearSession();
        }
    }

    /**
     * Save session to config
     */
    private void saveSession() {
        if (currentUser != null && accessToken != null) {
            config.setProperty("user.id", currentUser.id);
            config.setProperty("user.email", currentUser.email);
            config.setProperty("user.fullName", currentUser.fullName);
            config.setProperty("user.role", currentUser.role != null ? currentUser.role : "");
            config.setProperty("user.token", accessToken);
            config.setProperty("user.refreshToken", refreshToken != null ? refreshToken : "");
            if (tokenExpiresAt != null) {
                config.setProperty("user.tokenExpiresAt", String.valueOf(tokenExpiresAt));
            }
        }
    }

    /**
     * Clear session (both regular and POS user)
     */
    private void clearSession() {
        this.currentUser = null;
        this.currentPosUser = null;
        this.accessToken = null;
        this.refreshToken = null;
        this.tokenExpiresAt = null;
        this.isPosUser = false;

        // Clear regular user from config
        config.setProperty("user.id", "");
        config.setProperty("user.email", "");
        config.setProperty("user.fullName", "");
        config.setProperty("user.role", "");
        config.setProperty("user.token", "");
        config.setProperty("user.refreshToken", "");
        config.setProperty("user.tokenExpiresAt", "");

        // Clear POS user from config
        config.setProperty("pos.user.id", "");
        config.setProperty("pos.user.username", "");
        config.setProperty("pos.user.fullName", "");
        config.setProperty("pos.user.storeId", "");
        config.setProperty("pos.user.role", "");
        config.setProperty("pos.user.token", "");
        config.setProperty("pos.user.isPosUser", "");
        config.setProperty("pos.user.tokenExpiresAt", "");
        // Note: We keep cached credentials for offline login, but clear them on
        // explicit logout
        // config.setProperty("pos.user.pin", ""); // Clear PIN on logout
    }

    /**
     * Get current user's role (works for both regular users and POS users)
     * 
     * @return Role string (Admin, Manager, Cashier) or null if not logged in
     */
    public String getCurrentUserRole() {
        if (!isLoggedIn()) {
            return null;
        }

        if (isPosUser && currentPosUser != null) {
            return currentPosUser.role != null ? currentPosUser.role : "Cashier";
        } else if (currentUser != null) {
            return currentUser.role != null ? currentUser.role : "Cashier";
        }

        return "Cashier"; // Default for backward compatibility
    }

    /**
     * Get all active POS users from the local database
     */
    public List<PosUserLoginResponse.PosUserInfo> getAllPosUsers() {
        List<PosUserLoginResponse.PosUserInfo> users = new java.util.ArrayList<>();
        try {
            String deviceStoreId = config.getProperty("store.id");
            if (deviceStoreId == null || deviceStoreId.isEmpty()) {
                return users;
            }

            String sql = "SELECT id, username, full_name, store_id, role, is_active FROM pos_users WHERE store_id = ? AND is_active = TRUE ORDER BY full_name ASC";

            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, deviceStoreId);
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    PosUserLoginResponse.PosUserInfo userInfo = new PosUserLoginResponse.PosUserInfo();
                    userInfo.id = rs.getString("id");
                    userInfo.username = rs.getString("username");
                    userInfo.fullName = rs.getString("full_name");
                    userInfo.storeId = rs.getString("store_id");
                    userInfo.role = rs.getString("role");
                    users.add(userInfo);
                }
            }
        } catch (SQLException e) {
            logger.error("Error retrieving all POS users from local DB", e);
        }
        return users;
    }

    /**
     * Verify a POS user's PIN/password from the local database.
     * Used for sensitive actions performed on behalf of another user.
     */
    public boolean verifyPosUserCredentials(String userId, String passwordOrPin) {
        if (userId == null || userId.isBlank() || passwordOrPin == null || passwordOrPin.isBlank()) {
            return false;
        }

        try {
            String deviceStoreId = config.getProperty("store.id");
            if (deviceStoreId == null || deviceStoreId.isEmpty()) {
                return false;
            }

            String sql = "SELECT pin_hash FROM pos_users WHERE id = ? AND store_id = ? AND is_active = TRUE";

            try (Connection conn = dbManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, userId);
                stmt.setString(2, deviceStoreId);
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    return PasswordHasher.verifyPassword(passwordOrPin, rs.getString("pin_hash"));
                }
            }
        } catch (SQLException e) {
            logger.error("Error verifying POS user credentials for {}", userId, e);
        }

        return false;
    }

}
