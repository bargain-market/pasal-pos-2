package com.pos.service;

import com.pos.database.DatabaseManager;
import com.pos.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing favorite products for quick access on the sales screen
 */
public class FavoritesService {

    private static final Logger logger = LoggerFactory.getLogger(FavoritesService.class);
    private static FavoritesService instance;
    private final DatabaseManager dbManager;
    private static final int MAX_FAVORITES = 12;

    private FavoritesService() {
        this.dbManager = DatabaseManager.getInstance();
        initializeTable();
    }

    public static synchronized FavoritesService getInstance() {
        if (instance == null) {
            instance = new FavoritesService();
        }
        return instance;
    }

    /**
     * Initialize the favorites table if it doesn't exist
     */
    private void initializeTable() {
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(true);

            String createTable = """
                        CREATE TABLE IF NOT EXISTS favorite_products (
                            id VARCHAR(255) PRIMARY KEY,
                            product_id VARCHAR(255) NOT NULL,
                            user_id VARCHAR(255),
                            display_order INTEGER DEFAULT 0,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            UNIQUE(product_id, user_id)
                        )
                    """;

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createTable);

                // Create index
                try {
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_favorites_user ON favorite_products(user_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_favorites_order ON favorite_products(display_order)");
                } catch (SQLException e) {
                    logger.debug("Index may already exist: {}", e.getMessage());
                }
            }

            conn.setAutoCommit(false);
            logger.info("Favorites table initialized");
        } catch (SQLException e) {
            logger.error("Error initializing favorites table", e);
        }
    }

    /**
     * Get all favorite products for the current user (or global if no user)
     */
    public List<Product> getFavorites() {
        return getFavorites(null);
    }

    /**
     * Get all favorite products for a specific user
     */
    public List<Product> getFavorites(String userId) {
        List<Product> favorites = new ArrayList<>();

        try (Connection conn = dbManager.getConnection()) {
            String sql = """
                        SELECT p.id, p.barcode, p.name, p.price, p.stock_quantity, p.department_id
                        FROM favorite_products f
                        JOIN products p ON f.product_id = p.id
                        WHERE f.user_id IS NULL OR f.user_id = ?
                        ORDER BY f.display_order ASC, f.created_at ASC
                        LIMIT ?
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, userId);
                stmt.setInt(2, MAX_FAVORITES);

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    Product product = new Product(
                            rs.getString("id"),
                            rs.getString("barcode"),
                            rs.getString("name"),
                            rs.getBigDecimal("price"),
                            rs.getInt("stock_quantity"),
                            rs.getString("department_id"));
                    favorites.add(product);
                }
            }

            logger.debug("Loaded {} favorite products", favorites.size());
        } catch (SQLException e) {
            logger.error("Error loading favorites", e);
        }

        return favorites;
    }

    /**
     * Add a product to favorites
     */
    public boolean addFavorite(String productId) {
        return addFavorite(productId, null);
    }

    /**
     * Add a product to favorites for a specific user
     */
    public boolean addFavorite(String productId, String userId) {
        try (Connection conn = dbManager.getConnection()) {
            // Check if already at max favorites
            String countSql = "SELECT COUNT(*) FROM favorite_products WHERE user_id IS NULL OR user_id = ?";
            try (PreparedStatement countStmt = conn.prepareStatement(countSql)) {
                countStmt.setString(1, userId);
                ResultSet rs = countStmt.executeQuery();
                if (rs.next() && rs.getInt(1) >= MAX_FAVORITES) {
                    logger.warn("Maximum favorites ({}) reached", MAX_FAVORITES);
                    return false;
                }
            }

            // Get next display order
            String orderSql = "SELECT COALESCE(MAX(display_order), 0) + 1 FROM favorite_products WHERE user_id IS NULL OR user_id = ?";
            int nextOrder = 0;
            try (PreparedStatement orderStmt = conn.prepareStatement(orderSql)) {
                orderStmt.setString(1, userId);
                ResultSet rs = orderStmt.executeQuery();
                if (rs.next()) {
                    nextOrder = rs.getInt(1);
                }
            }

            // Insert favorite
            String insertSql = """
                        INSERT INTO favorite_products (id, product_id, user_id, display_order)
                        VALUES (?, ?, ?, ?)
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                stmt.setString(1, UUID.randomUUID().toString());
                stmt.setString(2, productId);
                stmt.setString(3, userId);
                stmt.setInt(4, nextOrder);
                stmt.executeUpdate();
            }

            conn.commit();
            logger.info("Added product {} to favorites", productId);
            return true;
        } catch (SQLException e) {
            if (e.getMessage().contains("UNIQUE") || e.getMessage().contains("unique") ||
                    e.getMessage().contains("duplicate") || e.getMessage().contains("Duplicate")) {
                logger.warn("Product {} is already a favorite", productId);
            } else {
                logger.error("Error adding favorite", e);
            }
            return false;
        }
    }

    /**
     * Remove a product from favorites
     */
    public boolean removeFavorite(String productId) {
        return removeFavorite(productId, null);
    }

    /**
     * Remove a product from favorites for a specific user
     */
    public boolean removeFavorite(String productId, String userId) {
        try (Connection conn = dbManager.getConnection()) {
            String sql;
            if (userId == null) {
                sql = "DELETE FROM favorite_products WHERE product_id = ? AND user_id IS NULL";
            } else {
                sql = "DELETE FROM favorite_products WHERE product_id = ? AND (user_id IS NULL OR user_id = ?)";
            }

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, productId);
                if (userId != null) {
                    stmt.setString(2, userId);
                }
                int rows = stmt.executeUpdate();
                conn.commit();

                if (rows > 0) {
                    logger.info("Removed product {} from favorites", productId);
                    return true;
                }
            }
        } catch (SQLException e) {
            logger.error("Error removing favorite", e);
        }
        return false;
    }

    /**
     * Check if a product is a favorite
     */
    public boolean isFavorite(String productId) {
        return isFavorite(productId, null);
    }

    /**
     * Check if a product is a favorite for a specific user
     */
    public boolean isFavorite(String productId, String userId) {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT 1 FROM favorite_products WHERE product_id = ? AND (user_id IS NULL OR user_id = ?)";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, productId);
                stmt.setString(2, userId);
                ResultSet rs = stmt.executeQuery();
                return rs.next();
            }
        } catch (SQLException e) {
            logger.error("Error checking favorite", e);
        }
        return false;
    }

    /**
     * Reorder favorites
     */
    public boolean reorderFavorites(List<String> productIds, String userId) {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "UPDATE favorite_products SET display_order = ? WHERE product_id = ? AND (user_id IS NULL OR user_id = ?)";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (int i = 0; i < productIds.size(); i++) {
                    stmt.setInt(1, i);
                    stmt.setString(2, productIds.get(i));
                    stmt.setString(3, userId);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }

            conn.commit();
            logger.info("Reordered {} favorites", productIds.size());
            return true;
        } catch (SQLException e) {
            logger.error("Error reordering favorites", e);
            return false;
        }
    }

    /**
     * Clear all favorites for a user
     */
    public boolean clearFavorites(String userId) {
        try (Connection conn = dbManager.getConnection()) {
            String sql;
            if (userId == null) {
                sql = "DELETE FROM favorite_products WHERE user_id IS NULL";
            } else {
                sql = "DELETE FROM favorite_products WHERE user_id IS NULL OR user_id = ?";
            }

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (userId != null) {
                    stmt.setString(1, userId);
                }
                stmt.executeUpdate();
            }

            conn.commit();
            logger.info("Cleared all favorites");
            return true;
        } catch (SQLException e) {
            logger.error("Error clearing favorites", e);
            return false;
        }
    }

    /**
     * Get the maximum number of favorites allowed
     */
    public int getMaxFavorites() {
        return MAX_FAVORITES;
    }

    /**
     * Get current favorite count
     */
    public int getFavoriteCount(String userId) {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT COUNT(*) FROM favorite_products WHERE user_id IS NULL OR user_id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, userId);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.error("Error counting favorites", e);
        }
        return 0;
    }
}
