package com.pos.service;

import com.pos.config.ConfigManager;
import com.pos.database.DatabaseManager;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.io.IOException;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class BackupRecoveryTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private Path base;
    private String url;
    private Map<String,String> values;

    private void set(Object object, String name, Object value) throws Exception {
        var field = BackupService.class.getDeclaredField(name);
        field.setAccessible(true); field.set(object, value);
    }

    private BackupService service() throws Exception {
        base = folder.getRoot().toPath().resolve("pos");
        url = "jdbc:h2:file:" + base.toString().replace('\\','/');
        try (Connection c = DriverManager.getConnection(url,"sa",""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE sales(id INT PRIMARY KEY, total INT)");
            s.execute("INSERT INTO sales VALUES(1,10)");
        }
        values = new HashMap<>(Map.of("database.url",url,"database.user","sa","database.password",""));
        ConfigManager config = mock(ConfigManager.class);
        when(config.getProperty(anyString())).thenAnswer(c -> values.get(c.getArgument(0)));
        when(config.getProperty(anyString(),anyString())).thenAnswer(c ->
                values.getOrDefault(c.getArgument(0),c.getArgument(1)));
        when(config.getIntProperty(anyString(),anyInt())).thenAnswer(c -> c.getArgument(1));
        doAnswer(c -> {values.put(c.getArgument(0),c.getArgument(1)); return null;})
                .when(config).setProperty(anyString(),anyString());
        DatabaseManager database = mock(DatabaseManager.class);
        when(database.getConnection()).thenAnswer(c -> DriverManager.getConnection(url,"sa",""));
        BackupService service = mock(BackupService.class, CALLS_REAL_METHODS);
        set(service,"config",config);
        set(service,"databaseManager",database);
        set(service,"backupLock",new Object());
        return service;
    }

    @Test public void verifiesCreatedBackupAndRestoresWhilePreservingOriginalFiles() throws Exception {
        BackupService service = service();
        var backup = service.createManualBackup();
        assertNotNull(values.get("backup.lastVerifiedAt"));
        service.queueRestoreOnNextStartup(backup.path);
        assertTrue(service.applyPendingRestoreIfNeeded());
        try (var paths = Files.list(folder.getRoot().toPath())) {
            assertTrue(paths.anyMatch(p -> p.getFileName().toString().startsWith("before-restore-")
                    && Files.exists(p.resolve("pos.mv.db"))));
        }
        assertEquals(1, count());
        assertNull(service.getPendingRestoreFile());
    }

    @Test public void saleAfterQueueBlocksRestoreAtStartup() throws Exception {
        BackupService service = service();
        var backup = service.createManualBackup();
        service.queueRestoreOnNextStartup(backup.path);
        try (Connection c = DriverManager.getConnection(url,"sa",""); Statement s = c.createStatement()) {
            s.execute("INSERT INTO sales VALUES(2,25)");
        }
        assertThrows(IOException.class, service::applyPendingRestoreIfNeeded);
        assertEquals(2,count());
        assertNull(service.getPendingRestoreFile());
        assertTrue(values.get("backup.restore.lastResult").contains("protect current data"));
    }

    @Test public void olderBackupCannotBeQueuedOverNewerSales() throws Exception {
        BackupService service = service();
        var backup = service.createManualBackup();
        try (Connection c = DriverManager.getConnection(url,"sa",""); Statement s = c.createStatement()) {
            s.execute("INSERT INTO sales VALUES(2,25)");
        }
        assertThrows(IOException.class, () -> service.queueRestoreOnNextStartup(backup.path));
        assertEquals(2,count());
        assertNull(service.getPendingRestoreFile());
    }

    private int count() throws Exception {
        try (Connection c = DriverManager.getConnection(url,"sa",""); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM sales")) {
            rs.next(); return rs.getInt(1);
        }
    }
}
