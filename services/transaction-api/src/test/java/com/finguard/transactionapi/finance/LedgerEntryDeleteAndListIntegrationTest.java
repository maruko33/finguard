package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.EntryType;
import com.finguard.transactionapi.finance.domain.LedgerEntry;
import com.finguard.transactionapi.finance.dto.CreateFinanceEntryRequest;
import com.finguard.transactionapi.finance.dto.FinanceEntryResponse;
import com.finguard.transactionapi.finance.dto.ListFinanceEntriesFilter;
import com.finguard.transactionapi.finance.dto.UpdateFinanceEntryRequest;
import com.finguard.transactionapi.finance.exception.FinanceEntryConflictException;
import com.finguard.transactionapi.finance.exception.FinanceEntryNotFoundException;
import com.finguard.transactionapi.finance.exception.FinancialAccountNotFoundException;
import com.finguard.transactionapi.finance.exception.InvalidFinanceEntryException;
import com.finguard.transactionapi.finance.repository.LedgerEntryRepository;
import com.finguard.transactionapi.finance.service.LedgerEntryService;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.aopalliance.intercept.MethodInterceptor;
import org.flywaydb.core.Flyway;
import org.hibernate.resource.jdbc.spi.StatementInspector;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.SAME_THREAD)
class LedgerEntryDeleteAndListIntegrationTest {
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static LedgerEntryService service;
    private static ReadGate readGate;
    private static SqlCapture sqlCapture;
    private static String schema;
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID EXPENSE = UUID.randomUUID();
    private static final UUID INCOME = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 10, 10);
    private static final Sort ORDER = Sort.by(Sort.Order.desc("transactionDate"), Sort.Order.desc("id"));

    // Explicitly registered only here; not component-scanned by application tests.
    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = LedgerEntryRepository.class)
    @Import(LedgerEntryService.class)
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

        @Bean ReadGate readGate() { return new ReadGate(); }

        @Bean @Primary LedgerEntryRepository gatedLedgerEntryRepository(
                @Qualifier("ledgerEntryRepository") LedgerEntryRepository delegate,
                ReadGate gate, EntityManagerFactory factory) {
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
        schema = "ledger_delete_list_test_" + UUID.randomUUID().toString().replace("-", "");
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> ds);
        context.register(Config.class);
        context.refresh();
        service = context.getBean(LedgerEntryService.class);
        readGate = context.getBean(ReadGate.class);
        sqlCapture = context.getBean(SqlCapture.class);
        jdbc.update("INSERT INTO " + table("app_user") + " (id,auth_subject,display_name) "
                + "VALUES (?,'delete-list-owner','Owner'),(?,'delete-list-other','Other')", OWNER, OTHER);
        jdbc.update("INSERT INTO " + table("category") + " (id,user_id,name,entry_type) VALUES "
                + "(?,?,'expense','EXPENSE'),(?,?,'income','INCOME')", EXPENSE, OWNER, INCOME, OWNER);
    }

    @AfterAll static void cleanup() {
        if (context != null) context.close();
        // Only this test's randomly named schema is removed; existing schemas/volumes are untouched.
        if (jdbc != null && schema != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test void softDeleteRetainsRowAndAllOriginalFinancialAndImportMetadataButHidesReadAndList() {
        UUID account = account(OWNER);
        var created = create(OWNER, account, DATE, EntryType.EXPENSE);
        attachConfirmedImport(created.id(), account);
        Map<String, Object> before = row(created.id());
        Instant earliestDeletion = Instant.now().truncatedTo(ChronoUnit.MICROS);

        service.deleteEntry(OWNER, created.id(), created.version());

        Map<String, Object> deleted = row(created.id());
        Instant deletedAt = instant(deleted, "deleted_at");
        assertNotNull(deletedAt);
        assertFalse(deletedAt.isBefore(earliestDeletion));
        assertFalse(deletedAt.isAfter(Instant.now()));
        assertTrue(instant(deleted, "updated_at").isAfter(instant(before, "updated_at")));
        assertEquals(1L, deleted.get("version"));
        assertEquals(originalFields(before), originalFields(deleted));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + table("ledger_entry")
                + " WHERE id=?", Integer.class, created.id()));
        assertThrows(FinanceEntryNotFoundException.class, () -> service.findById(OWNER, created.id()));
        var page = service.listEntries(OWNER, filter(account), PageRequest.of(0, 20));
        assertTrue(page.isEmpty());
        assertEquals(0, page.getTotalElements());
    }

    @Test void softDeleteKeepsUpdatedAtMonotonicEvenWhenStoredTimeIsAheadOfServerClock() {
        var created = create(OWNER, account(OWNER), DATE, EntryType.EXPENSE);
        Instant future = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("UPDATE " + table("ledger_entry") + " SET updated_at=? WHERE id=?", Timestamp.from(future), created.id());
        service.deleteEntry(OWNER, created.id(), 0L);
        Map<String, Object> deleted = row(created.id());
        assertFalse(instant(deleted, "updated_at").isBefore(future));
        assertTrue(instant(deleted, "deleted_at").isBefore(future));
        assertEquals(1L, deleted.get("version"));
    }

    @Test void deletesOwnedEntryAfterOriginalAccountAndCategoryBecomeInactive() {
        UUID account = account(OWNER);
        var created = create(OWNER, account, DATE, EntryType.EXPENSE);
        jdbc.update("UPDATE " + table("financial_account") + " SET active=false WHERE id=?", account);
        UUID category = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("category") + " (id,user_id,name,entry_type,active) "
                + "VALUES (?,?,?,'EXPENSE',false)", category, OWNER, category.toString());
        jdbc.update("UPDATE " + table("ledger_entry") + " SET category_id=? WHERE id=?", category, created.id());
        Map<String, Object> before = row(created.id());
        service.deleteEntry(OWNER, created.id(), 0L);
        assertNotNull(row(created.id()).get("deleted_at"));
        assertEquals(originalFields(before), originalFields(row(created.id())));
    }

    @Test void foreignMissingAndRepeatedDeletesShareNotFoundAndPreserveEveryColumn() {
        var created = create(OWNER, account(OWNER), DATE, EntryType.EXPENSE);
        Map<String, Object> before = row(created.id());
        var missing = assertThrows(FinanceEntryNotFoundException.class,
                () -> service.deleteEntry(OWNER, UUID.randomUUID(), 0L));
        var foreign = assertThrows(FinanceEntryNotFoundException.class,
                () -> service.deleteEntry(OTHER, created.id(), 0L));
        assertEquals(missing.getMessage(), foreign.getMessage());
        assertEquals(before, row(created.id()));
        var foreignInvalid = assertThrows(FinanceEntryNotFoundException.class,
                () -> service.deleteEntry(OTHER, created.id(), null));
        assertEquals(missing.getMessage(), foreignInvalid.getMessage());
        service.deleteEntry(OWNER, created.id(), 0L);
        Map<String, Object> deleted = row(created.id());
        var repeated = assertThrows(FinanceEntryNotFoundException.class,
                () -> service.deleteEntry(OWNER, created.id(), 1L));
        assertEquals(missing.getMessage(), repeated.getMessage());
        assertEquals(deleted, row(created.id()));
    }

    @Test void invalidAndStaleDeleteVersionsLeaveEntireCommittedRowUnchanged() {
        var created = create(OWNER, account(OWNER), DATE, EntryType.EXPENSE);
        Map<String, Object> initial = row(created.id());
        for (Long version : Arrays.asList(null, -1L)) {
            assertThrows(InvalidFinanceEntryException.class, () -> service.deleteEntry(OWNER, created.id(), version));
            assertEquals(initial, row(created.id()));
        }
        var winner = service.updateEntry(OWNER, created.id(), replacement());
        Map<String, Object> committed = row(created.id());
        assertThrows(FinanceEntryConflictException.class, () -> service.deleteEntry(OWNER, created.id(), 0L));
        assertEquals(committed, row(created.id()));
        assertEquals(winner, service.findById(OWNER, created.id()));
    }

    @Test void outerTransactionRollbackRestoresFlushedDeletionVersionAndTimestamps() {
        UUID account = account(OWNER);
        var created = create(OWNER, account, DATE, EntryType.EXPENSE);
        Map<String, Object> before = row(created.id());
        var transaction = new TransactionTemplate(context.getBean(JpaTransactionManager.class));
        transaction.executeWithoutResult(status -> {
            service.deleteEntry(OWNER, created.id(), 0L);
            assertNotNull(row(created.id()).get("deleted_at"));
            assertEquals(1L, row(created.id()).get("version"));
            assertTrue(service.listEntries(OWNER, filter(account), PageRequest.of(0, 20)).isEmpty());
            status.setRollbackOnly();
        });
        assertEquals(before, row(created.id()));
        assertEquals(created, service.findById(OWNER, created.id()));
    }

    @Test void actualUpdateVersusDeleteConflictRollsBackDeleteWhenUpdateCommitsFirst() throws Exception {
        assertUpdateDeleteRace(Operation.UPDATE);
    }

    @Test void actualUpdateVersusDeleteConflictRollsBackUpdateWhenDeleteCommitsFirst() throws Exception {
        assertUpdateDeleteRace(Operation.DELETE);
    }

    @Test void paginatesByTransactionDateThenPostgresUuidOrderAndIncludesManualAndConfirmedImportedRows() {
        UUID account = account(OWNER);
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID mid = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
        UUID highest = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        for (UUID id : List.of(low, mid, high, highest)) insertEntry(id, OWNER, account, DATE, EntryType.EXPENSE);
        attachConfirmedImport(high, account);
        UUID newer = insertEntry(UUID.randomUUID(), OWNER, account, DATE.plusDays(1), EntryType.INCOME);
        UUID older = insertEntry(UUID.randomUUID(), OWNER, account, DATE.minusDays(1), EntryType.EXPENSE);
        jdbc.update("UPDATE " + table("ledger_entry") + " SET version=7 WHERE id=?", high);
        create(OWNER, account(OWNER), DATE.plusDays(20), EntryType.INCOME);
        create(OTHER, account(OTHER), DATE.plusDays(20), EntryType.EXPENSE);
        Map<UUID, Map<String, Object>> before = snapshots(List.of(low, mid, high, highest, newer, older));
        var actual = new ArrayList<UUID>();
        for (int index = 0; index < 3; index++) {
            Pageable pageable = index == 1 ? PageRequest.of(index, 2, ORDER) : PageRequest.of(index, 2);
            var page = service.listEntries(OWNER, filter(account), pageable);
            assertEquals(6, page.getTotalElements());
            assertEquals(3, page.getTotalPages());
            assertEquals(index, page.getNumber());
            assertEquals(2, page.getNumberOfElements());
            actual.addAll(page.getContent().stream().map(FinanceEntryResponse::id).toList());
            page.getContent().stream().filter(entry -> entry.id().equals(high))
                    .forEach(entry -> assertEquals(7L, entry.version()));
        }
        // PostgreSQL UUID order compares unsigned bytes, unlike UUID.compareTo's signed longs.
        assertEquals(List.of(newer, highest, high, mid, low, older), actual);
        assertEquals(before, snapshots(before.keySet().stream().toList()));
    }

    @Test void filtersInclusiveTransactionDatesIndependentlyOfCreationTimeAndOptionalEntryType() {
        UUID account = account(OWNER);
        var before = create(OWNER, account, DATE.minusDays(1), EntryType.EXPENSE);
        var lower = create(OWNER, account, DATE, EntryType.EXPENSE);
        var income = create(OWNER, account, DATE.plusDays(1), EntryType.INCOME);
        var upper = create(OWNER, account, DATE.plusDays(2), EntryType.EXPENSE);
        var after = create(OWNER, account, DATE.plusDays(3), EntryType.EXPENSE);
        jdbc.update("UPDATE " + table("ledger_entry") + " SET created_at=? WHERE account_id=?",
                Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")), account);
        var both = new ListFinanceEntriesFilter(account, DATE, DATE.plusDays(2), null);
        assertEquals(List.of(upper.id(), income.id(), lower.id()), ids(service.listEntries(OWNER, both, PageRequest.of(0, 20))));
        var expense = new ListFinanceEntriesFilter(account, DATE, DATE.plusDays(2), EntryType.EXPENSE);
        assertEquals(List.of(upper.id(), lower.id()), ids(service.listEntries(OWNER, expense, PageRequest.of(0, 20))));
        var onlyIncome = new ListFinanceEntriesFilter(account, null, null, EntryType.INCOME);
        assertEquals(List.of(income.id()), ids(service.listEntries(OWNER, onlyIncome, PageRequest.of(0, 20))));
        var fromOnly = new ListFinanceEntriesFilter(account, DATE.plusDays(2), null, null);
        assertEquals(List.of(after.id(), upper.id()), ids(service.listEntries(OWNER, fromOnly, PageRequest.of(0, 20))));
        var toOnly = new ListFinanceEntriesFilter(account, null, DATE, null);
        assertEquals(List.of(lower.id(), before.id()), ids(service.listEntries(OWNER, toOnly, PageRequest.of(0, 20))));
        var sameDate = new ListFinanceEntriesFilter(account, DATE, DATE, null);
        assertEquals(List.of(lower.id()), ids(service.listEntries(OWNER, sameDate, PageRequest.of(0, 20))));
    }

    @Test void excludesDeletedEntriesFromEveryPageAndFromDatabaseTotalCount() {
        UUID account = account(OWNER);
        var visible = new ArrayList<UUID>();
        for (int day = 0; day < 5; day++) visible.add(create(OWNER, account, DATE.plusDays(day), EntryType.EXPENSE).id());
        UUID deleted = visible.remove(2);
        service.deleteEntry(OWNER, deleted, 0L);
        var first = service.listEntries(OWNER, filter(account), PageRequest.of(0, 2));
        var second = service.listEntries(OWNER, filter(account), PageRequest.of(1, 2));
        assertEquals(4, first.getTotalElements());
        assertEquals(4, second.getTotalElements());
        assertEquals(2, first.getTotalPages());
        assertEquals(List.of(visible.get(3), visible.get(2)), ids(first));
        assertEquals(List.of(visible.get(1), visible.get(0)), ids(second));
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM " + table("ledger_entry")
                + " WHERE account_id=?", Integer.class, account));
    }

    @Test void returnsEmptyPageForOwnedAccountWithoutMatchesAndForPageBeyondEnd() {
        UUID account = account(OWNER);
        var empty = service.listEntries(OWNER, filter(account), PageRequest.of(0, 20));
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.getTotalElements());
        create(OWNER, account, DATE, EntryType.EXPENSE);
        var noMatchingType = new ListFinanceEntriesFilter(account, null, null, EntryType.INCOME);
        assertTrue(service.listEntries(OWNER, noMatchingType, PageRequest.of(0, 20)).isEmpty());
        var beyond = service.listEntries(OWNER, filter(account), PageRequest.of(2, 1));
        assertTrue(beyond.isEmpty());
        assertEquals(1, beyond.getTotalElements());
    }

    @Test void missingAndForeignAccountsReturnSameNotFoundBeforeAnyLedgerQuery() {
        UUID foreign = account(OTHER);
        create(OTHER, foreign, DATE, EntryType.EXPENSE);
        sqlCapture.clear();
        var notOwned = assertThrows(FinancialAccountNotFoundException.class,
                () -> service.listEntries(OWNER, filter(foreign), PageRequest.of(0, 20)));
        var missing = assertThrows(FinancialAccountNotFoundException.class,
                () -> service.listEntries(OWNER, filter(UUID.randomUUID()), PageRequest.of(0, 20)));
        assertEquals(missing.getMessage(), notOwned.getMessage());
        assertTrue(sqlCapture.selects().stream().noneMatch(sql -> sql.contains("ledger_entry")));
    }

    @Test void listsHistoricalEntriesOfInactiveOwnedAccountWithoutMutatingRows() {
        UUID account = account(OWNER);
        var created = create(OWNER, account, DATE, EntryType.INCOME);
        jdbc.update("UPDATE " + table("financial_account") + " SET active=false WHERE id=?", account);
        Map<String, Object> before = row(created.id());
        var page = service.listEntries(OWNER, filter(account), PageRequest.of(0, 20));
        assertEquals(List.of(created), page.getContent());
        assertEquals(0L, page.getContent().getFirst().version());
        assertEquals(before, row(created.id()));
    }

    @Test void rejectsInvalidListArgumentsDateRangesPaginationAndUnsupportedSortWithoutMutation() {
        UUID account = account(OWNER);
        var created = create(OWNER, account, DATE, EntryType.EXPENSE);
        Map<String, Object> before = row(created.id());
        var valid = filter(account);
        assertThrows(InvalidFinanceEntryException.class, () -> service.listEntries(null, valid, PageRequest.of(0, 20)));
        assertThrows(InvalidFinanceEntryException.class, () -> service.listEntries(OWNER, null, PageRequest.of(0, 20)));
        assertThrows(InvalidFinanceEntryException.class, () -> service.listEntries(OWNER, filter(null), PageRequest.of(0, 20)));
        assertThrows(InvalidFinanceEntryException.class, () -> service.listEntries(OWNER,
                new ListFinanceEntriesFilter(account, DATE.plusDays(1), DATE, null), PageRequest.of(0, 20)));
        for (Pageable invalid : Arrays.asList(null, Pageable.unpaged(), PageRequest.of(0, 101),
                PageRequest.of(Integer.MAX_VALUE, 100), new UntrustedPageable(-1, 20),
                new UntrustedPageable(0, 0), new UntrustedPageable(0, -1),
                PageRequest.of(0, 20, Sort.by("description")),
                PageRequest.of(0, 20, Sort.by(Sort.Order.asc("transactionDate"), Sort.Order.desc("id"))),
                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("transactionDate"))),
                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("id"), Sort.Order.desc("transactionDate"))))) {
            assertThrows(InvalidFinanceEntryException.class, () -> service.listEntries(OWNER, valid, invalid));
        }
        assertEquals(before, row(created.id()));
    }

    @Test void sendsAllPredicatesOrderingOffsetAndLimitToPostgresWithOneContentQueryOneCountAndNoNPlusOne() {
        UUID account = account(OWNER);
        for (int day = 0; day < 6; day++) create(OWNER, account, DATE.plusDays(day), EntryType.EXPENSE);
        create(OWNER, account, DATE.plusDays(2), EntryType.INCOME);
        var deleted = create(OWNER, account, DATE.plusDays(3), EntryType.EXPENSE);
        service.deleteEntry(OWNER, deleted.id(), 0L);
        var filter = new ListFinanceEntriesFilter(account, DATE, DATE.plusDays(5), EntryType.EXPENSE);
        sqlCapture.clear();
        var page = service.listEntries(OWNER, filter, PageRequest.of(1, 2));
        assertEquals(2, page.getNumberOfElements());
        assertEquals(6, page.getTotalElements());
        var selects = sqlCapture.selects();
        assertEquals(3, selects.size(), () -> "Expected ownership, content and count only; got: " + selects);
        var ledger = selects.stream().filter(sql -> sql.contains("ledger_entry")).toList();
        assertEquals(2, ledger.size());
        String count = ledger.stream().filter(sql -> sql.contains("count(")).findFirst().orElseThrow();
        String content = ledger.stream().filter(sql -> !sql.contains("count(")).findFirst().orElseThrow();
        for (String query : List.of(content, count)) {
            String predicate = query.substring(query.indexOf(" where "));
            assertTrue(predicate.matches("(?s).*\\buser_id\\s*=\\s*\\?.*"), query);
            assertTrue(predicate.matches("(?s).*\\baccount_id\\s*=\\s*\\?.*"), query);
            assertTrue(predicate.matches("(?s).*\\bdeleted_at\\s+is\\s+null.*"), query);
            assertTrue(predicate.matches("(?s).*\\btransaction_date\\s*>=\\s*\\?.*"), query);
            assertTrue(predicate.matches("(?s).*\\btransaction_date\\s*<=\\s*\\?.*"), query);
            assertTrue(predicate.matches("(?s).*\\bentry_type\\s*=\\s*\\?.*"), query);
            assertFalse(predicate.contains("created_at"), query);
        }
        assertTrue(content.matches("(?s).*order by\\s+\\w+\\.transaction_date desc,\\s*\\w+\\.id desc.*"), content);
        assertTrue(content.contains("offset ?"), content);
        assertTrue(content.contains("fetch first ?") || content.contains("fetch next ?") || content.contains("limit ?"), content);
        assertFalse(count.contains("offset") || count.contains("fetch first") || count.contains("limit"), count);
    }

    private static void assertUpdateDeleteRace(Operation winner) throws Exception {
        UUID account = account(OWNER);
        var created = create(OWNER, account, DATE, EntryType.EXPENSE);
        attachConfirmedImport(created.id(), account);
        Map<String, Object> before = row(created.id());
        var executor = Executors.newFixedThreadPool(2);
        Race race = readGate.arm(created.id(), winner);
        try {
            var updateFuture = executor.submit(() -> attemptRace(created.id(), Operation.UPDATE, race));
            var deleteFuture = executor.submit(() -> attemptRace(created.id(), Operation.DELETE, race));
            List<Outcome> outcomes = List.of(updateFuture.get(30, TimeUnit.SECONDS), deleteFuture.get(30, TimeUnit.SECONDS));
            readGate.disarm();
            assertEquals(2, race.reads.size());
            assertTrue(race.reads.stream().allMatch(read -> read.version() == 0L));
            assertEquals(2L, race.reads.stream().map(ObservedRead::backendPid).distinct().count());
            var successes = outcomes.stream().filter(outcome -> outcome.conflict() == null).toList();
            var failures = outcomes.stream().filter(outcome -> outcome.conflict() != null).toList();
            assertEquals(1, successes.size());
            assertEquals(winner, successes.getFirst().operation());
            assertEquals(1, failures.size());
            assertTrue(hasOptimisticCause(failures.getFirst().conflict()),
                    "Losing write must encounter genuine PostgreSQL/JPA optimistic locking after reading version 0");
            Map<String, Object> committed = row(created.id());
            assertEquals(1L, committed.get("version"));
            assertEquals(sourceAndIdentity(before), sourceAndIdentity(committed));
            assertTrue(instant(committed, "updated_at").isAfter(instant(before, "updated_at")));
            if (winner == Operation.DELETE) {
                assertNotNull(committed.get("deleted_at"));
                assertEquals(originalFields(before), originalFields(committed));
                assertThrows(FinanceEntryNotFoundException.class, () -> service.findById(OWNER, created.id()));
                assertTrue(service.listEntries(OWNER, filter(account), PageRequest.of(0, 20)).isEmpty());
            } else {
                assertNull(committed.get("deleted_at"));
                var persisted = service.findById(OWNER, created.id());
                assertEquals(successes.getFirst().response(), persisted);
                assertEquals(INCOME, persisted.categoryId());
                assertEquals(EntryType.INCOME, persisted.entryType());
                assertEquals(new BigDecimal("987.65"), persisted.amount());
                assertEquals("CAD", persisted.currencyCode());
                assertEquals(DATE.plusDays(3), persisted.transactionDate());
                assertEquals(Instant.parse("2026-10-13T15:16:17.123456Z"), persisted.occurredAt());
                assertEquals("Committed update", persisted.description());
                assertEquals(List.of(persisted), service.listEntries(OWNER, filter(account), PageRequest.of(0, 20)).getContent());
            }
        } finally {
            readGate.disarm();
            race.winnerCommitted.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Concurrent write threads did not finish");
        }
    }

    private static Outcome attemptRace(UUID id, Operation operation, Race race) {
        readGate.operation.set(operation);
        try {
            if (operation == Operation.DELETE) {
                service.deleteEntry(OWNER, id, 0L);
                return new Outcome(operation, null, null);
            }
            return new Outcome(operation, service.updateEntry(OWNER, id, replacement()), null);
        } catch (FinanceEntryConflictException conflict) {
            return new Outcome(operation, null, conflict);
        } finally {
            // The service proxy committed/rolled back its real transaction before this release.
            if (operation == race.winner) race.winnerCommitted.countDown();
            readGate.operation.remove();
        }
    }

    private static UUID account(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("financial_account")
                + " (id,user_id,name,account_type) VALUES (?,?,?,'CASH')", id, owner, id.toString());
        return id;
    }

    private static FinanceEntryResponse create(UUID owner, UUID account, LocalDate date, EntryType type) {
        UUID category = owner.equals(OWNER) ? (type == EntryType.EXPENSE ? EXPENSE : INCOME) : null;
        return service.createEntry(owner, new CreateFinanceEntryRequest(account, category, type,
                new BigDecimal("12.34"), "CAD", date, Instant.parse("2026-10-10T10:11:12Z"), "Original entry"));
    }

    private static UUID insertEntry(UUID id, UUID owner, UUID account, LocalDate date, EntryType type) {
        jdbc.update("INSERT INTO " + table("ledger_entry") + " (id,user_id,account_id,entry_type,amount,currency_code,"
                + "transaction_date,description) VALUES (?,?,?,?,12.34,'CAD',?,'Ordered fixture')",
                id, owner, account, type.name(), date);
        return id;
    }

    private static void attachConfirmedImport(UUID entry, UUID account) {
        UUID batch = UUID.randomUUID();
        jdbc.update("INSERT INTO " + table("import_batch")
                + " (id,user_id,account_id,upload_key,file_name,file_sha256,status,confirmed_at,closed_at) "
                + "VALUES (?,?,?,?,'fixture.csv',?,'CONFIRMED',now(),now())",
                batch, OWNER, account, UUID.randomUUID(), "b".repeat(64));
        jdbc.update("UPDATE " + table("ledger_entry")
                + " SET source_batch_id=?,source_row_number=3,client_request_key=?,bank_transaction_id=? WHERE id=?",
                batch, UUID.randomUUID(), "bank-" + UUID.randomUUID(), entry);
    }

    private static ListFinanceEntriesFilter filter(UUID account) {
        return new ListFinanceEntriesFilter(account, null, null, null);
    }

    private static UpdateFinanceEntryRequest replacement() {
        return new UpdateFinanceEntryRequest(INCOME, EntryType.INCOME, new BigDecimal("987.65"), "CAD",
                DATE.plusDays(3), Instant.parse("2026-10-13T15:16:17.123456Z"), "Committed update", 0L);
    }

    private static List<UUID> ids(org.springframework.data.domain.Page<FinanceEntryResponse> page) {
        return page.getContent().stream().map(FinanceEntryResponse::id).toList();
    }

    private static Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM " + table("ledger_entry") + " WHERE id=?", id);
    }

    private static Map<UUID, Map<String, Object>> snapshots(List<UUID> ids) {
        var snapshots = new HashMap<UUID, Map<String, Object>>();
        for (UUID id : ids) snapshots.put(id, row(id));
        return snapshots;
    }

    private static Map<String, Object> originalFields(Map<String, Object> row) {
        var original = new HashMap<>(row);
        for (String changed : List.of("version", "updated_at", "deleted_at")) original.remove(changed);
        return original;
    }

    private static Map<String, Object> sourceAndIdentity(Map<String, Object> row) {
        var result = new HashMap<String, Object>();
        for (String key : List.of("id", "user_id", "account_id", "created_at", "source_batch_id",
                "source_row_number", "client_request_key", "bank_transaction_id")) result.put(key, row.get(key));
        return result;
    }

    private static Instant instant(Map<String, Object> row, String column) {
        return ((Timestamp) row.get(column)).toInstant();
    }

    private static String table(String name) { return schema + "." + name; }

    private static boolean hasOptimisticCause(Throwable failure) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockingFailureException || cause instanceof OptimisticLockException) return true;
        }
        return false;
    }

    private enum Operation { UPDATE, DELETE }
    private record Outcome(Operation operation, FinanceEntryResponse response, FinanceEntryConflictException conflict) {}
    private record ObservedRead(long version, int backendPid) {}

    private static class Race {
        final UUID entryId;
        final Operation winner;
        final CyclicBarrier bothRead = new CyclicBarrier(2);
        final CountDownLatch winnerCommitted = new CountDownLatch(1);
        final ConcurrentLinkedQueue<ObservedRead> reads = new ConcurrentLinkedQueue<>();
        Race(UUID entryId, Operation winner) { this.entryId = entryId; this.winner = winner; }
    }

    static class ReadGate {
        private volatile Race active;
        final ThreadLocal<Operation> operation = new ThreadLocal<>();
        Race arm(UUID id, Operation winner) { active = new Race(id, winner); return active; }
        void disarm() { active = null; }
        void afterRead(LedgerEntry entry, EntityManagerFactory factory) throws Exception {
            Race race = active;
            if (race == null || !race.entryId.equals(entry.getId())) return;
            var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(factory);
            assertNotNull(entityManager, "Repository reads must occur inside the service transaction");
            int pid = ((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue();
            race.reads.add(new ObservedRead(entry.getVersion(), pid));
            race.bothRead.await(15, TimeUnit.SECONDS);
            if (operation.get() != race.winner) {
                assertTrue(race.winnerCommitted.await(15, TimeUnit.SECONDS), "Winning transaction did not finish");
            }
        }
    }

    static class SqlCapture implements StatementInspector {
        private final ConcurrentLinkedQueue<String> sql = new ConcurrentLinkedQueue<>();
        @Override public String inspect(String statement) {
            sql.add(statement.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " "));
            return statement;
        }
        void clear() { sql.clear(); }
        List<String> selects() { return sql.stream().filter(statement -> statement.startsWith("select ")).toList(); }
    }

    // Internal callers can supply Pageable without PageRequest's constructor guards.
    private record UntrustedPageable(int getPageNumber, int getPageSize) implements Pageable {
        @Override public long getOffset() { return (long) getPageNumber * getPageSize; }
        @Override public Sort getSort() { return Sort.unsorted(); }
        @Override public Pageable next() { return new UntrustedPageable(getPageNumber + 1, getPageSize); }
        @Override public Pageable previousOrFirst() { return first(); }
        @Override public Pageable first() { return new UntrustedPageable(0, getPageSize); }
        @Override public Pageable withPage(int number) { return new UntrustedPageable(number, getPageSize); }
        @Override public boolean hasPrevious() { return getPageNumber > 0; }
    }
}
