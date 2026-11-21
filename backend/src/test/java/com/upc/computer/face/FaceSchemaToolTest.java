package com.upc.computer.face;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FaceSchemaToolTest {
    private String script() throws Exception {
        try (var stream = FaceSchemaTool.class.getResourceAsStream("/db/face-auth-v1.sql")) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void actualMigrationCreatesAllTablesAndCanBeRepeatedWithoutChangingBusinessData() throws Exception {
        var commands = FaceSchemaTool.migrationStatements(script());
        assertEquals(3, commands.size());
        assertTrue(commands.get(1).contains("retries; no photos or vectors"));
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE business_sentinel (id BIGINT PRIMARY KEY, value_text VARCHAR(30))");
                statement.execute("INSERT INTO business_sentinel VALUES (1, 'keep-existing-data')");
                assertEquals(3, FaceSchemaTool.apply(connection, commands));
                statement.execute("INSERT INTO mes_face_audit (event_type, result_code, created_at) VALUES ('TEST', 'KEEP', CURRENT_TIMESTAMP)");
                assertEquals(3, FaceSchemaTool.apply(connection, commands));
                for (String table : new String[]{"mes_face_binding", "mes_face_cleanup", "mes_face_audit"}) {
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        assertTrue(rows.next());
                        assertEquals(table.equals("mes_face_audit") ? 1 : 0, rows.getInt(1));
                    }
                }
                try (var rows = statement.executeQuery("SELECT value_text FROM business_sentinel WHERE id=1")) {
                    assertTrue(rows.next());
                    assertEquals("keep-existing-data", rows.getString(1));
                }
            }
        }
    }

    @Test
    void delimitersInQuotesAndCommentsArePreservedOrSkipped() {
        var commands = FaceSchemaTool.splitStatements("\uFEFF-- comment ;\r\nSELECT 'it''s; fine', \"a; b\", `semi;colon`, 'escaped\\';quote'; /* ignored ; */ # ignored ;\nSELECT 2;");
        assertEquals(2, commands.size());
        assertTrue(commands.get(0).contains("'it''s; fine'"));
        assertTrue(commands.get(0).contains("'escaped\\';quote'"));
        assertEquals("SELECT 2", commands.get(1));
    }

    @Test
    void invalidOrOutOfScopeScriptsAreRejectedBeforeAnyExecution() throws Exception {
        String valid = script();
        assertThrows(IllegalArgumentException.class, () -> FaceSchemaTool.migrationStatements(valid + "DROP TABLE user;"));
        assertThrows(IllegalArgumentException.class, () -> FaceSchemaTool.migrationStatements(valid.replace("mes_face_audit", "mes_face_extra")));
        assertThrows(IllegalArgumentException.class, () -> FaceSchemaTool.migrationStatements(valid + "SELECT 'unfinished"));
        assertThrows(IllegalArgumentException.class, () -> FaceSchemaTool.migrationStatements(valid + "/* unfinished"));
    }

    @Test
    void errorsExposeStageAndSqlCodesWithoutSensitiveMessages() {
        String secret = "private-password-and-database-url";
        String diagnostic = FaceSchemaTool.safeDiagnostic("CONNECT", new SQLException(secret, "28000", 1045));
        assertTrue(diagnostic.contains("stage=CONNECT"));
        assertTrue(diagnostic.contains("SQLState=28000"));
        assertTrue(diagnostic.contains("vendorCode=1045"));
        assertFalse(diagnostic.contains(secret));
        assertFalse(FaceSchemaTool.safeDiagnostic("LOAD_DRIVER", new ClassNotFoundException(secret)).contains(secret));
        assertFalse(FaceSchemaTool.safeDiagnostic("CREATE_TABLES", new SQLException(secret, secret, 0)).contains(secret));
    }
}
