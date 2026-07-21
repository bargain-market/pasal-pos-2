package com.pos.sync;

import java.time.Instant;

/**
 * Represents the current sync status of the POS system.
 * Used to track connectivity, WebSocket status, sync progress, and pending operations.
 */
public class SyncStatus {
    private final boolean online;
    private final boolean webSocketConnected;
    private final boolean inboundSyncing;
    private final boolean outboundSyncing;
    private final Instant lastInboundSync;
    private final Instant lastOutboundSync;
    private final String lastError;
    private final int pendingOutboundCount;
    
    public SyncStatus(
            boolean online,
            boolean inboundSyncing,
            boolean outboundSyncing,
            Instant lastInboundSync,
            Instant lastOutboundSync,
            String lastError,
            int pendingOutboundCount
    ) {
        this(online, false, inboundSyncing, outboundSyncing, lastInboundSync, lastOutboundSync, lastError, pendingOutboundCount);
    }
    
    public SyncStatus(
            boolean online,
            boolean webSocketConnected,
            boolean inboundSyncing,
            boolean outboundSyncing,
            Instant lastInboundSync,
            Instant lastOutboundSync,
            String lastError,
            int pendingOutboundCount
    ) {
        this.online = online;
        this.webSocketConnected = webSocketConnected;
        this.inboundSyncing = inboundSyncing;
        this.outboundSyncing = outboundSyncing;
        this.lastInboundSync = lastInboundSync;
        this.lastOutboundSync = lastOutboundSync;
        this.lastError = lastError;
        this.pendingOutboundCount = pendingOutboundCount;
    }
    
    /**
     * Check if device is online.
     */
    public boolean isOnline() {
        return online;
    }
    
    /**
     * Check if WebSocket is connected (real-time sync active).
     */
    public boolean isWebSocketConnected() {
        return webSocketConnected;
    }
    
    /**
     * Check if real-time sync is active (WebSocket connected).
     */
    public boolean isRealTimeSyncActive() {
        return online && webSocketConnected;
    }
    
    /**
     * Check if inbound sync (backend → local) is in progress.
     */
    public boolean isInboundSyncing() {
        return inboundSyncing;
    }
    
    /**
     * Check if outbound sync (local → backend) is in progress.
     */
    public boolean isOutboundSyncing() {
        return outboundSyncing;
    }
    
    /**
     * Check if any sync is in progress.
     */
    public boolean isSyncing() {
        return inboundSyncing || outboundSyncing;
    }
    
    /**
     * Get last inbound sync timestamp.
     */
    public Instant getLastInboundSync() {
        return lastInboundSync;
    }
    
    /**
     * Get last outbound sync timestamp.
     */
    public Instant getLastOutboundSync() {
        return lastOutboundSync;
    }
    
    /**
     * Get last sync error message.
     */
    public String getLastError() {
        return lastError;
    }
    
    /**
     * Get count of pending outbound items (sales, shifts, etc.).
     */
    public int getPendingOutboundCount() {
        return pendingOutboundCount;
    }
    
    /**
     * Check if there are pending outbound items.
     */
    public boolean hasPendingOutbound() {
        return pendingOutboundCount > 0;
    }
    
    /**
     * Get a human-readable status string.
     */
    public String getStatusText() {
        if (!online) {
            return "Offline" + (pendingOutboundCount > 0 ? " (" + pendingOutboundCount + " pending)" : "");
        }
        if (inboundSyncing && outboundSyncing) {
            return "Syncing...";
        }
        if (inboundSyncing) {
            return "Downloading...";
        }
        if (outboundSyncing) {
            return "Uploading...";
        }
        
        String wsStatus = webSocketConnected ? "⚡ Real-time" : "📡 Polling";
        if (pendingOutboundCount > 0) {
            return wsStatus + " (" + pendingOutboundCount + " pending)";
        }
        return wsStatus;
    }
    
    @Override
    public String toString() {
        return "SyncStatus{" +
                "online=" + online +
                ", webSocketConnected=" + webSocketConnected +
                ", inboundSyncing=" + inboundSyncing +
                ", outboundSyncing=" + outboundSyncing +
                ", lastInboundSync=" + lastInboundSync +
                ", lastOutboundSync=" + lastOutboundSync +
                ", pendingOutboundCount=" + pendingOutboundCount +
                ", lastError='" + lastError + '\'' +
                '}';
    }
}

