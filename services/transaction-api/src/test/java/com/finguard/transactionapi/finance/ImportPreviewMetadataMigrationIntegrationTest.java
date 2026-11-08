package com.finguard.transactionapi.finance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real V3 -> V4 upgrade: existing data retains unknown bank/source metadata. */
class ImportPreviewMetadataMigrationIntegrationTest {
    @Test
    void upgradesV3WithoutGuessingHistoricalMetadataAndConstrainsKnownValues() throws Exception {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/finguard");
        String user = System.getenv().getOrDefault("DB_USERNAME", "finguard");
        String password = System.getenv("DB_PASSWORD");
        String schema = "preview_metadata_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var db = DriverManager.getConnection(url, user, password)) {
            assertEquals(17, db.getMetaData().getDatabaseMajorVersion());
            try {
                var v3 = Flyway.configure().dataSource(url, user, password).schemas(schema)
                        .defaultSchema(schema).locations("classpath:db/migration").target("3").load();
                assertEquals(3, v3.migrate().migrationsExecuted);
                execute(db, "SET search_path TO " + schema);
                UUID owner = UUID.randomUUID(), account = UUID.randomUUID(), batch = UUID.randomUUID();
                UUID expense = UUID.randomUUID(), income = UUID.randomUUID(), invalid = UUID.randomUUID();
                execute(db, "INSERT INTO app_user(id,auth_subject,display_name) VALUES (?,'metadata-owner','Owner')", owner);
                execute(db, "INSERT INTO financial_account(id,user_id,name,account_type) VALUES (?,?,'Cash','CASH')", account, owner);
                execute(db, "INSERT INTO import_batch(id,user_id,account_id,upload_key,file_name,file_sha256,status,version)"
                        + " VALUES (?,?,?,?,'legacy.csv',repeat('a',64),'REVIEWING',7)", batch, owner, account, UUID.randomUUID());
                execute(db, "INSERT INTO import_candidate(id,batch_id,user_id,row_number,entry_type,amount,currency_code,transaction_date,description,selected_for_import)"
                        + " VALUES (?,?,?,2,'EXPENSE',12.34,'CAD',DATE '2026-10-01','Legacy expense',true)", expense, batch, owner);
                execute(db, "INSERT INTO import_candidate(id,batch_id,user_id,row_number,entry_type,amount,currency_code,transaction_date,description)"
                        + " VALUES (?,?,?,3,'INCOME',25.00,'CAD',DATE '2026-10-02','Legacy income')", income, batch, owner);
                execute(db, "INSERT INTO import_candidate(id,batch_id,user_id,row_number,description,validation_errors)"
                        + " VALUES (?,?,?,4,'Legacy invalid',CAST(? AS jsonb))", invalid, batch, owner,
                        "[{\"code\":\"INVALID_AMOUNT\"},{\"code\":\"INVALID_DATE\"}]");
                execute(db, "INSERT INTO ledger_entry(id,user_id,account_id,entry_type,amount,currency_code,transaction_date,description)"
                        + " VALUES (?,?,?,'EXPENSE',6.00,'CAD',DATE '2026-10-01','Existing manual entry')",
                        UUID.randomUUID(), owner, account);
                List<String> batchesBefore = jsonRows(db, "import_batch", "bank_type");
                List<String> candidatesBefore = jsonRows(db, "import_candidate", "source_direction");
                List<String> ledgerBefore = jsonRows(db, "ledger_entry", "nonexistent_property");

                var v4 = Flyway.configure().dataSource(url, user, password).schemas(schema)
                        .defaultSchema(schema).locations("classpath:db/migration").target("4").load();
                assertEquals(1, v4.migrate().migrationsExecuted);
                v4.validate();
                assertEquals(batchesBefore, jsonRows(db, "import_batch", "bank_type"));
                assertEquals(candidatesBefore, jsonRows(db, "import_candidate", "source_direction"));
                assertEquals(ledgerBefore, jsonRows(db, "ledger_entry", "nonexistent_property"));
                assertEquals(1, scalar(db, "SELECT count(*) FROM import_batch WHERE bank_type IS NULL AND version=7"));
                assertEquals(3, scalar(db, "SELECT count(*) FROM import_candidate WHERE source_direction IS NULL"),
                        "Neither historical INCOME nor EXPENSE reveals an original bank direction");
                assertEquals(3, scalar(db, "SELECT count(*) FROM import_candidate WHERE jsonb_typeof(validation_errors)='array'"));

                execute(db, "UPDATE import_batch SET bank_type='RBC_VISA' WHERE id=?", batch);
                execute(db, "UPDATE import_candidate SET source_direction='DEBIT' WHERE id=?", expense);
                execute(db, "UPDATE import_candidate SET source_direction='CREDIT' WHERE id=?", income);
                assertEquals(1, scalar(db, "SELECT count(*) FROM import_batch WHERE bank_type='RBC_VISA'"));
                assertEquals(2, scalar(db, "SELECT count(*) FROM import_candidate WHERE source_direction IS NOT NULL"));
                assertCheckViolation(db, "UPDATE import_batch SET bank_type='UNSUPPORTED' WHERE id=?", batch);
                assertCheckViolation(db, "UPDATE import_candidate SET source_direction='INCOME' WHERE id=?", expense);
                assertCheckViolation(db, "UPDATE import_candidate SET validation_errors=CAST(? AS jsonb) WHERE id=?", "{}", invalid);
                execute(db, "UPDATE import_batch SET bank_type=NULL WHERE id=?", batch);
                execute(db, "UPDATE import_candidate SET source_direction=NULL WHERE id IN (?,?)", expense, income);

                // Equal file hashes with distinct retry keys remain legal after the additive migration.
                execute(db, "INSERT INTO import_batch(id,user_id,account_id,upload_key,file_name,file_sha256,bank_type)"
                        + " VALUES (?,?,?,?,'new.csv',repeat('a',64),'RBC_VISA')", UUID.randomUUID(), owner, account, UUID.randomUUID());
                assertEquals(2, scalar(db, "SELECT count(*) FROM import_batch WHERE file_sha256=repeat('a',64)"));
                assertEquals(0, v4.migrate().migrationsExecuted);
            } finally {
                assertTrue(schema.matches("preview_metadata_test_[a-f0-9]{32}"));
                // Only this run's randomly named schema is removed, never existing schemas/volumes.
                execute(db, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static void assertCheckViolation(Connection db, String sql, Object... args) {
        var failure = assertThrows(SQLException.class, () -> execute(db, sql, args));
        assertEquals("23514", failure.getSQLState());
    }

    private static int scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static List<String> jsonRows(Connection db, String table, String addedField) throws SQLException {
        List<String> result = new ArrayList<>();
        try (var statement = db.createStatement();
             var rows = statement.executeQuery("SELECT (to_jsonb(t) - '" + addedField + "')::text FROM " + table + " t ORDER BY id")) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    private static void execute(Connection db, String sql, Object... args) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            statement.execute();
        }
    }
}
