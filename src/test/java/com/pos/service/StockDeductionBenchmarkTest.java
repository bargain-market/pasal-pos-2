package com.pos.service;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.model.Product;
import com.pos.model.SaleItem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

public class StockDeductionBenchmarkTest {

    private DatabaseManager dbManager;
    private ConfigManager configManager;

    @Before
    public void setUp() throws Exception {
        System.setProperty("pasal.test.mode", "true");

        // Setup in-memory DB
        configManager = ConfigManager.getInstance();
        Field propertiesField = ConfigManager.class.getDeclaredField("properties");
        propertiesField.setAccessible(true);
        Properties properties = (Properties) propertiesField.get(configManager);

        // Use in-memory DB
        properties.setProperty("database.url", "jdbc:h2:mem:stock_bench_db;DB_CLOSE_DELAY=-1");
        properties.setProperty("database.user", "sa");
        properties.setProperty("database.password", "");
        properties.setProperty("store.id", "STORE-123");
        properties.setProperty("device.id", "DEVICE-123");
        properties.setProperty("pos.user.isPosUser", "true");
        properties.setProperty("pos.user.id", "USER-123");
        properties.setProperty("pos.user.username", "benchmark_user");
        properties.setProperty("pos.user.fullName", "Benchmark User");
        properties.setProperty("pos.user.token", "dummy_token");

        dbManager = DatabaseManager.getInstance();
        dbManager.initializeSchema();

        // Clear data just in case
        try (Connection conn = dbManager.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM products");
            stmt.execute("DELETE FROM shifts");
            stmt.execute("DELETE FROM pos_users");
            conn.commit();
        }

        // Insert dummy user
        try (Connection conn = dbManager.getConnection()) {
            String sql = "INSERT INTO pos_users (id, username, pin_hash, full_name, store_id, is_active, role) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, "USER-123");
                stmt.setString(2, "benchmark_user");
                stmt.setString(3, "hash");
                stmt.setString(4, "Benchmark User");
                stmt.setString(5, "STORE-123");
                stmt.setBoolean(6, true);
                stmt.setString(7, "Manager");
                stmt.executeUpdate();
            }
            conn.commit();
        }

        // Reload session to ensure services pick up the user
        // UserAuthService constructor loads session from config

        // Start a shift
        ShiftService.getInstance().startShift("Benchmark User", "REG-1", BigDecimal.valueOf(100), "Start");
    }

    @After
    public void tearDown() throws Exception {
        InventoryService.getInstance().awaitPendingStockSync(10_000);
        try (Connection conn = dbManager.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    public void testProcessSalePerformance() throws SQLException {
        // Create 200 products
        List<Product> products = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {
            String sql = "INSERT INTO products (id, name, sku, barcode, price, stock_quantity, status) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (int i = 0; i < 200; i++) {
                    String id = UUID.randomUUID().toString();
                    String name = "Product " + i;
                    String sku = "SKU-" + i;
                    String barcode = "BAR-" + i;
                    stmt.setString(1, id);
                    stmt.setString(2, name);
                    stmt.setString(3, sku);
                    stmt.setString(4, barcode);
                    stmt.setBigDecimal(5, BigDecimal.valueOf(10.00));
                    stmt.setInt(6, 1000); // Plenty of stock
                    stmt.setString(7, "IN_STOCK");
                    stmt.addBatch();

                    products.add(new Product(id, sku, barcode, name, BigDecimal.valueOf(10.00), 1000, null, null, null, null));
                }
                stmt.executeBatch();
                conn.commit();
            }
        }

        // Create sale items
        List<SaleItem> items = new ArrayList<>();
        for (Product p : products) {
            items.add(new SaleItem(p, 1));
        }

        SalesService salesService = SalesService.getInstance();
        BigDecimal total = BigDecimal.valueOf(2000.00); // 200 items * $10

        // Warm up
        // processSale(items.subList(0, 5), "CASH", BigDecimal.valueOf(50), "Benchmark User");

        long startTime = System.nanoTime();

        // Process sale with 200 items
        String saleId = salesService.processSaleAndGetId(items, "CASH", total, "Benchmark User", BigDecimal.valueOf(2000.00), BigDecimal.ZERO);

        long endTime = System.nanoTime();
        double durationMs = (endTime - startTime) / 1_000_000.0;

        System.out.printf("ProcessSale with %d items took: %.2f ms%n", items.size(), durationMs);
        org.junit.Assert.assertTrue(
                "Sale with " + items.size() + " items should complete within 5s, took " + durationMs + "ms",
                durationMs < 5000);
        org.junit.Assert.assertNotNull("Sale ID should be returned", saleId);
    }

    @Test
    public void testProcessSaleSmallCartPerformance() throws SQLException {
        List<Product> products = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {
            String sql = "INSERT INTO products (id, name, sku, barcode, price, stock_quantity, status, department_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (int i = 0; i < 20; i++) {
                    String id = UUID.randomUUID().toString();
                    String deptId = "DEPT-" + (i % 5);
                    stmt.setString(1, id);
                    stmt.setString(2, "Perf Product " + i);
                    stmt.setString(3, "SKU-P-" + i);
                    stmt.setString(4, "BAR-P-" + i);
                    stmt.setBigDecimal(5, BigDecimal.valueOf(5.00));
                    stmt.setInt(6, 500);
                    stmt.setString(7, "IN_STOCK");
                    stmt.setString(8, deptId);
                    stmt.addBatch();
                    products.add(new Product(id, "SKU-P-" + i, "BAR-P-" + i, "Perf Product " + i,
                            BigDecimal.valueOf(5.00), 500, null, deptId, null, null));
                }
                stmt.executeBatch();
                conn.commit();
            }
        }

        List<SaleItem> items = new ArrayList<>();
        for (Product p : products) {
            items.add(new SaleItem(p, 1));
        }

        SalesService salesService = SalesService.getInstance();
        BigDecimal total = BigDecimal.valueOf(100.00);

        long startTime = System.nanoTime();
        String saleId = salesService.processSaleAndGetId(items, "CASH", total, "Benchmark User",
                BigDecimal.valueOf(100.00), BigDecimal.ZERO);
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        System.out.printf("ProcessSale with %d items (5 departments) took: %d ms%n", items.size(), durationMs);
        org.junit.Assert.assertNotNull(saleId);
        org.junit.Assert.assertTrue(
                "Small cart sale should complete within 2s, took " + durationMs + "ms",
                durationMs < 2000);
    }
}
