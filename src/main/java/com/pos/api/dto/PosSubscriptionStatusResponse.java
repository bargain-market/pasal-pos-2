package com.pos.api.dto;

/**
 * POS subscription status + signed offline lease, returned by GET /pos/subscription.
 *
 * <p>When {@code hasAccess} is false the {@code lease} fields are null. When the
 * server has no lease signing key configured, {@code hasAccess} is true but
 * {@code lease.token} is null (online-only operation).</p>
 */
public class PosSubscriptionStatusResponse {
    public String storeId;
    public String deviceId;
    public String deviceDbId;
    public String status;
    public Boolean hasAccess;
    public Boolean isStoreExempt;
    /** ISO-8601 timestamp the store is paid through, or null. */
    public String paidThrough;
    /** ISO-8601 server time at the moment of the response. */
    public String serverTime;
    public Lease lease;

    /**
     * Signed offline lease. {@code token} is an RS256 JWT verified offline with
     * {@code publicKeyPem}; {@code publicKeyFingerprint} is the sha256 hex of the
     * key's SPKI DER encoding.
     */
    public static class Lease {
        public String token;
        public String expiresAt;
        public String publicKeyFingerprint;
        public String publicKeyPem;
    }
}
