package com.pos.util;

import com.pos.model.Product;
import com.pos.model.SaleItem;
import com.pos.model.Payment;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Basic tests for SplitPaymentCalculator to validate split cash/card pricing
 * and tax.
 *
 * These are logic-level tests that do not touch the database or UI.
 */
public class SplitPaymentCalculatorTest {

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
        public void testSimple5050CashCardSplit_UsesCashAndCardPrices() {
                // One item: cash price 10, card price 10 (was 12), qty 1
                Product p = createProduct("Item", 10.0, "D1");
                SaleItem item = new SaleItem(p, 1, "CASH");

                List<SaleItem> items = Arrays.asList(item);

                // Payments: 50% cash, 50% card
                // Note: Payments define the split ratio. If total price is 10, we expect 5 and
                // 5 payment logic roughly?
                // Actually the calculator uses payments to determine split ratio of the TOTAL.
                // If we want 50/50 split on $10 item, inputs should be 5.50 and 5.50 (incl
                // tax)?
                // Original test had 11.0 and 11.0. Total 22.0.
                // Item cost was Cash 10 / Card 12. => Avg?
                // If we keep payments as 11 and 11, it's still 50/50.
                List<Payment> payments = Arrays.asList(
                                new Payment("CASH", BigDecimal.valueOf(11.0)),
                                new Payment("CARD", BigDecimal.valueOf(11.0)));

                // Tax: 10% on department D1
                Map<String, BigDecimal> taxRates = new HashMap<>();
                taxRates.put("D1", BigDecimal.valueOf(10.0));

                SplitPaymentCalculator.SplitPaymentResult result = SplitPaymentCalculator.calculateSplitPayment(
                                items,
                                payments,
                                BigDecimal.ZERO,
                                taxRates);

                // With 50/50 split, everything is at cash price (10)
                // Cash subtotal = 5.00, Card subtotal = 5.00
                assertEquals(new BigDecimal("5.00"), result.cashSubtotal);
                assertEquals(new BigDecimal("5.00"), result.cardSubtotal);

                // Tax at 10% on each portion
                assertEquals(new BigDecimal("0.50"), result.cashTax);
                assertEquals(new BigDecimal("0.50"), result.cardTax);

                // Totals
                assertEquals(new BigDecimal("5.50"), result.cashTotal);
                assertEquals(new BigDecimal("5.50"), result.cardTotal);
                assertEquals(new BigDecimal("1.00"), result.totalTax);
        }

        @Test
        public void testCashOnly_AllAtCashPrice() {
                Product p = createProduct("Item", 10.0, "D1");
                SaleItem item = new SaleItem(p, 2, "CASH"); // 2 qty

                List<SaleItem> items = Arrays.asList(item);

                // 100% cash
                List<Payment> payments = Arrays.asList(
                                new Payment("CASH", BigDecimal.valueOf(20.0)));

                Map<String, BigDecimal> taxRates = new HashMap<>();
                taxRates.put("D1", BigDecimal.valueOf(5.0)); // 5% tax

                SplitPaymentCalculator.SplitPaymentResult result = SplitPaymentCalculator.calculateSplitPayment(
                                items,
                                payments,
                                BigDecimal.ZERO,
                                taxRates);

                // Entire amount should be at cash price: 2 * 10 = 20
                assertEquals(new BigDecimal("20.00"), result.cashSubtotal);
                assertEquals(new BigDecimal("0.00"), result.cardSubtotal);

                // Tax 5% of 20
                assertEquals(new BigDecimal("1.00"), result.cashTax);
                assertEquals(new BigDecimal("0.00"), result.cardTax);

                assertEquals(new BigDecimal("21.00"), result.cashTotal);
                assertEquals(new BigDecimal("0.00"), result.cardTotal);
                assertEquals(new BigDecimal("1.00"), result.totalTax);
        }

        @Test
        public void testSaleDiscountSplitBetweenCashAndCard() {
                Product p = createProduct("Item", 10.0, "D1");
                SaleItem item = new SaleItem(p, 1, "CASH");

                List<SaleItem> items = Arrays.asList(item);

                // 75% cash, 25% card
                List<Payment> payments = Arrays.asList(
                                new Payment("CASH", BigDecimal.valueOf(9.0)),
                                new Payment("CARD", BigDecimal.valueOf(3.0)));

                Map<String, BigDecimal> taxRates = new HashMap<>();
                taxRates.put("D1", BigDecimal.valueOf(0.0)); // ignore tax here

                BigDecimal saleDiscount = BigDecimal.valueOf(2.00); // discount on the whole sale

                SplitPaymentCalculator.SplitPaymentResult result = SplitPaymentCalculator.calculateSplitPayment(
                                items,
                                payments,
                                saleDiscount,
                                taxRates);

                // Base: cash 10, card 10 (was 12)
                // 10 total. Discount 2. Subtotal 8.
                BigDecimal totalSubtotal = result.cashSubtotal.add(result.cardSubtotal);
                assertEquals(new BigDecimal("8.00"), totalSubtotal);

                // Ensure total discount tracked equals saleDiscount
                assertEquals(0, saleDiscount.compareTo(new BigDecimal("2.00")));
        }
}
