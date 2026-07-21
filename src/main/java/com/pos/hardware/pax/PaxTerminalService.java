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

    public void reloadClient() {
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
        PaxTerminalClient activeClient = client.get();
        if (activeClient == null) {
            reloadClient();
            activeClient = client.get();
        }
        if (shouldUseTerminalForCard() && !clientOverriddenForTests && !PosLink2TerminalClient.isSdkPresent()) {
            throw new PaxTerminalException(PosLink2TerminalClient.getSdkLoadHint());
        }
        logger.info("Processing PAX sale: amount={}, ref={}", amount, invoiceRef);
        return activeClient.processSale(amount, invoiceRef);
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
        return client.get().testConnection();
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
