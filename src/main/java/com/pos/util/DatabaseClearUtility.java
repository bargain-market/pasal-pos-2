package com.pos.util;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.service.BackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone utility to completely clear the POS system database.
 * 
 * This utility can be run independently to:
 * 1. Drop all database tables and indexes
 * 2. Shutdown the database gracefully
 * 3. Delete all database files
 * 
 * Usage:
 *   java -cp <classpath> com.pos.util.DatabaseClearUtility
 * 
 * Or via Maven:
 *   mvn exec:java -Dexec.mainClass="com.pos.util.DatabaseClearUtility"
 */
public class DatabaseClearUtility {
    
    private static final Logger logger = LoggerFactory.getLogger(DatabaseClearUtility.class);
    
    public static void main(String[] args) {
        logger.info("========================================");
        logger.info("Pasal POS 2 Database Clear Utility");
        logger.info("========================================");
        
        try {
            // Initialize configuration
            logger.info("Loading configuration...");
            ConfigManager config = ConfigManager.getInstance();
            String dbUrl = config.getProperty("database.url");
            logger.info("Database URL: {}", dbUrl != null ? dbUrl.replaceAll("password=[^;]*", "password=***") : "not configured");
            
            // Initialize database manager
            logger.info("Initializing database manager...");
            DatabaseManager dbManager = DatabaseManager.getInstance();
            BackupService backupService = BackupService.getInstance();
            
            // Confirm deletion (if running interactively)
            if (args.length == 0 || !args[0].equals("--force")) {
                logger.warn("========================================");
                logger.warn("WARNING: This will DELETE ALL DATA!");
                logger.warn("========================================");
                logger.warn("To proceed, run with --force flag:");
                logger.warn("  java -cp <classpath> com.pos.util.DatabaseClearUtility --force");
                logger.warn("Or via Maven:");
                logger.warn("  mvn exec:java -Dexec.mainClass=\"com.pos.util.DatabaseClearUtility\" -Dexec.args=\"--force\"");
                System.exit(1);
            }

            // Create a safety snapshot before destructive deletion
            if (backupService.isBackupSupported()) {
                var snapshot = backupService.createPreResetBackup();
                logger.info("Created pre-reset backup at {}", snapshot.path);
            } else {
                logger.warn("Skipping safety backup because the configured database is not a local H2 file.");
            }
            
            // Perform database deletion
            logger.info("Starting database deletion...");
            dbManager.deleteDatabase();
            
            // Verify deletion by checking if files still exist
            String dbUrlForCheck = config.getProperty("database.url");
            String dbPath = extractDatabasePath(dbUrlForCheck);
            if (dbPath != null) {
                boolean filesExist = checkDatabaseFilesExist(dbPath);
                if (filesExist) {
                    logger.error("WARNING: Database files still exist after deletion attempt!");
                    logger.error("The database may be locked by another process.");
                    logger.error("Please close the POS application and try again.");
                    System.exit(1);
                }
            }
            
            logger.info("========================================");
            logger.info("Database cleared successfully!");
            logger.info("========================================");
            logger.info("The database has been completely reset.");
            logger.info("All tables, indexes, and data files have been removed.");
            logger.info("");
            logger.info("NOTE: When you start the POS application, it will automatically");
            logger.info("sync data from the backend. This is normal behavior.");
            logger.info("The database will be recreated with a fresh schema on next startup.");
            
        } catch (Exception e) {
            logger.error("========================================");
            logger.error("ERROR: Failed to clear database", e);
            logger.error("========================================");
            logger.error("Error details: {}", e.getMessage());
            logger.error("If the database is locked, try:");
            logger.error("  1. Close all running instances of the POS application");
            logger.error("  2. Wait a few seconds");
            logger.error("  3. Run this utility again");
            System.exit(1);
        }
    }
    
    /**
     * Extract database file path from H2 JDBC URL
     */
    private static String extractDatabasePath(String dbUrl) {
        if (dbUrl == null || !dbUrl.startsWith("jdbc:h2:")) {
            return null;
        }
        
        String path = dbUrl.substring("jdbc:h2:".length());
        int paramIndex = path.indexOf(';');
        if (paramIndex >= 0) {
            path = path.substring(0, paramIndex);
        }
        
        // Handle relative paths
        if (path.startsWith("./") || path.startsWith(".\\")) {
            java.nio.file.Path currentDir = java.nio.file.Paths.get("").toAbsolutePath();
            path = currentDir.resolve(path.substring(2)).toString();
        } else if (path.startsWith("~/")) {
            String userHome = System.getProperty("user.home");
            path = java.nio.file.Paths.get(userHome, path.substring(2)).toString();
        }
        
        return path;
    }
    
    /**
     * Check if database files still exist
     */
    private static boolean checkDatabaseFilesExist(String dbPath) {
        String[] extensions = {".mv.db", ".trace.db", ".lock.db"};
        for (String ext : extensions) {
            java.io.File file = new java.io.File(dbPath + ext);
            if (file.exists()) {
                return true;
            }
        }
        return false;
    }
}

