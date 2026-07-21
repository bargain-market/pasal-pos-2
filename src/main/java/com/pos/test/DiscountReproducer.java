package com.pos.test;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class DiscountReproducer {
    public static void main(String[] args) {
        System.out.println("Starting Discount Logic Verification (Standalone)...");

        // 1. Simulate the "Buggy" Case: Tiny floating point artifact
        BigDecimal tinyDiscount = new BigDecimal("0.0001");
        System.out.println("Testing Logic with tiny discount: " + tinyDiscount);

        // Old Logic: effectively strict > 0
        if (tinyDiscount.compareTo(BigDecimal.ZERO) > 0) {
            System.out.println("[FAIL] Old Logic: Would print discount line for " + tinyDiscount);
        } else {
            System.out.println("[PASS] Old Logic: Correctly hid discount.");
        }

        // New Logic: Round to 2 decimals first
        // 0.0001 rounds to 0.00. 0.00 > 0 is FALSE.
        if (tinyDiscount.setScale(2, RoundingMode.HALF_UP).compareTo(BigDecimal.ZERO) > 0) {
            System.out.println("[FAIL] New Logic: Still prints discount line!");
        } else {
            System.out.println("[PASS] New Logic: Correctly hides discount (0.00).");
        }

        // 2. Simulate Valid Case: Smallest valid discount (1 cent)
        BigDecimal validDiscount = new BigDecimal("0.01");
        System.out.println("\nTesting Logic with valid discount: " + validDiscount);

        if (validDiscount.setScale(2, RoundingMode.HALF_UP).compareTo(BigDecimal.ZERO) > 0) {
            System.out.println("[PASS] New Logic: Correctly shows valid discount " + validDiscount);
        } else {
            System.out.println("[FAIL] New Logic: Hidden valid discount!");
        }

        // 3. Simulate Valid Case: Larger discount
        BigDecimal largeDiscount = new BigDecimal("10.50");
        if (largeDiscount.setScale(2, RoundingMode.HALF_UP).compareTo(BigDecimal.ZERO) > 0) {
            System.out.println("[PASS] New Logic: Correctly shows valid discount " + largeDiscount);
        } else {
            System.out.println("[FAIL] New Logic: Hidden valid discount!");
        }
    }
}
