package com.pos.util;

import com.pos.model.Expense;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class ExportServiceTest {

    private File tempFile;

    @Before
    public void setUp() throws IOException {
        tempFile = File.createTempFile("test_expenses", ".csv");
    }

    @After
    public void tearDown() {
        if (tempFile != null && tempFile.exists()) {
            tempFile.delete();
        }
    }

    @Test
    public void testExportExpensesToCSV() throws IOException {
        // Arrange
        List<Expense> expenses = new ArrayList<>();

        Expense e1 = new Expense();
        e1.setExpenseId("EXP001");
        e1.setCategoryName("Office Supplies");
        e1.setDescription("Paper and pens");
        e1.setAmount(new BigDecimal("125.50"));
        e1.setPaymentMethod("CARD");
        e1.setCreatedBy("user1");
        e1.setCreatedByName("John Doe");
        e1.setVendorName("Staples");
        e1.setReceiptNumber("REC-123");
        e1.setTimestamp("2023-10-27T10:00:00");

        Expense e2 = new Expense();
        e2.setExpenseId("EXP002");
        e2.setCategoryName("Utilities");
        e2.setDescription("Electric Bill");
        e2.setAmount(new BigDecimal("300.00"));
        e2.setPaymentMethod("BANK_TRANSFER");
        e2.setCreatedBy("user1");
        e2.setCreatedByName("John Doe");
        e2.setTimestamp("2023-10-28T14:30:00");
        // Optional fields left null or empty

        expenses.add(e1);
        expenses.add(e2);

        // Act
        boolean result = ExportService.exportExpensesToCSV(expenses, tempFile.getAbsolutePath());

        // Assert
        Assert.assertTrue("Export should return true", result);

        List<String> lines = Files.readAllLines(tempFile.toPath());
        Assert.assertFalse("File should not be empty", lines.isEmpty());

        // Expected Header: Date, Category, Description, Amount, Payment Method, Created By, Vendor, Receipt #, Expense ID
        String header = lines.get(0);
        Assert.assertTrue(header.contains("Date"));
        Assert.assertTrue(header.contains("Category"));
        Assert.assertTrue(header.contains("Amount"));

        // Check first data row
        String row1 = lines.get(1);
        Assert.assertTrue(row1.contains("Office Supplies"));
        Assert.assertTrue(row1.contains("125.50"));
        Assert.assertTrue(row1.contains("Staples"));
        Assert.assertTrue(row1.contains("REC-123"));

        // Check second data row
        String row2 = lines.get(2);
        Assert.assertTrue(row2.contains("Utilities"));
        Assert.assertTrue(row2.contains("300.00"));
    }
}
