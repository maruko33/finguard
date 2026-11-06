package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.ImportStatus;
import com.finguard.transactionapi.finance.dto.ImportBatchSummary;
import com.finguard.transactionapi.finance.exception.InvalidImportBatchQueryException;
import com.finguard.transactionapi.finance.repository.ImportBatchRepository;
import com.finguard.transactionapi.finance.service.ImportBatchQueryService;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.springframework.data.domain.Page;
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

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.SAME_THREAD)
class ImportBatchQueryIntegrationTest {
    private static final Instant CREATED = Instant.parse("2026-11-01T12:00:00Z");
    private static final Sort ORDER = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static ImportBatchQueryService service;
    private static ImportBatchRepository repository;
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
        assertEquals(17, serverVersion / 10_000, "Run these integration tests against PostgreSQL 17");
        schema = "import_batch_query_test_" + UUID.randomUUID().toString().replace("-", "");
        var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
        assertEquals(4, flyway.migrate().migrationsExecuted, "The isolated fixture must apply V1 through V4");
        flyway.validate();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.register(Config.class);
        context.refresh();
        service = context.getBean(ImportBatchQueryService.class);
        repository = context.getBean(ImportBatchRepository.class);
        sql = context.getBean(SqlCapture.class);
    }

    @AfterAll static void cleanup() {
        if (context != null) context.close();
        if (jdbc != null && schema != null) {
            assertTrue(schema.matches("import_batch_query_test_[a-f0-9]{32}"));
            // Remove only the schema this test created, never public or existing schemas/volumes.
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test void pagesOnlyTheOwnersSeventeenReviewingBatchesDespiteNewerForeignAndClosedBatches() {
        Owner owner = owner();
        Owner other = owner();
        List<UUID> owned = reviewing(owner, 17, CREATED);
        reviewing(other, 30, CREATED.plusSeconds(100_000));
        for (ImportStatus status : List.of(ImportStatus.DRAFT, ImportStatus.CONFIRMED,
                ImportStatus.CANCELLED, ImportStatus.FAILED)) {
            batch(owner, status, CREATED.plusSeconds(200_000), UUID.randomUUID());
        }
        UUID newest = owned.getLast();
        fourCandidateFixture(owner, newest);
        var page = service.listReviewingBatches(owner.userId(), PageRequest.of(0, 15));
        assertEquals(15, page.getNumberOfElements());
        assertEquals(17, page.getTotalElements());
        assertEquals(2, page.getTotalPages());
        assertEquals(0, page.getNumber());
        assertEquals(15, page.getSize());
        assertEquals(ORDER, page.getSort());
        assertEquals(owned.reversed().subList(0, 15), ids(page));
        assertTrue(page.stream().allMatch(row -> row.accountId().equals(owner.accountId())
                && row.status() == ImportStatus.REVIEWING));
        ImportBatchSummary first = page.getContent().getFirst();
        assertEquals(newest, first.id());
        assertEquals("synthetic-batch.csv", first.fileName());
        assertEquals(7, first.version());
        assertEquals(CREATED.plusSeconds(16), first.createdAt());
        assertEquals(4, first.candidateCount());
        assertEquals(3, first.attentionRowCount());
        assertEquals(0, page.getContent().get(1).candidateCount());
        assertEquals(0, page.getContent().get(1).attentionRowCount());
        // Actual return values from synthetic database fixtures, not an upload-flow claim.
        System.out.printf("BATCH_QUERY_FIXTURE_RESULT page=%d size=%d total=%d content=%d file=%s "
                        + "status=%s version=%d candidateCount=%d attentionRowCount=%d%n",
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getNumberOfElements(),
                first.fileName(), first.status(), first.version(), first.candidateCount(), first.attentionRowCount());
    }

    @Test void countsNonemptyErrorArraysOncePerRowAndIndependentlyOfSelectionFlags() {
        Owner owner = owner();
        UUID batch = batch(owner, ImportStatus.REVIEWING, CREATED, UUID.randomUUID());
        fourCandidateFixture(owner, batch);
        ImportBatchSummary before = service.listReviewingBatches(owner.userId(), PageRequest.of(0, 15))
                .getContent().getFirst();
        assertEquals(4, before.candidateCount());
        assertEquals(3, before.attentionRowCount());
        // The normal candidate starts unselected; selection does not define whether a row needs attention.
        jdbc.update("UPDATE " + table("import_candidate")
                + " SET selected_for_import = TRUE WHERE batch_id = ? AND row_number = 1", batch);
        ImportBatchSummary after = service.listReviewingBatches(owner.userId(), PageRequest.of(0, 15))
                .getContent().getFirst();
        assertEquals(before, after);
    }

    @Test void groupedCountsRemainOwnerScopedEvenWhenPassedAnotherUsersBatchId() {
        Owner owner = owner();
        Owner other = owner();
        UUID ownBatch = batch(owner, ImportStatus.REVIEWING, CREATED, UUID.randomUUID());
        UUID otherBatch = batch(other, ImportStatus.REVIEWING, CREATED, UUID.randomUUID());
        fourCandidateFixture(owner, ownBatch);
        fourCandidateFixture(other, otherBatch);
        var counts = repository.countCandidatesByBatchIds(owner.userId(), List.of(ownBatch, otherBatch));
        assertEquals(1, counts.size());
        assertEquals(ownBatch, counts.getFirst().getBatchId());
        assertEquals(4, counts.getFirst().getCandidateCount());
        assertEquals(3, counts.getFirst().getAttentionRowCount());
    }

    @Test void returnsEmptyPagesForNoMatchingBatchesAndZerosForABatchWithoutCandidates() {
        Owner owner = owner();
        var noBatches = service.listReviewingBatches(owner.userId(), PageRequest.of(0, 15));
        assertTrue(noBatches.isEmpty());
        assertEquals(0, noBatches.getTotalElements());
        for (ImportStatus status : List.of(ImportStatus.DRAFT, ImportStatus.CONFIRMED,
                ImportStatus.CANCELLED, ImportStatus.FAILED)) {
            batch(owner, status, CREATED, UUID.randomUUID());
        }
        var noMatches = service.listReviewingBatches(owner.userId(), PageRequest.of(0, 15));
        assertTrue(noMatches.isEmpty());
        assertEquals(0, noMatches.getTotalElements());
        UUID emptyBatch = batch(owner, ImportStatus.REVIEWING, CREATED, UUID.randomUUID());
        var page = service.listReviewingBatches(owner.userId(), PageRequest.of(0, 15));
        assertEquals(List.of(emptyBatch), ids(page));
        assertEquals(1, page.getTotalElements());
        assertEquals(0, page.getContent().getFirst().candidateCount());
        assertEquals(0, page.getContent().getFirst().attentionRowCount());
    }

    @Test void preservesScopedTotalWhenRequestingTheLastPageAndPagesBeyondTheEnd() {
        Owner owner = owner();
        List<UUID> owned = reviewing(owner, 17, CREATED);
        reviewing(owner(), 25, CREATED.plusSeconds(100_000));
        var lastPage = service.listReviewingBatches(owner.userId(), PageRequest.of(1, 15));
        assertEquals(List.of(owned.get(1), owned.getFirst()), ids(lastPage));
        assertEquals(17, lastPage.getTotalElements());
        sql.clear();
        var beyondEnd = service.listReviewingBatches(owner.userId(), PageRequest.of(2, 15));
        assertTrue(beyondEnd.isEmpty());
        assertEquals(17, beyondEnd.getTotalElements());
        assertEquals(2, beyondEnd.getNumber());
        assertEquals(15, beyondEnd.getSize());
        assertEquals(ORDER, beyondEnd.getSort());
        assertTrue(sql.selects().stream().noneMatch(statement -> statement.contains("import_candidate")));
        assertTrue(sql.selects().stream().anyMatch(statement -> statement.contains("count(")
                && statement.contains("user_id") && statement.contains("status")));
    }

    @Test void breaksCreatedAtTiesByDescendingIdAcrossPageBoundaries() {
        Owner owner = owner();
        var tied = new ArrayList<UUID>();
        for (int index = 1; index <= 7; index++) {
            UUID id = UUID.fromString("01000000-0000-0000-0000-" + String.format("%012d", index));
            tied.add(batch(owner, ImportStatus.REVIEWING, CREATED, id));
        }
        List<UUID> expected = tied.stream().sorted(Comparator.comparing(UUID::toString).reversed()).toList();
        var first = service.listReviewingBatches(owner.userId(), PageRequest.of(0, 3, ORDER));
        var second = service.listReviewingBatches(owner.userId(), PageRequest.of(1, 3));
        var third = service.listReviewingBatches(owner.userId(), PageRequest.of(2, 3));
        assertEquals(expected.subList(0, 3), ids(first));
        assertEquals(expected.subList(3, 6), ids(second));
        assertEquals(expected.subList(6, 7), ids(third));
        assertEquals(ids(first), ids(service.listReviewingBatches(owner.userId(), PageRequest.of(0, 3))));
    }

    @Test void databasePagesBatchesAndUsesOneScopedGroupQueryForTheCurrentPageOnly() {
        Owner owner = owner();
        for (UUID batch : reviewing(owner, 17, CREATED)) fourCandidateFixture(owner, batch);
        sql.clear();
        var single = service.listReviewingBatches(owner.userId(), PageRequest.of(0, 1));
        List<String> singleStatements = sql.selects();
        assertEquals(1, single.getNumberOfElements());
        assertEquals(3, singleStatements.size(), "Full page: batch page, scoped total, grouped candidate counts");
        sql.clear();
        var page = service.listReviewingBatches(owner.userId(), PageRequest.of(0, 15));
        List<String> statements = sql.selects();
        assertEquals(15, page.getNumberOfElements());
        assertEquals(singleStatements.size(), statements.size(), "Query count must not grow with the page");
        assertEquals(3, sql.all().size(), "Read-only query must not emit writes or candidate entity loads");
        String batchPage = statements.stream().filter(statement -> statement.contains("import_batch")
                && !statement.contains("count(")).findFirst().orElseThrow();
        assertTrue(batchPage.contains("user_id"), batchPage);
        assertTrue(batchPage.contains("status"), batchPage);
        assertTrue(batchPage.contains("created_at desc"), batchPage);
        assertTrue(batchPage.contains("id desc"), batchPage);
        assertTrue(batchPage.contains("fetch first ? rows only"), batchPage);
        assertFalse(batchPage.contains(" join "), batchPage);
        assertFalse(batchPage.contains("import_candidate"), batchPage);
        String batchCount = statements.stream().filter(statement -> statement.contains("import_batch")
                && statement.contains("count(")).findFirst().orElseThrow();
        assertTrue(batchCount.contains("user_id"), batchCount);
        assertTrue(batchCount.contains("status"), batchCount);
        List<String> groups = statements.stream().filter(statement -> statement.contains("import_candidate")).toList();
        assertEquals(1, groups.size());
        String grouped = groups.getFirst();
        assertTrue(grouped.contains(schema + ".import_candidate"), "Native query must honor the fixture schema: " + grouped);
        assertTrue(grouped.contains("group by"), grouped);
        assertTrue(grouped.contains("batch_id"), grouped);
        assertTrue(grouped.contains("jsonb_array_length"), grouped);
        assertTrue(grouped.contains("filter"), grouped);
        assertTrue(grouped.contains("user_id"), grouped);
        assertTrue(grouped.contains(" in ("), grouped);
        assertEquals(16, grouped.chars().filter(character -> character == '?').count(),
                "Only one owner and the current page's 15 batch IDs should be bound");
        assertFalse(grouped.contains("selected_for_import"), grouped);
        assertFalse(grouped.contains("description"), grouped);
        assertFalse(grouped.contains("entry_type"), grouped);

        // Hibernate may omit a zero offset; inspect a later page to verify database offset pagination.
        sql.clear();
        var laterPage = service.listReviewingBatches(owner.userId(), PageRequest.of(1, 15));
        assertEquals(2, laterPage.getNumberOfElements());
        String offsetPage = sql.selects().stream().filter(statement -> statement.contains("import_batch")
                && !statement.contains("count(")).findFirst().orElseThrow();
        assertTrue(offsetPage.contains("offset ? rows"), offsetPage);
        assertTrue(offsetPage.contains("fetch first ? rows only"), offsetPage);
    }

    @Test void queryingLeavesEveryStoredBatchAndCandidateFieldAndBatchVersionUnchanged() {
        Owner owner = owner();
        List<UUID> batches = reviewing(owner, 3, CREATED);
        for (UUID batch : batches) fourCandidateFixture(owner, batch);
        jdbc.update("UPDATE " + table("import_candidate")
                        + " SET duplicate_warning = TRUE, duplicate_acknowledged = TRUE, duplicate_match_signature = ?"
                        + " WHERE batch_id = ? AND row_number = 1", "d".repeat(64), batches.getFirst());
        var batchesBefore = snapshot("import_batch");
        var candidatesBefore = snapshot("import_candidate");
        assertTrue(candidatesBefore.stream().noneMatch(row -> row.containsKey("version")),
                "Existing V2 candidates have no version column");
        service.listReviewingBatches(owner.userId(), PageRequest.of(0, 15));
        service.listReviewingBatches(owner.userId(), PageRequest.of(1, 1));
        assertEquals(batchesBefore, snapshot("import_batch"));
        assertEquals(candidatesBefore, snapshot("import_candidate"));
    }

    @Test void rejectsInvalidPagingAndSortingWithoutIssuingDatabaseQueries() {
        UUID userId = UUID.randomUUID();
        sql.clear();
        assertThrows(InvalidImportBatchQueryException.class,
                () -> service.listReviewingBatches(null, PageRequest.of(0, 15)));
        assertThrows(InvalidImportBatchQueryException.class, () -> service.listReviewingBatches(userId, null));
        for (Pageable invalid : List.of(Pageable.unpaged(), PageRequest.of(0, 101),
                PageRequest.of(Integer.MAX_VALUE, 100), PageRequest.of(0, 15, Sort.by("createdAt")),
                PageRequest.of(0, 15, Sort.by(Sort.Order.desc("createdAt"))))) {
            assertThrows(InvalidImportBatchQueryException.class, () -> service.listReviewingBatches(userId, invalid));
        }
        assertTrue(sql.all().isEmpty());
    }

    private record Owner(UUID userId, UUID accountId) {}

    private static Owner owner() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("app_user") + " (id,auth_subject,display_name) VALUES (?,?,?)",
                userId, "batch-query-" + userId, "Synthetic owner");
        jdbc.update("INSERT INTO " + table("financial_account")
                        + " (id,user_id,name,account_type) VALUES (?,?,?,'CREDIT_CARD')",
                accountId, userId, "Synthetic account");
        return new Owner(userId, accountId);
    }

    private static UUID batch(Owner owner, ImportStatus status, Instant created, UUID id) {
        boolean closed = status == ImportStatus.CONFIRMED || status == ImportStatus.CANCELLED || status == ImportStatus.FAILED;
        jdbc.update("INSERT INTO " + table("import_batch")
                        + " (id,user_id,account_id,upload_key,file_name,file_sha256,status,version,created_at,confirmed_at,closed_at)"
                        + " VALUES (?,?,?,?,'synthetic-batch.csv',?,?,7,?,?,?)",
                id, owner.userId(), owner.accountId(), UUID.randomUUID(), "a".repeat(64), status.name(),
                Timestamp.from(created), status == ImportStatus.CONFIRMED ? Timestamp.from(created) : null,
                closed ? Timestamp.from(created) : null);
        return id;
    }

    private static List<UUID> reviewing(Owner owner, int count, Instant start) {
        var batches = new ArrayList<UUID>();
        for (int index = 0; index < count; index++) {
            batches.add(batch(owner, ImportStatus.REVIEWING, start.plusSeconds(index), UUID.randomUUID()));
        }
        return batches;
    }

    private static void fourCandidateFixture(Owner owner, UUID batch) {
        LocalDate date = LocalDate.of(2026, 11, 1);
        candidate(owner, batch, 1, "EXPENSE", new BigDecimal("12.34"), date, "Synthetic normal", "[]");
        candidate(owner, batch, 2, "EXPENSE", null, null, "Synthetic invalid fields",
                "[{\"code\":\"INVALID_DATE\"},{\"code\":\"INVALID_AMOUNT\"}]");
        candidate(owner, batch, 3, null, new BigDecimal("5.00"), date, "Synthetic credit",
                "[{\"code\":\"REVIEW_REQUIRED\"}]");
        candidate(owner, batch, 4, "EXPENSE", new BigDecimal("7.00"), date, null,
                "[{\"code\":\"INVALID_DESCRIPTION\"}]");
    }

    private static void candidate(Owner owner, UUID batch, int row, String entryType,
                                  BigDecimal amount, LocalDate date, String description, String errors) {
        jdbc.update("INSERT INTO " + table("import_candidate")
                        + " (id,batch_id,user_id,row_number,entry_type,amount,currency_code,transaction_date,description,validation_errors)"
                        + " VALUES (?,?,?,?,?,?,'CAD',?,?,CAST(? AS jsonb))",
                UUID.randomUUID(), batch, owner.userId(), row, entryType, amount, date, description, errors);
    }

    private static String table(String name) { return schema + "." + name; }

    private static List<Map<String, Object>> snapshot(String name) {
        return jdbc.queryForList("SELECT * FROM " + table(name) + " ORDER BY id");
    }

    private static List<UUID> ids(Page<ImportBatchSummary> page) {
        return page.stream().map(ImportBatchSummary::id).toList();
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
        List<String> selects() { return all().stream().filter(statement -> statement.startsWith("select ")).toList(); }
    }
}
