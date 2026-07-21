package com.pos.service;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import org.h2.tools.Restore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages scheduled and manual backups for the local H2 database.
 *
 * <p>Backups use H2's hot BACKUP command, which produces a zip archive without
 * requiring the application to stop. Restores are queued and applied on next
 * startup before the database is opened, which avoids fighting active H2
 * connections inside the running application.
 */
public class BackupService {

    private static final Logger logger = LoggerFactory.getLogger(BackupService.class);

    private static final String PENDING_RESTORE_FILE_KEY = "backup.restore.pendingFile";
    private static final String PENDING_RESTORE_REQUESTED_AT_KEY = "backup.restore.pendingRequestedAt";

    private static final DateTimeFormatter FILE_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DISPLAY_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern BACKUP_FILE_PATTERN = Pattern.compile(
            "^pasal-pos-backup-(scheduled|manual|pre-update|pre-reset|pre-restore)-(\\d{8}-\\d{6})\\.zip$");

    private static BackupService instance;

    private final ConfigManager config;
    private final DatabaseManager databaseManager;
    private final Object backupLock = new Object();

    private ScheduledExecutorService scheduler;

    private BackupService() {
        this.config = ConfigManager.getInstance();
        this.databaseManager = DatabaseManager.getInstance();
    }

    public static synchronized BackupService getInstance() {
        if (instance == null) {
            instance = new BackupService();
        }
        return instance;
    }

    /**
     * Start the scheduled backup worker if backups are enabled and the database is a
     * local H2 file.
     */
    public void initialize() {
        synchronized (backupLock) {
            if (scheduler != null || !isAutomaticBackupEnabled() || !isBackupSupported()) {
                return;
            }

            pruneBackups();

            ThreadFactory threadFactory = runnable -> {
                Thread thread = new Thread(runnable, "DatabaseBackupScheduler");
                thread.setDaemon(true);
                return thread;
            };

            scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory);
            long intervalMinutes = getBackupIntervalMinutes();
            scheduler.scheduleWithFixedDelay(
                    this::runScheduledBackupSafely,
                    intervalMinutes,
                    intervalMinutes,
                    TimeUnit.MINUTES);

            logger.info("Database backup scheduler started. Interval: {} minutes. Directory: {}",
                    intervalMinutes, getBackupDirectory());
        }
    }

    /**
     * Stop the scheduled backup worker.
     */
    public void shutdown() {
        synchronized (backupLock) {
            if (scheduler == null) {
                return;
            }

            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            } finally {
                scheduler = null;
            }
        }
    }

    public boolean isBackupSupported() {
        try {
            String dbUrl = config.getProperty("database.url", "");
            if (!dbUrl.startsWith("jdbc:h2:")) {
                return false;
            }

            String pathPart = dbUrl.substring("jdbc:h2:".length()).toLowerCase(Locale.ROOT);
            return !pathPart.startsWith("mem:");
        } catch (Exception e) {
            logger.debug("Failed to determine backup support", e);
            return false;
        }
    }

    public boolean isAutomaticBackupEnabled() {
        return config.getBooleanProperty("backup.enabled", true);
    }

    public long getBackupIntervalMinutes() {
        return Math.max(1, config.getIntProperty("backup.intervalMinutes", 15));
    }

    public Path getBackupDirectory() {
        try {
            return resolveBackupDirectory();
        } catch (Exception e) {
            logger.warn("Failed to resolve backup directory: {}", e.getMessage());
            return null;
        }
    }

    public BackupInfo createManualBackup() throws IOException {
        return createBackup(BackupTrigger.MANUAL);
    }

    public BackupInfo createPreResetBackup() throws IOException {
        return createBackup(BackupTrigger.PRE_RESET);
    }

    public BackupInfo createPreRestoreSnapshot() throws IOException {
        return createBackup(BackupTrigger.PRE_RESTORE);
    }

    public BackupInfo getLatestBackup() {
        try {
            return listBackups().stream()
                    .max(Comparator.comparing(info -> info.createdAt))
                    .orElse(null);
        } catch (Exception e) {
            logger.warn("Failed to inspect latest backup: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Restore the local database from the newest available backup.
     * Intended for automatic recovery when H2 reports file corruption.
     */
    public RecoveryResult attemptRecoveryFromLatestBackup() {
        if (!isBackupSupported()) {
            return RecoveryResult.failed(
                    "Automatic recovery is not available for this database configuration.");
        }

        BackupInfo latestBackup = getLatestBackup();
        if (latestBackup == null) {
            return RecoveryResult.noBackupAvailable(getBackupDirectory());
        }

        try {
            logger.warn("Attempting automatic database recovery from {}", latestBackup.path);
            databaseManager.closePool();
            applyRestore(latestBackup.path);
            logger.info("Automatic database recovery completed from {}", latestBackup.path);
            return RecoveryResult.success(latestBackup);
        } catch (Exception e) {
            logger.error("Automatic database recovery failed", e);
            return RecoveryResult.failed("Failed to restore from backup: " + e.getMessage());
        }
    }

    public Path getPendingRestoreFile() {
        String pendingRestore = config.getProperty(PENDING_RESTORE_FILE_KEY, "").trim();
        if (pendingRestore.isEmpty()) {
            return null;
        }
        return Paths.get(pendingRestore).toAbsolutePath().normalize();
    }

    /**
     * Create a safety snapshot and queue a restore to be applied on next startup.
     */
    public BackupInfo queueRestoreOnNextStartup(Path backupFile) throws IOException {
        if (!isBackupSupported()) {
            throw new IOException("Restore is only supported for local H2 file databases.");
        }

        Path normalizedBackup = backupFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedBackup)) {
            throw new IOException("Backup archive not found: " + normalizedBackup);
        }

        validateRestoreArchive(normalizedBackup);

        BackupInfo safetySnapshot = createPreRestoreSnapshot();
        config.setProperty(PENDING_RESTORE_FILE_KEY, normalizedBackup.toString());
        config.setProperty(PENDING_RESTORE_REQUESTED_AT_KEY, Instant.now().toString());

        logger.info("Queued database restore from {}. Safety snapshot created at {}",
                normalizedBackup, safetySnapshot.path);
        return safetySnapshot;
    }

    /**
     * Apply a queued restore before normal database startup.
     *
     * @return true if a restore was applied, false otherwise.
     */
    public boolean applyPendingRestoreIfNeeded() throws IOException {
        Path pendingRestore = getPendingRestoreFile();
        if (pendingRestore == null) {
            return false;
        }

        try {
            applyRestore(pendingRestore);
            logger.info("Pending database restore applied successfully from {}", pendingRestore);
            return true;
        } finally {
            clearPendingRestore();
        }
    }

    public String getStatusSummary() {
        if (!isBackupSupported()) {
            return "Backups are available only for the local H2 file database.";
        }

        Path backupDirectory = getBackupDirectory();
        StringBuilder summary = new StringBuilder();
        summary.append("Automatic backups: ")
                .append(isAutomaticBackupEnabled() ? "Enabled" : "Disabled");

        if (isAutomaticBackupEnabled()) {
            summary.append(" (every ").append(getBackupIntervalMinutes()).append(" minutes)");
        }

        summary.append("\nDirectory: ")
                .append(backupDirectory != null ? backupDirectory : "Unavailable");

        BackupInfo latest = getLatestBackup();
        summary.append("\nLatest backup: ");
        if (latest != null) {
            summary.append(formatInstant(latest.createdAt))
                    .append(" (")
                    .append(latest.trigger)
                    .append(", ")
                    .append(formatSize(latest.sizeBytes))
                    .append(")");
        } else {
            summary.append("None yet");
        }

        Path pendingRestore = getPendingRestoreFile();
        summary.append("\nPending restore: ")
                .append(pendingRestore != null ? pendingRestore.getFileName() + " on next launch" : "None");

        summary.append("\nRetention: ")
                .append(getHourlyRetentionHours()).append(" hourly / ")
                .append(getDailyRetentionDays()).append(" daily / ")
                .append(getMonthlyRetentionMonths()).append(" monthly");

        return summary.toString();
    }

    private void runScheduledBackupSafely() {
        try {
            BackupInfo info = createBackup(BackupTrigger.SCHEDULED);
            logger.info("Scheduled database backup completed: {}", info.path);
        } catch (Exception e) {
            logger.error("Scheduled database backup failed", e);
        }
    }

    private BackupInfo createBackup(BackupTrigger trigger) throws IOException {
        if (!isBackupSupported()) {
            throw new IOException("Backups are only supported for local H2 file databases.");
        }

        synchronized (backupLock) {
            Path backupDirectory = resolveBackupDirectory();
            Files.createDirectories(backupDirectory);

            Instant createdAt = Instant.now();
            String fileName = "pasal-pos-backup-" + trigger.slug + "-"
                    + FILE_TIMESTAMP_FORMAT.format(LocalDateTime.ofInstant(createdAt, ZoneId.systemDefault()))
                    + ".zip";
            Path backupPath = backupDirectory.resolve(fileName);

            String backupSqlPath = backupPath.toAbsolutePath().toString()
                    .replace("\\", "/")
                    .replace("'", "''");

            logger.info("Creating {} database backup at {}", trigger.displayName, backupPath);

            try (Connection connection = databaseManager.getConnection();
                 Statement statement = connection.createStatement()) {
                connection.setAutoCommit(true);
                statement.execute("BACKUP TO '" + backupSqlPath + "'");
            } catch (Exception e) {
                throw new IOException("Failed to create database backup: " + e.getMessage(), e);
            }

            BackupInfo backupInfo = buildBackupInfo(backupPath, trigger, createdAt);
            pruneBackups();
            return backupInfo;
        }
    }

    private void applyRestore(Path backupFile) throws IOException {
        if (!Files.isRegularFile(backupFile)) {
            throw new IOException("Restore backup does not exist: " + backupFile);
        }

        synchronized (backupLock) {
            Path databaseBasePath = resolveDatabaseBasePath();
            Path databaseDirectory = databaseBasePath.getParent();
            String databaseName = databaseBasePath.getFileName().toString();

            Path tempDirectory = Files.createTempDirectory("pasal-pos-restore-");
            try {
                Restore.execute(backupFile.toAbsolutePath().toString(), tempDirectory.toAbsolutePath().toString(),
                        databaseName);

                List<Path> restoredFiles = listRestoredDatabaseFiles(tempDirectory, databaseName);
                if (restoredFiles.isEmpty()) {
                    throw new IOException("Restore archive did not contain database files: " + backupFile);
                }

                databaseManager.closePool();
                databaseManager.deleteDatabase();
                if (databaseDirectory != null) {
                    Files.createDirectories(databaseDirectory);
                }

                for (Path restoredFile : restoredFiles) {
                    Path target = (databaseDirectory != null ? databaseDirectory : Paths.get("."))
                            .resolve(restoredFile.getFileName().toString());
                    Files.move(restoredFile, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                throw new IOException("Failed to restore database from " + backupFile + ": " + e.getMessage(), e);
            } finally {
                deleteRecursively(tempDirectory);
            }
        }
    }

    private void validateRestoreArchive(Path backupFile) throws IOException {
        Path tempDirectory = Files.createTempDirectory("pasal-pos-restore-validate-");
        String tempDbName = "validation_" + UUID.randomUUID().toString().replace("-", "");

        try {
            Restore.execute(backupFile.toAbsolutePath().toString(), tempDirectory.toAbsolutePath().toString(),
                    tempDbName);

            List<Path> restoredFiles = listRestoredDatabaseFiles(tempDirectory, tempDbName);
            if (restoredFiles.isEmpty()) {
                throw new IOException("Selected file is not a valid H2 backup archive.");
            }
        } catch (Exception e) {
            throw new IOException("Failed to validate restore archive: " + e.getMessage(), e);
        } finally {
            deleteRecursively(tempDirectory);
        }
    }

    private List<Path> listRestoredDatabaseFiles(Path directory, String databaseName) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(databaseName + "."))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private void clearPendingRestore() {
        config.setProperty(PENDING_RESTORE_FILE_KEY, "");
        config.setProperty(PENDING_RESTORE_REQUESTED_AT_KEY, "");
    }

    private Path resolveBackupDirectory() {
        Path databaseBasePath = resolveDatabaseBasePath();
        Path databaseDirectory = databaseBasePath.getParent() != null
                ? databaseBasePath.getParent()
                : Paths.get(".").toAbsolutePath().normalize();

        String configuredDirectory = config.getProperty("backup.directory", "").trim();
        if (configuredDirectory.isEmpty()) {
            return databaseDirectory.resolve("backups").toAbsolutePath().normalize();
        }

        Path configuredPath = Paths.get(configuredDirectory);
        if (!configuredPath.isAbsolute()) {
            configuredPath = databaseDirectory.resolve(configuredPath);
        }
        return configuredPath.toAbsolutePath().normalize();
    }

    private Path resolveDatabaseBasePath() {
        String dbUrl = config.getProperty("database.url", "");
        if (!dbUrl.startsWith("jdbc:h2:")) {
            throw new IllegalStateException("Database URL is not an H2 URL: " + dbUrl);
        }

        String rawPath = dbUrl.substring("jdbc:h2:".length());
        if (rawPath.startsWith("file:")) {
            rawPath = rawPath.substring("file:".length());
        }
        if (rawPath.startsWith("mem:")) {
            throw new IllegalStateException("In-memory H2 databases do not support file backups.");
        }

        int optionsIndex = rawPath.indexOf(';');
        if (optionsIndex >= 0) {
            rawPath = rawPath.substring(0, optionsIndex);
        }

        if (rawPath.startsWith("~/")) {
            rawPath = Paths.get(System.getProperty("user.home"), rawPath.substring(2)).toString();
        }

        Path databasePath = Paths.get(rawPath);
        if (!databasePath.isAbsolute()) {
            databasePath = Paths.get("").toAbsolutePath().resolve(databasePath);
        }

        return databasePath.normalize();
    }

    private List<BackupInfo> listBackups() throws IOException {
        Path backupDirectory = resolveBackupDirectory();
        if (!Files.isDirectory(backupDirectory)) {
            return List.of();
        }

        try (Stream<Path> files = Files.list(backupDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(this::parseBackupInfo)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing((BackupInfo info) -> info.createdAt).reversed())
                    .collect(Collectors.toList());
        }
    }

    private BackupInfo parseBackupInfo(Path backupPath) {
        Matcher matcher = BACKUP_FILE_PATTERN.matcher(backupPath.getFileName().toString());
        if (!matcher.matches()) {
            return null;
        }

        try {
            BackupTrigger trigger = BackupTrigger.fromSlug(matcher.group(1));
            LocalDateTime createdAt = LocalDateTime.parse(matcher.group(2), FILE_TIMESTAMP_FORMAT);
            long sizeBytes = Files.size(backupPath);
            return buildBackupInfo(backupPath, trigger, createdAt.atZone(ZoneId.systemDefault()).toInstant(), sizeBytes);
        } catch (Exception e) {
            logger.debug("Skipping unparseable backup file {}", backupPath, e);
            return null;
        }
    }

    private BackupInfo buildBackupInfo(Path backupPath, BackupTrigger trigger, Instant createdAt) throws IOException {
        return buildBackupInfo(backupPath, trigger, createdAt, Files.size(backupPath));
    }

    private BackupInfo buildBackupInfo(Path backupPath, BackupTrigger trigger, Instant createdAt, long sizeBytes) {
        return new BackupInfo(backupPath.toAbsolutePath().normalize(), trigger.displayName, createdAt, sizeBytes);
    }

    private void pruneBackups() {
        try {
            List<BackupInfo> backups = listBackups();
            pruneScheduledBackups(backups);
            pruneSnapshotBackups(backups);
        } catch (Exception e) {
            logger.warn("Failed to prune old backups: {}", e.getMessage());
        }
    }

    private void pruneScheduledBackups(List<BackupInfo> backups) {
        List<BackupInfo> scheduledBackups = backups.stream()
                .filter(info -> BackupTrigger.SCHEDULED.displayName.equals(info.trigger))
                .sorted(Comparator.comparing((BackupInfo info) -> info.createdAt).reversed())
                .collect(Collectors.toList());

        if (scheduledBackups.isEmpty()) {
            return;
        }

        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime hourlyCutoff = now.minusHours(getHourlyRetentionHours());
        ZonedDateTime dailyCutoff = now.minusDays(getDailyRetentionDays());
        ZonedDateTime monthlyCutoff = now.minusMonths(getMonthlyRetentionMonths());

        Set<Path> keep = new HashSet<>();
        Map<LocalDate, BackupInfo> dailyKeep = new HashMap<>();
        Map<YearMonth, BackupInfo> monthlyKeep = new HashMap<>();

        for (BackupInfo backup : scheduledBackups) {
            ZonedDateTime createdAt = backup.createdAt.atZone(ZoneId.systemDefault());

            if (createdAt.isAfter(hourlyCutoff)) {
                keep.add(backup.path);
                continue;
            }

            if (createdAt.isAfter(dailyCutoff)) {
                dailyKeep.putIfAbsent(createdAt.toLocalDate(), backup);
                continue;
            }

            if (createdAt.isAfter(monthlyCutoff)) {
                monthlyKeep.putIfAbsent(YearMonth.from(createdAt), backup);
            }
        }

        keep.addAll(dailyKeep.values().stream().map(info -> info.path).collect(Collectors.toSet()));
        keep.addAll(monthlyKeep.values().stream().map(info -> info.path).collect(Collectors.toSet()));

        for (BackupInfo backup : scheduledBackups) {
            if (!keep.contains(backup.path)) {
                deleteQuietly(backup.path);
            }
        }
    }

    private void pruneSnapshotBackups(List<BackupInfo> backups) {
        List<BackupInfo> snapshotBackups = backups.stream()
                .filter(info -> BackupTrigger.isSnapshot(info.trigger))
                .sorted(Comparator.comparing((BackupInfo info) -> info.createdAt).reversed())
                .collect(Collectors.toList());

        int keepCount = Math.max(1, config.getIntProperty("backup.retention.snapshotCount", 20));
        for (int index = keepCount; index < snapshotBackups.size(); index++) {
            deleteQuietly(snapshotBackups.get(index).path);
        }
    }

    private int getHourlyRetentionHours() {
        return Math.max(0, config.getIntProperty("backup.retention.hourly", 24));
    }

    private int getDailyRetentionDays() {
        return Math.max(0, config.getIntProperty("backup.retention.daily", 30));
    }

    private int getMonthlyRetentionMonths() {
        return Math.max(0, config.getIntProperty("backup.retention.monthly", 12));
    }

    private void deleteQuietly(Path backupPath) {
        try {
            Files.deleteIfExists(backupPath);
            logger.info("Deleted old backup {}", backupPath);
        } catch (IOException e) {
            logger.warn("Could not delete old backup {}: {}", backupPath, e.getMessage());
        }
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }

        try (Stream<Path> walk = Files.walk(path)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toCollection(ArrayList::new));
            for (Path current : paths) {
                Files.deleteIfExists(current);
            }
        } catch (IOException e) {
            logger.debug("Failed to clean temporary path {}: {}", path, e.getMessage());
        }
    }

    private String formatInstant(Instant instant) {
        return DISPLAY_TIMESTAMP_FORMAT.format(ZonedDateTime.ofInstant(instant, ZoneId.systemDefault()));
    }

    private String formatSize(long sizeBytes) {
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        }
        if (sizeBytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", sizeBytes / 1024.0);
        }
        if (sizeBytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.1f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0));
    }

    private enum BackupTrigger {
        SCHEDULED("scheduled", "scheduled"),
        MANUAL("manual", "manual"),
        PRE_UPDATE("pre-update", "pre-update"),
        PRE_RESET("pre-reset", "pre-reset"),
        PRE_RESTORE("pre-restore", "pre-restore");

        private final String slug;
        private final String displayName;

        BackupTrigger(String slug, String displayName) {
            this.slug = slug;
            this.displayName = displayName;
        }

        private static BackupTrigger fromSlug(String slug) {
            for (BackupTrigger trigger : values()) {
                if (trigger.slug.equals(slug)) {
                    return trigger;
                }
            }
            throw new IllegalArgumentException("Unknown backup trigger: " + slug);
        }

        private static boolean isSnapshot(String displayName) {
            return PRE_UPDATE.displayName.equals(displayName)
                    || PRE_RESET.displayName.equals(displayName)
                    || PRE_RESTORE.displayName.equals(displayName);
        }
    }

    public static class BackupInfo {
        public final Path path;
        public final String trigger;
        public final Instant createdAt;
        public final long sizeBytes;

        public BackupInfo(Path path, String trigger, Instant createdAt, long sizeBytes) {
            this.path = path;
            this.trigger = trigger;
            this.createdAt = createdAt;
            this.sizeBytes = sizeBytes;
        }
    }

    public static class RecoveryResult {
        public enum Status {
            SUCCESS,
            NO_BACKUP,
            FAILED
        }

        public final Status status;
        public final String message;
        public final BackupInfo backup;

        private RecoveryResult(Status status, String message, BackupInfo backup) {
            this.status = status;
            this.message = message;
            this.backup = backup;
        }

        public static RecoveryResult success(BackupInfo backup) {
            return new RecoveryResult(Status.SUCCESS, null, backup);
        }

        public static RecoveryResult noBackupAvailable(Path backupDirectory) {
            String directory = backupDirectory != null ? backupDirectory.toString() : "the backups folder";
            return new RecoveryResult(Status.NO_BACKUP,
                    "The local database file is corrupted and no automatic backup was found.\n\n"
                            + "Backup folder:\n" + directory + "\n\n"
                            + "To recover manually:\n"
                            + "1. Close Pasal POS 2 completely\n"
                            + "2. Copy a backup .zip file into the backup folder\n"
                            + "3. Use Settings > Restore Backup, or edit config.properties to queue a restore\n"
                            + "4. Restart the application",
                    null);
        }

        public static RecoveryResult failed(String message) {
            return new RecoveryResult(Status.FAILED, message, null);
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }

        public String getUserMessage() {
            if (status == Status.SUCCESS && backup != null) {
                return "Restored from backup created on "
                        + DISPLAY_TIMESTAMP_FORMAT.format(
                                ZonedDateTime.ofInstant(backup.createdAt, ZoneId.systemDefault()))
                        + " (" + backup.trigger + ").\n\n"
                        + "Backup file:\n" + backup.path + "\n\n"
                        + "Please verify your recent sales and sync status.";
            }
            return message != null ? message : "Database recovery failed.";
        }
    }
}
