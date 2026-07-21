package com.pos.sync.outbound;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.api.dto.SaleSubmission;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;


import static org.junit.Assert.assertTrue;

public class SalesOutboundSyncTest {

    private DatabaseManager dbManager;

    @Before
    public void setUp() throws Exception {
        // Use in-memory database for testing
        ConfigManager.getInstance().setProperty("database.url", "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        dbManager = DatabaseManager.getInstance();
        dbManager.initializeSchema();

        // Clear tables to ensure clean state
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM sale_items");
            stmt.execute("DELETE FROM sales");
            stmt.execute("DELETE FROM products");
            conn.commit();
        }
    }

    @After
    public void tearDown() throws Exception {
         try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM sale_items");
            stmt.execute("DELETE FROM sales");
            stmt.execute("DELETE FROM products");
            conn.commit();
        }
    }

    @Test
    public void testGetPendingSales_skipsMissingProductId() throws Exception {
        // Setup scenarios:
        // 1. Sale with valid product -> Should be included
        // 2. Sale with missing productId but valid SKU -> Currently skipped, should be included after fix
        
        // Insert a product for the valid case
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO products (id, sku, barcode, name, price, stock_quantity, department_id) " +
                    "VALUES ('prod-1', 'SKU-1', '123456', 'Valid Product', 10.00, 100, 'dept-1')");
            conn.commit();
        }

        // Insert sale 1 (Valid)
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO sales (id, sale_id, subtotal, discount, tax, total, payment_method, cashier_name, timestamp, synced) " +
                    "VALUES ('sale-1', 'SALE-1', 10.00, 0.00, 0.00, 10.00, 'CASH', 'Cashier', '2023-01-01T00:00:00Z', FALSE)");
            
            stmt.execute("INSERT INTO sale_items (sale_id, product_id, sku, name, price, quantity, subtotal, department_id, department_name) " +
                    "VALUES ('SALE-1', 'prod-1', 'SKU-1', 'Valid Product', 10.00, 1, 10.00, 'dept-1', 'Dept')");
            conn.commit();
        }

        // Insert sale 2 (Missing productId, Invalid currently)
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO sales (id, sale_id, subtotal, discount, tax, total, payment_method, cashier_name, timestamp, synced) " +
                    "VALUES ('sale-2', 'SALE-2', 20.00, 0.00, 0.00, 20.00, 'CASH', 'Cashier', '2023-01-01T00:00:00Z', FALSE)");
            
            // NOTE: product_id is NULL here. This simulates the issue.
            stmt.execute("INSERT INTO sale_items (sale_id, product_id, sku, name, price, quantity, subtotal, department_id, department_name) " +
                    "VALUES ('SALE-2', NULL, 'SKU-1', 'Valid Product', 20.00, 1, 20.00, 'dept-1', 'Dept')");
            conn.commit();
        }

        SalesOutboundSync sync = SalesOutboundSync.getInstance();
        
        // Use reflection to access private method getPendingSales
        Method getPendingSalesMethod = SalesOutboundSync.class.getDeclaredMethod("getPendingSales", int.class);
        getPendingSalesMethod.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        List<SaleSubmission> pendingSales = (List<SaleSubmission>) getPendingSalesMethod.invoke(sync, 50);

        // BEFORE FIX: Only SALE-1 should be returned. SALE-2 is skipped because validated fails.
        // We actually EXPECT this test to show the current buggy behavior first, detecting only 1 sale.
        // After fix, we expect 2 sales.
        
        boolean foundSale1 = pendingSales.stream().anyMatch(s -> "SALE-1".equals(s.saleId));
        boolean foundSale2 = pendingSales.stream().anyMatch(s -> "SALE-2".equals(s.saleId));
        
        assertTrue("Should contain valid SALE-1", foundSale1);
        
        // AFTER FIX: SALE-2 should also be returned because productId can be recovered from SKU.
        assertTrue("Should contain SALE-2 with recovered productId", foundSale2);
    }
}
