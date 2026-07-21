package com.pos.hardware;

import org.junit.Before;
import org.junit.Test;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class HardwareManagerTest {

    private HardwareManager hardwareManager;

    @Before
    public void setUp() {
        hardwareManager = new HardwareManager();
    }

    @Test
    public void testBuildEscPosDataWithBarcodeMarker() {
        String receiptText = "Store Information\n" +
                "Receipt #: SALE-1234567890\n" +
                "Item 1          1      10.00      10.00\n" +
                "------------------------------------------\n" +
                "TOTAL:                             10.00\n" +
                "SALE-1234567890\n" +
                "THANK YOU!";

        byte[] data = hardwareManager.buildEscPosData(receiptText);
        String resultString = new String(data, StandardCharsets.UTF_8);

        // Verify that "Receipt #: SALE-1234567890" is preserved (not mistaken for
        // barcode marker)
        assertTrue("Header sale ID should be preserved", resultString.contains("Receipt #: SALE-1234567890"));

        // Verify that "THANK YOU!" after the barcode marker is preserved (fix for
        // truncation bug)
        assertTrue("Text after barcode should be preserved", resultString.contains("THANK YOU!"));

        // Note: The actual barcode commands will be in ESC/POS format (binary),
        // but our replacement string logic for failed barcode generation or generic
        // text
        // would still be detectable if it fell back to plain text.
        // In a real environment, BarcodeGenerator.generateESCPOSBarcode would be
        // called.
    }

    @Test
    public void testBuildEscPosDataWithoutBarcodeMarker() {
        String receiptText = "Plain Receipt\nNo Barcode Here";
        byte[] data = hardwareManager.buildEscPosData(receiptText);
        String resultString = new String(data, StandardCharsets.UTF_8);

        assertTrue("Plain text should be preserved", resultString.contains("Plain Receipt"));
        assertTrue("Plain text should be preserved", resultString.contains("No Barcode Here"));
    }
}
