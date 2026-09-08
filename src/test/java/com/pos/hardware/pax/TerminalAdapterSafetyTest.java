package com.pos.hardware.pax;

import org.junit.Test;
import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class TerminalAdapterSafetyTest {
    private static PaxCommSettings settings() {
        return new PaxCommSettings(true, "TCP", "192.0.2.10", 10009, 60000, false);
    }
    public static class InitResult {
        public boolean isSuccessful() { return true; }
        public String message() { return "OK"; }
    }
    public static class Management {
        int initCalls;
        public InitResult init() { initCalls++; return new InitResult(); }
    }
    public static class Terminal {
        final Management manage = new Management();
        public Management getManage() { return manage; }
        public Object getTransaction() { throw new AssertionError("Connection test attempted payment"); }
    }
    @Test public void connectionTestUsesManagementInitWithoutSale() throws Exception {
        Terminal terminal = new Terminal();
        PosLink2TerminalClient adapter = new PosLink2TerminalClient(settings()) {
            @Override Object connectSemiIntegrationTerminal() { return terminal; }
            @Override public PaxPaymentResult processSale(BigDecimal amount, String ref) {
                throw new AssertionError("Connection test attempted sale");
            }
        };
        assertTrue(adapter.testConnection().contains("192.0.2.10:10009"));
        assertEquals(1, terminal.manage.initCalls);
    }
    @Test public void cancellationUsesBundledSdkPublicInterface() throws Exception {
        PosLink2TerminalClient adapter = new PosLink2TerminalClient(settings());
        var loaderField = PosLink2TerminalClient.class.getDeclaredField("sdkClassLoader");
        loaderField.setAccessible(true);
        ClassLoader loader = (ClassLoader) loaderField.get(adapter);
        Class<?> terminalInterface = loader.loadClass("com.pax.poslinkadmin.BaseTerminal");
        AtomicInteger calls = new AtomicInteger();
        Object terminal = Proxy.newProxyInstance(loader, new Class<?>[]{terminalInterface}, (proxy, method, args) -> {
            if (method.getName().equals("cancel")) { calls.incrementAndGet(); return null; }
            throw new AssertionError("Unexpected SDK operation: " + method.getName());
        });
        assertFalse(adapter.cancelPendingPayment());
        var pendingField = PosLink2TerminalClient.class.getDeclaredField("pendingTerminal");
        pendingField.setAccessible(true);
        @SuppressWarnings("unchecked") AtomicReference<Object> pending = (AtomicReference<Object>) pendingField.get(adapter);
        pending.set(terminal);
        assertTrue(adapter.cancelPendingPayment());
        assertEquals(1, calls.get());
        pending.set(null);
        assertFalse(adapter.cancelPendingPayment());
    }
}

