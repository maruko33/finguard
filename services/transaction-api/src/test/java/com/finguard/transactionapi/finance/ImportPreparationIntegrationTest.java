package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.*;
import com.finguard.transactionapi.finance.dto.*;
import com.finguard.transactionapi.finance.exception.*;
import com.finguard.transactionapi.finance.importing.*;
import com.finguard.transactionapi.finance.repository.ImportBatchRepository;
import com.finguard.transactionapi.finance.service.ImportBatchQueryService;
import com.finguard.transactionapi.finance.service.ImportPreparationService;
import jakarta.persistence.EntityManagerFactory;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.flywaydb.core.Flyway;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.SAME_THREAD)
class ImportPreparationIntegrationTest {
    private static final String HEADER = "Transaction Date,Description 1,CAD$,USD$\n";
    private static final String SIMPLE = HEADER + "11/1/2026,Synthetic expense,-12.34,\n";
    private static final String MIXED = HEADER
            + "10/30/2026,Synthetic expense,-12.34,\n"
            + "bad,Synthetic date,-8.25,\n"
            + "11/1/2026,Synthetic amount,-1.001,\n"
            + "11/1/2026,Synthetic credit,5.00,\n";
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static ImportPreparationService service;
    private static ImportBatchQueryService query;
    private static RaceGate gate;
    private static SqlCapture sql;
    private static String schema;

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = ImportBatchRepository.class)
    @Import({ImportPreparationService.class, ImportBatchQueryService.class})
    static class Config {
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
        @Bean RaceGate raceGate() { return new RaceGate(); }
        @Bean SqlCapture sqlCapture() { return new SqlCapture(); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source, SqlCapture capture) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("com.finguard.transactionapi.finance.domain");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                    "hibernate.default_schema", schema,
                    "hibernate.session_factory.statement_inspector", capture));
            return factory;
        }
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
        @Bean @Primary ImportBatchRepository gatedBatchRepository(
                @Qualifier("importBatchRepository") ImportBatchRepository delegate,
                RaceGate raceGate, EntityManagerFactory factory) {
            var proxy = new ProxyFactory(delegate);
            proxy.addAdvice((MethodInterceptor) invocation -> {
                Object result = invocation.proceed();
                if (invocation.getMethod().getName().equals("findByUserIdAndUploadKey")
                        && result instanceof Optional<?> existing && existing.isEmpty()) {
                    raceGate.afterAbsentRead((UUID) invocation.getArguments()[1], factory);
                }
                return result;
            });
            return (ImportBatchRepository) proxy.getProxy();
        }
    }

    @BeforeAll static void setup() {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/finguard"),
                System.getenv().getOrDefault("DB_USERNAME", "finguard"), System.getenv("DB_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        assertEquals(17, jdbc.queryForObject("SHOW server_version_num", Integer.class) / 10_000);
        schema = "import_prepare_test_" + UUID.randomUUID().toString().replace("-", "");
        var flyway = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
        assertEquals(4, flyway.migrate().migrationsExecuted);
        flyway.validate();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.register(Config.class);
        context.refresh();
        service = context.getBean(ImportPreparationService.class);
        query = context.getBean(ImportBatchQueryService.class);
        gate = context.getBean(RaceGate.class);
        sql = context.getBean(SqlCapture.class);
    }

    @AfterAll static void cleanup() {
        if (context != null) context.close();
        if (jdbc != null && schema != null) {
            assertTrue(schema.matches("import_prepare_test_[a-f0-9]{32}"));
            // Remove only this run's isolated fixture schema.
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test void uploadCanBeListedAndRestoredInANewContextWithEveryRowAndNoLedgerChanges() {
        Owner owner = owner();
        seedLedger(owner);
        var ledgerBefore = snapshot("ledger_entry");
        String csv = MIXED + "10/29/2026,Synthetic extra,-1.00,\n".repeat(13);
        var uploaded = upload(owner, UUID.randomUUID(), csv, "synthetic-upload.csv");
        assertEquals(ImportStatus.REVIEWING, uploaded.status());
        assertEquals(0, uploaded.version());
        assertEquals(15, uploaded.candidates().getSize());
        assertEquals(15, uploaded.candidates().getNumberOfElements());
        assertEquals(17, uploaded.candidates().getTotalElements());
        var transaction = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        transaction.setReadOnly(true);
        ImportPreviewResponse restored = transaction.execute(status -> {
            var batches = query.listReviewingBatches(owner.userId(), PageRequest.of(0, 15));
            assertEquals(1, batches.getTotalElements());
            var summary = batches.getContent().getFirst();
            assertEquals(uploaded.id(), summary.id());
            assertEquals(17, summary.candidateCount());
            assertEquals(3, summary.attentionRowCount());
            return query.getImportPreview(owner.userId(), uploaded.id(), PageRequest.of(0, 100));
        });
        assertNotNull(restored);
        assertEquals(17, restored.candidates().getNumberOfElements());
        Map<Integer, CandidateSummary> rows = restored.candidates().stream().collect(
                java.util.stream.Collectors.toMap(CandidateSummary::rowNumber, row -> row));
        assertEquals(Set.copyOf(java.util.stream.IntStream.rangeClosed(2, 18).boxed().toList()), rows.keySet());
        assertEquals(new BigDecimal("12.34"), rows.get(2).amount());
        assertEquals(EntryType.EXPENSE, rows.get(2).entryType());
        assertEquals(ImportSourceDirection.DEBIT, rows.get(2).sourceDirection());
        assertNull(rows.get(3).transactionDate());
        assertEquals(new BigDecimal("8.25"), rows.get(3).amount());
        assertTrue(rows.get(3).validationErrors().contains("INVALID_DATE"));
        assertNull(rows.get(4).amount());
        assertEquals(LocalDate.of(2026, 11, 1), rows.get(4).transactionDate());
        assertTrue(rows.get(4).validationErrors().contains("INVALID_AMOUNT"));
        var credit = rows.get(5);
        assertNull(credit.entryType());
        assertEquals(ImportSourceDirection.CREDIT, credit.sourceDirection());
        assertEquals(new BigDecimal("5.00"), credit.amount());
        assertEquals("CAD", credit.currencyCode());
        assertEquals("Synthetic credit", credit.description());
        assertTrue(credit.validationErrors().contains("REVIEW_REQUIRED"));
        for (var row : rows.values()) {
            assertFalse(row.selectedForImport());
            assertNull(row.categoryId());
            assertFalse(row.duplicateWarning());
            assertFalse(row.duplicateAcknowledged());
        }
        assertEquals(17, jdbc.queryForObject("SELECT count(*) FROM " + table("import_candidate")
                + " WHERE batch_id=? AND jsonb_typeof(validation_errors)='array'", Integer.class, uploaded.id()));
        assertEquals("RBC_VISA", batchValue(uploaded.id(), "bank_type"));
        assertNull(batchValue(uploaded.id(), "closed_at"));
        assertNull(batchValue(uploaded.id(), "confirmed_at"));
        assertEquals(0, batchValue(uploaded.id(), "confirmed_count"));
        assertEquals(0, batchValue(uploaded.id(), "excluded_count"));
        assertEquals(sha256(csv), batchValue(uploaded.id(), "file_sha256"));
        assertEquals(ledgerBefore, snapshot("ledger_entry"));
        System.out.printf("IMPORT_ROUND_TRIP_RESULT file=%s status=%s version=%d firstPage=%d/%d storedRows=%d credit=%s%n",
                uploaded.fileName(), restored.status(), restored.version(), uploaded.candidates().getNumberOfElements(),
                uploaded.candidates().getSize(), restored.candidates().getTotalElements(), credit);
    }

    @Test void exactRetriesPreserveIdsVersionAndOriginalNameWhileANewKeyCreatesANewBatch() {
        Owner owner = owner();
        UUID key = UUID.randomUUID();
        var first = upload(owner, key, MIXED, "original.csv");
        var before = batchSnapshot(first.id());
        var rowsBefore = candidateSnapshot(first.id());
        var retry = upload(owner, key, MIXED, "renamed.csv");
        assertEquals(first.id(), retry.id());
        assertEquals(first.candidates(), retry.candidates());
        assertEquals("original.csv", retry.fileName());
        assertEquals(before, batchSnapshot(first.id()));
        assertEquals(rowsBefore, candidateSnapshot(first.id()));
        var otherKey = upload(owner, UUID.randomUUID(), MIXED, "new.csv");
        assertNotEquals(first.id(), otherKey.id());
        assertEquals(2, batchCount(owner));
        assertEquals(4, otherKey.candidates().getTotalElements());
    }

    @Test void differentAccountOrRawBytesConflictWithoutOverwritingTheWinner() {
        Owner owner = owner();
        UUID key = UUID.randomUUID();
        var first = upload(owner, key, SIMPLE, "original.csv");
        var before = batchSnapshot(first.id());
        var rowsBefore = candidateSnapshot(first.id());
        UUID anotherAccount = account(owner.userId(), "CAD");
        assertThrows(ImportIdempotencyConflictException.class, () -> service.prepareImport(owner.userId(),
                anotherAccount, stream(SIMPLE), BankType.RBC_VISA, key, "another.csv"));
        // Same parsed transaction, different raw bytes: identity must conflict.
        assertThrows(ImportIdempotencyConflictException.class, () -> upload(owner, key, SIMPLE + "\n", "another.csv"));
        assertEquals(before, batchSnapshot(first.id()));
        assertEquals(rowsBefore, candidateSnapshot(first.id()));
        assertEquals(1, batchCount(owner));
    }

    @Test void unknownHistoricalBankCannotBeAcceptedAsAVerifiedRetry() {
        Owner owner = owner();
        UUID key = UUID.randomUUID();
        var preview = upload(owner, key, SIMPLE, "original.csv");
        jdbc.update("UPDATE " + table("import_batch") + " SET bank_type=NULL WHERE id=?", preview.id());
        var before = batchSnapshot(preview.id());
        assertThrows(ImportIdempotencyConflictException.class, () -> upload(owner, key, SIMPLE, "retry.csv"));
        assertEquals(before, batchSnapshot(preview.id()));
    }

    @Test void exactRetryDoesNotRunTheCurrentParserAgain() {
        Owner owner = owner();
        UUID key = UUID.randomUUID();
        var preview = upload(owner, key, SIMPLE, "original.csv");
        // Synthetic historical identity predating current header rules: a reparse would fail.
        String oldBytes = "unsupported historical header\nlegacy value\n";
        jdbc.update("UPDATE " + table("import_batch") + " SET file_sha256=? WHERE id=?", sha256(oldBytes), preview.id());
        var before = candidateSnapshot(preview.id());
        var retry = upload(owner, key, oldBytes, "retry.csv");
        assertEquals(preview.id(), retry.id());
        assertEquals(before, candidateSnapshot(preview.id()));
    }

    @Test void ownershipIsRequiredAndTheSameUploadKeyIsIsolatedPerUser() {
        Owner owner = owner();
        Owner other = owner();
        UUID key = UUID.randomUUID();
        var missing = assertThrows(FinancialAccountNotFoundException.class, () -> service.prepareImport(
                owner.userId(), UUID.randomUUID(), stream(SIMPLE), BankType.RBC_VISA, key, "file.csv"));
        var foreign = assertThrows(FinancialAccountNotFoundException.class, () -> service.prepareImport(
                other.userId(), owner.accountId(), stream(SIMPLE), BankType.RBC_VISA, key, "file.csv"));
        assertEquals(missing.getMessage(), foreign.getMessage());
        assertEquals(0, batchCount(owner));
        var first = upload(owner, key, SIMPLE, "owner.csv");
        var second = upload(other, key, SIMPLE, "other.csv");
        assertNotEquals(first.id(), second.id());
        assertEquals(owner.accountId(), first.accountId());
        assertEquals(other.accountId(), second.accountId());
        assertEquals(1, batchCount(owner));
        assertEquals(1, batchCount(other));
    }

    @Test void inactiveAccountsRejectNewUploadsButTerminalRetriesKeepTheirActualState() {
        Owner owner = owner();
        UUID key = UUID.randomUUID();
        var first = upload(owner, key, SIMPLE, "original.csv");
        jdbc.update("UPDATE " + table("financial_account") + " SET active=FALSE WHERE id=?", owner.accountId());
        assertThrows(InvalidImportRequestException.class, () -> upload(owner, UUID.randomUUID(), SIMPLE, "new.csv"));
        for (ImportStatus status : List.of(ImportStatus.CONFIRMED, ImportStatus.CANCELLED, ImportStatus.FAILED)) {
            jdbc.update("UPDATE " + table("import_batch")
                    + " SET status=?,version=9,closed_at=now(),confirmed_at=CASE WHEN ?='CONFIRMED' THEN now() ELSE NULL END WHERE id=?",
                    status.name(), status.name(), first.id());
            var before = batchSnapshot(first.id());
            var rowsBefore = candidateSnapshot(first.id());
            var retry = upload(owner, key, SIMPLE, "retry.csv");
            assertEquals(first.id(), retry.id());
            assertEquals(status, retry.status());
            assertEquals(9, retry.version());
            assertEquals(before, batchSnapshot(first.id()));
            assertEquals(rowsBefore, candidateSnapshot(first.id()));
        }
        assertEquals(1, batchCount(owner));
    }

    @Test void writeTransactionRechecksAccountStateAndCurrencyAfterInitialRead() {
        Owner inactive = owner();
        var changesState = new MutatingStream(SIMPLE, () -> jdbc.update("UPDATE " + table("financial_account")
                + " SET active=FALSE WHERE id=?", inactive.accountId()));
        assertThrows(InvalidImportRequestException.class, () -> service.prepareImport(inactive.userId(),
                inactive.accountId(), changesState, BankType.RBC_VISA, UUID.randomUUID(), "file.csv"));
        assertEquals(0, batchCount(inactive));
        Owner currency = owner();
        var changesCurrency = new MutatingStream(SIMPLE, () -> jdbc.update("UPDATE " + table("financial_account")
                + " SET currency_code='USD' WHERE id=?", currency.accountId()));
        var preview = service.prepareImport(currency.userId(), currency.accountId(), changesCurrency,
                BankType.RBC_VISA, UUID.randomUUID(), "file.csv");
        var row = preview.candidates().getContent().getFirst();
        assertEquals("CAD", row.currencyCode());
        assertEquals(new BigDecimal("12.34"), row.amount());
        assertTrue(row.validationErrors().contains("ACCOUNT_CURRENCY_MISMATCH"));
    }

    @Test void currencyMismatchRetainsFactsAndAddsAnArrayIssueWithoutRejectingTheFile() {
        Owner owner = owner();
        var preview = upload(owner, UUID.randomUUID(), HEADER + "11/1/2026,Synthetic USD,,-8.75\n", "usd.csv");
        var row = preview.candidates().getContent().getFirst();
        assertEquals("USD", row.currencyCode());
        assertEquals(new BigDecimal("8.75"), row.amount());
        assertEquals(EntryType.EXPENSE, row.entryType());
        assertEquals(ImportSourceDirection.DEBIT, row.sourceDirection());
        assertFalse(row.selectedForImport());
        assertTrue(row.validationErrors().contains("ACCOUNT_CURRENCY_MISMATCH"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + table("import_candidate")
                + " WHERE batch_id=? AND jsonb_typeof(validation_errors)='array'"
                + " AND validation_errors @> '[{\"code\":\"ACCOUNT_CURRENCY_MISMATCH\"}]'::jsonb", Integer.class, preview.id()));
    }

    @Test void fileLevelFailuresLeaveNoRowsButAllInvalidDataRowsRemainReviewable() {
        Owner owner = owner();
        for (byte[] bad : List.of(new byte[0], new byte[] {(byte) 0xc3, (byte) 0x28},
                bytes(HEADER + "11/1/2026,\"unterminated,-1.00,\n"),
                bytes("wrong,header\nvalue,value\n"), bytes(HEADER),
                bytes(HEADER + "11/1/2026,Synthetic row,-1.00,\n".repeat(CsvParser.DEFAULT_MAX_ROWS + 1)))) {
            assertThrows(IllegalArgumentException.class, () -> service.prepareImport(owner.userId(), owner.accountId(),
                    new ByteArrayInputStream(bad), BankType.RBC_VISA, UUID.randomUUID(), "file.csv"));
            assertEquals(0, batchCount(owner));
            assertEquals(0, candidateCount(owner));
        }
        assertTrue(new CsvImportProcessor().process(stream(HEADER), BankType.RBC_VISA).rows().isEmpty(),
                "The generic processor's header-only contract must remain unchanged");
        String invalid = HEADER + "bad,Synthetic date,-1.00,\n11/1/2026,Synthetic amount,invalid,\n";
        var preview = upload(owner, UUID.randomUUID(), invalid, "all-invalid.csv");
        assertEquals(2, preview.candidates().getTotalElements());
        assertTrue(preview.candidates().stream().allMatch(row -> !row.validationErrors().equals("[]")));
        assertEquals(2, query.listReviewingBatches(owner.userId(), PageRequest.of(0, 15))
                .getContent().getFirst().attentionRowCount());
    }

    @Test void originalStreamIsBoundedConsumedOnceAndRemainsCallerOwned() throws Exception {
        Owner owner = owner();
        var endless = new CountingStream();
        var failure = assertThrows(CsvParseException.class, () -> service.prepareImport(owner.userId(), owner.accountId(),
                endless, BankType.RBC_VISA, UUID.randomUUID(), "file.csv"));
        assertEquals(ImportIssueCode.FILE_TOO_LARGE, failure.code());
        assertTrue(endless.consumed <= CsvParser.DEFAULT_MAX_BYTES + 1);
        assertFalse(endless.closed);
        endless.close();
        assertEquals(0, batchCount(owner));
        var valid = new CountingFiniteStream(bytes(SIMPLE));
        service.prepareImport(owner.userId(), owner.accountId(), valid, BankType.RBC_VISA, UUID.randomUUID(), "file.csv");
        assertEquals(bytes(SIMPLE).length, valid.consumed);
        assertFalse(valid.closed);
        valid.close();
    }

    @Test void invalidDisplayNamesCannotCreateBatches() {
        Owner owner = owner();
        for (String name : List.of("", " ", "../file.csv", "folder/file.csv", "folder\\file.csv",
                "bad\nfile.csv", "bad" + (char) 0 + "file.csv", "a".repeat(256))) {
            assertThrows(InvalidImportRequestException.class, () -> upload(owner, UUID.randomUUID(), SIMPLE, name));
        }
        assertEquals(0, batchCount(owner));
    }

    @Test void candidateFailureRollsBackBatchAndEarlierRowsAndTheSameKeyCanSucceedLater() {
        Owner owner = owner();
        UUID key = UUID.randomUUID();
        var ledgerBefore = snapshot("ledger_entry");
        jdbc.execute("CREATE FUNCTION " + schema + ".fail_second_candidate() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.row_number=3 THEN "
                + "IF NOT EXISTS(SELECT 1 FROM " + table("import_candidate") + " WHERE batch_id=NEW.batch_id AND row_number=2) "
                + "THEN RAISE EXCEPTION 'first candidate was not inserted' USING ERRCODE='23503'; END IF; "
                + "RAISE EXCEPTION 'synthetic failure after first candidate' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_second_candidate BEFORE INSERT ON " + table("import_candidate")
                + " FOR EACH ROW EXECUTE FUNCTION " + schema + ".fail_second_candidate()");
        try {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> upload(owner, key, MIXED, "retryable.csv"));
            assertTrue(hasSqlState(failure, "23514"), "Real database failure must propagate and roll back");
            // Outside the failed service transaction, JdbcTemplate opens a new database connection.
            assertEquals(0, batchCount(owner));
            assertEquals(0, candidateCount(owner));
            assertEquals(ledgerBefore, snapshot("ledger_entry"));
        } finally {
            jdbc.execute("DROP TRIGGER fail_second_candidate ON " + table("import_candidate"));
            jdbc.execute("DROP FUNCTION " + schema + ".fail_second_candidate()");
        }
        var retry = upload(owner, key, MIXED, "retryable.csv");
        assertEquals(1, batchCount(owner));
        assertEquals(4, retry.candidates().getTotalElements());
        System.out.println("IMPORT_ATOMICITY_RESULT partialInsertFailure=23514 residualBatches=0 residualCandidates=0 sameKeyRetryRows=4");
    }

    @Test void concurrentIdenticalUploadsUseTwoSessionsAndCreateOneBatchAndCandidateSet() throws Exception {
        Owner owner = owner();
        UUID key = UUID.randomUUID();
        var attempts = race(owner, key, MIXED, MIXED);
        assertNull(attempts.get(0).failure());
        assertNull(attempts.get(1).failure());
        assertEquals(attempts.get(0).preview().id(), attempts.get(1).preview().id());
        assertEquals(1, batchCount(owner));
        assertEquals(4, candidateCount(owner));
        assertEquals(2, gate.sessions.size());
        assertEquals(2, sql.statements.stream().filter(s -> s.contains("on conflict on constraint uq_batch_upload_retry")).count());
        System.out.printf("IMPORT_CONCURRENCY_RESULT identity=same sessions=%s batches=1 candidates=4 sharedBatch=true%n", gate.sessions);
    }

    @Test void concurrentDifferentFilesHaveOneWinnerAndOneConflictWithoutChangingTheWinner() throws Exception {
        Owner owner = owner();
        UUID key = UUID.randomUUID();
        String otherFile = HEADER + "11/1/2026,Synthetic other,-77.25,\n";
        var attempts = race(owner, key, SIMPLE, otherFile);
        assertEquals(1, attempts.stream().filter(a -> a.failure() == null).count());
        assertEquals(1, attempts.stream().filter(a -> a.failure() instanceof ImportIdempotencyConflictException).count());
        int winner = attempts.getFirst().failure() == null ? 0 : 1;
        var preview = attempts.get(winner).preview();
        assertEquals(sha256(winner == 0 ? SIMPLE : otherFile), batchValue(preview.id(), "file_sha256"));
        assertEquals(new BigDecimal(winner == 0 ? "12.34" : "77.25"), preview.candidates().getContent().getFirst().amount());
        assertEquals(1, batchCount(owner));
        assertEquals(1, candidateCount(owner));
        assertEquals(2, gate.sessions.size());
        assertEquals(2, sql.statements.stream().filter(s -> s.contains("on conflict on constraint uq_batch_upload_retry")).count());
        var before = candidateSnapshot(preview.id());
        assertEquals(preview, query.getImportPreview(owner.userId(), preview.id(), PageRequest.of(0, 15)));
        assertEquals(before, candidateSnapshot(preview.id()));
        System.out.printf("IMPORT_CONCURRENCY_RESULT identity=different sessions=%s successes=1 conflicts=1 batches=1 candidates=1%n", gate.sessions);
    }

    @Test void preparationCommitsIndependentlyWhenTheCallingTransactionRollsBack() {
        Owner owner = owner();
        UUID key = UUID.randomUUID();
        var outer = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        UUID committed = outer.execute(status -> {
            var response = upload(owner, key, SIMPLE, "independent.csv");
            status.setRollbackOnly();
            return response.id();
        });
        assertNotNull(committed);
        assertEquals(1, batchCount(owner));
        assertEquals(1, candidateCount(owner));
        assertEquals(committed, upload(owner, key, SIMPLE, "retry.csv").id());
    }

    private record Owner(UUID userId, UUID accountId) {}
    private record Attempt(ImportPreviewResponse preview, RuntimeException failure) {}

    private static List<Attempt> race(Owner owner, UUID key, String first, String second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        gate.arm(key);
        sql.statements.clear();
        try {
            var a = executor.submit(() -> attempt(owner, key, first));
            var b = executor.submit(() -> attempt(owner, key, second));
            return List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
        } finally {
            gate.disarm();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Bounded concurrency workers must terminate");
        }
    }
    private static Attempt attempt(Owner owner, UUID key, String csv) {
        try { return new Attempt(upload(owner, key, csv, "concurrent.csv"), null); }
        catch (RuntimeException failure) { return new Attempt(null, failure); }
    }
    private static ImportPreviewResponse upload(Owner owner, UUID key, String csv, String fileName) {
        return service.prepareImport(owner.userId(), owner.accountId(), stream(csv), BankType.RBC_VISA, key, fileName);
    }
    private static Owner owner() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("app_user") + " (id,auth_subject,display_name) VALUES (?,?,?)",
                id, "prepare-" + id, "Synthetic owner");
        return new Owner(id, account(id, "CAD"));
    }
    private static UUID account(UUID owner, String currency) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("financial_account")
                + " (id,user_id,name,account_type,currency_code) VALUES (?,?,?,'CREDIT_CARD',?)",
                id, owner, "Synthetic account " + id, currency);
        return id;
    }
    private static void seedLedger(Owner owner) {
        jdbc.update("INSERT INTO " + table("ledger_entry")
                + " (id,user_id,account_id,entry_type,amount,currency_code,transaction_date,description)"
                + " VALUES (?,?,?,'EXPENSE',4.25,'CAD',DATE '2026-10-01','Existing synthetic ledger row')",
                UUID.randomUUID(), owner.userId(), owner.accountId());
    }
    private static int batchCount(Owner owner) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table("import_batch") + " WHERE user_id=?", Integer.class, owner.userId());
    }
    private static int candidateCount(Owner owner) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table("import_candidate") + " WHERE user_id=?", Integer.class, owner.userId());
    }
    private static Object batchValue(UUID batch, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + table("import_batch") + " WHERE id=?", Object.class, batch);
    }
    private static Map<String, Object> batchSnapshot(UUID batch) {
        return jdbc.queryForMap("SELECT * FROM " + table("import_batch") + " WHERE id=?", batch);
    }
    private static List<Map<String, Object>> candidateSnapshot(UUID batch) {
        return jdbc.queryForList("SELECT * FROM " + table("import_candidate") + " WHERE batch_id=? ORDER BY row_number", batch);
    }
    private static List<Map<String, Object>> snapshot(String name) {
        return jdbc.queryForList("SELECT * FROM " + table(name) + " ORDER BY id");
    }
    private static String table(String name) { return schema + "." + name; }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static ByteArrayInputStream stream(String value) { return new ByteArrayInputStream(bytes(value)); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static boolean hasSqlState(Throwable failure, String state) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlFailure && state.equals(sqlFailure.getSQLState())) return true;
        }
        return false;
    }

    static class RaceGate {
        private volatile UUID key;
        private volatile CyclicBarrier barrier;
        final Set<Integer> sessions = ConcurrentHashMap.newKeySet();
        void arm(UUID key) { sessions.clear(); this.key = key; barrier = new CyclicBarrier(2); }
        void disarm() { barrier = null; key = null; }
        void afterAbsentRead(UUID uploadKey, EntityManagerFactory factory) throws Exception {
            var current = barrier;
            if (current == null || !uploadKey.equals(key) || !TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) return;
            var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(factory);
            assertNotNull(entityManager);
            sessions.add(((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue());
            current.await(15, TimeUnit.SECONDS);
        }
    }
    static class SqlCapture implements StatementInspector {
        final ConcurrentLinkedQueue<String> statements = new ConcurrentLinkedQueue<>();
        @Override public String inspect(String statement) {
            statements.add(statement.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip());
            return statement;
        }
    }
    private static class MutatingStream extends ByteArrayInputStream {
        private final Runnable beforeRead;
        private boolean changed;
        MutatingStream(String csv, Runnable beforeRead) { super(bytes(csv)); this.beforeRead = beforeRead; }
        private void change() { if (!changed) { changed = true; beforeRead.run(); } }
        @Override public synchronized int read(byte[] bytes, int offset, int length) { change(); return super.read(bytes, offset, length); }
        @Override public synchronized int read() { change(); return super.read(); }
    }
    private static class CountingStream extends InputStream {
        long consumed;
        boolean closed;
        @Override public int read() { consumed++; return 'x'; }
        @Override public int read(byte[] bytes, int offset, int length) {
            Arrays.fill(bytes, offset, offset + length, (byte) 'x'); consumed += length; return length;
        }
        @Override public void close() { closed = true; }
    }
    private static class CountingFiniteStream extends ByteArrayInputStream {
        long consumed;
        boolean closed;
        CountingFiniteStream(byte[] bytes) { super(bytes); }
        @Override public synchronized int read(byte[] bytes, int offset, int length) {
            int count = super.read(bytes, offset, length); if (count > 0) consumed += count; return count;
        }
        @Override public synchronized int read() { int value = super.read(); if (value != -1) consumed++; return value; }
        @Override public void close() { closed = true; }
    }
}
