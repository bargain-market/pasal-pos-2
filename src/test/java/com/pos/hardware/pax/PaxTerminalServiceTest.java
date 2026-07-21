package com.pos.hardware.pax;

import com.pos.config.ConfigManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PaxTerminalServiceTest {

    @Before
    public void setUp() {
        PaxTerminalService.resetForTests();
    }

    @After
    public void tearDown() {
        PaxTerminalService.resetForTests();
    }

    @Test
    public void mockClientApprovesSale() throws Exception {
        PaxTerminalService service = PaxTerminalService.getInstance();
        service.setClientForTests(new MockPaxTerminalClient());

        PaxPaymentResult result = service.processSale(new BigDecimal("12.50"), "TEST123");

        assertTrue(result.approved);
        assertEquals("MOCK01", result.authCode);
        assertEquals("4242", result.cardLastFour);
    }

    @Test
    public void mockClientDeclinesWhenConfigured() throws Exception {
        PaxTerminalService service = PaxTerminalService.getInstance();
        service.setClientForTests(new MockPaxTerminalClient(true));

        PaxPaymentResult result = service.processSale(new BigDecimal("5.00"), "DECLINE1");

        assertFalse(result.approved);
        assertEquals("MOCK_DECLINED", result.resultCode);
    }

    @Test
    public void splitCardPaymentsStopOnDecline() throws Exception {
        PaxTerminalService service = PaxTerminalService.getInstance();
        service.setClientForTests(new MockPaxTerminalClient(true));

        try {
            service.processCardSplitPayments(
                    List.of(new BigDecimal("10.00"), new BigDecimal("5.00")),
                    "SPLITREF");
            org.junit.Assert.fail("Expected PaxTerminalException");
        } catch (PaxTerminalException e) {
            assertTrue(e.getMessage().contains("declined"));
        }
    }

    @Test
    public void splitCardPaymentsApproveSequentially() throws Exception {
        PaxTerminalService service = PaxTerminalService.getInstance();
        service.setClientForTests(new MockPaxTerminalClient());

        List<PaxPaymentResult> results = service.processCardSplitPayments(
                List.of(new BigDecimal("10.00"), new BigDecimal("5.00")),
                "SPLITREF");

        assertEquals(2, results.size());
        assertTrue(results.get(0).approved);
        assertTrue(results.get(1).approved);
    }

    @Test
    public void paymentDetailsMappedFromApprovedResult() {
        PaxPaymentResult result = PaxPaymentResult.approved(
                "Approved",
                "AUTH01",
                "REF01",
                "9999",
                "MASTERCARD",
                "MASTERCARD",
                "CHIP",
                "HOST01",
                "INV01");

        assertTrue(result.toPaymentDetails().approvalStatus.equals("APPROVED"));
        assertEquals("PAX", result.toPaymentDetails().paymentProcessor);
        assertEquals("AUTH01", result.toPaymentDetails().authCode);
    }

    @Test
    public void declinedResultHasNoPaymentDetails() {
        PaxPaymentResult result = PaxPaymentResult.declined("Card declined", "051");

        assertFalse(result.approved);
        // A declined card must not produce payment details that could be persisted as a sale.
        org.junit.Assert.assertNull(result.toPaymentDetails());
    }

    @Test
    public void testingModeUsesMockClient() {
        ConfigManager config = ConfigManager.getInstance();
        config.setProperty("app.testing.mode", "true");
        config.setProperty("pax.enabled", "true");

        PaxTerminalService service = PaxTerminalService.getInstance();
        service.reloadClient();

        assertFalse(service.shouldUseTerminalForCard());
        assertTrue(service.isTestingMode());
    }
}
