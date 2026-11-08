package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.*;
import com.finguard.transactionapi.finance.dto.*;
import com.finguard.transactionapi.finance.exception.*;
import com.finguard.transactionapi.finance.importing.*;
import com.finguard.transactionapi.finance.repository.ImportBatchRepository;
import com.finguard.transactionapi.finance.service.*;
import jakarta.persistence.EntityManagerFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.SAME_THREAD)
class ImportDuplicateWarningIntegrationTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 1);
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static ImportDuplicateWarningService service;
    private static ImportPreparationService preparation;
    private static ImportBatchQueryService query;
    private static SqlCapture sql;
    private static LockGate gate;
    private static String schema;

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = ImportBatchRepository.class)
    @Import({ImportDuplicateWarningService.class, ImportPreparationService.class, ImportBatchQueryService.class})
    static class Config {
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
        @Bean SqlCapture sqlCapture() { return new SqlCapture(); }
        @Bean LockGate lockGate() { return new LockGate(); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source, SqlCapture capture) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("com.finguard.transactionapi.finance.domain");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.default_schema", schema,
                    "hibernate.session_factory.statement_inspector", capture));
            return factory;
        }
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
        @Bean @Primary ImportBatchRepository gatedBatchRepository(
                @Qualifier("importBatchRepository") ImportBatchRepository delegate,
                LockGate gate, EntityManagerFactory factory) {
            var proxy = new ProxyFactory(delegate);
            proxy.addAdvice((MethodInterceptor) invocation -> {
                int attempt = invocation.getMethod().getName().equals("findOwnedForDuplicateScan")
                        ? gate.beforeLock((UUID) invocation.getArguments()[0], factory) : 0;
                Object result = invocation.proceed();
                if (attempt != 0) gate.afterLock(attempt, result);
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
        schema = "import_duplicate_test_" + UUID.randomUUID().toString().replace("-", "");
        var flyway = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
        assertEquals(4, flyway.migrate().migrationsExecuted);
        flyway.validate();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.register(Config.class);
        context.refresh();
        service = context.getBean(ImportDuplicateWarningService.class);
        preparation = context.getBean(ImportPreparationService.class);
        query = context.getBean(ImportBatchQueryService.class);
        sql = context.getBean(SqlCapture.class);
        gate = context.getBean(LockGate.class);
    }

    @AfterAll static void cleanup() {
        if (context != null) context.close();
        if (jdbc != null && schema != null) {
            assertTrue(schema.matches("import_duplicate_test_[a-f0-9]{32}"));
            // Remove only this run's isolated fixture schema.
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test void preparedPreviewCanBeScannedAndRestoredWithoutChangingTransactionsOrAttentionCounts() {
        Owner owner = owner();
        jdbc.update("INSERT INTO " + table("ledger_entry")
                + " (id,user_id,account_id,entry_type,amount,currency_code,transaction_date,description)"
                + " VALUES (?,?,?,'EXPENSE',4.25,'CAD',DATE '2026-10-01','Existing synthetic ledger row')",
                UUID.randomUUID(), owner.user(), owner.account());
        var ledgerBefore = jsonRows("ledger_entry", "", null);
        String csv = "Transaction Date,Description 1,CAD$,USD$\n"
                + "11/1/2026,Coffee Shop,-30.0,\n"
                + "11/1/2026,Bus fare,-3.00,\n"
                + "11/1/2026,  COFFEE   SHOP  ,-30.00,\n"
                + "11/1/2026,Synthetic credit,5.00,\n"
                + "bad,Synthetic invalid date,-8.25,\n"
                + "11/1/2026,synthetic credit,5.00,\n";
        var uploaded = preparation.prepareImport(owner.user(), owner.account(),
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), BankType.RBC_VISA,
                UUID.randomUUID(), "legitimate-repeat.csv");
        var factsBefore = candidateFacts(uploaded.id());
        var batchBefore = batchFacts(uploaded.id());
        scan(owner, uploaded.id());
        var read = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        read.setReadOnly(true);
        ImportPreviewResponse restored = read.execute(status -> query.getImportPreview(
                owner.user(), uploaded.id(), PageRequest.of(0, 15)));
        assertNotNull(restored);
        assertEquals(1, restored.version());
        var rows = restored.candidates().stream().collect(java.util.stream.Collectors.toMap(CandidateSummary::rowNumber, r -> r));
        assertEquals(Set.of(2, 4, 5, 7), rows.values().stream().filter(CandidateSummary::duplicateWarning)
                .map(CandidateSummary::rowNumber).collect(java.util.stream.Collectors.toSet()));
        for (int creditRow : List.of(5, 7)) {
            assertNull(rows.get(creditRow).entryType());
            assertEquals(ImportSourceDirection.CREDIT, rows.get(creditRow).sourceDirection());
            assertTrue(rows.get(creditRow).validationErrors().contains("REVIEW_REQUIRED"));
        }
        assertTrue(rows.values().stream().noneMatch(CandidateSummary::selectedForImport));
        assertTrue(rows.values().stream().noneMatch(CandidateSummary::duplicateAcknowledged));
        assertEquals(3, query.listReviewingBatches(owner.user(), PageRequest.of(0, 15))
                .getContent().getFirst().attentionRowCount(), "Attention count remains validation-errors only");
        assertEquals(factsBefore, candidateFacts(uploaded.id()));
        assertEquals(batchBefore, batchFacts(uploaded.id()));
        assertEquals(ledgerBefore, jsonRows("ledger_entry", "", null));
        System.out.printf("DUPLICATE_ROUND_TRIP_RESULT file=%s version=%d candidateCount=%d warningRows=%s attentionRows=3 selected=false%n",
                restored.fileName(), restored.version(), restored.candidates().getTotalElements(),
                rows.values().stream().filter(CandidateSummary::duplicateWarning).map(CandidateSummary::rowNumber).sorted().toList());
    }

    @Test void groupsAllThreeNonadjacentMembersAndKeepsAdjacentDifferentFactsSeparate() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        UUID first = row(owner, batch, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.0", " Store #12! ");
        row(owner, batch, 3, DATE.plusDays(1), "CAD", "DEBIT", "EXPENSE", "30.00", "Store #12!");
        row(owner, batch, 4, DATE, "CAD", "DEBIT", "EXPENSE", "31.00", "Store #12!");
        row(owner, batch, 5, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Store #13!");
        row(owner, batch, 6, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Store #12?");
        UUID second = row(owner, batch, 20, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "STORE\t#12!");
        UUID third = row(owner, batch, 40, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "store  #12!");
        scan(owner, batch);
        assertEquals(Set.of(first, second, third), warnedIds(batch));
        assertEquals(1, version(batch));
        List<String> signatures = jdbc.queryForList("SELECT duplicate_match_signature FROM " + table("import_candidate")
                + " WHERE batch_id=? AND duplicate_warning", String.class, batch);
        assertEquals(1, new HashSet<>(signatures).size());
        assertTrue(signatures.getFirst().matches("[a-f0-9]{64}"));
    }

    @Test void currencyDirectionAndNullableEntryTypeAreSeparateComparisonFacts() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        row(owner, batch, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Same merchant");
        row(owner, batch, 3, DATE, "USD", "DEBIT", "EXPENSE", "30.00", "Same merchant");
        row(owner, batch, 4, DATE, "CAD", "CREDIT", "EXPENSE", "30.00", "Same merchant");
        row(owner, batch, 5, DATE, "CAD", "DEBIT", "INCOME", "30.00", "Same merchant");
        row(owner, batch, 6, DATE, "CAD", "DEBIT", null, "30.00", "Same merchant");
        UUID credit1 = row(owner, batch, 7, DATE, "CAD", "CREDIT", null, "30.00", "Same merchant");
        UUID credit2 = row(owner, batch, 20, DATE, "CAD", "CREDIT", null, "30.00", "same merchant");
        scan(owner, batch);
        assertEquals(Set.of(credit1, credit2), warnedIds(batch));
    }

    @Test void missingComparisonFactsNeverMatchAndOldWarningsAreClearedWithoutChangingFacts() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        int number = 2;
        for (int missing = 0; missing < 6; missing++) {
            for (int copy = 0; copy < 2; copy++) {
                row(owner, batch, number++, missing == 0 ? null : DATE, missing == 1 ? null : "CAD",
                        missing == 2 ? null : "DEBIT", "EXPENSE", missing == 3 ? null : "30.00",
                        missing == 4 ? null : missing == 5 ? " \t " : "Merchant");
            }
        }
        jdbc.update("UPDATE " + table("import_candidate")
                + " SET validation_errors='[{\"code\":\"SYNTHETIC_EXISTING_ISSUE\"}]'::jsonb,"
                + " duplicate_warning=TRUE,duplicate_acknowledged=TRUE,duplicate_match_signature=? WHERE batch_id=?",
                "a".repeat(64), batch);
        var before = candidateFacts(batch);
        scan(owner, batch);
        assertTrue(warnedIds(batch).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + table("import_candidate")
                + " WHERE batch_id=? AND (duplicate_acknowledged OR duplicate_match_signature IS NOT NULL)", Integer.class, batch));
        assertEquals(before, candidateFacts(batch));
        assertEquals(1, version(batch));
    }

    @Test void compatibilityEntryNowMatchesOtherReviewingBatchesButNeverModifiesThemAndAllowsInactiveAccounts() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        row(owner, batch, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
        UUID otherBatch = batch(owner, ImportStatus.REVIEWING);
        repeated(owner, otherBatch, 2);
        Owner other = owner(); UUID foreign = batch(other, ImportStatus.REVIEWING);
        repeated(other, foreign, 2);
        jdbc.update("UPDATE " + table("financial_account") + " SET active=FALSE WHERE id=?", owner.account());
        var beforeOther = snapshotCandidates(otherBatch);
        var beforeForeign = snapshotCandidates(foreign);
        scan(owner, batch);
        assertEquals(1, warnedIds(batch).size());
        assertEquals(1, version(batch));
        assertEquals(beforeOther, snapshotCandidates(otherBatch));
        assertEquals(beforeForeign, snapshotCandidates(foreign));
    }

    @Test void nullArgumentsNotFoundAndNonReviewingStatesCannotWriteOrReadCandidates() {
        Owner owner = owner(); Owner other = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        repeated(owner, batch, 2);
        var before = snapshotCandidates(batch);
        sql.clear();
        assertThrows(InvalidImportRequestException.class, () -> service.refreshWithinBatchDuplicateWarnings(null, owner.account(), batch));
        assertThrows(InvalidImportRequestException.class, () -> service.refreshWithinBatchDuplicateWarnings(owner.user(), null, batch));
        assertThrows(InvalidImportRequestException.class, () -> service.refreshWithinBatchDuplicateWarnings(owner.user(), owner.account(), null));
        assertTrue(sql.all().isEmpty());
        var missing = assertThrows(ImportBatchNotFoundException.class,
                () -> service.refreshWithinBatchDuplicateWarnings(owner.user(), owner.account(), UUID.randomUUID()));
        var foreign = assertThrows(ImportBatchNotFoundException.class,
                () -> service.refreshWithinBatchDuplicateWarnings(other.user(), owner.account(), batch));
        var wrongAccount = assertThrows(ImportBatchNotFoundException.class,
                () -> service.refreshWithinBatchDuplicateWarnings(owner.user(), other.account(), batch));
        assertEquals(missing.getMessage(), foreign.getMessage());
        assertEquals(missing.getMessage(), wrongAccount.getMessage());
        for (ImportStatus status : List.of(ImportStatus.DRAFT, ImportStatus.CONFIRMED, ImportStatus.CANCELLED, ImportStatus.FAILED)) {
            UUID terminal = batch(owner, status);
            repeated(owner, terminal, 2);
            var terminalBefore = batchSnapshot(terminal);
            assertThrows(ImportBatchStateConflictException.class, () -> scan(owner, terminal));
            assertEquals(terminalBefore, batchSnapshot(terminal));
        }
        assertTrue(sql.all().stream().allMatch(s -> s.startsWith("select ")));
        assertTrue(sql.all().stream().noneMatch(s -> s.contains("import_candidate")));
        assertEquals(before, snapshotCandidates(batch));
        assertEquals(0, version(batch));
    }

    @Test void unchangedEvidencePreservesAcknowledgementsAndVersionWhileChangedGroupsResetThem() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        repeated(owner, batch, 2);
        scan(owner, batch);
        assertEquals(1, version(batch));
        String original = signature(batch);
        acknowledge(batch);
        var acknowledged = snapshotCandidates(batch);
        sql.clear();
        scan(owner, batch);
        assertEquals(acknowledged, snapshotCandidates(batch));
        assertEquals(1, version(batch));
        assertTrue(sql.all().stream().noneMatch(s -> s.startsWith("update ")));

        row(owner, batch, 10, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
        scan(owner, batch);
        assertEquals(2, version(batch));
        assertNotEquals(original, signature(batch));
        assertEquals(3, warnedIds(batch).size());
        assertNoAcknowledgements(batch);
        String membershipChanged = signature(batch);
        acknowledge(batch);
        jdbc.update("UPDATE " + table("import_candidate") + " SET description='Merchant #2' WHERE batch_id=?", batch);
        scan(owner, batch);
        assertEquals(3, version(batch));
        assertNotEquals(membershipChanged, signature(batch));
        assertNoAcknowledgements(batch);
        acknowledge(batch);
        jdbc.update("UPDATE " + table("import_candidate") + " SET description='Unique ' || row_number WHERE batch_id=?", batch);
        scan(owner, batch);
        assertEquals(4, version(batch));
        assertTrue(warnedIds(batch).isEmpty());
        assertNoAcknowledgements(batch);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + table("import_candidate")
                + " WHERE batch_id=? AND duplicate_match_signature IS NOT NULL", Integer.class, batch));
        scan(owner, batch);
        assertEquals(4, version(batch));
    }

    @Test void ordinaryRequiredTransactionRollsBackWarningsAndForcedParentVersionWithItsCaller() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        repeated(owner, batch, 2);
        var beforeRows = snapshotCandidates(batch);
        var beforeBatch = batchSnapshot(batch);
        var outer = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        outer.executeWithoutResult(status -> {
            scan(owner, batch);
            EntityManagerFactoryUtils.getTransactionalEntityManager(context.getBean(EntityManagerFactory.class)).flush();
            assertEquals(2, warnedIds(batch).size());
            assertEquals(1, version(batch));
            status.setRollbackOnly();
        });
        assertEquals(beforeRows, snapshotCandidates(batch));
        assertEquals(beforeBatch, batchSnapshot(batch));
    }

    @Test void realFailureAfterOneCandidateUpdateRollsBackAllFlagsAndParentVersion() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        repeated(owner, batch, 3);
        var beforeRows = snapshotCandidates(batch);
        var beforeBatch = batchSnapshot(batch);
        jdbc.execute("CREATE FUNCTION " + schema + ".fail_second_warning() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.batch_id='" + batch + "'::uuid AND NEW.duplicate_warning AND EXISTS(SELECT 1 FROM "
                + table("import_candidate") + " WHERE batch_id=NEW.batch_id AND duplicate_warning) THEN "
                + "RAISE EXCEPTION 'synthetic failure after one warning update' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_second_warning BEFORE UPDATE ON " + table("import_candidate")
                + " FOR EACH ROW EXECUTE FUNCTION " + schema + ".fail_second_warning()");
        try {
            var failure = assertThrows(RuntimeException.class, () -> scan(owner, batch));
            assertTrue(hasSqlState(failure, "23514"));
            assertEquals(beforeRows, snapshotCandidates(batch));
            assertEquals(beforeBatch, batchSnapshot(batch));
        } finally {
            jdbc.execute("DROP TRIGGER fail_second_warning ON " + table("import_candidate"));
            jdbc.execute("DROP FUNCTION " + schema + ".fail_second_warning()");
        }
        scan(owner, batch);
        assertEquals(3, warnedIds(batch).size());
        assertEquals(1, version(batch));
        System.out.println("DUPLICATE_ROLLBACK_RESULT sqlState=23514 residualWarningChanges=0 residualVersionChanges=0 retryVersion=1");
    }

    @Test void twoPostgresTransactionsSerializeOnTheBatchLockAndConvergeWithoutSecondVersionIncrement() throws Exception {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        repeated(owner, batch, 3);
        var executor = Executors.newFixedThreadPool(2);
        gate.arm(batch);
        try {
            var first = executor.submit(() -> scan(owner, batch));
            assertTrue(gate.firstLocked.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> scan(owner, batch));
            assertTrue(gate.secondAttempted.await(10, TimeUnit.SECONDS));
            assertNotEquals(gate.firstPid.get(), gate.secondPid.get());
            boolean blocked = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                blocked = Boolean.TRUE.equals(jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))",
                        Boolean.class, gate.firstPid.get(), gate.secondPid.get()));
                if (blocked) break;
                TimeUnit.MILLISECONDS.sleep(20);
            }
            assertTrue(blocked, "PostgreSQL must report the second session blocked by the first Batch lock");
            assertFalse(second.isDone());
            gate.releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertEquals(List.of(0L, 1L), List.of(gate.firstVersion, gate.secondVersion));
            assertEquals(1, version(batch));
            assertEquals(3, warnedIds(batch).size());
            assertEquals(1, new HashSet<>(jdbc.queryForList("SELECT duplicate_match_signature FROM "
                    + table("import_candidate") + " WHERE batch_id=?", String.class, batch)).size());
            System.out.printf("DUPLICATE_CONCURRENCY_RESULT sessions=%d,%d observedBlocking=true versionsRead=0,1 finalVersion=1 warningRows=3%n",
                    gate.firstPid.get(), gate.secondPid.get());
        } finally {
            gate.releaseFirst.countDown();
            gate.disarm();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void scanReadsTheBoundedFullBatchAndExternalUnionInThreeSelectsInsteadOfOneQueryPerRow() {
        Owner owner = owner(); UUID small = batch(owner, ImportStatus.REVIEWING);
        UUID larger = batch(owner, ImportStatus.REVIEWING);
        repeated(owner, small, 2);
        repeated(owner, larger, 40);
        sql.clear();
        scan(owner, small);
        assertEquals(3, sql.selects().size());
        sql.clear();
        scan(owner, larger);
        var selects = sql.selects();
        assertEquals(3, selects.size());
        assertTrue(selects.getFirst().contains("import_batch"));
        assertTrue(selects.getFirst().contains("for update") || selects.getFirst().contains("for no key update"), selects.getFirst());
        String batchWhere = whereClause(selects.getFirst());
        assertTrue(batchWhere.contains(".id=?"), batchWhere);
        assertTrue(batchWhere.contains(".user_id=?"), batchWhere);
        assertTrue(batchWhere.contains(".account_id=?"), batchWhere);
        String candidates = selects.get(1);
        assertTrue(candidates.contains("import_candidate"), candidates);
        assertTrue(whereClause(candidates).contains(".batch_id=?"), candidates);
        assertTrue(whereClause(candidates).contains(".user_id=?"), candidates);
        assertTrue(candidates.contains("fetch first ? rows only"), candidates);
        assertTrue(selects.get(2).contains("ledger_entry") && selects.get(2).contains("union all"), selects.get(2));
        assertFalse(selects.get(2).contains("for update") || selects.get(2).contains("for no key update"), selects.get(2));
        assertEquals(40, warnedIds(larger).size(), "The scan includes rows beyond a preview page of 15");
        assertEquals(1, version(larger));
        System.out.println("DUPLICATE_QUERY_RESULT candidates=2,40 selects=3,3 warningRows=40 batchVersion=1");
    }

    @Test void aLegacyBatchBeyondTheExistingUploadLimitIsRejectedWithoutPartialScanning() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        jdbc.update("INSERT INTO " + table("import_candidate")
                + " (id,batch_id,user_id,row_number,entry_type,amount,currency_code,transaction_date,description,source_direction)"
                + " SELECT md5(? || g::text)::uuid,?,?,g+1,'EXPENSE',30.00,'CAD',DATE '2026-11-01','Merchant','DEBIT'"
                + " FROM generate_series(1,?) AS g", batch.toString(), batch, owner.user(), CsvParser.DEFAULT_MAX_ROWS + 1);
        assertThrows(InvalidImportRequestException.class, () -> scan(owner, batch));
        assertTrue(warnedIds(batch).isEmpty());
        assertEquals(0, version(batch));
    }

    private record Owner(UUID user, UUID account) {}
    private static Owner owner() {
        UUID user = UUID.randomUUID(), account = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("app_user") + " (id,auth_subject,display_name) VALUES (?,?,?)",
                user, "duplicate-" + user, "Synthetic owner");
        jdbc.update("INSERT INTO " + table("financial_account") + " (id,user_id,name,account_type) VALUES (?,?,?,'CREDIT_CARD')",
                account, user, "Synthetic account");
        return new Owner(user, account);
    }
    private static UUID batch(Owner owner, ImportStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("import_batch")
                + " (id,user_id,account_id,upload_key,file_name,file_sha256,status,bank_type,confirmed_at,closed_at)"
                + " VALUES (?,?,?,?,'synthetic-duplicate.csv',? ,?,'RBC_VISA',"
                + " CASE WHEN ?='CONFIRMED' THEN now() ELSE NULL END,"
                + " CASE WHEN ? IN ('CONFIRMED','CANCELLED','FAILED') THEN now() ELSE NULL END)",
                id, owner.user(), owner.account(), UUID.randomUUID(), "a".repeat(64), status.name(), status.name(), status.name());
        return id;
    }
    private static UUID row(Owner owner, UUID batch, int rowNumber, LocalDate date, String currency,
                            String direction, String type, String amount, String description) {
        UUID id = UUID.randomUUID();
        String errors = "CREDIT".equals(direction) && type == null ? "[{\"code\":\"REVIEW_REQUIRED\"}]" : "[]";
        jdbc.update("INSERT INTO " + table("import_candidate")
                + " (id,batch_id,user_id,row_number,transaction_date,currency_code,source_direction,entry_type,amount,description,validation_errors)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))", id, batch, owner.user(), rowNumber, date,
                currency, direction, type, amount == null ? null : new BigDecimal(amount), description, errors);
        return id;
    }
    private static void repeated(Owner owner, UUID batch, int count) {
        for (int row = 2; row < count + 2; row++) row(owner, batch, row, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
    }
    private static void scan(Owner owner, UUID batch) {
        service.refreshWithinBatchDuplicateWarnings(owner.user(), owner.account(), batch);
    }
    private static String table(String name) { return schema + "." + name; }
    private static long version(UUID batch) {
        return jdbc.queryForObject("SELECT version FROM " + table("import_batch") + " WHERE id=?", Long.class, batch);
    }
    private static Set<UUID> warnedIds(UUID batch) {
        return new HashSet<>(jdbc.queryForList("SELECT id FROM " + table("import_candidate")
                + " WHERE batch_id=? AND duplicate_warning", UUID.class, batch));
    }
    private static void acknowledge(UUID batch) {
        // Simulate an already committed human decision; this task provides no editing endpoint.
        jdbc.update("UPDATE " + table("import_candidate") + " SET duplicate_acknowledged=TRUE WHERE batch_id=? AND duplicate_warning", batch);
    }
    private static void assertNoAcknowledgements(UUID batch) {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + table("import_candidate")
                + " WHERE batch_id=? AND duplicate_acknowledged", Integer.class, batch));
    }
    private static String signature(UUID batch) {
        return jdbc.queryForObject("SELECT min(duplicate_match_signature) FROM " + table("import_candidate") + " WHERE batch_id=?", String.class, batch);
    }
    private static List<Map<String, Object>> snapshotCandidates(UUID batch) {
        return jdbc.queryForList("SELECT * FROM " + table("import_candidate") + " WHERE batch_id=? ORDER BY id", batch);
    }
    private static Map<String, Object> batchSnapshot(UUID batch) {
        return jdbc.queryForMap("SELECT * FROM " + table("import_batch") + " WHERE id=?", batch);
    }
    private static List<String> candidateFacts(UUID batch) {
        return jsonRows("import_candidate", " - 'duplicate_warning' - 'duplicate_acknowledged' - 'duplicate_match_signature'", batch);
    }
    private static String batchFacts(UUID batch) {
        return jdbc.queryForObject("SELECT (to_jsonb(t) - 'version')::text FROM " + table("import_batch") + " t WHERE id=?", String.class, batch);
    }
    private static List<String> jsonRows(String name, String removal, UUID batch) {
        String query = "SELECT (to_jsonb(t)" + removal + ")::text FROM " + table(name) + " t"
                + (batch == null ? "" : " WHERE batch_id=?") + " ORDER BY id";
        return batch == null ? jdbc.queryForList(query, String.class) : jdbc.queryForList(query, String.class, batch);
    }
    private static boolean hasSqlState(Throwable failure, String state) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlFailure && state.equals(sqlFailure.getSQLState())) return true;
        }
        return false;
    }
    private static String whereClause(String sql) { return sql.substring(sql.indexOf(" where ") + 7); }

    static class LockGate {
        private volatile UUID batch;
        private final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger firstPid = new AtomicInteger();
        final AtomicInteger secondPid = new AtomicInteger();
        volatile long firstVersion = -1, secondVersion = -1;
        volatile CountDownLatch firstLocked, secondAttempted, releaseFirst;
        void arm(UUID batch) {
            attempts.set(0); firstPid.set(0); secondPid.set(0); firstVersion = -1; secondVersion = -1;
            firstLocked = new CountDownLatch(1); secondAttempted = new CountDownLatch(1); releaseFirst = new CountDownLatch(1);
            this.batch = batch;
        }
        void disarm() { batch = null; }
        int beforeLock(UUID id, EntityManagerFactory factory) {
            if (!id.equals(batch)) return 0;
            int attempt = attempts.incrementAndGet();
            var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(factory);
            assertNotNull(entityManager);
            int pid = ((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue();
            if (attempt == 1) firstPid.set(pid);
            if (attempt == 2) { secondPid.set(pid); secondAttempted.countDown(); }
            return attempt;
        }
        void afterLock(int attempt, Object result) throws InterruptedException {
            var value = ((Optional<?>) result).orElseThrow();
            long version = ((ImportBatch) value).getVersion();
            if (attempt == 1) {
                firstVersion = version; firstLocked.countDown();
                assertTrue(releaseFirst.await(15, TimeUnit.SECONDS), "Release the first Batch lock within the test deadline");
            }
            if (attempt == 2) secondVersion = version;
        }
    }
    static class SqlCapture implements StatementInspector {
        private final ConcurrentLinkedQueue<String> statements = new ConcurrentLinkedQueue<>();
        @Override public String inspect(String statement) {
            statements.add(statement.replaceAll("(?s)/\\*.*?\\*/", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip());
            return statement;
        }
        void clear() { statements.clear(); }
        List<String> all() { return List.copyOf(statements); }
        List<String> selects() { return all().stream().filter(s -> s.startsWith("select ")).toList(); }
    }
}
