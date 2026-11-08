package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.EntryType;
import com.finguard.transactionapi.finance.domain.ImportStatus;
import com.finguard.transactionapi.finance.dto.CandidateSummary;
import com.finguard.transactionapi.finance.dto.ImportPreviewResponse;
import com.finguard.transactionapi.finance.exception.ImportBatchNotFoundException;
import com.finguard.transactionapi.finance.exception.InvalidImportBatchQueryException;
import com.finguard.transactionapi.finance.repository.ImportBatchRepository;
import com.finguard.transactionapi.finance.service.ImportBatchQueryService;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.SAME_THREAD)
class ImportPreviewIntegrationTest {
    private static final Instant CREATED = Instant.parse("2026-11-01T12:00:00Z");
    private static final Sort ORDER = Sort.by(Sort.Order.desc("transactionDate").nullsLast(),
            Sort.Order.asc("rowNumber"));
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static ImportBatchQueryService service;
    private static SqlCapture sql;
    private static String schema;

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = ImportBatchRepository.class)
    @Import(ImportBatchQueryService.class)
    static class Config {
        @Bean SqlCapture sqlCapture() { return new SqlCapture(); }

        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource, SqlCapture capture) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
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
    }

    @BeforeAll static void setup() {
        var dataSource = new DriverManagerDataSource(
                System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/finguard"),
                System.getenv().getOrDefault("DB_USERNAME", "finguard"), System.getenv("DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        Integer serverVersion = jdbc.queryForObject("SHOW server_version_num", Integer.class);
        assertNotNull(serverVersion);
        assertEquals(17, serverVersion / 10_000, "These fixtures require PostgreSQL 17");
        schema = "import_preview_test_" + UUID.randomUUID().toString().replace("-", "");
        var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
        assertEquals(4, flyway.migrate().migrationsExecuted);
        flyway.validate();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.register(Config.class);
        context.refresh();
        service = context.getBean(ImportBatchQueryService.class);
        sql = context.getBean(SqlCapture.class);
    }

    @AfterAll static void cleanup() {
        if (context != null) context.close();
        if (jdbc != null && schema != null) {
            assertTrue(schema.matches("import_preview_test_[a-f0-9]{32}"));
            // Only this run's isolated fixture schema is removed.
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test void returnsBatchMetadataAndEveryStoredReviewFieldWithoutRevalidatingRows() {
        Owner owner = owner();
        UUID batch = batch(owner, ImportStatus.REVIEWING);
        List<UUID> candidates = fourCandidateFixture(owner, batch);
        ImportPreviewResponse preview = service.getImportPreview(owner.userId(), batch, PageRequest.of(0, 2));
        assertEquals(batch, preview.id());
        assertEquals(owner.accountId(), preview.accountId());
        assertEquals("synthetic-preview.csv", preview.fileName());
        assertEquals(ImportStatus.REVIEWING, preview.status());
        assertEquals(7, preview.version());
        assertEquals(4, preview.candidates().getTotalElements());
        assertEquals(0, preview.candidates().getNumber());
        assertEquals(2, preview.candidates().getSize());
        assertEquals(ORDER, preview.candidates().getSort());
        assertEquals(List.of(3, 4), rows(preview));
        CandidateSummary badAmount = preview.candidates().getContent().getFirst();
        assertEquals(candidates.get(2), badAmount.candidateId());
        assertEquals(EntryType.EXPENSE, badAmount.entryType());
        assertNull(badAmount.amount());
        assertEquals("CAD", badAmount.currencyCode());
        assertEquals(LocalDate.of(2026, 11, 1), badAmount.transactionDate());
        assertEquals("Synthetic amount review", badAmount.description());
        assertNull(badAmount.occurredAt());
        assertNull(badAmount.categoryId());
        assertFalse(badAmount.selectedForImport());
        assertEquals(storedErrors(candidates.get(2)), badAmount.validationErrors());
        CandidateSummary credit = preview.candidates().getContent().get(1);
        assertNull(credit.entryType(), "Stored credit must not be guessed as INCOME");
        assertEquals(new BigDecimal("5.00"), credit.amount());
        assertEquals("Synthetic credit", credit.description());
        assertEquals(storedErrors(candidates.get(3)), credit.validationErrors());
        assertFalse(credit.selectedForImport());
        assertFalse(credit.duplicateWarning());
        assertFalse(credit.duplicateAcknowledged());

        ImportPreviewResponse tail = service.getImportPreview(owner.userId(), batch, PageRequest.of(1, 2));
        assertEquals(List.of(1, 2), rows(tail));
        CandidateSummary normal = tail.candidates().getContent().getFirst();
        assertEquals(candidates.getFirst(), normal.candidateId());
        assertEquals(EntryType.EXPENSE, normal.entryType());
        assertEquals(new BigDecimal("12.34"), normal.amount());
        assertEquals(Instant.parse("2026-10-30T12:00:00Z"), normal.occurredAt());
        assertEquals(owner.categoryId(), normal.categoryId());
        assertTrue(normal.selectedForImport());
        assertTrue(normal.duplicateWarning());
        assertTrue(normal.duplicateAcknowledged());
        assertEquals("[]", normal.validationErrors());
        CandidateSummary badDate = tail.candidates().getContent().get(1);
        assertNull(badDate.transactionDate());
        assertEquals(new BigDecimal("8.25"), badDate.amount());
        assertEquals("Synthetic date review", badDate.description());
        assertEquals(storedErrors(candidates.get(1)), badDate.validationErrors());
        // Safe synthetic database output only; fixtures do not demonstrate an upload workflow.
        System.out.printf("IMPORT_PREVIEW_FIXTURE_RESULT file=%s status=%s version=%d page=%d size=%d total=%d rows=%s%n",
                preview.fileName(), preview.status(), preview.version(), preview.candidates().getNumber(),
                preview.candidates().getSize(), preview.candidates().getTotalElements(),
                preview.candidates().getContent());
    }

    @Test void contentAndTotalStayWithinTheRequestedBatchAndOwner() {
        Owner owner = owner();
        UUID batch = batch(owner, ImportStatus.REVIEWING);
        normalCandidates(owner, batch, 17);
        normalCandidates(owner, batch(owner, ImportStatus.REVIEWING), 29);
        Owner other = owner();
        normalCandidates(other, batch(other, ImportStatus.REVIEWING), 36);
        var preview = service.getImportPreview(owner.userId(), batch, PageRequest.of(0, 15));
        assertEquals(15, preview.candidates().getNumberOfElements());
        assertEquals(17, preview.candidates().getTotalElements());
        List<UUID> expected = jdbc.queryForList("SELECT id FROM " + table("import_candidate")
                + " WHERE batch_id = ? AND user_id = ? ORDER BY row_number LIMIT 15", UUID.class, batch, owner.userId());
        assertEquals(expected, preview.candidates().stream().map(CandidateSummary::candidateId).toList());
    }

    @Test void missingAndForeignBatchesHaveIdenticalNotFoundResponsesWithoutCandidateQueries() {
        Owner owner = owner();
        Owner other = owner();
        UUID batch = batch(owner, ImportStatus.REVIEWING);
        fourCandidateFixture(owner, batch);
        sql.clear();
        var foreign = assertThrows(ImportBatchNotFoundException.class,
                () -> service.getImportPreview(other.userId(), batch, PageRequest.of(0, 15)));
        assertEquals(1, sql.all().size());
        assertTrue(sql.all().getFirst().contains("import_batch"));
        assertTrue(sql.all().stream().noneMatch(statement -> statement.contains("import_candidate")));
        sql.clear();
        var absent = assertThrows(ImportBatchNotFoundException.class,
                () -> service.getImportPreview(other.userId(), UUID.randomUUID(), PageRequest.of(0, 15)));
        assertEquals(foreign.getClass(), absent.getClass());
        assertEquals(foreign.getMessage(), absent.getMessage());
        assertEquals(1, sql.all().size());
        assertTrue(sql.all().stream().noneMatch(statement -> statement.contains("import_candidate")));
    }

    @Test void databaseOrdersDatesDescendingThenRowNumberAndPlacesNullDatesLastAcrossPages() {
        Owner owner = owner();
        UUID batch = batch(owner, ImportStatus.REVIEWING);
        fourCandidateFixture(owner, batch);
        sql.clear();
        var first = service.getImportPreview(owner.userId(), batch, PageRequest.of(0, 3, ORDER));
        assertEquals(List.of(3, 4, 1), rows(first));
        String candidateSql = candidatePageSql(sql.all());
        assertTrue(candidateSql.contains("transaction_date desc nulls last"), candidateSql);
        assertTrue(candidateSql.matches(".*order by .*transaction_date desc nulls last,[ ]*[^ ,]+\\.row_number( asc)?.*"),
                candidateSql);
        var second = service.getImportPreview(owner.userId(), batch, PageRequest.of(1, 3));
        assertEquals(List.of(2), rows(second));
        assertNull(second.candidates().getContent().getFirst().transactionDate());
        assertEquals(rows(first), rows(service.getImportPreview(owner.userId(), batch, PageRequest.of(0, 3))));
    }

    @Test void returnsExistingEmptyBatchAndPreservesTotalBeyondTheLastCandidatePage() {
        Owner owner = owner();
        UUID emptyBatch = batch(owner, ImportStatus.REVIEWING);
        var empty = service.getImportPreview(owner.userId(), emptyBatch, PageRequest.of(0, 15));
        assertEquals(emptyBatch, empty.id());
        assertTrue(empty.candidates().isEmpty());
        assertEquals(0, empty.candidates().getTotalElements());
        UUID batch = batch(owner, ImportStatus.REVIEWING);
        normalCandidates(owner, batch, 17);
        var last = service.getImportPreview(owner.userId(), batch, PageRequest.of(1, 15));
        assertEquals(List.of(16, 17), rows(last));
        assertEquals(17, last.candidates().getTotalElements());
        var past = service.getImportPreview(owner.userId(), batch, PageRequest.of(2, 15));
        assertTrue(past.candidates().isEmpty());
        assertEquals(17, past.candidates().getTotalElements());
        assertEquals(2, past.candidates().getTotalPages());
        assertEquals(ORDER, past.candidates().getSort());
    }

    @Test void readsActualStatusesEvenWithAnInactiveAccountWithoutReopeningBatches() {
        Owner owner = owner();
        jdbc.update("UPDATE " + table("financial_account") + " SET active = FALSE WHERE id = ?", owner.accountId());
        for (ImportStatus status : ImportStatus.values()) {
            UUID batch = batch(owner, status);
            normalCandidates(owner, batch, 1);
            var before = jdbc.queryForMap("SELECT * FROM " + table("import_batch") + " WHERE id = ?", batch);
            var preview = service.getImportPreview(owner.userId(), batch, PageRequest.of(0, 15));
            assertEquals(status, preview.status());
            assertEquals(7, preview.version());
            assertEquals(1, preview.candidates().getTotalElements());
            assertEquals(before, jdbc.queryForMap("SELECT * FROM " + table("import_batch") + " WHERE id = ?", batch));
        }
    }

    @Test void separateDatabaseTransactionsReadBackTheSameStoredPreviewWithoutInterveningWrites() {
        Owner owner = owner();
        UUID batch = batch(owner, ImportStatus.REVIEWING);
        fourCandidateFixture(owner, batch);
        var transaction = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        transaction.setReadOnly(true);
        ReadContext first = transaction.execute(status -> new ReadContext(
                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class),
                service.getImportPreview(owner.userId(), batch, PageRequest.of(0, 15))));
        ReadContext second = transaction.execute(status -> new ReadContext(
                jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class),
                service.getImportPreview(owner.userId(), batch, PageRequest.of(0, 15))));
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first.backendPid(), second.backendPid(), "DriverManagerDataSource opens separate database sessions");
        assertEquals(first.preview(), second.preview());
    }

    @Test void queryLeavesEveryBatchAndCandidateFieldAndBatchVersionUnchanged() {
        Owner owner = owner();
        UUID batch = batch(owner, ImportStatus.REVIEWING);
        fourCandidateFixture(owner, batch);
        normalCandidates(owner, batch(owner, ImportStatus.CONFIRMED), 2);
        var batchesBefore = snapshot("import_batch");
        var candidatesBefore = snapshot("import_candidate");
        assertTrue(candidatesBefore.stream().noneMatch(row -> row.containsKey("version")),
                "Candidate has no version column in existing V2");
        sql.clear();
        service.getImportPreview(owner.userId(), batch, PageRequest.of(0, 2));
        service.getImportPreview(owner.userId(), batch, PageRequest.of(1, 2));
        assertTrue(sql.all().stream().allMatch(statement -> statement.startsWith("select ")));
        assertTrue(sql.all().stream().noneMatch(statement -> statement.contains("for update")));
        assertEquals(batchesBefore, snapshot("import_batch"));
        assertEquals(candidatesBefore, snapshot("import_candidate"));
    }

    @Test void usesDatabasePaginationAndScopedCountWithConstantQueriesAndNoAssociationLoads() {
        Owner owner = owner();
        UUID batch = batch(owner, ImportStatus.REVIEWING);
        normalCandidates(owner, batch, 17);
        sql.clear();
        var single = service.getImportPreview(owner.userId(), batch, PageRequest.of(0, 1));
        assertEquals(1, single.candidates().getNumberOfElements());
        assertEquals(3, sql.all().size(), "Batch ownership, candidate page, scoped candidate count");
        sql.clear();
        var larger = service.getImportPreview(owner.userId(), batch, PageRequest.of(0, 15));
        assertEquals(15, larger.candidates().getNumberOfElements());
        List<String> statements = sql.all();
        assertEquals(3, statements.size(), "Query count must not increase with candidate rows");
        assertTrue(statements.stream().allMatch(statement -> statement.startsWith("select ")));
        assertTrue(statements.stream().noneMatch(statement -> statement.contains("for update")
                || statement.contains("group by") || statement.contains(" join ")));
        assertTrue(statements.getFirst().contains("import_batch"));
        String batchWhere = whereClause(statements.getFirst());
        assertTrue(batchWhere.contains(".id=?"), batchWhere);
        assertTrue(batchWhere.contains(".user_id=?"), batchWhere);
        String page = candidatePageSql(statements);
        assertTrue(page.contains("fetch first ? rows only"), page);
        assertTrue(page.contains("transaction_date desc nulls last"), page);
        String count = statements.stream().filter(statement -> statement.contains("import_candidate")
                && statement.contains("count(")).findFirst().orElseThrow();
        for (String statement : List.of(page, count)) {
            String where = whereClause(statement);
            assertTrue(where.contains(".batch_id=?"), where);
            assertTrue(where.contains(".user_id=?"), where);
            assertFalse(where.contains("selected_for_import"), where);
            assertFalse(where.contains("validation_errors"), where);
            assertFalse(where.contains("entry_type"), where);
        }
        sql.clear();
        service.getImportPreview(owner.userId(), batch, PageRequest.of(1, 1));
        String offsetPage = candidatePageSql(sql.all());
        assertTrue(offsetPage.contains("offset ? rows"), offsetPage);
        assertTrue(offsetPage.contains("fetch first ? rows only"), offsetPage);
    }

    @Test void invalidIdentityPaginationAndSortingAreRejectedBeforeQueries() {
        UUID userId = UUID.randomUUID();
        UUID batch = UUID.randomUUID();
        sql.clear();
        assertThrows(InvalidImportBatchQueryException.class,
                () -> service.getImportPreview(null, batch, PageRequest.of(0, 15)));
        assertThrows(InvalidImportBatchQueryException.class,
                () -> service.getImportPreview(userId, null, PageRequest.of(0, 15)));
        assertThrows(InvalidImportBatchQueryException.class, () -> service.getImportPreview(userId, batch, null));
        for (Pageable invalid : List.of(Pageable.unpaged(), PageRequest.of(0, 101),
                PageRequest.of(Integer.MAX_VALUE, 100), PageRequest.of(0, 15, Sort.by("createdAt")),
                PageRequest.of(0, 15, Sort.by(Sort.Order.desc("transactionDate"), Sort.Order.asc("rowNumber"))),
                PageRequest.of(0, 15, Sort.by(Sort.Order.desc("transactionDate").nullsFirst(), Sort.Order.asc("rowNumber"))))) {
            assertThrows(InvalidImportBatchQueryException.class, () -> service.getImportPreview(userId, batch, invalid));
        }
        assertTrue(sql.all().isEmpty());
    }

    private record Owner(UUID userId, UUID accountId, UUID categoryId) {}
    private record ReadContext(int backendPid, ImportPreviewResponse preview) {}

    private static Owner owner() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("app_user") + " (id,auth_subject,display_name) VALUES (?,?,?)",
                userId, "preview-" + userId, "Synthetic owner");
        jdbc.update("INSERT INTO " + table("financial_account")
                        + " (id,user_id,name,account_type) VALUES (?,?,?,'CREDIT_CARD')",
                accountId, userId, "Synthetic account");
        jdbc.update("INSERT INTO " + table("category") + " (id,user_id,name,entry_type) VALUES (?,?,?,'EXPENSE')",
                categoryId, userId, "Synthetic category");
        return new Owner(userId, accountId, categoryId);
    }

    private static UUID batch(Owner owner, ImportStatus status) {
        UUID id = UUID.randomUUID();
        boolean closed = status == ImportStatus.CONFIRMED || status == ImportStatus.CANCELLED || status == ImportStatus.FAILED;
        jdbc.update("INSERT INTO " + table("import_batch")
                        + " (id,user_id,account_id,upload_key,file_name,file_sha256,status,version,created_at,confirmed_at,closed_at)"
                        + " VALUES (?,?,?,?,'synthetic-preview.csv',?,?,7,?,?,?)",
                id, owner.userId(), owner.accountId(), UUID.randomUUID(), "a".repeat(64), status.name(),
                Timestamp.from(CREATED), status == ImportStatus.CONFIRMED ? Timestamp.from(CREATED) : null,
                closed ? Timestamp.from(CREATED) : null);
        return id;
    }

    private static void normalCandidates(Owner owner, UUID batch, int count) {
        for (int row = 1; row <= count; row++) {
            candidate(owner, batch, row, "EXPENSE", new BigDecimal("12.34"), LocalDate.of(2026, 11, 1),
                    "Synthetic normal", "[]");
        }
    }

    private static List<UUID> fourCandidateFixture(Owner owner, UUID batch) {
        var ids = new ArrayList<UUID>();
        ids.add(candidate(owner, batch, 1, "EXPENSE", new BigDecimal("12.34"), LocalDate.of(2026, 10, 30),
                "Synthetic normal", "[]"));
        jdbc.update("UPDATE " + table("import_candidate")
                        + " SET occurred_at = ?, category_id = ?, selected_for_import = TRUE, duplicate_warning = TRUE,"
                        + " duplicate_acknowledged = TRUE, duplicate_match_signature = ?, bank_transaction_id = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-10-30T12:00:00Z")), owner.categoryId(), "b".repeat(64),
                "synthetic-internal-bank-id", ids.getFirst());
        ids.add(candidate(owner, batch, 2, "EXPENSE", new BigDecimal("8.25"), null,
                "Synthetic date review", "[{\"code\":\"INVALID_DATE\",\"field\":\"transactionDate\"}]"));
        ids.add(candidate(owner, batch, 3, "EXPENSE", null, LocalDate.of(2026, 11, 1),
                "Synthetic amount review", "[{\"code\":\"INVALID_AMOUNT\",\"field\":\"amount\"}]"));
        ids.add(candidate(owner, batch, 4, null, new BigDecimal("5.00"), LocalDate.of(2026, 11, 1),
                "Synthetic credit", "[{\"code\":\"REVIEW_REQUIRED\"}]"));
        return ids;
    }

    private static UUID candidate(Owner owner, UUID batch, int row, String type, BigDecimal amount,
                                  LocalDate date, String description, String errors) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("import_candidate")
                        + " (id,batch_id,user_id,row_number,entry_type,amount,currency_code,transaction_date,description,validation_errors)"
                        + " VALUES (?,?,?,?,?,?,'CAD',?,?,CAST(? AS jsonb))",
                id, batch, owner.userId(), row, type, amount, date, description, errors);
        return id;
    }

    private static String table(String name) { return schema + "." + name; }

    private static String storedErrors(UUID candidateId) {
        return jdbc.queryForObject("SELECT validation_errors::text FROM " + table("import_candidate")
                + " WHERE id = ?", String.class, candidateId);
    }

    private static List<Map<String, Object>> snapshot(String name) {
        return jdbc.queryForList("SELECT * FROM " + table(name) + " ORDER BY id");
    }

    private static List<Integer> rows(ImportPreviewResponse preview) {
        return preview.candidates().stream().map(CandidateSummary::rowNumber).toList();
    }

    private static String candidatePageSql(List<String> statements) {
        return statements.stream().filter(statement -> statement.contains("import_candidate")
                && !statement.contains("count(")).findFirst().orElseThrow();
    }

    private static String whereClause(String statement) {
        return statement.substring(statement.indexOf(" where ") + 7).split(" order by | offset | fetch ")[0];
    }

    static class SqlCapture implements StatementInspector {
        private final ConcurrentLinkedQueue<String> statements = new ConcurrentLinkedQueue<>();

        @Override public String inspect(String statement) {
            statements.add(statement.replaceAll("(?s)/\\*.*?\\*/", "").toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ").strip());
            return statement;
        }

        void clear() { statements.clear(); }
        List<String> all() { return List.copyOf(statements); }
    }
}
