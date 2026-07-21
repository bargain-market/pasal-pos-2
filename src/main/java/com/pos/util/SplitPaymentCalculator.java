package com.pos.util;

import com.pos.model.SaleItem;
import com.pos.model.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility class for calculating split payment allocations with proper cash/card
 * pricing
 */
public class SplitPaymentCalculator {
    private static final Logger logger = LoggerFactory.getLogger(SplitPaymentCalculator.class);

    /**
     * Result of split payment calculation
     */
    public static class SplitPaymentResult {
        public BigDecimal cashSubtotal;
        public BigDecimal cardSubtotal;
        public BigDecimal cashTax;
        public BigDecimal cardTax;
        public BigDecimal cashTotal;
        public BigDecimal cardTotal;
        public BigDecimal totalTax;
        public BigDecimal totalSubtotal;
        public BigDecimal totalDiscount;
    }

    /**
     * Calculate split payment allocation with proper cash/card pricing
     * 
     * @param items              List of sale items
     * @param payments           List of split payments
     * @param saleDiscount       Sale-level discount amount
     * @param departmentTaxRates Map of department ID to tax rate (as BigDecimal,
     *                           e.g., 8.5 for 8.5%)
     * @return SplitPaymentResult with allocated amounts
     */
    public static SplitPaymentResult calculateSplitPayment(
            List<SaleItem> items,
            List<Payment> payments,
            BigDecimal saleDiscount,
            Map<String, BigDecimal> departmentTaxRates) {

        SplitPaymentResult result = new SplitPaymentResult();

        // Calculate total cash and card payments
        BigDecimal totalCashPaid = payments.stream()
                .filter(p -> "CASH".equals(p.getPaymentMethod()))
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCardPaid = payments.stream()
                .filter(p -> "CARD".equals(p.getPaymentMethod()) || "EBT".equals(p.getPaymentMethod()))
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPaid = totalCashPaid.add(totalCardPaid);

        if (totalPaid.compareTo(BigDecimal.ZERO) == 0) {
            logger.warn("Total paid is zero, returning zero result");
            result.cashSubtotal = BigDecimal.ZERO;
            result.cardSubtotal = BigDecimal.ZERO;
            result.cashTax = BigDecimal.ZERO;
            result.cardTax = BigDecimal.ZERO;
            result.cashTotal = BigDecimal.ZERO;
            result.cardTotal = BigDecimal.ZERO;
            result.totalTax = BigDecimal.ZERO;
            result.totalSubtotal = BigDecimal.ZERO;
            result.totalDiscount = BigDecimal.ZERO;
            return result;
        }

        // Calculate cash and card fractions
        BigDecimal cashFraction = totalCashPaid.divide(totalPaid, 6, RoundingMode.HALF_UP);
        BigDecimal cardFraction = totalCardPaid.divide(totalPaid, 6, RoundingMode.HALF_UP);

        // Calculate subtotals for each item at cash and card prices
        BigDecimal totalCashSubtotal = BigDecimal.ZERO;
        BigDecimal totalCardSubtotal = BigDecimal.ZERO;
        BigDecimal totalDiscountAmount = BigDecimal.ZERO;

        // Group items by department for tax calculation
        Map<String, List<SaleItem>> itemsByDept = items.stream()
                .collect(Collectors.groupingBy(item -> {
                    String deptId = item.getProduct().getDepartmentId();
                    return deptId != null ? deptId : "NO_DEPT";
                }));

        // Calculate item-level allocations
        for (SaleItem item : items) {
            BigDecimal cashPrice = item.getPrice("CASH");
            BigDecimal cardPrice = item.getPrice("CARD");
            int quantity = item.getQuantity();

            // Calculate base totals using respective prices
            BigDecimal itemCashBase = cashPrice.multiply(BigDecimal.valueOf(quantity));
            BigDecimal itemCardBase = cardPrice.multiply(BigDecimal.valueOf(quantity));

            // Apply item-level discounts proportionally
            // We'll use the ratio of discount to the item's CARD base price
            // if we assume discounts are entered against the card price, 
            // or simply use the absolute total discount.
            BigDecimal itemDiscount = item.getTotalDiscount();
            totalDiscountAmount = totalDiscountAmount.add(itemDiscount);

            // Net amounts after item discounts
            BigDecimal itemCashNet = itemCashBase.scaleByPowerOfTen(0); // For reference
            BigDecimal itemCardNet = itemCardBase.scaleByPowerOfTen(0);

            // Subtract discount from BOTH bases to get net price for that portion
            // This assumes the discount applies equally regardless of payment method
            itemCashNet = itemCashBase.subtract(itemDiscount);
            itemCardNet = itemCardBase.subtract(itemDiscount);

            // Ensure non-negative
            if (itemCashNet.compareTo(BigDecimal.ZERO) < 0) itemCashNet = BigDecimal.ZERO;
            if (itemCardNet.compareTo(BigDecimal.ZERO) < 0) itemCardNet = BigDecimal.ZERO;

            // Allocate based on payment fractions
            // 60% cash paid means 60% of the CASH PRICE is used for that portion
            // 40% card paid means 40% of the CARD PRICE is used for that portion
            BigDecimal itemCashPortion = itemCashNet.multiply(cashFraction).setScale(2, RoundingMode.HALF_UP);
            BigDecimal itemCardPortion = itemCardNet.multiply(cardFraction).setScale(2, RoundingMode.HALF_UP);

            totalCashSubtotal = totalCashSubtotal.add(itemCashPortion);
            totalCardSubtotal = totalCardSubtotal.add(itemCardPortion);
        }

        // Apply sale-level discount proportionally
        if (saleDiscount.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal totalSubtotalBeforeSaleDiscount = totalCashSubtotal.add(totalCardSubtotal);
            if (totalSubtotalBeforeSaleDiscount.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal cashProportion = totalCashSubtotal.divide(totalSubtotalBeforeSaleDiscount, 6,
                        RoundingMode.HALF_UP);
                BigDecimal cardProportion = totalCardSubtotal.divide(totalSubtotalBeforeSaleDiscount, 6,
                        RoundingMode.HALF_UP);

                BigDecimal cashSaleDiscount = saleDiscount.multiply(cashProportion).setScale(2, RoundingMode.HALF_UP);
                BigDecimal cardSaleDiscount = saleDiscount.multiply(cardProportion).setScale(2, RoundingMode.HALF_UP);

                // Adjust to ensure total discount equals saleDiscount (handle rounding)
                BigDecimal totalAllocatedDiscount = cashSaleDiscount.add(cardSaleDiscount);
                if (totalAllocatedDiscount.compareTo(saleDiscount) != 0) {
                    BigDecimal diff = saleDiscount.subtract(totalAllocatedDiscount);
                    // Add difference to the larger portion
                    if (cashProportion.compareTo(cardProportion) >= 0) {
                        cashSaleDiscount = cashSaleDiscount.add(diff);
                    } else {
                        cardSaleDiscount = cardSaleDiscount.add(diff);
                    }
                }

                totalCashSubtotal = totalCashSubtotal.subtract(cashSaleDiscount);
                totalCardSubtotal = totalCardSubtotal.subtract(cardSaleDiscount);

                if (totalCashSubtotal.compareTo(BigDecimal.ZERO) < 0) {
                    totalCashSubtotal = BigDecimal.ZERO;
                }
                if (totalCardSubtotal.compareTo(BigDecimal.ZERO) < 0) {
                    totalCardSubtotal = BigDecimal.ZERO;
                }
            }
        }

        result.cashSubtotal = totalCashSubtotal.setScale(2, RoundingMode.HALF_UP);
        result.cardSubtotal = totalCardSubtotal.setScale(2, RoundingMode.HALF_UP);
        result.totalSubtotal = result.cashSubtotal.add(result.cardSubtotal);
        result.totalDiscount = totalDiscountAmount.add(saleDiscount);

        // Calculate tax for each department, then split between cash and card
        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal cashTax = BigDecimal.ZERO;
        BigDecimal cardTax = BigDecimal.ZERO;

        for (Map.Entry<String, List<SaleItem>> entry : itemsByDept.entrySet()) {
            String deptId = entry.getKey();
            List<SaleItem> deptItems = entry.getValue();

            if ("NO_DEPT".equals(deptId) || !departmentTaxRates.containsKey(deptId)) {
                continue;
            }

            BigDecimal taxRate = departmentTaxRates.get(deptId);
            if (taxRate == null || taxRate.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            // Calculate department subtotal at cash and card prices
            BigDecimal deptCashSubtotal = BigDecimal.ZERO;
            BigDecimal deptCardSubtotal = BigDecimal.ZERO;
            BigDecimal deptItemDiscounts = BigDecimal.ZERO;

            for (SaleItem item : deptItems) {
                BigDecimal cashPrice = item.getPrice("CASH");
                BigDecimal cardPrice = item.getPrice("CARD");
                int quantity = item.getQuantity();

                BigDecimal itemCashBase = cashPrice.multiply(BigDecimal.valueOf(quantity));
                BigDecimal itemCardBase = cardPrice.multiply(BigDecimal.valueOf(quantity));
                BigDecimal itemDiscount = item.getTotalDiscount();

                BigDecimal itemCashNet = itemCashBase.subtract(itemDiscount);
                BigDecimal itemCardNet = itemCardBase.subtract(itemDiscount);

                if (itemCashNet.compareTo(BigDecimal.ZERO) < 0) itemCashNet = BigDecimal.ZERO;
                if (itemCardNet.compareTo(BigDecimal.ZERO) < 0) itemCardNet = BigDecimal.ZERO;

                deptCashSubtotal = deptCashSubtotal
                        .add(itemCashNet.multiply(cashFraction).setScale(2, RoundingMode.HALF_UP));
                deptCardSubtotal = deptCardSubtotal
                        .add(itemCardNet.multiply(cardFraction).setScale(2, RoundingMode.HALF_UP));
                deptItemDiscounts = deptItemDiscounts.add(itemDiscount);
            }

            // Apply sale-level discount proportionally to this department
            if (saleDiscount.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal deptTotalBeforeSaleDiscount = deptCashSubtotal.add(deptCardSubtotal);
                BigDecimal totalSubtotalBeforeSaleDiscount = result.totalSubtotal.add(saleDiscount);

                if (totalSubtotalBeforeSaleDiscount.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal deptProportion = deptTotalBeforeSaleDiscount.divide(totalSubtotalBeforeSaleDiscount, 6,
                            RoundingMode.HALF_UP);
                    BigDecimal deptSaleDiscount = saleDiscount.multiply(deptProportion).setScale(2,
                            RoundingMode.HALF_UP);

                    BigDecimal deptCashProportion = deptCashSubtotal.divide(deptTotalBeforeSaleDiscount, 6,
                            RoundingMode.HALF_UP);
                    BigDecimal deptCardProportion = deptCardSubtotal.divide(deptTotalBeforeSaleDiscount, 6,
                            RoundingMode.HALF_UP);

                    BigDecimal deptCashSaleDiscount = deptSaleDiscount.multiply(deptCashProportion).setScale(2,
                            RoundingMode.HALF_UP);
                    BigDecimal deptCardSaleDiscount = deptSaleDiscount.multiply(deptCardProportion).setScale(2,
                            RoundingMode.HALF_UP);

                    deptCashSubtotal = deptCashSubtotal.subtract(deptCashSaleDiscount);
                    deptCardSubtotal = deptCardSubtotal.subtract(deptCardSaleDiscount);

                    if (deptCashSubtotal.compareTo(BigDecimal.ZERO) < 0) {
                        deptCashSubtotal = BigDecimal.ZERO;
                    }
                    if (deptCardSubtotal.compareTo(BigDecimal.ZERO) < 0) {
                        deptCardSubtotal = BigDecimal.ZERO;
                    }
                }
            }

            // Calculate tax for this department
            BigDecimal deptCashTax = deptCashSubtotal.multiply(taxRate)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal deptCardTax = deptCardSubtotal.multiply(taxRate)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            cashTax = cashTax.add(deptCashTax);
            cardTax = cardTax.add(deptCardTax);
            totalTax = totalTax.add(deptCashTax).add(deptCardTax);
        }

        result.cashTax = cashTax.setScale(2, RoundingMode.HALF_UP);
        result.cardTax = cardTax.setScale(2, RoundingMode.HALF_UP);
        result.totalTax = totalTax.setScale(2, RoundingMode.HALF_UP);

        result.cashTotal = result.cashSubtotal.add(result.cashTax).setScale(2, RoundingMode.HALF_UP);
        result.cardTotal = result.cardSubtotal.add(result.cardTax).setScale(2, RoundingMode.HALF_UP);

        return result;
    }
}
