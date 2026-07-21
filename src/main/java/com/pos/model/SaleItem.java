package com.pos.model;

import javafx.beans.property.*;
import java.math.BigDecimal;
import java.text.NumberFormat;

/**
 * Sale item model (product in cart)
 */
public class SaleItem {
    private final Product product;
    private final IntegerProperty quantity;
    private final ObjectProperty<BigDecimal> total;
    private String paymentMethod; // CASH or CARD

    // Discount fields
    private final ObjectProperty<BigDecimal> discountAmount;
    private final ObjectProperty<BigDecimal> discountPercent;
    private final StringProperty discountReason;

    // Manual price override (e.g. for open price items or manual price changes)
    // DEPRECATED: Use manualCashPrice instead
    private final ObjectProperty<BigDecimal> manualPrice;

    // Manual cash price override - when set, cash price uses this and list price is
    // calculated from it
    private final ObjectProperty<BigDecimal> manualCashPrice;

    // Calculated list price from manual cash price (stored to avoid recalculation)
    private final ObjectProperty<BigDecimal> calculatedListPrice;

    public SaleItem(Product product, int quantity) {
        this(product, quantity, "CASH"); // Default to cash price
    }

    public SaleItem(Product product, int quantity, String paymentMethod) {
        this.product = product;
        this.quantity = new SimpleIntegerProperty(quantity);
        this.paymentMethod = paymentMethod;
        this.discountAmount = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.discountPercent = new SimpleObjectProperty<>(BigDecimal.ZERO);
        this.discountReason = new SimpleStringProperty("");
        this.manualPrice = new SimpleObjectProperty<>(null);
        this.manualCashPrice = new SimpleObjectProperty<>(null);
        this.calculatedListPrice = new SimpleObjectProperty<>(null);

        // Apply global card surcharge if enabled
        applyGlobalCardSurcharge();

        // Calculate initial total using payment method
        BigDecimal itemPrice = getPrice(paymentMethod);
        this.total = new SimpleObjectProperty<>(
                itemPrice.multiply(BigDecimal.valueOf(quantity)));

        // Update total when quantity changes
        this.quantity.addListener((obs, oldVal, newVal) -> {
            recalculateTotal();
        });

        // Update total when discount changes
        this.discountAmount.addListener((obs, oldVal, newVal) -> {
            recalculateTotal();
        });

        this.discountPercent.addListener((obs, oldVal, newVal) -> {
            recalculateTotal();
        });

        // Update total when manual price changes
        this.manualPrice.addListener((obs, oldVal, newVal) -> {
            recalculateTotal();
        });

        // Update total when manual cash price changes
        this.manualCashPrice.addListener((obs, oldVal, newVal) -> {
            // Re-apply surcharge when cash price changes
            applyGlobalCardSurcharge();
            recalculateTotal();
        });

        // Update total when calculated list price changes
        this.calculatedListPrice.addListener((obs, oldVal, newVal) -> {
            recalculateTotal();
        });
    }

    private void applyGlobalCardSurcharge() {
        try {
            com.pos.service.SettingsService.CardSurchargeSettings settings = com.pos.service.SettingsService
                    .getInstance().getCardSurchargeSettings();

            if (settings != null && settings.enabled && settings.percent != null) {
                applyCardSurcharge(settings.percent);
            }
        } catch (Exception e) {
            // Fallback gracefully if settings cannot be loaded
        }
    }

    /**
     * Recalculate total after discount
     */
    private void recalculateTotal() {
        BigDecimal itemPrice = getPrice(this.paymentMethod);
        BigDecimal baseTotal = itemPrice.multiply(BigDecimal.valueOf(quantity.get()));

        // Apply percentage discount first
        BigDecimal percentDiscount = BigDecimal.ZERO;
        if (discountPercent.get() != null && discountPercent.get().compareTo(BigDecimal.ZERO) > 0) {
            percentDiscount = baseTotal.multiply(discountPercent.get())
                    .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        }

        // Apply fixed amount discount
        BigDecimal fixedDiscount = discountAmount.get() != null ? discountAmount.get() : BigDecimal.ZERO;

        // Total discount is the sum of both
        BigDecimal totalDiscount = percentDiscount.add(fixedDiscount);

        // Ensure discount doesn't exceed item total
        if (totalDiscount.compareTo(baseTotal) > 0) {
            totalDiscount = baseTotal;
        }

        this.total.set(baseTotal.subtract(totalDiscount));
    }

    public Product getProduct() {
        return product;
    }

    public int getQuantity() {
        return quantity.get();
    }

    /**
     * Get price (defaults to cash price)
     */
    public BigDecimal getPrice() {
        return getPrice(paymentMethod != null ? paymentMethod : "CASH");
    }

    /**
     * Get price for a specific payment method
     */
    public BigDecimal getPrice(String paymentMethod) {
        // If manual prices are set, prioritize them
        if ("CARD".equals(paymentMethod) && calculatedListPrice.get() != null) {
            return calculatedListPrice.get();
        }
        
        // Return manual cash price for CASH or fallback
        if (manualCashPrice.get() != null) {
            return manualCashPrice.get();
        }

        // Fallback to old manualPrice for backward compatibility
        if (manualPrice.get() != null) {
            return manualPrice.get();
        }

        return product.getPrice(paymentMethod);
    }

    /**
     * Apply card surcharge percent to calculate list price dynamically
     */
    public void applyCardSurcharge(double percent) {
        if (percent > 0) {
            BigDecimal cashPrice = manualCashPrice.get() != null ? manualCashPrice.get() : product.getPrice("CASH");
            // listPrice = cashPrice + (cashPrice * percent/100)
            BigDecimal surcharge = cashPrice.multiply(BigDecimal.valueOf(percent))
                    .divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
            BigDecimal listPrice = cashPrice.add(surcharge);

            this.calculatedListPrice.set(listPrice);
        }
    }

    public BigDecimal getTotal() {
        return total.get();
    }

    public String getProductName() {
        return product.getName();
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    /**
     * Get base total before discount
     */
    public BigDecimal getBaseTotal() {
        BigDecimal itemPrice = getPrice(this.paymentMethod);
        return itemPrice.multiply(BigDecimal.valueOf(quantity.get()));
    }

    /**
     * Get base total at card/list price (for consistent calculation)
     */
    public BigDecimal getBaseTotalAtCardPrice() {
        BigDecimal cardPrice = getPrice("CARD");
        return cardPrice.multiply(BigDecimal.valueOf(quantity.get()));
    }

    /**
     * Get base total at cash price
     */
    public BigDecimal getBaseTotalAtCashPrice() {
        BigDecimal cashPrice = getPrice("CASH");
        return cashPrice.multiply(BigDecimal.valueOf(quantity.get()));
    }

    /**
     * Get total discount amount
     */
    public BigDecimal getTotalDiscount() {
        BigDecimal baseTotal = getBaseTotal();

        // Calculate percentage discount
        BigDecimal percentDiscount = BigDecimal.ZERO;
        if (discountPercent.get() != null && discountPercent.get().compareTo(BigDecimal.ZERO) > 0) {
            percentDiscount = baseTotal.multiply(discountPercent.get())
                    .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        }

        // Get fixed discount
        BigDecimal fixedDiscount = discountAmount.get() != null ? discountAmount.get() : BigDecimal.ZERO;

        BigDecimal totalDiscount = percentDiscount.add(fixedDiscount);

        // Ensure discount doesn't exceed item total
        if (totalDiscount.compareTo(baseTotal) > 0) {
            return baseTotal;
        }

        return totalDiscount;
    }

    /**
     * Update payment method and recalculate total
     */
    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
        recalculateTotal();
    }

    // Discount getters and setters
    public BigDecimal getDiscountAmount() {
        return discountAmount.get();
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount.set(discountAmount != null ? discountAmount : BigDecimal.ZERO);
    }

    public BigDecimal getDiscountPercent() {
        return discountPercent.get();
    }

    public void setDiscountPercent(BigDecimal discountPercent) {
        this.discountPercent.set(discountPercent != null ? discountPercent : BigDecimal.ZERO);
    }

    public String getDiscountReason() {
        return discountReason.get();
    }

    public void setDiscountReason(String discountReason) {
        this.discountReason.set(discountReason != null ? discountReason : "");
    }

    /**
     * Clear all discounts
     */
    public void clearDiscount() {
        this.discountAmount.set(BigDecimal.ZERO);
        this.discountPercent.set(BigDecimal.ZERO);
        this.discountReason.set("");
    }

    // Property getters for JavaFX binding
    public StringProperty productNameProperty() {
        return product.nameProperty();
    }

    public IntegerProperty quantityProperty() {
        return quantity;
    }

    public ObjectProperty<BigDecimal> priceProperty() {
        return new SimpleObjectProperty<>(getPrice());
    }

    public StringProperty totalProperty() {
        return new SimpleStringProperty(
                String.format("$%.2f", total.get()));
    }

    /**
     * Get formatted price string showing both cash and list prices
     * 
     * @return Formatted string like "Cash: $X.XX" or "Cash: $X.XX / Card: $Y.YY"
     */
    public StringProperty formattedPriceProperty() {
        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();

        // Use manual cash price if set, otherwise use product cash price
        BigDecimal cashPrice = manualCashPrice.get() != null ? manualCashPrice.get() : product.getPrice("CASH");

        // Use calculated list price if manual cash price is set, otherwise use product
        // list price
        BigDecimal listPrice = calculatedListPrice.get();
        if (listPrice == null) {
            applyGlobalCardSurcharge();
            listPrice = calculatedListPrice.get() != null ? calculatedListPrice.get() : cashPrice;
        }

        // Only show single price (cash price is standard display)
        String priceText = currencyFormat.format(cashPrice);

        return new SimpleStringProperty(priceText);
    }

    /**
     * Get formatted price string for current payment method
     * 
     * @return Formatted string showing the price being used
     */
    public StringProperty currentPriceProperty() {
        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance();
        BigDecimal currentPrice = getPrice();
        return new SimpleStringProperty(currencyFormat.format(currentPrice));
    }

    public ObjectProperty<BigDecimal> discountAmountProperty() {
        return discountAmount;
    }

    public ObjectProperty<BigDecimal> discountPercentProperty() {
        return discountPercent;
    }

    public StringProperty discountReasonProperty() {
        return discountReason;
    }

    public void setQuantity(int quantity) {
        this.quantity.set(quantity);
    }

    public ObjectProperty<BigDecimal> manualPriceProperty() {
        return manualPrice;
    }

    public BigDecimal getManualPrice() {
        return manualPrice.get();
    }

    public void setManualPrice(BigDecimal price) {
        this.manualPrice.set(price);
    }

    /**
     * Set manual cash price and calculate list price from it
     * 
     * @param cashPrice            The new cash price
     * @param cardSurchargePercent The card surcharge percentage (e.g., 3.5 for
     *                             3.5%)
     */
    public void setManualCashPrice(BigDecimal cashPrice, Double cardSurchargePercent) {
        this.manualCashPrice.set(cashPrice);

        // Calculate list price from cash price
        BigDecimal listPrice = cashPrice;
        if (cardSurchargePercent != null && cardSurchargePercent > 0) {
            // listPrice = cashPrice * (1 + percent/100)
            BigDecimal multiplier = BigDecimal.ONE.add(BigDecimal.valueOf(cardSurchargePercent / 100.0));
            listPrice = cashPrice.multiply(multiplier).setScale(4, java.math.RoundingMode.HALF_UP);
        }
        this.calculatedListPrice.set(listPrice);
    }

    /**
     * Set manual cash price (list price will be same as cash price)
     */
    public void setManualCashPrice(BigDecimal cashPrice) {
        setManualCashPrice(cashPrice, null);
    }

    public BigDecimal getManualCashPrice() {
        return manualCashPrice.get();
    }

    public BigDecimal getCalculatedListPrice() {
        return calculatedListPrice.get();
    }
}
