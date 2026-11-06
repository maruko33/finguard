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
import java.sql.Connection;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.SAME_THREAD)
class ImportConfirmationIntegrationTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 1);
    private static AnnotationConfigApplicationContext context;
    private static DataSource source;
    private static JdbcTemplate jdbc;
    private static ImportConfirmationService service;
    private static ImportReviewService review;
    private static ImportDuplicateWarningService duplicates;
    private static ImportPreparationService preparation;
    private static ImportBatchQueryService query;
    private static SqlCapture sql;
    private static LockGate gate;
    private static String schema;

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = ImportBatchRepository.class)
    @Import({ImportConfirmationService.class, ImportReviewService.class, ImportDuplicateWarningService.class,
            ImportPreparationService.class, ImportBatchQueryService.class})
    static class Config {
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
        @Bean SqlCapture sqlCapture() { return new SqlCapture(); }
        @Bean LockGate lockGate() { return new LockGate(); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source, SqlCapture capture) {
            var factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(source);
            factory.setPackagesToScan("com.finguard.transactionapi.finance.domain");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.default_schema", schema,
                    "hibernate.session_factory.statement_inspector", capture)); return factory;
        }
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean @Primary ImportBatchRepository gatedBatches(@Qualifier("importBatchRepository") ImportBatchRepository delegate,
                LockGate gate, EntityManagerFactory factory) {
            return proxy(delegate, ImportBatchRepository.class, "BATCH", gate, factory);
        }
        @Bean @Primary FinancialAccountRepository gatedAccounts(@Qualifier("financialAccountRepository") FinancialAccountRepository delegate,
                LockGate gate, EntityManagerFactory factory) {
            return proxy(delegate, FinancialAccountRepository.class, "ACCOUNT", gate, factory);
        }
        @Bean @Primary CategoryRepository gatedCategories(@Qualifier("categoryRepository") CategoryRepository delegate,
                LockGate gate, EntityManagerFactory factory) {
            return proxy(delegate, CategoryRepository.class, "CATEGORY", gate, factory);
        }
        private static <T> T proxy(T delegate, Class<T> type, String stage, LockGate gate, EntityManagerFactory factory) {
            var proxy = new ProxyFactory(delegate);
            proxy.addAdvice((MethodInterceptor) invocation -> {
                int attempt = gate.before(stage, invocation.getMethod().getName(), invocation.getArguments(), factory);
                Object result = invocation.proceed(); if (attempt != 0) gate.after(attempt); return result;
            });
            return type.cast(proxy.getProxy());
        }
    }

    @BeforeAll static void setup() {
        source = new DriverManagerDataSource(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/finguard"),
                System.getenv().getOrDefault("DB_USERNAME", "finguard"), System.getenv("DB_PASSWORD"));
        jdbc = new JdbcTemplate(source); assertEquals(17, jdbc.queryForObject("SHOW server_version_num", Integer.class) / 10_000);
        schema = "import_confirm_test_" + UUID.randomUUID().toString().replace("-", "");
        var flyway = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
        assertEquals(4, flyway.migrate().migrationsExecuted); flyway.validate();
        context = new AnnotationConfigApplicationContext(); context.registerBean(DataSource.class, () -> source);
        context.register(Config.class); context.refresh(); service = context.getBean(ImportConfirmationService.class);
        review = context.getBean(ImportReviewService.class); duplicates = context.getBean(ImportDuplicateWarningService.class);
        preparation = context.getBean(ImportPreparationService.class); query = context.getBean(ImportBatchQueryService.class);
        sql = context.getBean(SqlCapture.class); gate = context.getBean(LockGate.class);
    }
    @AfterAll static void cleanup() {
        if (context != null) context.close();
        if (jdbc != null && schema != null) {
            assertTrue(schema.matches("import_confirm_test_[a-f0-9]{32}"));
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test void suppliedSyntheticCsvCompletesPersistedImportAcceptance() throws Exception {
        Owner owner = owner(); UUID uploadKey = UUID.randomUUID();
        String resource = "/finance/importing/finguard_import_acceptance_synthetic.csv";
        String fileName = "finguard_import_acceptance_synthetic.csv";
        ImportPreviewResponse uploaded;
        try (var csv = getClass().getResourceAsStream(resource)) {
            assertNotNull(csv, "The committed synthetic acceptance fixture must be on the test classpath");
            uploaded = preparation.prepareImport(owner.user(), owner.account(), csv,
                    BankType.RBC_VISA, uploadKey, fileName);
        }
        assertEquals(ImportStatus.REVIEWING, uploaded.status()); assertEquals(0, uploaded.version());
        assertEquals(6, uploaded.candidates().getTotalElements());
        assertTrue(uploaded.candidates().stream().noneMatch(CandidateSummary::selectedForImport));
        assertTrue(ledgerRows(uploaded.id()).isEmpty());
        var reviewing = query.listReviewingBatches(owner.user(), PageRequest.of(0, 15));
        assertEquals(1, reviewing.getTotalElements());
        var summary = reviewing.getContent().getFirst();
        assertEquals(uploaded.id(), summary.id()); assertEquals(6, summary.candidateCount());
        assertEquals(3, summary.attentionRowCount(), "Attention counts issue-bearing rows, not duplicate warnings");

        var initial = query.getImportPreview(owner.user(), uploaded.id(), PageRequest.of(0, 15));
        var initialByRow = initial.candidates().stream().collect(java.util.stream.Collectors.toMap(CandidateSummary::rowNumber, r -> r));
        assertEquals(Set.of(2, 3, 4, 5, 6, 7), initialByRow.keySet(), "Header is logical record 1");
        for (int number : List.of(2, 3, 4)) {
            var row = initialByRow.get(number);
            assertEquals(EntryType.EXPENSE, row.entryType());
            assertEquals(ImportSourceDirection.DEBIT, row.sourceDirection());
            assertEquals("CAD", row.currencyCode()); assertEquals("[]", row.validationErrors());
        }
        assertEquals(new BigDecimal("10.25"), initialByRow.get(2).amount());
        assertEquals(new BigDecimal("3.50"), initialByRow.get(3).amount());
        assertEquals(new BigDecimal("10.25"), initialByRow.get(4).amount());
        assertEquals(LocalDate.of(2026, 10, 1), initialByRow.get(2).transactionDate());
        assertEquals(LocalDate.of(2026, 10, 2), initialByRow.get(3).transactionDate());
        assertEquals(LocalDate.of(2026, 10, 1), initialByRow.get(4).transactionDate());
        var credit = initialByRow.get(5);
        assertNull(credit.entryType()); assertEquals(ImportSourceDirection.CREDIT, credit.sourceDirection());
        assertEquals(new BigDecimal("25.00"), credit.amount()); assertEquals("CAD", credit.currencyCode());
        assertEquals(LocalDate.of(2026, 10, 3), credit.transactionDate());
        assertEquals("SYNTHETIC CREDIT", credit.description());
        assertTrue(credit.validationErrors().contains("REVIEW_REQUIRED"));
        assertNull(initialByRow.get(6).transactionDate());
        assertEquals(new BigDecimal("4.00"), initialByRow.get(6).amount());
        assertTrue(initialByRow.get(6).validationErrors().contains("INVALID_DATE"));
        assertNull(initialByRow.get(7).amount());
        assertEquals(LocalDate.of(2026, 10, 4), initialByRow.get(7).transactionDate());
        assertTrue(initialByRow.get(7).validationErrors().contains("INVALID_AMOUNT"));

        duplicates.refreshDuplicateWarnings(owner.user(), uploaded.id());
        var scanned = preview(owner, uploaded.id()); assertEquals(1, scanned.version());
        assertEquals(Set.of(2, 4), scanned.candidates().stream().filter(CandidateSummary::duplicateWarning)
                .map(CandidateSummary::rowNumber).collect(java.util.stream.Collectors.toSet()));
        var scannedByRow = scanned.candidates().stream().collect(java.util.stream.Collectors.toMap(CandidateSummary::rowNumber, r -> r));
        assertNotNull(scannedByRow.get(2).duplicateMatchSignature());
        assertEquals(scannedByRow.get(2).duplicateMatchSignature(), scannedByRow.get(4).duplicateMatchSignature());
        var decisions = scanned.candidates().stream().map(row -> {
            boolean selected = Set.of(2, 3, 4).contains(row.rowNumber());
            boolean acknowledged = selected && row.duplicateWarning();
            // These two weak matches represent independently accepted synthetic purchases.
            return new CandidateReviewDecision(row.candidateId(), selected, null, acknowledged,
                    acknowledged ? row.duplicateMatchSignature() : null);
        }).toList();
        var saved = review.saveReview(owner.user(), uploaded.id(), new SaveImportReviewRequest(scanned.version(), decisions));
        assertEquals(ImportStatus.REVIEWING, saved.status()); assertEquals(2, saved.version());
        assertEquals(3, saved.changedCandidateCount());
        var reviewed = preview(owner, uploaded.id());
        assertEquals(Set.of(2, 3, 4), reviewed.candidates().stream().filter(CandidateSummary::selectedForImport)
                .map(CandidateSummary::rowNumber).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(2, 4), reviewed.candidates().stream().filter(CandidateSummary::duplicateAcknowledged)
                .map(CandidateSummary::rowNumber).collect(java.util.stream.Collectors.toSet()));
        for (var row : reviewed.candidates()) {
            var original = initialByRow.get(row.rowNumber());
            assertEquals(original.amount(), row.amount()); assertEquals(original.transactionDate(), row.transactionDate());
            assertEquals(original.currencyCode(), row.currencyCode()); assertEquals(original.description(), row.description());
            assertEquals(original.entryType(), row.entryType()); assertEquals(original.sourceDirection(), row.sourceDirection());
            assertEquals(original.validationErrors(), row.validationErrors());
            assertEquals(scannedByRow.get(row.rowNumber()).duplicateMatchSignature(), row.duplicateMatchSignature());
        }
        var candidateDecisions = candidates(uploaded.id());
        var result = confirm(owner, uploaded.id(), saved.version());
        assertEquals(ImportStatus.CONFIRMED, result.status()); assertEquals(3, result.version());
        assertEquals(3, result.confirmedCount()); assertEquals(3, result.notImportedCount()); assertNotNull(result.confirmedAt());

        // A separate read transaction observes the committed result, not managed write-context objects.
        var independent = transaction(); independent.setReadOnly(true);
        var persistedLedger = independent.execute(status -> ledgerRows(uploaded.id()));
        var persistedBatch = independent.execute(status -> batchSnapshot(uploaded.id()));
        assertNotNull(persistedLedger); assertNotNull(persistedBatch); assertEquals(3, persistedLedger.size());
        assertEquals("CONFIRMED", persistedBatch.get("status")); assertEquals(3L, persistedBatch.get("version"));
        assertEquals(3, persistedBatch.get("confirmed_count")); assertEquals(3, persistedBatch.get("excluded_count"));
        assertNotNull(persistedBatch.get("confirmed_at"));
        assertEquals(persistedBatch.get("confirmed_at"), persistedBatch.get("closed_at"));
        assertEquals(Set.of(2, 3, 4), persistedLedger.stream().map(row -> ((Number) row.get("source_row_number")).intValue())
                .collect(java.util.stream.Collectors.toSet()));
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : persistedLedger) {
            int number = ((Number) entry.get("source_row_number")).intValue();
            var original = initialByRow.get(number);
            assertEquals(owner.user(), entry.get("user_id")); assertEquals(owner.account(), entry.get("account_id"));
            assertEquals(uploaded.id(), entry.get("source_batch_id")); assertEquals("EXPENSE", entry.get("entry_type"));
            assertEquals("CAD", entry.get("currency_code")); assertEquals(original.amount(), entry.get("amount"));
            assertEquals(original.transactionDate(), ((java.sql.Date) entry.get("transaction_date")).toLocalDate());
            assertEquals(original.description(), entry.get("description")); assertEquals(original.occurredAt(), entry.get("occurred_at"));
            assertNull(entry.get("category_id")); assertNull(entry.get("bank_transaction_id")); assertNull(entry.get("deleted_at"));
            assertEquals(0L, entry.get("version")); total = total.add((BigDecimal) entry.get("amount"));
        }
        assertEquals(new BigDecimal("24.00"), total);
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM " + table("ledger_entry") + " WHERE user_id=?", Integer.class, owner.user()));
        assertEquals(candidateDecisions, candidates(uploaded.id()), "All six Candidates and saved decisions survive confirmation");
        assertEquals(0, query.listReviewingBatches(owner.user(), PageRequest.of(0, 15)).getTotalElements());
        var historical = preview(owner, uploaded.id()); assertEquals(ImportStatus.CONFIRMED, historical.status());
        assertEquals(6, historical.candidates().getTotalElements());

        sql.clear();
        assertEquals(result, confirm(owner, uploaded.id(), saved.version()), "Original expected version retries return the durable summary");
        ImportPreviewResponse uploadRetry;
        try (var csv = getClass().getResourceAsStream(resource)) {
            assertNotNull(csv);
            uploadRetry = preparation.prepareImport(owner.user(), owner.account(), csv,
                    BankType.RBC_VISA, uploadKey, fileName);
        }
        assertEquals(uploaded.id(), uploadRetry.id()); assertEquals(ImportStatus.CONFIRMED, uploadRetry.status());
        assertEquals(result.version(), uploadRetry.version()); assertEquals(historical.candidates().getContent(), uploadRetry.candidates().getContent());
        assertEquals(persistedBatch, batchSnapshot(uploaded.id())); assertEquals(persistedLedger, ledgerRows(uploaded.id()));
        assertEquals(candidateDecisions, candidates(uploaded.id()));
        assertTrue(sql.all().stream().noneMatch(statement -> statement.startsWith("insert ") || statement.startsWith("update ") || statement.startsWith("delete ")));
        System.out.printf("IMPORT_SUPPLIED_CSV_ACCEPTANCE batch=%s candidates=6 attentionRows=3 warnings=[2,4] selected=[2,3,4] ledgerRows=3 sumCAD=%s confirmed=3 notImported=3 version=0->1->2->3 candidatesRetained=6 confirmRetryEqual=true uploadRetryConfirmed=true%n",
                uploaded.id(), total.toPlainString());
    }

    @Test void prepareReviewConfirmAndRetryPreserveCandidatesAndPersistOnlySavedSelections() {
        Owner owner = owner(); UUID category = category(owner, "EXPENSE", true);
        String csv = "Transaction Date,Description 1,CAD$,USD$\n"
                + "11/1/2026,Coffee shop,-30.0,\n"
                + "11/1/2026,Bus fare,-3.00,\n"
                + "11/1/2026, COFFEE  SHOP ,-30.00,\n"
                + "bad,Invalid date,-8.25,\n"
                + "11/1/2026,Credit refund,5.00,\n";
        var uploaded = preparation.prepareImport(owner.user(), owner.account(),
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), BankType.RBC_VISA,
                UUID.randomUUID(), "atomic-confirmation-synthetic.csv");
        duplicates.refreshDuplicateWarnings(owner.user(), uploaded.id());
        var preview = preview(owner, uploaded.id());
        var rows = preview.candidates().stream().collect(java.util.stream.Collectors.toMap(CandidateSummary::rowNumber, r -> r));
        var saved = review.saveReview(owner.user(), uploaded.id(), new SaveImportReviewRequest(preview.version(), List.of(
                new CandidateReviewDecision(rows.get(2).candidateId(), true, category, true, rows.get(2).duplicateMatchSignature()),
                new CandidateReviewDecision(rows.get(3).candidateId(), true, category, false, null))));
        assertEquals(2, saved.version()); var candidatesBefore = candidates(uploaded.id());
        var result = confirm(owner, uploaded.id(), saved.version());
        assertEquals(uploaded.id(), result.batchId()); assertEquals(ImportStatus.CONFIRMED, result.status());
        assertEquals(3, result.version()); assertEquals(2, result.confirmedCount()); assertEquals(3, result.notImportedCount());
        assertNotNull(result.confirmedAt()); assertEquals(candidatesBefore, candidates(uploaded.id()));
        var independent = new TransactionTemplate(context.getBean(JpaTransactionManager.class)); independent.setReadOnly(true);
        var ledger = independent.execute(status -> ledgerRows(uploaded.id()));
        assertNotNull(ledger); assertEquals(2, ledger.size());
        assertEquals(Set.of(2, 3), ledger.stream().map(r -> ((Number) r.get("source_row_number")).intValue()).collect(java.util.stream.Collectors.toSet()));
        for (var entry : ledger) {
            assertEquals(owner.user(), entry.get("user_id")); assertEquals(owner.account(), entry.get("account_id"));
            assertEquals(category, entry.get("category_id")); assertEquals("EXPENSE", entry.get("entry_type"));
            assertEquals("CAD", entry.get("currency_code")); assertEquals(0L, entry.get("version"));
            assertEquals(entry.get("created_at"), entry.get("updated_at")); assertNull(entry.get("deleted_at"));
            assertNull(entry.get("bank_transaction_id")); assertNull(entry.get("client_request_key"));
            int number = ((Number) entry.get("source_row_number")).intValue();
            assertEquals(rows.get(number).description(), entry.get("description"));
            assertEquals(rows.get(number).amount(), entry.get("amount"));
        }
        var batch = batchSnapshot(uploaded.id()); assertEquals(batch.get("confirmed_at"), batch.get("closed_at"));
        var persisted = ledgerRows(uploaded.id()); sql.clear();
        assertEquals(result, confirm(owner, uploaded.id(), saved.version()));
        assertEquals(persisted, ledgerRows(uploaded.id())); assertEquals(candidatesBefore, candidates(uploaded.id()));
        assertTrue(sql.all().stream().noneMatch(s -> s.startsWith("insert ") || s.startsWith("update ")));
        assertTrue(sql.all().stream().noneMatch(s -> s.contains("import_candidate") || s.contains("financial_account") || s.contains("union all")));
        System.out.printf("IMPORT_CONFIRM_ROUND_TRIP batch=%s version=2->3 status=CONFIRMED confirmed=2 notImported=3 sourceRows=[2,3] candidatesRetained=5 retryEqual=true%n", uploaded.id());
    }

    @Test void confirmedRetryDoesNotDependOnCandidatesActiveAccountOrLiveLedgerCount() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING); selectedRow(owner, batch, 2, "Stored selection");
        var result = confirm(owner, batch, 0); var ledger = ledgerRows(batch);
        jdbc.update("UPDATE " + table("financial_account") + " SET active=FALSE WHERE id=?", owner.account());
        jdbc.update("UPDATE " + table("ledger_entry") + " SET deleted_at=now() WHERE source_batch_id=?", batch);
        // Deliberate fixture retention cleanup; production confirmation performs no cleanup.
        jdbc.update("DELETE FROM " + table("import_candidate") + " WHERE batch_id=?", batch);
        var beforeBatch = batchSnapshot(batch); var beforeLedger = ledgerRows(batch); sql.clear();
        assertEquals(result, confirm(owner, batch, 0)); assertEquals(result, confirm(owner, batch, 999));
        assertEquals(beforeBatch, batchSnapshot(batch)); assertEquals(beforeLedger, ledgerRows(batch)); assertEquals(1, ledger.size());
        assertTrue(sql.all().stream().noneMatch(s -> s.contains("import_candidate") || s.contains("financial_account") || s.startsWith("insert ") || s.startsWith("update ")));
    }

    @Test void ownershipStatusVersionAndEmptySelectionFailWithoutAnyLedgerOrBatchChange() {
        Owner owner = owner(), foreign = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        selectedRow(owner, batch, 2, "Selection"); var before = batchSnapshot(batch); var rows = candidates(batch);
        var absent = assertThrows(ImportBatchNotFoundException.class, () -> confirm(owner, UUID.randomUUID(), 0));
        var denied = assertThrows(ImportBatchNotFoundException.class, () -> confirm(foreign, batch, 0));
        assertEquals(absent.getMessage(), denied.getMessage());
        assertThrows(ImportConfirmationConflictException.class, () -> confirm(owner, batch, 1));
        for (ImportStatus state : List.of(ImportStatus.DRAFT, ImportStatus.CANCELLED, ImportStatus.FAILED)) {
            UUID terminal = batch(owner, state); selectedRow(owner, terminal, 2, "Terminal selection");
            var terminalBefore = batchSnapshot(terminal);
            assertThrows(ImportBatchStateConflictException.class, () -> confirm(owner, terminal, 0));
            assertEquals(terminalBefore, batchSnapshot(terminal)); assertTrue(ledgerRows(terminal).isEmpty());
        }
        UUID empty = batch(owner, ImportStatus.REVIEWING); row(owner, empty, 2, "Unselected", false);
        assertThrows(InvalidImportRequestException.class, () -> confirm(owner, empty, 0));
        assertEquals(before, batchSnapshot(batch)); assertEquals(rows, candidates(batch)); assertTrue(ledgerRows(batch).isEmpty());
    }

    @Test void invalidRequestShapeAndIncompatibleOuterTransactionsAreRejectedBeforeBusinessReads() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING); selectedRow(owner, batch, 2, "Selection");
        sql.clear();
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(null, batch, new ConfirmImportRequest(0L)));
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(owner.user(), null, new ConfirmImportRequest(0L)));
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(owner.user(), batch, null));
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(owner.user(), batch, new ConfirmImportRequest(null)));
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(owner.user(), batch, new ConfirmImportRequest(-1L)));
        assertTrue(sql.all().stream().noneMatch(s -> s.contains("import_batch") || s.contains("import_candidate")));
        for (int isolation : List.of(TransactionDefinition.ISOLATION_REPEATABLE_READ, TransactionDefinition.ISOLATION_SERIALIZABLE)) {
            var outer = transaction(); outer.setIsolationLevel(isolation);
            assertThrows(InvalidImportRequestException.class, () -> outer.executeWithoutResult(status -> confirm(owner, batch, 0)));
        }
        var readOnly = transaction(); readOnly.setReadOnly(true);
        assertThrows(InvalidImportRequestException.class, () -> readOnly.executeWithoutResult(status -> confirm(owner, batch, 0)));
        assertTrue(ledgerRows(batch).isEmpty()); assertEquals(0, version(batch));
    }

    @Test void finalValidationRejectsInactiveAccountsCurrenciesCategoriesAndBlankDescriptionsAtomically() {
        for (String defect : List.of("inactiveAccount", "accountCurrency", "inactiveCategory", "blankDescription", "invalidIso")) {
            Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING); UUID id = selectedRow(owner, batch, 2, "Selection");
            if (defect.equals("inactiveAccount")) jdbc.update("UPDATE " + table("financial_account") + " SET active=FALSE WHERE id=?", owner.account());
            if (defect.equals("accountCurrency")) jdbc.update("UPDATE " + table("financial_account") + " SET currency_code='USD' WHERE id=?", owner.account());
            if (defect.equals("inactiveCategory")) {
                UUID category = category(owner, "EXPENSE", false);
                jdbc.update("UPDATE " + table("import_candidate") + " SET category_id=? WHERE id=?", category, id);
            }
            if (defect.equals("blankDescription")) jdbc.update("UPDATE " + table("import_candidate") + " SET description='  ' WHERE id=?", id);
            if (defect.equals("invalidIso")) jdbc.update("UPDATE " + table("import_candidate") + " SET currency_code='ZZZ' WHERE id=?", id);
            var beforeBatch = batchSnapshot(batch); var beforeRows = candidates(batch);
            assertThrows(InvalidImportRequestException.class, () -> confirm(owner, batch, 0), defect);
            assertEquals(beforeBatch, batchSnapshot(batch)); assertEquals(beforeRows, candidates(batch)); assertTrue(ledgerRows(batch).isEmpty());
        }
    }

    @Test void databasePreventsInvalidSelectedCreditDateAmountOrIssuesWithoutRepairingThem() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING); UUID id = row(owner, batch, 2, "Invalid staging", false);
        for (String assignment : List.of("entry_type=NULL", "transaction_date=NULL", "amount=NULL", "validation_errors='[{}]'::jsonb")) {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("UPDATE " + table("import_candidate")
                    + " SET selected_for_import=TRUE," + assignment + " WHERE id=?", id));
        }
        assertFalse((Boolean) candidate(id).get("selected_for_import")); assertTrue(ledgerRows(batch).isEmpty());
    }

    @Test void newLedgerEvidenceRequiresSeparateRefreshAndAcknowledgementWithoutSavingWarningsOnFailure() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING); UUID id = selectedRow(owner, batch, 2, "Overlapping purchase");
        var beforeRows = candidates(batch); var beforeBatch = batchSnapshot(batch);
        ledger(owner, null, null, "Overlapping purchase", null);
        assertThrows(ImportConfirmationConflictException.class, () -> confirm(owner, batch, 0));
        assertEquals(beforeRows, candidates(batch)); assertEquals(beforeBatch, batchSnapshot(batch)); assertTrue(ledgerRows(batch).isEmpty());
        duplicates.refreshDuplicateWarnings(owner.user(), batch); var preview = preview(owner, batch); var target = preview.candidates().getContent().getFirst();
        var saved = review.saveReview(owner.user(), batch, new SaveImportReviewRequest(preview.version(), List.of(
                new CandidateReviewDecision(id, true, null, true, target.duplicateMatchSignature()))));
        var result = confirm(owner, batch, saved.version()); assertEquals(1, result.confirmedCount());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM " + table("ledger_entry") + " WHERE user_id=?", Integer.class, owner.user()));
    }

    @Test void sourceLimitFailsClosedAndDoesNotPersistNewWarningsOrPartialLedger() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING); selectedRow(owner, batch, 2, "Limit purchase");
        jdbc.update("INSERT INTO " + table("ledger_entry") + " (id,user_id,account_id,transaction_date,currency_code,entry_type,amount,description)"
                + " SELECT md5(? || g::text)::uuid,?,?,?,'CAD','EXPENSE',30.00,'Limit purchase' FROM generate_series(1,20001) g",
                owner.account().toString(), owner.user(), owner.account(), DATE);
        var rows = candidates(batch); var original = batchSnapshot(batch);
        assertThrows(InvalidImportRequestException.class, () -> confirm(owner, batch, 0));
        assertEquals(rows, candidates(batch)); assertEquals(original, batchSnapshot(batch)); assertTrue(ledgerRows(batch).isEmpty());
    }

    @Test void sourceRowUniqueConflictRollsBackEveryInsertRatherThanSkippingOneSelectedRow() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        selectedRow(owner, batch, 2, "First selection"); selectedRow(owner, batch, 3, "Second selection");
        // Deliberately inconsistent fixture: existing source row with a different weak-match key.
        ledger(owner, batch, 3, "Existing source identity", null);
        var rows = candidates(batch); var original = batchSnapshot(batch); var entries = ledgerRows(batch);
        var failure = assertThrows(ImportConfirmationConflictException.class, () -> confirm(owner, batch, 0));
        assertTrue(hasSqlState(failure, "23505")); assertEquals(entries, ledgerRows(batch));
        assertEquals(rows, candidates(batch)); assertEquals(original, batchSnapshot(batch));
    }

    @Test void trustedBankIdConstraintFixtureIsNotConfusedWithSupportedRbcInputIdentity() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING); UUID id = selectedRow(owner, batch, 2, "New purchase");
        jdbc.update("UPDATE " + table("import_candidate") + " SET bank_transaction_id='unverified-candidate-value',occurred_at=TIMESTAMPTZ '2026-11-01 12:34:56Z' WHERE id=?", id);
        ledger(owner, null, null, "Unrelated ledger fact", "synthetic-trusted-bank-id");
        var rows = candidates(batch); var original = batchSnapshot(batch);
        // Fixture-only trigger tests the preserved database barrier; RBC parsing supplies no verified bank ID.
        jdbc.execute("CREATE FUNCTION " + schema + ".inject_bank_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.source_batch_id='" + batch + "'::uuid THEN NEW.bank_transaction_id='synthetic-trusted-bank-id'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER inject_bank_identity BEFORE INSERT ON " + table("ledger_entry")
                + " FOR EACH ROW EXECUTE FUNCTION " + schema + ".inject_bank_identity()");
        try {
            var failure = assertThrows(ImportConfirmationConflictException.class, () -> confirm(owner, batch, 0));
            assertTrue(hasSqlState(failure, "23505")); assertTrue(ledgerRows(batch).isEmpty());
            assertEquals(rows, candidates(batch)); assertEquals(original, batchSnapshot(batch));
        } finally {
            jdbc.execute("DROP TRIGGER inject_bank_identity ON " + table("ledger_entry")); jdbc.execute("DROP FUNCTION " + schema + ".inject_bank_identity()");
        }
        confirm(owner, batch, 0); assertNull(ledgerRows(batch).getFirst().get("bank_transaction_id"));
        assertEquals(candidate(id).get("occurred_at"), ledgerRows(batch).getFirst().get("occurred_at"));
        assertEquals(rows, candidates(batch));
    }

    @Test void twoRealSessionsConfirmingOneBatchReturnOnePersistedResult() throws Exception {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING); selectedRow(owner, batch, 2, "Selection");
        var executor = Executors.newFixedThreadPool(2); gate.arm("BATCH", batch, batch);
        try {
            var first = executor.submit(() -> confirm(owner, batch, 0)); assertTrue(gate.firstLocked.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> confirm(owner, batch, 0)); awaitSecondBlocked(); gate.releaseFirst.countDown();
            var winner = first.get(10, TimeUnit.SECONDS); assertEquals(winner, second.get(10, TimeUnit.SECONDS));
            assertEquals(1, ledgerRows(batch).size()); assertEquals(1, version(batch));
            System.out.printf("IMPORT_CONFIRM_SAME_BATCH sessions=%d,%d observedBlocking=true inserts=1 equalResults=true finalVersion=1%n", gate.firstPid.get(), gate.secondPid.get());
        } finally { finish(executor); }
    }

    @Test void reviewBeforeConfirmMakesTheOldConfirmationVersionConflict() throws Exception { reviewConfirmRace(true); }
    @Test void confirmBeforeReviewMakesTheWaitingReviewRejectTheTerminalState() throws Exception { reviewConfirmRace(false); }

    private static void reviewConfirmRace(boolean reviewFirst) throws Exception {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING); UUID id = selectedRow(owner, batch, 2, "Selection");
        var request = new SaveImportReviewRequest(0L, List.of(new CandidateReviewDecision(id, false, null, false, null)));
        var executor = Executors.newFixedThreadPool(2); gate.arm("BATCH", batch, batch);
        try {
            Future<?> first = reviewFirst ? executor.submit(() -> review.saveReview(owner.user(), batch, request))
                    : executor.submit(() -> confirm(owner, batch, 0));
            assertTrue(gate.firstLocked.await(10, TimeUnit.SECONDS));
            Future<?> second = reviewFirst ? executor.submit(() -> confirm(owner, batch, 0))
                    : executor.submit(() -> review.saveReview(owner.user(), batch, request));
            awaitSecondBlocked(); gate.releaseFirst.countDown(); first.get(10, TimeUnit.SECONDS);
            var failure = assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
            if (reviewFirst) assertInstanceOf(ImportConfirmationConflictException.class, failure.getCause());
            else assertInstanceOf(ImportBatchStateConflictException.class, failure.getCause());
            assertEquals(1, version(batch)); assertEquals(reviewFirst ? 0 : 1, ledgerRows(batch).size());
            System.out.printf("IMPORT_CONFIRM_REVIEW_RACE first=%s sessions=%d,%d observedBlocking=true waitingWriteRejected=true%n",
                    reviewFirst ? "REVIEW" : "CONFIRM", gate.firstPid.get(), gate.secondPid.get());
        } finally { finish(executor); }
    }

    @Test void sameAccountDifferentBatchesSerializeAndRequireRereviewAfterCandidateBecomesLedger() throws Exception {
        Owner owner = owner(); UUID firstBatch = batch(owner, ImportStatus.REVIEWING), secondBatch = batch(owner, ImportStatus.REVIEWING);
        selectedRow(owner, firstBatch, 2, "Same weak facts"); selectedRow(owner, secondBatch, 2, "Same weak facts");
        refreshAndAcknowledge(owner, firstBatch); refreshAndAcknowledge(owner, secondBatch);
        long firstVersion = version(firstBatch), secondVersion = version(secondBatch);
        var secondRows = candidates(secondBatch); var secondBefore = batchSnapshot(secondBatch);
        var executor = Executors.newFixedThreadPool(2); gate.arm("ACCOUNT", owner.account(), firstBatch);
        try {
            var first = executor.submit(() -> confirm(owner, firstBatch, firstVersion));
            assertTrue(gate.firstLocked.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> confirm(owner, secondBatch, secondVersion));
            awaitSecondBlocked(); gate.releaseFirst.countDown(); assertEquals(ImportStatus.CONFIRMED, first.get(10, TimeUnit.SECONDS).status());
            var failure = assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
            assertInstanceOf(ImportConfirmationConflictException.class, failure.getCause());
            assertEquals(secondRows, candidates(secondBatch)); assertEquals(secondBefore, batchSnapshot(secondBatch));
            assertTrue(ledgerRows(secondBatch).isEmpty()); assertEquals(1, ledgerRows(firstBatch).size());
            System.out.printf("IMPORT_CONFIRM_ACCOUNT_SERIALIZATION sessions=%d,%d observedBlocking=true secondRequiresRereview=true%n", gate.firstPid.get(), gate.secondPid.get());
        } finally { finish(executor); }
        refreshAndAcknowledge(owner, secondBatch);
        var eventual = confirm(owner, secondBatch, version(secondBatch)); assertEquals(1, eventual.confirmedCount());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM " + table("ledger_entry") + " WHERE user_id=?", Integer.class, owner.user()));
    }

    @Test void accountLockProtectsActiveAndCurrencyUntilConfirmationCommits() throws Exception {
        protectiveLockRace("ACCOUNT");
    }
    @Test void orderedCategoryShareLockProtectsActivityUntilConfirmationCommits() throws Exception {
        protectiveLockRace("CATEGORY");
    }

    private static void protectiveLockRace(String stage) throws Exception {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING); UUID id = selectedRow(owner, batch, 2, "Selection");
        UUID category = category(owner, "EXPENSE", true);
        jdbc.update("UPDATE " + table("import_candidate") + " SET category_id=? WHERE id=?", category, id);
        UUID locked = stage.equals("ACCOUNT") ? owner.account() : category;
        var executor = Executors.newFixedThreadPool(2); gate.arm(stage, locked, batch);
        try {
            var confirmation = executor.submit(() -> confirm(owner, batch, 0)); assertTrue(gate.firstLocked.await(10, TimeUnit.SECONDS));
            var writerPid = new AtomicInteger(); var attempted = new CountDownLatch(1);
            var writer = executor.submit(() -> {
                try (Connection connection = source.getConnection()) {
                    connection.setAutoCommit(false); writerPid.set(backendPid(connection));
                    try (var timeout = connection.createStatement()) { timeout.execute("SET LOCAL lock_timeout='8s'"); }
                    attempted.countDown();
                    try (var statement = connection.prepareStatement("UPDATE " + table(stage.equals("ACCOUNT") ? "financial_account" : "category")
                            + " SET active=FALSE" + (stage.equals("ACCOUNT") ? ",currency_code='USD'" : "") + " WHERE id=?")) {
                        statement.setObject(1, locked); statement.executeUpdate();
                    }
                    connection.commit(); return null;
                }
            });
            assertTrue(attempted.await(10, TimeUnit.SECONDS)); awaitBlocked(gate.firstPid.get(), writerPid.get());
            gate.releaseFirst.countDown(); assertEquals(ImportStatus.CONFIRMED, confirmation.get(10, TimeUnit.SECONDS).status()); writer.get(10, TimeUnit.SECONDS);
            assertEquals(1, ledgerRows(batch).size()); assertEquals("CAD", ledgerRows(batch).getFirst().get("currency_code"));
            System.out.printf("IMPORT_CONFIRM_PROTECTIVE_LOCK scope=%s confirmSession=%d updaterSession=%d observedBlocking=true%n", stage, gate.firstPid.get(), writerPid.get());
        } finally { finish(executor); }
    }

    @Test void preloadedCandidateUsesTheNewlySavedDatabaseSelectionInsteadOfItsOldCache() throws Exception {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING); UUID id = row(owner, batch, 2, "Fresh saved selection", false);
        var executor = Executors.newSingleThreadExecutor();
        try {
            transaction().executeWithoutResult(status -> {
                var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(context.getBean(EntityManagerFactory.class));
                var cached = manager.find(ImportCandidate.class, id); assertFalse(cached.getSelectedForImport());
                try {
                    executor.submit(() -> review.saveReview(owner.user(), batch, new SaveImportReviewRequest(0L,
                            List.of(new CandidateReviewDecision(id, true, null, false, null))))).get(10, TimeUnit.SECONDS);
                } catch (Exception failure) { throw new AssertionError("Independent Review must commit", failure); }
                var result = confirm(owner, batch, 1); assertEquals(1, result.confirmedCount());
                assertFalse(cached.getSelectedForImport(), "No global refresh may overwrite the caller's cached object");
            });
            assertEquals(1, ledgerRows(batch).size()); assertEquals(2, version(batch));
            assertTrue((Boolean) candidate(id).get("selected_for_import"));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void confirmedRetryReadsPersistentSummaryEvenWhenOuterBatchEntityIsStale() throws Exception {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING); selectedRow(owner, batch, 2, "Selection");
        var executor = Executors.newSingleThreadExecutor();
        try {
            transaction().executeWithoutResult(status -> {
                var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(context.getBean(EntityManagerFactory.class));
                var cached = manager.find(ImportBatch.class, batch); assertEquals(ImportStatus.REVIEWING, cached.getStatus());
                ImportConfirmationResult independent;
                try { independent = executor.submit(() -> confirm(owner, batch, 0)).get(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError("Independent Confirm must commit", failure); }
                assertEquals(independent, confirm(owner, batch, 0)); assertEquals(ImportStatus.REVIEWING, cached.getStatus());
            });
            assertEquals(1, ledgerRows(batch).size()); assertEquals(1, version(batch));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void preloadedAccountAndCategoryCannotHideACommittedDeactivation() throws Exception {
        for (String scope : List.of("financial_account", "category")) {
            Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING); UUID row = selectedRow(owner, batch, 2, "Selection");
            UUID category = category(owner, "EXPENSE", true);
            jdbc.update("UPDATE " + table("import_candidate") + " SET category_id=? WHERE id=?", category, row);
            var original = batchSnapshot(batch); var executor = Executors.newSingleThreadExecutor();
            try {
                assertThrows(InvalidImportRequestException.class, () -> transaction().executeWithoutResult(status -> {
                    var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(context.getBean(EntityManagerFactory.class));
                    assertTrue(manager.find(FinancialAccount.class, owner.account()).getActive());
                    assertTrue(manager.find(Category.class, category).getActive());
                    UUID id = scope.equals("category") ? category : owner.account();
                    try { executor.submit(() -> jdbc.update("UPDATE " + table(scope) + " SET active=FALSE WHERE id=?", id)).get(10, TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new AssertionError("Independent deactivation must commit", failure); }
                    confirm(owner, batch, 0);
                }));
                assertEquals(original, batchSnapshot(batch)); assertTrue(ledgerRows(batch).isEmpty());
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    @Test void realFailureAfterOneLedgerInsertRollsBackAllRowsAndBatchAndRetainsReviewDecisions() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
        selectedRow(owner, batch, 2, "First selection"); selectedRow(owner, batch, 3, "Second selection");
        var rows = candidates(batch); var before = batchSnapshot(batch);
        jdbc.execute("CREATE FUNCTION " + schema + ".fail_second_entry() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.source_batch_id='" + batch + "'::uuid AND EXISTS(SELECT 1 FROM " + table("ledger_entry")
                + " WHERE source_batch_id=NEW.source_batch_id) THEN RAISE EXCEPTION 'synthetic failure after first ledger insert'"
                + " USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_second_entry BEFORE INSERT ON " + table("ledger_entry")
                + " FOR EACH ROW EXECUTE FUNCTION " + schema + ".fail_second_entry()");
        try {
            var failure = assertThrows(RuntimeException.class, () -> confirm(owner, batch, 0));
            assertTrue(hasSqlState(failure, "23514")); assertFalse(failure instanceof ImportConfirmationConflictException);
            assertTrue(ledgerRows(batch).isEmpty()); assertEquals(before, batchSnapshot(batch)); assertEquals(rows, candidates(batch));
        } finally {
            jdbc.execute("DROP TRIGGER fail_second_entry ON " + table("ledger_entry")); jdbc.execute("DROP FUNCTION " + schema + ".fail_second_entry()");
        }
        assertEquals(2, confirm(owner, batch, 0).confirmedCount());
        System.out.println("IMPORT_CONFIRM_INSERT_ROLLBACK sqlState=23514 firstInsertExecuted=true residualLedger=0 residualBatchChanges=0 retryConfirmed=2");
    }

    @Test void failureOfFinalBatchUpdateAlsoRollsBackAlreadyInsertedLedger() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING); selectedRow(owner, batch, 2, "Selection");
        var before = batchSnapshot(batch); var rows = candidates(batch);
        jdbc.execute("CREATE FUNCTION " + schema + ".fail_confirmation_state() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.id='" + batch + "'::uuid AND NEW.status='CONFIRMED' THEN "
                + "IF NOT EXISTS(SELECT 1 FROM " + table("ledger_entry") + " WHERE source_batch_id=NEW.id) THEN "
                + "RAISE EXCEPTION 'expected an inserted ledger row before batch update' USING ERRCODE='P0001'; END IF; "
                + "RAISE EXCEPTION 'synthetic final batch failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_confirmation_state BEFORE UPDATE ON " + table("import_batch")
                + " FOR EACH ROW EXECUTE FUNCTION " + schema + ".fail_confirmation_state()");
        try {
            var failure = assertThrows(RuntimeException.class, () -> confirm(owner, batch, 0));
            assertTrue(hasSqlState(failure, "23514")); assertTrue(ledgerRows(batch).isEmpty());
            assertEquals(before, batchSnapshot(batch)); assertEquals(rows, candidates(batch));
        } finally {
            jdbc.execute("DROP TRIGGER fail_confirmation_state ON " + table("import_batch")); jdbc.execute("DROP FUNCTION " + schema + ".fail_confirmation_state()");
        }
    }

    @Test void requiredOuterRollbackUndoesTheReturnedConfirmationAndAllLedgerInserts() {
        Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING); selectedRow(owner, batch, 2, "Selection");
        var before = batchSnapshot(batch); var rows = candidates(batch);
        transaction().executeWithoutResult(status -> {
            var result = confirm(owner, batch, 0); assertEquals(ImportStatus.CONFIRMED, result.status()); assertEquals(1, result.version());
            assertEquals(1, ledgerRows(batch).size()); status.setRollbackOnly();
        });
        assertEquals(before, batchSnapshot(batch)); assertEquals(rows, candidates(batch)); assertTrue(ledgerRows(batch).isEmpty());
    }

    @Test void boundedWholeBatchAndCategoryReadsDoNotGrowPerSelectedCandidate() {
        var counts = new ArrayList<Integer>();
        for (int size : List.of(2, 100)) {
            Owner owner = owner(); UUID batch = batch(owner, ImportStatus.REVIEWING);
            for (int i = 0; i < size; i++) {
                UUID id = selectedRow(owner, batch, i + 2, "Unique merchant " + i); UUID category = category(owner, "EXPENSE", true);
                jdbc.update("UPDATE " + table("import_candidate") + " SET category_id=? WHERE id=?", category, id);
            }
            var rows = candidates(batch); sql.clear(); var result = confirm(owner, batch, 0);
            assertEquals(size, result.confirmedCount()); assertEquals(0, result.notImportedCount()); assertEquals(rows, candidates(batch));
            var selects = sql.selects(); counts.add(selects.size());
            assertEquals(1, selects.stream().filter(s -> s.contains("union all")).count());
            assertEquals(1, selects.stream().filter(s -> s.contains(".category ")).count());
            String categories = selects.stream().filter(s -> s.contains(".category ")).findFirst().orElseThrow();
            assertTrue(categories.contains("order by") && categories.contains("for share"), categories);
            assertTrue(selects.stream().anyMatch(s -> s.contains("financial_account") && s.contains("for no key update")));
        }
        assertEquals(counts.getFirst(), counts.getLast());
        System.out.printf("IMPORT_CONFIRM_QUERIES selected=2,100 selects=%s stable=true jdbcBatchingNotClaimed=true%n", counts);
    }

    private record Owner(UUID user, UUID account) {}
    private static Owner owner() {
        UUID user = UUID.randomUUID(), account = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("app_user") + " (id,auth_subject,display_name) VALUES (?,?,?)", user, "confirm-" + user, "Synthetic owner");
        jdbc.update("INSERT INTO " + table("financial_account") + " (id,user_id,name,account_type) VALUES (?,?,?,'CREDIT_CARD')", account, user, "Synthetic account");
        return new Owner(user, account);
    }
    private static UUID category(Owner owner, String type, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("category") + " (id,user_id,name,entry_type,active) VALUES (?,?,?,?,?)", id, owner.user(), "Synthetic " + id, type, active);
        return id;
    }
    private static UUID batch(Owner owner, ImportStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("import_batch") + " (id,user_id,account_id,upload_key,file_name,file_sha256,status,bank_type,confirmed_at,closed_at)"
                + " VALUES (?,?,?,?,'synthetic-confirm.csv',?,?,'RBC_VISA',CASE WHEN ?='CONFIRMED' THEN now() ELSE NULL END,"
                + "CASE WHEN ? IN ('CONFIRMED','CANCELLED','FAILED') THEN now() ELSE NULL END)", id, owner.user(), owner.account(),
                UUID.randomUUID(), "a".repeat(64), status.name(), status.name(), status.name());
        return id;
    }
    private static UUID selectedRow(Owner owner, UUID batch, int rowNumber, String description) { return row(owner, batch, rowNumber, description, true); }
    private static UUID row(Owner owner, UUID batch, int rowNumber, String description, boolean selected) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("import_candidate") + " (id,batch_id,user_id,row_number,transaction_date,currency_code,source_direction,entry_type,amount,description,selected_for_import)"
                + " VALUES (?,?,?,? ,?,'CAD','DEBIT','EXPENSE',30.00,?,?)", id, batch, owner.user(), rowNumber, DATE, description, selected);
        return id;
    }
    private static UUID ledger(Owner owner, UUID batch, Integer rowNumber, String description, String bankId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("ledger_entry") + " (id,user_id,account_id,source_batch_id,source_row_number,transaction_date,currency_code,entry_type,amount,description,bank_transaction_id)"
                + " VALUES (?,?,?,?,?,?,'CAD','EXPENSE',30.00,?,?)", id, owner.user(), owner.account(), batch, rowNumber, DATE, description, bankId);
        return id;
    }
    private static ImportConfirmationResult confirm(Owner owner, UUID batch, long version) {
        return service.confirmImport(owner.user(), batch, new ConfirmImportRequest(version));
    }
    private static ImportPreviewResponse preview(Owner owner, UUID batch) { return query.getImportPreview(owner.user(), batch, PageRequest.of(0, 100)); }
    private static void refreshAndAcknowledge(Owner owner, UUID batch) {
        duplicates.refreshDuplicateWarnings(owner.user(), batch); var preview = preview(owner, batch);
        var decisions = preview.candidates().stream().filter(CandidateSummary::selectedForImport)
                .map(r -> new CandidateReviewDecision(r.candidateId(), true, r.categoryId(), r.duplicateWarning(),
                        r.duplicateWarning() ? r.duplicateMatchSignature() : null)).toList();
        review.saveReview(owner.user(), batch, new SaveImportReviewRequest(preview.version(), decisions));
    }
    private static TransactionTemplate transaction() { return new TransactionTemplate(context.getBean(JpaTransactionManager.class)); }
    private static String table(String name) { return schema + "." + name; }
    private static long version(UUID batch) { return jdbc.queryForObject("SELECT version FROM " + table("import_batch") + " WHERE id=?", Long.class, batch); }
    private static List<Map<String, Object>> candidates(UUID batch) { return jdbc.queryForList("SELECT * FROM " + table("import_candidate") + " WHERE batch_id=? ORDER BY id", batch); }
    private static Map<String, Object> candidate(UUID id) { return jdbc.queryForMap("SELECT * FROM " + table("import_candidate") + " WHERE id=?", id); }
    private static Map<String, Object> batchSnapshot(UUID batch) { return jdbc.queryForMap("SELECT * FROM " + table("import_batch") + " WHERE id=?", batch); }
    private static List<Map<String, Object>> ledgerRows(UUID batch) { return jdbc.queryForList("SELECT * FROM " + table("ledger_entry") + " WHERE source_batch_id=? ORDER BY source_row_number", batch); }
    private static boolean hasSqlState(Throwable failure, String state) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) return true;
        return false;
    }
    private static int backendPid(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) { assertTrue(result.next()); return result.getInt(1); }
    }
    private static void awaitSecondBlocked() throws Exception {
        assertTrue(gate.secondAttempted.await(10, TimeUnit.SECONDS)); assertNotEquals(gate.firstPid.get(), gate.secondPid.get());
        awaitBlocked(gate.firstPid.get(), gate.secondPid.get());
    }
    private static void awaitBlocked(int holder, int waiter) throws Exception {
        boolean blocked = false; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            blocked = Boolean.TRUE.equals(jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, holder, waiter));
            if (blocked) break; TimeUnit.MILLISECONDS.sleep(20);
        }
        assertTrue(blocked, "PostgreSQL must observe actual lock blocking, not a timing guess");
    }
    private static void finish(ExecutorService executor) throws Exception {
        gate.releaseFirst.countDown(); gate.disarm(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
    static class LockGate {
        private volatile String stage;
        private UUID key;
        private UUID targetBatch;
        private final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger firstPid = new AtomicInteger(), secondPid = new AtomicInteger();
        volatile CountDownLatch firstLocked, secondAttempted, releaseFirst;
        void arm(String stage, UUID key, UUID targetBatch) {
            attempts.set(0); firstPid.set(0); secondPid.set(0); this.key = key; this.targetBatch = targetBatch;
            firstLocked = new CountDownLatch(1); secondAttempted = new CountDownLatch(1); releaseFirst = new CountDownLatch(1); this.stage = stage;
        }
        void disarm() { stage = null; }
        int before(String eventStage, String method, Object[] args, EntityManagerFactory factory) {
            if (!eventStage.equals(stage)) return 0;
            boolean locking = eventStage.equals("BATCH") ? method.equals("findConfirmationSnapshot") || method.equals("findOwnedForDuplicateScan")
                    : eventStage.equals("ACCOUNT") ? method.equals("findOwnedForConfirmation") : method.equals("findForConfirmation");
            if (!locking || Arrays.stream(args).noneMatch(arg -> key.equals(arg) || arg instanceof Collection<?> values && values.contains(key))) return 0;
            int attempt = attempts.incrementAndGet();
            var manager = EntityManagerFactoryUtils.getTransactionalEntityManager(factory); assertNotNull(manager);
            manager.createNativeQuery("SET LOCAL lock_timeout='8s'").executeUpdate();
            int pid = ((Number) manager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue();
            if (attempt == 1) firstPid.set(pid);
            if (attempt == 2) { secondPid.set(pid); secondAttempted.countDown(); }
            return attempt;
        }
        void after(int attempt) throws Exception {
            if (attempt == 1) { firstLocked.countDown(); assertTrue(releaseFirst.await(15, TimeUnit.SECONDS), "Release the held lock before timeout"); }
        }
    }
    static class SqlCapture implements StatementInspector {
        private final ConcurrentLinkedQueue<String> statements = new ConcurrentLinkedQueue<>();
        @Override public String inspect(String statement) {
            statements.add(statement.replaceAll("(?s)/\\*.*?\\*/", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip()); return statement;
        }
        void clear() { statements.clear(); }
        List<String> all() { return List.copyOf(statements); }
        List<String> selects() { return all().stream().filter(s -> s.startsWith("select ")).toList(); }
    }
}
