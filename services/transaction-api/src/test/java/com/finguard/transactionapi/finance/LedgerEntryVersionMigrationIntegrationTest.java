package com.finguard.transactionapi.finance;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises an actual V2 -> V3 upgrade, including existing imported/deleted data. */
class LedgerEntryVersionMigrationIntegrationTest {
    @Test
    void upgradesV2WithoutChangingExistingDataAndEnforcesVersionConstraints() throws Exception {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/finguard");
        String user = System.getenv().getOrDefault("DB_USERNAME", "finguard");
        String password = System.getenv("DB_PASSWORD");
        String schema = "ledger_version_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var db = DriverManager.getConnection(url, user, password)) {
            assertEquals(17, db.getMetaData().getDatabaseMajorVersion());
            try {
                var v2 = Flyway.configure().dataSource(url, user, password).schemas(schema)
                        .defaultSchema(schema).locations("classpath:db/migration").target("2").load();
                assertEquals(2, v2.migrate().migrationsExecuted);
                execute(db, "SET search_path TO " + schema);
                UUID owner = UUID.randomUUID(), account = UUID.randomUUID(), batch = UUID.randomUUID();
                UUID manual = UUID.randomUUID(), imported = UUID.randomUUID();
                execute(db, "INSERT INTO app_user(id,auth_subject,display_name) VALUES (?,'version-owner','Owner')", owner);
                execute(db, "INSERT INTO financial_account(id,user_id,name,account_type) VALUES (?,?,'Cash','CASH')", account, owner);
                execute(db, "INSERT INTO import_batch(id,user_id,account_id,upload_key,file_name,file_sha256) VALUES (?,?,?,?,'fixture.csv',repeat('a',64))", batch, owner, account, UUID.randomUUID());
                String insert = "INSERT INTO ledger_entry(id,user_id,account_id,entry_type,amount,currency_code,transaction_date,description) VALUES (?,?,?,'EXPENSE',12.34,'CAD',DATE '2026-10-01','Existing V2 row')";
                execute(db, insert, manual, owner, account);
                execute(db, insert, imported, owner, account);
                execute(db, "UPDATE ledger_entry SET source_batch_id=?,source_row_number=1,bank_transaction_id='bank-source',client_request_key=?,deleted_at=now() WHERE id=?", batch, UUID.randomUUID(), imported);
                List<String> before = rowsWithoutVersion(db);
                var latest = Flyway.configure().dataSource(url, user, password).schemas(schema)
                        .defaultSchema(schema).locations("classpath:db/migration").target("3").load();
                assertEquals(1, latest.migrate().migrationsExecuted);
                latest.validate();
                assertEquals(before, rowsWithoutVersion(db));
                try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM ledger_entry WHERE version=0")) {
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt(1));
                }
                // A new SQL insert also receives the safe initial version default.
                UUID afterMigration = UUID.randomUUID();
                execute(db, insert, afterMigration, owner, account);
                try (var statement = db.prepareStatement("SELECT version FROM ledger_entry WHERE id=?")) {
                    statement.setObject(1, afterMigration);
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(0L, rows.getLong(1));
                        assertFalse(rows.wasNull());
                    }
                }
                var negative = assertThrows(SQLException.class,
                        () -> execute(db, "UPDATE ledger_entry SET version=-1 WHERE id=?", manual));
                assertEquals("23514", negative.getSQLState());
                var missing = assertThrows(SQLException.class,
                        () -> execute(db, "UPDATE ledger_entry SET version=NULL WHERE id=?", manual));
                assertEquals("23502", missing.getSQLState());
                assertEquals(0, latest.migrate().migrationsExecuted);
            } finally {
                // Only this test's randomly named schema is removed; never an application schema/volume.
                execute(db, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static List<String> rowsWithoutVersion(Connection db) throws SQLException {
        List<String> result = new ArrayList<>();
        try (var statement = db.createStatement();
                var rows = statement.executeQuery("SELECT (to_jsonb(e) - 'version')::text FROM ledger_entry e ORDER BY id")) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    private static void execute(Connection db, String sql, Object... args) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            statement.execute();
        }
    }
}
