package com.pos.util;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CLI utility to create or update the single active admin user for the current store.
 *
 * Usage:
 *   java -cp <classpath> com.pos.util.StoreAdminBootstrap --username admin --pin 1234
 *   java -cp <classpath> com.pos.util.StoreAdminBootstrap --username admin --pin 1234 --full-name "Store Admin"
 *   java -cp <classpath> com.pos.util.StoreAdminBootstrap --username admin --pin 1234 --store-id store-1 --store-name "Main Store"
 */
public class StoreAdminBootstrap {

    public static void main(String[] args) {
        try {
            Map<String, String> options = parseArgs(args);

            if (options.containsKey("help")) {
                printUsage();
                return;
            }

            String username = requireOption(options, "username");
            String pin = requireOption(options, "pin");
            String fullName = options.getOrDefault("full-name", "Store Admin");

            ConfigManager config = ConfigManager.getInstance();

            if (options.containsKey("store-id")) {
                config.setProperty("store.id", options.get("store-id"));
            }

            String storeId = config.getProperty("store.id");
            if (storeId == null || storeId.isBlank()) {
                throw new IllegalStateException("No store is configured. Set store.id first or pass --store-id.");
            }

            String storeName = options.getOrDefault("store-name",
                    defaultIfBlank(config.getProperty("store.name"), "Store " + storeId));

            DatabaseManager dbManager = DatabaseManager.getInstance();
            dbManager.initializeSchema();

            try (Connection conn = dbManager.getConnection()) {
                conn.setAutoCommit(false);

                upsertStoreSettings(conn, storeId, storeName);
                upsertAdminUser(conn, storeId, username, pin, fullName);

                conn.commit();
            }

            System.out.println("Admin user is ready.");
            System.out.println("Store ID: " + storeId);
            System.out.println("Store Name: " + storeName);
            System.out.println("Username: " + username);
            System.out.println("Role: Admin");
        } catch (Exception e) {
            System.err.println("Failed to create store admin: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void upsertStoreSettings(Connection conn, String storeId, String storeName) throws Exception {
        String sql = """
                MERGE INTO store_settings (id, store_id, store_name, updated_at)
                KEY (id)
                VALUES (?, ?, ?, ?)
                """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, "1");
            stmt.setString(2, storeId);
            stmt.setString(3, storeName);
            stmt.setString(4, Instant.now().toString());
            stmt.executeUpdate();
        }
    }

    private static void upsertAdminUser(Connection conn, String storeId, String username, String pin, String fullName)
            throws Exception {
        ExistingUser existingUser = findUserByUsernameAndStore(conn, username, storeId);
        ExistingUser otherAdmin = findOtherActiveAdmin(conn, storeId, existingUser != null ? existingUser.id : null);

        if (otherAdmin != null) {
            throw new IllegalStateException(
                    "Store already has an active admin user: " + otherAdmin.username + ". Update that user or deactivate it first.");
        }

        String pinHash = PasswordHasher.hashPassword(pin);
        String now = Instant.now().toString();

        if (existingUser == null) {
            String insertSql = """
                    INSERT INTO pos_users (id, username, pin_hash, full_name, store_id, is_active, role, created_at, synced_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                stmt.setString(1, UUID.randomUUID().toString());
                stmt.setString(2, username);
                stmt.setString(3, pinHash);
                stmt.setString(4, fullName);
                stmt.setString(5, storeId);
                stmt.setBoolean(6, true);
                stmt.setString(7, "Admin");
                stmt.setString(8, now);
                stmt.setString(9, now);
                stmt.executeUpdate();
            }
            return;
        }

        String updateSql = """
                UPDATE pos_users
                SET pin_hash = ?, full_name = ?, store_id = ?, is_active = TRUE, role = ?, synced_at = ?
                WHERE id = ?
                """;

        try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            stmt.setString(1, pinHash);
            stmt.setString(2, fullName);
            stmt.setString(3, storeId);
            stmt.setString(4, "Admin");
            stmt.setString(5, now);
            stmt.setString(6, existingUser.id);
            stmt.executeUpdate();
        }
    }

    private static ExistingUser findUserByUsernameAndStore(Connection conn, String username, String storeId) throws Exception {
        String sql = """
                SELECT id, username, role, is_active
                FROM pos_users
                WHERE username = ? AND store_id = ?
                """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, storeId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new ExistingUser(
                            rs.getString("id"),
                            rs.getString("username"),
                            rs.getString("role"),
                            rs.getBoolean("is_active"));
                }
            }
        }

        return null;
    }

    private static ExistingUser findOtherActiveAdmin(Connection conn, String storeId, String excludeUserId) throws Exception {
        String sql = """
                SELECT id, username, role, is_active
                FROM pos_users
                WHERE store_id = ? AND role = 'Admin' AND is_active = TRUE
                """ + (excludeUserId != null ? " AND id <> ?" : "");

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, storeId);
            if (excludeUserId != null) {
                stmt.setString(2, excludeUserId);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new ExistingUser(
                            rs.getString("id"),
                            rs.getString("username"),
                            rs.getString("role"),
                            rs.getBoolean("is_active"));
                }
            }
        }

        return null;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                options.put("help", "true");
                continue;
            }

            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }

            String key = arg.substring(2);
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for --" + key);
            }

            options.put(key, args[++i]);
        }

        return options;
    }

    private static String requireOption(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option --" + key);
        }
        return value;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void printUsage() {
        System.out.println("Create or update the admin POS user for the current store.");
        System.out.println("Required:");
        System.out.println("  --username <value>");
        System.out.println("  --pin <value>");
        System.out.println("Optional:");
        System.out.println("  --full-name <value>");
        System.out.println("  --store-id <value>");
        System.out.println("  --store-name <value>");
    }

    private record ExistingUser(String id, String username, String role, boolean active) {
    }
}
