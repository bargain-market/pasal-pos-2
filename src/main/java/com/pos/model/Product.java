package com.pos.model;

import javafx.beans.property.*;
import java.math.BigDecimal;

/**
 * Product model
 */
public class Product {
    private final StringProperty id;
    private final StringProperty sku;
    private final StringProperty barcode;
    private final StringProperty name;
    private final ObjectProperty<BigDecimal> price; // Cash price

    private final IntegerProperty stock;
    private final StringProperty departmentId;

    // Vendor/Supplier fields for payout tracking
    private final StringProperty vendorId;
    private final StringProperty vendorName;
    private final ObjectProperty<BigDecimal> cost; // Cost price from vendor

    public Product(String barcode, String name, BigDecimal price, int stock) {
        this(null, barcode, name, price, stock, null);
    }

    public Product(String barcode, String name, BigDecimal price, int stock, String departmentId) {
        this(null, barcode, name, price, stock, departmentId);
    }

    public Product(String id, String barcode, String name, BigDecimal price, int stock, String departmentId) {
        this(id, null, barcode, name, price, stock, departmentId, null, null, null);
    }

    public Product(String id, String barcode, String name, BigDecimal price, int stock,
            String departmentId, String vendorId, String vendorName, BigDecimal cost) {
        this(id, null, barcode, name, price, stock, departmentId, vendorId, vendorName, cost);
    }

    public Product(String id, String sku, String barcode, String name, BigDecimal price, int stock,
            String departmentId, String vendorId, String vendorName, BigDecimal cost) {
        this.id = new SimpleStringProperty(id);
        this.sku = new SimpleStringProperty(sku);
        this.barcode = new SimpleStringProperty(barcode);
        this.name = new SimpleStringProperty(name);
        this.price = new SimpleObjectProperty<>(price);

        this.stock = new SimpleIntegerProperty(stock);
        this.departmentId = new SimpleStringProperty(departmentId);
        this.vendorId = new SimpleStringProperty(vendorId);
        this.vendorName = new SimpleStringProperty(vendorName);
        this.cost = new SimpleObjectProperty<>(cost);
    }

    // Getters
    public String getId() {
        return id != null ? id.get() : null;
    }

    public String getSku() {
        return sku.get();
    }

    public String getBarcode() {
        return barcode.get();
    }

    public String getName() {
        return name.get();
    }

    public BigDecimal getPrice() {
        return price.get();
    }

    public int getStock() {
        return stock.get();
    }

    public String getDepartmentId() {
        return departmentId.get();
    }

    /**
     * Get price based on payment method
     * 
     * @param paymentMethod "CASH" or "CARD"
     * @return Cash price (list price concept is removed from Product model)
     */
    public BigDecimal getPrice(String paymentMethod) {
        return price.get();
    }

    // Property getters for JavaFX binding
    public StringProperty idProperty() {
        return id;
    }

    public StringProperty skuProperty() {
        return sku;
    }

    public StringProperty barcodeProperty() {
        return barcode;
    }

    public StringProperty nameProperty() {
        return name;
    }

    public ObjectProperty<BigDecimal> priceProperty() {
        return price;
    }

    public IntegerProperty stockProperty() {
        return stock;
    }

    public StringProperty departmentIdProperty() {
        return departmentId;
    }

    public StringProperty vendorIdProperty() {
        return vendorId;
    }

    public StringProperty vendorNameProperty() {
        return vendorName;
    }

    public ObjectProperty<BigDecimal> costProperty() {
        return cost;
    }

    // Setters
    public void setSku(String sku) {
        this.sku.set(sku);
    }

    public void setBarcode(String barcode) {
        this.barcode.set(barcode);
    }

    public void setName(String name) {
        this.name.set(name);
    }

    public void setPrice(BigDecimal price) {
        this.price.set(price);
    }

    public void setStock(int stock) {
        this.stock.set(stock);
    }

    public void setDepartmentId(String departmentId) {
        this.departmentId.set(departmentId);
    }

    public void setVendorId(String vendorId) {
        this.vendorId.set(vendorId);
    }

    public void setVendorName(String vendorName) {
        this.vendorName.set(vendorName);
    }

    public void setCost(BigDecimal cost) {
        this.cost.set(cost);
    }

    // Vendor getters
    public String getVendorId() {
        return vendorId.get();
    }

    public String getVendorName() {
        return vendorName.get();
    }

    public BigDecimal getCost() {
        return cost.get();
    }

    /**
     * Check if product has vendor information
     */
    public boolean hasVendor() {
        return vendorId.get() != null && !vendorId.get().isEmpty();
    }

    /**
     * Calculate profit margin (sale price - cost)
     */
    public BigDecimal getProfitMargin() {
        BigDecimal costVal = cost.get();
        BigDecimal priceVal = price.get();
        if (costVal != null && priceVal != null) {
            return priceVal.subtract(costVal);
        }
        return BigDecimal.ZERO;
    }
}
