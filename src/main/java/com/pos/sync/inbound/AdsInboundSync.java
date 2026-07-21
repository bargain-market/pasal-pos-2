package com.pos.sync.inbound;

import com.pos.api.ApiClient;
import com.pos.database.DatabaseManager;
import com.pos.model.Ad;
import com.pos.sync.SyncDirection;
import com.pos.sync.SyncManager;
import com.pos.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Inbound sync handler for customer display ads.
 * Pulls ad data from backend and stores in local database.
 * 
 * <h2>Sync Flow</h2>
 * 
 * <pre>
 * Backend API ──────────────────────────────────────────────────────────────
 *     │
 *     │ GET /pos/ads
 *     │
 *     ▼
 * AdsInboundSync ───────────────────────────────────────────────────────────
 *     │
 *     │ Parse response
 *     │ Store ads locally
 *     │
 *     ▼
 * Local H2 Database ────────────────────────────────────────────────────────
 *     • ads table
 * </pre>
 * 
 * <h2>Conflict Resolution</h2>
 * Backend always wins. Local ad data is completely replaced by backend data.
 * This is a one-way sync (backend → local only).
 */
public class AdsInboundSync implements SyncManager.InboundSyncHandler {
    private static final Logger logger = LoggerFactory.getLogger(AdsInboundSync.class);
    
    private static AdsInboundSync instance;
    
    private final ApiClient apiClient;
    private final DatabaseManager dbManager;
    
    private AdsInboundSync() {
        this.apiClient = ApiClient.getInstance();
        this.dbManager = DatabaseManager.getInstance();
    }
    
    public static synchronized AdsInboundSync getInstance() {
        if (instance == null) {
            instance = new AdsInboundSync();
        }
        return instance;
    }
    
    @Override
    public String getName() {
        return "AdsSync";
    }
    
    @Override
    public SyncResult sync(String lastSyncTime) throws Exception {
        logger.info("Starting ads sync");
        
        try {
            // Call backend API to get ads
            ApiClient.ApiResponse<AdsSyncResponse> response = apiClient.get(
                "/pos/ads",
                AdsSyncResponse.class
            );
            
            AdsSyncResponse syncData = response.getData();
            
            if (syncData == null) {
                logger.warn("Empty ads response from backend");
                return SyncResult.empty(SyncDirection.INBOUND);
            }
            
            int adsStored = 0;
            
            // Store ads
            if (syncData.ads != null && !syncData.ads.isEmpty()) {
                adsStored = storeAds(syncData.ads);
                logger.info("Synced {} ads", adsStored);
            } else {
                // Clear local ads if backend has none
                clearLocalAds();
                logger.info("No ads from backend, cleared local ads");
            }
            
            return SyncResult.success(SyncDirection.INBOUND, adsStored);
            
        } catch (ApiClient.ApiException e) {
            logger.error("Ads sync failed: {}", e.getMessage());
            return SyncResult.failure(SyncDirection.INBOUND, e.getMessage());
        }
    }
    
    /**
     * Store ads in local database.
     * 
     * <p>This performs a full replace of all local ads with the ads from backend.
     * The display_order field is preserved, which determines the rotation sequence
     * on the customer display. Ads are stored with all their metadata including
     * scheduling information (startDate, endDate) for proper filtering.
     */
    private int storeAds(List<AdSyncData> ads) throws SQLException {
        if (ads == null || ads.isEmpty()) {
            return 0;
        }
        
        try (Connection conn = dbManager.getConnection()) {
            // First, clear existing ads (full replace strategy)
            String deleteSql = "DELETE FROM ads";

            // H2 uses MERGE INTO for upsert, but we'll do full replace
            String insertSql = "INSERT INTO ads " +
                "(id, title, subtitle, image_url, video_url, ad_type, background_color, text_color, " +
                "display_order, is_active, start_date, end_date, updated_at, synced_at, " +
                "display_duration, text_alignment, title_font_size, subtitle_font_size, is_title_bold) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            int successCount = 0;

            try {
                conn.setAutoCommit(false);

                // Delete all existing ads
                try (Statement deleteStmt = conn.createStatement()) {
                    deleteStmt.execute(deleteSql);
                }

                // Insert new ads
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    String syncedAt = Instant.now().toString();

                    for (AdSyncData ad : ads) {
                        try {
                            if (ad.id == null || ad.title == null) {
                                logger.warn("Skipping ad with missing required fields: id={}, title={}",
                                        ad.id, ad.title);
                                continue;
                            }

                            stmt.setString(1, ad.id);
                            stmt.setString(2, ad.title);
                            stmt.setString(3, ad.subtitle);
                            stmt.setString(4, ad.imageUrl);
                            stmt.setString(5, ad.videoUrl);
                            stmt.setString(6, ad.adType != null ? ad.adType : "text");
                            stmt.setString(7, ad.backgroundColor != null ? ad.backgroundColor : "#e3f2fd");
                            stmt.setString(8, ad.textColor != null ? ad.textColor : "#1565c0");
                            // Display order determines rotation sequence (lower = earlier in rotation)
                            stmt.setInt(9, ad.displayOrder != null ? ad.displayOrder : 0);
                            stmt.setBoolean(10, ad.isActive != null ? ad.isActive : true);
                            stmt.setString(11, ad.startDate);
                            stmt.setString(12, ad.endDate);
                            stmt.setString(13, ad.updatedAt);
                            stmt.setString(14, syncedAt);
                            stmt.setInt(15, ad.displayDuration != null ? ad.displayDuration : 5);
                            stmt.setString(16, ad.textAlignment != null ? ad.textAlignment : "center");
                            stmt.setString(17, ad.titleFontSize != null ? ad.titleFontSize : "large");
                            stmt.setString(18, ad.subtitleFontSize != null ? ad.subtitleFontSize : "medium");
                            stmt.setBoolean(19, ad.isTitleBold != null ? ad.isTitleBold : true);
                            stmt.addBatch();

                        } catch (SQLException e) {
                            logger.warn("Error preparing ad {} for batch: {}", ad.id, e.getMessage());
                        }
                    }

                    // Execute batch
                    int[] results = stmt.executeBatch();
                    conn.commit();

                    // Count successful inserts
                    for (int result : results) {
                        if (result >= 0 || result == Statement.SUCCESS_NO_INFO) {
                            successCount++;
                        }
                    }
                }

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }

            return successCount;
        }
    }
    
    /**
     * Clear all local ads.
     */
    private void clearLocalAds() throws SQLException {
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM ads");
            conn.commit();
        }
    }
    
    /**
     * Get all active ads from local database.
     * 
     * <p>Ads are returned in display order (ascending), which determines
     * the rotation sequence on the customer display. Only ads that are
     * currently active and within their scheduled date range are included.
     * 
     * <p>The display order is set in the backend management interface and
     * synced to the POS system. Lower display_order values appear first
     * in the rotation.
     * 
     * @return List of active ads sorted by display_order, filtered by schedule
     */
    public List<Ad> getActiveAds() {
        List<Ad> ads = new ArrayList<>();
        
        try (Connection conn = dbManager.getConnection()) {
            // Order by display_order to ensure rotation follows the intended sequence
            String sql = "SELECT * FROM ads WHERE is_active = TRUE ORDER BY display_order ASC";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                int skippedCount = 0;
                
                while (rs.next()) {
                    Ad ad = new Ad();
                    ad.setId(rs.getString("id"));
                    ad.setTitle(rs.getString("title"));
                    ad.setSubtitle(rs.getString("subtitle"));
                    ad.setImageUrl(rs.getString("image_url"));
                    ad.setBackgroundColor(rs.getString("background_color"));
                    ad.setTextColor(rs.getString("text_color"));
                    ad.setDisplayOrder(rs.getInt("display_order"));
                    ad.setActive(rs.getBoolean("is_active"));
                    
                    // Video fields
                    try {
                        ad.setVideoUrl(rs.getString("video_url"));
                    } catch (SQLException e) {
                        ad.setVideoUrl(null); // Column may not exist in older schemas
                    }
                    try {
                        ad.setAdType(rs.getString("ad_type"));
                    } catch (SQLException e) {
                        // Determine ad type from URLs if column doesn't exist
                        if (ad.getVideoUrl() != null && !ad.getVideoUrl().isEmpty()) {
                            ad.setAdType("video");
                        } else if (ad.getImageUrl() != null && !ad.getImageUrl().isEmpty()) {
                            ad.setAdType("image");
                        } else {
                            ad.setAdType("text");
                        }
                    }
                    
                    String startDateStr = rs.getString("start_date");
                    if (startDateStr != null && !startDateStr.isEmpty()) {
                        ad.setStartDate(Instant.parse(startDateStr));
                    }
                    
                    String endDateStr = rs.getString("end_date");
                    if (endDateStr != null && !endDateStr.isEmpty()) {
                        ad.setEndDate(Instant.parse(endDateStr));
                    }
                    
                    String updatedAtStr = rs.getString("updated_at");
                    if (updatedAtStr != null && !updatedAtStr.isEmpty()) {
                        ad.setUpdatedAt(Instant.parse(updatedAtStr));
                    }
                    
                    // Set formatting fields (with defaults for backward compatibility)
                    try {
                        ad.setDisplayDuration(rs.getInt("display_duration"));
                    } catch (SQLException e) {
                        ad.setDisplayDuration(5); // Default if column doesn't exist
                    }
                    try {
                        ad.setTextAlignment(rs.getString("text_alignment"));
                    } catch (SQLException e) {
                        ad.setTextAlignment("center"); // Default
                    }
                    try {
                        ad.setTitleFontSize(rs.getString("title_font_size"));
                    } catch (SQLException e) {
                        ad.setTitleFontSize("large"); // Default
                    }
                    try {
                        ad.setSubtitleFontSize(rs.getString("subtitle_font_size"));
                    } catch (SQLException e) {
                        ad.setSubtitleFontSize("medium"); // Default
                    }
                    try {
                        ad.setTitleBold(rs.getBoolean("is_title_bold"));
                    } catch (SQLException e) {
                        ad.setTitleBold(true); // Default
                    }
                    
                    // Only include ads that should be displayed based on schedule
                    // This filters out ads that haven't started yet or have expired
                    if (ad.shouldDisplay()) {
                        ads.add(ad);
                    } else {
                        skippedCount++;
                        logger.debug("Skipping ad '{}' - outside scheduled date range", ad.getTitle());
                    }
                }
                
                if (skippedCount > 0) {
                    logger.debug("Filtered out {} ads outside scheduled date range", skippedCount);
                }
                
                if (!ads.isEmpty()) {
                    logger.debug("Loaded {} active ads in display order (first: '{}', order: {})",
                            ads.size(), ads.get(0).getTitle(), ads.get(0).getDisplayOrder());
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting active ads", e);
        }
        
        return ads;
    }
    
    /**
     * Get local ad count (for diagnostics).
     */
    public int getLocalAdCount() {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT COUNT(*) FROM ads";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting ad count", e);
        }
        return 0;
    }
    
    /**
     * Response DTO for ads sync from backend.
     */
    public static class AdsSyncResponse {
        public List<AdSyncData> ads;
        public String syncTimestamp;
        public Integer totalAds;
    }
    
    /**
     * DTO for individual ad data from backend.
     */
    public static class AdSyncData {
        public String id;
        public String title;
        public String subtitle;
        public String imageUrl;
        public String videoUrl;
        public String adType;
        public String backgroundColor;
        public String textColor;
        public Integer displayOrder;
        public Boolean isActive;
        public String startDate;
        public String endDate;
        public String updatedAt;
        public Integer displayDuration;
        public String textAlignment;
        public String titleFontSize;
        public String subtitleFontSize;
        public Boolean isTitleBold;
    }
}

