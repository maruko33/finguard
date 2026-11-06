package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.*;
import com.finguard.transactionapi.finance.dto.*;
import com.finguard.transactionapi.finance.exception.*;
import com.finguard.transactionapi.finance.importing.*;
import com.finguard.transactionapi.finance.repository.*;
import com.finguard.transactionapi.finance.service.*;
import jakarta.persistence.EntityManagerFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.SAME_THREAD)
class ImportCrossSourceDuplicateIntegrationTest {
    private static final LocalDate DATE = LocalDate.of(2021, 2, 3);
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static ImportDuplicateWarningService service;
    private static ImportPreparationService preparation;
    private static ImportBatchQueryService query;
    private static SqlCapture sql;
    private static MutualLockGate gate;
    private static String schema;

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = ImportBatchRepository.class)
    @Import({ImportDuplicateWarningService.class, ImportPreparationService.class, ImportBatchQueryService.class})
    static class Config {
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
        @Bean SqlCapture sqlCapture() { return new SqlCapture(); }
        @Bean MutualLockGate lockGate() { return new MutualLockGate(); }
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
                MutualLockGate gate, EntityManagerFactory factory) {
            var proxy = new ProxyFactory(delegate);
            proxy.addAdvice((MethodInterceptor) invocation -> {
                Object result = invocation.proceed();
                if (invocation.getMethod().getName().equals("findOwnedForDuplicateScan")) {
                    gate.afterLock((UUID) invocation.getArguments()[0], factory);
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
        schema = "import_cross_duplicate_test_" + UUID.randomUUID().toString().replace("-", "");
        var flyway = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
        assertEquals(4, flyway.migrate().migrationsExecuted);
        flyway.validate();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.register(Config.class); context.refresh();
        service = context.getBean(ImportDuplicateWarningService.class);
        preparation = context.getBean(ImportPreparationService.class);
        query = context.getBean(ImportBatchQueryService.class);
        sql = context.getBean(SqlCapture.class); gate = context.getBean(MutualLockGate.class);
    }

    @AfterAll static void cleanup() {
        if (context != null) context.close();
        if (jdbc != null && schema != null) {
            assertTrue(schema.matches("import_cross_duplicate_test_[a-f0-9]{32}"));
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test void uploadScanAndNewContextPreviewRestoreAllThreeSourcesWithoutChangingFactsOrSelection() {
        Owner owner = owner();
        UUID external = batch(owner, "REVIEWING");
        UUID creditSource = row(owner, external, 2, DATE, "CAD", "CREDIT", null, "5.00", "Credit refund");
        UUID manual = ledger(owner, DATE, "CAD", "EXPENSE", "8.00", "Old manual shop", false);
        var externalBefore = rows(external); var ledgerBefore = ledgerRows(owner);
        String csv = "Transaction Date,Description 1,CAD$,USD$\n"
                + "2/3/2021,Coffee shop,-30.0,\n"
                + "2/3/2021,Old manual shop,-8.00,\n"
                + "2/3/2021, COFFEE  SHOP ,-30.00,\n"
                + "2/3/2021,Credit refund,5.00,\n"
                + "bad,Invalid date,-9.00,\n";
        var uploaded = preparation.prepareImport(owner.user(), owner.account(),
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), BankType.RBC_VISA,
                UUID.randomUUID(), "cross-source-synthetic.csv");
        var beforeFacts = facts(uploaded.id());
        service.refreshDuplicateWarnings(owner.user(), uploaded.id());
        var read = new TransactionTemplate(context.getBean(JpaTransactionManager.class)); read.setReadOnly(true);
        var restored = read.execute(status -> query.getImportPreview(owner.user(), uploaded.id(), PageRequest.of(0, 15)));
        assertNotNull(restored); assertEquals(1, restored.version());
        var byRow = restored.candidates().stream().collect(java.util.stream.Collectors.toMap(CandidateSummary::rowNumber, r -> r));
        assertEquals(Set.of(2, 3, 4, 5), byRow.values().stream().filter(CandidateSummary::duplicateWarning)
                .map(CandidateSummary::rowNumber).collect(java.util.stream.Collectors.toSet()));
        assertNull(byRow.get(5).entryType()); assertEquals(ImportSourceDirection.CREDIT, byRow.get(5).sourceDirection());
        assertTrue(byRow.get(5).validationErrors().contains("REVIEW_REQUIRED"));
        assertTrue(byRow.values().stream().noneMatch(CandidateSummary::selectedForImport));
        assertEquals(beforeFacts, facts(uploaded.id())); assertEquals(externalBefore, rows(external));
        assertEquals(ledgerBefore, ledgerRows(owner)); assertEquals(0, version(external));
        assertEquals(2, query.listReviewingBatches(owner.user(), PageRequest.of(0, 15)).stream()
                .filter(b -> b.id().equals(uploaded.id())).findFirst().orElseThrow().attentionRowCount());
        System.out.printf("CROSS_SOURCE_ROUND_TRIP target=%s sourceCandidate=%s manualLedger=%s rows=5 version=1 INTRA_BATCH=[2,4] OTHER_REVIEWING_BATCH=[5] LEDGER=[3] unselected=true attentionRows=2%n",
                uploaded.id(), creditSource, manual);
    }

    @Test void oldManualAndConfirmedImportedLedgerMatchWithoutBankProfileOrUploadTimeWindow() {
        Owner owner = owner(); UUID target = batch(owner, "REVIEWING");
        UUID manualTarget = row(owner, target, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Old manual");
        UUID importedTarget = row(owner, target, 3, DATE.minusYears(1), "CAD", "DEBIT", "EXPENSE", "20.00", "Imported");
        UUID manual = ledger(owner, DATE, "CAD", "EXPENSE", "30.00", " old MANUAL ", false);
        UUID confirmed = batch(owner, "CONFIRMED");
        row(owner, confirmed, 2, DATE.minusYears(1), "CAD", "DEBIT", "EXPENSE", "20.00", "Imported");
        UUID imported = ledger(owner, DATE.minusYears(1), "CAD", "EXPENSE", "20.00", "Imported", false);
        jdbc.update("UPDATE " + table("ledger_entry") + " SET source_batch_id=?,source_row_number=2 WHERE id=?", confirmed, imported);
        jdbc.update("UPDATE " + table("import_batch") + " SET bank_type=NULL,created_at=TIMESTAMPTZ '2026-11-01 00:00:00Z' WHERE id=?", target);
        service.refreshDuplicateWarnings(owner.user(), target);
        assertEquals(Set.of(manualTarget, importedTarget), warned(target));
        assertNull(jdbc.queryForObject("SELECT source_batch_id FROM " + table("ledger_entry") + " WHERE id=?", UUID.class, manual));
        var original = signature(importedTarget); acknowledge(target);
        jdbc.update("UPDATE " + table("import_candidate") + " SET description='changed confirmed staging row' WHERE batch_id=?", confirmed);
        service.refreshDuplicateWarnings(owner.user(), target);
        assertEquals(original, signature(importedTarget)); assertTrue(ack(importedTarget)); assertEquals(1, version(target));
    }

    @Test void sourceQueriesIsolateOwnersAccountsStatusesAndDeletedLedgerRows() {
        Owner owner = owner(), foreign = owner(), secondAccount = account(owner.user());
        UUID target = batch(owner, "REVIEWING");
        row(owner, target, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
        UUID otherAccountBatch = batch(secondAccount, "REVIEWING"), foreignBatch = batch(foreign, "REVIEWING");
        row(secondAccount, otherAccountBatch, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
        row(foreign, foreignBatch, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
        ledger(secondAccount, DATE, "CAD", "EXPENSE", "30.00", "Merchant", false);
        ledger(foreign, DATE, "CAD", "EXPENSE", "30.00", "Merchant", false);
        ledger(owner, DATE, "CAD", "EXPENSE", "30.00", "Merchant", true);
        for (String status : List.of("DRAFT", "CONFIRMED", "CANCELLED", "FAILED")) {
            UUID excluded = batch(owner, status);
            row(owner, excluded, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
        }
        var foreignBefore = rows(foreignBatch); var otherBefore = rows(otherAccountBatch);
        sql.clear(); service.refreshDuplicateWarnings(owner.user(), target);
        assertTrue(warned(target).isEmpty()); assertEquals(0, version(target));
        assertEquals(foreignBefore, rows(foreignBatch)); assertEquals(otherBefore, rows(otherAccountBatch));
        String union = sql.selects().stream().filter(s -> s.contains("union all")).findFirst().orElseThrow();
        assertTrue(union.contains("c.user_id = ?") && union.contains("b.user_id = ?") && union.contains("b.account_id = ?"), union);
        assertTrue(union.contains("l.user_id = ?") && union.contains("l.account_id = ?") && union.contains("l.deleted_at is null"), union);
        assertTrue(union.contains("b.status = 'reviewing'") && union.contains("b.id <> ?"), union);
    }

    @Test void datesCurrenciesAmountsDescriptionsDirectionsAndTypesNeedAnExactMatch() {
        Owner owner = owner(); UUID target = batch(owner, "REVIEWING"), external = batch(owner, "REVIEWING");
        UUID id = row(owner, target, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Store #12!");
        row(owner, external, 2, DATE.plusDays(1), "CAD", "DEBIT", "EXPENSE", "30.00", "Store #12!");
        row(owner, external, 3, DATE, "USD", "DEBIT", "EXPENSE", "30.00", "Store #12!");
        row(owner, external, 4, DATE, "CAD", "CREDIT", "EXPENSE", "30.00", "Store #12!");
        row(owner, external, 5, DATE, "CAD", "DEBIT", "INCOME", "30.00", "Store #12!");
        row(owner, external, 6, DATE, "CAD", "DEBIT", "EXPENSE", "30.01", "Store #12!");
        row(owner, external, 7, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Store #13!");
        row(owner, external, 8, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Store #12?");
        row(owner, external, 9, DATE, "CAD", null, "EXPENSE", "30.00", "Store #12!");
        ledger(owner, DATE.plusDays(1), "CAD", "EXPENSE", "30.00", "Store #12!", false);
        ledger(owner, DATE, "USD", "EXPENSE", "30.00", "Store #12!", false);
        ledger(owner, DATE, "CAD", "INCOME", "30.00", "Store #12!", false);
        ledger(owner, DATE, "CAD", "EXPENSE", "30.01", "Store #12!", false);
        ledger(owner, DATE, "CAD", "EXPENSE", "30.00", "Store #13!", false);
        var before = rows(target);
        service.refreshDuplicateWarnings(owner.user(), target);
        assertEquals(before, rows(target)); assertEquals(0, version(target)); assertFalse(warned(target).contains(id));
    }

    @Test void unknownCreditDoesNotBecomeIncomeAndUnknownDirectionCanOnlyUseExplicitLedgerFacts() {
        Owner owner = owner(); UUID target = batch(owner, "REVIEWING"), external = batch(owner, "REVIEWING");
        UUID credit = row(owner, target, 2, DATE, "CAD", "CREDIT", null, "5.00", "Credit");
        UUID unknownDirection = row(owner, target, 3, DATE, "CAD", null, "EXPENSE", "8.00", "Legacy explicit expense");
        UUID unknownBoth = row(owner, target, 4, DATE, "CAD", null, null, "9.00", "Unknown facts");
        ledger(owner, DATE, "CAD", "INCOME", "5.00", "Credit", false);
        ledger(owner, DATE, "CAD", "EXPENSE", "8.00", "Legacy explicit expense", false);
        ledger(owner, DATE, "CAD", "INCOME", "9.00", "Unknown facts", false);
        row(owner, external, 2, DATE, "CAD", null, "EXPENSE", "8.00", "Legacy explicit expense");
        row(owner, external, 3, DATE, "CAD", null, null, "9.00", "Unknown facts");
        var before = facts(target);
        service.refreshDuplicateWarnings(owner.user(), target);
        assertEquals(Set.of(unknownDirection), warned(target)); assertEquals(before, facts(target));
        String original = signature(unknownDirection); acknowledge(target);
        jdbc.update("UPDATE " + table("import_candidate") + " SET description='irrelevant unknown direction' WHERE batch_id=?", external);
        service.refreshDuplicateWarnings(owner.user(), target);
        assertEquals(original, signature(unknownDirection)); assertTrue(ack(unknownDirection));
        UUID properCredit = row(owner, external, 10, DATE, "CAD", "CREDIT", null, "5.00", " CREDIT ");
        service.refreshDuplicateWarnings(owner.user(), target);
        assertEquals(Set.of(unknownDirection, credit), warned(target)); assertFalse(warned(target).contains(unknownBoth));
        assertEquals(before, facts(target)); assertFalse(ack(credit)); assertFalse(warned(external).contains(properCredit));
    }

    @Test void mergedEvidenceInvalidatesAcknowledgementOnlyWhenItsCompleteFactsOrMembershipChange() {
        Owner owner = owner(); UUID target = batch(owner, "REVIEWING"), external = batch(owner, "REVIEWING");
        UUID first = row(owner, target, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.0", "Merchant");
        row(owner, target, 20, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", " merchant ");
        service.refreshDuplicateWarnings(owner.user(), target);
        String intraOnly = signature(first); assertEquals(1, version(target)); acknowledge(target);
        UUID source = row(owner, external, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
        UUID ledger = ledger(owner, DATE, "CAD", "EXPENSE", "30.00", "Merchant", false);
        service.refreshDuplicateWarnings(owner.user(), target);
        String allSources = signature(first); assertNotEquals(intraOnly, allSources); assertEquals(2, version(target)); assertFalse(ack(first));
        acknowledge(target);
        jdbc.update("UPDATE " + table("import_candidate") + " SET duplicate_warning=TRUE,duplicate_acknowledged=TRUE,duplicate_match_signature=? WHERE id=?", "b".repeat(64), source);
        jdbc.update("UPDATE " + table("import_batch") + " SET version=version+1 WHERE id=?", external);
        var externalBefore = rows(external); var externalBatchBefore = batchSnapshot(external); var ledgerBefore = ledgerRows(owner);
        sql.clear(); service.refreshDuplicateWarnings(owner.user(), target);
        assertTrue(ack(first)); assertEquals(allSources, signature(first)); assertEquals(2, version(target));
        assertTrue(sql.all().stream().noneMatch(s -> s.startsWith("update ")), sql.all().toString());
        assertEquals(externalBefore, rows(external)); assertEquals(externalBatchBefore, batchSnapshot(external)); assertEquals(ledgerBefore, ledgerRows(owner));
        jdbc.update("UPDATE " + table("ledger_entry") + " SET deleted_at=now() WHERE id=?", ledger);
        service.refreshDuplicateWarnings(owner.user(), target);
        assertEquals(2, warned(target).size()); assertFalse(ack(first)); assertNotEquals(allSources, signature(first)); assertEquals(3, version(target));
        acknowledge(target);
        jdbc.update("UPDATE " + table("import_candidate") + " SET description='No longer matching' WHERE id=?", source);
        service.refreshDuplicateWarnings(owner.user(), target);
        assertEquals(intraOnly, signature(first)); assertFalse(ack(first)); assertEquals(4, version(target));
        acknowledge(target); sql.clear(); service.refreshDuplicateWarnings(owner.user(), target);
        assertTrue(ack(first)); assertEquals(4, version(target)); assertTrue(sql.all().stream().noneMatch(s -> s.startsWith("update ")));
    }

    @Test void externalOnlyWarningClearsWhenReviewingSourceClosesAndCompatibilityEntryUsesUnifiedScope() {
        Owner owner = owner(); UUID target = batch(owner, "REVIEWING"), external = batch(owner, "REVIEWING");
        UUID candidate = row(owner, target, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
        row(owner, external, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
        service.refreshDuplicateWarnings(owner.user(), target); assertEquals(Set.of(candidate), warned(target)); acknowledge(target);
        String original = signature(candidate);
        service.refreshWithinBatchDuplicateWarnings(owner.user(), owner.account(), target);
        assertTrue(ack(candidate)); assertEquals(original, signature(candidate)); assertEquals(1, version(target));
        jdbc.update("UPDATE " + table("import_batch") + " SET status='CANCELLED',closed_at=now() WHERE id=?", external);
        service.refreshDuplicateWarnings(owner.user(), target);
        assertTrue(warned(target).isEmpty()); assertNull(signature(candidate)); assertFalse(ack(candidate)); assertEquals(2, version(target));
    }

    @Test void unifiedEntryValidatesOwnershipAndStateBeforeReadingCandidatesAndAllowsInactiveAccount() {
        Owner owner = owner(), foreign = owner(); UUID target = batch(owner, "REVIEWING");
        UUID candidate = row(owner, target, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
        ledger(owner, DATE, "CAD", "EXPENSE", "30.00", "Merchant", false);
        sql.clear();
        assertThrows(InvalidImportRequestException.class, () -> service.refreshDuplicateWarnings(null, target));
        assertThrows(InvalidImportRequestException.class, () -> service.refreshDuplicateWarnings(owner.user(), null));
        assertTrue(sql.all().isEmpty());
        var foreignFailure = assertThrows(ImportBatchNotFoundException.class, () -> service.refreshDuplicateWarnings(foreign.user(), target));
        var absentFailure = assertThrows(ImportBatchNotFoundException.class, () -> service.refreshDuplicateWarnings(owner.user(), UUID.randomUUID()));
        assertEquals(foreignFailure.getMessage(), absentFailure.getMessage());
        for (String state : List.of("DRAFT", "CONFIRMED", "FAILED", "CANCELLED")) {
            UUID terminal = batch(owner, state); var before = batchSnapshot(terminal);
            assertThrows(ImportBatchStateConflictException.class, () -> service.refreshDuplicateWarnings(owner.user(), terminal));
            assertEquals(before, batchSnapshot(terminal));
        }
        assertTrue(sql.all().stream().noneMatch(s -> s.contains("import_candidate") || s.startsWith("update ")));
        jdbc.update("UPDATE " + table("financial_account") + " SET active=FALSE WHERE id=?", owner.account());
        service.refreshDuplicateWarnings(owner.user(), target);
        assertEquals(Set.of(candidate), warned(target)); assertEquals(1, version(target));
    }

    @Test void twoMutuallySourcingBatchesHoldOnlyTheirOwnLocksAndConvergeWithoutDeadlock() throws Exception {
        Owner owner = owner(); UUID first = batch(owner, "REVIEWING"), second = batch(owner, "REVIEWING");
        UUID firstRow = row(owner, first, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
        UUID secondRow = row(owner, second, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
        var firstFacts = facts(first); var secondFacts = facts(second);
        gate.arm(first, second); sql.clear();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> service.refreshDuplicateWarnings(owner.user(), first));
            var b = executor.submit(() -> service.refreshDuplicateWarnings(owner.user(), second));
            a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
            assertEquals(2, new HashSet<>(gate.pids.values()).size());
            assertEquals(Set.of(firstRow), warned(first)); assertEquals(Set.of(secondRow), warned(second));
            assertEquals(1, version(first)); assertEquals(1, version(second));
            assertEquals(firstFacts, facts(first)); assertEquals(secondFacts, facts(second));
            var unions = sql.selects().stream().filter(s -> s.contains("union all")).toList();
            assertEquals(2, unions.size());
            assertTrue(unions.stream().noneMatch(s -> s.contains("for update") || s.contains("for no key update") || s.contains("for share")));
            gate.disarm(); acknowledge(first); acknowledge(second); sql.clear();
            service.refreshDuplicateWarnings(owner.user(), first); service.refreshDuplicateWarnings(owner.user(), second);
            assertTrue(ack(firstRow)); assertTrue(ack(secondRow)); assertEquals(1, version(first)); assertEquals(1, version(second));
            assertTrue(sql.all().stream().noneMatch(s -> s.startsWith("update ")));
            System.out.printf("CROSS_SOURCE_CONCURRENCY sessions=%s bothOwnLocksHeld=true externalRowLocks=false finalVersions=1,1 rescansNoUpdates=true%n", gate.pids.values());
        } finally {
            gate.disarm(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void externalEvidenceChangesJoinTheCallersRollbackInsteadOfCommittingIndependently() {
        Owner owner = owner(); UUID target = batch(owner, "REVIEWING");
        row(owner, target, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
        ledger(owner, DATE, "CAD", "EXPENSE", "30.00", "Merchant", false);
        service.refreshDuplicateWarnings(owner.user(), target); acknowledge(target);
        var beforeRows = rows(target); var beforeBatch = batchSnapshot(target);
        ledger(owner, DATE, "CAD", "EXPENSE", "30.00", "Merchant", false);
        var outer = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        outer.executeWithoutResult(status -> {
            service.refreshDuplicateWarnings(owner.user(), target);
            EntityManagerFactoryUtils.getTransactionalEntityManager(context.getBean(EntityManagerFactory.class)).flush();
            assertEquals(2, version(target)); status.setRollbackOnly();
        });
        assertEquals(beforeRows, rows(target)); assertEquals(beforeBatch, batchSnapshot(target));
    }

    @Test void overflowingExternalSourceProbeFailsWithoutPartiallyClearingPreviousEvidence() {
        Owner owner = owner(); UUID target = batch(owner, "REVIEWING");
        row(owner, target, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
        row(owner, target, 20, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
        service.refreshDuplicateWarnings(owner.user(), target); acknowledge(target);
        var beforeRows = rows(target); var beforeBatch = batchSnapshot(target);
        bulkLedger(owner, 20_001, DATE, "Merchant", "overflow");
        sql.clear();
        assertThrows(InvalidImportRequestException.class, () -> service.refreshDuplicateWarnings(owner.user(), target));
        assertEquals(beforeRows, rows(target)); assertEquals(beforeBatch, batchSnapshot(target));
        assertTrue(sql.all().stream().noneMatch(s -> s.startsWith("update ")));
        System.out.println("CROSS_SOURCE_LIMIT sourceRows=20001 rejected=true previousWarningsAndAcknowledgementsAndVersionPreserved=true");
    }

    @Test void boundedUnionUsesOneSnapshotAndProducesARealPlanForFilteredSyntheticHistory() {
        Owner owner = owner(); UUID target = batch(owner, "REVIEWING"), external = batch(owner, "REVIEWING");
        UUID candidate = row(owner, target, 2, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant");
        jdbc.update("INSERT INTO " + table("import_candidate")
                + " (id,batch_id,user_id,row_number,transaction_date,currency_code,source_direction,entry_type,amount,description)"
                + " SELECT md5(? || g::text)::uuid,?,?,g+1,?,'CAD','DEBIT','EXPENSE',30.00,'Merchant' FROM generate_series(1,400) g",
                external.toString(), external, owner.user(), DATE);
        bulkLedger(owner, 800, DATE, "Merchant", "matching");
        bulkLedger(owner, 5_000, DATE.plusYears(3), "Merchant", "irrelevant-date");
        jdbc.execute("ANALYZE " + table("import_batch")); jdbc.execute("ANALYZE " + table("import_candidate"));
        jdbc.execute("ANALYZE " + table("ledger_entry"));
        sql.clear(); service.refreshDuplicateWarnings(owner.user(), target);
        assertEquals(Set.of(candidate), warned(target)); assertEquals(3, sql.selects().size());
        String captured = sql.selects().stream().filter(s -> s.contains("union all")).findFirst().orElseThrow();
        assertTrue(captured.contains("transaction_date in") && captured.contains("amount in") && captured.contains("currency_code in"), captured);
        assertFalse(captured.contains("created_at") || captured.contains("for update") || captured.contains("for no key update"), captured);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("userId", owner.user()); parameters.put("accountId", owner.account()); parameters.put("batchId", target);
        parameters.put("dates", List.of(DATE)); parameters.put("currencies", List.of("CAD"));
        parameters.put("amounts", List.of(new BigDecimal("30.00"))); parameters.put("directions", List.of("DEBIT"));
        parameters.put("types", List.of("EXPENSE")); parameters.put("includeUnknownType", false);
        parameters.put("includeLedger", true); parameters.put("sourceLimit", 20_001);
        String nativeSql = ImportCandidateRepository.EXTERNAL_DUPLICATE_SOURCES_SQL.replace("{h-schema}", schema + ".");
        var named = new NamedParameterJdbcTemplate(jdbc);
        var sources = named.queryForList(nativeSql, parameters);
        assertEquals(1_200, sources.size());
        assertEquals(400, sources.stream().filter(s -> "OTHER_REVIEWING_BATCH".equals(s.get("sourceKind"))).count());
        assertEquals(800, sources.stream().filter(s -> "LEDGER".equals(s.get("sourceKind"))).count());
        String plan = String.join("\n", named.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + nativeSql, parameters, String.class));
        assertTrue(plan.contains("actual time=") && plan.contains("Buffers:") && plan.contains("Execution Time:"), plan);
        System.out.println("CROSS_SOURCE_QUERY candidates=1 syntheticCandidateSources=400 syntheticLedgerSources=800 offDateLedgerRows=5000 returnedSources=1200 selects=3");
        System.out.println("CROSS_SOURCE_EXPLAIN_BEGIN\n" + plan + "\nCROSS_SOURCE_EXPLAIN_END");
    }

    private record Owner(UUID user, UUID account) {}
    private static Owner owner() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("app_user") + " (id,auth_subject,display_name) VALUES (?,?,?)",
                user, "cross-duplicate-" + user, "Synthetic owner");
        return account(user);
    }
    private static Owner account(UUID user) {
        UUID account = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("financial_account") + " (id,user_id,name,account_type) VALUES (?,?,?,'CREDIT_CARD')",
                account, user, "Synthetic " + account);
        return new Owner(user, account);
    }
    private static UUID batch(Owner owner, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("import_batch")
                + " (id,user_id,account_id,upload_key,file_name,file_sha256,status,bank_type,confirmed_at,closed_at)"
                + " VALUES (?,?,?,?,'synthetic-cross-duplicate.csv',?,?,'RBC_VISA',"
                + " CASE WHEN ?='CONFIRMED' THEN now() ELSE NULL END,"
                + " CASE WHEN ? IN ('CONFIRMED','CANCELLED','FAILED') THEN now() ELSE NULL END)",
                id, owner.user(), owner.account(), UUID.randomUUID(), "a".repeat(64), status, status, status);
        return id;
    }
    private static UUID row(Owner owner, UUID batch, int row, LocalDate date, String currency,
                            String direction, String type, String amount, String description) {
        UUID id = UUID.randomUUID();
        String issues = "CREDIT".equals(direction) && type == null ? "[{\"code\":\"REVIEW_REQUIRED\"}]" : "[]";
        jdbc.update("INSERT INTO " + table("import_candidate")
                + " (id,batch_id,user_id,row_number,transaction_date,currency_code,source_direction,entry_type,amount,description,validation_errors)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))", id, batch, owner.user(), row, date, currency,
                direction, type, amount == null ? null : new BigDecimal(amount), description, issues);
        return id;
    }
    private static UUID ledger(Owner owner, LocalDate date, String currency, String type, String amount, String description, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("ledger_entry")
                + " (id,user_id,account_id,transaction_date,currency_code,entry_type,amount,description,deleted_at)"
                + " VALUES (?,?,?,?,?,?,?,?,CASE WHEN ? THEN now() ELSE NULL END)",
                id, owner.user(), owner.account(), date, currency, type, new BigDecimal(amount), description, deleted);
        return id;
    }
    private static void bulkLedger(Owner owner, int count, LocalDate date, String description, String salt) {
        jdbc.update("INSERT INTO " + table("ledger_entry")
                + " (id,user_id,account_id,transaction_date,currency_code,entry_type,amount,description)"
                + " SELECT md5(? || g::text)::uuid,?,?,?,'CAD','EXPENSE',30.00,? FROM generate_series(1,?) g",
                owner.account() + salt, owner.user(), owner.account(), date, description, count);
    }
    private static String table(String name) { return schema + "." + name; }
    private static long version(UUID batch) {
        return jdbc.queryForObject("SELECT version FROM " + table("import_batch") + " WHERE id=?", Long.class, batch);
    }
    private static Set<UUID> warned(UUID batch) {
        return new HashSet<>(jdbc.queryForList("SELECT id FROM " + table("import_candidate")
                + " WHERE batch_id=? AND duplicate_warning", UUID.class, batch));
    }
    private static void acknowledge(UUID batch) {
        jdbc.update("UPDATE " + table("import_candidate") + " SET duplicate_acknowledged=TRUE WHERE batch_id=? AND duplicate_warning", batch);
    }
    private static boolean ack(UUID candidate) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT duplicate_acknowledged FROM " + table("import_candidate") + " WHERE id=?", Boolean.class, candidate));
    }
    private static String signature(UUID candidate) {
        return jdbc.queryForObject("SELECT duplicate_match_signature FROM " + table("import_candidate") + " WHERE id=?", String.class, candidate);
    }
    private static List<Map<String, Object>> rows(UUID batch) {
        return jdbc.queryForList("SELECT * FROM " + table("import_candidate") + " WHERE batch_id=? ORDER BY id", batch);
    }
    private static Map<String, Object> batchSnapshot(UUID batch) {
        return jdbc.queryForMap("SELECT * FROM " + table("import_batch") + " WHERE id=?", batch);
    }
    private static List<String> facts(UUID batch) {
        return jdbc.queryForList("SELECT (to_jsonb(c) - 'duplicate_warning' - 'duplicate_acknowledged' - 'duplicate_match_signature')::text FROM "
                + table("import_candidate") + " c WHERE batch_id=? ORDER BY id", String.class, batch);
    }
    private static List<Map<String, Object>> ledgerRows(Owner owner) {
        return jdbc.queryForList("SELECT * FROM " + table("ledger_entry") + " WHERE user_id=? AND account_id=? ORDER BY id", owner.user(), owner.account());
    }

    static class MutualLockGate {
        private volatile Set<UUID> batches = Set.of();
        private volatile CyclicBarrier bothLocked;
        final Map<UUID, Integer> pids = new ConcurrentHashMap<>();
        void arm(UUID first, UUID second) {
            pids.clear(); bothLocked = new CyclicBarrier(2); batches = Set.of(first, second);
        }
        void disarm() { batches = Set.of(); }
        void afterLock(UUID batch, EntityManagerFactory factory) throws Exception {
            if (!batches.contains(batch)) return;
            var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(factory);
            assertNotNull(manager);
            pids.put(batch, ((Number) manager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue());
            manager.createNativeQuery("SET LOCAL lock_timeout='5s'").executeUpdate();
            bothLocked.await(10, TimeUnit.SECONDS);
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
