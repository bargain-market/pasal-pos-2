package com.pos.hardware.pax;

import com.pos.config.ConfigManager;
import org.junit.Test;
import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class PendingCardPaymentTest {
    private static final class PendingClient implements PaxTerminalClient {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        final AtomicInteger cancels = new AtomicInteger();
        final boolean approve;
        PendingClient(boolean approve) { this.approve = approve; }
        public boolean isAvailable() { return true; }
        public PaxPaymentResult processSale(BigDecimal amount, String ref)
                throws PaxTerminalException {
            started.countDown();
            try {
                if (!finish.await(5, TimeUnit.SECONDS)) throw new PaxTerminalException("Test timed out");
            } catch (InterruptedException e) { throw new PaxTerminalException("Interrupted", e); }
            return approve ? new PaxPaymentResult(true, "Approved", null, null, null, null, null, null, null, null, "OK")
                    : PaxPaymentResult.declined("Aborted", "100002");
        }
        public boolean cancelPendingPayment() { cancels.incrementAndGet(); return true; }
        public String testConnection() { return "Non-payment connection check"; }
    }

    @Test public void cancelIsBoundToOriginalPaymentAndWaitsForTerminal() throws Exception {
        runCancellation(false);
    }
    @Test public void approvalRacingCancelIsNeverDiscarded() throws Exception {
        runCancellation(true);
    }
    private void runCancellation(boolean approve) throws Exception {
        PaxTerminalService.resetForTests();
        PaxTerminalService service = PaxTerminalService.getInstance();
        PendingClient client = new PendingClient(approve);
        service.setClientForTests(client);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<PaxPaymentResult> sale = worker.submit(() -> service.processSale(BigDecimal.TEN, "CURRENT"));
            assertTrue(client.started.await(2, TimeUnit.SECONDS));
            assertFalse(service.cancelPendingPayment("PREVIOUS"));
            assertEquals(0, client.cancels.get());
            assertTrue(service.cancelPendingPayment("CURRENT"));
            assertFalse("Sending cancel is not a completed payment", sale.isDone());
            try { service.processSale(BigDecimal.ONE, "SECOND"); fail("Concurrent sale allowed"); }
            catch (PaxTerminalException expected) { assertTrue(expected.getMessage().contains("already")); }
            try { service.testConnection(); fail("Connection check interrupted sale"); }
            catch (PaxTerminalException expected) { assertTrue(expected.getMessage().contains("Wait")); }
            try { service.reloadClient(); fail("Client replaced during payment"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Wait")); }
            client.finish.countDown();
            PaxPaymentResult result = sale.get(2, TimeUnit.SECONDS);
            assertEquals(approve, result.approved);
            assertEquals(approve ? "OK" : "100002", result.resultCode);
            assertFalse(service.cancelPendingPayment("CURRENT"));
            assertEquals(1, client.cancels.get());
        } finally {
            client.finish.countDown();
            worker.shutdownNow();
            PaxTerminalService.resetForTests();
        }
    }

    @Test public void invalidSettingsDoNotReplaceWorkingHost() {
        PaxTerminalService service = PaxTerminalService.getInstance();
        String host = service.loadSettings().host;
        try {
            service.saveSettings(true, "TCP", "http://wrong-address", "10009", "60000");
            fail("Invalid host accepted");
        } catch (IllegalArgumentException expected) { assertEquals(host, service.loadSettings().host); }
        try {
            service.saveSettings(true, "TCP", "192.0.2.10", "70000", "60000");
            fail("Invalid port accepted");
        } catch (IllegalArgumentException expected) { assertEquals(host, service.loadSettings().host); }
    }

    @Test public void failedSaveRollsBackSettingsAndReportsFailure() throws Exception {
        ConfigManager config = ConfigManager.getInstance();
        var fileField = ConfigManager.class.getDeclaredField("configFilePath");
        fileField.setAccessible(true);
        Object originalPath = fileField.get(config);
        String originalHost = config.getProperty("pax.comm.host", "");
        java.nio.file.Path invalidTarget = java.nio.file.Files.createTempDirectory("pos-config-target-");
        try {
            fileField.set(config, invalidTarget);
            try {
                config.setPropertiesAtomically(java.util.Map.of("pax.comm.host", "192.0.2.99"));
                fail("Failed persistence reported success");
            } catch (IllegalStateException expected) {
                assertEquals(originalHost, config.getProperty("pax.comm.host", ""));
            }
        } finally {
            fileField.set(config, originalPath);
            java.nio.file.Files.delete(invalidTarget);
        }
    }

    @Test public void savedHostSurvivesReloadWithOtherDeviceSettingsIntact() throws Exception {
        ConfigManager config = ConfigManager.getInstance();
        var propertiesField = ConfigManager.class.getDeclaredField("properties");
        propertiesField.setAccessible(true);
        java.util.Properties original = new java.util.Properties();
        original.putAll((java.util.Properties) propertiesField.get(config));
        try {
            config.setProperty("device.name", "Terminal persistence QA");
            PaxTerminalService.getInstance().saveSettings(true, "TCP", "192.0.2.25", "10010", "45000");
            var reload = ConfigManager.class.getDeclaredMethod("loadConfiguration");
            reload.setAccessible(true);
            reload.invoke(config);
            PaxCommSettings saved = PaxTerminalService.getInstance().loadSettings();
            assertEquals("192.0.2.25", saved.host);
            assertEquals(10010, saved.port);
            assertEquals(45000, saved.timeoutMs);
            assertEquals("Terminal persistence QA", config.getProperty("device.name"));
        } finally {
            propertiesField.set(config, original);
            config.setPropertiesAtomically(java.util.Map.of());
            PaxTerminalService.resetForTests();
        }
    }
}

