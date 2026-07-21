package com.pos.service;

import com.pos.database.DatabaseManager;
import com.pos.model.Vendor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing vendors/suppliers.
 * Handles CRUD operations for vendor records and vendor-related queries.
 */
public class VendorService {

    private static final Logger logger = LoggerFactory.getLogger(VendorService.class);
    private static VendorService instance;
    private final DatabaseManager dbManager;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private VendorService() {
        this.dbManager = DatabaseManager.getInstance();
    }

    public static synchronized VendorService getInstance() {
        if (instance == null) {
            instance = new VendorService();
        }
        return instance;
    }

    /**
     * Create a new vendor
     */
    public Vendor createVendor(Vendor vendor) {
        if (vendor.getId() == null || vendor.getId().isEmpty()) {
            vendor.setId(UUID.randomUUID().toString());
        }
        
        String now = LocalDateTime.now().format(DATE_FORMATTER);
        vendor.setCreatedAt(now);
        vendor.setUpdatedAt(now);

        String sql = """
            INSERT INTO vendors (id, name, contact_name, email, phone, address, payment_terms, 
                commission_rate, default_cost_margin, bank_account_info, notes, is_active, 
                synced, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, vendor.getId());
            stmt.setString(2, vendor.getName());
            stmt.setString(3, vendor.getContactName());
            stmt.setString(4, vendor.getEmail());
            stmt.setString(5, vendor.getPhone());
            stmt.setString(6, vendor.getAddress());
            stmt.setString(7, vendor.getPaymentTerms());
            stmt.setBigDecimal(8, vendor.getCommissionRate());
            stmt.setBigDecimal(9, vendor.getDefaultCostMargin());
            stmt.setString(10, vendor.getBankAccountInfo());
            stmt.setString(11, vendor.getNotes());
            stmt.setBoolean(12, vendor.isActive());
            stmt.setBoolean(13, false); // Not synced initially
            stmt.setString(14, vendor.getCreatedAt());
            stmt.setString(15, vendor.getUpdatedAt());

            stmt.executeUpdate();
            conn.commit();
            
            logger.info("Created vendor: {} ({})", vendor.getName(), vendor.getId());
            return vendor;
            
        } catch (SQLException e) {
            logger.error("Error creating vendor", e);
            throw new RuntimeException("Failed to create vendor", e);
        }
    }

    /**
     * Update an existing vendor
     */
    public Vendor updateVendor(Vendor vendor) {
        vendor.setUpdatedAt(LocalDateTime.now().format(DATE_FORMATTER));

        String sql = """
            UPDATE vendors SET 
                name = ?, contact_name = ?, email = ?, phone = ?, address = ?, 
                payment_terms = ?, commission_rate = ?, default_cost_margin = ?, 
                bank_account_info = ?, notes = ?, is_active = ?, synced = ?, updated_at = ?
            WHERE id = ?
        """;

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, vendor.getName());
            stmt.setString(2, vendor.getContactName());
            stmt.setString(3, vendor.getEmail());
            stmt.setString(4, vendor.getPhone());
            stmt.setString(5, vendor.getAddress());
            stmt.setString(6, vendor.getPaymentTerms());
            stmt.setBigDecimal(7, vendor.getCommissionRate());
            stmt.setBigDecimal(8, vendor.getDefaultCostMargin());
            stmt.setString(9, vendor.getBankAccountInfo());
            stmt.setString(10, vendor.getNotes());
            stmt.setBoolean(11, vendor.isActive());
            stmt.setBoolean(12, false); // Mark as not synced after update
            stmt.setString(13, vendor.getUpdatedAt());
            stmt.setString(14, vendor.getId());

            int updated = stmt.executeUpdate();
            conn.commit();
            
            if (updated > 0) {
                logger.info("Updated vendor: {} ({})", vendor.getName(), vendor.getId());
            } else {
                logger.warn("No vendor found with ID: {}", vendor.getId());
            }
            
            return vendor;
            
        } catch (SQLException e) {
            logger.error("Error updating vendor", e);
            throw new RuntimeException("Failed to update vendor", e);
        }
    }

    /**
     * Get vendor by ID
     */
    public Vendor getVendorById(String id) {
        String sql = "SELECT * FROM vendors WHERE id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, id);
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return mapResultSetToVendor(rs);
            }
            
        } catch (SQLException e) {
            logger.error("Error getting vendor by ID", e);
        }
        
        return null;
    }

    /**
     * Get all vendors
     */
    public List<Vendor> getAllVendors() {
        return getAllVendors(false);
    }

    /**
     * Get all vendors, optionally including inactive ones
     */
    public List<Vendor> getAllVendors(boolean includeInactive) {
        List<Vendor> vendors = new ArrayList<>();
        
        String sql = includeInactive 
            ? "SELECT * FROM vendors ORDER BY name" 
            : "SELECT * FROM vendors WHERE is_active = TRUE ORDER BY name";

        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                vendors.add(mapResultSetToVendor(rs));
            }
            
        } catch (SQLException e) {
            logger.error("Error getting all vendors", e);
        }
        
        return vendors;
    }

    /**
     * Search vendors by name
     */
    public List<Vendor> searchVendors(String query) {
        List<Vendor> vendors = new ArrayList<>();
        
        if (query == null || query.trim().isEmpty()) {
            return vendors;
        }
        
        String searchPattern = "%" + query.trim().toLowerCase() + "%";
        String sql = """
            SELECT * FROM vendors 
            WHERE LOWER(name) LIKE ? OR LOWER(contact_name) LIKE ? OR LOWER(email) LIKE ?
            ORDER BY name
        """;

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, searchPattern);
            stmt.setString(2, searchPattern);
            stmt.setString(3, searchPattern);
            
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                vendors.add(mapResultSetToVendor(rs));
            }
            
        } catch (SQLException e) {
            logger.error("Error searching vendors", e);
        }
        
        return vendors;
    }

    /**
     * Get vendors with products
     */
    public List<Vendor> getVendorsWithProducts() {
        List<Vendor> vendors = new ArrayList<>();
        
        String sql = """
            SELECT DISTINCT v.* FROM vendors v
            INNER JOIN products p ON p.vendor_id = v.id
            WHERE v.is_active = TRUE
            ORDER BY v.name
        """;

        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                vendors.add(mapResultSetToVendor(rs));
            }
            
        } catch (SQLException e) {
            logger.error("Error getting vendors with products", e);
        }
        
        return vendors;
    }

    /**
     * Delete a vendor (soft delete - sets is_active to false)
     */
    public boolean deleteVendor(String id) {
        String sql = "UPDATE vendors SET is_active = FALSE, synced = FALSE, updated_at = ? WHERE id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, LocalDateTime.now().format(DATE_FORMATTER));
            stmt.setString(2, id);
            
            int deleted = stmt.executeUpdate();
            conn.commit();
            
            if (deleted > 0) {
                logger.info("Soft deleted vendor: {}", id);
                return true;
            }
            
        } catch (SQLException e) {
            logger.error("Error deleting vendor", e);
        }
        
        return false;
    }

    /**
     * Get unsynced vendors for backend sync
     */
    public List<Vendor> getUnsyncedVendors() {
        List<Vendor> vendors = new ArrayList<>();
        
        String sql = "SELECT * FROM vendors WHERE synced = FALSE";

        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                vendors.add(mapResultSetToVendor(rs));
            }
            
        } catch (SQLException e) {
            logger.error("Error getting unsynced vendors", e);
        }
        
        return vendors;
    }

    /**
     * Mark vendors as synced
     */
    public void markVendorsAsSynced(List<String> vendorIds) {
        if (vendorIds == null || vendorIds.isEmpty()) {
            return;
        }
        
        String sql = "UPDATE vendors SET synced = TRUE WHERE id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            for (String id : vendorIds) {
                stmt.setString(1, id);
                stmt.addBatch();
            }
            
            stmt.executeBatch();
            conn.commit();
            
            logger.info("Marked {} vendors as synced", vendorIds.size());
            
        } catch (SQLException e) {
            logger.error("Error marking vendors as synced", e);
        }
    }

    /**
     * Save or update vendor from sync
     */
    public void saveVendorFromSync(Vendor vendor) {
        String sql = """
            MERGE INTO vendors (id, name, contact_name, email, phone, address, payment_terms,
                commission_rate, default_cost_margin, bank_account_info, notes, is_active, 
                synced, updated_at)
            KEY (id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
        """;

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, vendor.getId());
            stmt.setString(2, vendor.getName());
            stmt.setString(3, vendor.getContactName());
            stmt.setString(4, vendor.getEmail());
            stmt.setString(5, vendor.getPhone());
            stmt.setString(6, vendor.getAddress());
            stmt.setString(7, vendor.getPaymentTerms());
            stmt.setBigDecimal(8, vendor.getCommissionRate());
            stmt.setBigDecimal(9, vendor.getDefaultCostMargin());
            stmt.setString(10, vendor.getBankAccountInfo());
            stmt.setString(11, vendor.getNotes());
            stmt.setBoolean(12, vendor.isActive());
            stmt.setString(13, vendor.getUpdatedAt());

            stmt.executeUpdate();
            conn.commit();
            
            logger.debug("Saved vendor from sync: {} ({})", vendor.getName(), vendor.getId());
            
        } catch (SQLException e) {
            logger.error("Error saving vendor from sync", e);
        }
    }

    /**
     * Get count of products for a vendor
     */
    public int getProductCountForVendor(String vendorId) {
        String sql = "SELECT COUNT(*) FROM products WHERE vendor_id = ?";

        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, vendorId);
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return rs.getInt(1);
            }
            
        } catch (SQLException e) {
            logger.error("Error getting product count for vendor", e);
        }
        
        return 0;
    }

    /**
     * Map ResultSet to Vendor object
     */
    private Vendor mapResultSetToVendor(ResultSet rs) throws SQLException {
        Vendor vendor = new Vendor();
        vendor.setId(rs.getString("id"));
        vendor.setName(rs.getString("name"));
        vendor.setContactName(rs.getString("contact_name"));
        vendor.setEmail(rs.getString("email"));
        vendor.setPhone(rs.getString("phone"));
        vendor.setAddress(rs.getString("address"));
        vendor.setPaymentTerms(rs.getString("payment_terms"));
        
        BigDecimal commissionRate = rs.getBigDecimal("commission_rate");
        vendor.setCommissionRate(commissionRate != null ? commissionRate : BigDecimal.ZERO);
        
        BigDecimal defaultCostMargin = rs.getBigDecimal("default_cost_margin");
        vendor.setDefaultCostMargin(defaultCostMargin != null ? defaultCostMargin : BigDecimal.ZERO);
        
        vendor.setBankAccountInfo(rs.getString("bank_account_info"));
        vendor.setNotes(rs.getString("notes"));
        vendor.setActive(rs.getBoolean("is_active"));
        vendor.setSynced(rs.getBoolean("synced"));
        vendor.setUpdatedAt(rs.getString("updated_at"));
        
        return vendor;
    }
}
