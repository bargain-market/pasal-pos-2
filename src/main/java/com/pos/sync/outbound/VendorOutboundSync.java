package com.pos.sync.outbound;

import com.pos.api.ApiClient;
import com.pos.config.ConfigManager;
import com.pos.model.Vendor;
import com.pos.service.VendorService;
import com.pos.sync.SyncDirection;
import com.pos.sync.SyncManager;
import com.pos.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Outbound sync for vendors created or edited on the POS.
 * Pushes to {@code POST /pos/vendors/sync} (same path as pull, different method).
 */
public class VendorOutboundSync implements SyncManager.OutboundSyncHandler {

    private static final Logger logger = LoggerFactory.getLogger(VendorOutboundSync.class);
    private static final int BATCH_SIZE = 50;

    private static VendorOutboundSync instance;

    private final ApiClient apiClient;
    private final VendorService vendorService;
    private final ConfigManager config;

    private VendorOutboundSync() {
        this.apiClient = ApiClient.getInstance();
        this.vendorService = VendorService.getInstance();
        this.config = ConfigManager.getInstance();
    }

    public static synchronized VendorOutboundSync getInstance() {
        if (instance == null) {
            instance = new VendorOutboundSync();
        }
        return instance;
    }

    @Override
    public String getName() {
        return "VendorSync";
    }

    @Override
    public SyncResult sync() throws Exception {
        int totalSynced = 0;
        int totalFailed = 0;
        StringBuilder errors = new StringBuilder();

        while (true) {
            List<Vendor> pending = vendorService.getUnsyncedVendors();
            if (pending.isEmpty()) {
                break;
            }

            int n = Math.min(BATCH_SIZE, pending.size());
            List<Vendor> batch = new ArrayList<>(pending.subList(0, n));

            logger.info("Syncing batch of {} vendors to backend", batch.size());

            BatchVendorSyncRequest request = new BatchVendorSyncRequest();
            request.vendors = batch.stream().map(this::toPayload).collect(Collectors.toList());
            request.deviceId = config.getProperty("device.id", "");
            request.syncTimestamp = Instant.now().toString();

            try {
                ApiClient.ApiResponse<BatchVendorSyncResponse> response = apiClient.post(
                        "/pos/vendors/sync",
                        request,
                        BatchVendorSyncResponse.class);

                BatchVendorSyncResponse data = response.getData();
                if (data == null || data.results == null) {
                    logger.warn("Vendor sync response missing data");
                    totalFailed += batch.size();
                    break;
                }

                List<String> syncedIds = new ArrayList<>();
                for (VendorSyncResult result : data.results) {
                    if ("created".equals(result.status) || "updated".equals(result.status)) {
                        if (result.vendorId != null) {
                            syncedIds.add(result.vendorId);
                        }
                        totalSynced++;
                    } else if ("failed".equals(result.status)) {
                        totalFailed++;
                        String msg = String.format("Vendor %s failed: %s",
                                result.vendorId != null ? result.vendorId : "?",
                                result.error != null ? result.error : "unknown");
                        logger.error(msg);
                        if (errors.length() > 0) {
                            errors.append("; ");
                        }
                        errors.append(msg);
                    }
                }

                if (!syncedIds.isEmpty()) {
                    vendorService.markVendorsAsSynced(syncedIds);
                }
            } catch (ApiClient.ApiException e) {
                logger.error("Vendor batch sync failed: {}", e.getMessage());
                totalFailed += batch.size();
                if (errors.length() > 0) {
                    errors.append("; ");
                }
                errors.append(e.getMessage());
                break;
            }
        }

        if (totalSynced == 0 && totalFailed == 0) {
            return SyncResult.empty(SyncDirection.OUTBOUND);
        }

        return new SyncResult(SyncDirection.OUTBOUND, totalSynced, totalFailed,
                errors.length() > 0 ? errors.toString() : null);
    }

    private VendorSyncPayload toPayload(Vendor v) {
        VendorSyncPayload p = new VendorSyncPayload();
        p.id = v.getId();
        p.name = v.getName();
        p.contactName = v.getContactName();
        p.email = v.getEmail();
        p.phone = v.getPhone();
        p.address = v.getAddress();
        p.paymentTerms = v.getPaymentTerms();
        p.commissionRate = v.getCommissionRate() != null ? v.getCommissionRate().doubleValue() : 0;
        p.defaultCostMargin = v.getDefaultCostMargin() != null ? v.getDefaultCostMargin().doubleValue() : 0;
        p.bankAccountInfo = v.getBankAccountInfo();
        p.notes = v.getNotes();
        p.isActive = v.isActive();
        p.updatedAt = v.getUpdatedAt();
        return p;
    }

    public static class BatchVendorSyncRequest {
        public List<VendorSyncPayload> vendors;
        public String deviceId;
        public String syncTimestamp;
    }

    public static class VendorSyncPayload {
        public String id;
        public String name;
        public String contactName;
        public String email;
        public String phone;
        public String address;
        public String paymentTerms;
        public Double commissionRate;
        public Double defaultCostMargin;
        public String bankAccountInfo;
        public String notes;
        public Boolean isActive;
        public String updatedAt;
    }

    public static class BatchVendorSyncResponse {
        public Integer processed;
        public Integer failed;
        public List<VendorSyncResult> results;
    }

    public static class VendorSyncResult {
        public String vendorId;
        public String status;
        public String serverId;
        public String error;
    }
}
