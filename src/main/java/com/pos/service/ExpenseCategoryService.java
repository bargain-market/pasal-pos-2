package com.pos.service;

import com.pos.database.DatabaseManager;
import com.pos.model.ExpenseCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing expense categories.
 * Handles CRUD operations for expense category records.
 */
public class ExpenseCategoryService {

    private static final Logger logger = LoggerFactory.getLogger(ExpenseCategoryService.class);
    private static ExpenseCategoryService instance;
    private final DatabaseManager dbManager;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private ExpenseCategoryService() {
        this.dbManager = DatabaseManager.getInstance();
    }

    public static synchronized ExpenseCategoryService getInstance() {
        if (instance == null) {
            instance = new ExpenseCategoryService();
        }
        return instance;
    }

    /**
     * Get all active categories
     */
    public List<ExpenseCategory> getAllCategories() {
        return getAllCategories(false);
    }

    /**
     * Get all categories, optionally including inactive ones
     */
    public List<ExpenseCategory> getAllCategories(boolean includeInactive) {
        List<ExpenseCategory> categories = new ArrayList<>();
        
        String sql = includeInactive 
            ? "SELECT * FROM expense_categories ORDER BY display_order, name" 
            : "SELECT * FROM expense_categories WHERE is_active = TRUE ORDER BY display_order, name";

        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                categories.add(mapResultSetToCategory(rs));
            }
            
        } catch (SQLException e) {
            logger.error("Error getting all expense categories", e);
        }
        
        return categories;
    }

    /**
     * Get system categories (predefined categories)
     */
    public List<ExpenseCategory> getSystemCategories() {
        List<ExpenseCategory> categories = new ArrayList<>();
        
        String sql = "SELECT * FROM expense_categories WHERE is_system = TRUE AND is_active = TRUE ORDER BY display_order, name";

        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                categories.add(mapResultSetToCategory(rs));
            }
            
        } catch (SQLException e) {
            logger.error("Error getting system expense categories", e);
        }
        
        return categories;
    }

    /**
     * Get category by ID
     */
    public ExpenseCategory getCategoryById(String id) {
        String sql = "SELECT * FROM expense_categories WHERE id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, id);
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return mapResultSetToCategory(rs);
            }
            
        } catch (SQLException e) {
            logger.error("Error getting expense category by ID", e);
        }
        
        return null;
    }

    /**
     * Get category by name
     */
    public ExpenseCategory getCategoryByName(String name) {
        String sql = "SELECT * FROM expense_categories WHERE name = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, name);
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return mapResultSetToCategory(rs);
            }
            
        } catch (SQLException e) {
            logger.error("Error getting expense category by name", e);
        }
        
        return null;
    }

    /**
     * Create a new custom category (system categories are created during initialization)
     */
    public ExpenseCategory createCategory(ExpenseCategory category) {
        if (category.getId() == null || category.getId().isEmpty()) {
            category.setId(UUID.randomUUID().toString());
        }
        
        // Ensure custom categories are marked as non-system
        category.setSystem(false);
        
        String now = LocalDateTime.now().format(DATE_FORMATTER);
        category.setCreatedAt(now);
        category.setUpdatedAt(now);

        String sql = """
            INSERT INTO expense_categories (id, name, description, is_system, is_active, display_order, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, category.getId());
            stmt.setString(2, category.getName());
            stmt.setString(3, category.getDescription());
            stmt.setBoolean(4, category.isSystem());
            stmt.setBoolean(5, category.isActive());
            stmt.setInt(6, category.getDisplayOrder());
            stmt.setString(7, category.getCreatedAt());
            stmt.setString(8, category.getUpdatedAt());

            stmt.executeUpdate();
            conn.commit();
            
            logger.info("Created expense category: {} ({})", category.getName(), category.getId());
            return category;
            
        } catch (SQLException e) {
            logger.error("Error creating expense category", e);
            throw new RuntimeException("Failed to create expense category", e);
        }
    }

    /**
     * Update an existing category
     */
    public ExpenseCategory updateCategory(ExpenseCategory category) {
        // Prevent updating system categories (except is_active and display_order)
        ExpenseCategory existing = getCategoryById(category.getId());
        if (existing != null && existing.isSystem()) {
            // Only allow updating is_active and display_order for system categories
            category.setName(existing.getName());
            category.setDescription(existing.getDescription());
            category.setSystem(true);
        }
        
        category.setUpdatedAt(LocalDateTime.now().format(DATE_FORMATTER));

        String sql = """
            UPDATE expense_categories SET 
                name = ?, description = ?, is_active = ?, display_order = ?, updated_at = ?
            WHERE id = ?
        """;

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, category.getName());
            stmt.setString(2, category.getDescription());
            stmt.setBoolean(3, category.isActive());
            stmt.setInt(4, category.getDisplayOrder());
            stmt.setString(5, category.getUpdatedAt());
            stmt.setString(6, category.getId());

            int updated = stmt.executeUpdate();
            conn.commit();
            
            if (updated > 0) {
                logger.info("Updated expense category: {} ({})", category.getName(), category.getId());
            } else {
                logger.warn("No expense category found with ID: {}", category.getId());
            }
            
            return category;
            
        } catch (SQLException e) {
            logger.error("Error updating expense category", e);
            throw new RuntimeException("Failed to update expense category", e);
        }
    }

    /**
     * Soft delete a category (only custom categories can be deleted)
     */
    public boolean deleteCategory(String id) {
        ExpenseCategory category = getCategoryById(id);
        if (category == null) {
            return false;
        }
        
        if (category.isSystem()) {
            logger.warn("Cannot delete system category: {}", category.getName());
            throw new RuntimeException("Cannot delete system expense category");
        }

        // Soft delete by setting is_active to false
        String sql = "UPDATE expense_categories SET is_active = FALSE, updated_at = ? WHERE id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, LocalDateTime.now().format(DATE_FORMATTER));
            stmt.setString(2, id);

            int updated = stmt.executeUpdate();
            conn.commit();
            
            if (updated > 0) {
                logger.info("Deleted expense category: {}", id);
                return true;
            }
            
        } catch (SQLException e) {
            logger.error("Error deleting expense category", e);
            throw new RuntimeException("Failed to delete expense category", e);
        }
        
        return false;
    }

    /**
     * Map ResultSet to ExpenseCategory
     */
    private ExpenseCategory mapResultSetToCategory(ResultSet rs) throws SQLException {
        ExpenseCategory category = new ExpenseCategory();
        category.setId(rs.getString("id"));
        category.setName(rs.getString("name"));
        category.setDescription(rs.getString("description"));
        category.setSystem(rs.getBoolean("is_system"));
        category.setActive(rs.getBoolean("is_active"));
        category.setDisplayOrder(rs.getInt("display_order"));
        category.setCreatedAt(rs.getString("created_at"));
        category.setUpdatedAt(rs.getString("updated_at"));
        return category;
    }
}
