package com.pos.service;

import com.pos.api.dto.SaleSubmission;
import com.pos.database.DatabaseManager;
import com.pos.model.Refund;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for retrieving sale history from local database
 */
public class SaleHistoryService {
    private static final Logger logger = LoggerFactory.getLogger(SaleHistoryService.class);
    private static SaleHistoryService instance;

    private final DatabaseManager dbManager;

    private SaleHistoryService() {
        this.dbManager = DatabaseManager.getInstance();
    }

    public static synchronized SaleHistoryService getInstance() {
        if (instance == null) {
            instance = new SaleHistoryService();
        }
        return instance;
    }

    /**
     * Sale record for display
     */
    public enum TransactionType {
        SALE,
        REFUND
    }

    public static class SaleRecord {
        public String id;
        public String saleId;
        public BigDecimal subtotal;
        public BigDecimal discount;
        public BigDecimal tax;
        public BigDecimal total;
        public String paymentMethod;
        public String cashierName;
        public String cashierId;
        public String posUserId;
        public String timestamp;
        public BigDecimal amountReceived; // Cash payment: amount received from customer
        public BigDecimal change; // Cash payment: change given to customer
        public boolean synced;
        public boolean voided;
        public String voidedBy;
        public String voidReason;
        public String syncError;
        public LocalDate saleDate;
        public TransactionType transactionType;
        public String originalSaleId;
        public String refundReason;
        public String refundMethod;
        public String originalPaymentMethod;
        public List<SaleItemRecord> items;

        public SaleRecord() {
            this.items = new ArrayList<>();
            this.voided = false;
            this.transactionType = TransactionType.SALE;
        }

        public boolean isRefund() {
            return transactionType == TransactionType.REFUND;
        }
    }

    /**
     * Sale item record
     */
    public static class SaleItemRecord {
        public String productId;
        public String sku;
        public String name;
        public BigDecimal price;
        public int quantity;
        public int originalQuantity;
        public BigDecimal subtotal;
    }

    /**
     * Search criteria for filtering sales
     */
    public static class SearchCriteria {
        public String saleId;
        public LocalDate startDate;
        public LocalDate endDate;
        public String cashierName;
        public String paymentMethod;
        public TransactionType transactionType;
        public Integer limit = 100; // Default limit
        public Integer offset = 0;
    }

    public static class SalesSummary {
        public BigDecimal grossSales = BigDecimal.ZERO;
        public BigDecimal netSales = BigDecimal.ZERO;
        public BigDecimal totalTax = BigDecimal.ZERO;
        public BigDecimal totalDiscount = BigDecimal.ZERO;
        public int transactionCount = 0;

        public BigDecimal getAverageSale() {
            if (transactionCount <= 0) {
                return BigDecimal.ZERO;
            }
            return netSales.divide(BigDecimal.valueOf(transactionCount), 2, java.math.RoundingMode.HALF_UP);
        }
    }

    /**
     * Get sales matching search criteria
     */
    public List<SaleRecord> searchSales(SearchCriteria criteria) throws SQLException {
        List<SaleRecord> sales = new ArrayList<>();
        StringBuilder sql = new StringBuilder(baseTransactionQuery("""
                SELECT t.id, t.sale_id, t.subtotal, t.discount, t.tax, t.total,
                       t.payment_method, t.cashier_name, t.cashier_id, t.pos_user_id,
                       t.timestamp, t.synced, t.created_at, t.amount_received, t.change,
                       t.voided, t.voided_by, t.void_reason, t.sync_error,
                       t.transaction_type, t.original_sale_id, t.refund_reason,
                       t.refund_method, t.original_payment_method
                """));

        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, criteria);

        sql.append(" ORDER BY t.created_at DESC, t.sale_id DESC");

        if (criteria.limit != null && criteria.limit > 0) {
            sql.append(" LIMIT ?");
            params.add(criteria.limit);
        }
        if (criteria.offset != null && criteria.offset > 0) {
            sql.append(" OFFSET ?");
            params.add(criteria.offset);
        }

        try (Connection conn = dbManager.getConnection()) {
            if (conn == null) {
                throw new SQLException("Database connection is null");
            }

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    stmt.setObject(i + 1, params.get(i));
                }

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        SaleRecord sale = new SaleRecord();
                        sale.id = rs.getString("id");
                        sale.saleId = rs.getString("sale_id");
                        sale.subtotal = rs.getBigDecimal("subtotal");
                        sale.discount = rs.getBigDecimal("discount");
                        sale.tax = rs.getBigDecimal("tax");
                        sale.total = rs.getBigDecimal("total");
                        sale.paymentMethod = rs.getString("payment_method");
                        sale.cashierName = rs.getString("cashier_name");
                        sale.cashierId = rs.getString("cashier_id");
                        sale.posUserId = rs.getString("pos_user_id");
                        sale.timestamp = rs.getString("timestamp");
                        sale.synced = rs.getBoolean("synced");
                        sale.amountReceived = rs.getBigDecimal("amount_received");
                        sale.change = rs.getBigDecimal("change");
                        sale.voided = rs.getBoolean("voided");
                        sale.voidedBy = rs.getString("voided_by");
                        sale.voidReason = rs.getString("void_reason");
                        sale.syncError = rs.getString("sync_error");
                        sale.transactionType = TransactionType.valueOf(rs.getString("transaction_type"));
                        sale.originalSaleId = rs.getString("original_sale_id");
                        sale.refundReason = rs.getString("refund_reason");
                        sale.refundMethod = rs.getString("refund_method");
                        sale.originalPaymentMethod = rs.getString("original_payment_method");

                        try {
                            java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
                            if (createdAt != null) {
                                sale.saleDate = createdAt.toLocalDateTime().toLocalDate();
                            } else if (sale.timestamp != null) {
                                Instant instant = Instant.parse(sale.timestamp);
                                sale.saleDate = instant.atOffset(ZoneOffset.UTC).toLocalDate();
                            }
                        } catch (Exception e) {
                            logger.debug("Could not parse sale date", e);
                            sale.saleDate = LocalDate.now();
                        }

                        sales.add(sale);
                    }
                }
            }

            if (!sales.isEmpty()) {
                List<String> saleIds = sales.stream()
                        .filter(sale -> !sale.isRefund())
                        .map(sale -> sale.saleId)
                        .collect(Collectors.toList());
                List<String> refundIds = sales.stream()
                        .filter(SaleRecord::isRefund)
                        .map(sale -> sale.saleId)
                        .collect(Collectors.toList());

                Map<String, List<SaleItemRecord>> saleItemsMap = getSaleItemsForSales(conn, saleIds);
                Map<String, List<SaleItemRecord>> refundItemsMap = getRefundItemsForRefunds(conn, refundIds);

                for (SaleRecord sale : sales) {
                    Map<String, List<SaleItemRecord>> itemsMap = sale.isRefund() ? refundItemsMap : saleItemsMap;
                    sale.items = itemsMap.getOrDefault(sale.saleId, new ArrayList<>());
                }
            }
        }

        logger.info("Found {} transactions matching criteria", sales.size());
        return sales;
    }

    public int countSales(SearchCriteria criteria) throws SQLException {
        StringBuilder sql = new StringBuilder(baseTransactionQuery("SELECT COUNT(*) AS total_count"));
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, criteria);

        try (Connection conn = dbManager.getConnection()) {
            if (conn == null) {
                throw new SQLException("Database connection is null");
            }

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                setParameters(stmt, params);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("total_count");
                    }
                }
            }
        }

        return 0;
    }

    public SalesSummary getSalesSummary(SearchCriteria criteria) throws SQLException {
        StringBuilder sql = new StringBuilder(baseTransactionQuery("""
                SELECT COALESCE(SUM(CASE
                           WHEN t.voided THEN 0
                           WHEN t.transaction_type = 'REFUND' THEN -t.total
                           ELSE t.total + t.discount
                       END), 0) AS gross_sales,
                       COALESCE(SUM(CASE
                           WHEN t.voided THEN 0
                           WHEN t.transaction_type = 'REFUND' THEN -t.total
                           ELSE t.total
                       END), 0) AS net_sales,
                       COALESCE(SUM(CASE
                           WHEN t.voided THEN 0
                           WHEN t.transaction_type = 'REFUND' THEN -t.tax
                           ELSE t.tax
                       END), 0) AS total_tax,
                       COALESCE(SUM(CASE
                           WHEN t.voided OR t.transaction_type = 'REFUND' THEN 0
                           ELSE t.discount
                       END), 0) AS total_discount,
                       COALESCE(SUM(CASE
                           WHEN t.voided OR t.transaction_type = 'REFUND' THEN 0
                           ELSE 1
                       END), 0) AS transaction_count
                """));
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, criteria);

        try (Connection conn = dbManager.getConnection()) {
            if (conn == null) {
                throw new SQLException("Database connection is null");
            }

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                setParameters(stmt, params);
                try (ResultSet rs = stmt.executeQuery()) {
                    SalesSummary summary = new SalesSummary();
                    if (rs.next()) {
                        summary.grossSales = safeBigDecimal(rs.getBigDecimal("gross_sales"));
                        summary.netSales = safeBigDecimal(rs.getBigDecimal("net_sales"));
                        summary.totalTax = safeBigDecimal(rs.getBigDecimal("total_tax"));
                        summary.totalDiscount = safeBigDecimal(rs.getBigDecimal("total_discount"));
                        summary.transactionCount = rs.getInt("transaction_count");
                    }
                    return summary;
                }
            }
        }
    }

    /**
     * Get sale by sale ID
     */
    public SaleRecord getSaleBySaleId(String saleId) throws SQLException {
        SearchCriteria criteria = new SearchCriteria();
        criteria.saleId = saleId;
        criteria.transactionType = TransactionType.SALE;
        criteria.limit = 1;

        List<SaleRecord> sales = searchSales(criteria);
        return sales.isEmpty() ? null : sales.get(0);
    }

    /**
     * Get sale items for a sale
     */
    private List<SaleItemRecord> getSaleItems(Connection conn, String saleId) throws SQLException {
        Map<String, List<SaleItemRecord>> map = getSaleItemsForSales(conn, Collections.singletonList(saleId));
        return map.getOrDefault(saleId, new ArrayList<>());
    }

    /**
     * Get sale items for multiple sales in batches to avoid N+1 problem
     */
    private Map<String, List<SaleItemRecord>> getSaleItemsForSales(Connection conn, List<String> saleIds)
            throws SQLException {
        Map<String, List<SaleItemRecord>> result = new HashMap<>();
        if (saleIds == null || saleIds.isEmpty()) {
            return result;
        }

        // Process in batches of 500 to avoid SQL IN clause limits
        int batchSize = 500;
        for (int i = 0; i < saleIds.size(); i += batchSize) {
            int end = Math.min(i + batchSize, saleIds.size());
            List<String> batch = saleIds.subList(i, end);

            StringBuilder sql = new StringBuilder(
                    "SELECT sale_id, product_id, sku, name, price, quantity, subtotal FROM sale_items WHERE sale_id IN (");
            for (int j = 0; j < batch.size(); j++) {
                if (j > 0)
                    sql.append(",");
                sql.append("?");
            }
            sql.append(")");

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int j = 0; j < batch.size(); j++) {
                    stmt.setString(j + 1, batch.get(j));
                }

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String saleId = rs.getString("sale_id");
                        SaleItemRecord item = new SaleItemRecord();
                        item.productId = rs.getString("product_id");
                        item.sku = rs.getString("sku");
                        item.name = rs.getString("name");
                        item.price = rs.getBigDecimal("price");
                        item.quantity = rs.getInt("quantity");
                        item.originalQuantity = item.quantity;
                        item.subtotal = rs.getBigDecimal("subtotal");

                        result.computeIfAbsent(saleId, k -> new ArrayList<>()).add(item);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Get refund items for multiple refunds in batches to avoid N+1 problem
     */
    private Map<String, List<SaleItemRecord>> getRefundItemsForRefunds(Connection conn, List<String> refundIds)
            throws SQLException {
        Map<String, List<SaleItemRecord>> result = new HashMap<>();
        if (refundIds == null || refundIds.isEmpty()) {
            return result;
        }

        int batchSize = 500;
        for (int i = 0; i < refundIds.size(); i += batchSize) {
            int end = Math.min(i + batchSize, refundIds.size());
            List<String> batch = refundIds.subList(i, end);

            StringBuilder sql = new StringBuilder(
                    "SELECT refund_id, product_id, sku, name, original_price, original_quantity, refund_quantity, refund_amount FROM refund_items WHERE refund_id IN (");
            for (int j = 0; j < batch.size(); j++) {
                if (j > 0) {
                    sql.append(",");
                }
                sql.append("?");
            }
            sql.append(")");

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int j = 0; j < batch.size(); j++) {
                    stmt.setString(j + 1, batch.get(j));
                }

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String refundId = rs.getString("refund_id");
                        SaleItemRecord item = new SaleItemRecord();
                        item.productId = rs.getString("product_id");
                        item.sku = rs.getString("sku");
                        item.name = rs.getString("name");
                        item.price = rs.getBigDecimal("original_price");
                        item.originalQuantity = rs.getInt("original_quantity");
                        item.quantity = rs.getInt("refund_quantity");
                        item.subtotal = rs.getBigDecimal("refund_amount");

                        result.computeIfAbsent(refundId, key -> new ArrayList<>()).add(item);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Get all unique cashier names from sales
     */
    public List<String> getCashierNames() throws SQLException {
        List<String> cashiers = new ArrayList<>();

        String sql = """
                SELECT cashier_name
                FROM (
                    SELECT DISTINCT cashier_name FROM sales WHERE cashier_name IS NOT NULL
                    UNION
                    SELECT DISTINCT cashier_name FROM refunds WHERE cashier_name IS NOT NULL
                ) cashiers
                ORDER BY cashier_name
                """;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString("cashier_name");
                if (name != null && !name.isEmpty()) {
                    cashiers.add(name);
                }
            }
        }

        return cashiers;
    }

    private String baseTransactionQuery(String selectClause) {
        return selectClause + "\n" + """
                FROM (
                    SELECT s.id,
                           s.sale_id,
                           s.subtotal,
                           s.discount,
                           s.tax,
                           s.total,
                           s.payment_method,
                           s.cashier_name,
                           s.cashier_id,
                           s.pos_user_id,
                           s.timestamp,
                           s.synced,
                           s.created_at,
                           s.amount_received,
                           s.change,
                           s.voided,
                           s.voided_by,
                           s.void_reason,
                           s.sync_error,
                           'SALE' AS transaction_type,
                           CAST(NULL AS VARCHAR(255)) AS original_sale_id,
                           CAST(NULL AS VARCHAR(500)) AS refund_reason,
                           CAST(NULL AS VARCHAR(50)) AS refund_method,
                           CAST(NULL AS VARCHAR(50)) AS original_payment_method
                    FROM sales s
                    UNION ALL
                    SELECT r.id,
                           r.refund_id AS sale_id,
                           r.refund_amount AS subtotal,
                           CAST(0 AS DECIMAL(10,2)) AS discount,
                           r.refund_tax AS tax,
                           r.total_refund AS total,
                           r.refund_method AS payment_method,
                           r.cashier_name,
                           CAST(NULL AS VARCHAR(255)) AS cashier_id,
                           r.pos_user_id,
                           r.timestamp,
                           r.synced,
                           r.created_at,
                           CAST(NULL AS DECIMAL(10,2)) AS amount_received,
                           CAST(NULL AS DECIMAL(10,2)) AS change,
                           FALSE AS voided,
                           CAST(NULL AS VARCHAR(255)) AS voided_by,
                           CAST(NULL AS VARCHAR(500)) AS void_reason,
                           CAST(NULL AS VARCHAR(4000)) AS sync_error,
                           'REFUND' AS transaction_type,
                           r.original_sale_id,
                           r.reason AS refund_reason,
                           r.refund_method,
                           r.payment_method AS original_payment_method
                    FROM refunds r
                ) t
                WHERE 1=1
                """;
    }

    private void appendFilters(StringBuilder sql, List<Object> params, SearchCriteria criteria) {
        if (criteria.saleId != null && !criteria.saleId.isEmpty()) {
            sql.append(" AND (LOWER(t.sale_id) LIKE LOWER(?) OR LOWER(COALESCE(t.original_sale_id, '')) LIKE LOWER(?))");
            params.add("%" + criteria.saleId + "%");
            params.add("%" + criteria.saleId + "%");
        }

        if (criteria.startDate != null) {
            sql.append(" AND CAST(t.created_at AS DATE) >= ?");
            params.add(java.sql.Date.valueOf(criteria.startDate));
        }

        if (criteria.endDate != null) {
            sql.append(" AND CAST(t.created_at AS DATE) <= ?");
            params.add(java.sql.Date.valueOf(criteria.endDate));
        }

        if (criteria.cashierName != null && !criteria.cashierName.isEmpty()) {
            sql.append(" AND LOWER(t.cashier_name) LIKE LOWER(?)");
            params.add("%" + criteria.cashierName + "%");
        }

        if (criteria.paymentMethod != null && !criteria.paymentMethod.isEmpty()) {
            sql.append(" AND LOWER(COALESCE(t.payment_method, '')) = LOWER(?)");
            params.add(criteria.paymentMethod);
        }

        if (criteria.transactionType != null) {
            sql.append(" AND t.transaction_type = ?");
            params.add(criteria.transactionType.name());
        }
    }

    private void setParameters(PreparedStatement stmt, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            stmt.setObject(i + 1, params.get(i));
        }
    }

    private BigDecimal safeBigDecimal(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Reconstruct SaleSubmission from SaleRecord (for receipt generation)
     */
    public SaleSubmission reconstructSaleSubmission(SaleRecord sale) {
        if (sale.isRefund()) {
            throw new IllegalArgumentException("Cannot reconstruct a sale submission from a refund transaction");
        }

        SaleSubmission submission = new SaleSubmission();
        submission.saleId = sale.saleId;
        submission.subtotal = sale.subtotal.doubleValue();
        submission.discount = sale.discount.doubleValue();
        submission.tax = sale.tax.doubleValue();
        submission.total = sale.total.doubleValue();
        submission.paymentMethod = sale.paymentMethod;
        submission.cashierName = sale.cashierName;
        submission.cashierId = sale.cashierId;
        submission.posUserId = sale.posUserId;
        submission.timestamp = sale.timestamp;
        // Include cash payment details
        if (sale.amountReceived != null) {
            submission.amountReceived = sale.amountReceived.doubleValue();
        }
        if (sale.change != null) {
            submission.change = sale.change.doubleValue();
        }

        submission.items = sale.items.stream().map(item -> {
            SaleSubmission.SaleItemInput itemInput = new SaleSubmission.SaleItemInput();
            itemInput.productId = item.productId;
            itemInput.sku = item.sku;
            itemInput.name = item.name;
            itemInput.price = item.price.doubleValue();
            itemInput.quantity = item.quantity;
            itemInput.subtotal = item.subtotal.doubleValue();
            return itemInput;
        }).toList();

        return submission;
    }

    /**
     * Reconstruct Refund from SaleRecord for refund details and receipt reprints
     */
    public Refund reconstructRefund(SaleRecord sale) {
        if (!sale.isRefund()) {
            throw new IllegalArgumentException("Cannot reconstruct a refund from a sale transaction");
        }

        Refund refund = new Refund();
        refund.setId(sale.id);
        refund.setRefundId(sale.saleId);
        refund.setOriginalSaleId(sale.originalSaleId);
        refund.setRefundAmount(sale.subtotal != null ? sale.subtotal : BigDecimal.ZERO);
        refund.setRefundTax(sale.tax != null ? sale.tax : BigDecimal.ZERO);
        refund.setTotalRefund(sale.total != null ? sale.total : BigDecimal.ZERO);
        refund.setPaymentMethod(sale.originalPaymentMethod);
        refund.setRefundMethod(sale.refundMethod != null ? sale.refundMethod : sale.paymentMethod);
        refund.setReason(sale.refundReason);
        refund.setCashierName(sale.cashierName);
        refund.setPosUserId(sale.posUserId);
        refund.setTimestamp(sale.timestamp);
        refund.setSynced(sale.synced);

        List<Refund.RefundItem> items = sale.items.stream().map(item -> {
            Refund.RefundItem refundItem = new Refund.RefundItem();
            refundItem.setProductId(item.productId);
            refundItem.setSku(item.sku);
            refundItem.setName(item.name);
            refundItem.setOriginalPrice(item.price);
            refundItem.setOriginalQuantity(item.originalQuantity > 0 ? item.originalQuantity : item.quantity);
            refundItem.setRefundQuantity(item.quantity);
            refundItem.setRefundAmount(item.subtotal != null ? item.subtotal : BigDecimal.ZERO);
            return refundItem;
        }).toList();

        refund.setItems(items);
        return refund;
    }
}
