package com.pos.sync;

/**
 * Per-table counts that make up {@link SyncManager#getPendingOutboundCount()}.
 */
public class PendingOutboundBreakdown {
    public int sales;
    public int shifts;
    public int products;
    public int departments;
    public int pendingRequests;
    public int cartCancellations;

    public int total() {
        return sales + shifts + products + departments + pendingRequests + cartCancellations;
    }

    public String toSummaryLine() {
        return String.format(
                "sales=%d, shifts=%d, products=%d, departments=%d, pending_requests=%d, cart_cancellations=%d (total=%d)",
                sales, shifts, products, departments, pendingRequests, cartCancellations, total());
    }
}
