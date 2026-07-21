package com.pos.service;

import com.pos.database.DatabaseManager;
import com.pos.model.Customer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing customers and loyalty
 */
public class CustomerService {

    private static final Logger logger = LoggerFactory.getLogger(CustomerService.class);
    private static CustomerService instance;
    private final DatabaseManager dbManager;

    // Loyalty points configuration
    private static final BigDecimal POINTS_PER_DOLLAR = new BigDecimal("1"); // 1 point per dollar spent
    private static final BigDecimal POINTS_TO_DOLLARS = new BigDecimal("0.01"); // 100 points = $1

    private CustomerService() {
        this.dbManager = DatabaseManager.getInstance();
        initializeTable();
    }

    public static synchronized CustomerService getInstance() {
        if (instance == null) {
            instance = new CustomerService();
        }
        return instance;
    }

    /**
     * Initialize the customers table if it doesn't exist
     */
    private void initializeTable() {
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(true);
            
            String createTable = """
                CREATE TABLE IF NOT EXISTS customers (
                    id VARCHAR(255) PRIMARY KEY,
                    first_name VARCHAR(255),
                    last_name VARCHAR(255),
                    email VARCHAR(255),
                    phone VARCHAR(50),
                    loyalty_points DECIMAL(10,2) DEFAULT 0,
                    total_spent DECIMAL(12,2) DEFAULT 0,
                    visit_count INTEGER DEFAULT 0,
                    last_visit TIMESTAMP,
                    membership_tier VARCHAR(50) DEFAULT 'STANDARD',
                    is_active BOOLEAN DEFAULT TRUE,
                    notes TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    synced BOOLEAN DEFAULT FALSE
                )
            """;
            
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createTable);
                
                // Create indexes
                try {
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_customers_email ON customers(email)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_customers_phone ON customers(phone)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_customers_name ON customers(last_name, first_name)");
                    // Function-based indexes for case-insensitive search optimization
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_customers_lower_first_name ON customers(LOWER(first_name))");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_customers_lower_last_name ON customers(LOWER(last_name))");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_customers_lower_email ON customers(LOWER(email))");
                } catch (SQLException e) {
                    logger.debug("Index may already exist: {}", e.getMessage());
                }
            }
            
            conn.setAutoCommit(false);
            logger.info("Customers table initialized");
        } catch (SQLException e) {
            logger.error("Error initializing customers table", e);
        }
    }

    /**
     * Search customers by name, email, or phone
     */
    public List<Customer> searchCustomers(String query) {
        List<Customer> customers = new ArrayList<>();
        
        if (query == null || query.trim().isEmpty()) {
            return customers;
        }
        
        try (Connection conn = dbManager.getConnection()) {
            
            String searchPattern = "%" + query.trim().toLowerCase() + "%";
            
            // Use UNION to allow index usage for each OR condition
            // Standard OR with LIKE usually results in a full table scan
            // Even with %wildcard%, scanning an index is faster than scanning the table
            String sql = """
                SELECT * FROM (
                    SELECT * FROM customers WHERE LOWER(first_name) LIKE ?
                    UNION
                    SELECT * FROM customers WHERE LOWER(last_name) LIKE ?
                    UNION
                    SELECT * FROM customers WHERE LOWER(email) LIKE ?
                    UNION
                    SELECT * FROM customers WHERE phone LIKE ?
                )
                ORDER BY last_name, first_name
                LIMIT 20
            """;
            
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, searchPattern);
                stmt.setString(2, searchPattern);
                stmt.setString(3, searchPattern);
                stmt.setString(4, searchPattern);
                
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    customers.add(mapResultSetToCustomer(rs));
                }
            }
            
            logger.debug("Found {} customers matching '{}'", customers.size(), query);
        } catch (SQLException e) {
            logger.error("Error searching customers", e);
        }
        
        return customers;
    }

    /**
     * Get customer by ID
     */
    public Customer getCustomerById(String id) {
        try (Connection conn = dbManager.getConnection()) {
            
            String sql = "SELECT * FROM customers WHERE id = ?";
            
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                ResultSet rs = stmt.executeQuery();
                
                if (rs.next()) {
                    return mapResultSetToCustomer(rs);
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting customer by ID", e);
        }
        
        return null;
    }

    /**
     * Get customer by phone number
     */
    public Customer getCustomerByPhone(String phone) {
        try (Connection conn = dbManager.getConnection()) {
            
            // Normalize phone number (remove non-digits)
            String normalizedPhone = phone.replaceAll("[^0-9]", "");
            
            String sql = "SELECT * FROM customers WHERE REPLACE(REPLACE(REPLACE(phone, '-', ''), ' ', ''), '(', '') LIKE ?";
            
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, "%" + normalizedPhone + "%");
                ResultSet rs = stmt.executeQuery();
                
                if (rs.next()) {
                    return mapResultSetToCustomer(rs);
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting customer by phone", e);
        }
        
        return null;
    }

    /**
     * Create a new customer
     */
    public Customer createCustomer(String firstName, String lastName, String email, String phone) {
        try (Connection conn = dbManager.getConnection()) {
            
            String id = UUID.randomUUID().toString();
            
            String sql = """
                INSERT INTO customers (id, first_name, last_name, email, phone, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;
            
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, firstName);
                stmt.setString(3, lastName);
                stmt.setString(4, email);
                stmt.setString(5, phone);
                stmt.executeUpdate();
            }
            
            conn.commit();
            logger.info("Created customer: {} {}", firstName, lastName);
            
            return getCustomerById(id);
        } catch (SQLException e) {
            logger.error("Error creating customer", e);
            return null;
        }
    }

    /**
     * Update customer information
     */
    public boolean updateCustomer(Customer customer) {
        try (Connection conn = dbManager.getConnection()) {
            
            String sql = """
                UPDATE customers SET
                    first_name = ?,
                    last_name = ?,
                    email = ?,
                    phone = ?,
                    notes = ?,
                    updated_at = CURRENT_TIMESTAMP,
                    synced = FALSE
                WHERE id = ?
            """;
            
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, customer.getFirstName());
                stmt.setString(2, customer.getLastName());
                stmt.setString(3, customer.getEmail());
                stmt.setString(4, customer.getPhone());
                stmt.setString(5, customer.getNotes());
                stmt.setString(6, customer.getId());
                
                int rows = stmt.executeUpdate();
                conn.commit();
                
                return rows > 0;
            }
        } catch (SQLException e) {
            logger.error("Error updating customer", e);
            return false;
        }
    }

    /**
     * Record a sale for a customer (updates loyalty points and stats)
     */
    public void recordSale(String customerId, BigDecimal saleAmount) {
        if (customerId == null || saleAmount == null) return;
        
        try (Connection conn = dbManager.getConnection()) {
            
            // Calculate points earned
            BigDecimal pointsEarned = saleAmount.multiply(POINTS_PER_DOLLAR).setScale(0, java.math.RoundingMode.DOWN);
            
            String sql = """
                UPDATE customers SET
                    loyalty_points = loyalty_points + ?,
                    total_spent = total_spent + ?,
                    visit_count = visit_count + 1,
                    last_visit = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP,
                    synced = FALSE
                WHERE id = ?
            """;
            
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBigDecimal(1, pointsEarned);
                stmt.setBigDecimal(2, saleAmount);
                stmt.setString(3, customerId);
                stmt.executeUpdate();
            }
            
            // Check for tier upgrade
            updateMembershipTier(customerId);
            
            conn.commit();
            logger.info("Recorded sale for customer {}: ${}, {} points earned", customerId, saleAmount, pointsEarned);
        } catch (SQLException e) {
            logger.error("Error recording sale for customer", e);
        }
    }

    /**
     * Redeem loyalty points
     */
    public BigDecimal redeemPoints(String customerId, BigDecimal pointsToRedeem) {
        if (customerId == null || pointsToRedeem == null) return BigDecimal.ZERO;
        
        try (Connection conn = dbManager.getConnection()) {
            
            // Get current points
            Customer customer = getCustomerById(customerId);
            if (customer == null) return BigDecimal.ZERO;
            
            BigDecimal currentPoints = customer.getLoyaltyPoints();
            if (currentPoints.compareTo(pointsToRedeem) < 0) {
                pointsToRedeem = currentPoints; // Can only redeem what they have
            }
            
            // Calculate dollar value
            BigDecimal dollarValue = pointsToRedeem.multiply(POINTS_TO_DOLLARS).setScale(2, java.math.RoundingMode.DOWN);
            
            // Deduct points
            String sql = """
                UPDATE customers SET
                    loyalty_points = loyalty_points - ?,
                    updated_at = CURRENT_TIMESTAMP,
                    synced = FALSE
                WHERE id = ?
            """;
            
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setBigDecimal(1, pointsToRedeem);
                stmt.setString(2, customerId);
                stmt.executeUpdate();
            }
            
            conn.commit();
            logger.info("Redeemed {} points for customer {}: ${}", pointsToRedeem, customerId, dollarValue);
            
            return dollarValue;
        } catch (SQLException e) {
            logger.error("Error redeeming points", e);
            return BigDecimal.ZERO;
        }
    }

    /**
     * Update membership tier based on total spent
     */
    private void updateMembershipTier(String customerId) {
        try (Connection conn = dbManager.getConnection()) {
            
            // Get total spent
            String getSql = "SELECT total_spent FROM customers WHERE id = ?";
            BigDecimal totalSpent = BigDecimal.ZERO;
            
            try (PreparedStatement stmt = conn.prepareStatement(getSql)) {
                stmt.setString(1, customerId);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    totalSpent = rs.getBigDecimal("total_spent");
                }
            }
            
            // Determine tier
            String newTier;
            if (totalSpent.compareTo(new BigDecimal("5000")) >= 0) {
                newTier = "GOLD";
            } else if (totalSpent.compareTo(new BigDecimal("1000")) >= 0) {
                newTier = "SILVER";
            } else if (totalSpent.compareTo(new BigDecimal("250")) >= 0) {
                newTier = "BRONZE";
            } else {
                newTier = "STANDARD";
            }
            
            // Update tier
            String updateSql = "UPDATE customers SET membership_tier = ? WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setString(1, newTier);
                stmt.setString(2, customerId);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Error updating membership tier", e);
        }
    }

    /**
     * Get points value in dollars
     */
    public BigDecimal getPointsValue(BigDecimal points) {
        return points.multiply(POINTS_TO_DOLLARS).setScale(2, java.math.RoundingMode.DOWN);
    }

    /**
     * Get points per dollar configuration
     */
    public BigDecimal getPointsPerDollar() {
        return POINTS_PER_DOLLAR;
    }

    private Customer mapResultSetToCustomer(ResultSet rs) throws SQLException {
        Customer customer = new Customer();
        customer.setId(rs.getString("id"));
        customer.setFirstName(rs.getString("first_name"));
        customer.setLastName(rs.getString("last_name"));
        customer.setEmail(rs.getString("email"));
        customer.setPhone(rs.getString("phone"));
        customer.setLoyaltyPoints(rs.getBigDecimal("loyalty_points"));
        customer.setTotalSpent(rs.getBigDecimal("total_spent"));
        customer.setVisitCount(rs.getInt("visit_count"));
        
        Timestamp lastVisit = rs.getTimestamp("last_visit");
        if (lastVisit != null) {
            customer.setLastVisit(lastVisit.toLocalDateTime());
        }
        
        customer.setMembershipTier(rs.getString("membership_tier"));
        customer.setActive(rs.getBoolean("is_active"));
        customer.setNotes(rs.getString("notes"));
        
        return customer;
    }
}

