package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.EntryType;
import com.finguard.transactionapi.finance.domain.LedgerEntry;
import com.finguard.transactionapi.finance.dto.CreateFinanceEntryRequest;
import com.finguard.transactionapi.finance.dto.FinanceEntryResponse;
import com.finguard.transactionapi.finance.dto.UpdateFinanceEntryRequest;
import com.finguard.transactionapi.finance.exception.FinanceEntryConflictException;
import com.finguard.transactionapi.finance.exception.FinanceEntryNotFoundException;
import com.finguard.transactionapi.finance.exception.InvalidFinanceEntryException;
import com.finguard.transactionapi.finance.repository.LedgerEntryRepository;
import com.finguard.transactionapi.finance.service.LedgerEntryService;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.aopalliance.intercept.MethodInterceptor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.SAME_THREAD)
class LedgerEntryUpdateIntegrationTest {
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static LedgerEntryService service;
    private static ReadGate readGate;
    private static String schema;
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID EXPENSE = UUID.randomUUID();
    private static final UUID INCOME = UUID.randomUUID();
    private static final UUID FOREIGN_CATEGORY = UUID.randomUUID();
    private static final UUID INACTIVE_CATEGORY = UUID.randomUUID();
    private static final LocalDate ORIGINAL_DATE = LocalDate.of(2026, 10, 1);
    private static final Instant ORIGINAL_TIME = Instant.parse("2026-10-02T01:02:03Z");

    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = LedgerEntryRepository.class)
    @Import(LedgerEntryService.class)
    static class Config {
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.finguard.transactionapi.finance.domain");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                    "hibernate.default_schema", schema));
            return factory;
        }

        @Bean JpaTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean ReadGate readGate() { return new ReadGate(); }

        @Bean @Primary
        LedgerEntryRepository gatedLedgerEntryRepository(
                @Qualifier("ledgerEntryRepository") LedgerEntryRepository delegate,
                ReadGate gate, EntityManagerFactory factory) {
            // Synchronize reads only; all queries, flushes and transactions use real PostgreSQL/JPA.
            var proxy = new ProxyFactory(delegate);
            proxy.addAdvice((MethodInterceptor) invocation -> {
                Object result = invocation.proceed();
                if (invocation.getMethod().getName().equals("findByIdAndUserIdAndDeletedAtIsNull")
                        && result instanceof Optional<?> optional
                        && optional.orElse(null) instanceof LedgerEntry entry) {
                    gate.afterRead(entry, factory);
                }
                return result;
            });
            return (LedgerEntryRepository) proxy.getProxy();
        }
    }

    @BeforeAll static void setup() {
        var ds = new DriverManagerDataSource(
                System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/finguard"),
                System.getenv().getOrDefault("DB_USERNAME", "finguard"), System.getenv("DB_PASSWORD"));
        schema = "ledger_update_test_" + UUID.randomUUID().toString().replace("-", "");
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> ds);
        context.register(Config.class);
        context.refresh();
        service = context.getBean(LedgerEntryService.class);
        readGate = context.getBean(ReadGate.class);
        jdbc.update("INSERT INTO " + table("app_user") + " (id,auth_subject,display_name) "
                + "VALUES (?,'update-owner','Owner'),(?,'update-other','Other')", OWNER, OTHER);
        jdbc.update("INSERT INTO " + table("financial_account")
                + " (id,user_id,name,account_type) VALUES (?,?,'active','CASH')", ACCOUNT, OWNER);
        jdbc.update("INSERT INTO " + table("category") + " (id,user_id,name,entry_type,active) VALUES "
                        + "(?,?,'expense','EXPENSE',true),(?,?,'income','INCOME',true),"
                        + "(?,?,'foreign','EXPENSE',true),(?,?,'inactive','EXPENSE',false)",
                EXPENSE, OWNER, INCOME, OWNER, FOREIGN_CATEGORY, OTHER, INACTIVE_CATEGORY, OWNER);
    }

    @AfterAll static void cleanup() {
        if (context != null) context.close();
        // This randomly named schema belongs solely to this test; existing schemas/volumes are untouched.
        if (jdbc != null && schema != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test void replacesEveryEditableFieldAndPreservesIdentityAndSourceMetadata() {
        var created = create(ACCOUNT, EXPENSE, EntryType.EXPENSE);
        UUID batch = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("import_batch")
                        + " (id,user_id,account_id,upload_key,file_name,file_sha256,status,confirmed_at,closed_at) "
                        + "VALUES (?,?,?,?,'fixture.csv',?,'CONFIRMED',now(),now())",
                batch, OWNER, ACCOUNT, UUID.randomUUID(), "a".repeat(64));
        jdbc.update("UPDATE " + table("ledger_entry") + " SET source_batch_id=?,source_row_number=7,"
                        + "client_request_key=?,bank_transaction_id='bank-fixture' WHERE id=?",
                batch, UUID.randomUUID(), created.id());
        Map<String, Object> immutableBefore = immutableFields(created.id());
        var request = new UpdateFinanceEntryRequest(INCOME, EntryType.INCOME, new BigDecimal("987.65"),
                "CAD", LocalDate.of(2026, 9, 29), Instant.parse("2026-09-30T02:03:04.123456Z"),
                "Reclassified payment", created.version());

        var updated = service.updateEntry(OWNER, created.id(), request);

        assertEquals(0L, created.version());
        assertEquals(1L, updated.version());
        assertEquals(updated, service.findById(OWNER, created.id()));
        assertEditableFields(request, updated);
        assertEquals(immutableBefore, immutableFields(created.id()));
        assertTrue(updated.updatedAt().isAfter(created.updatedAt()));
        assertEquals(created.createdAt(), updated.createdAt());
    }

    @Test void replacesIncomeWithExpenseAndClearsNullableFields() {
        var created = create(ACCOUNT, INCOME, EntryType.INCOME);
        var request = new UpdateFinanceEntryRequest(null, EntryType.EXPENSE, new BigDecimal("23.45"),
                "CAD", LocalDate.of(2026, 10, 3), null, "", created.version());

        var updated = service.updateEntry(OWNER, created.id(), request);

        assertEditableFields(request, service.findById(OWNER, created.id()));
        assertEquals(1L, updated.version());
        assertNull(updated.categoryId());
        assertNull(updated.occurredAt());
    }

    @Test void identicalReplacementStillAdvancesVersionAndUpdatedAt() {
        var created = create(ACCOUNT, EXPENSE, EntryType.EXPENSE);
        var request = new UpdateFinanceEntryRequest(created.categoryId(), created.entryType(), created.amount(),
                created.currencyCode(), created.transactionDate(), created.occurredAt(), created.description(),
                created.version());

        var updated = service.updateEntry(OWNER, created.id(), request);

        assertEquals(1L, updated.version());
        assertTrue(updated.updatedAt().isAfter(created.updatedAt()));
        assertEquals(updated, service.findById(OWNER, created.id()));
        assertThrows(FinanceEntryConflictException.class, () -> service.updateEntry(OWNER, created.id(), request));
    }

    @Test void joinsOuterTransactionAndRollsBackAllFlushedChanges() {
        var created = create(ACCOUNT, EXPENSE, EntryType.EXPENSE);
        assertEquals(0L, service.findById(OWNER, created.id()).version());
        Map<String, Object> before = row(created.id());
        var transaction = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        transaction.executeWithoutResult(status -> {
            var flushed = service.updateEntry(OWNER, created.id(),
                    replacement(INCOME, EntryType.INCOME, "123.45", "CAD", 0));
            assertEquals(1L, flushed.version());
            assertEquals(flushed, service.findById(OWNER, created.id()));
            status.setRollbackOnly();
        });

        assertEquals(before, row(created.id()));
        assertEquals(created, service.findById(OWNER, created.id()));
    }

    @Test void rejectsInvalidAmountsWithoutAnyPersistedChanges() {
        var created = create(ACCOUNT, EXPENSE, EntryType.EXPENSE);
        for (String amount : List.of("0", "-1", "1.001", "100000000000000000")) {
            assertRejectedUnchanged(created.id(), replacement(EXPENSE, EntryType.EXPENSE, amount, "CAD", 0));
        }
    }

    @Test void rejectsInvalidOrMismatchedCurrencyWithoutAnyPersistedChanges() {
        var created = create(ACCOUNT, EXPENSE, EntryType.EXPENSE);
        for (String currency : Arrays.asList(null, "cad", "CA", "ZZZ", "USD")) {
            assertRejectedUnchanged(created.id(), replacement(EXPENSE, EntryType.EXPENSE, "2.00", currency, 0));
        }
    }

    @Test void rejectsForeignWrongTypeInactiveAndMissingCategoriesWithoutAnyPersistedChanges() {
        var created = create(ACCOUNT, EXPENSE, EntryType.EXPENSE);
        for (UUID category : List.of(FOREIGN_CATEGORY, INCOME, INACTIVE_CATEGORY, UUID.randomUUID())) {
            assertRejectedUnchanged(created.id(), replacement(category, EntryType.EXPENSE, "2.00", "CAD", 0));
        }
    }

    @Test void revalidatesOriginalAccountActivity() {
        UUID inactive = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("financial_account")
                + " (id,user_id,name,account_type) VALUES (?,?,'deactivated-after-create','CASH')", inactive, OWNER);
        var created = create(inactive, EXPENSE, EntryType.EXPENSE);
        jdbc.update("UPDATE " + table("financial_account") + " SET active=false WHERE id=?", inactive);

        assertRejectedUnchanged(created.id(), replacement(EXPENSE, EntryType.EXPENSE, "2.00", "CAD", 0));
    }

    @Test void rejectsMissingRequiredFieldsAndInvalidExpectedVersionsWithoutAnyPersistedChanges() {
        var created = create(ACCOUNT, EXPENSE, EntryType.EXPENSE);
        for (var request : Arrays.asList(
                null,
                new UpdateFinanceEntryRequest(EXPENSE, null, BigDecimal.ONE, "CAD", ORIGINAL_DATE, null, "valid", 0L),
                new UpdateFinanceEntryRequest(EXPENSE, EntryType.EXPENSE, null, "CAD", ORIGINAL_DATE, null, "valid", 0L),
                new UpdateFinanceEntryRequest(EXPENSE, EntryType.EXPENSE, BigDecimal.ONE, "CAD", null, null, "valid", 0L),
                new UpdateFinanceEntryRequest(EXPENSE, EntryType.EXPENSE, BigDecimal.ONE, "CAD", ORIGINAL_DATE, null, null, 0L),
                new UpdateFinanceEntryRequest(EXPENSE, EntryType.EXPENSE, BigDecimal.ONE, "CAD", ORIGINAL_DATE, null, "x".repeat(501), 0L),
                new UpdateFinanceEntryRequest(EXPENSE, EntryType.EXPENSE, BigDecimal.ONE, "CAD", ORIGINAL_DATE, null, "valid", null),
                new UpdateFinanceEntryRequest(EXPENSE, EntryType.EXPENSE, BigDecimal.ONE, "CAD", ORIGINAL_DATE, null, "valid", -1L))) {
            assertRejectedUnchanged(created.id(), request);
        }
    }

    @Test void hidesOtherUsersMissingAndSoftDeletedEntriesWithoutChanges() {
        var created = create(ACCOUNT, EXPENSE, EntryType.EXPENSE);
        var request = replacement(EXPENSE, EntryType.EXPENSE, "2.00", "CAD", 0);
        Map<String, Object> before = row(created.id());
        var unauthorized = assertThrows(FinanceEntryNotFoundException.class,
                () -> service.updateEntry(OTHER, created.id(), request));
        var missing = assertThrows(FinanceEntryNotFoundException.class,
                () -> service.updateEntry(OWNER, UUID.randomUUID(), request));
        assertEquals(missing.getMessage(), unauthorized.getMessage());
        assertEquals(before, row(created.id()));
        jdbc.update("UPDATE " + table("ledger_entry") + " SET deleted_at=now() WHERE id=?", created.id());
        Map<String, Object> deleted = row(created.id());
        var softDeleted = assertThrows(FinanceEntryNotFoundException.class,
                () -> service.updateEntry(OWNER, created.id(), request));
        assertEquals(missing.getMessage(), softDeleted.getMessage());
        assertEquals(deleted, row(created.id()));
    }

    @Test void rejectsStaleExpectedVersionFromIndependentServiceReadsAndPreservesWinner() {
        var created = create(ACCOUNT, EXPENSE, EntryType.EXPENSE);
        // Each proxied service call starts and ends its own transaction/persistence context.
        var firstSnapshot = service.findById(OWNER, created.id());
        var secondSnapshot = service.findById(OWNER, created.id());
        var winnerRequest = replacement(INCOME, EntryType.INCOME, "100.00", "CAD", firstSnapshot.version());
        var winner = service.updateEntry(OWNER, created.id(), winnerRequest);
        Map<String, Object> committed = row(created.id());

        assertThrows(FinanceEntryConflictException.class, () -> service.updateEntry(OWNER, created.id(),
                replacement(EXPENSE, EntryType.EXPENSE, "99.99", "CAD", secondSnapshot.version())));

        assertEquals(committed, row(created.id()));
        assertEquals(winner, service.findById(OWNER, created.id()));
        assertEquals(1L, winner.version());
    }

    @Test void simultaneousWritesFromTwoPostgresSessionsCommitOneAndRollbackTheActualLockConflict() throws Exception {
        var created = create(ACCOUNT, EXPENSE, EntryType.EXPENSE);
        Map<String, Object> immutableBefore = immutableFields(created.id());
        var first = new UpdateFinanceEntryRequest(INCOME, EntryType.INCOME, new BigDecimal("111.11"), "CAD",
                LocalDate.of(2026, 10, 4), Instant.parse("2026-10-04T12:00:00Z"), "Writer one", 0L);
        var second = new UpdateFinanceEntryRequest(null, EntryType.EXPENSE, new BigDecimal("222.22"), "CAD",
                LocalDate.of(2026, 10, 5), null, "Writer two", 0L);
        var executor = Executors.newFixedThreadPool(2);
        var race = readGate.arm(created.id());
        try {
            var firstFuture = executor.submit(() -> attemptUpdate(created.id(), first));
            var secondFuture = executor.submit(() -> attemptUpdate(created.id(), second));
            var outcomes = List.of(firstFuture.get(30, TimeUnit.SECONDS), secondFuture.get(30, TimeUnit.SECONDS));
            // Disable the gate before the final service read, which must not wait for a second reader.
            readGate.disarm();
            assertEquals(2, race.reads.size());
            assertTrue(race.reads.stream().allMatch(read -> read.version() == 0));
            assertEquals(2L, race.reads.stream().map(ObservedRead::backendPid).distinct().count());
            var winners = outcomes.stream().filter(outcome -> outcome.response() != null).toList();
            var losers = outcomes.stream().filter(outcome -> outcome.conflict() != null).toList();
            assertEquals(1, winners.size());
            assertEquals(1, losers.size());
            assertTrue(hasOptimisticCause(losers.getFirst().conflict()),
                    "The losing write must fail in PostgreSQL/Hibernate optimistic locking, not only expectedVersion validation");
            var persisted = service.findById(OWNER, created.id());
            assertEquals(winners.getFirst().response(), persisted);
            assertEditableFields(winners.getFirst().request(), persisted);
            assertEquals(1L, persisted.version());
            assertEquals(immutableBefore, immutableFields(created.id()));
            assertEquals(1L, jdbc.queryForObject("SELECT version FROM " + table("ledger_entry")
                    + " WHERE id=?", Long.class, created.id()));
        } finally {
            readGate.disarm();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Concurrent update threads did not finish");
        }
    }

    private static FinanceEntryResponse create(UUID account, UUID category, EntryType type) {
        return service.createEntry(OWNER, new CreateFinanceEntryRequest(account, category, type,
                new BigDecimal("12.34"), "CAD", ORIGINAL_DATE, ORIGINAL_TIME, "Original entry"));
    }

    private static UpdateFinanceEntryRequest replacement(UUID category, EntryType type, String amount,
            String currency, long version) {
        return new UpdateFinanceEntryRequest(category, type, new BigDecimal(amount), currency,
                LocalDate.of(2026, 10, 3), null, "Replacement", version);
    }

    private static void assertRejectedUnchanged(UUID id, UpdateFinanceEntryRequest request) {
        Map<String, Object> before = row(id);
        assertThrows(InvalidFinanceEntryException.class, () -> service.updateEntry(OWNER, id, request));
        assertEquals(before, row(id), "Validation failures must preserve every column, including version/timestamps");
    }

    private static void assertEditableFields(UpdateFinanceEntryRequest expected, FinanceEntryResponse actual) {
        assertAll(
                () -> assertEquals(expected.categoryId(), actual.categoryId()),
                () -> assertEquals(expected.entryType(), actual.entryType()),
                () -> assertEquals(expected.amount(), actual.amount()),
                () -> assertEquals(expected.currencyCode(), actual.currencyCode()),
                () -> assertEquals(expected.transactionDate(), actual.transactionDate()),
                () -> assertEquals(expected.occurredAt(), actual.occurredAt()),
                () -> assertEquals(expected.description(), actual.description()));
    }

    private static Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM " + table("ledger_entry") + " WHERE id=?", id);
    }

    private static Map<String, Object> immutableFields(UUID id) {
        return jdbc.queryForMap("SELECT id,user_id,account_id,source_batch_id,source_row_number,"
                + "client_request_key,bank_transaction_id,created_at,deleted_at FROM "
                + table("ledger_entry") + " WHERE id=?", id);
    }

    private static String table(String name) { return schema + "." + name; }

    private static UpdateOutcome attemptUpdate(UUID id, UpdateFinanceEntryRequest request) {
        try {
            return new UpdateOutcome(request, service.updateEntry(OWNER, id, request), null);
        } catch (FinanceEntryConflictException ex) {
            return new UpdateOutcome(request, null, ex);
        }
    }

    private static boolean hasOptimisticCause(Throwable failure) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockingFailureException || cause instanceof OptimisticLockException) return true;
        }
        return false;
    }

    private record UpdateOutcome(UpdateFinanceEntryRequest request, FinanceEntryResponse response,
            FinanceEntryConflictException conflict) {}
    private record ObservedRead(long version, int backendPid) {}

    private static class Race {
        final UUID entryId;
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final ConcurrentLinkedQueue<ObservedRead> reads = new ConcurrentLinkedQueue<>();
        Race(UUID entryId) { this.entryId = entryId; }
    }

    static class ReadGate {
        private volatile Race active;
        Race arm(UUID id) { active = new Race(id); return active; }
        void disarm() { active = null; }
        void afterRead(LedgerEntry entry, EntityManagerFactory factory) throws Exception {
            Race race = active;
            if (race == null || !race.entryId.equals(entry.getId())) return;
            var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(factory);
            assertNotNull(entityManager, "Read must happen inside the service transaction");
            int backendPid = ((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue();
            race.reads.add(new ObservedRead(entry.getVersion(), backendPid));
            race.barrier.await(15, TimeUnit.SECONDS);
        }
    }
}
