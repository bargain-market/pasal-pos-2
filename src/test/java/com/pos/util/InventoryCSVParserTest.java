package com.pos.util;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;

public class InventoryCSVParserTest {

    @Test
    public void testPOSSystemHeaderDetection_WithPrice() throws IOException {
        File tempFile = File.createTempFile("inventory_with_price", ".csv");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("Name,SKU,Barcode,Stock,Price,Status,Reorder Level,Department\n");
            writer.write("Test Product,SKU001,BAR001,10,9.99,IN_STOCK,5,Auto\n");
        }

        try {
            InventoryCSVParser.ParseResult result = InventoryCSVParser.parseCSV(tempFile,
                    InventoryCSVParser.ImportFormat.POS_SYSTEM);
            assertFalse("Should have no errors", result.hasErrors());
            assertEquals("Should have 1 product", 1, result.products.size());
            assertEquals("Test Product", result.products.get(0).name);
            assertEquals(new BigDecimal("9.99"), result.products.get(0).price);
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testPOSSystemHeaderDetection_WithDeptSettings() throws IOException {
        File tempFile = File.createTempFile("inventory_dept_settings", ".csv");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write(
                    "Name,SKU,Barcode,Stock,Price,Status,Reorder Level,Department,Dept Tax Enabled,Dept Tax Rate,Dept EBT Eligible,Dept Age VR\n");
            writer.write("Taxed Item,SKU002,BAR002,20,15.00,IN_STOCK,5,Tobacco,true,8.25,false,21\n");
        }

        try {
            InventoryCSVParser.ParseResult result = InventoryCSVParser.parseCSV(tempFile,
                    InventoryCSVParser.ImportFormat.POS_SYSTEM);
            assertFalse("Should have no errors", result.hasErrors());
            assertEquals("Should have 1 product", 1, result.products.size());

            InventoryCSVParser.ParsedProduct p = result.products.get(0);
            assertEquals("Taxed Item", p.name);
            assertEquals("Tobacco", p.departmentName);
            assertEquals(Boolean.TRUE, p.taxEnabled);
            assertEquals(new BigDecimal("8.25"), p.taxRate);
            assertEquals(Boolean.FALSE, p.ebtEligible);
            assertEquals(Integer.valueOf(21), p.ageVerification);
        } finally {
            tempFile.delete();
        }
    }
}
