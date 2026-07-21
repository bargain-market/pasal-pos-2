package com.pos.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response DTO for store settings
 * Matches backend response structure: { store: {...}, settings: {...} }
 */
public class StoreSettingsResponse {
    public StoreInfo store;
    public SettingsInfo settings;
    
    public static class StoreInfo {
        public String id;
        public String name;
        public String address;
        public String phone;
    }
    
    public static class SettingsInfo {
        public boolean taxEnabled;
        public double taxRate;
        public String currency;
        public String timezone;
        public String receiptHeader;
        public String receiptFooter;
        public int lowStockThreshold;
        /** Minimum sale total in store currency; null or omitted = no minimum */
        public Double minimumSaleAmount;
        public boolean cardSurchargeEnabled;
        public Double cardSurchargePercent;
        public RequireManagerApproval requireManagerApproval;
        public CashCheckLimits cashCheckLimits;

        public static class RequireManagerApproval {
            public boolean voids;
            public double discountsOver;
            public boolean refunds;
        }

        public static class CashCheckLimits {
            public List<RoleCashCheckLimit> roles;
            public String manualCashCheckPinHash;

            public static class RoleCashCheckLimit {
                public String roleId;
                public String roleName;
                public boolean limitEnabled;
                public int dailyLimit;
            }
        }
    }
    
    /**
     * Legacy nested structure for backward compatibility
     * Maps to the new flat structure
     */
    public StoreSettingsData getSettings() {
        StoreSettingsData data = new StoreSettingsData();
        if (store != null) {
            data.storeId = store.id;
            data.storeName = store.name;
            data.storeAddress = store.address;
            data.storePhone = store.phone;
        }
        if (settings != null) {
            data.currency = settings.currency;
            data.timezone = settings.timezone;
            data.dateFormat = "MM/dd/yyyy"; // Default
            data.timeFormat = "HH:mm"; // Default
            
            // Map tax settings
            if (settings.taxRate > 0) {
                data.taxSettings = new TaxSettings();
                data.taxSettings.defaultTaxRate = BigDecimal.valueOf(settings.taxRate / 100.0); // Convert percentage to decimal
                data.taxSettings.taxInclusive = false; // Default
            }
            
            // Map receipt settings
            if (settings.receiptHeader != null || settings.receiptFooter != null) {
                data.receiptSettings = new ReceiptSettings();
                data.receiptSettings.headerText = settings.receiptHeader;
                data.receiptSettings.footerText = settings.receiptFooter;
            }
            if (settings.minimumSaleAmount != null && settings.minimumSaleAmount > 0) {
                data.minimumSaleAmount = BigDecimal.valueOf(settings.minimumSaleAmount).setScale(2,
                        java.math.RoundingMode.HALF_UP);
            }
        }
        return data;
    }
    
    /**
     * Legacy nested structure for backward compatibility
     */
    public static class StoreSettingsData {
        public String storeAddress;
        public String storePhone;
        public String storeId;
        public String storeName;
        public String currency;
        public String timezone;
        public String dateFormat;
        public String timeFormat;
        public TaxSettings taxSettings;
        public ReceiptSettings receiptSettings;
        public List<PaymentMethod> paymentMethods;
        /** When set and positive, sale totals below this are rejected */
        public BigDecimal minimumSaleAmount;
    }
    
    public static class TaxSettings {
        public BigDecimal defaultTaxRate;
        public Boolean taxInclusive;
        public List<TaxRule> taxRules;
    }
    
    public static class TaxRule {
        public String name;
        public BigDecimal rate;
        public Boolean appliesToAll;
        public List<String> departmentIds;
    }
    
    public static class ReceiptSettings {
        public String headerText;
        public String footerText;
        public Boolean printLogo;
        public Boolean printBarcode;
        public Boolean printCustomerInfo;
    }
    
    public static class PaymentMethod {
        public String id;
        public String name;
        public String type; // CASH, CARD, EBT, OTHER
        public Boolean enabled;
    }
}

