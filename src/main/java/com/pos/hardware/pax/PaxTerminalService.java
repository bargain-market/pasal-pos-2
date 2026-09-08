package com.pos.hardware.pax;

import com.pos.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Singleton service for PAX POSLink terminal communication.
 */
public class PaxTerminalService {

    private static final Logger logger = LoggerFactory.getLogger(PaxTerminalService.class);
    private static PaxTerminalService instance;

    private final ConfigManager configManager;
    private final AtomicReference<PaxTerminalClient> client = new AtomicReference<>();
    private final AtomicReference<PaxTerminalClient> pendingClient = new AtomicReference<>();
    private String pendingInvoiceRef;
    private volatile boolean clientOverriddenForTests = false;

    private PaxTerminalService() {
        this.configManager = ConfigManager.getInstance();
        reloadClient();
    }

    public static synchronized PaxTerminalService getInstance() {
        if (instance == null) {
            instance = new PaxTerminalService();
        }
        return instance;
    }

    /** Visible for unit tests. */
    static void resetForTests() {
        instance = null;
    }

    public synchronized void reloadClient() {
        if (pendingClient.get() != null) throw new IllegalStateException("Wait for the terminal operation to finish.");
        clientOverriddenForTests = false;
        // JARs in lib/pax/ may have changed since last load — force a fresh detection.
        PosLink2TerminalClient.clearSdkPresenceCache();
        client.set(createClient());
    }

    public void setClientForTests(PaxTerminalClient testClient) {
        clientOverriddenForTests = true;
        client.set(testClient);
    }

    public PaxCommSettings loadSettings() {
        boolean enabled = Boolean.parseBoolean(configManager.getProperty("pax.enabled", "true"));
        String commType = configManager.getProperty("pax.comm.type", "TCP");
        String host = configManager.getProperty("pax.comm.host", "192.168.1.100");
        int port = parseInt(configManager.getProperty("pax.comm.port", "10009"), 10009);
        int timeoutMs = parseInt(configManager.getProperty("pax.comm.timeoutMs", "60000"), 60000);
        boolean logEnabled = Boolean.parseBoolean(configManager.getProperty("pax.log.enabled", "true"));
        return new PaxCommSettings(enabled, commType, host, port, timeoutMs, logEnabled);
    }

    public synchronized void saveSettings(boolean enabled, String type, String host,
            String port, String timeout) {
        if (pendingClient.get() != null) {
            throw new IllegalStateException("Wait for the terminal operation to finish before changing settings.");
        }
        String normalizedHost = host != null ? host.trim() : "";
        if (enabled && (!"TCP".equals(type) || normalizedHost.isEmpty()
                || normalizedHost.contains("://") || normalizedHost.matches(".*\\s+.*"))) {
            throw new IllegalArgumentException("Enter a terminal IP address or hostname and select TCP.");
        }
        int parsedPort;
        int parsedTimeout;
        try {
            parsedPort = Integer.parseInt(port.trim());
            parsedTimeout = Integer.parseInt(timeout.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Terminal port and timeout must be whole numbers.");
        }
        if (parsedPort < 1 || parsedPort > 65535 || parsedTimeout < 1000 || parsedTimeout > 300000) {
            throw new IllegalArgumentException("Port must be 1–65535 and timeout 1000–300000 milliseconds.");
        }
        configManager.setPropertiesAtomically(java.util.Map.of(
                "pax.enabled", String.valueOf(enabled),
                "pax.comm.type", type, "pax.comm.host", normalizedHost,
                "pax.comm.port", String.valueOf(parsedPort), "pax.comm.timeoutMs", String.valueOf(parsedTimeout)));
        reloadClient();
    }

    public boolean isEnabled() {
        return loadSettings().enabled;
    }

    public boolean shouldUseTerminalForCard() {
        if (!isEnabled()) {
            return false;
        }
        if (isTestingMode()) {
            return false;
        }
        return true;
    }

    public boolean isTestingMode() {
        return Boolean.parseBoolean(configManager.getProperty("app.testing.mode", "false"));
    }

    public boolean isSdkAvailable() {
        if (isTestingMode() || !isEnabled()) {
            return true;
        }
        return PosLink2TerminalClient.isSdkPresent();
    }

    public PaxPaymentResult processSale(BigDecimal amount, String invoiceRef) throws PaxTerminalException {
        PaxTerminalClient activeClient;
        synchronized (this) {
        activeClient = client.get();
        if (activeClient == null) {
            reloadClient();
            activeClient = client.get();
        }
        if (shouldUseTerminalForCard() && !clientOverriddenForTests && !PosLink2TerminalClient.isSdkPresent()) {
            throw new PaxTerminalException(PosLink2TerminalClient.getSdkLoadHint());
        }
        logger.info("Processing PAX sale: amount={}, ref={}", amount, invoiceRef);
            if (!pendingClient.compareAndSet(null, activeClient)) throw new PaxTerminalException("A terminal operation is already in progress.");
            pendingInvoiceRef = invoiceRef;
        }
        try { return activeClient.processSale(amount, invoiceRef); }
        finally { synchronized (this) { pendingClient.compareAndSet(activeClient, null); pendingInvoiceRef = null; } }
    }

    public List<PaxPaymentResult> processCardSplitPayments(List<BigDecimal> cardAmounts, String saleRefPrefix)
            throws PaxTerminalException {
        List<PaxPaymentResult> results = new ArrayList<>();
        for (int i = 0; i < cardAmounts.size(); i++) {
            String ref = saleRefPrefix + "-C" + (i + 1);
            PaxPaymentResult result = processSale(cardAmounts.get(i), ref);
            results.add(result);
            if (!result.approved) {
                throw new PaxTerminalException(
                        "Card portion " + (i + 1) + " declined: " + result.message);
            }
        }
        return results;
    }

    public String testConnection() throws PaxTerminalException {
        PaxTerminalClient active;
        synchronized (this) {
        active = client.get();
        if (!pendingClient.compareAndSet(null, active)) {
            throw new PaxTerminalException("Wait for the current terminal operation to finish.");
        }
        }
        try {
            return active.testConnection();
        } finally {
            pendingClient.compareAndSet(active, null);
        }
    }

    public synchronized boolean cancelPendingPayment(String invoiceRef) throws PaxTerminalException {
        PaxTerminalClient active = pendingClient.get();
        return active != null && invoiceRef != null && invoiceRef.equals(pendingInvoiceRef)
                && active.cancelPendingPayment();
    }

    public String getStatusSummary() {
        PaxCommSettings settings = loadSettings();
        if (!settings.enabled) {
            return "Disabled";
        }
        if (isTestingMode()) {
            return "Testing mode (mock approvals)";
        }
        if (!settings.isConfigured()) {
            return "Not configured — set host and port";
        }
        if (!PosLink2TerminalClient.isSdkPresent()) {
            return PosLink2TerminalClient.getSdkLoadHint();
        }
        return "Configured (" + settings.commType + " " + settings.host + ":" + settings.port + ")";
    }

    private PaxTerminalClient createClient() {
        if (isTestingMode() || !isEnabled()) {
            return new MockPaxTerminalClient();
        }
        if (!PosLink2TerminalClient.isSdkPresent()) {
            logger.warn("PAX enabled but POSLink SDK unavailable: {}", PosLink2TerminalClient.getSdkLoadHint());
            return new UnavailablePaxTerminalClient();
        }
        try {
            PaxCommSettings settings = loadSettings();
            return new PosLink2TerminalClient(settings);
        } catch (PaxTerminalException e) {
            logger.error("Failed to initialize PAX client: {}", e.getMessage());
            return new UnavailablePaxTerminalClient();
        }
    }

    /**
     * Placeholder client when PAX is enabled but the SDK failed to load.
     */
    private static final class UnavailablePaxTerminalClient implements PaxTerminalClient {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public PaxPaymentResult processSale(BigDecimal amount, String invoiceRef) throws PaxTerminalException {
            throw new PaxTerminalException(PosLink2TerminalClient.getSdkLoadHint());
        }

        @Override
        public String testConnection() throws PaxTerminalException {
            throw new PaxTerminalException(PosLink2TerminalClient.getSdkLoadHint());
        }
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
