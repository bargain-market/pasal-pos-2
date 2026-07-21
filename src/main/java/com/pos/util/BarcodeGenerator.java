package com.pos.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;

/**
 * Utility class for generating barcodes for receipts
 */
public class BarcodeGenerator {
    private static final Logger logger = LoggerFactory.getLogger(BarcodeGenerator.class);

    /**
     * Generate a Code128 barcode for a sale ID
     * 
     * @param saleId The sale ID to encode
     * @param width  Width of the barcode in pixels
     * @param height Height of the barcode in pixels
     * @return BufferedImage containing the barcode, or null if generation fails
     */
    public static BufferedImage generateCode128Barcode(String saleId, int width, int height) {
        if (saleId == null || saleId.isEmpty()) {
            logger.warn("Cannot generate barcode for null or empty sale ID");
            return null;
        }

        Code128Writer barcodeWriter = new Code128Writer();
        BitMatrix bitMatrix = barcodeWriter.encode(saleId, BarcodeFormat.CODE_128, width, height);
        return MatrixToImageWriter.toBufferedImage(bitMatrix);
    }

    /**
     * Generate ESC/POS barcode commands for thermal printers
     * Creates a smaller barcode suitable for receipts
     * Uses byte arrays properly to avoid encoding issues
     * 
     * @param saleId The sale ID to encode
     * @return ESC/POS command string for printing barcode, or fallback text if
     *         barcode fails
     */
    public static String generateESCPOSBarcode(String saleId) {
        if (saleId == null || saleId.isEmpty()) {
            return "";
        }

        try {
            StringBuilder escpos = new StringBuilder();

            // Add some line feeds before barcode for spacing
            escpos.append("\n\n");

            // Center alignment for barcode
            escpos.append((char) 0x1B); // ESC
            escpos.append('a'); // Align
            escpos.append((char) 1); // Center

            // ESC/POS Barcode Commands
            // GS h n - Set barcode height (n = height in dots)
            escpos.append((char) 0x1D); // GS
            escpos.append('h'); // Set barcode height
            escpos.append((char) 40); // Height = 40 dots (readable but compact)

            // GS w n - Set barcode module width (n = 2-6)
            escpos.append((char) 0x1D); // GS
            escpos.append('w'); // Set barcode width
            escpos.append((char) 2); // Width = 2 (smallest)

            // GS H n - Set HRI position (0=none, 1=above, 2=below, 3=both)
            escpos.append((char) 0x1D); // GS
            escpos.append('H'); // Print HRI (Human Readable Interpretation)
            escpos.append((char) 2); // Position: below barcode

            // GS f n - Set HRI font (0=Font A, 1=Font B)
            escpos.append((char) 0x1D); // GS
            escpos.append('f'); // Set HRI font
            escpos.append((char) 0); // Font A (more readable)

            // GS k m n d1...dn - Print barcode using CODE128 with explicit code set B
            // Use format: GS k 73 n {B data (73 = CODE128)
            // Prepend {B to force Code Set B which handles alphanumeric characters
            String barcodeData = "{B" + saleId;
            escpos.append((char) 0x1D); // GS
            escpos.append('k'); // Print barcode
            escpos.append((char) 73); // CODE128
            escpos.append((char) barcodeData.length()); // Data length (including {B)
            escpos.append(barcodeData); // Barcode data with Code Set B

            // Add line feeds after barcode
            escpos.append("\n\n");

            // Reset to left alignment
            escpos.append((char) 0x1B); // ESC
            escpos.append('a'); // Align
            escpos.append((char) 0); // Left

            logger.debug("Generated ESC/POS barcode for sale ID: {}, command length: {}",
                    saleId, escpos.length());
            return escpos.toString();

        } catch (Exception e) {
            // Fallback: just print the sale ID as text if barcode generation fails
            logger.warn("Failed to generate ESC/POS barcode, falling back to text: {}", e.getMessage());
            return "\n\n" + saleId + "\n\n";
        }
    }

    /**
     * Generate a text representation of barcode for receipt
     * 
     * @param saleId The sale ID
     * @return Formatted text for receipt
     */
    public static String generateBarcodeTextForReceipt(String saleId) {
        if (saleId == null || saleId.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(saleId).append("\n");

        return sb.toString();
    }
}
