package com.pos.service;

import com.pos.database.DatabaseManager;
import com.pos.model.Product;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.Assert.assertNotNull;

public class ProductSyncServicePerformanceTest {

    private ProductSyncService productSyncService;
    private DatabaseManager dbManager;

    @Before
    public void setUp() throws SQLException {
        dbManager = DatabaseManager.getInstance();
        dbManager.initializeSchema();
        dbManager.clearStoreData();

        productSyncService = ProductSyncService.getInstance();
        insertProducts(100);
    }

    @After
    public void tearDown() {
        dbManager.clearStoreData();
    }

    @Test
    public void testGetLocalProductByBarcodePerformance() throws SQLException {
        int iterations = 500;
        String barcode = "BARCODE-0";

        // Warmup
        productSyncService.getLocalProductByBarcode(barcode);

        long start = System.nanoTime();
        try (Connection conn = dbManager.getConnection()) {
            for (int i = 0; i < iterations; i++) {
                Product p = productSyncService.getLocalProductByBarcode(barcode, conn);
                assertNotNull(p);
            }
        }
        long end = System.nanoTime();

        double durationMs = (end - start) / 1_000_000.0;
        System.out.println("Calling getLocalProductByBarcode " + iterations + " times with REUSED connection took: " + durationMs + " ms");
        System.out.println("Average time per call: " + (durationMs / iterations) + " ms");
    }

    private void insertProducts(int count) throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            String sql = "INSERT INTO products (id, name, sku, barcode, price, stock_quantity, created_locally, synced) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (int i = 0; i < count; i++) {
                    stmt.setString(1, UUID.randomUUID().toString());
                    stmt.setString(2, "Product " + i);
                    stmt.setString(3, "SKU-" + i);
                    stmt.setString(4, "BARCODE-" + i);
                    stmt.setBigDecimal(5, new java.math.BigDecimal("10.00"));
                    stmt.setInt(6, 100);
                    stmt.setBoolean(7, true);
                    stmt.setBoolean(8, true);
                    stmt.addBatch();
                }
                stmt.executeBatch();
                conn.commit();
            }
        }
    }
}
