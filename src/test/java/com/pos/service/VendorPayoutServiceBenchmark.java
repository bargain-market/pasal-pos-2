package com.pos.service;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.model.VendorPayout;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class VendorPayoutServiceBenchmark {

    private DatabaseManager dbManager;
    private VendorPayoutService service;
    private Path tempDbDir;

    @Before
    public void setUp() throws IOException {
        // Create temp dir for DB
        tempDbDir = Files.createTempDirectory("pos-benchmark-db");
        String dbPath = tempDbDir.resolve("testdb").toAbsolutePath().toString();

        // Configure to use file-based database for benchmark to support AUTO_SERVER
        ConfigManager.getInstance().setProperty("database.url", "jdbc:h2:" + dbPath + ";DB_CLOSE_DELAY=-1");

        dbManager = DatabaseManager.getInstance();
        dbManager.initializeSchema();
        service = VendorPayoutService.getInstance();

        // Populate data
        populateData();
    }

    @After
    public void tearDown() throws IOException {
        // Close connections usually handled by pool or try-with-resources.
        // We can't easily force close all connections here, but we can try to clean up files.
        // H2 might hold locks.

        // Try to shutdown DB
        try (Connection conn = dbManager.getConnection()) {
             conn.createStatement().execute("SHUTDOWN");
        } catch (SQLException e) {
            // ignore
        }

        // Recursively delete temp dir
        if (Files.exists(tempDbDir)) {
             Files.walk(tempDbDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }

    private void populateData() {
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            // 1. Create 10 Vendors
            String vendorSql = "INSERT INTO vendors (id, name, is_active) VALUES (?, ?, TRUE)";
            try (PreparedStatement stmt = conn.prepareStatement(vendorSql)) {
                for (int i = 0; i < 10; i++) {
                    stmt.setString(1, "vendor-" + i);
                    stmt.setString(2, "Vendor " + i);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }

            // 2. Create 100 Products (10 per vendor)
            String productSql = "INSERT INTO products (id, name, vendor_id, price, cost) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(productSql)) {
                for (int v = 0; v < 10; v++) {
                    for (int p = 0; p < 10; p++) {
                        stmt.setString(1, "prod-" + v + "-" + p);
                        stmt.setString(2, "Product " + v + "-" + p);
                        stmt.setString(3, "vendor-" + v);
                        stmt.setBigDecimal(4, new BigDecimal("10.00"));
                        stmt.setBigDecimal(5, new BigDecimal("5.00"));
                        stmt.addBatch();
                    }
                }
                stmt.executeBatch();
            }

            // 3. Create 1000 Sales (randomly distributed)
            String saleSql = "INSERT INTO sales (id, sale_id, timestamp, voided) VALUES (?, ?, ?, FALSE)";
            String saleItemSql = "INSERT INTO sale_items (sale_id, product_id, quantity, subtotal, cost, vendor_id) VALUES (?, ?, ?, ?, ?, ?)";

            LocalDate today = LocalDate.now();
            String timestamp = today.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            try (PreparedStatement saleStmt = conn.prepareStatement(saleSql);
                 PreparedStatement itemStmt = conn.prepareStatement(saleItemSql)) {

                for (int s = 0; s < 1000; s++) {
                    String saleId = "sale-" + s;
                    saleStmt.setString(1, saleId);
                    saleStmt.setString(2, saleId);
                    saleStmt.setString(3, timestamp);
                    saleStmt.addBatch();

                    // Add 1-5 items per sale
                    int numItems = 1 + (s % 5);
                    for (int i = 0; i < numItems; i++) {
                        int v = (s + i) % 10;
                        int p = (s + i) % 10;
                        String prodId = "prod-" + v + "-" + p;

                        itemStmt.setString(1, saleId);
                        itemStmt.setString(2, prodId);
                        itemStmt.setInt(3, 1);
                        itemStmt.setBigDecimal(4, new BigDecimal("10.00"));
                        itemStmt.setBigDecimal(5, new BigDecimal("5.00"));
                        itemStmt.setString(6, "vendor-" + v);
                        itemStmt.addBatch();
                    }
                }
                saleStmt.executeBatch();
                itemStmt.executeBatch();
            }

            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to populate benchmark data", e);
        }
    }

    @Test
    public void benchmarkCalculateAllVendorPayouts() {
        LocalDate today = LocalDate.now();

        long startTime = System.nanoTime();

        List<VendorPayout> payouts = service.calculateAllVendorPayouts(today, today);

        long endTime = System.nanoTime();
        double durationMs = (endTime - startTime) / 1_000_000.0;

        System.out.println("Benchmark Result: " + durationMs + " ms");
        System.out.println("Payouts calculated: " + payouts.size());

        assertNotNull(payouts);
        assertFalse(payouts.isEmpty());
    }
}
