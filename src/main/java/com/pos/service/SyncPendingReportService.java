package com.pos.service;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.sync.PendingOutboundBreakdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes human-readable sync pending / failure reports under
 * Documents/Pasal POS 2/sync logs (see {@link ConfigManager#getSyncLogsDirectory()}).
 */
public class SyncPendingReportService {

    private static final Logger logger = LoggerFactory.getLogger(SyncPendingReportService.class);
    private static final int MAX_SAMPLES = 20;
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DISPLAY_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static SyncPendingReportService instance;

    private final DatabaseManager dbManager;
    private final ConfigManager config;

    private SyncPendingReportService() {
        this.dbManager = DatabaseManager.getInstance();
        this.config = ConfigManager.getInstance();
    }

    public static synchronized SyncPendingReportService getInstance() {
        if (instance == null) {
            instance = new SyncPendingReportService();
        }
        return instance;
    }

    /**
     * Summary of one manual / forced sync run for the report file.
     */
    public static class SyncRunSummary {
        public boolean online;
        public int synced;
        public int failed;
        public int roundsExecuted;
        public PendingOutboundBreakdown breakdownBefore;
        public PendingOutboundBreakdown breakdownAfter;
        public String handlerErrors;
        public String note;
    }

    public static class SaleFailureSample {
        public final String saleId;
        public final String error;

        public SaleFailureSample(String saleId, String error) {
            this.saleId = saleId;
            this.error = error;
        }
    }

    public static class PendingRequestSample {
        public final String id;
        public final String endpoint;
        public final String method;
        public final int retryCount;
        public final String lastError;

        public PendingRequestSample(String id, String endpoint, String method, int retryCount, String lastError) {
            this.id = id;
            this.endpoint = endpoint;
            this.method = method;
            this.retryCount = retryCount;
            this.lastError = lastError;
        }
    }

    /**
     * Write report when sync did not fully clear pending or had failures.
     *
     * @return absolute path to latest report file, or null if write failed
     */
    public String writeReport(SyncRunSummary summary) {
        if (summary == null) {
            return null;
        }

        String body = buildReportBody(summary);
        Path logsDir = config.getSyncFailureLogsDirectory();
        Path latest = logsDir.resolve("sync-failure-report.txt");
        Path archived = logsDir.resolve("sync-failure-report-" + LocalDateTime.now().format(FILE_TS) + ".txt");

        try {
            Files.writeString(latest, body, StandardCharsets.UTF_8);
            Files.writeString(archived, body, StandardCharsets.UTF_8);
            logger.info("Sync failure report written to {}", latest.toAbsolutePath());
            return latest.toAbsolutePath().toString();
        } catch (IOException e) {
            logger.error("Failed to write sync pending report", e);
            return null;
        }
    }

    private String buildReportBody(SyncRunSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Pasal POS 2 — Sync Failure Report ===\n");
        sb.append("Generated: ").append(LocalDateTime.now().format(DISPLAY_TS)).append("\n");
        sb.append("Online: ").append(summary.online).append("\n\n");

        sb.append("--- Sync run ---\n");
        sb.append("Synced: ").append(summary.synced).append("\n");
        sb.append("Failed: ").append(summary.failed).append("\n");
        sb.append("Rounds executed: ").append(summary.roundsExecuted).append("\n");
        if (summary.note != null && !summary.note.isBlank()) {
            sb.append("Note: ").append(summary.note).append("\n");
        }
        if (summary.handlerErrors != null && !summary.handlerErrors.isBlank()) {
            sb.append("Handler errors: ").append(summary.handlerErrors).append("\n");
        }
        sb.append("\n");

        sb.append("--- Pending breakdown (before) ---\n");
        appendBreakdown(sb, summary.breakdownBefore);
        sb.append("\n--- Pending breakdown (after) ---\n");
        appendBreakdown(sb, summary.breakdownAfter);
        sb.append("\n");

        sb.append("--- Sample failed sales (sync_error) ---\n");
        List<SaleFailureSample> sales = collectSaleFailureSamples();
        if (sales.isEmpty()) {
            sb.append("(none with sync_error)\n");
        } else {
            for (SaleFailureSample s : sales) {
                sb.append("  ").append(s.saleId).append(": ").append(s.error).append("\n");
            }
        }
        sb.append("\n");

        sb.append("--- Sample pending API requests ---\n");
        List<PendingRequestSample> requests = collectPendingRequestSamples();
        if (requests.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (PendingRequestSample r : requests) {
                sb.append("  ").append(r.method).append(" ").append(r.endpoint)
                        .append(" retries=").append(r.retryCount);
                if (r.lastError != null && !r.lastError.isBlank()) {
                    sb.append(" error=").append(r.lastError);
                }
                sb.append("\n");
            }
        }
        sb.append("\n");

        PendingOutboundBreakdown after = summary.breakdownAfter;
        if (after != null && after.cartCancellations > 0) {
            sb.append("--- Cart cancellations ---\n");
            sb.append("  ").append(after.cartCancellations)
                    .append(" still unsynced (check logs for /pos/cart-cancellations errors)\n\n");
        }

        sb.append("--- What counts as \"pending\" in Settings ---\n");
        sb.append("  sales (synced=false), shifts, products, departments,\n");
        sb.append("  pending_requests (retry_count < 5), cart_cancellations (synced=false)\n");

        return sb.toString();
    }

    private static void appendBreakdown(StringBuilder sb, PendingOutboundBreakdown b) {
        if (b == null) {
            sb.append("(unavailable)\n");
            return;
        }
        sb.append("  sales: ").append(b.sales).append("\n");
        sb.append("  shifts: ").append(b.shifts).append("\n");
        sb.append("  products: ").append(b.products).append("\n");
        sb.append("  departments: ").append(b.departments).append("\n");
        sb.append("  pending_requests: ").append(b.pendingRequests).append("\n");
        sb.append("  cart_cancellations: ").append(b.cartCancellations).append("\n");
        sb.append("  total: ").append(b.total()).append("\n");
    }

    public List<SaleFailureSample> collectSaleFailureSamples() {
        List<SaleFailureSample> samples = new ArrayList<>();
        String sql = """
                SELECT sale_id, sync_error FROM sales
                WHERE synced = FALSE AND sync_error IS NOT NULL AND sync_error <> ''
                ORDER BY created_at ASC
                LIMIT ?
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, MAX_SAMPLES);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    samples.add(new SaleFailureSample(
                            rs.getString("sale_id"),
                            rs.getString("sync_error")));
                }
            }
        } catch (SQLException e) {
            logger.debug("Could not collect sale failure samples: {}", e.getMessage());
        }
        return samples;
    }

    public List<PendingRequestSample> collectPendingRequestSamples() {
        List<PendingRequestSample> samples = new ArrayList<>();
        String sql = """
                SELECT id, endpoint, method, retry_count, last_error FROM pending_requests
                WHERE retry_count < 5
                ORDER BY priority DESC, created_at ASC
                LIMIT ?
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, MAX_SAMPLES);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    samples.add(new PendingRequestSample(
                            rs.getString("id"),
                            rs.getString("endpoint"),
                            rs.getString("method"),
                            rs.getInt("retry_count"),
                            rs.getString("last_error")));
                }
            }
        } catch (SQLException e) {
            logger.debug("Could not collect pending request samples: {}", e.getMessage());
        }
        return samples;
    }
}
