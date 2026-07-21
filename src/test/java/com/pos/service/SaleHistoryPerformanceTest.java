package com.pos.service;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class SaleHistoryPerformanceTest {

    private DatabaseManager dbManager;

    @Before
    public void setUp() throws Exception {
        // Use reflection to switch database URL to in-memory H2
        ConfigManager configManager = ConfigManager.getInstance();
        Field propertiesField = ConfigManager.class.getDeclaredField("properties");
        propertiesField.setAccessible(true);
        Properties properties = (Properties) propertiesField.get(configManager);
        // Use a named in-memory database to keep content across connections within the same VM
        // DB_CLOSE_DELAY=-1 keeps the DB alive until the VM exits or we explicitly close it
        properties.setProperty("database.url", "jdbc:h2:mem:perf_test_db;DB_CLOSE_DELAY=-1");
        properties.setProperty("database.user", "sa");
        properties.setProperty("database.password", "");

        dbManager = DatabaseManager.getInstance();
        dbManager.initializeSchema();

        populateDatabase();
    }

    @After
    public void tearDown() throws Exception {
        try (Connection conn = dbManager.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("DROP ALL OBJECTS");
        }
    }

    private void populateDatabase() throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            // Insert 100 sales
            String insertSaleSQL = "INSERT INTO sales (id, sale_id, subtotal, discount, tax, total, created_at, cashier_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            String insertItemSQL = "INSERT INTO sale_items (sale_id, product_id, sku, name, price, quantity, subtotal) VALUES (?, ?, ?, ?, ?, ?, ?)";

            try (PreparedStatement saleStmt = conn.prepareStatement(insertSaleSQL);
                 PreparedStatement itemStmt = conn.prepareStatement(insertItemSQL)) {

                for (int i = 0; i < 1000; i++) {
                    String saleId = UUID.randomUUID().toString();

                    saleStmt.setString(1, UUID.randomUUID().toString());
                    saleStmt.setString(2, saleId);
                    saleStmt.setBigDecimal(3, new BigDecimal("100.00"));
                    saleStmt.setBigDecimal(4, BigDecimal.ZERO);
                    saleStmt.setBigDecimal(5, new BigDecimal("10.00"));
                    saleStmt.setBigDecimal(6, new BigDecimal("110.00"));
                    saleStmt.setObject(7, java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(i % 10)));
                    saleStmt.setString(8, "Cashier " + (i % 5));
                    saleStmt.addBatch();

                    // Add 5 items per sale
                    for (int j = 0; j < 5; j++) {
                        itemStmt.setString(1, saleId);
                        itemStmt.setString(2, "PROD-" + j);
                        itemStmt.setString(3, "SKU-" + j);
                        itemStmt.setString(4, "Product " + j);
                        itemStmt.setBigDecimal(5, new BigDecimal("20.00"));
                        itemStmt.setInt(6, 1);
                        itemStmt.setBigDecimal(7, new BigDecimal("20.00"));
                        itemStmt.addBatch();
                    }
                }

                saleStmt.executeBatch();
                itemStmt.executeBatch();
            }

            conn.commit();
        }
    }

    @Test
    public void testSearchSalesPerformance() throws SQLException {
        SaleHistoryService service = SaleHistoryService.getInstance();
        SaleHistoryService.SearchCriteria criteria = new SaleHistoryService.SearchCriteria();
        criteria.limit = 1000; // Get all

        long startTime = System.nanoTime();
        List<SaleHistoryService.SaleRecord> results = service.searchSales(criteria);
        long endTime = System.nanoTime();

        System.out.printf("SearchSales took: %.2f ms%n", (endTime - startTime) / 1_000_000.0);

        assertEquals(1000, results.size());
        for (SaleHistoryService.SaleRecord sale : results) {
            assertEquals(5, sale.items.size());
        }
    }
}
