package com.pos.service;

import com.pos.api.ApiClient;
import com.pos.api.dto.SaleSubmission;
import com.pos.api.dto.SaleResponse;
import com.pos.api.dto.StoreSettingsResponse;
import com.pos.api.dto.BatchSaleRequest;
import com.pos.api.dto.BatchSaleResponse;
import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import com.pos.model.SaleItem;
import com.pos.model.Product;
import com.pos.model.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.text.NumberFormat;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for handling sales transactions
 */
public class SalesService {
    private static final Logger logger = LoggerFactory.getLogger(SalesService.class);
    private static SalesService instance;

    private final ApiClient apiClient;
    private final DatabaseManager dbManager;
    private final ConfigManager config;
    private final ProductSyncService productSyncService;
    private final OfflineSyncService offlineSyncService;

    private SalesService() {
        this.apiClient = ApiClient.getInstance();
        this.dbManager = DatabaseManager.getInstance();
        this.config = ConfigManager.getInstance();
        this.productSyncService = ProductSyncService.getInstance();
        this.offlineSyncService = OfflineSyncService.getInstance();
    }

    public static synchronized SalesService getInstance() {
        if (instance == null) {
            instance = new SalesService();
        }
        return instance;
    }

    /**
     * Process a sale transaction and submit to backend
     */
    public boolean processSale(List<SaleItem> items, String paymentMethod, BigDecimal total, String cashierName) {
        return processSale(items, paymentMethod, total, cashierName, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null,
                false);
    }

    public String processSaleAndGetId(List<SaleItem> items, String paymentMethod, BigDecimal total, String cashierName,
            BigDecimal amountReceived, BigDecimal change) {
        return processSaleInternal(items, paymentMethod, total, cashierName, BigDecimal.ZERO, BigDecimal.ZERO, null,
                amountReceived, change, false, null);
    }

    /**
     * Process a sale with explicit discount and return the sale ID
     */
    public String processSaleAndGetId(List<SaleItem> items, String paymentMethod, BigDecimal total, String cashierName,
            BigDecimal saleDiscount, BigDecimal tax, BigDecimal amountReceived, BigDecimal change) {
        return processSaleInternal(items, paymentMethod, total, cashierName, saleDiscount, tax, null, amountReceived,
                change, false, null);
    }

    public String processSaleAndGetId(List<SaleItem> items, String paymentMethod, BigDecimal total, String cashierName,
            BigDecimal saleDiscount, BigDecimal tax, BigDecimal amountReceived, BigDecimal change,
            SaleSubmission.PaymentDetails paymentDetails) {
        return processSaleInternal(items, paymentMethod, total, cashierName, saleDiscount, tax, null, amountReceived,
                change, false, paymentDetails);
    }

    public String processSaleAndGetId(List<SaleItem> items, String paymentMethod, BigDecimal total, String cashierName,
            List<Payment> splitPayments) {
        return processSaleAndGetId(items, paymentMethod, total, cashierName, BigDecimal.ZERO, BigDecimal.ZERO,
                splitPayments);
    }

    /**
     * Process a split payment sale with explicit discount and return the sale ID
     */
    public String processSaleAndGetId(List<SaleItem> items, String paymentMethod, BigDecimal total, String cashierName,
            BigDecimal saleDiscount, BigDecimal tax, List<Payment> splitPayments) {
        return processSaleAndGetId(items, paymentMethod, total, cashierName, saleDiscount, tax, splitPayments, null);
    }

    public String processSaleAndGetId(List<SaleItem> items, String paymentMethod, BigDecimal total, String cashierName,
            BigDecimal saleDiscount, BigDecimal tax, List<Payment> splitPayments,
            SaleSubmission.PaymentDetails paymentDetails) {
        boolean isSplitPayment = splitPayments != null && splitPayments.size() > 1;
        String effectivePaymentMethod = isSplitPayment ? "SPLIT" : paymentMethod;
        return processSaleInternal(items, effectivePaymentMethod, total, cashierName, saleDiscount, tax, splitPayments,
                null, null, isSplitPayment, paymentDetails);
    }

    public boolean processSale(List<SaleItem> items, String paymentMethod, BigDecimal total, String cashierName,
            BigDecimal amountReceived, BigDecimal change) {
        return processSale(items, paymentMethod, total, cashierName, BigDecimal.ZERO, BigDecimal.ZERO, null,
                amountReceived, change, false);
    }

    public boolean processSale(List<SaleItem> items, String paymentMethod, BigDecimal total, String cashierName,
            List<Payment> splitPayments) {
        boolean isSplit = splitPayments != null && splitPayments.size() > 1;
        String method = isSplit ? "SPLIT" : paymentMethod;
        return processSale(items, method, total, cashierName, BigDecimal.ZERO, BigDecimal.ZERO, splitPayments, null,
                null, isSplit);
    }

    public boolean processSale(List<SaleItem> items, String paymentMethod, BigDecimal total, String cashierName,
            List<Payment> splitPayments, BigDecimal amountReceived, BigDecimal change) {
        boolean isSplit = splitPayments != null && splitPayments.size() > 1;
        return processSale(items, paymentMethod, total, cashierName, BigDecimal.ZERO, BigDecimal.ZERO, splitPayments,
                amountReceived, change, isSplit);
    }

    public boolean processSale(List<SaleItem> items, String paymentMethod, BigDecimal total, String cashierName,
            BigDecimal saleDiscount, BigDecimal tax, List<Payment> splitPayments, BigDecimal amountReceived,
            BigDecimal change, boolean isSplitPayment) {
        String saleId = processSaleInternal(items, paymentMethod, total, cashierName, saleDiscount, tax, splitPayments,
                amountReceived, change, isSplitPayment, null);
        return saleId != null;
    }

    /**
     * Internal method to process sale and return sale ID
     */
    private String processSaleInternal(List<SaleItem> items, String paymentMethod, BigDecimal total, String cashierName,
            BigDecimal saleDiscount, BigDecimal tax, List<Payment> splitPayments, BigDecimal amountReceived,
            BigDecimal change, boolean isSplitPayment, SaleSubmission.PaymentDetails paymentDetails) {
        try {
            // CRITICAL: Validate active shift before processing any sale
            try {
                ShiftService shiftService = ShiftService.getInstance();
                shiftService.requireActiveShift();
            } catch (IllegalStateException e) {
                logger.error("Cannot process sale: {}", e.getMessage());
                throw new RuntimeException(e.getMessage());
            }

            logger.info("Processing sale: {} items, payment: {}, total: {}",
                    items.size(), paymentMethod, total);

            // Get current user info
            UserAuthService authService = UserAuthService.getInstance();
            String currentUserName = authService.getCurrentUserName();
            String posUserId = null;
            String cashierId = null;

            // Check if POS user is logged in
            if (authService.isPosUser()) {
                posUserId = authService.getCurrentPosUserId();
                cashierId = posUserId; // Use POS user ID as cashier ID
                // Use POS user name if cashierName not provided
                if (cashierName == null || cashierName.isEmpty()) {
                    cashierName = currentUserName != null ? currentUserName : "Cashier";
                }
            } else {
                cashierId = authService.getCurrentUserId(); // Use regular user ID as cashier ID
                // Use current user name if cashierName not provided
                if (cashierName == null || cashierName.isEmpty()) {
                    cashierName = currentUserName != null ? currentUserName : "Cashier";
                }
            }

            // Calculate totals
            // Get base subtotal (before discounts)
            BigDecimal baseSubtotal = items.stream()
                    .map(item -> {
                        BigDecimal itemPrice = item.getPrice(paymentMethod);
                        return itemPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Calculate line item discounts
            BigDecimal lineItemDiscounts = items.stream()
                    .map(SaleItem::getTotalDiscount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Get sale-level discount (passed as part of total calculation)
            // Note: saleDiscount and tax are now passed explicitly from the UI
            BigDecimal totalDiscount = lineItemDiscounts.add(saleDiscount);
            BigDecimal finalSubtotal = baseSubtotal.subtract(totalDiscount);

            // Ensure strict 2-decimal precision for components first
            tax = tax.setScale(2, java.math.RoundingMode.HALF_UP);
            totalDiscount = totalDiscount.setScale(2, java.math.RoundingMode.HALF_UP);

            // Track tax surcharge separately to pass to submission GPI calculation
            BigDecimal taxSurcharge = BigDecimal.ZERO;

            // Adjust Subtotal and Total based on Payment Method logic
            BigDecimal calculatedTotal;

            if (isSplitPayment && splitPayments != null && !splitPayments.isEmpty()) {
                // For split payments, the authoritative Total is the sum of all payments
                // collected
                calculatedTotal = splitPayments.stream()
                        .map(Payment::getAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .setScale(2, java.math.RoundingMode.HALF_UP);

                // Back-calculate subtotal to maintain consistency: Total = Subtotal + Tax
                // This implicitly captures any surcharges or roundings in the subtotal
                // (Revenue)
                finalSubtotal = calculatedTotal.subtract(tax);

            } else if ("CARD".equals(paymentMethod)) {
                // For CARD payments, apply surcharge on Tax
                // The baseSubtotal already includes item-level surcharges.
                // But we must also collect surcharge on the tax amount (as the merchant pays
                // fees on the tax collected too)
                try {
                    var settings = com.pos.service.SettingsService.getInstance().getCardSurchargeSettings();
                    if (settings != null && settings.enabled && settings.percent != null) {
                        BigDecimal percent = BigDecimal.valueOf(settings.percent);
                        // Calculate surcharge on tax
                        taxSurcharge = tax.multiply(percent)
                                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);

                        // Rounding can be tricky. If surcharge is < 0.005 it becomes 0.
                        // $0.80 * 4% = 0.032 -> 0.03.

                        if (taxSurcharge.compareTo(BigDecimal.ZERO) > 0) {
                            logger.info("Adding surcharge on tax to subtotal: {}", taxSurcharge);
                            finalSubtotal = finalSubtotal.add(taxSurcharge);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Could not load surcharge settings", e);
                }

                finalSubtotal = finalSubtotal.setScale(2, java.math.RoundingMode.HALF_UP);
                calculatedTotal = finalSubtotal.add(tax).setScale(2, java.math.RoundingMode.HALF_UP);

            } else {
                // Standard calculation (CASH, etc)
                finalSubtotal = finalSubtotal.setScale(2, java.math.RoundingMode.HALF_UP);
                calculatedTotal = finalSubtotal.add(tax).setScale(2, java.math.RoundingMode.HALF_UP);
            }

            if (calculatedTotal.compareTo(total.setScale(2, java.math.RoundingMode.HALF_UP)) != 0) {
                logger.warn(
                        "Total mismatch fixed: calculated={}, provided={}. Breakdown: Subtotal={}, Discount={}, Tax={}",
                        calculatedTotal, total, finalSubtotal, totalDiscount, tax);
                // We use the calculated total to ensure data consistency
            }

            // Re-calculate change if amount received is present, to match the corrected
            // total
            if (amountReceived != null) {
                amountReceived = amountReceived.setScale(2, java.math.RoundingMode.HALF_UP);
                change = amountReceived.subtract(calculatedTotal);
            }

            StoreSettingsResponse.StoreSettingsData storeSettings = SettingsService.getInstance().getSettings();
            BigDecimal minSaleFloor = storeSettings != null ? storeSettings.minimumSaleAmount : null;
            if (minSaleFloor != null && minSaleFloor.compareTo(BigDecimal.ZERO) > 0
                    && calculatedTotal.compareTo(minSaleFloor) < 0) {
                String formattedMin = NumberFormat.getCurrencyInstance().format(minSaleFloor);
                String formattedTotal = NumberFormat.getCurrencyInstance().format(calculatedTotal);
                throw new IllegalArgumentException(
                        "This sale total (" + formattedTotal + ") is below the store minimum of " + formattedMin + ".");
            }

            // Generate unique sale ID
            String saleId = generateSaleId();

            // Create sale submission using the authoritative calculated values
            // Pass taxSurcharge so it can be included in the total GPI
            SaleSubmission submission = createSaleSubmission(
                    saleId, items, finalSubtotal, totalDiscount, tax, calculatedTotal, paymentMethod, cashierName,
                    cashierId,
                    posUserId,
                    amountReceived, change, splitPayments, isSplitPayment, taxSurcharge, paymentDetails);

            // Get active shift for local storage and tracking
            com.pos.service.ShiftService shiftService = com.pos.service.ShiftService.getInstance();
            com.pos.api.dto.ShiftResponse.ShiftData activeShift = shiftService.getActiveShift();

            // Persist sale, shift totals, and stock in one transaction
            persistSaleInSingleTransaction(
                    submission, splitPayments, isSplitPayment, activeShift, items, paymentMethod, total);

            // Trigger outbound sync (non-blocking) — do not wait for backend API
            com.pos.sync.SyncManager.getInstance().triggerOutboundSync();

            // Link any pending age verifications to this sale (non-critical, separate tx)
            try {
                AgeVerificationService ageVerificationService = AgeVerificationService.getInstance();
                ageVerificationService.linkVerificationsToSale(saleId);
            } catch (Exception e) {
                logger.warn("Failed to link age verifications to sale {}: {}", saleId, e.getMessage());
            }

            notifySaleCompletedAsync(saleId, total, items.size(), paymentMethod, cashierName);

            logger.info("Sale {} stored locally; backend sync queued", saleId);
            return saleId;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error processing sale", e);
            return null;
        }
    }

    /**
     * Persist sale record, shift totals, and stock deduction in a single DB transaction.
     */
    private void persistSaleInSingleTransaction(
            SaleSubmission submission,
            List<Payment> splitPayments,
            boolean isSplitPayment,
            com.pos.api.dto.ShiftResponse.ShiftData activeShift,
            List<SaleItem> items,
            String paymentMethod,
            BigDecimal total) throws SQLException {
        InventoryService inventoryService = InventoryService.getInstance();
        ShiftService shiftService = ShiftService.getInstance();
        String shiftId = activeShift != null ? activeShift.id : null;

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                storeSaleLocallyOnConnection(conn, submission, splitPayments, isSplitPayment, shiftId);

                if (activeShift != null) {
                    BigDecimal totalGpi = calculateSaleGpi(items, paymentMethod);
                    if (isSplitPayment && splitPayments != null && splitPayments.size() > 1) {
                        for (Payment payment : splitPayments) {
                            BigDecimal paymentGpi = BigDecimal.ZERO;
                            if ("CARD".equals(payment.getPaymentMethod()) && total.compareTo(BigDecimal.ZERO) > 0) {
                                paymentGpi = totalGpi.multiply(payment.getAmount()).divide(total, 2,
                                        java.math.RoundingMode.HALF_UP);
                            }
                            shiftService.updateShiftTotals(conn, activeShift.id, payment.getAmount(),
                                    payment.getPaymentMethod(),
                                    paymentGpi.setScale(2, java.math.RoundingMode.HALF_UP));
                        }
                    } else {
                        shiftService.updateShiftTotals(conn, activeShift.id, total, paymentMethod,
                                totalGpi.setScale(2, java.math.RoundingMode.HALF_UP));
                    }
                }

                List<InventoryService.BatchAdjustmentItem> adjustmentItems = buildStockAdjustments(items);
                if (!adjustmentItems.isEmpty()) {
                    inventoryService.adjustStockBatch(conn, adjustmentItems, "SALE", "Sale: " + submission.saleId);
                }

                conn.commit();
                inventoryService.flushPendingStockSync();
            } catch (Exception e) {
                conn.rollback();
                inventoryService.clearPendingStockSync();
                throw e instanceof SQLException ? (SQLException) e : new SQLException("Failed to persist sale", e);
            }
        }
    }

    private BigDecimal calculateSaleGpi(List<SaleItem> items, String paymentMethod) {
        BigDecimal totalGpi = BigDecimal.ZERO;
        String effectivePaymentMethod = "SPLIT".equals(paymentMethod) ? "CARD" : paymentMethod;
        if ("CARD".equals(effectivePaymentMethod)) {
            for (SaleItem item : items) {
                BigDecimal itemCardPrice = item.getPrice("CARD");
                BigDecimal itemCashPrice = item.getPrice("CASH");
                BigDecimal itemGpi = itemCardPrice.subtract(itemCashPrice)
                        .multiply(BigDecimal.valueOf(item.getQuantity()));
                totalGpi = totalGpi.add(itemGpi);
            }
        }
        return totalGpi;
    }

    private List<InventoryService.BatchAdjustmentItem> buildStockAdjustments(List<SaleItem> items) {
        List<InventoryService.BatchAdjustmentItem> adjustmentItems = new ArrayList<>();
        for (SaleItem item : items) {
            Product product = item.getProduct();
            String productName = product.getName();
            if (productName != null && productName.contains("(Open)")) {
                continue;
            }
            String productId = product.getId();
            if (productId == null || productId.isEmpty()) {
                continue;
            }
            adjustmentItems.add(new InventoryService.BatchAdjustmentItem(productId, -item.getQuantity()));
        }
        return adjustmentItems;
    }

    private void notifySaleCompletedAsync(String saleId, BigDecimal total, int itemCount, String paymentMethod,
            String cashierName) {
        new Thread(() -> {
            try {
                com.pos.sync.WebSocketClient wsClient = com.pos.sync.WebSocketClient.getInstance();
                if (wsClient.isConnected()) {
                    wsClient.emitSaleCompleted(saleId, total.doubleValue(), itemCount, paymentMethod, cashierName);
                }
            } catch (Exception e) {
                logger.debug("Failed to broadcast sale via WebSocket: {}", e.getMessage());
            }
        }, "SaleWebSocketNotify").start();
    }

    /**
     * Deduct stock for sold items and create inventory logs
     */
    private void deductStockForSale(List<SaleItem> items, String saleId) {
        InventoryService inventoryService = InventoryService.getInstance();
        List<InventoryService.BatchAdjustmentItem> adjustmentItems = buildStockAdjustments(items);
        if (!adjustmentItems.isEmpty()) {
            try {
                inventoryService.adjustStockBatch(adjustmentItems, "SALE", "Sale: " + saleId);
                logger.info("Batch deducted stock for {} items for sale {}", adjustmentItems.size(), saleId);
            } catch (SQLException e) {
                logger.warn("Failed to deduct stock batch for sale {}: {} - sale will proceed, stock can be reconciled later",
                        saleId, e.getMessage());
            } catch (Exception e) {
                logger.warn("Unexpected error deducting stock batch for sale {}: {} - sale will proceed",
                        saleId, e.getMessage());
            }
        }
    }

    /**
     * Submit sale to backend API
     */
    private boolean submitSaleToBackend(SaleSubmission submission) {
        try {
            ApiClient.ApiResponse<SaleResponse> response = apiClient.post(
                    "/pos/sales",
                    submission,
                    SaleResponse.class);

            SaleResponse saleResponse = response.getData();
            logger.info("Sale submitted: {}", saleResponse.sale.saleId);
            return true;
        } catch (ApiClient.ApiException e) {
            logger.error("Failed to submit sale to backend", e);
            return false;
        }
    }

    /**
     * Store sale locally for offline sync
     */
    private void storeSaleLocally(SaleSubmission submission) throws SQLException {
        storeSaleLocally(submission, null, false, null);
    }

    /**
     * Store sale locally for offline sync with split payments
     */
    private void storeSaleLocally(SaleSubmission submission, List<Payment> splitPayments, boolean isSplitPayment, String shiftId)
            throws SQLException {
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                storeSaleLocallyOnConnection(conn, submission, splitPayments, isSplitPayment, shiftId);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e instanceof SQLException ? (SQLException) e : new SQLException("Failed to store sale", e);
            }
        }
    }

    private void storeSaleLocallyOnConnection(Connection conn, SaleSubmission submission, List<Payment> splitPayments,
            boolean isSplitPayment, String shiftId) throws SQLException {
            // Insert sale
            String saleSql = """
                    INSERT INTO sales (id, sale_id, subtotal, discount, tax, total, payment_method, is_split_payment, cashier_name, cashier_id, pos_user_id, timestamp, amount_received, change, gpi, ebt_fee, shift_id, synced)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE)
                    """;

            try (PreparedStatement saleStmt = conn.prepareStatement(saleSql)) {
                saleStmt.setString(1, UUID.randomUUID().toString());
                saleStmt.setString(2, submission.saleId);
                saleStmt.setBigDecimal(3, BigDecimal.valueOf(submission.subtotal));
                saleStmt.setBigDecimal(4, BigDecimal.valueOf(submission.discount));
                saleStmt.setBigDecimal(5, BigDecimal.valueOf(submission.tax));
                saleStmt.setBigDecimal(6, BigDecimal.valueOf(submission.total));
                saleStmt.setString(7, submission.paymentMethod);
                saleStmt.setBoolean(8, isSplitPayment);
                saleStmt.setString(9, submission.cashierName);
                saleStmt.setString(10, submission.cashierId);
                saleStmt.setString(11, submission.posUserId);
                saleStmt.setString(12, submission.timestamp);
                if (submission.amountReceived != null) {
                    saleStmt.setBigDecimal(13, BigDecimal.valueOf(submission.amountReceived));
                } else {
                    saleStmt.setNull(13, java.sql.Types.DECIMAL);
                }
                if (submission.change != null) {
                    saleStmt.setBigDecimal(14, BigDecimal.valueOf(submission.change));
                } else {
                    saleStmt.setNull(14, java.sql.Types.DECIMAL);
                }
                saleStmt.setBigDecimal(15, submission.gpi != null ? BigDecimal.valueOf(submission.gpi) : BigDecimal.ZERO);
                saleStmt.setBigDecimal(16,
                        submission.ebtFee != null ? BigDecimal.valueOf(submission.ebtFee) : BigDecimal.ZERO);
                saleStmt.setString(17, shiftId);
                saleStmt.executeUpdate();
            }

            String itemSql = """
                    INSERT INTO sale_items (sale_id, product_id, sku, name, price, quantity, subtotal, discount, discount_reason, gpi, department_id, department_name, vendor_id, vendor_name, cost)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;

            try (PreparedStatement itemStmt = conn.prepareStatement(itemSql)) {
                for (SaleSubmission.SaleItemInput item : submission.items) {
                    itemStmt.setString(1, submission.saleId);
                    itemStmt.setString(2, item.productId);
                    itemStmt.setString(3, item.sku);
                    itemStmt.setString(4, item.name);
                    itemStmt.setBigDecimal(5, BigDecimal.valueOf(item.price));
                    itemStmt.setInt(6, item.quantity);
                    itemStmt.setBigDecimal(7, BigDecimal.valueOf(item.subtotal));
                    itemStmt.setBigDecimal(8, item.discount != null ? BigDecimal.valueOf(item.discount) : BigDecimal.ZERO);
                    itemStmt.setString(9, item.discountReason != null ? item.discountReason : "");
                    itemStmt.setBigDecimal(10, item.gpi != null ? BigDecimal.valueOf(item.gpi) : BigDecimal.ZERO);
                    itemStmt.setString(11, item.departmentId);
                    itemStmt.setString(12, item.departmentName);
                    itemStmt.setString(13, item.vendorId);
                    itemStmt.setString(14, item.vendorName);
                    itemStmt.setObject(15, item.cost != null ? BigDecimal.valueOf(item.cost) : null);
                    itemStmt.addBatch();
                }
                itemStmt.executeBatch();
            }

            if (splitPayments != null && !splitPayments.isEmpty()) {
                String paymentSql = """
                        INSERT INTO sale_payments (sale_id, payment_method, amount)
                        VALUES (?, ?, ?)
                        """;

                try (PreparedStatement paymentStmt = conn.prepareStatement(paymentSql)) {
                    for (Payment payment : splitPayments) {
                        paymentStmt.setString(1, submission.saleId);
                        paymentStmt.setString(2, payment.getPaymentMethod());
                        paymentStmt.setBigDecimal(3, payment.getAmount());
                        paymentStmt.addBatch();
                    }
                    paymentStmt.executeBatch();
                }
            }
    }

    /**
     * Create sale submission DTO with split payment support
     */
    private SaleSubmission createSaleSubmission(
            String saleId,
            List<SaleItem> items,
            BigDecimal subtotal,
            BigDecimal discount,
            BigDecimal tax,
            BigDecimal total,
            String paymentMethod,
            String cashierName,
            String cashierId,
            String posUserId,
            BigDecimal amountReceived,
            BigDecimal change,
            List<Payment> splitPayments,
            boolean isSplitPayment,
            BigDecimal taxSurcharge,
            SaleSubmission.PaymentDetails paymentDetails) {
        SaleSubmission submission = new SaleSubmission();
        submission.saleId = saleId;
        submission.items = items.stream().map(item -> {
            SaleSubmission.SaleItemInput itemInput = new SaleSubmission.SaleItemInput();

            // Check if this is an open department item (SKU starts with "OPEN-")
            String barcode = item.getProduct().getBarcode();
            boolean isOpenDepartmentItem = barcode != null && barcode.startsWith("OPEN-");

            // Use actual product ID if available, otherwise fallback to barcode
            // For open department items, productId will be null on the backend
            String productId = item.getProduct().getId();
            if (productId == null || productId.isEmpty()) {
                productId = item.getProduct().getBarcode();
                if (!isOpenDepartmentItem) {
                    logger.warn("Product {} has no ID, using barcode as productId", item.getProduct().getBarcode());
                }
            }

            // For open department items, don't send productId (backend will handle it)
            itemInput.productId = isOpenDepartmentItem ? null : productId;
            String actualSku = item.getProduct().getSku();
            itemInput.sku = (actualSku != null && !actualSku.isEmpty()) ? actualSku : barcode;
            itemInput.name = item.getProductName();
            // Use price based on payment method (cash price for CASH, list price for CARD)
            // For split payments, use card/list price as the base
            String effectivePaymentMethod = "SPLIT".equals(paymentMethod) ? "CARD" : paymentMethod;
            itemInput.price = item.getPrice(effectivePaymentMethod).setScale(2, java.math.RoundingMode.HALF_UP)
                    .doubleValue();
            itemInput.quantity = item.getQuantity();
            // Subtotal should be the base total before discount
            // For split payments, use card price base total
            BigDecimal itemSubtotal;
            if ("SPLIT".equals(paymentMethod)) {
                itemSubtotal = item.getBaseTotalAtCardPrice();
            } else {
                itemSubtotal = item.getBaseTotal();
            }
            itemInput.subtotal = itemSubtotal.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();

            // Include discount amount
            itemInput.discount = item.getTotalDiscount().setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
            // Include discount reason
            itemInput.discountReason = item.getDiscountReason();

            // Include GPI (Gross Profit Increase) - difference between card and cash price
            // Only if payment is CARD or SPLIT
            if ("CARD".equals(effectivePaymentMethod)) {
                BigDecimal itemCardPrice = item.getPrice("CARD");
                BigDecimal itemCashPrice = item.getPrice("CASH");
                BigDecimal itemGpi = itemCardPrice.subtract(itemCashPrice)
                        .multiply(BigDecimal.valueOf(item.getQuantity()));
                itemInput.gpi = itemGpi.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
            } else {
                itemInput.gpi = 0.0;
            }

            // Set open department item fields
            itemInput.isOpenDepartmentItem = isOpenDepartmentItem;
            if (isOpenDepartmentItem) {
                // Department ID is stored in the product for open department items
                itemInput.departmentId = item.getProduct().getDepartmentId();
                // Extract department name from item name (format: "DeptName (Open)")
                String itemName = item.getProductName();
                if (itemName != null && itemName.endsWith(" (Open)")) {
                    itemInput.departmentName = itemName.substring(0, itemName.length() - 7);
                } else {
                    itemInput.departmentName = itemName;
                }
            } else {
                // For regular products, also capture department info for reporting
                itemInput.departmentId = item.getProduct().getDepartmentId();
                // Department name will be looked up from the departments table during reporting
                itemInput.departmentName = null; // Will be resolved from department_id
            }

            // Capture vendor info for payout tracking
            itemInput.vendorId = item.getProduct().getVendorId();
            itemInput.vendorName = item.getProduct().getVendorName();
            if (item.getProduct().getCost() != null) {
                itemInput.cost = item.getProduct().getCost().setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
            }

            return itemInput;
        }).toList();

        submission.subtotal = subtotal.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
        submission.discount = discount.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
        submission.tax = tax.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
        submission.total = total.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
        submission.paymentMethod = paymentMethod;
        submission.isSplitPayment = isSplitPayment;
        submission.cashierName = cashierName;
        submission.cashierId = cashierId; // Set cashier ID (user who made the sale)
        submission.posUserId = posUserId; // Set POS user ID if available

        // Calculate total GPI from items
        // Use BigDecimal for summation to preserve precision, then round to 2 decimals
        // for valid currency value
        BigDecimal totalGpi = submission.items.stream()
                .filter(i -> i.gpi != null)
                .map(i -> BigDecimal.valueOf(i.gpi))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Add tax surcharge to total GPI if provided
        if (taxSurcharge != null) {
            totalGpi = totalGpi.add(taxSurcharge);
        }

        // Round to 2 decimals to match currency precision (and user expectation of
        // "0.43" not "0.4302")
        // This ensures Total = Net + Tax + GPI holds true for 2-decimal currency
        submission.gpi = totalGpi.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();

        // Calculate EBT Fee ($0.10) if payment method is EBT or contains EBT in split
        submission.ebtFee = 0.0;
        boolean isEbt = "EBT".equals(paymentMethod);
        if (isSplitPayment && splitPayments != null) {
            isEbt = splitPayments.stream().anyMatch(p -> "EBT".equals(p.getPaymentMethod()));
        }

        if (isEbt) {
            submission.ebtFee = 0.10; // Fixed $0.10 fee per EBT transaction
        }

        // Set split payment details if this is a split payment
        if (isSplitPayment && splitPayments != null && !splitPayments.isEmpty()) {
            submission.splitPayments = splitPayments.stream()
                    .map(p -> new SaleSubmission.SplitPaymentDetail(p.getPaymentMethod(),
                            p.getAmount().setScale(2, java.math.RoundingMode.HALF_UP).doubleValue()))
                    .toList();
        }

        // Set cash payment details if provided (only for CASH payments)
        if ("CASH".equals(paymentMethod) && amountReceived != null && change != null) {
            submission.amountReceived = amountReceived.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
            submission.change = change.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
        }

        if (paymentDetails != null) {
            submission.paymentDetails = paymentDetails;
        }
        submission.timestamp = Instant.now().atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);
        // Set local date for proper date-based filtering on backend (fixes timezone
        // mismatch)
        submission.localDate = java.time.LocalDate.now().toString();

        return submission;
    }

    /**
     * Mark sale as synced
     */
    private void markSaleAsSynced(String saleId) throws SQLException {
        String sql = "UPDATE sales SET synced = TRUE WHERE sale_id = ?";

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, saleId);
            stmt.executeUpdate();
            conn.commit();
        }
    }

    /**
     * Generate unique sale ID
     */
    private String generateSaleId() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String random = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return "SALE-" + timestamp + "-" + random;
    }

    /**
     * Get product by barcode (from local DB or backend)
     * Products fetched from backend are automatically stored locally for offline
     * use
     */
    public Product getProductByBarcode(String barcode) {
        try {
            // Try local database first (offline-first approach)
            Product product = productSyncService.getLocalProductByBarcode(barcode);
            if (product != null) {
                logger.debug("Product found in local database by barcode: {}", barcode);
                return product;
            }

            // Some products store their scannable code in the SKU column rather
            // than the barcode column. The product-management search matches on
            // SKU too, so a scan must fall back to SKU to behave consistently.
            product = productSyncService.getLocalProductBySku(barcode);
            if (product != null) {
                logger.debug("Product found in local database by SKU fallback: {}", barcode);
                return product;
            }

            // Final local fallback: use the same LIKE-based search the product
            // management screen uses, so anything that screen can find by barcode
            // (e.g. stored with leading zeros or as a substring) also resolves on
            // a scan. We only auto-select an UNAMBIGUOUS match to avoid adding the
            // wrong item to the sale; a partial match with multiple hits is left
            // for the operator to resolve manually.
            Product likeMatch = findUniqueProductByLikeSearch(barcode);
            if (likeMatch != null) {
                logger.info("Product found via LIKE search fallback for scan: {}", barcode);
                return likeMatch;
            }

            // If not found locally, try backend (will store locally automatically)
            logger.info("Product not found locally, fetching from backend: {}", barcode);
            product = productSyncService.getProductByBarcode(barcode);
            if (product != null) {
                logger.info("Product fetched from backend and stored locally: {}", barcode);
            } else {
                logger.warn("Product not found in backend: {}", barcode);
            }
            return product;
        } catch (Exception e) {
            logger.error("Error looking up product: {}", barcode, e);
            return null;
        }
    }

    /**
     * Resolve a scanned code using the same LIKE-based matching as the product
     * management search, but only return a result when it is unambiguous so we
     * never add the wrong item to a sale.
     *
     * Selection rules:
     * 1. If any result's barcode or SKU equals the scanned code (trimmed,
     *    case-insensitive), return that result.
     * 2. Otherwise, if the search returns exactly one product, return it.
     * 3. Otherwise (zero or multiple ambiguous matches), return null.
     */
    private Product findUniqueProductByLikeSearch(String barcode) {
        if (barcode == null || barcode.trim().isEmpty()) {
            return null;
        }
        String needle = barcode.trim();
        List<Product> matches = searchProducts(needle);
        if (matches.isEmpty()) {
            return null;
        }

        for (Product p : matches) {
            if ((p.getBarcode() != null && p.getBarcode().trim().equalsIgnoreCase(needle))
                    || (p.getSku() != null && p.getSku().trim().equalsIgnoreCase(needle))) {
                return p;
            }
        }

        if (matches.size() == 1) {
            return matches.get(0);
        }

        logger.warn("Scan '{}' matched {} products via LIKE search; ambiguous, not auto-selecting",
                needle, matches.size());
        return null;
    }

    /**
     * Search products by name, department, SKU, or barcode (optimized with
     * relevance ordering)
     * Results are ordered by relevance:
     * 1. Exact matches (name, SKU, or barcode)
     * 2. Starts with search term
     * 3. Contains search term (in name, SKU, barcode, or department)
     */
    public List<Product> searchProducts(String searchTerm) {
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            return List.of();
        }

        try (Connection conn = dbManager.getConnection()) {
            // Trim and prepare search patterns
            String trimmedTerm = searchTerm.trim();
            String searchPattern = "%" + trimmedTerm + "%";
            String startsWithPattern = trimmedTerm + "%";
            String exactPattern = trimmedTerm;

            // H2-compatible query: include CASE expression in SELECT to allow ORDER BY with
            // DISTINCT
            // The relevance_score column is used for ordering
            String sql = """
                    SELECT p.id, p.name, p.sku, p.barcode, p.price, p.list_price,
                           p.stock_quantity, p.status, p.department_id, p.updated_at,
                           p.sync_version, p.created_locally, p.synced,
                           CASE
                               WHEN LOWER(p.name) = LOWER(?) THEN 1
                               WHEN LOWER(p.sku) = LOWER(?) THEN 1
                               WHEN LOWER(p.barcode) = LOWER(?) THEN 1
                               WHEN LOWER(p.name) LIKE LOWER(?) THEN 2
                               WHEN LOWER(p.sku) LIKE LOWER(?) THEN 2
                               WHEN LOWER(p.barcode) LIKE LOWER(?) THEN 2
                               ELSE 3
                           END AS relevance_score
                    FROM products p
                    LEFT JOIN departments d ON p.department_id = d.id
                    WHERE LOWER(p.name) LIKE LOWER(?)
                       OR LOWER(p.sku) LIKE LOWER(?)
                       OR LOWER(p.barcode) LIKE LOWER(?)
                       OR LOWER(d.name) LIKE LOWER(?)
                    ORDER BY relevance_score ASC, p.name ASC
                    LIMIT 100
                    """;

            List<Product> products = new ArrayList<>();

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                // CASE expression patterns for relevance scoring (in SELECT)
                stmt.setString(1, exactPattern); // exact name match
                stmt.setString(2, exactPattern); // exact sku match
                stmt.setString(3, exactPattern); // exact barcode match
                stmt.setString(4, startsWithPattern); // name starts with
                stmt.setString(5, startsWithPattern); // sku starts with
                stmt.setString(6, startsWithPattern); // barcode starts with

                // WHERE clause patterns
                stmt.setString(7, searchPattern); // name contains
                stmt.setString(8, searchPattern); // sku contains
                stmt.setString(9, searchPattern); // barcode contains
                stmt.setString(10, searchPattern); // department name contains

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    String id = rs.getString("id");
                    String sku = rs.getString("sku");
                    String barcode = rs.getString("barcode");
                    String name = rs.getString("name");
                    BigDecimal price = rs.getBigDecimal("price");
                    int stock = rs.getInt("stock_quantity");
                    String departmentId = rs.getString("department_id");

                    Product product = new Product(id, sku, barcode, name, price, stock, departmentId, null, null, null);
                    products.add(product);
                }
            }

            logger.info("Found {} products matching: {} (search term: {})", products.size(), searchTerm, trimmedTerm);
            return products;
        } catch (SQLException e) {
            logger.error("Error searching products", e);
            return List.of();
        }
    }

    /**
     * Get all departments
     * 
     * @return Map of Department ID to Department Name
     */
    public java.util.Map<String, String> getDepartments() {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT id, name FROM departments ORDER BY name";

            java.util.Map<String, String> departments = new java.util.LinkedHashMap<>();
            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    departments.put(rs.getString("id"), rs.getString("name"));
                }
            }
            return departments;
        } catch (SQLException e) {
            logger.error("Error fetching departments", e);
            return java.util.Collections.emptyMap();
        }
    }

    /**
     * Get products by department (loads all - use paginated version for large
     * datasets)
     * 
     * @param departmentId Department ID (null for all)
     * @return List of products
     */
    public List<Product> getProductsByDepartment(String departmentId) {
        // For backwards compatibility, delegate to paginated version with default limit
        return getProductsByDepartment(departmentId, 0, 50);
    }

    /**
     * Get products by department with pagination
     * 
     * @param departmentId Department ID (null for all)
     * @param offset       Starting index (0-based)
     * @param limit        Maximum number of products to return
     * @return List of products
     */
    public List<Product> getProductsByDepartment(String departmentId, int offset, int limit) {
        try (Connection conn = dbManager.getConnection()) {
            String sql;
            if (departmentId == null) {
                sql = "SELECT * FROM products ORDER BY name LIMIT ? OFFSET ?";
            } else {
                sql = "SELECT * FROM products WHERE department_id = ? ORDER BY name LIMIT ? OFFSET ?";
            }

            List<Product> products = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (departmentId != null) {
                    stmt.setString(1, departmentId);
                    stmt.setInt(2, limit);
                    stmt.setInt(3, offset);
                } else {
                    stmt.setInt(1, limit);
                    stmt.setInt(2, offset);
                }

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    String id = rs.getString("id");
                    String sku = rs.getString("sku");
                    String barcode = rs.getString("barcode");
                    String name = rs.getString("name");
                    BigDecimal price = rs.getBigDecimal("price");
                    int stock = rs.getInt("stock_quantity");
                    String deptId = rs.getString("department_id");

                    Product product = new Product(id, sku, barcode, name, price, stock, deptId, null, null, null);
                    products.add(product);
                }
            }
            return products;
        } catch (SQLException e) {
            logger.error("Error fetching products by department", e);
            return List.of();
        }
    }

    /**
     * Get total product count by department
     * 
     * @param departmentId Department ID (null for all)
     * @return Total number of products
     */
    public int getProductCount(String departmentId) {
        try (Connection conn = dbManager.getConnection()) {
            String sql;
            if (departmentId == null) {
                sql = "SELECT COUNT(*) FROM products";
            } else {
                sql = "SELECT COUNT(*) FROM products WHERE department_id = ?";
            }

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                if (departmentId != null) {
                    stmt.setString(1, departmentId);
                }

                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
            return 0;
        } catch (SQLException e) {
            logger.error("Error counting products by department", e);
            return 0;
        }
    }

    /**
     * Get count of pending sales that haven't been synced.
     */
    public int getPendingSalesCount() {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "SELECT COUNT(*) FROM sales WHERE synced = FALSE";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
            return 0;
        } catch (SQLException e) {
            logger.error("Error counting pending sales", e);
            return 0;
        }
    }

    /**
     * Get pending sales for batch submission
     */
    public List<SaleSubmission> getPendingSales(int limit) throws SQLException {
        String sql = """
                SELECT s.* FROM sales s
                WHERE s.synced = FALSE
                ORDER BY s.created_at ASC
                LIMIT ?
                """;

        List<SaleSubmission> pendingSales = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String saleId = rs.getString("sale_id");
                SaleSubmission submission = reconstructSaleSubmission(conn, saleId);
                if (submission != null) {
                    pendingSales.add(submission);
                }
            }
        }

        return pendingSales;
    }

    /**
     * Reconstruct SaleSubmission from database
     */
    private SaleSubmission reconstructSaleSubmission(Connection conn, String saleId) throws SQLException {
        // Get sale
        String saleSql = "SELECT * FROM sales WHERE sale_id = ?";
        SaleSubmission submission = null;

        try (PreparedStatement saleStmt = conn.prepareStatement(saleSql)) {
            saleStmt.setString(1, saleId);
            ResultSet saleRs = saleStmt.executeQuery();

            if (saleRs.next()) {
                submission = new SaleSubmission();
                submission.saleId = saleRs.getString("sale_id");
                submission.subtotal = saleRs.getBigDecimal("subtotal").doubleValue();
                submission.discount = saleRs.getBigDecimal("discount").doubleValue();
                submission.tax = saleRs.getBigDecimal("tax").doubleValue();
                submission.total = saleRs.getBigDecimal("total").doubleValue();
                submission.paymentMethod = saleRs.getString("payment_method");
                submission.cashierName = saleRs.getString("cashier_name");
                submission.cashierId = saleRs.getString("cashier_id");
                submission.posUserId = saleRs.getString("pos_user_id");
                submission.timestamp = saleRs.getString("timestamp");
                // Read cash payment details
                BigDecimal amountReceived = saleRs.getBigDecimal("amount_received");
                if (amountReceived != null) {
                    submission.amountReceived = amountReceived.doubleValue();
                }
                BigDecimal change = saleRs.getBigDecimal("change");
                if (change != null) {
                    submission.change = change.doubleValue();
                }

                // Get sale items
                String itemSql = "SELECT * FROM sale_items WHERE sale_id = ?";
                List<SaleSubmission.SaleItemInput> items = new ArrayList<>();

                try (PreparedStatement itemStmt = conn.prepareStatement(itemSql)) {
                    itemStmt.setString(1, saleId);
                    ResultSet itemRs = itemStmt.executeQuery();

                    while (itemRs.next()) {
                        SaleSubmission.SaleItemInput item = new SaleSubmission.SaleItemInput();
                        item.productId = itemRs.getString("product_id");
                        item.sku = itemRs.getString("sku");
                        item.name = itemRs.getString("name");
                        item.price = itemRs.getBigDecimal("price").doubleValue();
                        item.quantity = itemRs.getInt("quantity");
                        item.subtotal = itemRs.getBigDecimal("subtotal").doubleValue();
                        items.add(item);
                    }
                }

                submission.items = items;
            }
        }

        return submission;
    }

    /**
     * Submit batch of sales to backend
     */
    public BatchSaleResponse submitBatchSales(List<SaleSubmission> sales) throws ApiClient.ApiException {
        if (sales.isEmpty()) {
            return new BatchSaleResponse();
        }

        String deviceId = config.getProperty("device.id", "");
        String syncTimestamp = Instant.now().atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);

        BatchSaleRequest request = new BatchSaleRequest(sales, deviceId, syncTimestamp);

        ApiClient.ApiResponse<BatchSaleResponse> response = apiClient.post(
                "/pos/sales/batch",
                request,
                BatchSaleResponse.class);

        BatchSaleResponse batchResponse = response.getData();

        // Mark successfully synced sales
        if (batchResponse.results != null) {
            for (BatchSaleResponse.BatchSaleResult result : batchResponse.results) {
                if ("created".equals(result.status) || "duplicate".equals(result.status)) {
                    try {
                        markSaleAsSynced(result.saleId);
                    } catch (SQLException e) {
                        logger.warn("Failed to mark sale as synced: {}", result.saleId, e);
                    }
                }
            }
        }

        logger.info("Batch sale submission: {} processed, {} failed",
                batchResponse.processed, batchResponse.failed);

        return batchResponse;
    }

    /**
     * Hold a sale (save current cart temporarily)
     * 
     * @param items              Current cart items
     * @param subtotal           Current subtotal
     * @param discount           Total discount
     * @param saleDiscount       Sale-level discount
     * @param saleDiscountReason Sale-level discount reason
     * @param tax                Tax amount
     * @param total              Total amount
     * @param paymentMethod      Payment method
     * @param customerName       Customer name (optional)
     * @param note               Hold note (optional)
     * @return Hold ID if successful, null otherwise
     */
    public String holdSale(
            List<SaleItem> items,
            BigDecimal subtotal,
            BigDecimal discount,
            BigDecimal saleDiscount,
            String saleDiscountReason,
            BigDecimal tax,
            BigDecimal total,
            String paymentMethod,
            String customerName,
            String note) {
        if (items == null || items.isEmpty()) {
            logger.warn("Cannot hold empty sale");
            return null;
        }

        try (Connection conn = dbManager.getConnection()) {

            // Generate hold ID
            String holdId = "HOLD-" + Instant.now().getEpochSecond() + "-" +
                    UUID.randomUUID().toString().substring(0, 8).toUpperCase();

            // Get current user info
            UserAuthService authService = UserAuthService.getInstance();
            String cashierName = authService.getCurrentUserName();
            String cashierId = authService.getCurrentUserId() != null ? authService.getCurrentUserId() : "";
            String posUserId = authService.isPosUser() ? authService.getCurrentPosUserId() : null;

            // Calculate expiration time (default: 24 hours from now)
            long expirationHours = 24;
            try {
                String expirationConfig = config.getProperty("hold_sale.expiration_hours", "24");
                expirationHours = Long.parseLong(expirationConfig);
            } catch (Exception e) {
                logger.debug("Using default expiration hours: 24");
            }

            java.sql.Timestamp expiresAt = new java.sql.Timestamp(
                    Instant.now().plusSeconds(expirationHours * 3600).toEpochMilli());

            // Insert held sale
            String holdSql = """
                    INSERT INTO held_sales (id, hold_id, customer_name, hold_note, subtotal, discount,
                        tax, total, sale_discount, sale_discount_reason, payment_method,
                        cashier_name, cashier_id, pos_user_id, held_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                    """;

            try (PreparedStatement holdStmt = conn.prepareStatement(holdSql)) {
                holdStmt.setString(1, UUID.randomUUID().toString());
                holdStmt.setString(2, holdId);
                holdStmt.setString(3, customerName != null ? customerName : "");
                holdStmt.setString(4, note != null ? note : "");
                holdStmt.setBigDecimal(5, subtotal);
                holdStmt.setBigDecimal(6, discount);
                holdStmt.setBigDecimal(7, tax);
                holdStmt.setBigDecimal(8, total);
                holdStmt.setBigDecimal(9, saleDiscount != null ? saleDiscount : BigDecimal.ZERO);
                holdStmt.setString(10, saleDiscountReason != null ? saleDiscountReason : "");
                holdStmt.setString(11, paymentMethod);
                holdStmt.setString(12, cashierName != null ? cashierName : "");
                holdStmt.setString(13, cashierId);
                holdStmt.setString(14, posUserId);
                holdStmt.setTimestamp(15, expiresAt);
                holdStmt.executeUpdate();
            }

            // Insert held sale items
            String itemSql = """
                    INSERT INTO held_sale_items (hold_id, product_id, product_barcode, product_name,
                        sku, price, list_price, quantity, payment_method, discount_amount,
                        discount_percent, discount_reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;

            try (PreparedStatement itemStmt = conn.prepareStatement(itemSql)) {
                for (SaleItem item : items) {
                    Product product = item.getProduct();
                    itemStmt.setString(1, holdId);
                    itemStmt.setString(2, product.getId());
                    itemStmt.setString(3, product.getBarcode());
                    itemStmt.setString(4, product.getName());
                    itemStmt.setString(5, product.getBarcode()); // Use barcode as SKU if SKU not available
                    itemStmt.setBigDecimal(6, product.getPrice());
                    itemStmt.setBigDecimal(7, product.getPrice());
                    itemStmt.setInt(8, item.getQuantity());
                    itemStmt.setString(9, item.getPaymentMethod());
                    itemStmt.setBigDecimal(10,
                            item.getDiscountAmount() != null ? item.getDiscountAmount() : BigDecimal.ZERO);
                    itemStmt.setBigDecimal(11,
                            item.getDiscountPercent() != null ? item.getDiscountPercent() : BigDecimal.ZERO);
                    itemStmt.setString(12, item.getDiscountReason() != null ? item.getDiscountReason() : "");
                    itemStmt.addBatch();
                }
                itemStmt.executeBatch();
            }

            conn.commit();
            logger.info("Sale held successfully: {}", holdId);
            return holdId;
        } catch (SQLException e) {
            logger.error("Error holding sale", e);
            return null;
        }
    }

    /**
     * Get all held sales (non-expired)
     */
    public List<HeldSaleInfo> getHeldSales() {
        List<HeldSaleInfo> heldSales = new ArrayList<>();
        try (Connection conn = dbManager.getConnection()) {
            String sql = """
                    SELECT h.hold_id, h.customer_name, h.total, h.held_at, h.hold_note,
                        COUNT(i.id) as item_count
                    FROM held_sales h
                    LEFT JOIN held_sale_items i ON h.hold_id = i.hold_id
                    WHERE h.expires_at IS NULL OR h.expires_at > CURRENT_TIMESTAMP
                    GROUP BY h.hold_id, h.customer_name, h.total, h.held_at, h.hold_note
                    ORDER BY h.held_at DESC
                    """;

            try (PreparedStatement stmt = conn.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String holdId = rs.getString("hold_id");
                    String customerName = rs.getString("customer_name");
                    BigDecimal total = rs.getBigDecimal("total");
                    int itemCount = rs.getInt("item_count");
                    java.sql.Timestamp heldAtTimestamp = rs.getTimestamp("held_at");
                    String note = rs.getString("hold_note");

                    LocalDateTime heldAt = heldAtTimestamp != null ? heldAtTimestamp.toLocalDateTime()
                            : LocalDateTime.now();

                    heldSales.add(new HeldSaleInfo(holdId, customerName, total, itemCount, heldAt, note));
                }
            }
        } catch (SQLException e) {
            logger.error("Error fetching held sales", e);
        }
        return heldSales;
    }

    /**
     * Recall a held sale (load it back into cart)
     * 
     * @param holdId Hold ID to recall
     * @return HeldSaleData if successful, null otherwise
     */
    public HeldSaleData recallSale(String holdId) {
        try (Connection conn = dbManager.getConnection()) {

            // Get held sale
            String saleSql = """
                    SELECT * FROM held_sales WHERE hold_id = ?
                    """;

            HeldSaleData saleData = null;
            try (PreparedStatement saleStmt = conn.prepareStatement(saleSql)) {
                saleStmt.setString(1, holdId);
                ResultSet saleRs = saleStmt.executeQuery();

                if (saleRs.next()) {
                    saleData = new HeldSaleData();
                    saleData.holdId = holdId;
                    saleData.subtotal = saleRs.getBigDecimal("subtotal");
                    saleData.discount = saleRs.getBigDecimal("discount");
                    saleData.saleDiscount = saleRs.getBigDecimal("sale_discount");
                    saleData.saleDiscountReason = saleRs.getString("sale_discount_reason");
                    saleData.tax = saleRs.getBigDecimal("tax");
                    saleData.total = saleRs.getBigDecimal("total");
                    saleData.paymentMethod = saleRs.getString("payment_method");

                    // Get held sale items
                    String itemSql = """
                            SELECT * FROM held_sale_items WHERE hold_id = ?
                            """;

                    List<SaleItem> items = new ArrayList<>();
                    try (PreparedStatement itemStmt = conn.prepareStatement(itemSql)) {
                        itemStmt.setString(1, holdId);
                        ResultSet itemRs = itemStmt.executeQuery();

                        while (itemRs.next()) {
                            String productId = itemRs.getString("product_id");
                            String barcode = itemRs.getString("product_barcode");

                            // Try to get product from database
                            Product product = productSyncService.getLocalProductByBarcode(barcode);
                            if (product == null && productId != null) {
                                // Try by ID - query directly
                                try (Connection conn2 = dbManager.getConnection()) {
                                    String productSql = "SELECT * FROM products WHERE id = ?";
                                    try (PreparedStatement productStmt = conn2.prepareStatement(productSql)) {
                                        productStmt.setString(1, productId);
                                        ResultSet productRs = productStmt.executeQuery();
                                        if (productRs.next()) {
                                            String id = productRs.getString("id");
                                            String sku = productRs.getString("sku");
                                            String barcode2 = productRs.getString("barcode");
                                            String name = productRs.getString("name");
                                            BigDecimal price = productRs.getBigDecimal("price");
                                            int stock = productRs.getInt("stock_quantity");
                                            String departmentId = productRs.getString("department_id");
                                            product = new Product(id, sku, barcode2, name, price, stock,
                                                    departmentId, null, null, null);
                                        }
                                    }
                                } catch (SQLException e) {
                                    logger.warn("Error fetching product by ID: {}", productId, e);
                                }
                            }

                            if (product == null) {
                                logger.warn("Product not found for held sale item: barcode={}, id={}", barcode,
                                        productId);
                                // Create a placeholder product
                                product = new Product(
                                        productId != null ? productId : UUID.randomUUID().toString(),
                                        barcode != null ? barcode : "",
                                        itemRs.getString("product_name"),
                                        itemRs.getBigDecimal("price"),
                                        0, // Stock unknown
                                        null // Department unknown
                                );
                            }

                            int quantity = itemRs.getInt("quantity");
                            String paymentMethod = itemRs.getString("payment_method");
                            SaleItem item = new SaleItem(product, quantity, paymentMethod);

                            // Restore discounts
                            BigDecimal discountAmount = itemRs.getBigDecimal("discount_amount");
                            BigDecimal discountPercent = itemRs.getBigDecimal("discount_percent");
                            String discountReason = itemRs.getString("discount_reason");

                            if (discountAmount != null && discountAmount.compareTo(BigDecimal.ZERO) > 0) {
                                item.setDiscountAmount(discountAmount);
                            }
                            if (discountPercent != null && discountPercent.compareTo(BigDecimal.ZERO) > 0) {
                                item.setDiscountPercent(discountPercent);
                            }
                            if (discountReason != null && !discountReason.isEmpty()) {
                                item.setDiscountReason(discountReason);
                            }

                            items.add(item);
                        }
                    }

                    saleData.items = items;
                }
            }

            if (saleData != null) {
                logger.info("Sale recalled successfully: {}", holdId);
            } else {
                logger.warn("Held sale not found: {}", holdId);
            }

            return saleData;
        } catch (SQLException e) {
            logger.error("Error recalling sale", e);
            return null;
        }
    }

    /**
     * Delete a held sale
     */
    public boolean deleteHeldSale(String holdId) {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "DELETE FROM held_sales WHERE hold_id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, holdId);
                int rowsAffected = stmt.executeUpdate();
                conn.commit();

                if (rowsAffected > 0) {
                    logger.info("Held sale deleted: {}", holdId);
                    return true;
                } else {
                    logger.warn("Held sale not found for deletion: {}", holdId);
                    return false;
                }
            }
        } catch (SQLException e) {
            logger.error("Error deleting held sale", e);
            return false;
        }
    }

    /**
     * Clean up expired held sales
     */
    public int cleanupExpiredHeldSales() {
        try (Connection conn = dbManager.getConnection()) {
            String sql = "DELETE FROM held_sales WHERE expires_at IS NOT NULL AND expires_at < CURRENT_TIMESTAMP";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                int rowsAffected = stmt.executeUpdate();
                conn.commit();

                if (rowsAffected > 0) {
                    logger.info("Cleaned up {} expired held sales", rowsAffected);
                }
                return rowsAffected;
            }
        } catch (SQLException e) {
            logger.error("Error cleaning up expired held sales", e);
            return 0;
        }
    }

    /**
     * Info class for held sale display
     */
    public static class HeldSaleInfo {
        private String holdId;
        private String customerName;
        private BigDecimal total;
        private int itemCount;
        private LocalDateTime heldAt;
        private String note;

        public HeldSaleInfo(String holdId, String customerName, BigDecimal total,
                int itemCount, LocalDateTime heldAt, String note) {
            this.holdId = holdId;
            this.customerName = customerName != null && !customerName.isEmpty() ? customerName : "N/A";
            this.total = total;
            this.itemCount = itemCount;
            this.heldAt = heldAt;
            this.note = note != null && !note.isEmpty() ? note : "No note";
        }

        // Getters
        public String getHoldId() {
            return holdId;
        }

        public String getCustomerName() {
            return customerName;
        }

        public BigDecimal getTotal() {
            return total;
        }

        public int getItemCount() {
            return itemCount;
        }

        public LocalDateTime getHeldAt() {
            return heldAt;
        }

        public String getNote() {
            return note;
        }
    }

    /**
     * Data class for recalled sale
     */
    public static class HeldSaleData {
        public String holdId;
        public BigDecimal subtotal;
        public BigDecimal discount;
        public BigDecimal saleDiscount;
        public String saleDiscountReason;
        public BigDecimal tax;
        public BigDecimal total;
        public String paymentMethod;
        public List<SaleItem> items;
    }
}
