package com.pos.sync;

/**
 * Result of a multi-round forced outbound sync (Settings "Sync Now").
 */
public class ForcedOutboundSyncResult {
    private final int synced;
    private final int failed;
    private final int roundsExecuted;
    private final String error;

    public ForcedOutboundSyncResult(int synced, int failed, int roundsExecuted, String error) {
        this.synced = synced;
        this.failed = failed;
        this.roundsExecuted = roundsExecuted;
        this.error = error;
    }

    public int getSynced() {
        return synced;
    }

    public int getFailed() {
        return failed;
    }

    public int getRoundsExecuted() {
        return roundsExecuted;
    }

    public String getError() {
        return error;
    }
}
