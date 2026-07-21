package com.pos.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.pos.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Manages database connections
 */
public class DatabaseManager {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private static DatabaseManager instance;
    private ConfigManager config;
    private volatile HikariDataSource dataSource; // pooled connections

    // Notified the first time corruption is detected after the database has been
    // opened successfully (i.e. on a live/background thread, not during startup).
    // Startup corruption is handled separately by the caller's recovery flow.
    private volatile java.util.function.Consumer<Throwable> corruptionListener;
    private final java.util.concurrent.atomic.AtomicBoolean runtimeCorruptionReported =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private DatabaseManager() {
        config = ConfigManager.getInstance();
        // Register shutdown hook to ensure connections are closed on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down DatabaseManager...");
            closePool();
        }));
    }

    public static DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    /**
     * Return the configured SQLite JDBC URL and make sure its parent directory
     * exists — SQLite (unlike H2) will not auto-create the containing folder.
     */
    private String buildJdbcUrl() {
        String url = config.getProperty("database.url");
        ensureDatabaseDirectoryExists(url);
        return url;
    }

    /**
     * Create the directory that holds the database file (e.g. ./data) if needed.
     * No-op for in-memory databases.
     */
    private void ensureDatabaseDirectoryExists(String url) {
        String path = extractDatabasePath(url);
        if (path == null) {
            return;
        }
        try {
            Path parent = Paths.get(path).toAbsolutePath().getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
        } catch (Exception e) {
            logger.warn("Could not create database directory for {}: {}", path, e.getMessage());
        }
    }

    /**
     * Lazily create the shared HikariCP connection pool over a SQLite data source.
     * SQLite PRAGMAs are configured on the underlying {@link org.sqlite.SQLiteDataSource}
     * so every pooled connection is opened in WAL mode with foreign keys enforced
     * and timestamps stored as TEXT. The pool is sized to a single connection: this
     * is a single-terminal install and SQLite serialises writes anyway, so a larger
     * pool would only add SQLITE_BUSY contention.
     */
    private HikariDataSource getDataSource() {
        HikariDataSource ds = dataSource;
        if (ds != null && !ds.isClosed()) {
            return ds;
        }
        synchronized (this) {
            if (dataSource != null && !dataSource.isClosed()) {
                return dataSource;
            }
            String url = buildJdbcUrl();

            org.sqlite.SQLiteConfig sqliteConfig = new org.sqlite.SQLiteConfig();
            // WAL + NORMAL is the crash-resistant, fast combination for a desktop app:
            // committed transactions survive an app/OS crash, and readers never block
            // the single writer.
            sqliteConfig.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
            sqliteConfig.setSynchronous(org.sqlite.SQLiteConfig.SynchronousMode.NORMAL);
            // Wait (rather than fail immediately) when the database is momentarily locked.
            sqliteConfig.setBusyTimeout(10000);
            // SQLite has foreign keys OFF by default; the schema relies on ON DELETE
            // CASCADE, so enforce them on every connection.
            sqliteConfig.enforceForeignKeys(true);
            // Store/return timestamps as formatted TEXT so substr(created_at, 1, 10)
            // date filtering keeps working. Without this, the driver would persist
            // setTimestamp() values as numeric epoch millis.
            sqliteConfig.setDateClass("TEXT");
            sqliteConfig.setDateStringFormat("yyyy-MM-dd HH:mm:ss.SSS");

            org.sqlite.SQLiteDataSource sqliteDs = new org.sqlite.SQLiteDataSource(sqliteConfig);
            sqliteDs.setUrl(url);

            HikariConfig hc = new HikariConfig();
            hc.setPoolName("pos-sqlite-pool");
            hc.setDataSource(sqliteDs);
            // Preserve existing transaction semantics: callers manage commit/rollback.
            hc.setAutoCommit(false);
            hc.setMaximumPoolSize(1);
            hc.setMinimumIdle(1);
            hc.setConnectionTimeout(10000); // 10s to acquire before failing
            // Log a stack trace if a connection is held longer than 20s without being
            // returned to the pool. With a single-connection pool a leak freezes the app,
            // so surfacing it in the logs is important.
            hc.setLeakDetectionThreshold(20000);
            logger.info("Initializing SQLite connection pool: {}", url);
            dataSource = new HikariDataSource(hc);
            return dataSource;
        }
    }

    /**
     * Get a pooled database connection. The returned connection has
     * auto-commit disabled (callers manage transactions) and must be closed by
     * the caller — closing returns it to the pool rather than tearing it down.
     * H2 AUTO_SERVER mode allows multiple concurrent connections.
     */
    public Connection getConnection() throws SQLException {
        try {
            return getDataSource().getConnection();
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (msg.contains("database is locked") || msg.contains("sqlite_busy")) {
                logger.error(
                        "Database is locked. This usually means another instance is running or a connection wasn't closed properly.");
                logger.error("Try closing all other instances of the application.");
            } else if (isCorruptionError(e)) {
                logger.error("Database file is corrupted. Restart the application to trigger automatic recovery.");
                notifyCorruption(e);
            }
            throw e;
        }
    }

    /**
     * Register a one-shot callback invoked when the database is found corrupt
     * after a successful open — for example on a scheduled-backup or sync thread.
     * The listener should route the user to a restart, where the startup recovery
     * path restores from the latest backup. Live in-place recovery is intentionally
     * avoided because the pool and UI may hold in-flight transactions.
     */
    public void setCorruptionListener(java.util.function.Consumer<Throwable> listener) {
        this.corruptionListener = listener;
    }

    /**
     * Fire the corruption listener at most once. No-ops until a listener is
     * registered (so corruption seen during startup, before the database is
     * confirmed open, never trips the one-shot guard prematurely).
     */
    private void notifyCorruption(Throwable error) {
        java.util.function.Consumer<Throwable> listener = corruptionListener;
        if (listener == null) {
            return;
        }
        if (!runtimeCorruptionReported.compareAndSet(false, true)) {
            return;
        }
        try {
            listener.accept(error);
        } catch (Exception e) {
            logger.warn("Database corruption listener threw: {}", e.getMessage());
        }
    }

    /**
     * Detect SQLite file corruption from an exception chain. Covers SQLITE_CORRUPT
     * (result code 11) and SQLITE_NOTADB (result code 26) plus the matching message
     * text the driver surfaces when an exception is wrapped as a plain SQLException.
     */
    public static boolean isCorruptionError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof org.sqlite.SQLiteException sqliteException) {
                int code = sqliteException.getResultCode().code;
                // SQLITE_CORRUPT = 11, SQLITE_NOTADB = 26 (primary result codes).
                if (code == 11 || code == 26 || (code & 0xFF) == 11 || (code & 0xFF) == 26) {
                    return true;
                }
            }

            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("malformed")
                        || lower.contains("not a database")
                        || lower.contains("disk image")
                        || lower.contains("file is encrypted")
                        || lower.contains("database disk image is malformed")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Close the connection pool, releasing all open connections. Must be called
     * before deleting the database files, otherwise SQLite keeps the files locked.
     */
    public synchronized void closePool() {
        if (dataSource != null) {
            logger.info("Closing SQLite connection pool");
            try {
                dataSource.close();
            } catch (Exception e) {
                logger.warn("Error closing connection pool: {}", e.getMessage());
            }
            dataSource = null;
        }
    }

    /**
     * Initialize database schema
     */
    public void initializeSchema() {
        try (Connection conn = getConnection()) {
            // Enable auto-commit for DDL statements
            conn.setAutoCommit(true);

            // Create products table
            String createProductsTable = """
                    CREATE TABLE IF NOT EXISTS products (
                        id VARCHAR(255) PRIMARY KEY,
                        name VARCHAR(255) NOT NULL,
                        sku VARCHAR(100) UNIQUE,
                        barcode VARCHAR(100),
                        price DECIMAL(10,2) NOT NULL,
                        list_price DECIMAL(10,2),
                        stock_quantity INTEGER DEFAULT 0,
                        status VARCHAR(50),
                        department_id VARCHAR(255),
                        updated_at VARCHAR(50),
                        sync_version BIGINT,
                        created_locally BOOLEAN DEFAULT FALSE,
                        synced BOOLEAN DEFAULT TRUE
                    )
                    """;

            // Create departments table - matching backend schema
            String createDepartmentsTable = """
                    CREATE TABLE IF NOT EXISTS departments (
                        id VARCHAR(255) PRIMARY KEY,
                        name VARCHAR(255) NOT NULL,
                        icon VARCHAR(255),
                        parent_id VARCHAR(255),

                        -- Department features (matching backend schema)
                        department_type VARCHAR(50) DEFAULT 'PRODUCT',
                        tax_enabled BOOLEAN DEFAULT TRUE,
                        tax_rate DECIMAL(5,2),
                        hide_on_register BOOLEAN DEFAULT FALSE,
                        ebt_eligible BOOLEAN DEFAULT FALSE,
                        exclude_from_global_price_increase BOOLEAN DEFAULT FALSE,
                        no_points_earning BOOLEAN DEFAULT FALSE,
                        age_verification INTEGER,

                        -- Multi-pack discount settings
                        multipack_enabled BOOLEAN DEFAULT FALSE,
                        multipack_discount_type VARCHAR(20),
                        multipack_discount_value DECIMAL(10,2),
                        multipack_min_quantity INTEGER,
                        multipack_requires_approval BOOLEAN DEFAULT FALSE,

                        updated_at VARCHAR(50),
                        created_locally BOOLEAN DEFAULT FALSE,
                        synced BOOLEAN DEFAULT TRUE
                    )
                    """;

            // Create sales table (for offline sync)
            String createSalesTable = """
                    CREATE TABLE IF NOT EXISTS sales (
                        id VARCHAR(255) PRIMARY KEY,
                        sale_id VARCHAR(255) UNIQUE NOT NULL,
                        subtotal DECIMAL(10,2),
                        discount DECIMAL(10,2),
                        tax DECIMAL(10,2),
                        total DECIMAL(10,2),
                        payment_method VARCHAR(50),
                        is_split_payment BOOLEAN DEFAULT FALSE,
                        cashier_name VARCHAR(255),
                        cashier_id VARCHAR(255),
                        pos_user_id VARCHAR(255),
                        timestamp VARCHAR(50),
                        amount_received DECIMAL(10,2),
                        change DECIMAL(10,2),
                        gpi DECIMAL(10,2) DEFAULT 0,
                        ebt_fee DECIMAL(10,2) DEFAULT 0,
                        sync_error TEXT,
                        synced BOOLEAN DEFAULT FALSE,
                        voided BOOLEAN DEFAULT FALSE,
                        voided_at TEXT,
                        voided_by VARCHAR(255),
                        void_reason VARCHAR(500),
                        shift_id VARCHAR(255),
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP
                    )
                    """;

            // Create sale_items table
            String createSaleItemsTable = """
                    CREATE TABLE IF NOT EXISTS sale_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        sale_id VARCHAR(255) NOT NULL,
                        product_id VARCHAR(255),
                        sku VARCHAR(100),
                        name VARCHAR(255),
                        price DECIMAL(10,2),
                        quantity INTEGER,
                        subtotal DECIMAL(10,2),
                        discount DECIMAL(10,2) DEFAULT 0,
                        discount_reason VARCHAR(255),
                        gpi DECIMAL(10,2) DEFAULT 0,
                        department_id VARCHAR(255),
                        department_name VARCHAR(255),
                        FOREIGN KEY (sale_id) REFERENCES sales(sale_id)
                    )
                    """;

            // Create sale_payments table (for split payments)
            String createSalePaymentsTable = """
                    CREATE TABLE IF NOT EXISTS sale_payments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        sale_id VARCHAR(255) NOT NULL,
                        payment_method VARCHAR(50) NOT NULL,
                        amount DECIMAL(10,2) NOT NULL,
                        FOREIGN KEY (sale_id) REFERENCES sales(sale_id) ON DELETE CASCADE
                    )
                    """;

            // Create refunds table
            String createRefundsTable = """
                    CREATE TABLE IF NOT EXISTS refunds (
                        id VARCHAR(255) PRIMARY KEY,
                        refund_id VARCHAR(255) UNIQUE NOT NULL,
                        original_sale_id VARCHAR(255) NOT NULL,
                        refund_amount DECIMAL(10,2),
                        refund_tax DECIMAL(10,2),
                        total_refund DECIMAL(10,2),
                        payment_method VARCHAR(50),
                        refund_method VARCHAR(50),
                        reason VARCHAR(500),
                        cashier_name VARCHAR(255),
                        pos_user_id VARCHAR(255),
                        timestamp VARCHAR(50),
                        synced BOOLEAN DEFAULT FALSE,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP
                    )
                    """;

            // Create refund_items table
            String createRefundItemsTable = """
                    CREATE TABLE IF NOT EXISTS refund_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        refund_id VARCHAR(255) NOT NULL,
                        product_id VARCHAR(255),
                        sku VARCHAR(100),
                        name VARCHAR(255),
                        original_price DECIMAL(10,2),
                        original_quantity INTEGER,
                        refund_quantity INTEGER,
                        refund_amount DECIMAL(10,2),
                        FOREIGN KEY (refund_id) REFERENCES refunds(refund_id)
                    )
                    """;

            // Create sync_metadata table
            String createSyncMetadataTable = """
                    CREATE TABLE IF NOT EXISTS sync_metadata (
                        id INTEGER PRIMARY KEY,
                        last_sync_time VARCHAR(50),
                        last_full_sync_time VARCHAR(50)
                    )
                    """;

            // Create store_settings table
            String createStoreSettingsTable = """
                    CREATE TABLE IF NOT EXISTS store_settings (
                        id VARCHAR(255) PRIMARY KEY,
                        store_id VARCHAR(255) NOT NULL,
                        store_name VARCHAR(255),
                        tax_enabled BOOLEAN DEFAULT TRUE,
                        tax_rate DECIMAL(10,2) DEFAULT 0,
                        currency VARCHAR(10) DEFAULT 'USD',
                        timezone VARCHAR(50) DEFAULT 'UTC',
                        receipt_header VARCHAR(500),
                        receipt_footer VARCHAR(500),
                        low_stock_threshold INTEGER DEFAULT 10,
                        minimum_sale_amount DECIMAL(10,2),
                        card_surcharge_enabled BOOLEAN DEFAULT FALSE,
                        card_surcharge_percent DECIMAL(5,2),
                        require_manager_approval_voids BOOLEAN DEFAULT TRUE,
                        require_manager_approval_discounts_over DECIMAL(10,2) DEFAULT 20,
                        require_manager_approval_refunds BOOLEAN DEFAULT TRUE,
                        updated_at VARCHAR(50)
                    )
                    """;

            // Create shifts table
            String createShiftsTable = """
                    CREATE TABLE IF NOT EXISTS shifts (
                        id VARCHAR(255) PRIMARY KEY,
                        shift_id VARCHAR(255) UNIQUE,
                        store_id VARCHAR(255),
                        cashier_name VARCHAR(255),
                        cashier_id VARCHAR(255),
                        register_id VARCHAR(255),
                        shift_number INTEGER,
                        shift_started_at VARCHAR(50),
                        shift_ended_at VARCHAR(50),
                        status VARCHAR(50) DEFAULT 'ACTIVE',
                        opening_cash DECIMAL(10,2) DEFAULT 0,
                        opening_note VARCHAR(500),
                        expected_cash DECIMAL(10,2),
                        actual_cash DECIMAL(10,2),
                        cash_difference DECIMAL(10,2),
                        closing_note VARCHAR(500),
                        total_cash_sales DECIMAL(10,2) DEFAULT 0,
                        total_card_sales DECIMAL(10,2) DEFAULT 0,
                        total_ebt_sales DECIMAL(10,2) DEFAULT 0,
                        total_other_sales DECIMAL(10,2) DEFAULT 0,
                        transaction_count INTEGER DEFAULT 0,
                        gross_sales DECIMAL(10,2) DEFAULT 0,
                        net_sales DECIMAL(10,2) DEFAULT 0,
                        total_discounts DECIMAL(10,2) DEFAULT 0,
                        total_tax DECIMAL(10,2) DEFAULT 0,
                        synced BOOLEAN DEFAULT FALSE,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT DEFAULT CURRENT_TIMESTAMP
                    )
                    """;

            // Create cash_operations table
            String createCashOperationsTable = """
                    CREATE TABLE IF NOT EXISTS cash_operations (
                        id VARCHAR(255) PRIMARY KEY,
                        shift_id VARCHAR(255) NOT NULL,
                        type VARCHAR(50) NOT NULL,
                        amount DECIMAL(10,2) NOT NULL,
                        note VARCHAR(500),
                        performed_by VARCHAR(255),
                        verified_by VARCHAR(255),
                        created_at VARCHAR(50),
                        synced BOOLEAN DEFAULT FALSE
                    )
                    """;

            // Create pending_requests table (for offline queue)
            String createPendingRequestsTable = """
                    CREATE TABLE IF NOT EXISTS pending_requests (
                        id VARCHAR(255) PRIMARY KEY,
                        endpoint VARCHAR(500) NOT NULL,
                        method VARCHAR(10) NOT NULL,
                        payload TEXT,
                        priority INTEGER DEFAULT 1,
                        retry_count INTEGER DEFAULT 0,
                        last_error TEXT,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                        last_retry_at TEXT
                    )
                    """;

            // Create pos_users table (for offline POS user login)
            String createPosUsersTable = """
                    CREATE TABLE IF NOT EXISTS pos_users (
                        id VARCHAR(255) PRIMARY KEY,
                        username VARCHAR(100) NOT NULL,
                        pin_hash VARCHAR(255) NOT NULL,
                        full_name VARCHAR(255),
                        store_id VARCHAR(255) NOT NULL,
                        is_active BOOLEAN DEFAULT TRUE,
                        role VARCHAR(50) DEFAULT 'Cashier',
                        last_login_at VARCHAR(50),
                        created_at VARCHAR(50),
                        synced_at VARCHAR(50)
                    )
                    """;

            // Create users table (for offline regular user login)
            String createUsersTable = """
                    CREATE TABLE IF NOT EXISTS users (
                        id VARCHAR(255) PRIMARY KEY,
                        email VARCHAR(255) NOT NULL,
                        password_hash VARCHAR(255) NOT NULL,
                        full_name VARCHAR(255),
                        role VARCHAR(100),
                        store_id VARCHAR(255),
                        synced_at VARCHAR(50)
                    )
                    """;

            // Create user_permissions table (for custom permission overrides)
            String createUserPermissionsTable = """
                    CREATE TABLE IF NOT EXISTS user_permissions (
                        id VARCHAR(255) PRIMARY KEY,
                        user_id VARCHAR(255) NOT NULL,
                        user_type VARCHAR(20) NOT NULL,
                        permission VARCHAR(100) NOT NULL,
                        granted BOOLEAN DEFAULT TRUE,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                        created_by VARCHAR(255),
                        UNIQUE(user_id, user_type, permission)
                    )
                    """;

            String createRolePermissionsTable = """
                    CREATE TABLE IF NOT EXISTS role_permissions (
                        id VARCHAR(255) PRIMARY KEY,
                        role_name VARCHAR(50) NOT NULL,
                        permission VARCHAR(100) NOT NULL,
                        granted BOOLEAN DEFAULT TRUE,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                        created_by VARCHAR(255),
                        UNIQUE(role_name, permission)
                    )
                    """;

            String createRolesTable = """
                    CREATE TABLE IF NOT EXISTS roles (
                        id VARCHAR(255) PRIMARY KEY,
                        role_name VARCHAR(50) NOT NULL UNIQUE,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                        created_by VARCHAR(255)
                    )
                    """;

            // Create inventory_log table (for tracking stock adjustments)
            String createInventoryLogTable = """
                    CREATE TABLE IF NOT EXISTS inventory_log (
                        id VARCHAR(255) PRIMARY KEY,
                        product_id VARCHAR(255) NOT NULL,
                        product_name VARCHAR(255),
                        change_type VARCHAR(50) NOT NULL,
                        previous_quantity INTEGER NOT NULL,
                        new_quantity INTEGER NOT NULL,
                        change_amount INTEGER NOT NULL,
                        reason VARCHAR(500),
                        user_id VARCHAR(255),
                        user_name VARCHAR(255),
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                        synced BOOLEAN DEFAULT FALSE
                    )
                    """;

            // Create held_sales table (for holding sales temporarily)
            String createHeldSalesTable = """
                    CREATE TABLE IF NOT EXISTS held_sales (
                        id VARCHAR(255) PRIMARY KEY,
                        hold_id VARCHAR(255) UNIQUE NOT NULL,
                        customer_name VARCHAR(255),
                        hold_note VARCHAR(500),
                        subtotal DECIMAL(10,2),
                        discount DECIMAL(10,2) DEFAULT 0,
                        tax DECIMAL(10,2),
                        total DECIMAL(10,2),
                        sale_discount DECIMAL(10,2) DEFAULT 0,
                        sale_discount_reason VARCHAR(255),
                        payment_method VARCHAR(50),
                        cashier_name VARCHAR(255),
                        cashier_id VARCHAR(255),
                        pos_user_id VARCHAR(255),
                        held_at TEXT DEFAULT CURRENT_TIMESTAMP,
                        expires_at TEXT,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP
                    )
                    """;

            // Create held_sale_items table (for items in held sales)
            String createHeldSaleItemsTable = """
                    CREATE TABLE IF NOT EXISTS held_sale_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        hold_id VARCHAR(255) NOT NULL,
                        product_id VARCHAR(255),
                        product_barcode VARCHAR(100),
                        product_name VARCHAR(255),
                        sku VARCHAR(100),
                        price DECIMAL(10,2),
                        list_price DECIMAL(10,2),
                        quantity INTEGER,
                        payment_method VARCHAR(50),
                        discount_amount DECIMAL(10,2) DEFAULT 0,
                        discount_percent DECIMAL(10,2) DEFAULT 0,
                        discount_reason VARCHAR(255),
                        FOREIGN KEY (hold_id) REFERENCES held_sales(hold_id) ON DELETE CASCADE
                    )
                    """;

            // Create ads table (for customer display ads)
            String createAdsTable = """
                    CREATE TABLE IF NOT EXISTS ads (
                        id VARCHAR(255) PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        subtitle VARCHAR(500),
                        image_url VARCHAR(1000),
                        video_url VARCHAR(1000),
                        ad_type VARCHAR(20) DEFAULT 'text',
                        background_color VARCHAR(20) DEFAULT '#e3f2fd',
                        text_color VARCHAR(20) DEFAULT '#1565c0',
                        display_order INTEGER DEFAULT 0,
                        is_active BOOLEAN DEFAULT TRUE,
                        start_date VARCHAR(50),
                        end_date VARCHAR(50),
                        updated_at VARCHAR(50),
                        synced_at VARCHAR(50),
                        display_duration INTEGER DEFAULT 5,
                        text_alignment VARCHAR(10) DEFAULT 'center',
                        title_font_size VARCHAR(10) DEFAULT 'large',
                        subtitle_font_size VARCHAR(10) DEFAULT 'medium',
                        is_title_bold BOOLEAN DEFAULT TRUE
                    )
                    """;

            // Create global_settings table (for system-wide settings like card pricing)
            String createGlobalSettingsTable = """
                    CREATE TABLE IF NOT EXISTS global_settings (
                        id VARCHAR(255) NOT NULL PRIMARY KEY,
                        card_surcharge_enabled BOOLEAN DEFAULT FALSE,
                        card_surcharge_percent DECIMAL(5,2),
                        multipack_enabled BOOLEAN DEFAULT FALSE,
                        multipack_discount_type VARCHAR(20) DEFAULT 'PERCENT',
                        multipack_discount_value DECIMAL(10,2) DEFAULT 10.00,
                        multipack_min_quantity INTEGER DEFAULT 2,
                        multipack_eligible_departments VARCHAR(2000),
                        multipack_requires_approval BOOLEAN DEFAULT FALSE,
                        receipt_print_mode VARCHAR(20) DEFAULT 'AUTO',
                        updated_at VARCHAR(50)
                    )
                    """;

            // Create vendors table (for tracking product suppliers)
            String createVendorsTable = """
                    CREATE TABLE IF NOT EXISTS vendors (
                        id VARCHAR(255) PRIMARY KEY,
                        name VARCHAR(255) NOT NULL,
                        contact_name VARCHAR(255),
                        email VARCHAR(255),
                        phone VARCHAR(50),
                        address VARCHAR(500),
                        payment_terms VARCHAR(100) DEFAULT 'NET30',
                        commission_rate DECIMAL(5,2) DEFAULT 0,
                        default_cost_margin DECIMAL(5,2) DEFAULT 0,
                        bank_account_info VARCHAR(500),
                        notes TEXT,
                        is_active BOOLEAN DEFAULT TRUE,
                        synced BOOLEAN DEFAULT FALSE,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                        updated_at VARCHAR(50)
                    )
                    """;

            // Create vendor_payouts table (for tracking payments to vendors)
            String createVendorPayoutsTable = """
                    CREATE TABLE IF NOT EXISTS vendor_payouts (
                        id VARCHAR(255) PRIMARY KEY,
                        vendor_id VARCHAR(255) NOT NULL,
                        vendor_name VARCHAR(255),
                        period_start VARCHAR(50),
                        period_end VARCHAR(50),
                        total_sales DECIMAL(12,2) DEFAULT 0,
                        total_cost DECIMAL(12,2) DEFAULT 0,
                        total_payout DECIMAL(12,2) DEFAULT 0,
                        commission_rate DECIMAL(5,2) DEFAULT 0,
                        item_count INTEGER DEFAULT 0,
                        transaction_count INTEGER DEFAULT 0,
                        status VARCHAR(50) DEFAULT 'PENDING',
                        paid_at VARCHAR(50),
                        paid_by VARCHAR(255),
                        payment_method VARCHAR(50),
                        payment_reference VARCHAR(255),
                        shift_id VARCHAR(255),
                        notes TEXT,
                        synced BOOLEAN DEFAULT FALSE,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                        updated_at VARCHAR(50),
                        FOREIGN KEY (vendor_id) REFERENCES vendors(id)
                    )
                    """;

            // Create vendor_payout_items table (line items for vendor payouts)
            String createVendorPayoutItemsTable = """
                    CREATE TABLE IF NOT EXISTS vendor_payout_items (
                        id VARCHAR(255) PRIMARY KEY,
                        payout_id VARCHAR(255) NOT NULL,
                        sale_id VARCHAR(255),
                        sale_item_id INTEGER,
                        product_id VARCHAR(255),
                        product_name VARCHAR(255),
                        product_sku VARCHAR(100),
                        quantity INTEGER DEFAULT 0,
                        unit_price DECIMAL(10,2) DEFAULT 0,
                        unit_cost DECIMAL(10,2) DEFAULT 0,
                        sale_amount DECIMAL(10,2) DEFAULT 0,
                        cost_amount DECIMAL(10,2) DEFAULT 0,
                        discount_amount DECIMAL(10,2) DEFAULT 0,
                        commission_rate DECIMAL(5,2) DEFAULT 0,
                        payout_amount DECIMAL(10,2) DEFAULT 0,
                        sale_date VARCHAR(50),
                        payment_method VARCHAR(50),
                        FOREIGN KEY (payout_id) REFERENCES vendor_payouts(id) ON DELETE CASCADE
                    )
                    """;

            // Create age_verifications table (for compliance records when selling
            // age-restricted products)
            String createAgeVerificationsTable = """
                    CREATE TABLE IF NOT EXISTS age_verifications (
                        id VARCHAR(255) PRIMARY KEY,
                        sale_id VARCHAR(255),
                        department_id VARCHAR(255) NOT NULL,
                        required_age INTEGER NOT NULL,
                        customer_dob TEXT NOT NULL,
                        customer_age INTEGER NOT NULL,
                        id_last_four VARCHAR(4),
                        id_expiration TEXT,
                        verification_method VARCHAR(20) NOT NULL,
                        verified_by VARCHAR(255) NOT NULL,
                        verified_at TEXT DEFAULT CURRENT_TIMESTAMP
                    )
                    """;

            // Create employee_shifts table (for tracking employee clock-in/clock-out times)
            String createEmployeeShiftsTable = """
                    CREATE TABLE IF NOT EXISTS employee_shifts (
                        id VARCHAR(255) PRIMARY KEY,
                        employee_id VARCHAR(255) NOT NULL,
                        employee_name VARCHAR(255),
                        store_id VARCHAR(255),
                        clock_in_at TEXT NOT NULL,
                        clock_out_at TEXT,
                        status VARCHAR(20) DEFAULT 'ACTIVE',
                        total_hours DECIMAL(10,2),
                        notes VARCHAR(500),
                        synced BOOLEAN DEFAULT FALSE,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP
                    )
                    """;

            // Create cart_cancellations table (for logging cart cancellations)
            String createCartCancellationsTable = """
                    CREATE TABLE IF NOT EXISTS cart_cancellations (
                        id VARCHAR(255) PRIMARY KEY,
                        receipt_number VARCHAR(50),
                        total_value DECIMAL(10,2),
                        subtotal DECIMAL(10,2),
                        discount DECIMAL(10,2),
                        tax DECIMAL(10,2),
                        item_count INTEGER,
                        cashier_name VARCHAR(255),
                        cashier_id VARCHAR(255),
                        pos_user_id VARCHAR(255),
                        shift_id VARCHAR(255),
                        timestamp TEXT DEFAULT CURRENT_TIMESTAMP,
                        synced BOOLEAN DEFAULT FALSE,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP
                    )
                    """;

            // Create cart_cancellation_items table (for items in canceled carts)
            String createCartCancellationItemsTable = """
                    CREATE TABLE IF NOT EXISTS cart_cancellation_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        cancellation_id VARCHAR(255) NOT NULL,
                        product_id VARCHAR(255),
                        product_name VARCHAR(255),
                        sku VARCHAR(100),
                        barcode VARCHAR(100),
                        quantity INTEGER,
                        unit_price DECIMAL(10,2),
                        subtotal DECIMAL(10,2),
                        discount DECIMAL(10,2) DEFAULT 0,
                        discount_reason VARCHAR(255),
                        department_id VARCHAR(255),
                        department_name VARCHAR(255),
                        FOREIGN KEY (cancellation_id) REFERENCES cart_cancellations(id) ON DELETE CASCADE
                    )
                    """;

            // Create expense_categories table
            String createExpenseCategoriesTable = """
                    CREATE TABLE IF NOT EXISTS expense_categories (
                        id VARCHAR(255) PRIMARY KEY,
                        name VARCHAR(255) NOT NULL UNIQUE,
                        description VARCHAR(500),
                        is_system BOOLEAN DEFAULT FALSE,
                        is_active BOOLEAN DEFAULT TRUE,
                        display_order INTEGER DEFAULT 0,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                        updated_at VARCHAR(50)
                    )
                    """;

            // Create expenses table
            String createExpensesTable = """
                    CREATE TABLE IF NOT EXISTS expenses (
                        id VARCHAR(255) PRIMARY KEY,
                        expense_id VARCHAR(255) UNIQUE NOT NULL,
                        category_id VARCHAR(255) NOT NULL,
                        category_name VARCHAR(255),
                        amount DECIMAL(10,2) NOT NULL,
                        description VARCHAR(500),
                        payment_method VARCHAR(50),
                        shift_id VARCHAR(255),
                        receipt_number VARCHAR(100),
                        vendor_name VARCHAR(255),
                        created_by VARCHAR(255),
                        created_by_name VARCHAR(255),
                        timestamp VARCHAR(50),
                        synced BOOLEAN DEFAULT FALSE,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (category_id) REFERENCES expense_categories(id)
                    )
                    """;

            // Create role_cash_check_limits table (for role-based cash/check limit settings)
            String createRoleCashCheckLimitsTable = """
                    CREATE TABLE IF NOT EXISTS role_cash_check_limits (
                        role_id VARCHAR(255) PRIMARY KEY,
                        role_name VARCHAR(255) NOT NULL,
                        limit_enabled BOOLEAN DEFAULT FALSE,
                        daily_limit INTEGER DEFAULT 5,
                        updated_at VARCHAR(50)
                    )
                    """;

            // Create cash_check_daily_usage table (for tracking daily usage per user)
            String createCashCheckDailyUsageTable = """
                    CREATE TABLE IF NOT EXISTS cash_check_daily_usage (
                        id VARCHAR(255) PRIMARY KEY,
                        user_id VARCHAR(255) NOT NULL,
                        usage_date TEXT NOT NULL,
                        operation_count INTEGER DEFAULT 0,
                        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE (user_id, usage_date)
                    )
                    """;

            // Execute DDL statements
            try (Statement stmt = conn.createStatement()) {
                // Create tables
                stmt.execute(createProductsTable);
                stmt.execute(createDepartmentsTable);
                stmt.execute(createSalesTable);
                stmt.execute(createSaleItemsTable);
                stmt.execute(createSalePaymentsTable);
                stmt.execute(createRefundsTable);
                stmt.execute(createRefundItemsTable);
                stmt.execute(createSyncMetadataTable);
                stmt.execute(createStoreSettingsTable);
                stmt.execute(createShiftsTable);
                stmt.execute(createCashOperationsTable);
                stmt.execute(createPendingRequestsTable);
                stmt.execute(createPosUsersTable);
                stmt.execute(createUsersTable);
                stmt.execute(createUserPermissionsTable);
                stmt.execute(createRolePermissionsTable);
                stmt.execute(createRolesTable);
                stmt.execute(createInventoryLogTable);
                stmt.execute(createHeldSalesTable);
                stmt.execute(createHeldSaleItemsTable);
                stmt.execute(createAdsTable);
                stmt.execute(createGlobalSettingsTable);
                stmt.execute(createVendorsTable);
                stmt.execute(createVendorPayoutsTable);
                stmt.execute(createVendorPayoutItemsTable);
                stmt.execute(createAgeVerificationsTable);
                stmt.execute(createEmployeeShiftsTable);
                stmt.execute(createCartCancellationsTable);
                stmt.execute(createCartCancellationItemsTable);
                stmt.execute(createExpenseCategoriesTable);
                stmt.execute(createExpensesTable);
                stmt.execute(createRoleCashCheckLimitsTable);
                stmt.execute(createCashCheckDailyUsageTable);
                // Key/value settings store (upsert target for SyncManager); never had an
                // explicit CREATE before the SQLite migration.
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS pos_settings (
                            setting_key VARCHAR(255) PRIMARY KEY,
                            setting_value TEXT
                        )
                        """);
                // store_settings is upserted by store_id in several places; give it a
                // UNIQUE constraint so ON CONFLICT(store_id) has a valid target.
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_store_settings_store ON store_settings(store_id)");

                // Create indexes separately (H2 doesn't support inline INDEX in CREATE TABLE)
                try {
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_barcode ON products(barcode)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_sku ON products(sku)");
                    // Functional indexes for case-insensitive search
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_lower_name ON products(LOWER(name))");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_lower_sku ON products(LOWER(sku))");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_lower_barcode ON products(LOWER(barcode))");

                    // Standard indexes
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_name ON products(name)");

                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_parent ON departments(parent_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_pos_user ON sales(pos_user_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_sale ON sale_items(sale_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_sale_payments_sale ON sale_payments(sale_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_status ON shifts(status)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_cashier ON shifts(cashier_name)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_store ON shifts(store_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_shift ON cash_operations(shift_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_type ON cash_operations(type)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_priority ON pending_requests(priority)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_retry_count ON pending_requests(retry_count)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_pos_users_username ON pos_users(username)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_pos_users_store ON pos_users(store_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_users_email ON users(email)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_users_store ON users(store_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_inventory_product ON inventory_log(product_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_inventory_type ON inventory_log(change_type)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_inventory_created ON inventory_log(created_at)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_held_sales_hold_id ON held_sales(hold_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_held_sales_held_at ON held_sales(held_at)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_held_sales_expires_at ON held_sales(expires_at)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_held_sale_items_hold_id ON held_sale_items(hold_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_ads_display_order ON ads(display_order)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_ads_is_active ON ads(is_active)");
                    // Vendor indexes
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_vendors_name ON vendors(name)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_vendors_active ON vendors(is_active)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_product_vendor ON products(vendor_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_vendor_payouts_vendor ON vendor_payouts(vendor_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_vendor_payouts_status ON vendor_payouts(status)");
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_vendor_payouts_period ON vendor_payouts(period_start, period_end)");
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_vendor_payouts_shift ON vendor_payouts(shift_id)");
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_vendor_payouts_paid_at ON vendor_payouts(paid_at)");
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_vendor_payout_items_payout ON vendor_payout_items(payout_id)");
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_vendor_payout_items_sale ON vendor_payout_items(sale_id)");
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_vendor_payout_items_product ON vendor_payout_items(product_id)");
                    // Age verification indexes
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_age_verifications_sale ON age_verifications(sale_id)");
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_age_verifications_dept ON age_verifications(department_id)");
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_age_verifications_verified_at ON age_verifications(verified_at)");
                    // Employee shift indexes
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_employee_shifts_employee ON employee_shifts(employee_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_employee_shifts_store ON employee_shifts(store_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_employee_shifts_status ON employee_shifts(status)");
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_employee_shifts_clock_in ON employee_shifts(clock_in_at)");
                    // Cart cancellation indexes
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_cart_cancellations_timestamp ON cart_cancellations(timestamp)");
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_cart_cancellations_cashier ON cart_cancellations(cashier_id)");
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_cart_cancellations_shift ON cart_cancellations(shift_id)");
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_cart_cancellation_items_cancellation ON cart_cancellation_items(cancellation_id)");
                    // Expense indexes
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_expense_categories_name ON expense_categories(name)");
                    stmt.execute(
                            "CREATE INDEX IF NOT EXISTS idx_expense_categories_active ON expense_categories(is_active)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_expenses_category ON expenses(category_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_expenses_timestamp ON expenses(timestamp)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_expenses_shift ON expenses(shift_id)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_expenses_synced ON expenses(synced)");
                } catch (SQLException e) {
                    // Indexes may already exist, log but don't fail
                    logger.debug("Could not create some indexes (may already exist): {}", e.getMessage());
                }

                // Migration: Add list_price column if it doesn't exist
                try {
                    stmt.execute("ALTER TABLE products ADD COLUMN list_price DECIMAL(10,2)");
                    logger.info("Added list_price column to products table");
                } catch (SQLException e) {
                    // Column already exists or table doesn't exist yet (handled by CREATE TABLE IF
                    // NOT EXISTS)
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add list_price column (may already exist): {}", e.getMessage());
                    }
                }

                // Migration: Add gpi column to sales if it doesn't exist
                try {
                    stmt.execute("ALTER TABLE sales ADD COLUMN gpi DECIMAL(10,2) DEFAULT 0");
                    logger.info("Added gpi column to sales table");
                } catch (SQLException e) {
                    // Column already exists or table doesn't exist yet
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add gpi column (may already exist): {}", e.getMessage());
                    }
                }

                // Migration: Add shift_id column to sales if it doesn't exist
                try {
                    stmt.execute("ALTER TABLE sales ADD COLUMN shift_id VARCHAR(255)");
                    logger.info("Added shift_id column to sales table");
                    
                    // Optional: Create index for shift_id
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_sales_shift ON sales(shift_id)");
                    // Index the sync flag — pending-sales queries filter on synced = FALSE
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_sales_synced ON sales(synced)");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add shift_id column (may already exist): {}", e.getMessage());
                    }
                }

                // Migration: Add ebt_fee column to sales if it doesn't exist
                try {
                    stmt.execute("ALTER TABLE sales ADD COLUMN ebt_fee DECIMAL(10,2) DEFAULT 0");
                    logger.info("Added ebt_fee column to sales table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add gpi column to sales (may already exist): {}", e.getMessage());
                    }
                }

                // Migration: Add gpi column to sale_items if it doesn't exist
                try {
                    stmt.execute("ALTER TABLE sale_items ADD COLUMN gpi DECIMAL(10,2) DEFAULT 0");
                    logger.info("Added gpi column to sale_items table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add gpi column to sale_items (may already exist): {}", e.getMessage());
                    }
                }

                // Migration: Back-calculate GPI for existing sales if gpi is 0
                try {
                    // Check if we have any CARD or SPLIT sales with 0 GPI
                    ResultSet rsCheck = stmt
                            .executeQuery(
                                    "SELECT COUNT(*) FROM sales WHERE (payment_method = 'CARD' OR payment_method = 'SPLIT') AND gpi = 0");
                    if (rsCheck.next() && rsCheck.getInt(1) > 0) {
                        // Get current surcharge percent
                        ResultSet rsSurcharge = stmt.executeQuery(
                                "SELECT card_surcharge_percent FROM global_settings WHERE id = 'global' AND card_surcharge_enabled = TRUE");
                        if (rsSurcharge.next()) {
                            double percent = rsSurcharge.getDouble("card_surcharge_percent");
                            if (percent > 0) {
                                logger.info("Back-calculating GPI for historical sales using {}% surcharge...",
                                        percent);
                                double factor = percent / (100.0 + percent);

                                // Update sales table
                                String updateSales = String.format(
                                        "UPDATE sales SET gpi = subtotal * %f WHERE (payment_method = 'CARD' OR payment_method = 'SPLIT') AND gpi = 0",
                                        factor);
                                stmt.executeUpdate(updateSales);

                                // Update sale_items table
                                String updateItems = String.format(
                                        "UPDATE sale_items SET gpi = subtotal * %f WHERE gpi = 0 AND sale_id IN (SELECT sale_id FROM sales WHERE payment_method = 'CARD' OR payment_method = 'SPLIT')",
                                        factor);
                                stmt.executeUpdate(updateItems);

                                logger.info("Successfully updated historical GPI records.");
                            }
                        }
                    }
                } catch (SQLException e) {
                    logger.warn("Failed to back-calculate historical GPI: {}", e.getMessage());
                }

                // Migration: Add discount columns to sale_items if they don't exist
                try {
                    // Check if discount column exists
                    java.sql.DatabaseMetaData metaData = conn.getMetaData();
                    java.sql.ResultSet columns = metaData.getColumns(null, null, "sale_items", "discount");
                    boolean discountColumnExists = columns.next();
                    columns.close();

                    if (!discountColumnExists) {
                        logger.info("Adding discount columns to sale_items table");
                        stmt.execute("ALTER TABLE sale_items ADD COLUMN discount DECIMAL(10,2) DEFAULT 0");
                        stmt.execute("ALTER TABLE sale_items ADD COLUMN discount_reason VARCHAR(255)");
                        logger.info("Discount columns added successfully");
                    }
                } catch (SQLException e) {
                    // Column might already exist or table doesn't exist yet, ignore error
                    logger.debug("Discount columns migration check: {}", e.getMessage());
                }

                // Migration: Add amount_received and change columns to sales table if they
                // don't exist
                try {
                    java.sql.DatabaseMetaData metaData = conn.getMetaData();
                    java.sql.ResultSet columns = metaData.getColumns(null, null, "sales", "amount_received");
                    boolean amountReceivedColumnExists = columns.next();
                    columns.close();

                    if (!amountReceivedColumnExists) {
                        logger.info("Adding amount_received and change columns to sales table");
                        stmt.execute("ALTER TABLE sales ADD COLUMN amount_received DECIMAL(10,2)");
                        stmt.execute("ALTER TABLE sales ADD COLUMN change DECIMAL(10,2)");
                        logger.info("Cash payment columns added successfully");
                    }
                } catch (SQLException e) {
                    // Column might already exist or table doesn't exist yet, ignore error
                    logger.debug("Cash payment columns migration check: {}", e.getMessage());
                }

                // Migration: Add reorder_level column if it doesn't exist
                try {
                    stmt.execute("ALTER TABLE products ADD COLUMN reorder_level INTEGER DEFAULT 0");
                    logger.info("Added reorder_level column to products table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add reorder_level column (may already exist): {}", e.getMessage());
                    }
                }

                // Migration: Convert empty SKU and barcode values to NULL
                // This fixes unique constraint violations for products without SKU
                try {
                    int updatedSku = stmt.executeUpdate("UPDATE products SET sku = NULL WHERE sku = ''");
                    int updatedBarcode = stmt.executeUpdate("UPDATE products SET barcode = NULL WHERE barcode = ''");
                    if (updatedSku > 0 || updatedBarcode > 0) {
                        logger.info("Converted empty SKU/barcode values to NULL: {} SKUs, {} barcodes",
                                updatedSku, updatedBarcode);
                    }
                } catch (SQLException e) {
                    logger.debug("Empty SKU/barcode migration: {}", e.getMessage());
                }

                // Migration: Add expires_at column to held_sales if it doesn't exist
                try {
                    stmt.execute("ALTER TABLE held_sales ADD COLUMN expires_at TEXT");
                    logger.info("Added expires_at column to held_sales table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add expires_at column (may already exist): {}", e.getMessage());
                    }
                }

                // Migration: Add voided columns to sales table if they don't exist
                try {
                    java.sql.DatabaseMetaData metaData = conn.getMetaData();
                    java.sql.ResultSet columns = metaData.getColumns(null, null, "sales", "voided");
                    boolean voidedColumnExists = columns.next();
                    columns.close();

                    if (!voidedColumnExists) {
                        logger.info("Adding voided columns to sales table");
                        stmt.execute("ALTER TABLE sales ADD COLUMN voided BOOLEAN DEFAULT FALSE");
                        stmt.execute("ALTER TABLE sales ADD COLUMN voided_at TEXT");
                        stmt.execute("ALTER TABLE sales ADD COLUMN voided_by VARCHAR(255)");
                        stmt.execute("ALTER TABLE sales ADD COLUMN void_reason VARCHAR(500)");
                        logger.info("Voided columns added successfully");
                    }
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Voided columns migration check: {}", e.getMessage());
                    }
                }

                // Migration: Add is_split_payment column to sales table if it doesn't exist
                try {
                    stmt.execute("ALTER TABLE sales ADD COLUMN is_split_payment BOOLEAN DEFAULT FALSE");
                    logger.info("Added is_split_payment column to sales table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add is_split_payment column (may already exist): {}", e.getMessage());
                    }
                }

                // Migration: Add role, last_login_at, created_at columns to pos_users if they
                // don't exist
                try {
                    stmt.execute("ALTER TABLE pos_users ADD COLUMN role VARCHAR(50) DEFAULT 'Cashier'");
                    logger.info("Added role column to pos_users table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add role column (may already exist): {}", e.getMessage());
                    }
                }

                try {
                    stmt.execute("ALTER TABLE pos_users ADD COLUMN last_login_at VARCHAR(50)");
                    logger.info("Added last_login_at column to pos_users table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add last_login_at column (may already exist): {}", e.getMessage());
                    }
                }

                try {
                    stmt.execute("ALTER TABLE pos_users ADD COLUMN created_at VARCHAR(50)");
                    logger.info("Added created_at column to pos_users table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add created_at column (may already exist): {}", e.getMessage());
                    }
                }

                try {
                    stmt.execute("""
                            CREATE TABLE IF NOT EXISTS role_permissions (
                                id VARCHAR(255) PRIMARY KEY,
                                role_name VARCHAR(50) NOT NULL,
                                permission VARCHAR(100) NOT NULL,
                                granted BOOLEAN DEFAULT TRUE,
                                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                                created_by VARCHAR(255),
                                UNIQUE(role_name, permission)
                            )
                            """);
                    logger.info("Ensured role_permissions table exists");
                } catch (SQLException e) {
                    logger.debug("Could not create role_permissions table (may already exist): {}", e.getMessage());
                }

                try {
                    stmt.execute("""
                            CREATE TABLE IF NOT EXISTS roles (
                                id VARCHAR(255) PRIMARY KEY,
                                role_name VARCHAR(50) NOT NULL UNIQUE,
                                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                                created_by VARCHAR(255)
                            )
                            """);
                    logger.info("Ensured roles table exists");
                } catch (SQLException e) {
                    logger.debug("Could not create roles table (may already exist): {}", e.getMessage());
                }

                // Migration: Add performed_by_name column to cash_operations table if it doesn't exist
                try {
                    stmt.execute("ALTER TABLE cash_operations ADD COLUMN performed_by_name VARCHAR(255)");
                    logger.info("Added performed_by_name column to cash_operations table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add performed_by_name column (may already exist): {}", e.getMessage());
                    }
                }

                // Migration: Add sync_error column to sales table if it doesn't exist
                try {
                    stmt.execute("ALTER TABLE sales ADD COLUMN sync_error TEXT");
                    logger.info("Added sync_error column to sales table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add sync_error column (may already exist): {}", e.getMessage());
                    }
                }

                // Migration: Add department feature columns if they don't exist (matching
                // backend schema)
                String[] departmentColumns = {
                        "ALTER TABLE departments ADD COLUMN icon VARCHAR(255)",
                        "ALTER TABLE departments ADD COLUMN department_type VARCHAR(50) DEFAULT 'PRODUCT'",
                        "ALTER TABLE departments ADD COLUMN tax_enabled BOOLEAN DEFAULT TRUE",
                        "ALTER TABLE departments ADD COLUMN tax_rate DECIMAL(5,2)",
                        "ALTER TABLE departments ADD COLUMN hide_on_register BOOLEAN DEFAULT FALSE",
                        "ALTER TABLE departments ADD COLUMN ebt_eligible BOOLEAN DEFAULT FALSE",
                        "ALTER TABLE departments ADD COLUMN exclude_from_global_price_increase BOOLEAN DEFAULT FALSE",
                        "ALTER TABLE departments ADD COLUMN no_points_earning BOOLEAN DEFAULT FALSE",
                        "ALTER TABLE departments ADD COLUMN age_verification INTEGER"
                };

                for (String alterSql : departmentColumns) {
                    try {
                        stmt.execute(alterSql);
                        logger.info("Migration: {}", alterSql);
                    } catch (SQLException e) {
                        if (!e.getMessage().contains("already exists")
                                && !e.getMessage().contains("duplicate column")) {
                            logger.debug("Migration skipped (may already exist): {}", e.getMessage());
                        }
                    }
                }

                // Migration: Add created_locally and synced columns to products if they don't
                // exist
                try {
                    stmt.execute("ALTER TABLE products ADD COLUMN created_locally BOOLEAN DEFAULT FALSE");
                    logger.info("Added created_locally column to products table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add created_locally column (may already exist): {}", e.getMessage());
                    }
                }

                try {
                    stmt.execute("ALTER TABLE products ADD COLUMN synced BOOLEAN DEFAULT TRUE");
                    logger.info("Added synced column to products table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add synced column (may already exist): {}", e.getMessage());
                    }
                }

                // Migration: Add created_locally and synced columns to departments if they
                // don't exist
                try {
                    stmt.execute("ALTER TABLE departments ADD COLUMN created_locally BOOLEAN DEFAULT FALSE");
                    logger.info("Added created_locally column to departments table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add created_locally column (may already exist): {}", e.getMessage());
                    }
                }

                try {
                    stmt.execute("ALTER TABLE departments ADD COLUMN synced BOOLEAN DEFAULT TRUE");
                    logger.info("Added synced column to departments table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add synced column (may already exist): {}", e.getMessage());
                    }
                }

                // Migration: Add department_id and department_name columns to sale_items for
                // proper department tracking
                try {
                    stmt.execute("ALTER TABLE sale_items ADD COLUMN department_id VARCHAR(255)");
                    logger.info("Added department_id column to sale_items table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add department_id column (may already exist): {}", e.getMessage());
                    }
                }

                try {
                    stmt.execute("ALTER TABLE sale_items ADD COLUMN department_name VARCHAR(255)");
                    logger.info("Added department_name column to sale_items table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add department_name column (may already exist): {}", e.getMessage());
                    }
                }

                // Migration: Add vendor columns to products table for vendor payout tracking
                String[] vendorProductColumns = {
                        "ALTER TABLE products ADD COLUMN vendor_id VARCHAR(255)",
                        "ALTER TABLE products ADD COLUMN vendor_name VARCHAR(255)",
                        "ALTER TABLE products ADD COLUMN cost DECIMAL(10,2)"
                };
                for (String alterSql : vendorProductColumns) {
                    try {
                        stmt.execute(alterSql);
                        logger.info("Migration: {}", alterSql);
                    } catch (SQLException e) {
                        if (!e.getMessage().contains("already exists")
                                && !e.getMessage().contains("duplicate column")) {
                            logger.debug("Vendor migration skipped (may already exist): {}", e.getMessage());
                        }
                    }
                }

                // Migration: Add vendor columns to sale_items for tracking vendor info at time
                // of sale
                String[] vendorSaleItemColumns = {
                        "ALTER TABLE sale_items ADD COLUMN vendor_id VARCHAR(255)",
                        "ALTER TABLE sale_items ADD COLUMN vendor_name VARCHAR(255)",
                        "ALTER TABLE sale_items ADD COLUMN cost DECIMAL(10,2)"
                };
                for (String alterSql : vendorSaleItemColumns) {
                    try {
                        stmt.execute(alterSql);
                        logger.info("Migration: {}", alterSql);
                    } catch (SQLException e) {
                        if (!e.getMessage().contains("already exists")
                                && !e.getMessage().contains("duplicate column")) {
                            logger.debug("Vendor sale_items migration skipped (may already exist): {}", e.getMessage());
                        }
                    }
                }

                // Migration: Add amount_paid and cheque_number columns to vendor_payouts for
                // payment tracking
                String[] vendorPayoutPaymentColumns = {
                        "ALTER TABLE vendor_payouts ADD COLUMN amount_paid DECIMAL(12,2) DEFAULT 0",
                        "ALTER TABLE vendor_payouts ADD COLUMN cheque_number VARCHAR(100)",
                        "ALTER TABLE vendor_payouts ADD COLUMN shift_id VARCHAR(255)"
                };
                for (String alterSql : vendorPayoutPaymentColumns) {
                    try {
                        stmt.execute(alterSql);
                        logger.info("Migration: {}", alterSql);
                    } catch (SQLException e) {
                        if (!e.getMessage().contains("already exists")
                                && !e.getMessage().contains("duplicate column")) {
                            logger.debug("Vendor payout payment migration skipped (may already exist): {}",
                                    e.getMessage());
                        }
                    }
                }

                // Migration: Add video_url and ad_type columns to ads table for video ads
                // support
                String[] adsVideoColumns = {
                        "ALTER TABLE ads ADD COLUMN video_url VARCHAR(1000)",
                        "ALTER TABLE ads ADD COLUMN ad_type VARCHAR(20) DEFAULT 'text'"
                };
                for (String alterSql : adsVideoColumns) {
                    try {
                        stmt.execute(alterSql);
                        logger.info("Migration: {}", alterSql);
                    } catch (SQLException e) {
                        if (!e.getMessage().contains("already exists")
                                && !e.getMessage().contains("duplicate column")) {
                            logger.debug("Ads video migration skipped (may already exist): {}", e.getMessage());
                        }
                    }
                }

                // Migration: Add multi-pack discount columns to global_settings if they don't
                // exist
                try {
                    java.sql.DatabaseMetaData metaData = conn.getMetaData();
                    java.sql.ResultSet columns = metaData.getColumns(null, null, "global_settings",
                            "multipack_enabled");
                    boolean multipackEnabledExists = columns.next();
                    columns.close();

                    if (!multipackEnabledExists) {
                        logger.info("Adding multi-pack discount columns to global_settings table");
                        stmt.execute("ALTER TABLE global_settings ADD COLUMN multipack_enabled BOOLEAN DEFAULT FALSE");
                        stmt.execute(
                                "ALTER TABLE global_settings ADD COLUMN multipack_discount_type VARCHAR(20) DEFAULT 'PERCENT'");
                        stmt.execute(
                                "ALTER TABLE global_settings ADD COLUMN multipack_discount_value DECIMAL(10,2) DEFAULT 10.00");
                        stmt.execute("ALTER TABLE global_settings ADD COLUMN multipack_min_quantity INTEGER DEFAULT 2");
                        stmt.execute(
                                "ALTER TABLE global_settings ADD COLUMN multipack_eligible_departments VARCHAR(2000)");
                        stmt.execute(
                                "ALTER TABLE global_settings ADD COLUMN multipack_requires_approval BOOLEAN DEFAULT FALSE");
                        logger.info("Multi-pack discount columns added successfully");
                    }
                } catch (SQLException e) {
                    // Column might already exist or table doesn't exist yet, ignore error
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Multi-pack discount columns migration check: {}", e.getMessage());
                    }
                }

                // Migration: Add receipt_print_mode column to global_settings if it doesn't
                // exist
                try {
                    stmt.execute(
                            "ALTER TABLE global_settings ADD COLUMN receipt_print_mode VARCHAR(20) DEFAULT 'AUTO'");
                    logger.info("Added receipt_print_mode column to global_settings table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add receipt_print_mode column (may already exist): {}", e.getMessage());
                    }
                }

                // Migration: Add store_phone and store_address columns to store_settings if
                // they don't exist
                try {
                    stmt.execute("ALTER TABLE store_settings ADD COLUMN store_phone VARCHAR(50)");
                    logger.info("Added store_phone column to store_settings table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add store_phone column (may already exist): {}", e.getMessage());
                    }
                }
                try {
                    stmt.execute("ALTER TABLE store_settings ADD COLUMN store_address VARCHAR(500)");
                    logger.info("Added store_address column to store_settings table");
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Could not add store_address column (may already exist): {}", e.getMessage());
                    }
                }

                // Migration: minimum sale amount (store policy)
                try {
                    java.sql.DatabaseMetaData metaData = conn.getMetaData();
                    java.sql.ResultSet columns = metaData.getColumns(null, null, "store_settings",
                            "minimum_sale_amount");
                    boolean colExists = columns.next();
                    columns.close();
                    if (!colExists) {
                        logger.info("Adding minimum_sale_amount column to store_settings table");
                        stmt.execute("ALTER TABLE store_settings ADD COLUMN minimum_sale_amount DECIMAL(10,2)");
                    }
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("minimum_sale_amount column migration check: {}", e.getMessage());
                    }
                }

                // Migration: Add multi-pack discount columns to departments table if they don't
                // exist
                try {
                    java.sql.DatabaseMetaData metaData = conn.getMetaData();
                    java.sql.ResultSet columns = metaData.getColumns(null, null, "departments",
                            "multipack_enabled");
                    boolean multipackEnabledExists = columns.next();
                    columns.close();

                    if (!multipackEnabledExists) {
                        logger.info("Adding multi-pack discount columns to departments table");
                        stmt.execute("ALTER TABLE departments ADD COLUMN multipack_enabled BOOLEAN DEFAULT FALSE");
                        stmt.execute(
                                "ALTER TABLE departments ADD COLUMN multipack_discount_type VARCHAR(20)");
                        stmt.execute(
                                "ALTER TABLE departments ADD COLUMN multipack_discount_value DECIMAL(10,2)");
                        stmt.execute("ALTER TABLE departments ADD COLUMN multipack_min_quantity INTEGER");
                        stmt.execute(
                                "ALTER TABLE departments ADD COLUMN multipack_requires_approval BOOLEAN DEFAULT FALSE");
                        logger.info("Multi-pack discount columns added to departments table successfully");
                    }
                } catch (SQLException e) {
                    // Column might already exist or table doesn't exist yet, ignore error
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("Multi-pack discount columns migration check for departments: {}", e.getMessage());
                    }
                }

                // Migration: Add manual_cash_check_pin_hash column to global_settings if it doesn't exist
                try {
                    java.sql.DatabaseMetaData metaData = conn.getMetaData();
                    java.sql.ResultSet columns = metaData.getColumns(null, null, "global_settings",
                            "manual_cash_check_pin_hash");
                    boolean pinHashExists = columns.next();
                    columns.close();

                    if (!pinHashExists) {
                        logger.info("Adding manual_cash_check_pin_hash column to global_settings table");
                        stmt.execute("ALTER TABLE global_settings ADD COLUMN manual_cash_check_pin_hash VARCHAR(255)");
                        logger.info("manual_cash_check_pin_hash column added successfully");
                    }
                } catch (SQLException e) {
                    if (!e.getMessage().contains("already exists") && !e.getMessage().contains("duplicate column")) {
                        logger.debug("manual_cash_check_pin_hash column migration check: {}", e.getMessage());
                    }
                }

                // Initialize default expense categories if they don't exist
                initializeDefaultExpenseCategories(conn);

                logger.info("Database schema initialized successfully");
            }

            // Reset auto-commit
            conn.setAutoCommit(false);
        } catch (SQLException e) {
            logger.error("Error initializing database schema", e);
            throw new RuntimeException("Failed to initialize database schema", e);
        }
    }

    /**
     * Initialize default expense categories if they don't exist
     */
    private void initializeDefaultExpenseCategories(Connection conn) {
        String[] defaultCategories = {
                "Rent",
                "Utilities",
                "Supplies",
                "Repairs & Maintenance",
                "Insurance",
                "Marketing & Advertising",
                "Professional Services",
                "Other"
        };

        String[] descriptions = {
                "Monthly rent payments",
                "Electric, water, gas, internet, phone",
                "Office supplies, cleaning supplies, etc.",
                "Equipment repairs and maintenance",
                "Business insurance premiums",
                "Marketing and advertising expenses",
                "Legal, accounting, consulting services",
                "Other miscellaneous expenses"
        };

        try (java.sql.PreparedStatement checkStmt = conn.prepareStatement(
                "SELECT COUNT(*) FROM expense_categories WHERE name = ?");
                java.sql.PreparedStatement insertStmt = conn.prepareStatement(
                        "INSERT INTO expense_categories (id, name, description, is_system, is_active, display_order) VALUES (?, ?, ?, TRUE, TRUE, ?)")) {

            for (int i = 0; i < defaultCategories.length; i++) {
                // Check if category already exists
                checkStmt.setString(1, defaultCategories[i]);
                java.sql.ResultSet rs = checkStmt.executeQuery();
                if (rs.next() && rs.getInt(1) == 0) {
                    // Category doesn't exist, insert it
                    insertStmt.setString(1, java.util.UUID.randomUUID().toString());
                    insertStmt.setString(2, defaultCategories[i]);
                    insertStmt.setString(3, descriptions[i]);
                    insertStmt.setInt(4, i);
                    insertStmt.executeUpdate();
                    logger.info("Initialized default expense category: {}", defaultCategories[i]);
                }
            }
        } catch (SQLException e) {
            logger.warn("Error initializing default expense categories: {}", e.getMessage());
        }
    }

    /**
     * Clear all store-specific data from the database.
     * Called when registering device to a new store to ensure clean slate.
     * This removes ALL data from the database to ensure a completely fresh start.
     */
    public void clearStoreData() {
        logger.info("Clearing ALL data from database...");

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                // Delete all data from all tables in the correct order (respecting foreign
                // keys)

                // First, delete child tables (those with foreign keys)
                safeDeleteFrom(stmt, "held_sale_items");
                safeDeleteFrom(stmt, "sale_items");
                safeDeleteFrom(stmt, "sale_payments");
                safeDeleteFrom(stmt, "refund_items");
                safeDeleteFrom(stmt, "cash_operations");
                safeDeleteFrom(stmt, "inventory_log");
                safeDeleteFrom(stmt, "user_permissions");
                safeDeleteFrom(stmt, "role_permissions");
                safeDeleteFrom(stmt, "roles");
                safeDeleteFrom(stmt, "vendor_payout_items");
                safeDeleteFrom(stmt, "cart_cancellation_items");
                safeDeleteFrom(stmt, "expenses");

                // Then delete parent tables
                safeDeleteFrom(stmt, "held_sales");
                safeDeleteFrom(stmt, "sales");
                safeDeleteFrom(stmt, "refunds");
                safeDeleteFrom(stmt, "shifts");
                safeDeleteFrom(stmt, "cart_cancellations");
                safeDeleteFrom(stmt, "pending_requests");
                safeDeleteFrom(stmt, "vendor_payouts");
                safeDeleteFrom(stmt, "vendors");

                // Clear store-specific configuration and data
                safeDeleteFrom(stmt, "pos_users");
                safeDeleteFrom(stmt, "users");
                safeDeleteFrom(stmt, "products");
                safeDeleteFrom(stmt, "departments");
                safeDeleteFrom(stmt, "favorite_products");
                safeDeleteFrom(stmt, "ads");
                safeDeleteFrom(stmt, "store_settings");
                safeDeleteFrom(stmt, "global_settings");
                safeDeleteFrom(stmt, "sync_metadata");
                safeDeleteFrom(stmt, "expense_categories");

                conn.commit();
                logger.info("All database data cleared successfully");

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }

        } catch (SQLException e) {
            logger.error("Error clearing store data", e);
            throw new RuntimeException("Failed to clear store data", e);
        }
    }

    /**
     * Check if database file exists
     * 
     * @return true if database file exists, false otherwise
     */
    public boolean databaseFileExists() {
        try {
            String dbPath = extractDatabasePath(config.getProperty("database.url"));
            if (dbPath == null) {
                return false;
            }
            // SQLite stores everything in the single .db file named in the URL.
            File dbFile = new File(dbPath);
            boolean exists = dbFile.exists() && dbFile.length() > 0;
            logger.debug("Database file exists check: {} (path: {})", exists, dbFile.getAbsolutePath());
            return exists;
        } catch (Exception e) {
            logger.warn("Error checking if database file exists: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if database is empty (no data in key tables)
     * This checks if products, users, or departments tables have any data
     * 
     * @return true if database is empty or doesn't exist, false if it has data
     */
    public boolean isDatabaseEmpty() {
        // First check if database file exists
        if (!databaseFileExists()) {
            logger.info("Database file does not exist - database is empty");
            return true;
        }

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(true);

            // Check if any key tables have data
            // We check products, pos_users, and departments as indicators of populated
            // database
            String[] checkTables = { "products", "pos_users", "departments" };

            for (String table : checkTables) {
                try (Statement stmt = conn.createStatement()) {
                    // Check if table exists and has rows
                    java.sql.ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as count FROM " + table);
                    if (rs.next()) {
                        int count = rs.getInt("count");
                        if (count > 0) {
                            logger.debug("Database is not empty - {} table has {} rows", table, count);
                            return false;
                        }
                    }
                } catch (SQLException e) {
                    // Table might not exist yet (fresh database), that's okay
                    logger.debug("Table {} does not exist or error checking: {}", table, e.getMessage());
                }
            }

            // All checked tables are empty
            logger.info("Database is empty - no data found in key tables");
            return true;

        } catch (SQLException e) {
            logger.warn("Error checking if database is empty: {}", e.getMessage());
            // If we can't check, assume it's not empty to be safe
            return false;
        }
    }

    /**
     * Safely delete from a table, returning 0 if table doesn't exist
     */
    private int safeDeleteFrom(Statement stmt, String tableName) {
        try {
            return stmt.executeUpdate("DELETE FROM " + tableName);
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("not found")) {
                logger.debug("Table {} not found, skipping delete", tableName);
                return 0;
            }
            logger.warn("Error deleting from {}: {}", tableName, e.getMessage());
            return 0;
        }
    }

    /**
     * Clear all data for a specific store ID from pos_users table.
     * This is called during sync if store ID has changed to remove old store's
     * users.
     */
    public void clearUsersForStore(String storeId) {
        if (storeId == null || storeId.isEmpty()) {
            return;
        }

        logger.info("Clearing POS users for store: {}", storeId);

        try (Connection conn = getConnection()) {
            String sql = "DELETE FROM pos_users WHERE store_id = ?";
            try (java.sql.PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, storeId);
                int deleted = stmt.executeUpdate();
                conn.commit();
                logger.info("Deleted {} POS users for store {}", deleted, storeId);
            }
        } catch (SQLException e) {
            logger.error("Error clearing users for store {}", storeId, e);
        }
    }

    /**
     * Clear all POS users not belonging to the specified store.
     * This ensures only users for the current store remain after sync.
     */
    public void clearUsersNotForStore(String currentStoreId) {
        if (currentStoreId == null || currentStoreId.isEmpty()) {
            return;
        }

        logger.info("Clearing POS users NOT for store: {}", currentStoreId);

        try (Connection conn = getConnection()) {
            String sql = "DELETE FROM pos_users WHERE store_id != ? OR store_id IS NULL";
            try (java.sql.PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, currentStoreId);
                int deleted = stmt.executeUpdate();
                conn.commit();
                if (deleted > 0) {
                    logger.info("Deleted {} POS users from other stores", deleted);
                }
            }
        } catch (SQLException e) {
            logger.error("Error clearing users not for store {}", currentStoreId, e);
        }
    }

    /**
     * Delete the entire database including all tables and data files.
     * This performs a complete reset of the local database.
     * 
     * This method uses a direct file deletion approach that works even when
     * the database is locked or the application is running.
     * 
     * Steps:
     * 1. Stop H2 web console server (if running)
     * 2. Extract database file path
     * 3. Delete all database files directly (most reliable method)
     * 
     * @throws RuntimeException if database deletion fails
     */
    public void deleteDatabase() {
        logger.info("Starting complete database deletion...");

        String dbUrl = config.getProperty("database.url");
        if (dbUrl == null || dbUrl.isEmpty()) {
            logger.warn("Database URL not configured, cannot delete database");
            throw new RuntimeException("Database URL not configured");
        }

        // Extract database file path from JDBC URL
        String dbPath = extractDatabasePath(dbUrl);
        if (dbPath == null) {
            logger.error("Could not extract database path from URL: {}", dbUrl);
            throw new RuntimeException("Invalid database URL format");
        }

        logger.info("Database path: {}", dbPath);

        // Wait a moment for any connections to close
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Delete all database files directly (most reliable method)
        // We skip trying to connect and drop tables because if the app is running,
        // the database will be locked. Direct file deletion is more reliable.
        logger.info("Deleting database files directly...");
        deleteDatabaseFiles(dbPath);

        logger.info("Database deletion completed successfully");
    }

    /**
     * Extract database file path from a SQLite JDBC URL.
     * Handles formats like:
     * - jdbc:sqlite:/absolute/path/to/posdb.db
     * - jdbc:sqlite:./relative/path/to/posdb.db
     * - jdbc:sqlite:~/home/path/to/posdb.db
     */
    private String extractDatabasePath(String dbUrl) {
        if (dbUrl == null || !dbUrl.startsWith("jdbc:sqlite:")) {
            return null;
        }

        String path = dbUrl.substring("jdbc:sqlite:".length());

        // In-memory databases have no file path.
        if (path.isEmpty() || path.startsWith(":memory:") || path.contains("mode=memory")) {
            return null;
        }

        // Remove URL parameters (everything after ;)
        int paramIndex = path.indexOf(';');
        if (paramIndex >= 0) {
            path = path.substring(0, paramIndex);
        }

        // Handle relative paths
        if (path.startsWith("./") || path.startsWith(".\\")) {
            // Relative to current directory - convert to absolute
            Path currentDir = Paths.get("").toAbsolutePath();
            path = currentDir.resolve(path.substring(2)).toString();
        } else if (path.startsWith("~/")) {
            // Home directory relative
            String userHome = System.getProperty("user.home");
            path = Paths.get(userHome, path.substring(2)).toString();
        }

        return path;
    }

    /**
     * Delete all H2 database files for the given database path.
     * Deletes: .mv.db, .trace.db, .lock.db, and any temporary files
     * 
     * This method will retry deletion multiple times if files are locked.
     * Uses more aggressive deletion with longer waits and more retries.
     */
    private void deleteDatabaseFiles(String dbPath) {
        // SQLite creates these file types alongside the main database file:
        // - "" (the main database file itself, e.g. posdb.db)
        // - -wal (write-ahead log)
        // - -shm (shared-memory index)
        // - -journal (rollback journal, used outside WAL mode)
        String[] extensions = { "", "-wal", "-shm", "-journal" };
        int deletedCount = 0;
        int maxRetries = 15; // Increased retries
        int retryDelayMs = 500; // Initial delay

        logger.info("Attempting to delete database files from: {}", dbPath);

        // Close the pool first so it releases its connections and stops handing
        // out new ones; otherwise the database files stay locked.
        closePool();

        // Wait a bit for connections to close
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (String ext : extensions) {
            String filePath = dbPath + ext;
            File dbFile = new File(filePath);

            if (dbFile.exists()) {
                logger.info("Found database file: {} (size: {} bytes)", filePath, dbFile.length());
                boolean deleted = false;

                // Retry deletion multiple times in case file is temporarily locked
                for (int attempt = 1; attempt <= maxRetries; attempt++) {
                    try {
                        // Try to make file writable first (in case it's read-only)
                        if (dbFile.exists() && !dbFile.canWrite()) {
                            dbFile.setWritable(true);
                        }

                        deleted = dbFile.delete();
                        if (deleted) {
                            logger.info("✓ Deleted database file: {}", filePath);
                            deletedCount++;
                            break;
                        } else {
                            if (attempt < maxRetries) {
                                logger.debug("File {} is locked, retrying in {}ms (attempt {}/{})",
                                        filePath, retryDelayMs, attempt, maxRetries);
                                Thread.sleep(retryDelayMs);
                                // Increase delay for later attempts
                                if (attempt > 3) {
                                    retryDelayMs = 1000;
                                }
                            } else {
                                logger.error("✗ Could not delete database file after {} attempts: {}",
                                        maxRetries, filePath);
                                // Try to delete on exit as fallback
                                dbFile.deleteOnExit();
                                logger.warn("File will be deleted on JVM exit: {}", filePath);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.warn("Interrupted while retrying file deletion");
                        break;
                    } catch (Exception e) {
                        logger.warn("Error deleting database file {} (attempt {}/{}): {}",
                                filePath, attempt, maxRetries, e.getMessage());
                        if (attempt < maxRetries) {
                            try {
                                Thread.sleep(retryDelayMs);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        } else {
                            // Try to delete on exit as fallback
                            dbFile.deleteOnExit();
                            logger.warn("File will be deleted on JVM exit: {}", filePath);
                        }
                    }
                }

                // Final check - if file still exists, try one more time with force
                if (!deleted && dbFile.exists()) {
                    try {
                        // Force delete by making writable and trying again
                        dbFile.setWritable(true);
                        Thread.sleep(200);
                        deleted = dbFile.delete();
                        if (deleted) {
                            logger.info("✓ Force-deleted database file: {}", filePath);
                            deletedCount++;
                        }
                    } catch (Exception e) {
                        logger.error("Failed to force-delete file {}: {}", filePath, e.getMessage());
                    }
                }
            } else {
                logger.debug("Database file does not exist: {}", filePath);
            }
        }

        // Also check for any files matching the database name pattern in the directory
        File dbDir = new File(dbPath).getParentFile();
        if (dbDir != null && dbDir.exists()) {
            String dbFileName = new File(dbPath).getName();
            File[] matchingFiles = dbDir.listFiles((dir,
                    name) -> name.equals(dbFileName)
                            || name.startsWith(dbFileName + "-"));

            if (matchingFiles != null) {
                for (File file : matchingFiles) {
                    if (file.exists()) {
                        logger.warn("Found additional database file: {}", file.getAbsolutePath());
                        try {
                            file.setWritable(true);
                            if (file.delete()) {
                                logger.info("✓ Deleted additional file: {}", file.getName());
                                deletedCount++;
                            } else {
                                logger.warn("Could not delete additional file: {}", file.getAbsolutePath());
                            }
                        } catch (Exception e) {
                            logger.warn("Error deleting additional file {}: {}", file.getAbsolutePath(),
                                    e.getMessage());
                        }
                    }
                }
            }
        }

        // Verify all files are actually deleted
        int remainingFiles = 0;
        for (String ext : extensions) {
            File checkFile = new File(dbPath + ext);
            if (checkFile.exists()) {
                remainingFiles++;
                logger.error("File still exists after deletion attempt: {}", checkFile.getAbsolutePath());
                // Try one final aggressive delete
                try {
                    checkFile.setWritable(true);
                    System.gc(); // Force garbage collection to release file handles
                    Thread.sleep(500);
                    if (checkFile.delete()) {
                        logger.info("✓ Final delete successful for: {}", checkFile.getName());
                        remainingFiles--;
                        deletedCount++;
                    }
                } catch (Exception e) {
                    logger.error("Final delete attempt failed for {}: {}", checkFile.getAbsolutePath(), e.getMessage());
                }
            }
        }

        if (deletedCount == 0 && remainingFiles > 0) {
            logger.error("No database files were deleted. {} file(s) still exist.", remainingFiles);
            logger.error("Database path: {}", dbPath);
            logger.error("If files are locked:");
            logger.error("  1. Close all POS application instances");
            logger.error("  2. Wait a few seconds");
            logger.error("  3. Try again or restart the application");
            logger.error("  4. Or manually delete: rm -rf \"{}\"*", dbPath);
            throw new RuntimeException(
                    "Failed to delete database files. " + remainingFiles + " file(s) are still locked.");
        } else if (remainingFiles > 0) {
            logger.warn("Only {}/{} database files were deleted. {} file(s) may still be locked.",
                    deletedCount, extensions.length, remainingFiles);
            logger.warn("You may need to close the application and try again.");
            logger.warn("Or manually delete remaining files: rm -rf \"{}\"*", dbPath);
        } else {
            logger.info("✓ Successfully deleted all {} database file(s)", deletedCount);
        }
    }

}
