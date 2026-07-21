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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SaleHistoryServiceTest {

    private DatabaseManager dbManager;

    @Before
    public void setUp() throws Exception {
        ConfigManager configManager = ConfigManager.getInstance();
        Field propertiesField = ConfigManager.class.getDeclaredField("properties");
        propertiesField.setAccessible(true);
        Properties properties = (Properties) propertiesField.get(configManager);
        properties.setProperty("database.url", "jdbc:h2:mem:sale_history_test_db;DB_CLOSE_DELAY=-1");
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

    @Test
    public void searchSalesIncludesRefundTransactions() throws Exception {
        SaleHistoryService service = SaleHistoryService.getInstance();
        SaleHistoryService.SearchCriteria criteria = new SaleHistoryService.SearchCriteria();
        criteria.limit = 10;

        List<SaleHistoryService.SaleRecord> results = service.searchSales(criteria);

        assertEquals(2, results.size());
        assertTrue(results.get(0).isRefund());
        assertEquals("REFUND-200", results.get(0).saleId);
        assertEquals("SALE-100", results.get(0).originalSaleId);
        assertEquals(1, results.get(0).items.size());
        assertEquals(1, results.get(0).items.get(0).quantity);
        assertEquals(0, results.get(0).items.get(0).subtotal.compareTo(new BigDecimal("10.00")));

        assertFalse(results.get(1).isRefund());
        assertEquals("SALE-100", results.get(1).saleId);
        assertEquals(1, results.get(1).items.size());
    }

    @Test
    public void searchBySaleIdReturnsSaleAndRelatedRefund() throws Exception {
        SaleHistoryService service = SaleHistoryService.getInstance();
        SaleHistoryService.SearchCriteria criteria = new SaleHistoryService.SearchCriteria();
        criteria.saleId = "SALE-100";
        criteria.limit = 10;

        List<SaleHistoryService.SaleRecord> results = service.searchSales(criteria);

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(record -> !record.isRefund() && "SALE-100".equals(record.saleId)));
        assertTrue(results.stream().anyMatch(record -> record.isRefund() && "REFUND-200".equals(record.saleId)));
    }

    @Test
    public void getSaleBySaleIdReturnsOriginalSaleOnly() throws Exception {
        SaleHistoryService service = SaleHistoryService.getInstance();

        SaleHistoryService.SaleRecord sale = service.getSaleBySaleId("SALE-100");

        assertNotNull(sale);
        assertFalse(sale.isRefund());
        assertEquals("SALE-100", sale.saleId);
    }

    private void populateDatabase() throws Exception {
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            String saleSql = """
                    INSERT INTO sales (id, sale_id, subtotal, discount, tax, total, payment_method,
                                       cashier_name, cashier_id, pos_user_id, timestamp, synced, voided, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement stmt = conn.prepareStatement(saleSql)) {
                stmt.setString(1, UUID.randomUUID().toString());
                stmt.setString(2, "SALE-100");
                stmt.setBigDecimal(3, new BigDecimal("10.00"));
                stmt.setBigDecimal(4, BigDecimal.ZERO);
                stmt.setBigDecimal(5, new BigDecimal("1.00"));
                stmt.setBigDecimal(6, new BigDecimal("11.00"));
                stmt.setString(7, "CASH");
                stmt.setString(8, "Alice");
                stmt.setString(9, "cashier-1");
                stmt.setString(10, "user-1");
                stmt.setString(11, Instant.parse("2026-03-23T10:15:30Z").toString());
                stmt.setBoolean(12, true);
                stmt.setBoolean(13, false);
                stmt.setTimestamp(14, Timestamp.valueOf(LocalDateTime.of(2026, 3, 23, 10, 15, 30)));
                stmt.executeUpdate();
            }

            String saleItemSql = """
                    INSERT INTO sale_items (sale_id, product_id, sku, name, price, quantity, subtotal)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement stmt = conn.prepareStatement(saleItemSql)) {
                stmt.setString(1, "SALE-100");
                stmt.setString(2, "PROD-1");
                stmt.setString(3, "SKU-1");
                stmt.setString(4, "Product One");
                stmt.setBigDecimal(5, new BigDecimal("10.00"));
                stmt.setInt(6, 1);
                stmt.setBigDecimal(7, new BigDecimal("10.00"));
                stmt.executeUpdate();
            }

            String refundSql = """
                    INSERT INTO refunds (id, refund_id, original_sale_id, refund_amount, refund_tax, total_refund,
                                         payment_method, refund_method, reason, cashier_name, pos_user_id, timestamp,
                                         synced, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement stmt = conn.prepareStatement(refundSql)) {
                stmt.setString(1, UUID.randomUUID().toString());
                stmt.setString(2, "REFUND-200");
                stmt.setString(3, "SALE-100");
                stmt.setBigDecimal(4, new BigDecimal("10.00"));
                stmt.setBigDecimal(5, new BigDecimal("1.00"));
                stmt.setBigDecimal(6, new BigDecimal("11.00"));
                stmt.setString(7, "CASH");
                stmt.setString(8, "CARD");
                stmt.setString(9, "Customer return");
                stmt.setString(10, "Bob");
                stmt.setString(11, "user-2");
                stmt.setString(12, Instant.parse("2026-03-24T09:00:00Z").toString());
                stmt.setBoolean(13, true);
                stmt.setTimestamp(14, Timestamp.valueOf(LocalDateTime.of(2026, 3, 24, 9, 0, 0)));
                stmt.executeUpdate();
            }

            String refundItemSql = """
                    INSERT INTO refund_items (refund_id, product_id, sku, name, original_price, original_quantity,
                                              refund_quantity, refund_amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement stmt = conn.prepareStatement(refundItemSql)) {
                stmt.setString(1, "REFUND-200");
                stmt.setString(2, "PROD-1");
                stmt.setString(3, "SKU-1");
                stmt.setString(4, "Product One");
                stmt.setBigDecimal(5, new BigDecimal("10.00"));
                stmt.setInt(6, 1);
                stmt.setInt(7, 1);
                stmt.setBigDecimal(8, new BigDecimal("10.00"));
                stmt.executeUpdate();
            }

            conn.commit();
        }
    }
}
