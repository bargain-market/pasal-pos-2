package com.pos.hardware;

import org.junit.Before;
import org.junit.Test;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class HardwareManagerTest {

    private javax.print.PrintService queue(String name) {
        javax.print.PrintService service = org.mockito.Mockito.mock(javax.print.PrintService.class);
        org.mockito.Mockito.when(service.getName()).thenReturn(name);
        return service;
    }

    private javax.print.PrintService select(javax.print.PrintService[] queues,
            javax.print.PrintService defaultQueue, String configured) throws Exception {
        java.lang.reflect.Method method = HardwareManager.class.getDeclaredMethod(
                "selectPrintService", javax.print.PrintService[].class,
                javax.print.PrintService.class, String.class);
        method.setAccessible(true);
        return (javax.print.PrintService) method.invoke(hardwareManager, queues, defaultQueue, configured);
    }

    @Test
    public void exactConfiguredQueueWinsOverOldCopyEvenWhenDefault() throws Exception {
        javax.print.PrintService replacement = queue("SX-82V");
        javax.print.PrintService oldCopy = queue("SX-82V (Copy 1)");
        assertSame(replacement, select(new javax.print.PrintService[] {oldCopy, replacement},
                replacement, "sx-82v"));
        assertSame(oldCopy, select(new javax.print.PrintService[] {oldCopy, replacement},
                replacement, "SX-82V (Copy 1)"));
    }

    @Test
    public void existingFragmentAndThermalFallbackSelectionArePreserved() throws Exception {
        javax.print.PrintService first = queue("POS-58C USB");
        javax.print.PrintService second = queue("POS-58C LAN");
        javax.print.PrintService pdf = queue("Microsoft Print to PDF");
        javax.print.PrintService[] queues = {pdf, first, second};
        assertSame(second, select(queues, first, "POS-58C"));
        assertSame(second, select(queues, first, "Missing queue"));
        assertSame(first, select(queues, pdf, "Missing queue, POS-58C USB"));
        assertNull(select(new javax.print.PrintService[] {pdf}, pdf, "Missing queue"));
    }

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
