package com.pos.hardware.pax;

import java.math.BigDecimal;

/**
 * Abstraction over PAX POSLink terminal communication.
 */
public interface PaxTerminalClient {

    boolean isAvailable();

    PaxPaymentResult processSale(BigDecimal amount, String invoiceRef) throws PaxTerminalException;

    default boolean cancelPendingPayment() throws PaxTerminalException { return false; }

    String testConnection() throws PaxTerminalException;
}
