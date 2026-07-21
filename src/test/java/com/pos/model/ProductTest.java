package com.pos.model;

import org.junit.Test;
import java.math.BigDecimal;
import static org.junit.Assert.*;

public class ProductTest {

    @Test
    public void testProductSku() {
        // Test master constructor with SKU
        Product p1 = new Product("id1", "SKU-123", "BAR-456", "Test Product",
                new BigDecimal("10.00"), 100,
                "dept1", "vendor1", "Vendor Name", new BigDecimal("5.00"));

        assertEquals("Expected SKU to be SKU-123", "SKU-123", p1.getSku());
        assertEquals("Expected Barcode to be BAR-456", "BAR-456", p1.getBarcode());

        // Test backward compatibility (constructor without SKU)
        Product p2 = new Product("id2", "BAR-789", "Old Product",
                new BigDecimal("20.00"), 50, "dept2");

        assertNull("Expected SKU to be null for old constructor", p2.getSku());
        assertEquals("Expected Barcode to be BAR-789", "BAR-789", p2.getBarcode());

        // Test logic mimicking createSaleSubmission
        String barcode = p1.getBarcode();
        String actualSku = p1.getSku();
        String submissionSku = (actualSku != null && !actualSku.isEmpty()) ? actualSku : barcode;

        assertEquals("Expected submission SKU to be SKU-123", "SKU-123", submissionSku);

        // Test fallback logic
        barcode = p2.getBarcode();
        actualSku = p2.getSku();
        submissionSku = (actualSku != null && !actualSku.isEmpty()) ? actualSku : barcode;

        assertEquals("Expected submission SKU to be BAR-789 (fallback)", "BAR-789", submissionSku);
    }
}
