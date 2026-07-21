package com.pos.model;

import javafx.beans.property.*;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * VendorPayoutItem model representing a single line item in a vendor payout.
 * Each item corresponds to a product sale that contributes to the vendor's payout.
 */
public class VendorPayoutItem {
    
    private final StringProperty id;
    private final StringProperty payoutId;
    private final StringProperty saleId;
    private final IntegerProperty saleItemId;
    private final StringProperty productId;
    private final StringProperty productName;
    private final StringProperty productSku;
    private final IntegerProperty quantity;
    private final ObjectProperty<BigDecimal> unitPrice;
    private final ObjectProperty<BigDecimal> unitCost;
    private final ObjectProperty<BigDecimal> saleAmount;
    private final ObjectProperty<BigDecimal> costAmount;
    private final ObjectProperty<BigDecimal> discountAmount;
    private final ObjectProperty<BigDecimal> commissionRate;
    private final ObjectProperty<BigDecimal> payoutAmount;
    private final StringProperty saleDate;
    private final StringProperty paymentMethod;

    public VendorPayoutItem() {
        this(null);
    }

    public VendorPayoutItem(String id) {
        this.id = new SimpleStringProperty(id);
        this.payoutId = new SimpleStringProperty();
        this.saleId = new SimpleStringProperty();
        this.saleItemId = new SimpleIntegerProperty(0);
        this.productId = new SimpleStringProperty();
        this.productName = new SimpleStringProperty();
        this.productSku = new SimpleStringProperty();
        this.quantity = new SimpleIntegerProperty(0);
        this.unitPrice = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.unitCost = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.saleAmount = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.costAmount = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.discountAmount = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.commissionRate = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.payoutAmount = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.saleDate = new SimpleStringProperty();
        this.paymentMethod = new SimpleStringProperty();
    }

    // ID
    public String getId() { return id.get(); }
    public void setId(String id) { this.id.set(id); }
    public StringProperty idProperty() { return id; }

    // Payout ID
    public String getPayoutId() { return payoutId.get(); }
    public void setPayoutId(String payoutId) { this.payoutId.set(payoutId); }
    public StringProperty payoutIdProperty() { return payoutId; }

    // Sale ID
    public String getSaleId() { return saleId.get(); }
    public void setSaleId(String saleId) { this.saleId.set(saleId); }
    public StringProperty saleIdProperty() { return saleId; }

    // Sale Item ID
    public int getSaleItemId() { return saleItemId.get(); }
    public void setSaleItemId(int saleItemId) { this.saleItemId.set(saleItemId); }
    public IntegerProperty saleItemIdProperty() { return saleItemId; }

    // Product ID
    public String getProductId() { return productId.get(); }
    public void setProductId(String productId) { this.productId.set(productId); }
    public StringProperty productIdProperty() { return productId; }

    // Product Name
    public String getProductName() { return productName.get(); }
    public void setProductName(String productName) { this.productName.set(productName); }
    public StringProperty productNameProperty() { return productName; }

    // Product SKU
    public String getProductSku() { return productSku.get(); }
    public void setProductSku(String productSku) { this.productSku.set(productSku); }
    public StringProperty productSkuProperty() { return productSku; }

    // Quantity
    public int getQuantity() { return quantity.get(); }
    public void setQuantity(int quantity) { this.quantity.set(quantity); }
    public IntegerProperty quantityProperty() { return quantity; }

    // Unit Price
    public BigDecimal getUnitPrice() { return unitPrice.get(); }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice.set(unitPrice); }
    public ObjectProperty<BigDecimal> unitPriceProperty() { return unitPrice; }

    // Unit Cost
    public BigDecimal getUnitCost() { return unitCost.get(); }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost.set(unitCost); }
    public ObjectProperty<BigDecimal> unitCostProperty() { return unitCost; }

    // Sale Amount
    public BigDecimal getSaleAmount() { return saleAmount.get(); }
    public void setSaleAmount(BigDecimal saleAmount) { this.saleAmount.set(saleAmount); }
    public ObjectProperty<BigDecimal> saleAmountProperty() { return saleAmount; }

    // Cost Amount
    public BigDecimal getCostAmount() { return costAmount.get(); }
    public void setCostAmount(BigDecimal costAmount) { this.costAmount.set(costAmount); }
    public ObjectProperty<BigDecimal> costAmountProperty() { return costAmount; }

    // Discount Amount
    public BigDecimal getDiscountAmount() { return discountAmount.get(); }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount.set(discountAmount); }
    public ObjectProperty<BigDecimal> discountAmountProperty() { return discountAmount; }

    // Commission Rate
    public BigDecimal getCommissionRate() { return commissionRate.get(); }
    public void setCommissionRate(BigDecimal commissionRate) { this.commissionRate.set(commissionRate); }
    public ObjectProperty<BigDecimal> commissionRateProperty() { return commissionRate; }

    // Payout Amount
    public BigDecimal getPayoutAmount() { return payoutAmount.get(); }
    public void setPayoutAmount(BigDecimal payoutAmount) { this.payoutAmount.set(payoutAmount); }
    public ObjectProperty<BigDecimal> payoutAmountProperty() { return payoutAmount; }

    // Sale Date
    public String getSaleDate() { return saleDate.get(); }
    public void setSaleDate(String saleDate) { this.saleDate.set(saleDate); }
    public StringProperty saleDateProperty() { return saleDate; }

    // Payment Method
    public String getPaymentMethod() { return paymentMethod.get(); }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod.set(paymentMethod); }
    public StringProperty paymentMethodProperty() { return paymentMethod; }

    /**
     * Calculate payout amount based on cost and commission rate.
     * Payout = Cost Amount (what vendor should receive for their goods)
     * This assumes vendor is paid based on cost price of goods sold.
     */
    public void calculatePayoutFromCost() {
        BigDecimal cost = getCostAmount();
        if (cost != null) {
            setPayoutAmount(cost);
        }
    }

    /**
     * Calculate payout amount based on sale amount and commission rate.
     * Payout = Sale Amount * (Commission Rate / 100)
     * This is for vendors who receive a percentage of sales.
     */
    public void calculatePayoutFromCommission() {
        BigDecimal sale = getSaleAmount();
        BigDecimal rate = getCommissionRate();
        if (sale != null && rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal payout = sale.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            setPayoutAmount(payout);
        }
    }

    @Override
    public String toString() {
        return "VendorPayoutItem{" +
                "productName='" + getProductName() + '\'' +
                ", quantity=" + getQuantity() +
                ", saleAmount=" + getSaleAmount() +
                ", payoutAmount=" + getPayoutAmount() +
                '}';
    }
}
