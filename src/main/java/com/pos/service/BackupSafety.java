package com.pos.service;

import org.h2.tools.Restore;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;

/** Reads backups in isolation. Never connects to the running POS database. */
final class BackupSafety {
    private BackupSafety() {}

    static Map<String, Map<String, Integer>> inspectArchive(Path archive, String user, String password)
            throws IOException {
        Path temporary = Files.createTempDirectory("pasal-backup-check-");
        try {
            // Reject paths outside the extraction directory before invoking H2.
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName().replace('\\', '/');
                    if (name.contains(":") || !temporary.resolve(name).normalize().startsWith(temporary)) {
                        throw new IOException("Unsafe path in backup archive.");
                    }
                }
            }
            Restore.execute(archive.toString(), temporary.toString(), "verified");
            return inspectDatabase(temporary.resolve("verified"), user, password);
        } catch (Exception e) {
            throw new IOException("Backup is not readable. Current data has not been replaced.", e);
        } finally {
            try (var files = Files.walk(temporary)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    static Map<String, Map<String, Integer>> inspectDatabase(Path base, String user, String password)
            throws Exception {
        String url = "jdbc:h2:file:" + base.toAbsolutePath().toString().replace('\\', '/')
                + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r";
        Map<String, Map<String, Integer>> tables = new TreeMap<>();
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            List<String> names = new ArrayList<>();
            try (ResultSet rs = connection.getMetaData().getTables(null, "PUBLIC", "%",
                    new String[] {"BASE TABLE", "TABLE"})) {
                while (rs.next()) names.add(rs.getString("TABLE_NAME"));
            }
            if (!names.contains("SALES")) throw new IOException("Backup does not contain a POS sales database.");
            for (String name : names) {
                List<String> columns = new ArrayList<>();
                try (ResultSet rs = connection.getMetaData().getColumns(null, "PUBLIC", name, "%")) {
                    while (rs.next()) columns.add(rs.getString("COLUMN_NAME"));
                }
                Collections.sort(columns);
                String selected = columns.stream().map(BackupSafety::quote)
                        .collect(java.util.stream.Collectors.joining(","));
                Map<String, Integer> rows = new HashMap<>();
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery("SELECT " + selected + " FROM PUBLIC." + quote(name))) {
                    while (rs.next()) {
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        for (int i = 0; i < columns.size(); i++) {
                            add(digest, columns.get(i));
                            add(digest, rs.getString(i + 1));
                        }
                        rows.merge(HexFormat.of().formatHex(digest.digest()), 1, Integer::sum);
                    }
                }
                tables.put(name, rows);
            }
        }
        return tables;
    }

    private static String quote(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }

    private static void add(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
        } else {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update((byte) 1);
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
            digest.update(bytes);
        }
    }

    static void requireNoLostRecords(Map<String, Map<String, Integer>> current,
            Map<String, Map<String, Integer>> backup) throws IOException {
        for (var table : current.entrySet()) {
            Map<String, Integer> saved = backup.getOrDefault(table.getKey(), Map.of());
            for (var row : table.getValue().entrySet()) {
                if (saved.getOrDefault(row.getKey(), 0) < row.getValue()) {
                    throw new IOException("Restore blocked: the backup is missing current or changed records in "
                            + table.getKey() + ". Your current data is unchanged. Create a fresh backup, "
                            + "then contact support to recover missing records without replacing newer data.");
                }
            }
        }
    }
}
