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
class ImportReviewIntegrationTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 1);
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static ImportReviewService service;
    private static ImportDuplicateWarningService duplicates;
    private static ImportPreparationService preparation;
    private static ImportBatchQueryService query;
    private static SqlCapture sql;
    private static LockGate gate;
    private static String schema;

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = ImportBatchRepository.class)
    @Import({ImportReviewService.class, ImportDuplicateWarningService.class, ImportPreparationService.class, ImportBatchQueryService.class})
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
        schema = "import_review_test_" + UUID.randomUUID().toString().replace("-", "");
        var flyway = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
        assertEquals(4, flyway.migrate().migrationsExecuted);
        flyway.validate();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.register(Config.class);
        context.refresh();
        service = context.getBean(ImportReviewService.class);
        duplicates = context.getBean(ImportDuplicateWarningService.class);
        preparation = context.getBean(ImportPreparationService.class);
        query = context.getBean(ImportBatchQueryService.class);
        sql = context.getBean(SqlCapture.class);
        gate = context.getBean(LockGate.class);
    }

    @AfterAll static void cleanup() {
        if (context != null) context.close();
        if (jdbc != null && schema != null) {
            assertTrue(schema.matches("import_review_test_[a-f0-9]{32}"));
            // Remove only this run's isolated fixture schema.
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }


    @Test void prepareScanReviewAndNewContextPreviewPreserveFactsWhileRestoringManualDecisions() {
        Owner owner = owner(); UUID category = category(owner, "EXPENSE", true);
        String csv = "Transaction Date,Description 1,CAD$,USD$\n"
                + "11/1/2026,Coffee shop,-30.0,\n"
                + "11/1/2026,Bus fare,-3.00,\n"
                + "11/1/2026, COFFEE  SHOP ,-30.00,\n"
                + "bad,Invalid date,-8.25,\n"
                + "11/1/2026,Credit refund,5.00,\n";
        var uploaded = preparation.prepareImport(owner.user(), owner.account(),
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), BankType.RBC_VISA,
                UUID.randomUUID(), "manual-review-synthetic.csv");
        duplicates.refreshDuplicateWarnings(owner.user(), uploaded.id());
        var preview = preview(owner, uploaded.id());
        assertEquals(1, preview.version());
        var byRow = preview.candidates().stream().collect(java.util.stream.Collectors.toMap(CandidateSummary::rowNumber, r -> r));
        String signature = byRow.get(2).duplicateMatchSignature();
        assertNotNull(signature); assertEquals(signature, byRow.get(4).duplicateMatchSignature());
        var unchanged = candidateSnapshot(byRow.get(4).candidateId());
        var beforeFacts = immutableFacts(uploaded.id()); var beforeBatch = immutableBatch(uploaded.id());
        var ledgerBefore = ledgerRows(owner);
        var saved = service.saveReview(owner.user(), uploaded.id(), request(preview.version(),
                decision(byRow.get(2).candidateId(), true, category, true, signature),
                decision(byRow.get(3).candidateId(), true, category, false, null),
                decision(byRow.get(5).candidateId(), false, null, false, null)));
        assertEquals(uploaded.id(), saved.batchId()); assertEquals(ImportStatus.REVIEWING, saved.status());
        assertEquals(2, saved.version()); assertEquals(2, saved.changedCandidateCount());
        var read = new TransactionTemplate(context.getBean(JpaTransactionManager.class)); read.setReadOnly(true);
        var restored = read.execute(status -> preview(owner, uploaded.id()));
        assertNotNull(restored); assertEquals(2, restored.version());
        var restoredRows = restored.candidates().stream().collect(java.util.stream.Collectors.toMap(CandidateSummary::rowNumber, r -> r));
        assertTrue(restoredRows.get(2).selectedForImport()); assertTrue(restoredRows.get(2).duplicateAcknowledged());
        assertTrue(restoredRows.get(2).duplicateWarning()); assertEquals(signature, restoredRows.get(2).duplicateMatchSignature());
        assertEquals(category, restoredRows.get(2).categoryId()); assertEquals(category, restoredRows.get(3).categoryId());
        assertTrue(restoredRows.get(3).selectedForImport()); assertFalse(restoredRows.get(5).selectedForImport());
        assertTrue(restoredRows.get(5).validationErrors().contains("INVALID_DATE"));
        assertNull(restoredRows.get(6).entryType()); assertTrue(restoredRows.get(6).validationErrors().contains("REVIEW_REQUIRED"));
        assertEquals(unchanged, candidateSnapshot(byRow.get(4).candidateId()));
        assertEquals(beforeFacts, immutableFacts(uploaded.id())); assertEquals(beforeBatch, immutableBatch(uploaded.id()));
        assertEquals(ledgerBefore, ledgerRows(owner));
        assertEquals(2, query.listReviewingBatches(owner.user(), PageRequest.of(0, 15)).getContent().getFirst().attentionRowCount());
        System.out.printf("IMPORT_REVIEW_ROUND_TRIP batch=%s category=%s version=1->2 changed=2 status=REVIEWING selectedRows=[2,3] acknowledgedRows=[2] warningRows=[2,4] invalidRow5Unselected=true creditRow6Type=null ledgerUnchanged=true%n", uploaded.id(), category);
    }

    @Test void invalidSelectionCannotBeOverriddenByCategoryOrAcknowledgementAndExclusionRetainsIssues() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING); UUID category = category(owner, "EXPENSE", true);
        UUID badDate = row(owner, batch, 2, null, "CAD", "DEBIT", "EXPENSE", "30.00", "Bad date", "[{\"code\":\"INVALID_DATE\"}]");
        UUID badAmount = row(owner, batch, 3, DATE, "CAD", "DEBIT", "EXPENSE", null, "Bad amount", "[{\"code\":\"INVALID_AMOUNT\"}]");
        UUID credit = row(owner, batch, 4, DATE, "CAD", "CREDIT", null, "5.00", "Credit", "[{\"code\":\"REVIEW_REQUIRED\"}]");
        UUID currency = row(owner, batch, 5, DATE, "USD", "DEBIT", "EXPENSE", "30.00", "Currency", "[]");
        UUID blank = row(owner, batch, 6, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", " \t ", "[]");
        UUID missingDateWithoutIssue = row(owner, batch, 7, null, "CAD", "DEBIT", "EXPENSE", "30.00", "Missing date", "[]");
        UUID invalidIso = row(owner, batch, 8, DATE, "ZZZ", "DEBIT", "EXPENSE", "30.00", "Invalid currency", "[]");
        jdbc.update("UPDATE " + table("import_candidate") + " SET duplicate_warning=TRUE,duplicate_match_signature=? WHERE batch_id=?", "a".repeat(64), batch);
        var beforeRows = rows(batch); var beforeBatch = batchSnapshot(batch);
        for (UUID id : List.of(badDate, badAmount, credit, currency, blank, missingDateWithoutIssue, invalidIso)) {
            assertThrows(InvalidImportRequestException.class, () -> service.saveReview(owner.user(), batch,
                    request(0, decision(id, true, category, true, "a".repeat(64)))));
            assertEquals(beforeRows, rows(batch)); assertEquals(beforeBatch, batchSnapshot(batch));
        }
        jdbc.update("UPDATE " + table("import_candidate") + " SET category_id=? WHERE id=?", category, badDate);
        var facts = immutableFacts(batch);
        var result = service.saveReview(owner.user(), batch, request(0, decision(badDate, false, null, false, null)));
        assertEquals(1, result.changedCandidateCount()); assertEquals(1, result.version());
        assertEquals(facts, immutableFacts(batch)); assertNull(candidateSnapshot(badDate).get("category_id"));
        assertTrue(candidateSnapshot(badDate).get("validation_errors").toString().contains("INVALID_DATE"));
    }

    @Test void categoriesAreOwnerTypeAndActivityCheckedButUnchangedInactiveCategoryDoesNotBlockExclusion() {
        Owner owner = owner(), other = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        UUID row = validRow(owner, batch, 2); UUID active = category(owner, "EXPENSE", true);
        UUID inactive = category(owner, "EXPENSE", false), income = category(owner, "INCOME", true), foreign = category(other, "EXPENSE", true);
        var before = rows(batch);
        for (UUID invalidCategory : List.of(inactive, income, foreign, UUID.randomUUID())) {
            assertThrows(InvalidImportRequestException.class, () -> service.saveReview(owner.user(), batch,
                    request(0, decision(row, false, invalidCategory, false, null))));
            assertEquals(before, rows(batch)); assertEquals(0, version(batch));
        }
        service.saveReview(owner.user(), batch, request(0, decision(row, true, active, false, null)));
        jdbc.update("UPDATE " + table("category") + " SET active=FALSE WHERE id=?", active);
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(owner.user(), batch,
                request(1, decision(row, true, active, false, null))));
        var excluded = service.saveReview(owner.user(), batch, request(1, decision(row, false, active, false, null)));
        assertEquals(2, excluded.version()); assertEquals(active, candidateSnapshot(row).get("category_id"));
        var cleared = service.saveReview(owner.user(), batch, request(2, decision(row, false, null, false, null)));
        assertEquals(3, cleared.version()); assertNull(candidateSnapshot(row).get("category_id"));
        jdbc.update("UPDATE " + table("financial_account") + " SET active=FALSE WHERE id=?", owner.account());
        var selectedWithoutCategory = service.saveReview(owner.user(), batch, request(3, decision(row, true, null, false, null)));
        assertEquals(4, selectedWithoutCategory.version()); assertTrue((Boolean) candidateSnapshot(row).get("selected_for_import"));
    }

    @Test void categoryCannotInferUnknownCreditTypeEvenWhenNotSelected() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        UUID credit = row(owner, batch, 2, DATE, "CAD", "CREDIT", null, "5.00", "Credit", "[{\"code\":\"REVIEW_REQUIRED\"}]");
        UUID category = category(owner, "INCOME", true); var before = rows(batch);
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(owner.user(), batch,
                request(0, decision(credit, false, category, false, null))));
        assertEquals(before, rows(batch)); assertEquals(0, version(batch));
    }

    @Test void acknowledgementBindsSavedSignatureAndCanBeRevokedOnlyWithCompatibleSelection() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        UUID first = validRow(owner, batch, 2); validRow(owner, batch, 9);
        UUID unwarned = row(owner, batch, 3, DATE, "CAD", "DEBIT", "EXPENSE", "5.00", "Different", "[]");
        duplicates.refreshDuplicateWarnings(owner.user(), batch); String signature = signature(first);
        var before = rows(batch);
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(owner.user(), batch,
                request(1, decision(first, false, null, true, null))));
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(owner.user(), batch,
                request(1, decision(unwarned, false, null, true, "a".repeat(64)))));
        assertThrows(ImportReviewConflictException.class, () -> service.saveReview(owner.user(), batch,
                request(1, decision(first, false, null, true, "0".repeat(64)))));
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(owner.user(), batch,
                request(1, decision(first, true, null, false, null))));
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(owner.user(), batch,
                request(1, decision(first, false, null, false, signature))));
        assertEquals(before, rows(batch)); assertEquals(1, version(batch));
        service.saveReview(owner.user(), batch, request(1, decision(first, true, null, true, signature)));
        assertTrue((Boolean) candidateSnapshot(first).get("duplicate_warning")); assertEquals(signature, signature(first));
        var accepted = rows(batch);
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(owner.user(), batch,
                request(2, decision(first, true, null, false, null))));
        assertEquals(accepted, rows(batch));
        service.saveReview(owner.user(), batch, request(2, decision(first, false, null, false, null)));
        assertFalse((Boolean) candidateSnapshot(first).get("duplicate_acknowledged"));
        assertTrue((Boolean) candidateSnapshot(first).get("duplicate_warning")); assertEquals(signature, signature(first));
        assertEquals(3, version(batch));
    }

    @Test void ownershipCandidateMembershipAndTerminalStatesRejectTheWholeRequest() {
        Owner owner = owner(), other = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        UUID own = validRow(owner, batch, 2); UUID sameUserBatch = batch(owner, ImportStatus.REVIEWING);
        UUID wrongBatch = validRow(owner, sameUserBatch, 2); UUID foreignBatch = batch(other, ImportStatus.REVIEWING);
        UUID foreignRow = validRow(other, foreignBatch, 2); var before = rows(batch);
        sql.clear();
        var absent = assertThrows(ImportBatchNotFoundException.class, () -> service.saveReview(owner.user(), UUID.randomUUID(),
                request(0, decision(own, true, null, false, null))));
        var denied = assertThrows(ImportBatchNotFoundException.class, () -> service.saveReview(other.user(), batch,
                request(0, decision(own, true, null, false, null))));
        assertEquals(absent.getMessage(), denied.getMessage());
        assertTrue(sql.all().stream().noneMatch(s -> s.contains("import_candidate")));
        String candidateError = null;
        for (UUID invalid : List.of(wrongBatch, foreignRow, UUID.randomUUID())) {
            var failure = assertThrows(ImportCandidateNotFoundException.class, () -> service.saveReview(owner.user(), batch,
                    request(0, decision(own, true, null, false, null), decision(invalid, false, null, false, null))));
            if (candidateError == null) candidateError = failure.getMessage(); else assertEquals(candidateError, failure.getMessage());
            assertEquals(before, rows(batch)); assertEquals(0, version(batch));
        }
        for (ImportStatus status : List.of(ImportStatus.DRAFT, ImportStatus.CONFIRMED, ImportStatus.CANCELLED, ImportStatus.FAILED)) {
            UUID terminal = batch(owner, status); UUID candidate = validRow(owner, terminal, 2); var original = batchSnapshot(terminal);
            assertThrows(ImportBatchStateConflictException.class, () -> service.saveReview(owner.user(), terminal,
                    request(0, decision(candidate, false, null, false, null))));
            assertEquals(original, batchSnapshot(terminal));
        }
        assertEquals(before, rows(batch)); assertTrue(sql.all().stream().noneMatch(s -> s.startsWith("update ")));
    }

    @Test void malformedRequestsFailBeforeAnyDatabaseReadOrWrite() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING), id = validRow(owner, batch, 2);
        var decision = decision(id, false, null, false, null); sql.clear();
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(null, batch, request(0, decision)));
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(owner.user(), null, request(0, decision)));
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(owner.user(), batch, null));
        var invalid = new ArrayList<SaveImportReviewRequest>();
        invalid.add(new SaveImportReviewRequest(null, List.of(decision)));
        invalid.add(new SaveImportReviewRequest(-1L, List.of(decision)));
        invalid.add(new SaveImportReviewRequest(0L, null)); invalid.add(new SaveImportReviewRequest(0L, List.of()));
        invalid.add(new SaveImportReviewRequest(0L, Collections.nCopies(101, decision)));
        invalid.add(request(0, decision, decision));
        invalid.add(new SaveImportReviewRequest(0L, Arrays.asList((CandidateReviewDecision) null)));
        invalid.add(request(0, decision(null, false, null, false, null)));
        invalid.add(request(0, new CandidateReviewDecision(id, null, null, false, null)));
        invalid.add(request(0, new CandidateReviewDecision(id, false, null, null, null)));
        for (var request : invalid) assertThrows(InvalidImportRequestException.class,
                () -> service.saveReview(owner.user(), batch, request));
        assertTrue(sql.all().isEmpty(), sql.all().toString()); assertEquals(0, version(batch));
    }

    @Test void aLaterInvalidDecisionCannotPartiallySaveAnEarlierValidDecision() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        UUID first = validRow(owner, batch, 2);
        UUID bad = row(owner, batch, 3, null, "CAD", "DEBIT", "EXPENSE", "5.00", "Invalid", "[{\"code\":\"INVALID_DATE\"}]");
        var before = rows(batch); var beforeBatch = batchSnapshot(batch); sql.clear();
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(owner.user(), batch,
                request(0, decision(first, true, null, false, null), decision(bad, true, null, false, null))));
        assertEquals(before, rows(batch)); assertEquals(beforeBatch, batchSnapshot(batch));
        assertTrue(sql.all().stream().noneMatch(s -> s.startsWith("update ")));
    }

    @Test void exactDecisionsAtCurrentVersionAreNoOpButOldExpectedVersionStillConflicts() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING), row = validRow(owner, batch, 2);
        var first = service.saveReview(owner.user(), batch, request(0, decision(row, true, null, false, null)));
        assertEquals(1, first.version()); assertEquals(1, first.changedCandidateCount());
        var before = rows(batch); sql.clear();
        var same = service.saveReview(owner.user(), batch, request(1, decision(row, true, null, false, null)));
        assertEquals(1, same.version()); assertEquals(0, same.changedCandidateCount());
        assertTrue(sql.all().stream().noneMatch(s -> s.startsWith("update "))); assertEquals(before, rows(batch));
        assertThrows(ImportReviewConflictException.class, () -> service.saveReview(owner.user(), batch,
                request(0, decision(row, true, null, false, null))));
        assertEquals(before, rows(batch)); assertEquals(1, version(batch));
    }

    @Test void changedScanEvidenceRejectsOldReviewWithoutChangingUnsubmittedSelectedRows() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        UUID first = validRow(owner, batch, 2); UUID second = validRow(owner, batch, 9);
        UUID unrelated = row(owner, batch, 3, DATE, "CAD", "DEBIT", "EXPENSE", "5.00", "Unique", "[]");
        duplicates.refreshDuplicateWarnings(owner.user(), batch); String old = signature(first);
        service.saveReview(owner.user(), batch, request(1, decision(first, true, null, true, old)));
        long oldVersion = version(batch);
        validRow(owner, batch, 20); duplicates.refreshDuplicateWarnings(owner.user(), batch);
        assertEquals(3, version(batch)); assertFalse((Boolean) candidateSnapshot(first).get("duplicate_acknowledged"));
        assertTrue((Boolean) candidateSnapshot(first).get("selected_for_import"));
        var afterScan = rows(batch);
        assertThrows(ImportReviewConflictException.class, () -> service.saveReview(owner.user(), batch,
                request(oldVersion, decision(second, true, null, true, old))));
        assertEquals(afterScan, rows(batch));
        assertThrows(ImportReviewConflictException.class, () -> service.saveReview(owner.user(), batch,
                request(3, decision(second, true, null, true, old))));
        var firstBefore = candidateSnapshot(first);
        service.saveReview(owner.user(), batch, request(3, decision(unrelated, true, null, false, null)));
        assertEquals(firstBefore, candidateSnapshot(first)); assertEquals(4, version(batch));
    }

    @Test void twoRealSessionsWithOneExpectedVersionProduceOneSaveAndOneConflict() throws Exception {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING), row = validRow(owner, batch, 2);
        UUID firstCategory = category(owner, "EXPENSE", true), secondCategory = category(owner, "EXPENSE", true);
        var executor = Executors.newFixedThreadPool(2); gate.arm(batch);
        try {
            var first = executor.submit(() -> service.saveReview(owner.user(), batch, request(0, decision(row, true, firstCategory, false, null))));
            assertTrue(gate.firstLocked.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> service.saveReview(owner.user(), batch, request(0, decision(row, true, secondCategory, false, null))));
            awaitBlockedSecond(); gate.releaseFirst.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS).version());
            var failure = assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
            assertInstanceOf(ImportReviewConflictException.class, failure.getCause());
            assertEquals(List.of(0L, 1L), List.of(gate.firstVersion, gate.secondVersion));
            assertEquals(firstCategory, candidateSnapshot(row).get("category_id")); assertEquals(1, version(batch));
            System.out.printf("IMPORT_REVIEW_CONCURRENCY sessions=%d,%d observedBlocking=true versionsRead=0,1 saves=1 conflicts=1 finalVersion=1%n", gate.firstPid.get(), gate.secondPid.get());
        } finally { releaseAndStop(executor); }
    }

    @Test void scannerHoldingBatchLockMakesWaitingOldReviewConflict() throws Exception {
        exerciseReviewScanRace(false);
    }

    @Test void reviewHoldingBatchLockCommitsBeforeScannerInvalidatesOnlyItsAcknowledgement() throws Exception {
        exerciseReviewScanRace(true);
    }

    private static void exerciseReviewScanRace(boolean reviewFirst) throws Exception {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING), row = validRow(owner, batch, 2);
        validRow(owner, batch, 9); duplicates.refreshDuplicateWarnings(owner.user(), batch); String signature = signature(row);
        validRow(owner, batch, 20); var beforeFacts = transactionFacts(batch);
        var executor = Executors.newFixedThreadPool(2); gate.arm(batch);
        try {
            Future<?> first = reviewFirst
                    ? executor.submit(() -> service.saveReview(owner.user(), batch, request(1, decision(row, true, null, true, signature))))
                    : executor.submit(() -> duplicates.refreshDuplicateWarnings(owner.user(), batch));
            assertTrue(gate.firstLocked.await(10, TimeUnit.SECONDS));
            Future<?> second = reviewFirst
                    ? executor.submit(() -> duplicates.refreshDuplicateWarnings(owner.user(), batch))
                    : executor.submit(() -> service.saveReview(owner.user(), batch, request(1, decision(row, true, null, true, signature))));
            awaitBlockedSecond(); gate.releaseFirst.countDown(); first.get(10, TimeUnit.SECONDS);
            if (reviewFirst) second.get(10, TimeUnit.SECONDS);
            else {
                var failure = assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
                assertInstanceOf(ImportReviewConflictException.class, failure.getCause());
            }
            assertEquals(List.of(1L, 2L), List.of(gate.firstVersion, gate.secondVersion));
            assertEquals(reviewFirst ? 3 : 2, version(batch));
            assertNotEquals(signature, signature(row)); assertFalse((Boolean) candidateSnapshot(row).get("duplicate_acknowledged"));
            assertEquals(reviewFirst, candidateSnapshot(row).get("selected_for_import"));
            assertEquals(beforeFacts, transactionFacts(batch));
            System.out.printf("IMPORT_REVIEW_SCAN_RACE first=%s sessions=%d,%d observedBlocking=true finalVersion=%d staleAckCannotOverwrite=true%n",
                    reviewFirst ? "REVIEW" : "SCAN", gate.firstPid.get(), gate.secondPid.get(), version(batch));
        } finally { releaseAndStop(executor); }
    }

    @Test void staleEntitiesAlreadyLoadedByRequiredCallerCannotHideACommittedScanVersion() throws Exception {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING), row = validRow(owner, batch, 2);
        validRow(owner, batch, 9); var executor = Executors.newSingleThreadExecutor();
        var outer = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        try {
            assertThrows(ImportReviewConflictException.class, () -> outer.executeWithoutResult(status -> {
                var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(context.getBean(EntityManagerFactory.class));
                assertNotNull(manager); assertEquals(0, manager.find(ImportBatch.class, batch).getVersion());
                assertFalse(manager.find(ImportCandidate.class, row).getDuplicateWarning());
                try { executor.submit(() -> duplicates.refreshDuplicateWarnings(owner.user(), batch)).get(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError("Independent scanner must finish", failure); }
                service.saveReview(owner.user(), batch, request(0, decision(row, false, null, false, null)));
            }));
            assertEquals(1, version(batch)); assertTrue((Boolean) candidateSnapshot(row).get("duplicate_warning"));
            assertFalse((Boolean) candidateSnapshot(row).get("selected_for_import"));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void aPreloadedCandidateCannotHideFreshWarningEvenWhenExpectedBatchVersionIsCurrent() throws Exception {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING), row = validRow(owner, batch, 2);
        validRow(owner, batch, 9); var executor = Executors.newSingleThreadExecutor();
        var outer = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        try {
            var failure = assertThrows(RuntimeException.class, () -> outer.executeWithoutResult(status -> {
                var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(context.getBean(EntityManagerFactory.class));
                assertNotNull(manager);
                // Deliberately cache only the child. The subsequent Batch lock must load current version 1.
                assertFalse(manager.find(ImportCandidate.class, row).getDuplicateWarning());
                try { executor.submit(() -> duplicates.refreshDuplicateWarnings(owner.user(), batch)).get(10, TimeUnit.SECONDS); }
                catch (Exception problem) { throw new AssertionError("Independent scanner must finish", problem); }
                assertEquals(1, version(batch));
                service.saveReview(owner.user(), batch, request(1, decision(row, true, null, false, null)));
            }));
            assertTrue(failure instanceof ImportReviewConflictException || failure instanceof InvalidImportRequestException,
                    "Stale child state or missing acknowledgement must fail closed: " + failure.getClass().getName());
            assertEquals(1, version(batch)); assertTrue((Boolean) candidateSnapshot(row).get("duplicate_warning"));
            assertNotNull(signature(row)); assertFalse((Boolean) candidateSnapshot(row).get("selected_for_import"));
            assertFalse((Boolean) candidateSnapshot(row).get("duplicate_acknowledged"));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void outerRollbackUndoesSavedDecisionsAndParentVersionEvenAfterFlushReturnsAResult() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING), row = validRow(owner, batch, 2);
        UUID category = category(owner, "EXPENSE", true); var before = rows(batch); var beforeBatch = batchSnapshot(batch);
        var outer = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        outer.executeWithoutResult(status -> {
            var result = service.saveReview(owner.user(), batch, request(0, decision(row, true, category, false, null)));
            assertEquals(1, result.version()); assertEquals(1, version(batch));
            assertTrue((Boolean) candidateSnapshot(row).get("selected_for_import")); status.setRollbackOnly();
        });
        assertEquals(before, rows(batch)); assertEquals(beforeBatch, batchSnapshot(batch));
    }

    @Test void realDatabaseFailureAfterOneDecisionUpdateRollsBackEveryDecisionAndVersion() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING), first = validRow(owner, batch, 2), second = validRow(owner, batch, 9);
        var before = rows(batch); var beforeBatch = batchSnapshot(batch);
        var request = request(0, decision(first, true, null, false, null), decision(second, true, null, false, null));
        jdbc.execute("CREATE FUNCTION " + schema + ".fail_second_review() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.batch_id='" + batch + "'::uuid AND NEW.selected_for_import AND EXISTS(SELECT 1 FROM "
                + table("import_candidate") + " WHERE batch_id=NEW.batch_id AND selected_for_import) THEN "
                + "RAISE EXCEPTION 'synthetic failure after one review update' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_second_review BEFORE UPDATE ON " + table("import_candidate")
                + " FOR EACH ROW EXECUTE FUNCTION " + schema + ".fail_second_review()");
        try {
            var failure = assertThrows(RuntimeException.class, () -> service.saveReview(owner.user(), batch, request));
            assertTrue(hasSqlState(failure, "23514"));
            assertEquals(before, rows(batch)); assertEquals(beforeBatch, batchSnapshot(batch));
        } finally {
            jdbc.execute("DROP TRIGGER fail_second_review ON " + table("import_candidate"));
            jdbc.execute("DROP FUNCTION " + schema + ".fail_second_review()");
        }
        var retry = service.saveReview(owner.user(), batch, request);
        assertEquals(1, retry.version()); assertEquals(2, retry.changedCandidateCount());
        System.out.println("IMPORT_REVIEW_ROLLBACK sqlState=23514 partialDecisionChanges=0 partialVersionChanges=0 retryChanged=2 retryVersion=1");
    }

    @Test void candidateOwnershipAndCategoriesAreQueriedInBatchesForTwoAndOneHundredDecisions() {
        Owner owner = owner(); var counts = new ArrayList<Integer>();
        for (int size : List.of(2, 100)) {
            UUID batch = batch(owner, ImportStatus.REVIEWING); var decisions = new ArrayList<CandidateReviewDecision>();
            for (int i = 0; i < size; i++) {
                UUID id = validRow(owner, batch, i + 2); UUID category = category(owner, "EXPENSE", true);
                decisions.add(decision(id, true, category, false, null));
            }
            sql.clear(); var result = service.saveReview(owner.user(), batch, new SaveImportReviewRequest(0L, decisions));
            assertEquals(size, result.changedCandidateCount()); assertEquals(1, result.version());
            var selects = sql.selects(); counts.add(selects.size()); assertEquals(4, selects.size(), selects.toString());
            String candidateQuery = selects.stream().filter(s -> s.contains("import_candidate")).findFirst().orElseThrow();
            String categoryQuery = selects.stream().filter(s -> s.contains(".category ")).findFirst().orElseThrow();
            assertTrue(candidateQuery.contains(".batch_id=?") && candidateQuery.contains(".user_id=?") && candidateQuery.contains(" in ("), candidateQuery);
            assertTrue(categoryQuery.contains(".user_id=?") && categoryQuery.contains(" in ("), categoryQuery);
            assertTrue(selects.stream().noneMatch(s -> s.contains(" join ") || s.contains("ledger_entry")), selects.toString());
        }
        assertEquals(List.of(4, 4), counts);
        System.out.println("IMPORT_REVIEW_QUERY decisions=2,100 selects=4,4 ownerScopedCandidateAndCategoryBulkQueries=true");
    }

    private record Owner(UUID user, UUID account) {}
    private static Owner owner() {
        UUID user = UUID.randomUUID(), account = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("app_user") + " (id,auth_subject,display_name) VALUES (?,?,?)",
                user, "review-" + user, "Synthetic owner");
        jdbc.update("INSERT INTO " + table("financial_account") + " (id,user_id,name,account_type) VALUES (?,?,?,'CREDIT_CARD')",
                account, user, "Synthetic account");
        return new Owner(user, account);
    }
    private static UUID category(Owner owner, String type, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("category") + " (id,user_id,name,entry_type,active) VALUES (?,?,?,?,?)",
                id, owner.user(), "Synthetic category " + id, type, active);
        return id;
    }
    private static UUID batch(Owner owner, ImportStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("import_batch")
                + " (id,user_id,account_id,upload_key,file_name,file_sha256,status,bank_type,confirmed_at,closed_at)"
                + " VALUES (?,?,?,?,'synthetic-review.csv',? ,?,'RBC_VISA',"
                + " CASE WHEN ?='CONFIRMED' THEN now() ELSE NULL END,"
                + " CASE WHEN ? IN ('CONFIRMED','CANCELLED','FAILED') THEN now() ELSE NULL END)",
                id, owner.user(), owner.account(), UUID.randomUUID(), "a".repeat(64), status.name(), status.name(), status.name());
        return id;
    }
    private static UUID validRow(Owner owner, UUID batch, int number) {
        return row(owner, batch, number, DATE, "CAD", "DEBIT", "EXPENSE", "30.00", "Merchant", "[]");
    }
    private static UUID row(Owner owner, UUID batch, int rowNumber, LocalDate date, String currency,
                            String direction, String type, String amount, String description, String errors) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("import_candidate")
                + " (id,batch_id,user_id,row_number,transaction_date,currency_code,source_direction,entry_type,amount,description,validation_errors)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))", id, batch, owner.user(), rowNumber, date,
                currency, direction, type, amount == null ? null : new BigDecimal(amount), description, errors);
        return id;
    }
    private static CandidateReviewDecision decision(UUID id, boolean selected, UUID category, boolean ack, String signature) {
        return new CandidateReviewDecision(id, selected, category, ack, signature);
    }
    private static SaveImportReviewRequest request(long version, CandidateReviewDecision... decisions) {
        return new SaveImportReviewRequest(version, List.of(decisions));
    }
    private static ImportPreviewResponse preview(Owner owner, UUID batch) {
        return query.getImportPreview(owner.user(), batch, PageRequest.of(0, 100));
    }
    private static String table(String name) { return schema + "." + name; }
    private static long version(UUID batch) {
        return jdbc.queryForObject("SELECT version FROM " + table("import_batch") + " WHERE id=?", Long.class, batch);
    }
    private static String signature(UUID candidate) {
        return jdbc.queryForObject("SELECT duplicate_match_signature FROM " + table("import_candidate") + " WHERE id=?", String.class, candidate);
    }
    private static List<Map<String, Object>> rows(UUID batch) {
        return jdbc.queryForList("SELECT * FROM " + table("import_candidate") + " WHERE batch_id=? ORDER BY id", batch);
    }
    private static Map<String, Object> candidateSnapshot(UUID candidate) {
        return jdbc.queryForMap("SELECT * FROM " + table("import_candidate") + " WHERE id=?", candidate);
    }
    private static Map<String, Object> batchSnapshot(UUID batch) {
        return jdbc.queryForMap("SELECT * FROM " + table("import_batch") + " WHERE id=?", batch);
    }
    private static List<String> immutableFacts(UUID batch) {
        return jdbc.queryForList("SELECT (to_jsonb(c) - 'category_id' - 'selected_for_import' - 'duplicate_acknowledged')::text FROM "
                + table("import_candidate") + " c WHERE batch_id=? ORDER BY id", String.class, batch);
    }
    private static List<String> transactionFacts(UUID batch) {
        return jdbc.queryForList("SELECT (to_jsonb(c) - 'category_id' - 'selected_for_import' - 'duplicate_acknowledged'"
                + " - 'duplicate_warning' - 'duplicate_match_signature')::text FROM "
                + table("import_candidate") + " c WHERE batch_id=? ORDER BY id", String.class, batch);
    }
    private static String immutableBatch(UUID batch) {
        return jdbc.queryForObject("SELECT (to_jsonb(b) - 'version')::text FROM " + table("import_batch") + " b WHERE id=?", String.class, batch);
    }
    private static List<Map<String, Object>> ledgerRows(Owner owner) {
        return jdbc.queryForList("SELECT * FROM " + table("ledger_entry") + " WHERE user_id=? ORDER BY id", owner.user());
    }
    private static boolean hasSqlState(Throwable failure, String state) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlFailure && state.equals(sqlFailure.getSQLState())) return true;
        }
        return false;
    }
    private static void awaitBlockedSecond() throws Exception {
        assertTrue(gate.secondAttempted.await(10, TimeUnit.SECONDS)); assertNotEquals(gate.firstPid.get(), gate.secondPid.get());
        boolean blocked = false; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            blocked = Boolean.TRUE.equals(jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))",
                    Boolean.class, gate.firstPid.get(), gate.secondPid.get()));
            if (blocked) break; TimeUnit.MILLISECONDS.sleep(20);
        }
        assertTrue(blocked, "PostgreSQL must report real contention on the same Batch row");
    }
    private static void releaseAndStop(ExecutorService executor) throws Exception {
        gate.releaseFirst.countDown(); gate.disarm(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

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
            entityManager.createNativeQuery("SET LOCAL lock_timeout='8s'").executeUpdate();
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
