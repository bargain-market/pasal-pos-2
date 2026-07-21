package com.pos.service;

import com.pos.database.DatabaseManager;
import com.pos.model.Customer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class CustomerServicePerformanceTest {

    private CustomerService customerService;
    private DatabaseManager dbManager;

    @Before
    public void setUp() {
        dbManager = DatabaseManager.getInstance();
        // Initialize schema (creates tables)
        dbManager.initializeSchema();
        // Clear data just in case
        dbManager.clearStoreData();

        customerService = CustomerService.getInstance();
    }

    @After
    public void tearDown() {
        dbManager.clearStoreData();
    }

    @Test
    public void testSearchPerformance() throws SQLException {
        // 1. Insert 100,000 customers
        int count = 100000;
        System.out.println("Inserting " + count + " customers...");
        long startInsert = System.nanoTime();
        insertCustomers(count);
        long endInsert = System.nanoTime();
        System.out.println("Insertion took: " + (endInsert - startInsert) / 1_000_000.0 + " ms");

        // Warmup
        customerService.searchCustomers("Warmup");

        // 2. Measure search performance
        // Search for "Smith" (some matches)
        long start = System.nanoTime();
        List<Customer> results = customerService.searchCustomers("Smith");
        long end = System.nanoTime();

        System.out.println("Search 'Smith' took: " + (end - start) / 1_000_000.0 + " ms");
        assertTrue("Should find results", results.size() > 0);

        // Search for "NonExistentXYZ" (no matches, likely full scan)
        start = System.nanoTime();
        results = customerService.searchCustomers("NonExistentXYZ");
        end = System.nanoTime();
        System.out.println("Search 'NonExistentXYZ' took: " + (end - start) / 1_000_000.0 + " ms");
        assertEquals(0, results.size());
    }

    private void insertCustomers(int count) throws SQLException {
        Connection conn = dbManager.getConnection();
        String sql = "INSERT INTO customers (id, first_name, last_name, email, phone, created_at, updated_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
        conn.setAutoCommit(false);
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < count; i++) {
                stmt.setString(1, UUID.randomUUID().toString());
                stmt.setString(2, "FirstName" + i);
                stmt.setString(3, "LastName" + i);
                stmt.setString(4, "user" + i + "@example.com");
                stmt.setString(5, "555-000-" + String.format("%04d", i));
                stmt.addBatch();

                if (i % 1000 == 0) {
                    stmt.executeBatch();
                    conn.commit();
                }
            }
            // Add some "Smith"s
            for(int i=0; i<50; i++) {
                 stmt.setString(1, UUID.randomUUID().toString());
                stmt.setString(2, "John");
                stmt.setString(3, "Smith" + i);
                stmt.setString(4, "jsmith" + i + "@example.com");
                stmt.setString(5, "555-111-" + String.format("%04d", i));
                stmt.addBatch();
            }
            stmt.executeBatch();
            conn.commit();
        }
    }
}
