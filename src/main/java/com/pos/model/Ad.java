package com.pos.model;

import java.time.Instant;

/**
 * Ad model for customer display
 * Represents promotional content shown on the customer-facing screen
 * Supports text, image, and video ad types
 */
public class Ad {
    private String id;
    private String title;
    private String subtitle;
    private String imageUrl;
    private String videoUrl; // URL for video ads
    private String adType = "text"; // Type: text, image, video
    private String backgroundColor;
    private String textColor;
    private int displayOrder;
    private boolean isActive;
    private Instant startDate;
    private Instant endDate;
    private Instant updatedAt;
    private int displayDuration = 5; // Duration in seconds (default 5)
    private String textAlignment = "center"; // left, center, right
    private String titleFontSize = "large"; // small, medium, large, xlarge
    private String subtitleFontSize = "medium"; // small, medium, large
    private boolean isTitleBold = true;

    public Ad() {
        // Default constructor
    }

    public Ad(String id, String title, String subtitle, String imageUrl,
              String backgroundColor, String textColor, int displayOrder,
              boolean isActive, Instant startDate, Instant endDate, Instant updatedAt) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.imageUrl = imageUrl;
        this.backgroundColor = backgroundColor;
        this.textColor = textColor;
        this.displayOrder = displayOrder;
        this.isActive = isActive;
        this.startDate = startDate;
        this.endDate = endDate;
        this.updatedAt = updatedAt;
        this.adType = imageUrl != null && !imageUrl.isEmpty() ? "image" : "text";
    }
    
    public Ad(String id, String title, String subtitle, String imageUrl,
              String backgroundColor, String textColor, int displayOrder,
              boolean isActive, Instant startDate, Instant endDate, Instant updatedAt,
              int displayDuration, String textAlignment, String titleFontSize,
              String subtitleFontSize, boolean isTitleBold) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.imageUrl = imageUrl;
        this.backgroundColor = backgroundColor;
        this.textColor = textColor;
        this.displayOrder = displayOrder;
        this.isActive = isActive;
        this.startDate = startDate;
        this.endDate = endDate;
        this.updatedAt = updatedAt;
        this.displayDuration = displayDuration;
        this.textAlignment = textAlignment;
        this.titleFontSize = titleFontSize;
        this.subtitleFontSize = subtitleFontSize;
        this.isTitleBold = isTitleBold;
        this.adType = imageUrl != null && !imageUrl.isEmpty() ? "image" : "text";
    }

    // Getters
    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getSubtitle() { return subtitle; }
    public String getImageUrl() { return imageUrl; }
    public String getVideoUrl() { return videoUrl; }
    public String getAdType() { return adType; }
    public String getBackgroundColor() { return backgroundColor; }
    public String getTextColor() { return textColor; }
    public int getDisplayOrder() { return displayOrder; }
    public boolean isActive() { return isActive; }
    public Instant getStartDate() { return startDate; }
    public Instant getEndDate() { return endDate; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getDisplayDuration() { return displayDuration; }
    public String getTextAlignment() { return textAlignment; }
    public String getTitleFontSize() { return titleFontSize; }
    public String getSubtitleFontSize() { return subtitleFontSize; }
    public boolean isTitleBold() { return isTitleBold; }

    // Setters
    public void setId(String id) { this.id = id; }
    public void setTitle(String title) { this.title = title; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }
    public void setAdType(String adType) { this.adType = adType; }
    public void setBackgroundColor(String backgroundColor) { this.backgroundColor = backgroundColor; }
    public void setTextColor(String textColor) { this.textColor = textColor; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }
    public void setActive(boolean active) { isActive = active; }
    public void setStartDate(Instant startDate) { this.startDate = startDate; }
    public void setEndDate(Instant endDate) { this.endDate = endDate; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setDisplayDuration(int displayDuration) { this.displayDuration = displayDuration; }
    public void setTextAlignment(String textAlignment) { this.textAlignment = textAlignment; }
    public void setTitleFontSize(String titleFontSize) { this.titleFontSize = titleFontSize; }
    public void setSubtitleFontSize(String subtitleFontSize) { this.subtitleFontSize = subtitleFontSize; }
    public void setTitleBold(boolean isTitleBold) { this.isTitleBold = isTitleBold; }

    /**
     * Check if this ad is an image-based ad
     */
    public boolean isImageAd() {
        return "image".equals(adType) || (imageUrl != null && !imageUrl.isEmpty());
    }

    /**
     * Check if this ad is a video-based ad
     */
    public boolean isVideoAd() {
        return "video".equals(adType) || (videoUrl != null && !videoUrl.isEmpty());
    }

    /**
     * Check if this ad should be displayed based on current time and scheduling.
     * 
     * <p>An ad is displayed if:
     * <ul>
     *   <li>It is active (isActive = true)</li>
     *   <li>Current time is after startDate (if set)</li>
     *   <li>Current time is before endDate (if set)</li>
     * </ul>
     * 
     * <p>Ads that have expired (past endDate) or haven't started yet (before startDate)
     * will not be displayed, even if they are marked as active.
     * 
     * @return true if the ad should be displayed, false otherwise
     */
    public boolean shouldDisplay() {
        if (!isActive) {
            return false;
        }

        Instant now = Instant.now();

        // Check start date - ad hasn't started yet
        if (startDate != null && now.isBefore(startDate)) {
            return false;
        }

        // Check end date - ad has expired
        if (endDate != null && now.isAfter(endDate)) {
            return false;
        }

        return true;
    }

    @Override
    public String toString() {
        return "Ad{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", adType='" + adType + '\'' +
                ", isActive=" + isActive +
                ", displayOrder=" + displayOrder +
                '}';
    }
}

