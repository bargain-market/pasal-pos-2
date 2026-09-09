package com.pos.service;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.sql.*;
import java.io.IOException;
import java.util.zip.*;
import static org.junit.Assert.*;

public class BackupSafetyTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private Connection open(Path base) throws Exception {
        return DriverManager.getConnection("jdbc:h2:file:" + base.toString().replace('\\', '/'), "sa", "");
    }
    private Path backup(Path base, String name) throws Exception {
        Path archive = folder.getRoot().toPath().resolve(name + ".zip");
        try (Connection c = open(base); Statement s = c.createStatement()) {
            s.execute("BACKUP TO '" + archive.toString().replace('\\', '/').replace("'", "''") + "'");
        }
        return archive;
    }
    private Path database() throws Exception {
        Path base = folder.getRoot().toPath().resolve("pos");
        try (Connection c = open(base); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE sales(id VARCHAR PRIMARY KEY, total DECIMAL(10,2))");
            s.execute("CREATE TABLE sale_items(id INT, sale_id VARCHAR, quantity INT)");
            s.execute("INSERT INTO sales VALUES ('sale-1',10)");
            s.execute("INSERT INTO sale_items VALUES (1,'sale-1',1)");
        }
        return base;
    }

    @Test public void readsRealBackupAndRejectsNewerSalesWithoutChangingDatabase() throws Exception {
        Path base = database();
        Path archive = backup(base, "old");
        var saved = BackupSafety.inspectArchive(archive, "sa", "");
        BackupSafety.requireNoLostRecords(BackupSafety.inspectDatabase(base, "sa", ""), saved);
        try (Connection c = open(base); Statement s = c.createStatement()) {
            s.execute("INSERT INTO sales VALUES ('sale-2',25)");
        }
        assertThrows(IOException.class, () -> BackupSafety.requireNoLostRecords(
                BackupSafety.inspectDatabase(base, "sa", ""), saved));
        try (Connection c = open(base); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM sales")) {
            rs.next(); assertEquals(2, rs.getInt(1));
        }
    }

    @Test public void rejectsChangedSaleItemsEvenWhenSaleCountMatches() throws Exception {
        Path base = database();
        var saved = BackupSafety.inspectArchive(backup(base, "old"), "sa", "");
        try (Connection c = open(base); Statement s = c.createStatement()) {
            s.execute("UPDATE sale_items SET quantity=2");
        }
        assertThrows(IOException.class, () -> BackupSafety.requireNoLostRecords(
                BackupSafety.inspectDatabase(base, "sa", ""), saved));
    }

    @Test public void rejectsZipWithUnreadableDatabase() throws Exception {
        Path archive = folder.getRoot().toPath().resolve("broken.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("pos.mv.db"));
            zip.write(new byte[] {1,2,3,4});
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> BackupSafety.inspectArchive(archive, "sa", ""));
    }

    @Test public void rejectsUnsafeArchivePaths() throws Exception {
        Path archive = folder.getRoot().toPath().resolve("unsafe.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../outside.mv.db"));
            zip.write(1); zip.closeEntry();
        }
        assertThrows(IOException.class, () -> BackupSafety.inspectArchive(archive, "sa", ""));
    }
}
