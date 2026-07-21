package com.pos.util;

import com.pos.database.DatabaseManager;
import com.pos.sync.SyncManager;
import com.pos.sync.SyncResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class SyncCheck {
    public static void main(String[] args) {
        try {
            // Wait for DB to be ready for connections
            Thread.sleep(2000);

            SyncManager syncManager = SyncManager.getInstance();
            syncManager.checkConnectivity();
            System.out.println("IS_ONLINE:" + syncManager.isOnline());

            DatabaseManager dbManager = DatabaseManager.getInstance();
            int count = 0;
            try (Connection conn = dbManager.getConnection()) {
                String sql = "SELECT COUNT(*) FROM products";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        count = rs.getInt(1);
                    }
                }
            }

            System.out.println("INITIAL_PRODUCT_COUNT:" + count);

            if (count == 0) {
                System.out.println("COUNT_IS_ZERO, triggering manual inbound sync...");
                SyncResult result = syncManager.performFullInboundSync();
                System.out.println("SYNC_RESULT: " + result.getSynced() + " synced, success: " + result.isSuccess()
                        + ", error: " + result.getError());
            }

            try (Connection conn = dbManager.getConnection()) {
                String sql = "SELECT COUNT(*) FROM products";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        System.out.println("FINAL_PRODUCT_COUNT:" + rs.getInt(1));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
