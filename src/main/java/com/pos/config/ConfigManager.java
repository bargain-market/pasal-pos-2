package com.pos.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.pos.database.DatabaseMigrationService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Properties;

/**
 * Manages application configuration
 */
public class ConfigManager {

    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);
    private static ConfigManager instance;
    private Properties properties;
    private Path configFilePath;
    private final Path dataDir;

    private ConfigManager() {
        // Determine platform-specific data directory
        String userHome = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();
        Path resolvedDataDir;

        // Pasal POS 2 is an isolated, side-by-side install: its data directory is
        // deliberately DIFFERENT from the production "Pasal POS" install so the two apps
        // never share a database or config.
        if (isTestMode()) {
            resolvedDataDir = Paths.get(System.getProperty("java.io.tmpdir"), "pasal-pos-2-test");
        } else if (os.contains("win")) {
            resolvedDataDir = Paths.get(System.getenv("APPDATA"), "Pasal POS 2");
        } else if (os.contains("mac")) {
            resolvedDataDir = Paths.get(userHome, "Library", "Application Support", "Pasal POS 2");
        } else {
            // Linux/Unix
            resolvedDataDir = Paths.get(userHome, ".pos-system-2");
        }

        // Create data directory if it doesn't exist
        try {
            if (!Files.exists(resolvedDataDir)) {
                Files.createDirectories(resolvedDataDir);
            }
        } catch (IOException e) {
            logger.error("Failed to create data directory: " + resolvedDataDir, e);
            // Fallback to current directory if we can't create the system one
            resolvedDataDir = Paths.get("data");
        }
        this.dataDir = resolvedDataDir;

        // Config file in data directory (persists across builds)
        this.configFilePath = dataDir.resolve("config.properties");

        loadConfiguration();

        // ALWAYS check for old databases to migrate (checks marker file internally to
        // avoid re-running)
        migrateOldDatabase(dataDir);

        ensureProductionDatabaseUrl(os);
    }

    /**
     * True when running under Maven Surefire / unit tests — uses an isolated temp config dir.
     */
    public static boolean isTestMode() {
        return Boolean.getBoolean("pasal.test.mode");
    }

    /**
     * Reset persisted database URL when tests or a bad save pointed at an in-memory or relative path.
     */
    private void ensureProductionDatabaseUrl(String os) {
        if (isTestMode()) {
            return;
        }

        String dbUrl = properties.getProperty("database.url", "");
        if (!isInvalidProductionDatabaseUrl(dbUrl)) {
            return;
        }

        // Pin the database to an absolute H2 file inside this app's isolated data dir.
        // A relative "./data/posdb" resolves against the process working directory, which is
        // unpredictable (and often unwritable) for an installed app. This app runs on H2, so
        // any leftover SQLite URL (from an earlier build) is also re-pinned here — that makes
        // an already-broken config self-heal to H2 on the next launch.
        String absoluteDbPath = dataDir.resolve("posdb").toAbsolutePath().toString();
        if (os.contains("win")) {
            absoluteDbPath = absoluteDbPath.replace("\\", "/");
        }

        logger.warn("Invalid, non-absolute, or non-H2 database URL detected ({}). Pinning to data dir: {}",
                dbUrl, absoluteDbPath);
        properties.setProperty("database.url", "jdbc:h2:" + absoluteDbPath);
        properties.setProperty("database.driver", "org.h2.Driver");
        saveRuntimeConfig();
    }

    private boolean isInvalidProductionDatabaseUrl(String dbUrl) {
        if (dbUrl == null || dbUrl.isBlank()) {
            return true;
        }
        String lower = dbUrl.toLowerCase();
        if (lower.contains(":mem:") || lower.contains(":memory:")) {
            return true;
        }
        // This app runs on H2 — a non-H2 URL (e.g. a leftover SQLite URL) must be re-pinned.
        if (!lower.startsWith("jdbc:h2:")) {
            return true;
        }
        // A relative "./data/posdb" path must be pinned to an absolute one in the data dir.
        return dbUrl.contains("./data/posdb");
    }

    public static ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    private void loadConfiguration() {
        properties = new Properties();

        // First, load defaults from application.properties (read-only)
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
                logger.debug("Loaded default configuration from application.properties");
            } else {
                logger.warn("application.properties not found, using defaults");
                setDefaults();
            }
        } catch (Exception e) {
            logger.error("Error loading default configuration", e);
            setDefaults();
        }

        // Then, load runtime config from data/config.properties (overrides defaults)
        if (Files.exists(configFilePath)) {
            try (InputStream input = Files.newInputStream(configFilePath)) {
                Properties runtimeProps = new Properties();
                runtimeProps.load(input);
                // Override defaults with runtime values
                properties.putAll(runtimeProps);
                logger.info("Loaded runtime configuration from {}", configFilePath);
            } catch (Exception e) {
                logger.warn("Error loading runtime configuration, using defaults only", e);
            }
        } else {
            logger.debug("Runtime configuration file not found, will be created on first save");
        }
    }

    private void setDefaults() {
        properties.setProperty("database.url", "jdbc:h2:./data/posdb");
        properties.setProperty("database.user", "sa");
        properties.setProperty("database.password", "");
        properties.setProperty("printer.logicalName", "POSPrinter");
        properties.setProperty("scanner.logicalName", "Scanner");
        properties.setProperty("cashdrawer.logicalName", "CashDrawer");
    }

    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    /**
     * Platform-specific application data directory (config, DB, sync reports).
     */
    public Path getDataDirectory() {
        return dataDir;
    }

    /**
     * User-visible folder for daily sync activity logs and failure reports:
     * Documents/Pasal POS 2/sync logs.
     */
    public Path getSyncLogsDirectory() {
        String userHome = System.getProperty("user.home");
        Path documentsSyncLogs = Paths.get(userHome, "Documents", "Pasal POS 2", "sync logs");
        try {
            Files.createDirectories(documentsSyncLogs);
            return documentsSyncLogs;
        } catch (IOException e) {
            logger.warn("Could not create Documents sync logs folder, using app data dir: {}", e.getMessage());
            Path fallback = dataDir.resolve("sync logs");
            try {
                Files.createDirectories(fallback);
            } catch (IOException ex) {
                logger.error("Could not create fallback sync logs folder", ex);
            }
            return fallback;
        }
    }

    /**
     * Same folder as {@link #getSyncLogsDirectory()} — sync failure reports live alongside daily sync logs.
     */
    public Path getSyncFailureLogsDirectory() {
        return getSyncLogsDirectory();
    }

    /**
     * Property key for the Windows print queue name saved for a specific store.
     * Store id is base64url-encoded so it is safe in .properties files.
     */
    public static String printerNameKeyForStoreId(String storeId) {
        if (storeId == null || storeId.isEmpty()) {
            return null;
        }
        String enc = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(storeId.getBytes(StandardCharsets.UTF_8));
        return "printer.name.store." + enc;
    }

    /**
     * Resolves the Windows print queue name for the current device store: uses
     * per-store value when {@code store.id} is set and a value exists, otherwise
     * {@code printer.name}. Same machine can keep different queue names per store
     * when the app is re-registered to different locations.
     */
    public String getResolvedPrinterName(String defaultValue) {
        String storeId = getProperty("store.id");
        if (storeId != null && !storeId.isEmpty()) {
            String perStoreKey = printerNameKeyForStoreId(storeId);
            if (perStoreKey != null) {
                String v = getProperty(perStoreKey);
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
        }
        return getProperty("printer.name", defaultValue);
    }

    /**
     * Persists the queue name for the current store if {@code store.id} is set;
     * otherwise updates the global {@code printer.name}.
     */
    public void setPrinterNameForCurrentStore(String queueName) {
        String storeId = getProperty("store.id");
        if (storeId != null && !storeId.isEmpty()) {
            String key = printerNameKeyForStoreId(storeId);
            if (key != null) {
                setProperty(key, queueName);
                return;
            }
        }
        setProperty("printer.name", queueName);
    }

    /**
     * Set a property value (for runtime configuration)
     * This will persist to data/config.properties
     */
    public void setProperty(String key, String value) {
        properties.setProperty(key, value);
        saveRuntimeConfig();
    }

    /**
     * Save runtime configuration to file
     */
    private void saveRuntimeConfig() {
        try {
            // Ensure data directory exists
            Path dataDir = configFilePath.getParent();
            if (dataDir != null && !Files.exists(dataDir)) {
                Files.createDirectories(dataDir);
            }

            // Properties to save (only runtime-sensitive ones)
            Properties runtimeProps = new Properties();
            String[] runtimeKeys = {
                    "device.id", "store.id", "store.name", "device.name",
                    "register.number", "api.key", "api.secret", "backend.api.url",
                    "database.url", // Persist the corrected absolute database path
                    "backup.directory", "backup.restore.pendingFile", "backup.restore.pendingRequestedAt",
                    "printer.name", "printer.logicalName", "scanner.logicalName", "cashdrawer.logicalName",
                    // Regular user session
                    "user.id", "user.email", "user.fullName", "user.role",
                    "user.token", "user.refreshToken", "user.tokenExpiresAt",
                    // POS user session
                    "pos.user.id", "pos.user.username", "pos.user.fullName",
                    "pos.user.storeId", "pos.user.role", "pos.user.token",
                    "pos.user.isPosUser", "pos.user.tokenExpiresAt",
                    "app.screen.simulation", "app.screen.autoScale",
                    "customer.display.enabled",
                    // PAX card terminal settings (configured in Hardware Settings)
                    "pax.enabled", "pax.comm.type", "pax.comm.host", "pax.comm.port",
                    "pax.comm.timeoutMs", "pax.log.enabled"
            };

            for (String key : runtimeKeys) {
                String value = properties.getProperty(key);
                if (value != null && !value.isEmpty()) {
                    runtimeProps.setProperty(key, value);
                }
            }

            for (String name : properties.stringPropertyNames()) {
                if (name.startsWith("printer.name.store.")) {
                    String value = properties.getProperty(name);
                    if (value != null && !value.isEmpty()) {
                        runtimeProps.setProperty(name, value);
                    }
                }
            }

            // Save to file
            try (OutputStream output = Files.newOutputStream(configFilePath)) {
                runtimeProps.store(output, "Pasal POS 2 Runtime Configuration\n" +
                        "This file is auto-generated. Do not edit manually.");
                logger.debug("Saved runtime configuration to {}", configFilePath);
            }
        } catch (Exception e) {
            logger.error("Failed to save runtime configuration", e);
        }
    }

    /**
     * Get integer property
     */
    public int getIntProperty(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer value for property {}: {}", key, value);
            return defaultValue;
        }
    }

    /**
     * Get boolean property
     */
    public boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * Check if the application is running in testing mode.
     * In testing mode, receipts are saved as PDF without requiring a physical
     * printer.
     * 
     * @return true if testing mode is enabled
     */
    public boolean isTestingMode() {
        return getBooleanProperty("app.testing.mode", false);
    }

    /**
     * Migrate old database from relative ./data/ path to the new user data
     * directory.
     * This handles the case where users upgrade from an older version that stored
     * the database in the installation directory.
     * 
     * @param newDataDir The new user data directory
     */
    private void migrateOldDatabase(Path newDataDir) {
        // Pasal POS 2 must NEVER import data from the production "Pasal POS" install or any
        // legacy location — it is an isolated, side-by-side app that always starts with its
        // own data. Cross-location migration is intentionally disabled here.
        boolean migrationDisabled = true;
        Path markerFile = newDataDir.resolve(".migration_complete");
        if (migrationDisabled || Files.exists(markerFile)) {
            return;
        }

        String userHome = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();

        // Build list of possible old database locations
        java.util.List<Path> oldLocations = new java.util.ArrayList<>();

        // 1. Relative paths (inside app installation directory)
        oldLocations.add(Paths.get("data", "posdb"));
        oldLocations.add(Paths.get(".", "data", "posdb"));

        // 2. Legacy app name locations (old "POS System" name)
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                oldLocations.add(Paths.get(appData, "POS System", "posdb"));
                oldLocations.add(Paths.get(appData, "pos-system", "posdb"));
            }
        } else if (os.contains("mac")) {
            oldLocations.add(Paths.get(userHome, "Library", "Application Support", "POS System", "posdb"));
            oldLocations.add(Paths.get(userHome, "Library", "Application Support", "pos-system", "posdb"));
        }
        // Linux: .pos-system is same as current, no need to add

        // 3. Also check home directory based paths
        oldLocations.add(Paths.get(userHome, ".pos-system", "posdb"));

        Path newDbPath = newDataDir.resolve("posdb");

        // Try each old location
        for (Path oldBasePath : oldLocations) {
            Path oldDbFile = Paths.get(oldBasePath.toString() + ".mv.db");
            if (Files.exists(oldDbFile)) {
                logger.info("Found old database at {}, performing merge...", oldDbFile);

                // Use DatabaseMigrationService for smart merge
                boolean success = DatabaseMigrationService.mergeIfNeeded(oldBasePath, newDbPath);
                if (success) {
                    logger.info("Database merge completed successfully from {}", oldBasePath);
                } else {
                    logger.warn("Database merge returned false, check logs for details");
                }
                // Continue checking other locations in case there are multiple old databases
            }
        }

        // Also migrate old config.properties from various locations
        java.util.List<Path> oldConfigLocations = new java.util.ArrayList<>();
        oldConfigLocations.add(Paths.get("data", "config.properties"));
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                oldConfigLocations.add(Paths.get(appData, "POS System", "config.properties"));
                oldConfigLocations.add(Paths.get(appData, "pos-system", "config.properties"));
            }
        } else if (os.contains("mac")) {
            oldConfigLocations
                    .add(Paths.get(userHome, "Library", "Application Support", "POS System", "config.properties"));
            oldConfigLocations
                    .add(Paths.get(userHome, "Library", "Application Support", "pos-system", "config.properties"));
        }
        oldConfigLocations.add(Paths.get(userHome, ".pos-system", "config.properties"));

        for (Path oldConfigPath : oldConfigLocations) {
            if (Files.exists(oldConfigPath) && !Files.exists(configFilePath)) {
                try {
                    Files.copy(oldConfigPath, configFilePath);
                    logger.info("Migrated config.properties from {} to {}", oldConfigPath, configFilePath);
                    // Reload configuration after migration
                    loadConfiguration();
                    break; // Only need to migrate config once
                } catch (IOException e) {
                    logger.error("Failed to migrate config.properties from {}", oldConfigPath, e);
                }
            }
        }
    }
}
