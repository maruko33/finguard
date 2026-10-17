package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.*;
import org.flywaydb.core.Flyway;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the same local PostgreSQL as mvn-local.py, in a disposable isolated schema. */
class FinanceSchemaIntegrationTest {
    private static Connection db;
    private static String schema;
    private static String url;
    private static String user;
    private static String password;
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID OTHER_ACCOUNT = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID OTHER_CATEGORY = UUID.randomUUID();
    private static final UUID BATCH = UUID.randomUUID();

    @BeforeAll
    static void migrate() throws Exception {
        url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/finguard");
        user = System.getenv().getOrDefault("DB_USERNAME", "finguard");
        password = System.getenv("DB_PASSWORD");
        db = DriverManager.getConnection(url, user, password);
        assertEquals(17, db.getMetaData().getDatabaseMajorVersion(), "Requires actual PostgreSQL 17");
        schema = "finance_test_" + UUID.randomUUID().toString().replace("-", "");
        var flyway = Flyway.configure().dataSource(url, user, password).schemas(schema)
                .defaultSchema(schema).locations("classpath:db/migration").load();
        assertEquals(2, flyway.migrate().migrationsExecuted);
        flyway.validate();
        execute("SET search_path TO " + schema);
        execute("INSERT INTO app_user(id,auth_subject,display_name) VALUES (?, 'fixture-a', 'A'), (?, 'fixture-b', 'B')", OWNER, OTHER);
        execute("INSERT INTO financial_account(id,user_id,name,account_type) VALUES (?,?,'A','CASH'), (?,?,'B','CASH')", ACCOUNT, OWNER, OTHER_ACCOUNT, OTHER);
        execute("INSERT INTO category(id,user_id,name,entry_type) VALUES (?,?,'Food','EXPENSE'), (?,?,'Other','EXPENSE')", CATEGORY, OWNER, OTHER_CATEGORY, OTHER);
        execute("INSERT INTO import_batch(id,user_id,account_id,upload_key,file_name,file_sha256) VALUES (?,?,?,?,'fixture.csv',repeat('a',64))", BATCH, OWNER, ACCOUNT, UUID.randomUUID());
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (db != null) {
            try { if (schema != null) execute("DROP SCHEMA " + schema + " CASCADE"); }
            finally { db.close(); }
        }
    }

    private static void execute(String sql, Object... args) throws SQLException {
        try (var statement = db.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            statement.execute();
        }
    }

    private static void rejects(String state, String sql, Object... args) {
        var error = assertThrows(SQLException.class, () -> execute(sql,args));
        assertEquals(state,error.getSQLState());
    }

    private static String entry() {
        return "INSERT INTO ledger_entry(id,user_id,account_id,source_batch_id,source_row_number,entry_type,amount,currency_code,transaction_date,category_id) VALUES (?,?,?,?,?,'EXPENSE',12.34,'CAD',DATE '2026-10-01',?)";
    }

    @Test
    void sourceUniquenessAllowsIdenticalValuesAndCandidatePurge() throws Exception {
        execute("INSERT INTO import_candidate(id,batch_id,user_id,row_number) VALUES (?,?,?,1)", UUID.randomUUID(), BATCH, OWNER);
        execute(entry(),UUID.randomUUID(),OWNER,ACCOUNT,BATCH,1,CATEGORY);
        execute(entry(),UUID.randomUUID(),OWNER,ACCOUNT,BATCH,2,CATEGORY);
        rejects("23505",entry(),UUID.randomUUID(),OWNER,ACCOUNT,BATCH,1,CATEGORY);
        execute("DELETE FROM import_candidate WHERE batch_id=?",BATCH);
        rejects("23505",entry(),UUID.randomUUID(),OWNER,ACCOUNT,BATCH,1,CATEGORY);
        // Both NULL CSV timestamps and identical trusted timestamps are allowed.
        execute("UPDATE ledger_entry SET occurred_at=TIMESTAMPTZ '2026-10-01 12:00:00+00' WHERE source_batch_id=?",BATCH);
        try (var statement=db.prepareStatement("SELECT count(*) FROM ledger_entry WHERE source_batch_id=? AND occurred_at=TIMESTAMPTZ '2026-10-01 12:00:00+00' AND amount=12.34")) {
            statement.setObject(1,BATCH);
            try (var rows=statement.executeQuery()) { rows.next(); assertEquals(2,rows.getInt(1)); }
        }
    }

    @Test
    void ownershipTypeAndSourceChecks() throws Exception {
        rejects("23503",entry(),UUID.randomUUID(),OWNER,OTHER_ACCOUNT,null,null,null);
        rejects("23503",entry(),UUID.randomUUID(),OWNER,ACCOUNT,null,null,OTHER_CATEGORY);
        rejects("23503",entry().replace("'EXPENSE'","'INCOME'"),UUID.randomUUID(),OWNER,ACCOUNT,null,null,CATEGORY);
        rejects("23503",entry(),UUID.randomUUID(),OTHER,OTHER_ACCOUNT,BATCH,10,null);
        rejects("23514",entry(),UUID.randomUUID(),OWNER,ACCOUNT,BATCH,null,null);
        rejects("23514",entry(),UUID.randomUUID(),OWNER,ACCOUNT,null,10,null);
        rejects("23514",entry().replace("12.34","0"),UUID.randomUUID(),OWNER,ACCOUNT,null,null,null);
        rejects("23514",entry().replace("12.34","-1"),UUID.randomUUID(),OWNER,ACCOUNT,null,null,null);
        rejects("22003",entry().replace("12.34","100000000000000000"),UUID.randomUUID(),OWNER,ACCOUNT,null,null,null);
        rejects("23514",entry().replace("'CAD'","'cad'"),UUID.randomUUID(),OWNER,ACCOUNT,null,null,null);
        var id=UUID.randomUUID();
        execute(entry(),id,OWNER,ACCOUNT,null,null,null);
        rejects("23505",entry(),id,OWNER,ACCOUNT,null,null,null);
    }

    @Test
    void candidateAndBatchConstraints() throws Exception {
        String candidate="INSERT INTO import_candidate(id,batch_id,user_id,row_number,entry_type,category_id) VALUES (?,?,?,50,?,?)";
        rejects("23503",candidate,UUID.randomUUID(),BATCH,OTHER,"EXPENSE",null);
        rejects("23503",candidate,UUID.randomUUID(),BATCH,OWNER,"INCOME",CATEGORY);
        rejects("23514",candidate,UUID.randomUUID(),BATCH,OWNER,null,CATEGORY);
        rejects("23514","INSERT INTO import_candidate(id,batch_id,user_id,row_number,selected_for_import) VALUES (?,?,?,51,true)",UUID.randomUUID(),BATCH,OWNER);
        execute(candidate,UUID.randomUUID(),BATCH,OWNER,null,null);
        rejects("23505",candidate,UUID.randomUUID(),BATCH,OWNER,null,null);
        rejects("23514","UPDATE import_batch SET status='CONFIRMED' WHERE id=?",BATCH);
        rejects("23514","UPDATE import_batch SET version=-1 WHERE id=?",BATCH);
        rejects("23505","INSERT INTO category(id,user_id,name,entry_type) VALUES (?,?,'fOoD','EXPENSE')",UUID.randomUUID(),OWNER);
    }

    @Test
    void durableIdempotencyKeysAndTerminalTimestamps() throws Exception {
        var id=UUID.randomUUID();
        var request=UUID.randomUUID();
        execute(entry(),id,OWNER,ACCOUNT,null,null,null);
        execute("UPDATE ledger_entry SET client_request_key=?,bank_transaction_id='fixture-bank-id',deleted_at=now() WHERE id=?",request,id);
        var second=UUID.randomUUID();
        execute(entry(),second,OWNER,ACCOUNT,null,null,null);
        rejects("23505","UPDATE ledger_entry SET client_request_key=? WHERE id=?",request,second);
        rejects("23505","UPDATE ledger_entry SET bank_transaction_id='fixture-bank-id' WHERE id=?",second);
        rejects("23514","UPDATE import_batch SET status='CANCELLED' WHERE id=?",BATCH);
        execute("INSERT INTO import_candidate(id,batch_id,user_id,row_number) VALUES (?,?,?,60)",UUID.randomUUID(),BATCH,OWNER);
        rejects("23514","UPDATE import_candidate SET validation_errors='{}'::jsonb WHERE batch_id=?",BATCH);
        rejects("23514","UPDATE import_candidate SET duplicate_acknowledged=true WHERE batch_id=?",BATCH);
    }

    @Test
    void hibernateValidatesAndLoadsAllFinanceMappings() throws Exception {
        var config=new Configuration();
        for (var type : new Class<?>[]{AppUser.class,FinancialAccount.class,Category.class,ImportBatch.class,ImportCandidate.class,LedgerEntry.class}) config.addAnnotatedClass(type);
        config.setProperty("hibernate.connection.url",url);
        config.setProperty("hibernate.connection.username",user);
        config.setProperty("hibernate.connection.password",password);
        config.setProperty("hibernate.default_schema",schema);
        config.setProperty("hibernate.hbm2ddl.auto","validate");
        try (var factory=config.buildSessionFactory(); var session=factory.openSession()) {
            var batch=session.find(ImportBatch.class,BATCH);
            assertEquals(ImportStatus.DRAFT,batch.getStatus());
            assertEquals(0,batch.getVersion());
            assertEquals(OWNER,session.find(FinancialAccount.class,ACCOUNT).getUserId());
            assertEquals(EntryType.EXPENSE,session.find(Category.class,CATEGORY).getEntryType());
            assertEquals("fixture-a",session.find(AppUser.class,OWNER).getAuthSubject());
            session.createQuery("from ImportCandidate",ImportCandidate.class).getResultList();
            session.createQuery("from LedgerEntry",LedgerEntry.class).getResultList();
        }
    }
}
