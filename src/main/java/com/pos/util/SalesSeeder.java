package com.pos.util;

import com.pos.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Utility class to seed dummy sales data into the local database.
 * Usage: check implementation plan for execution command.
 */
public class SalesSeeder {
    private static final Logger logger = LoggerFactory.getLogger(SalesSeeder.class);
    private final DatabaseManager dbManager;
    private final Random random = new Random();

    public SalesSeeder() {
        this.dbManager = DatabaseManager.getInstance();
    }

    public static void main(String[] args) {
        try {
            SalesSeeder seeder = new SalesSeeder();
            seeder.seedSales();
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void seedSales() {
        logger.info("Starting sales seeding process...");

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);

            // 1. Ensure a seed product exists (fallback)
            ensureSeedProduct(conn);

            // 2. Fetch all products
            List<ProductInfo> products = getAllProducts(conn);
            if (products.isEmpty()) {
                logger.error("No products found for seeding!");
                return;
            }
            logger.info("Found {} products for randomization", products.size());

            // 3. Insert sales
            int salesToCreate = 105;
            logger.info("Generating {} random sales...", salesToCreate);

            for (int i = 0; i < salesToCreate; i++) {
                // Select random product
                ProductInfo product = products.get(random.nextInt(products.size()));

                // Random variation
                boolean isCard = random.nextDouble() > 0.4; // 60% card
                String paymentMethod = isCard ? "CARD" : "CASH";

                // Random quantity (1 to 5)
                int quantity = random.nextInt(5) + 1;

                // Calculate item totals
                double price = product.price.doubleValue();
                double subtotal = price * quantity;

                // Random discount (20% chance)
                double discount = 0.0;
                if (random.nextDouble() > 0.8) {
                    discount = Math.round((subtotal * 0.1) * 100.0) / 100.0; // 10% discount
                }

                // Calculate tax (assume 8%)
                // Tax matches (subtotal - discount) * rate ideally, but logic varies by region
                double taxableAmount = subtotal - discount;
                double tax = Math.round((taxableAmount * 0.08) * 100.0) / 100.0;

                double total = Math.round((subtotal - discount + tax) * 100.0) / 100.0;

                createSale(conn, product, quantity, paymentMethod, subtotal, discount, tax, total, false);

                if (i % 10 == 0) {
                    logger.info("Generated {}/{} sales", i, salesToCreate);
                }
            }

            conn.commit();
            logger.info("Seeding complete! Added {} sales.", salesToCreate);

        } catch (SQLException e) {
            logger.error("Seeding failed", e);
        }
    }

    private List<ProductInfo> getAllProducts(Connection conn) throws SQLException {
        List<ProductInfo> products = new ArrayList<>();
        String sql = "SELECT id, name, sku, price, department_id FROM products WHERE status = 'ACTIVE' AND synced = TRUE";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                products.add(new ProductInfo(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("sku"),
                        rs.getBigDecimal("price"),
                        rs.getString("department_id")));
            }
        }
        return products;
    }

    private String ensureSeedProduct(Connection conn) throws SQLException {
        String sku = "SEED-001";
        String checkSql = "SELECT id FROM products WHERE sku = ?";
        try (PreparedStatement stmt = conn.prepareStatement(checkSql)) {
            stmt.setString(1, sku);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("id");
            }
        }

        // Create if not exists
        String id = UUID.randomUUID().toString();
        String sql = """
                    INSERT INTO products (id, name, sku, barcode, price, list_price, stock_quantity, status, department_id, created_locally, synced)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, "Seed Test Product");
            stmt.setString(3, sku);
            stmt.setString(4, "SEED-BARCODE-001");
            stmt.setBigDecimal(5, new BigDecimal("10.00")); // Price
            stmt.setBigDecimal(6, new BigDecimal("10.00")); // List Price
            stmt.setInt(7, 100);
            stmt.setString(8, "ACTIVE");
            stmt.setString(9, "dept-001"); // Dummy Dept
            stmt.setBoolean(10, true);
            stmt.setBoolean(11, true); // Mark as synced so we don't try to sync it back
            stmt.executeUpdate();
        }

        logger.info("Created new seed product: {}", id);
        return id;
    }

    private void createSale(Connection conn, ProductInfo product, int quantity, String paymentMethod, double subtotal,
            double discount, double tax, double total, boolean isSplit) throws SQLException {
        String saleId = "SALE-SEED-" + System.currentTimeMillis() + "-" + random.nextInt(10000);
        String timestamp = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);

        // Insert Sale
        String saleSql = """
                    INSERT INTO sales (id, sale_id, subtotal, discount, tax, total, payment_method, is_split_payment, cashier_name, cashier_id, timestamp, synced, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement stmt = conn.prepareStatement(saleSql)) {
            stmt.setString(1, UUID.randomUUID().toString());
            stmt.setString(2, saleId);
            stmt.setBigDecimal(3, BigDecimal.valueOf(subtotal));
            stmt.setBigDecimal(4, BigDecimal.valueOf(discount));
            stmt.setBigDecimal(5, BigDecimal.valueOf(tax));
            stmt.setBigDecimal(6, BigDecimal.valueOf(total));
            stmt.setString(7, paymentMethod);
            stmt.setBoolean(8, isSplit);
            stmt.setString(9, "Seeder Bot");
            stmt.setString(10, "bot-001");
            stmt.setString(11, timestamp);
            stmt.setBoolean(12, false); // IMPORTANT: synced = FALSE to trigger sync
            stmt.setTimestamp(13, java.sql.Timestamp.from(Instant.now()));
            stmt.executeUpdate();
        }

        // Insert Sale Item
        String itemSql = """
                    INSERT INTO sale_items (sale_id, product_id, sku, name, price, quantity, subtotal, discount, department_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement stmt = conn.prepareStatement(itemSql)) {
            stmt.setString(1, saleId);
            stmt.setString(2, product.id);
            stmt.setString(3, product.sku);
            stmt.setString(4, product.name);
            stmt.setBigDecimal(5, product.price); // Unit Price
            stmt.setInt(6, quantity);
            stmt.setBigDecimal(7, BigDecimal.valueOf(subtotal)); // Item Subtotal matches sale subtotal for single item
            stmt.setBigDecimal(8, BigDecimal.valueOf(discount));
            stmt.setString(9, product.departmentId != null ? product.departmentId : "dept-default");
            stmt.executeUpdate();
        }

        logger.info("Inserted sale: {}", saleId);
    }

    private static class ProductInfo {
        String id;
        String name;
        String sku;
        BigDecimal price;
        String departmentId;

        public ProductInfo(String id, String name, String sku, BigDecimal price, String departmentId) {
            this.id = id;
            this.name = name;
            this.sku = sku;
            this.price = price;
            this.departmentId = departmentId;
        }
    }
}
