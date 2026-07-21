package com.pos.sync.outbound;

import com.pos.api.ApiClient;
import com.pos.database.DatabaseManager;
import com.pos.model.VendorPayout;
import com.pos.model.VendorPayoutItem;
import com.pos.service.VendorPayoutService;
import com.pos.sync.SyncDirection;
import com.pos.sync.SyncManager;
import com.pos.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Outbound sync handler for vendor payouts.
 * Pushes vendor payout records from local database to backend.
 * 
 * <h2>Sync Flow</h2>
 * <pre>
 * Local H2 Database ────────────────────────────────────────────────────────
 *     • vendor_payouts table (synced = FALSE)
 *     • vendor_payout_items table
 *     │
 *     ▼
 * VendorPayoutOutboundSync ─────────────────────────────────────────────────
 *     │
 *     │ Batch pending payouts (up to 20)
 *     │ Submit to backend
 *     │ Mark as synced on success
 *     │
 *     ▼
 * Backend API ──────────────────────────────────────────────────────────────
 *     POST /pos/vendor-payouts/batch
 * </pre>
 */
public class VendorPayoutOutboundSync implements SyncManager.OutboundSyncHandler {
    private static final Logger logger = LoggerFactory.getLogger(VendorPayoutOutboundSync.class);
    
    private static final int BATCH_SIZE = 20;
    
    private static VendorPayoutOutboundSync instance;
    
    private final ApiClient apiClient;
    private final DatabaseManager dbManager;
    private final VendorPayoutService payoutService;
    
    private VendorPayoutOutboundSync() {
        this.apiClient = ApiClient.getInstance();
        this.dbManager = DatabaseManager.getInstance();
        this.payoutService = VendorPayoutService.getInstance();
    }
    
    public static synchronized VendorPayoutOutboundSync getInstance() {
        if (instance == null) {
            instance = new VendorPayoutOutboundSync();
        }
        return instance;
    }
    
    @Override
    public String getName() {
        return "VendorPayoutSync";
    }
    
    @Override
    public SyncResult sync() throws Exception {
        // Get pending payouts
        List<VendorPayout> pendingPayouts = payoutService.getUnsyncedPayouts();
        
        if (pendingPayouts.isEmpty()) {
            logger.debug("No pending vendor payouts to sync");
            return SyncResult.empty(SyncDirection.OUTBOUND);
        }
        
        // Limit batch size
        if (pendingPayouts.size() > BATCH_SIZE) {
            pendingPayouts = pendingPayouts.subList(0, BATCH_SIZE);
        }
        
        logger.info("Syncing {} pending vendor payouts", pendingPayouts.size());
        
        try {
            // Convert to submission DTOs
            BatchVendorPayoutRequest request = new BatchVendorPayoutRequest();
            request.payouts = pendingPayouts.stream()
                    .map(this::convertToSubmission)
                    .collect(Collectors.toList());
            
            // Submit batch to backend
            ApiClient.ApiResponse<BatchVendorPayoutResponse> response = apiClient.post(
                    "/pos/vendor-payouts/batch",
                    request,
                    BatchVendorPayoutResponse.class);
            
            BatchVendorPayoutResponse responseData = response.getData();
            
            int synced = 0;
            int failed = 0;
            StringBuilder errorMessages = new StringBuilder();
            
            if (responseData != null && responseData.results != null) {
                List<String> syncedIds = new ArrayList<>();
                
                for (BatchVendorPayoutResult result : responseData.results) {
                    if ("created".equals(result.status) || "updated".equals(result.status) || "duplicate".equals(result.status)) {
                        syncedIds.add(result.payoutId);
                        synced++;
                    } else if ("failed".equals(result.status)) {
                        failed++;
                        String errorMsg = String.format("Payout %s failed: %s", 
                            result.payoutId != null ? result.payoutId : "unknown",
                            result.error != null ? result.error : "Unknown error");
                        logger.error(errorMsg);
                        if (errorMessages.length() > 0) {
                            errorMessages.append("; ");
                        }
                        errorMessages.append(errorMsg);
                    }
                }
                
                // Mark successfully synced payouts
                if (!syncedIds.isEmpty()) {
                    payoutService.markPayoutsAsSynced(syncedIds);
                }
            }
            
            if (failed > 0) {
                logger.error("Vendor payout sync completed with failures: {} synced, {} failed", synced, failed);
            } else {
                logger.info("Vendor payout sync completed: {} synced", synced);
            }
            
            return new SyncResult(SyncDirection.OUTBOUND, synced, failed, 
                failed > 0 ? errorMessages.toString() : null);
            
        } catch (ApiClient.ApiException e) {
            logger.error("Vendor payout batch sync failed: {}", e.getMessage());
            return SyncResult.failure(SyncDirection.OUTBOUND, e.getMessage());
        }
    }
    
    /**
     * Convert VendorPayout to submission DTO
     */
    private VendorPayoutSubmission convertToSubmission(VendorPayout payout) {
        VendorPayoutSubmission submission = new VendorPayoutSubmission();
        submission.payoutId = payout.getId();
        submission.vendorId = payout.getVendorId();
        submission.vendorName = payout.getVendorName();
        submission.periodStart = payout.getPeriodStart();
        submission.periodEnd = payout.getPeriodEnd();
        submission.totalSales = payout.getTotalSales() != null ? payout.getTotalSales().doubleValue() : 0;
        submission.totalCost = payout.getTotalCost() != null ? payout.getTotalCost().doubleValue() : 0;
        submission.totalPayout = payout.getTotalPayout() != null ? payout.getTotalPayout().doubleValue() : 0;
        submission.commissionRate = payout.getCommissionRate() != null ? payout.getCommissionRate().doubleValue() : 0;
        submission.itemCount = payout.getItemCount();
        submission.transactionCount = payout.getTransactionCount();
        submission.status = payout.getStatus() != null ? payout.getStatus().name() : "PENDING";
        submission.paidAt = payout.getPaidAt();
        submission.paidBy = payout.getPaidBy();
        submission.paymentMethod = payout.getPaymentMethod();
        submission.paymentReference = payout.getPaymentReference();
        submission.notes = payout.getNotes();
        submission.createdAt = payout.getCreatedAt();
        submission.updatedAt = payout.getUpdatedAt();
        
        // Convert items
        if (payout.getItems() != null) {
            submission.items = payout.getItems().stream()
                    .map(this::convertItemToSubmission)
                    .collect(Collectors.toList());
        }
        
        return submission;
    }
    
    /**
     * Convert VendorPayoutItem to submission DTO
     */
    private VendorPayoutItemSubmission convertItemToSubmission(VendorPayoutItem item) {
        VendorPayoutItemSubmission submission = new VendorPayoutItemSubmission();
        submission.itemId = item.getId();
        submission.saleId = item.getSaleId();
        submission.saleItemId = item.getSaleItemId();
        submission.productId = item.getProductId();
        submission.productName = item.getProductName();
        submission.productSku = item.getProductSku();
        submission.quantity = item.getQuantity();
        submission.unitPrice = item.getUnitPrice() != null ? item.getUnitPrice().doubleValue() : 0;
        submission.unitCost = item.getUnitCost() != null ? item.getUnitCost().doubleValue() : 0;
        submission.saleAmount = item.getSaleAmount() != null ? item.getSaleAmount().doubleValue() : 0;
        submission.costAmount = item.getCostAmount() != null ? item.getCostAmount().doubleValue() : 0;
        submission.discountAmount = item.getDiscountAmount() != null ? item.getDiscountAmount().doubleValue() : 0;
        submission.commissionRate = item.getCommissionRate() != null ? item.getCommissionRate().doubleValue() : 0;
        submission.payoutAmount = item.getPayoutAmount() != null ? item.getPayoutAmount().doubleValue() : 0;
        submission.saleDate = item.getSaleDate();
        submission.paymentMethod = item.getPaymentMethod();
        
        return submission;
    }
    
    // ==================== DTOs ====================
    
    /**
     * Batch request for vendor payouts
     */
    public static class BatchVendorPayoutRequest {
        public List<VendorPayoutSubmission> payouts;
    }
    
    /**
     * Vendor payout submission DTO
     */
    public static class VendorPayoutSubmission {
        public String payoutId;
        public String vendorId;
        public String vendorName;
        public String periodStart;
        public String periodEnd;
        public Double totalSales;
        public Double totalCost;
        public Double totalPayout;
        public Double commissionRate;
        public Integer itemCount;
        public Integer transactionCount;
        public String status;
        public String paidAt;
        public String paidBy;
        public String paymentMethod;
        public String paymentReference;
        public String notes;
        public String createdAt;
        public String updatedAt;
        public List<VendorPayoutItemSubmission> items;
    }
    
    /**
     * Vendor payout item submission DTO
     */
    public static class VendorPayoutItemSubmission {
        public String itemId;
        public String saleId;
        public Integer saleItemId;
        public String productId;
        public String productName;
        public String productSku;
        public Integer quantity;
        public Double unitPrice;
        public Double unitCost;
        public Double saleAmount;
        public Double costAmount;
        public Double discountAmount;
        public Double commissionRate;
        public Double payoutAmount;
        public String saleDate;
        public String paymentMethod;
    }
    
    /**
     * Batch response for vendor payouts
     */
    public static class BatchVendorPayoutResponse {
        public Integer processed;
        public Integer failed;
        public List<BatchVendorPayoutResult> results;
    }
    
    /**
     * Individual payout result
     */
    public static class BatchVendorPayoutResult {
        public String payoutId;
        public String status; // "created", "updated", "duplicate", "failed"
        public String error;
    }
}
