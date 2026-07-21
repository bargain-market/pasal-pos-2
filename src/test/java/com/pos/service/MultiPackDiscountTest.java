package com.pos.service;

import com.pos.model.Product;
import com.pos.model.SaleItem;
import com.pos.service.SettingsService.MultiPackDiscountSettings;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for Multi-Pack Discount feature
 * 
 * These tests validate the logic for:
 * - Eligibility checking (department and quantity)
 * - Discount calculation (percentage and fixed amount)
 * - Discount application to SaleItems
 */
public class MultiPackDiscountTest {

        private Product createProduct(String name, double cashPrice, String deptId) {
                return new Product(
                                null,
                                "BAR-" + name,
                                name,
                                BigDecimal.valueOf(cashPrice),
                                0,
                                deptId);
        }

        @Test
        public void testDepartmentEligibility() {
                MultiPackDiscountSettings settings = new MultiPackDiscountSettings();
                settings.enabled = true;
                settings.eligibleDepartmentIds = Arrays.asList("DEPT1", "DEPT2");
                settings.minimumQuantity = 2;

                // Eligible department
                assertTrue(settings.isDepartmentEligible("DEPT1"));
                assertTrue(settings.isDepartmentEligible("DEPT2"));

                // Ineligible department
                assertFalse(settings.isDepartmentEligible("DEPT3"));
                assertFalse(settings.isDepartmentEligible(null));
        }

        @Test
        public void testMinimumQuantityRequirement() {
                MultiPackDiscountSettings settings = new MultiPackDiscountSettings();
                settings.enabled = true;
                settings.eligibleDepartmentIds = Arrays.asList("DEPT1");
                settings.minimumQuantity = 3;

                Product product = createProduct("Cigarettes", 10.0, "DEPT1");

                // Quantity below minimum - not eligible
                SaleItem item1 = new SaleItem(product, 2, "CASH");
                assertFalse("Item with quantity 2 should not be eligible when min is 3",
                                item1.getQuantity() >= settings.minimumQuantity);

                // Quantity meets minimum - eligible
                SaleItem item2 = new SaleItem(product, 3, "CASH");
                assertTrue("Item with quantity 3 should be eligible when min is 3",
                                item2.getQuantity() >= settings.minimumQuantity);

                SaleItem item3 = new SaleItem(product, 5, "CASH");
                assertTrue("Item with quantity 5 should be eligible when min is 3",
                                item3.getQuantity() >= settings.minimumQuantity);
        }

        @Test
        public void testPercentageDiscountCalculation() {
                MultiPackDiscountSettings settings = new MultiPackDiscountSettings();
                settings.enabled = true;
                settings.discountType = MultiPackDiscountSettings.DiscountType.PERCENT;
                settings.discountValue = BigDecimal.valueOf(10.0); // 10%
                settings.minimumQuantity = 2;

                Product product = createProduct("Cigarettes", 10.0, "DEPT1");
                SaleItem item = new SaleItem(product, 3, "CASH"); // 3 items at $10 each = $30

                // Calculate expected discount: 10% of $30 = $3.00
                BigDecimal baseTotal = item.getBaseTotal();
                BigDecimal expectedDiscount = baseTotal.multiply(settings.discountValue)
                                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);

                assertEquals("Base total should be $30.00", 0,
                                new BigDecimal("30.00")
                                                .compareTo(baseTotal.setScale(2, java.math.RoundingMode.HALF_UP)));
                assertEquals("10% discount on $30 should be $3.00", 0,
                                new BigDecimal("3.00").compareTo(expectedDiscount));
        }

        @Test
        public void testFixedAmountDiscountCalculation() {
                MultiPackDiscountSettings settings = new MultiPackDiscountSettings();
                settings.enabled = true;
                settings.discountType = MultiPackDiscountSettings.DiscountType.AMOUNT;
                settings.discountValue = BigDecimal.valueOf(5.00); // $5 off
                settings.minimumQuantity = 2;

                Product product = createProduct("Cigarettes", 10.0, "DEPT1");
                SaleItem item = new SaleItem(product, 2, "CASH"); // 2 items at $10 each = $20

                BigDecimal baseTotal = item.getBaseTotal();
                BigDecimal expectedDiscount = settings.discountValue.min(baseTotal);

                assertEquals("Base total should be $20.00", 0,
                                new BigDecimal("20.00")
                                                .compareTo(baseTotal.setScale(2, java.math.RoundingMode.HALF_UP)));
                assertEquals("Fixed discount should be $5.00", 0,
                                new BigDecimal("5.00").compareTo(
                                                expectedDiscount.setScale(2, java.math.RoundingMode.HALF_UP)));
        }

        @Test
        public void testFixedAmountDiscountDoesNotExceedTotal() {
                MultiPackDiscountSettings settings = new MultiPackDiscountSettings();
                settings.enabled = true;
                settings.discountType = MultiPackDiscountSettings.DiscountType.AMOUNT;
                settings.discountValue = BigDecimal.valueOf(25.00); // $25 off
                settings.minimumQuantity = 2;

                Product product = createProduct("Cigarettes", 10.0, "DEPT1");
                SaleItem item = new SaleItem(product, 2, "CASH"); // 2 items at $10 each = $20

                BigDecimal baseTotal = item.getBaseTotal();
                BigDecimal expectedDiscount = settings.discountValue.min(baseTotal);

                assertEquals("Base total should be $20.00", 0,
                                new BigDecimal("20.00")
                                                .compareTo(baseTotal.setScale(2, java.math.RoundingMode.HALF_UP)));
                assertEquals("Discount should be capped at $20.00", 0,
                                new BigDecimal("20.00").compareTo(
                                                expectedDiscount.setScale(2, java.math.RoundingMode.HALF_UP)));
        }

        @Test
        public void testDiscountApplicationToSaleItem() {
                Product product = createProduct("Cigarettes", 10.0, "DEPT1");
                SaleItem item = new SaleItem(product, 3, "CASH"); // 3 items at $10 each = $30

                BigDecimal originalTotal = item.getTotal();
                assertEquals("Original total should be $30.00", 0,
                                new BigDecimal("30.00")
                                                .compareTo(originalTotal.setScale(2, java.math.RoundingMode.HALF_UP)));

                // Apply 10% discount
                item.setDiscountPercent(BigDecimal.valueOf(10.0));
                item.setDiscountReason("Multi-Pack Discount (10%)");

                BigDecimal discountedTotal = item.getTotal();
                BigDecimal expectedTotal = new BigDecimal("27.00"); // $30 - $3 = $27

                assertEquals("Discounted total should be $27.00", expectedTotal, discountedTotal);
                assertEquals("Discount reason should be set", "Multi-Pack Discount (10%)", item.getDiscountReason());
                assertEquals("Discount percent should be 10%", new BigDecimal("10.0"), item.getDiscountPercent());
        }

        @Test
        public void testDiscountApplicationWithFixedAmount() {
                Product product = createProduct("Cigarettes", 10.0, "DEPT1");
                SaleItem item = new SaleItem(product, 2, "CASH"); // 2 items at $10 each = $20

                BigDecimal originalTotal = item.getTotal();
                assertEquals("Original total should be $20.00", 0,
                                new BigDecimal("20.00")
                                                .compareTo(originalTotal.setScale(2, java.math.RoundingMode.HALF_UP)));

                // Apply $5 fixed discount
                item.setDiscountAmount(BigDecimal.valueOf(5.00));
                item.setDiscountReason("Multi-Pack Discount ($5.00)");

                BigDecimal discountedTotal = item.getTotal();
                BigDecimal expectedTotal = new BigDecimal("15.00"); // $20 - $5 = $15

                assertEquals("Discounted total should be $15.00", 0,
                                expectedTotal.compareTo(discountedTotal.setScale(2, java.math.RoundingMode.HALF_UP)));
                assertEquals("Discount reason should be set", "Multi-Pack Discount ($5.00)", item.getDiscountReason());
                assertEquals("Discount amount should be $5.00", 0,
                                new BigDecimal("5.00").compareTo(
                                                item.getDiscountAmount().setScale(2, java.math.RoundingMode.HALF_UP)));
        }

        @Test
        public void testItemWithExistingMultiPackDiscountIsNotEligible() {
                Product product = createProduct("Cigarettes", 10.0, "DEPT1");
                SaleItem item = new SaleItem(product, 3, "CASH");

                // Apply a multi-pack discount
                item.setDiscountPercent(BigDecimal.valueOf(10.0));
                item.setDiscountReason("Multi-Pack Discount (10%)");

                // Item should not be eligible again (check by discount reason)
                String reason = item.getDiscountReason();
                assertTrue("Item should have multi-pack discount", reason != null && reason.contains("Multi-Pack"));
        }

        @Test
        public void testDiscountDisplayString() {
                // Test percentage display
                MultiPackDiscountSettings percentSettings = new MultiPackDiscountSettings();
                percentSettings.discountType = MultiPackDiscountSettings.DiscountType.PERCENT;
                percentSettings.discountValue = BigDecimal.valueOf(15.0);
                assertEquals("15%", percentSettings.getDiscountDisplayString());

                // Test amount display
                MultiPackDiscountSettings amountSettings = new MultiPackDiscountSettings();
                amountSettings.discountType = MultiPackDiscountSettings.DiscountType.AMOUNT;
                amountSettings.discountValue = BigDecimal.valueOf(5.50);
                assertEquals("$5.50", amountSettings.getDiscountDisplayString());
        }

        @Test
        public void testMultipleEligibleItems() {
                MultiPackDiscountSettings settings = new MultiPackDiscountSettings();
                settings.enabled = true;
                settings.eligibleDepartmentIds = Arrays.asList("DEPT1", "DEPT2");
                settings.minimumQuantity = 2;

                List<SaleItem> cartItems = new ArrayList<>();

                // Eligible items
                Product p1 = createProduct("Cigarettes", 10.0, "DEPT1");
                SaleItem item1 = new SaleItem(p1, 3, "CASH"); // Eligible: DEPT1, qty 3
                cartItems.add(item1);

                Product p2 = createProduct("Beer", 8.0, "DEPT2");
                SaleItem item2 = new SaleItem(p2, 2, "CASH"); // Eligible: DEPT2, qty 2
                cartItems.add(item2);

                // Ineligible items
                Product p3 = createProduct("Candy", 2.0, "DEPT3");
                SaleItem item3 = new SaleItem(p3, 5, "CASH"); // Not eligible: wrong department
                cartItems.add(item3);

                Product p4 = createProduct("Snacks", 3.0, "DEPT1");
                SaleItem item4 = new SaleItem(p4, 1, "CASH"); // Not eligible: quantity too low
                cartItems.add(item4);

                // Count eligible items
                int eligibleCount = 0;
                for (SaleItem item : cartItems) {
                        String deptId = item.getProduct().getDepartmentId();
                        if (settings.isDepartmentEligible(deptId) &&
                                        item.getQuantity() >= settings.minimumQuantity) {
                                eligibleCount++;
                        }
                }

                assertEquals("Should have 2 eligible items", 2, eligibleCount);
        }
}
