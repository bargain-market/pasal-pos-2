package com.pos.sync;

/**
 * Result of a sync operation.
 */
public class SyncResult {
    private final SyncDirection direction;
    private final int synced;
    private final int failed;
    private final String error;
    
    public SyncResult(SyncDirection direction, int synced, int failed, String error) {
        this.direction = direction;
        this.synced = synced;
        this.failed = failed;
        this.error = error;
    }
    
    /**
     * Create a successful result.
     */
    public static SyncResult success(SyncDirection direction, int synced) {
        return new SyncResult(direction, synced, 0, null);
    }
    
    /**
     * Create a failed result.
     */
    public static SyncResult failure(SyncDirection direction, String error) {
        return new SyncResult(direction, 0, 1, error);
    }
    
    /**
     * Create an empty result (nothing to sync).
     */
    public static SyncResult empty(SyncDirection direction) {
        return new SyncResult(direction, 0, 0, null);
    }
    
    /**
     * Get sync direction.
     */
    public SyncDirection getDirection() {
        return direction;
    }
    
    /**
     * Get number of items successfully synced.
     */
    public int getSynced() {
        return synced;
    }
    
    /**
     * Get number of items that failed to sync.
     */
    public int getFailed() {
        return failed;
    }
    
    /**
     * Get error message (null if no error).
     */
    public String getError() {
        return error;
    }
    
    /**
     * Check if sync was successful (no failures).
     */
    public boolean isSuccess() {
        return failed == 0 && error == null;
    }
    
    /**
     * Check if there were any items to sync.
     */
    public boolean hasItems() {
        return synced > 0 || failed > 0;
    }
    
    @Override
    public String toString() {
        return "SyncResult{" +
                "direction=" + direction +
                ", synced=" + synced +
                ", failed=" + failed +
                ", error='" + error + '\'' +
                '}';
    }
}

