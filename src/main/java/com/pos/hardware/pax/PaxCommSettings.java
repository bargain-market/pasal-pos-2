package com.pos.hardware.pax;

/**
 * Communication settings for a PAX payment terminal.
 */
public class PaxCommSettings {

    public final boolean enabled;
    public final String commType;
    public final String host;
    public final int port;
    public final int timeoutMs;
    public final boolean logEnabled;

    public PaxCommSettings(
            boolean enabled,
            String commType,
            String host,
            int port,
            int timeoutMs,
            boolean logEnabled) {
        this.enabled = enabled;
        this.commType = commType != null && !commType.isBlank() ? commType.trim().toUpperCase() : "TCP";
        this.host = host != null ? host.trim() : "";
        this.port = port;
        this.timeoutMs = timeoutMs;
        this.logEnabled = logEnabled;
    }

    public boolean isConfigured() {
        if (!enabled) {
            return false;
        }
        if ("TCP".equals(commType)) {
            return !host.isEmpty() && port > 0;
        }
        return false;
    }
}
