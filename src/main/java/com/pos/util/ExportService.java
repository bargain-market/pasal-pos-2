package com.pos.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.pos.model.Expense;

/**
 * Service for exporting data to CSV format
 */
public class ExportService {
    private static final Logger logger = LoggerFactory.getLogger(ExportService.class);
    
    /**
     * Export sales data to CSV file
     */
    public static boolean exportSalesToCSV(List<Map<String, Object>> sales, String filePath) {
        try (FileWriter writer = new FileWriter(filePath)) {
            // Write CSV header
            writer.append("Sale ID,Timestamp,Cashier,Payment Method,Subtotal,Discount,Tax,Total,Synced\n");
            
            // Write data rows
            for (Map<String, Object> sale : sales) {
                writer.append(escapeCSV(sale.get("sale_id"))).append(",");
                writer.append(escapeCSV(sale.get("timestamp"))).append(",");
                writer.append(escapeCSV(sale.get("cashier_name"))).append(",");
                writer.append(escapeCSV(sale.get("payment_method"))).append(",");
                writer.append(formatDecimal(sale.get("subtotal"))).append(",");
                writer.append(formatDecimal(sale.get("discount"))).append(",");
                writer.append(formatDecimal(sale.get("tax"))).append(",");
                writer.append(formatDecimal(sale.get("total"))).append(",");
                writer.append(escapeCSV(sale.get("synced"))).append("\n");
            }
            
            logger.info("Exported {} sales to CSV: {}", sales.size(), filePath);
            return true;
        } catch (IOException e) {
            logger.error("Error exporting sales to CSV", e);
            return false;
        }
    }
    
    /**
     * Export employee sales report to CSV
     */
    public static boolean exportEmployeeSalesToCSV(
            List<com.pos.service.ReportService.EmployeeSales> employeeSales,
            String filePath) {
        try (FileWriter writer = new FileWriter(filePath)) {
            // Write CSV header
            writer.append("Employee Name,Employee ID,Transaction Count,Total Sales,Cash Sales,Card Sales\n");
            
            // Write data rows
            for (com.pos.service.ReportService.EmployeeSales emp : employeeSales) {
                writer.append(escapeCSV(emp.employeeName)).append(",");
                writer.append(escapeCSV(emp.employeeId)).append(",");
                writer.append(String.valueOf(emp.transactionCount)).append(",");
                writer.append(formatDecimal(emp.totalSales)).append(",");
                writer.append(formatDecimal(emp.cashSales)).append(",");
                writer.append(formatDecimal(emp.cardSales)).append("\n");
            }
            
            logger.info("Exported employee sales to CSV: {}", filePath);
            return true;
        } catch (IOException e) {
            logger.error("Error exporting employee sales to CSV", e);
            return false;
        }
    }
    
    /**
     * Export top products report to CSV
     */
    public static boolean exportTopProductsToCSV(
            List<com.pos.service.ReportService.TopProduct> topProducts,
            String filePath) {
        try (FileWriter writer = new FileWriter(filePath)) {
            // Write CSV header
            writer.append("Product Name,SKU,Product ID,Quantity Sold,Total Revenue\n");
            
            // Write data rows
            for (com.pos.service.ReportService.TopProduct product : topProducts) {
                writer.append(escapeCSV(product.productName)).append(",");
                writer.append(escapeCSV(product.sku)).append(",");
                writer.append(escapeCSV(product.productId)).append(",");
                writer.append(String.valueOf(product.quantitySold)).append(",");
                writer.append(formatDecimal(product.totalRevenue)).append("\n");
            }
            
            logger.info("Exported top products to CSV: {}", filePath);
            return true;
        } catch (IOException e) {
            logger.error("Error exporting top products to CSV", e);
            return false;
        }
    }
    
    /**
     * Export payment method breakdown to CSV
     */
    public static boolean exportPaymentMethodBreakdownToCSV(
            List<com.pos.service.ReportService.PaymentMethodBreakdown> breakdown,
            String filePath) {
        try (FileWriter writer = new FileWriter(filePath)) {
            // Write CSV header
            writer.append("Payment Method,Transaction Count,Total Amount,Percentage\n");
            
            // Write data rows
            for (com.pos.service.ReportService.PaymentMethodBreakdown pmb : breakdown) {
                writer.append(escapeCSV(pmb.paymentMethod)).append(",");
                writer.append(String.valueOf(pmb.transactionCount)).append(",");
                writer.append(formatDecimal(pmb.totalAmount)).append(",");
                writer.append(String.format("%.2f", pmb.percentage)).append("%\n");
            }
            
            logger.info("Exported payment method breakdown to CSV: {}", filePath);
            return true;
        } catch (IOException e) {
            logger.error("Error exporting payment method breakdown to CSV", e);
            return false;
        }
    }
    
    /**
     * Export expenses to CSV
     */
    public static boolean exportExpensesToCSV(List<Expense> expenses, String filePath) {
        try (FileWriter writer = new FileWriter(filePath)) {
            // Write CSV header
            writer.append("Date,Category,Description,Amount,Payment Method,Created By,Vendor,Receipt #,Expense ID\n");

            // Write data rows
            for (Expense expense : expenses) {
                writer.append(escapeCSV(expense.getTimestamp())).append(",");
                writer.append(escapeCSV(expense.getCategoryName())).append(",");
                writer.append(escapeCSV(expense.getDescription())).append(",");
                writer.append(formatDecimal(expense.getAmount())).append(",");
                writer.append(escapeCSV(expense.getPaymentMethod())).append(",");
                writer.append(escapeCSV(expense.getCreatedByName())).append(",");
                writer.append(escapeCSV(expense.getVendorName())).append(",");
                writer.append(escapeCSV(expense.getReceiptNumber())).append(",");
                writer.append(escapeCSV(expense.getExpenseId())).append("\n");
            }

            logger.info("Exported {} expenses to CSV: {}", expenses.size(), filePath);
            return true;
        } catch (IOException e) {
            logger.error("Error exporting expenses to CSV", e);
            return false;
        }
    }

    /**
     * Escape CSV value (handle commas, quotes, newlines)
     */
    private static String escapeCSV(Object value) {
        if (value == null) {
            return "";
        }
        
        String str = value.toString();
        
        // If value contains comma, quote, or newline, wrap in quotes and escape quotes
        if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
            return "\"" + str.replace("\"", "\"\"") + "\"";
        }
        
        return str;
    }
    
    /**
     * Format decimal value for CSV
     */
    private static String formatDecimal(Object value) {
        if (value == null) {
            return "0.00";
        }
        
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).setScale(2, java.math.RoundingMode.HALF_UP).toString();
        }
        
        return value.toString();
    }
}

