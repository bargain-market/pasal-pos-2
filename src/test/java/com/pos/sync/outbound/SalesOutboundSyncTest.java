package com.pos.sync.outbound;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.api.dto.SaleSubmission;
import com.pos.api.ApiClient;
import com.pos.api.dto.BatchSaleRequest;
import com.pos.api.dto.BatchSaleResponse;
import com.pos.sync.SyncResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;


import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

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
    public void validSaleBehindFiftyInvalidSalesStillUploads() throws Exception {
        seedQueue(false);
        Method method = SalesOutboundSync.class.getDeclaredMethod("getPendingSales", int.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SaleSubmission> pending = (List<SaleSubmission>) method.invoke(SalesOutboundSync.getInstance(), 50);
        assertTrue("Invalid older sales must not block newer valid sales", pending.stream().anyMatch(s -> "QUEUE-50".equals(s.saleId)));
    }

    private void seedQueue(boolean allValid) throws Exception {
        seedQueue(allValid, 51);
    }

    private void seedQueue(boolean allValid, int count) throws Exception {
        try (Connection conn = dbManager.getConnection(); Statement stmt = conn.createStatement()) {
            for (int i = 0; i < count; i++) {
                stmt.execute("INSERT INTO sales (id, sale_id, subtotal, discount, tax, total, payment_method, cashier_name, timestamp, synced, created_at) " +
                        "VALUES ('queue-" + i + "', 'QUEUE-" + i + "', 10, 0, 0, 10, 'CASH', 'Cashier', '2026-09-08T12:00:00Z', FALSE, DATEADD('SECOND', " + i + ", TIMESTAMP '2026-09-08 12:00:00'))");
            }
            stmt.execute("INSERT INTO products (id, sku, name, price, stock_quantity) VALUES ('queue-product', 'QUEUE-SKU', 'Item', 10, 100)");
            for (int i = allValid ? 0 : count - 1; i < count; i++) {
                stmt.execute("INSERT INTO sale_items (sale_id, product_id, sku, name, price, quantity, subtotal) VALUES ('QUEUE-" + i + "', 'queue-product', 'QUEUE-SKU', 'Item', 10, 1, 10)");
            }
            conn.commit();
        }
    }

    @Test
    public void rejectedBatchDoesNotBlockLaterSalesAndRetriesNextRun() throws Exception {
        seedQueue(true);
        ApiClient client = mock(ApiClient.class);
        var constructor = SalesOutboundSync.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        SalesOutboundSync sync = constructor.newInstance();
        var apiField = SalesOutboundSync.class.getDeclaredField("apiClient");
        apiField.setAccessible(true);
        apiField.set(sync, client);
        java.util.concurrent.atomic.AtomicBoolean rejectOlder = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(client.post(eq("/pos/sales/batch"), any(BatchSaleRequest.class), eq(BatchSaleResponse.class)))
                .thenAnswer(invocation -> {
                    BatchSaleRequest request = invocation.getArgument(1);
                    BatchSaleResponse response = new BatchSaleResponse();
                    response.results = new java.util.ArrayList<>();
                    for (SaleSubmission sale : request.sales) {
                        BatchSaleResponse.BatchSaleResult result = new BatchSaleResponse.BatchSaleResult();
                        result.saleId = sale.saleId;
                        boolean rejected = rejectOlder.get() && !"QUEUE-50".equals(sale.saleId);
                        result.status = rejected ? "failed" : "created";
                        result.error = rejected ? "Sale validation failed" : null;
                        if (rejected) response.failed++; else response.processed++;
                        response.results.add(result);
                    }
                    return new ApiClient.ApiResponse<>(response, 200);
                });
        SyncResult first = sync.sync();
        assertEquals(1, first.getSynced());
        assertEquals(50, first.getFailed());
        assertEquals("Rejected sales must remain pending", 50, sync.getPendingSalesCount());
        rejectOlder.set(false);
        SyncResult retry = sync.sync();
        assertEquals(50, retry.getSynced());
        assertEquals(0, sync.getPendingSalesCount());
        verify(client, times(3)).post(eq("/pos/sales/batch"), any(BatchSaleRequest.class), eq(BatchSaleResponse.class));
    }

    @Test
    public void drains5039SalesInBoundedBatches() throws Exception {
        seedQueue(true, 5039);
        ApiClient client = mock(ApiClient.class);
        var constructor = SalesOutboundSync.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        SalesOutboundSync sync = constructor.newInstance();
        var apiField = SalesOutboundSync.class.getDeclaredField("apiClient");
        apiField.setAccessible(true);
        apiField.set(sync, client);
        java.util.Set<String> accepted = new java.util.HashSet<>();
        when(client.post(eq("/pos/sales/batch"), any(BatchSaleRequest.class), eq(BatchSaleResponse.class)))
                .thenAnswer(invocation -> {
                    BatchSaleRequest request = invocation.getArgument(1);
                    assertTrue(request.sales.size() <= 50);
                    BatchSaleResponse response = new BatchSaleResponse();
                    response.results = new java.util.ArrayList<>();
                    for (SaleSubmission sale : request.sales) {
                        assertTrue("Each sale uploaded once", accepted.add(sale.saleId));
                        BatchSaleResponse.BatchSaleResult result = new BatchSaleResponse.BatchSaleResult();
                        result.saleId = sale.saleId;
                        result.status = "created";
                        response.results.add(result);
                        response.processed++;
                    }
                    return new ApiClient.ApiResponse<>(response, 200);
                });
        assertEquals(5039, sync.sync().getSynced());
        assertEquals(0, sync.getPendingSalesCount());
        verify(client, times(101)).post(eq("/pos/sales/batch"), any(BatchSaleRequest.class), eq(BatchSaleResponse.class));
    }

    @Test
    public void networkFailureStopsRunAndPreservesAllSales() throws Exception {
        seedQueue(true);
        ApiClient client = mock(ApiClient.class);
        var constructor = SalesOutboundSync.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        SalesOutboundSync sync = constructor.newInstance();
        var apiField = SalesOutboundSync.class.getDeclaredField("apiClient");
        apiField.setAccessible(true);
        apiField.set(sync, client);
        when(client.post(eq("/pos/sales/batch"), any(BatchSaleRequest.class), eq(BatchSaleResponse.class)))
                .thenThrow(new ApiClient.ApiException("Network unavailable"));
        SyncResult result = sync.sync();
        assertEquals(0, result.getSynced());
        assertEquals(51, sync.getPendingSalesCount());
        verify(client, times(1)).post(eq("/pos/sales/batch"), any(BatchSaleRequest.class), eq(BatchSaleResponse.class));
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
