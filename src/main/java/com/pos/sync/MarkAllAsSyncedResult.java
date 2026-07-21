package com.pos.sync;

/**
 * Result of batch-marking all locally pending outbound sync rows as synced.
 */
public class MarkAllAsSyncedResult {
    public int sales;
    public int shifts;
    public int products;
    public int departments;
    public int cartCancellations;
    public int refunds;
    public int cashOperations;
    public int vendors;
    public int vendorPayouts;
    public int expenses;
    public int inventoryLog;
    public int employeeShifts;
    public int customers;
    /** Rows removed from {@code pending_requests}. */
    public int pendingRequestsDeleted;

    public PendingOutboundBreakdown breakdownBefore;
    public PendingOutboundBreakdown breakdownAfter;

    public int totalRowsMarked() {
        return sales + shifts + products + departments + cartCancellations + refunds
                + cashOperations + vendors + vendorPayouts + expenses + inventoryLog
                + employeeShifts + customers + pendingRequestsDeleted;
    }
}
