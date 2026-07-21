package com.pos.sync;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MarkAllPendingAsSyncedTest {

    private DatabaseManager dbManager;
    private SyncManager syncManager;

    @Before
    public void setUp() throws Exception {
        ConfigManager.getInstance().setProperty("database.url", "jdbc:h2:mem:markAllSyncedTest;DB_CLOSE_DELAY=-1");
        dbManager = DatabaseManager.getInstance();
        dbManager.initializeSchema();
        syncManager = SyncManager.getInstance();

        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM sales");
            stmt.execute("DELETE FROM pending_requests");
            conn.commit();
        }
    }

    @After
    public void tearDown() throws Exception {
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM sales");
            stmt.execute("DELETE FROM pending_requests");
            conn.commit();
        }
    }

    @Test
    public void markAllPendingAsSynced_clearsSalesAndPendingRequestsInBatch() throws Exception {
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO sales (id, sale_id, synced) VALUES ('1', 'SALE-1', FALSE)");
            stmt.execute("INSERT INTO sales (id, sale_id, synced) VALUES ('2', 'SALE-2', FALSE)");
            stmt.execute("""
                    INSERT INTO pending_requests (id, endpoint, method, payload, priority, retry_count)
                    VALUES ('req-1', '/test', 'POST', '{}', 1, 0)
                    """);
            conn.commit();
        }

        assertEquals(2, syncManager.getPendingOutboundBreakdown().sales);
        assertEquals(1, syncManager.getPendingOutboundBreakdown().pendingRequests);

        MarkAllAsSyncedResult result = syncManager.markAllPendingAsSynced();

        assertEquals(2, result.sales);
        assertEquals(1, result.pendingRequestsDeleted);
        assertEquals(0, syncManager.getPendingOutboundCount());
        assertTrue(result.breakdownAfter.total() == 0);
    }
}
