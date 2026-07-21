package com.pos.sync;

/**
 * Direction of sync operation.
 */
public enum SyncDirection {
    /**
     * Inbound sync: Backend → Local database.
     * Used for products, departments, users, settings.
     */
    INBOUND,
    
    /**
     * Outbound sync: Local database → Backend.
     * Used for sales, shifts, cash operations, locally created products/departments.
     */
    OUTBOUND
}

