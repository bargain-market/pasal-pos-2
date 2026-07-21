package com.pos.hardware.pax;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Mock PAX client for testing mode or when terminal integration is disabled.
 */
public class MockPaxTerminalClient implements PaxTerminalClient {

    private static final Logger logger = LoggerFactory.getLogger(MockPaxTerminalClient.class);

    private final boolean simulateDecline;

    public MockPaxTerminalClient() {
        this(false);
    }

    public MockPaxTerminalClient(boolean simulateDecline) {
        this.simulateDecline = simulateDecline;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public PaxPaymentResult processSale(BigDecimal amount, String invoiceRef) {
        logger.info("Mock PAX sale: amount={}, ref={}", amount, invoiceRef);
        if (simulateDecline) {
            return PaxPaymentResult.declined("Mock terminal declined payment", "MOCK_DECLINED");
        }
        return PaxPaymentResult.approved(
                "Mock terminal approved",
                "MOCK01",
                "MOCK-" + invoiceRef,
                "4242",
                "VISA",
                "VISA",
                "CONTACTLESS",
                "MOCKHOST",
                invoiceRef);
    }

    @Override
    public String testConnection() {
        return "Mock PAX terminal ready (testing mode)";
    }
}
