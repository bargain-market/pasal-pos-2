package com.pos.service;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;

public class PendingSalesCountTest {

    private DatabaseManager dbManager;

    @Before
    public void setUp() throws Exception {
        // Use in-memory database for testing
        ConfigManager.getInstance().setProperty("database.url", "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        dbManager = DatabaseManager.getInstance();
        dbManager.initializeSchema();

        // Clear sales table to ensure clean state
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM sales");
            stmt.execute("DELETE FROM sale_items");
            conn.commit();
        }
    }

    @After
    public void tearDown() throws Exception {
         try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM sales");
            stmt.execute("DELETE FROM sale_items");
            conn.commit();
        }
    }

    @Test
    public void testGetPendingSalesCount() throws Exception {
        SalesService salesService = SalesService.getInstance();

        // Initially 0
        assertEquals(0, salesService.getPendingSalesCount());

        // Insert a pending sale (synced = FALSE)
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO sales (id, sale_id, synced) VALUES ('1', 'SALE-1', FALSE)");
            conn.commit();
        }
        assertEquals(1, salesService.getPendingSalesCount());

        // Insert another pending sale
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO sales (id, sale_id, synced) VALUES ('2', 'SALE-2', FALSE)");
            conn.commit();
        }
        assertEquals(2, salesService.getPendingSalesCount());

        // Insert a synced sale
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO sales (id, sale_id, synced) VALUES ('3', 'SALE-3', TRUE)");
            conn.commit();
        }
        // Count should remain 2
        assertEquals(2, salesService.getPendingSalesCount());

        // Mark one of the pending sales as synced
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE sales SET synced = TRUE WHERE id = '1'");
            conn.commit();
        }
        // Count should be 1
        assertEquals(1, salesService.getPendingSalesCount());
    }
}
