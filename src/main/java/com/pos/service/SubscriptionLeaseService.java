package com.pos.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pos.api.ApiClient;
import com.pos.api.dto.PosSubscriptionStatusResponse;
import com.pos.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * Manages the store subscription state and the signed offline lease that lets
 * this POS keep operating while the backend is unreachable.
 *
 * <h2>How it works</h2>
 * <ul>
 * <li>{@link #refreshLease()} calls {@code GET /pos/subscription} with device
 * authentication and persists the status + signed lease (an RS256 JWT) into
 * {@link ConfigManager}.</li>
 * <li>{@link #isOfflineAccessAllowed()} verifies the stored lease fully offline:
 * RS256 signature against the server-provided public key, claim binding to this
 * device/store, and {@code exp}. Offline access is granted ONLY by a verified
 * signed lease — never by a local flag or editable config value alone.</li>
 * <li>When the server explicitly reports {@code hasAccess=false} the stored
 * lease is cleared; transient network/HTTP failures never wipe it.</li>
 * </ul>
 */
public class SubscriptionLeaseService {
    private static final Logger logger = LoggerFactory.getLogger(SubscriptionLeaseService.class);
    private static SubscriptionLeaseService instance;

    // Config keys for the persisted subscription state and lease material.
    // Only the public key is persisted — never any private key or user secret.
    public static final String KEY_STATUS = "subscription.status";
    public static final String KEY_HAS_ACCESS = "subscription.hasAccess";
    public static final String KEY_PAID_THROUGH = "subscription.paidThrough";
    public static final String KEY_STORE_EXEMPT = "subscription.storeExempt";
    public static final String KEY_LEASE_TOKEN = "subscription.lease.token";
    public static final String KEY_LEASE_EXPIRES_AT = "subscription.lease.expiresAt";
    public static final String KEY_LEASE_FINGERPRINT = "subscription.lease.fingerprint";
    public static final String KEY_LEASE_PUBLIC_KEY = "subscription.lease.publicKeyPem";
    public static final String KEY_LEASE_FETCHED_AT = "subscription.lease.fetchedAt";
    /** Backend device row id used to bind the lease "sub" claim. */
    public static final String KEY_DEVICE_DB_ID = "device.db.id";

    /** JWT claim values issued by the backend. */
    private static final String LEASE_ISSUER = "pasal-pos";
    private static final String LEASE_AUDIENCE = "pasal-pos-offline";

    /** Message shown when an offline entry path is blocked by the lease check. */
    public static final String OFFLINE_SUBSCRIPTION_MESSAGE =
            "Store subscription is not active for offline use. Connect to the internet to renew.";

    private final ApiClient apiClient;
    private final ConfigManager config;

    /**
     * Result of a {@link #refreshLease()} call.
     */
    public enum RefreshResult {
        /** Server granted access and a fresh signed lease was stored. */
        REFRESHED,
        /** Server reachable and explicitly reported hasAccess=false (lease cleared). */
        NO_ACCESS,
        /** Server grants access but issues no lease (no signing key) — online-only operation. */
        ONLINE_ONLY,
        /** Network/HTTP failure — stored lease left untouched. */
        UNAVAILABLE
    }

    /**
     * Result of locally verifying the stored lease.
     */
    public enum LeaseCheckResult {
        ALLOWED,
        EXPIRED,
        MISSING,
        INVALID_SIGNATURE,
        MISMATCH
    }

    private SubscriptionLeaseService() {
        this(ApiClient.getInstance(), ConfigManager.getInstance());
    }

    SubscriptionLeaseService(ApiClient apiClient, ConfigManager config) {
        this.apiClient = apiClient;
        this.config = config;
    }

    public static synchronized SubscriptionLeaseService getInstance() {
        if (instance == null) {
            instance = new SubscriptionLeaseService();
        }
        return instance;
    }

    /**
     * Fetch the subscription status + signed lease from the backend and persist it.
     * Best-effort and quick (bounded call timeout) so it is safe to invoke on the
     * offline-login path: on any network/HTTP failure the previously stored lease
     * is left untouched and {@link RefreshResult#UNAVAILABLE} is returned.
     */
    public synchronized RefreshResult refreshLease() {
        try {
            int timeoutMs = config.getIntProperty("backend.api.timeout.lease", 4000);
            ApiClient.ApiResponse<PosSubscriptionStatusResponse> response = apiClient.get(
                    "/pos/subscription",
                    PosSubscriptionStatusResponse.class,
                    timeoutMs);

            PosSubscriptionStatusResponse data = response.getData();
            if (data == null) {
                return RefreshResult.UNAVAILABLE;
            }

            persistStatus(data);

            if (!Boolean.TRUE.equals(data.hasAccess)) {
                // The server explicitly says the subscription is not active —
                // clear the stored lease so offline access stops immediately.
                clearStoredLease();
                logger.warn("Store subscription inactive per backend (status: {})", data.status);
                return RefreshResult.NO_ACCESS;
            }

            if (data.lease != null
                    && data.lease.token != null && !data.lease.token.isEmpty()
                    && data.lease.publicKeyPem != null && !data.lease.publicKeyPem.isEmpty()) {
                persistLease(data.lease);
                return RefreshResult.REFRESHED;
            }

            // A successful online-only response supersedes any old offline grant.
            clearStoredLease();
            // Access granted but no lease issued (server has no signing key).
            logger.info("Subscription active; backend issued no offline lease (online-only mode)");
            return RefreshResult.ONLINE_ONLY;
        } catch (ApiClient.ApiException e) {
            if (e.getStatusCode() == 401 || e.getStatusCode() == 402 || e.getStatusCode() == 403) {
                markServerNoAccess("INACTIVE");
                return RefreshResult.NO_ACCESS;
            }
            // Keep the stored lease — this may be a transient failure.
            logger.debug("Subscription status fetch failed (status {}): {}", e.getStatusCode(), e.getMessage());
            return RefreshResult.UNAVAILABLE;
        } catch (Exception e) {
            logger.warn("Subscription status fetch failed: {}", e.getMessage());
            return RefreshResult.UNAVAILABLE;
        }
    }

    /**
     * Refresh the lease on a daemon background thread (does not block the caller).
     */
    public void refreshLeaseAsync() {
        Thread thread = new Thread(this::refreshLease, "SubscriptionLeaseRefresh");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Verify the stored signed lease for offline use.
     *
     * @return {@link LeaseCheckResult#ALLOWED} only when the stored RS256 JWT
     *         verifies against the stored server public key, is bound to this
     *         device/store, and has not expired.
     */
    public LeaseCheckResult isOfflineAccessAllowed() {
        String token = config.getProperty(KEY_LEASE_TOKEN, "");
        String pem = config.getProperty(KEY_LEASE_PUBLIC_KEY, "");
        String deviceId = config.getProperty("device.id", "");
        String storeId = config.getProperty("store.id", "");

        if (token.isEmpty() || pem.isEmpty()) {
            return LeaseCheckResult.MISSING;
        }
        if (deviceId.isEmpty() || storeId.isEmpty()) {
            return LeaseCheckResult.MISSING;
        }

        return verifyStoredLease(token, pem, deviceId, storeId);
    }

    /**
     * Verify the stored lease: RS256 signature, key fingerprint, claim binding
     * (iss/aud/deviceId/storeId/sub) and expiry.
     */
    private LeaseCheckResult verifyStoredLease(String token, String pem, String deviceId, String storeId) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                logger.warn("Stored lease token is malformed");
                return LeaseCheckResult.INVALID_SIGNATURE;
            }

            byte[] keyDer;
            try {
                keyDer = Base64.getDecoder().decode(pemToBase64Der(pem));
            } catch (IllegalArgumentException e) {
                logger.warn("Stored lease public key is not valid base64");
                return LeaseCheckResult.INVALID_SIGNATURE;
            }

            // The PEM must correspond to the fingerprint the server reported
            // (sha256 hex of the SPKI DER encoding).
            String expectedFingerprint = config.getProperty(KEY_LEASE_FINGERPRINT, "");
            if (!expectedFingerprint.isEmpty()) {
                String actualFingerprint = sha256Hex(keyDer);
                if (!expectedFingerprint.equalsIgnoreCase(actualFingerprint)) {
                    logger.warn("Stored lease public key fingerprint mismatch (expected {}, got {})",
                            expectedFingerprint, actualFingerprint);
                    return LeaseCheckResult.INVALID_SIGNATURE;
                }
            }

            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(keyDer));

            JsonObject header = decodePart(parts[0]);
            JsonObject payload = decodePart(parts[1]);
            if (header == null || payload == null) {
                return LeaseCheckResult.INVALID_SIGNATURE;
            }
            if (!"RS256".equals(getClaim(header, "alg"))) {
                logger.warn("Stored lease token uses unexpected alg: {}", getClaim(header, "alg"));
                return LeaseCheckResult.INVALID_SIGNATURE;
            }

            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                logger.warn("Stored lease signature verification failed");
                return LeaseCheckResult.INVALID_SIGNATURE;
            }

            // Claim binding: the lease must have been issued for THIS device+store.
            if (!LEASE_ISSUER.equals(getClaim(payload, "iss"))
                    || !LEASE_AUDIENCE.equals(getClaim(payload, "aud"))) {
                logger.warn("Stored lease iss/aud mismatch");
                return LeaseCheckResult.MISMATCH;
            }
            if (!deviceId.equals(getClaim(payload, "deviceId"))
                    || !storeId.equals(getClaim(payload, "storeId"))) {
                logger.warn("Stored lease deviceId/storeId mismatch");
                return LeaseCheckResult.MISMATCH;
            }
            String storedDeviceDbId = config.getProperty(KEY_DEVICE_DB_ID, "");
            String sub = getClaim(payload, "sub");
            if (sub == null || sub.isEmpty() || (!storedDeviceDbId.isEmpty() && !storedDeviceDbId.equals(sub))) {
                logger.warn("Stored lease subject does not match this device");
                return LeaseCheckResult.MISMATCH;
            }

            long exp = payload.has("exp") && !payload.get("exp").isJsonNull()
                    ? payload.get("exp").getAsLong()
                    : 0L;
            long issuedAt = payload.has("iat") && !payload.get("iat").isJsonNull()
                    ? payload.get("iat").getAsLong() : 0L;
            long now = Instant.now().getEpochSecond();
            if (issuedAt <= 0 || issuedAt > now + 60 || exp <= issuedAt) {
                return LeaseCheckResult.MISMATCH;
            }
            if (exp <= now) {
                logger.info("Stored lease expired at {}", exp);
                return LeaseCheckResult.EXPIRED;
            }

            logger.debug("Stored lease verified — offline access allowed until {}", exp);
            return LeaseCheckResult.ALLOWED;
        } catch (Exception e) {
            logger.warn("Stored lease verification failed: {}", e.getMessage());
            return LeaseCheckResult.INVALID_SIGNATURE;
        }
    }

    private static JsonObject decodePart(String part) {
        try {
            String json = new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String getClaim(JsonObject obj, String name) {
        return obj.has(name) && !obj.get(name).isJsonNull() ? obj.get(name).getAsString() : null;
    }

    /** Strip PEM armor and return the base64 SPKI DER body. */
    private static String pemToBase64Der(String pem) {
        return pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void persistStatus(PosSubscriptionStatusResponse data) {
        config.setProperty(KEY_STATUS, data.status != null ? data.status : "");
        config.setProperty(KEY_HAS_ACCESS, String.valueOf(Boolean.TRUE.equals(data.hasAccess)));
        config.setProperty(KEY_PAID_THROUGH, data.paidThrough != null ? data.paidThrough : "");
        if (data.isStoreExempt != null) {
            config.setProperty(KEY_STORE_EXEMPT, String.valueOf(data.isStoreExempt));
        }
        if (data.serverTime != null && !data.serverTime.isEmpty()) {
            config.setProperty(KEY_LEASE_FETCHED_AT, data.serverTime);
        } else {
            config.setProperty(KEY_LEASE_FETCHED_AT, Instant.now().toString());
        }
        if (data.deviceDbId != null && !data.deviceDbId.isEmpty()) {
            config.setProperty(KEY_DEVICE_DB_ID, data.deviceDbId);
        }
    }

    private void persistLease(PosSubscriptionStatusResponse.Lease lease) {
        config.setProperty(KEY_LEASE_TOKEN, lease.token);
        config.setProperty(KEY_LEASE_EXPIRES_AT, lease.expiresAt != null ? lease.expiresAt : "");
        config.setProperty(KEY_LEASE_FINGERPRINT,
                lease.publicKeyFingerprint != null ? lease.publicKeyFingerprint : "");
        config.setProperty(KEY_LEASE_PUBLIC_KEY, lease.publicKeyPem != null ? lease.publicKeyPem : "");
        // Never log the token or PEM — the fingerprint is enough to correlate.
        logger.info("Stored subscription lease (fingerprint: {}, expiresAt: {})",
                lease.publicKeyFingerprint, lease.expiresAt);
    }

    /**
     * Clear the stored lease material. Keeps the status fields so the UI can
     * still report what the server last said.
     */
    public void clearStoredLease() {
        config.setProperty(KEY_LEASE_TOKEN, "");
        config.setProperty(KEY_LEASE_EXPIRES_AT, "");
        config.setProperty(KEY_LEASE_FINGERPRINT, "");
        config.setProperty(KEY_LEASE_PUBLIC_KEY, "");
    }

    /**
     * Record that the server revoked access (e.g. a subscription_required push)
     * even without a fresh HTTP fetch — clears the lease so offline use stops.
     */
    public void markServerNoAccess(String status) {
        clearStoredLease();
        config.setProperty(KEY_HAS_ACCESS, "false");
        config.setProperty(KEY_STATUS, status != null && !status.isEmpty() ? status : "INACTIVE");
        config.setProperty(KEY_LEASE_FETCHED_AT, Instant.now().toString());
    }

    /**
     * Clear all persisted subscription state (device unregistered / store change).
     */
    public void clearAllSubscriptionState() {
        clearStoredLease();
        config.setProperty(KEY_STATUS, "");
        config.setProperty(KEY_HAS_ACCESS, "");
        config.setProperty(KEY_PAID_THROUGH, "");
        config.setProperty(KEY_STORE_EXEMPT, "");
        config.setProperty(KEY_LEASE_FETCHED_AT, "");
        config.setProperty(KEY_DEVICE_DB_ID, "");
    }

    /**
     * ISO-8601 timestamp the store is paid through (from the last server fetch),
     * falling back to the stored lease expiry. May be null.
     */
    public String paidThrough() {
        String paidThrough = config.getProperty(KEY_PAID_THROUGH, "");
        if (!paidThrough.isEmpty()) {
            return paidThrough;
        }
        String leaseExpiry = config.getProperty(KEY_LEASE_EXPIRES_AT, "");
        return leaseExpiry.isEmpty() ? null : leaseExpiry;
    }

    /**
     * The last status string reported by the backend (may be empty).
     */
    public String lastKnownStatus() {
        return config.getProperty(KEY_STATUS, "");
    }

    /**
     * Whether the server last confirmed the store has access.
     */
    public boolean lastKnownHasAccess() {
        return "true".equals(config.getProperty(KEY_HAS_ACCESS, ""));
    }

    /**
     * Short human-readable explanation for a non-ALLOWED lease check.
     */
    public static String describeBlockReason(LeaseCheckResult result) {
        switch (result) {
            case ALLOWED:
                return "";
            case EXPIRED:
                return "The stored offline lease has expired.";
            case MISSING:
                return "No signed offline lease is stored on this device.";
            case INVALID_SIGNATURE:
                return "The stored offline lease failed signature verification.";
            case MISMATCH:
                return "The stored offline lease was issued for a different device or store.";
            default:
                return "Offline access is not permitted.";
        }
    }

    /**
     * One-line summary of the subscription state for status UIs.
     */
    public String statusSummary() {
        StringBuilder sb = new StringBuilder();
        String status = lastKnownStatus();
        if (status != null && !status.isEmpty()) {
            sb.append("Status: ").append(status);
        }
        String paidThrough = paidThrough();
        if (paidThrough != null) {
            sb.append(sb.length() > 0 ? " — " : "").append("Paid through ").append(paidThrough);
        }
        LeaseCheckResult check = isOfflineAccessAllowed();
        if (check == LeaseCheckResult.ALLOWED) {
            String expiry = config.getProperty(KEY_LEASE_EXPIRES_AT, "");
            sb.append(sb.length() > 0 ? " — " : "")
                    .append("Offline access allowed")
                    .append(expiry.isEmpty() ? "" : " until " + expiry);
        } else {
            sb.append(sb.length() > 0 ? " — " : "")
                    .append("Offline access: ").append(describeBlockReason(check));
        }
        return sb.length() > 0 ? sb.toString() : "Subscription status unknown";
    }
}
