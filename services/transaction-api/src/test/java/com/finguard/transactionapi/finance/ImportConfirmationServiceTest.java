package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.*;
import com.finguard.transactionapi.finance.dto.*;
import com.finguard.transactionapi.finance.exception.*;
import com.finguard.transactionapi.finance.importing.*;
import com.finguard.transactionapi.finance.repository.*;
import com.finguard.transactionapi.finance.service.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.*;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportConfirmationServiceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 1);
    private static final Instant OCCURRED = Instant.parse("2026-11-01T12:34:56Z");
    private static final String SIGNATURE = "a".repeat(64);
    private final UUID user = UUID.randomUUID(), accountId = UUID.randomUUID(), batchId = UUID.randomUUID();
    private final ImportBatchRepository batches = mock(ImportBatchRepository.class);
    private final ImportCandidateRepository candidates = mock(ImportCandidateRepository.class);
    private final FinancialAccountRepository accounts = mock(FinancialAccountRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final LedgerEntryRepository ledger = mock(LedgerEntryRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Query transactionSettings = mock(Query.class);
    private final ImportBatch batch = BeanUtils.instantiateClass(ImportBatch.class);
    private final ImportBatchRepository.ConfirmationBatchSnapshot storedBatch = mock(ImportBatchRepository.ConfirmationBatchSnapshot.class);
    private final FinancialAccountRepository.ConfirmationAccount account = mock(FinancialAccountRepository.ConfirmationAccount.class);
    private final ImportConfirmationService service = new ImportConfirmationService(batches, candidates, accounts, categories,
            ledger, JsonMapper.builder().build());
    private final List<LedgerEntry> inserted = new ArrayList<>();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        when(entityManager.createNativeQuery(anyString())).thenReturn(transactionSettings);
        when(transactionSettings.setFlushMode(FlushModeType.COMMIT)).thenReturn(transactionSettings);
        when(transactionSettings.getSingleResult()).thenReturn(new Object[]{"read committed", "off"});
        ReflectionTestUtils.setField(batch, "id", batchId);
        ReflectionTestUtils.setField(batch, "userId", user);
        ReflectionTestUtils.setField(batch, "accountId", accountId);
        ReflectionTestUtils.setField(batch, "status", ImportStatus.REVIEWING);
        ReflectionTestUtils.setField(batch, "version", 7L);
        ReflectionTestUtils.setField(batch, "bankType", BankType.RBC_VISA);
        when(storedBatch.getId()).thenReturn(batchId); when(storedBatch.getUserId()).thenReturn(user);
        when(storedBatch.getAccountId()).thenReturn(accountId); when(storedBatch.getStatus()).thenReturn("REVIEWING");
        when(storedBatch.getVersion()).thenReturn(7L);
        when(batches.findConfirmationSnapshot(batchId, user)).thenReturn(Optional.of(storedBatch));
        when(batches.findByIdAndUserId(batchId, user)).thenReturn(Optional.of(batch));
        when(account.getId()).thenReturn(accountId); when(account.getUserId()).thenReturn(user);
        when(account.getActive()).thenReturn(true); when(account.getCurrencyCode()).thenReturn("CAD");
        when(accounts.findOwnedForConfirmation(accountId, user)).thenReturn(Optional.of(account));
        when(ledger.saveAll(any())).thenAnswer(invocation -> {
            var rows = new ArrayList<LedgerEntry>();
            for (LedgerEntry row : (Iterable<LedgerEntry>) invocation.getArgument(0)) rows.add(row);
            inserted.addAll(rows); return rows;
        });
        doAnswer(ignored -> {
            if (batch.getStatus() == ImportStatus.CONFIRMED) {
                ReflectionTestUtils.setField(batch, "version", batch.getVersion() + 1);
            }
            return null;
        }).when(entityManager).flush();
    }

    @AfterEach void clearTransactionContext() { TransactionSynchronizationManager.clear(); }

    @Test void validatesRequiredIdentityAndNonnegativeVersionBeforeRepositoryAccess() {
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(null, batchId, request()));
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, null, request()));
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, null));
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, new ConfirmImportRequest(null)));
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, new ConfirmImportRequest(-1L)));
        verifyNoInteractions(batches, candidates, accounts, categories, ledger);
    }

    @Test void rejectsMissingTransactionReadOnlyAndIncompatibleActualDatabaseIsolation() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, request()));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, request()));
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        for (Object[] settings : List.of(new Object[]{"repeatable read", "off"},
                new Object[]{"serializable", "off"}, new Object[]{"read committed", "on"})) {
            when(transactionSettings.getSingleResult()).thenReturn(settings);
            assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, request()));
        }
        verifyNoInteractions(batches, candidates, accounts, categories, ledger);
    }

    @Test void missingOrForeignBatchSharesNotFoundAndCannotLoadCandidates() {
        var absent = assertThrows(ImportBatchNotFoundException.class,
                () -> service.confirmImport(user, UUID.randomUUID(), request()));
        var foreign = assertThrows(ImportBatchNotFoundException.class,
                () -> service.confirmImport(UUID.randomUUID(), batchId, request()));
        assertEquals(absent.getMessage(), foreign.getMessage());
        verifyNoInteractions(candidates, accounts, categories, ledger);
    }

    @Test void confirmedRetryUsesStoredSummaryRegardlessOfExpectedVersionOrCachedEntity() {
        Instant confirmedAt = Instant.parse("2026-11-01T13:00:00Z");
        when(storedBatch.getStatus()).thenReturn("CONFIRMED"); when(storedBatch.getVersion()).thenReturn(12L);
        when(storedBatch.getConfirmedCount()).thenReturn(3); when(storedBatch.getExcludedCount()).thenReturn(4);
        when(storedBatch.getConfirmedAt()).thenReturn(confirmedAt);
        var result = service.confirmImport(user, batchId, new ConfirmImportRequest(0L));
        assertEquals(batchId, result.batchId()); assertEquals(ImportStatus.CONFIRMED, result.status());
        assertEquals(12, result.version()); assertEquals(3, result.confirmedCount());
        assertEquals(4, result.notImportedCount()); assertEquals(confirmedAt, result.confirmedAt());
        verify(batches, never()).findByIdAndUserId(any(), any());
        verifyNoInteractions(candidates, accounts, categories, ledger); verify(entityManager, never()).flush();
    }

    @Test void nonReviewingStatesAndStaleFirstConfirmationRejectBeforeAccountAndCandidateReads() {
        for (String status : List.of("DRAFT", "CANCELLED", "FAILED")) {
            when(storedBatch.getStatus()).thenReturn(status);
            assertThrows(ImportBatchStateConflictException.class, () -> service.confirmImport(user, batchId, request()));
        }
        when(storedBatch.getStatus()).thenReturn("REVIEWING");
        assertThrows(ImportConfirmationConflictException.class,
                () -> service.confirmImport(user, batchId, new ConfirmImportRequest(6L)));
        verifyNoInteractions(candidates, accounts, categories, ledger);
    }

    @Test void staleManagedBatchCannotOverwriteFreshLockedState() {
        ReflectionTestUtils.setField(batch, "version", 6L);
        assertThrows(ImportConfirmationConflictException.class, () -> service.confirmImport(user, batchId, request()));
        verifyNoInteractions(accounts, candidates, categories, ledger);
        assertEquals(ImportStatus.REVIEWING, batch.getStatus());
    }

    @Test void missingInactiveOrIncompatibleAccountPreventsAllLedgerWrites() {
        var row = row(2, true); rows(row);
        when(accounts.findOwnedForConfirmation(accountId, user)).thenReturn(Optional.empty());
        assertThrows(FinancialAccountNotFoundException.class, () -> service.confirmImport(user, batchId, request()));
        when(accounts.findOwnedForConfirmation(accountId, user)).thenReturn(Optional.of(account));
        when(account.getActive()).thenReturn(false);
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, request()));
        when(account.getActive()).thenReturn(true); when(account.getCurrencyCode()).thenReturn("USD");
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, request()));
        verifyNoInteractions(ledger); assertEquals(ImportStatus.REVIEWING, batch.getStatus());
    }

    @Test void zeroSelectedAndOversizedTargetBatchesCannotBeConfirmed() {
        rows(row(2, false));
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, request()));
        var first = row(2, true);
        when(candidates.findConfirmationCandidates(eq(batchId), eq(user), any()))
                .thenReturn(Collections.nCopies(CsvParser.DEFAULT_MAX_ROWS + 1, first));
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, request()));
        verifyNoInteractions(ledger); assertEquals(7, batch.getVersion());
    }

    @Test void selectedExpenseBecomesImportedLedgerWithTrustedScopeAndUnselectedRowsAreRetained() {
        var selected = row(2, true); var invalid = row(3, false); var credit = row(4, false);
        when(selected.getBankTransactionId()).thenReturn("synthetic-unverified-rbc-id");
        when(invalid.getAmount()).thenReturn(null); when(invalid.getTransactionDate()).thenReturn(null);
        when(invalid.getValidationErrors()).thenReturn("[{\"code\":\"INVALID_DATE\"}]");
        when(credit.getEntryType()).thenReturn(null); when(credit.getSourceDirection()).thenReturn(ImportSourceDirection.CREDIT);
        when(credit.getValidationErrors()).thenReturn("[{\"code\":\"REVIEW_REQUIRED\"}]");
        rows(selected, invalid, credit); var before = List.of(facts(selected), facts(invalid), facts(credit));
        var result = service.confirmImport(user, batchId, request());
        assertEquals(1, inserted.size()); var entry = inserted.getFirst();
        assertNotNull(entry.getId()); assertNotEquals(selected.getId(), entry.getId());
        assertEquals(user, entry.getUserId()); assertEquals(accountId, entry.getAccountId());
        assertEquals(batchId, entry.getSourceBatchId()); assertEquals(2, entry.getSourceRowNumber());
        assertEquals(EntryType.EXPENSE, entry.getEntryType()); assertEquals(new BigDecimal("30.00"), entry.getAmount());
        assertEquals("CAD", entry.getCurrencyCode()); assertEquals(DATE, entry.getTransactionDate());
        assertEquals(OCCURRED, entry.getOccurredAt()); assertEquals(selected.getDescription(), entry.getDescription());
        assertNull(entry.getBankTransactionId(), "RBC does not supply a validated bank transaction ID");
        assertNull(entry.getClientRequestKey()); assertNull(entry.getDeletedAt());
        assertNotNull(entry.getCreatedAt()); assertEquals(entry.getCreatedAt(), entry.getUpdatedAt());
        assertEquals(ImportStatus.CONFIRMED, result.status()); assertEquals(8, result.version());
        assertEquals(1, result.confirmedCount()); assertEquals(2, result.notImportedCount());
        assertNotNull(result.confirmedAt()); assertEquals(batch.getConfirmedAt(), batch.getClosedAt());
        assertEquals(before, List.of(facts(selected), facts(invalid), facts(credit)));
        verify(candidates, never()).saveAll(any()); verify(candidates, never()).deleteAll(any());
        verify(entityManager, never()).lock(any(), any()); verify(entityManager, times(1)).flush();
    }

    @Test void accountLockAndOrderedCategoryProtectionPrecedeEvidenceAndLedgerWrites() {
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var first = row(2, true); var second = row(3, true);
        when(first.getCategoryId()).thenReturn(high); when(second.getCategoryId()).thenReturn(low);
        when(second.getDescription()).thenReturn("Different merchant");
        rows(first, second); allowCategories(category(high, "EXPENSE", true), category(low, "EXPENSE", true));
        service.confirmImport(user, batchId, request());
        var order = inOrder(batches, accounts, candidates, categories, ledger);
        order.verify(batches).findConfirmationSnapshot(batchId, user);
        order.verify(batches).findByIdAndUserId(batchId, user);
        order.verify(accounts).findOwnedForConfirmation(accountId, user);
        order.verify(candidates).findConfirmationCandidates(eq(batchId), eq(user), any());
        order.verify(categories).findForConfirmation(eq(user), argThat(ids -> ids.size() == 2 && ids.containsAll(List.of(low, high))));
        order.verify(candidates).findExternalDuplicateSources(any(), any(), any(), anyList(), anyList(), anyList(),
                anyList(), anyList(), anyBoolean(), anyBoolean(), anyInt());
        order.verify(ledger).saveAll(any());
        assertEquals(Set.of(low, high), inserted.stream().map(LedgerEntry::getCategoryId).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void everySelectedRowMustHaveValidMoneyDateDescriptionTypeAndCurrency() {
        for (int bad = 0; bad < 11; bad++) {
            var row = row(2, true);
            switch (bad) {
                case 0 -> when(row.getAmount()).thenReturn(null);
                case 1 -> when(row.getAmount()).thenReturn(BigDecimal.ZERO);
                case 2 -> when(row.getAmount()).thenReturn(new BigDecimal("-1.00"));
                case 3 -> when(row.getAmount()).thenReturn(new BigDecimal("30.001"));
                case 4 -> when(row.getAmount()).thenReturn(new BigDecimal("100000000000000000.00"));
                case 5 -> when(row.getTransactionDate()).thenReturn(null);
                case 6 -> when(row.getDescription()).thenReturn(" \t");
                case 7 -> when(row.getDescription()).thenReturn("x".repeat(501));
                case 8 -> when(row.getEntryType()).thenReturn(null);
                case 9 -> when(row.getCurrencyCode()).thenReturn("ZZZ");
                case 10 -> when(row.getCurrencyCode()).thenReturn("USD");
                default -> throw new AssertionError();
            }
            rows(row);
            assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, request()));
        }
        verifyNoInteractions(ledger); assertEquals(ImportStatus.REVIEWING, batch.getStatus());
    }

    @Test void issuesAndUnknownCreditCannotBeBypassedWithCategoryOrAcknowledgement() {
        UUID categoryId = UUID.randomUUID(); allowCategories(category(categoryId, "EXPENSE", true));
        for (String errors : Arrays.asList("[{\"code\":\"REVIEW_REQUIRED\"}]", "[\"issue\"]", "{}", "null", "broken", null)) {
            var expense = row(2, true);
            when(expense.getValidationErrors()).thenReturn(errors);
            when(expense.getCategoryId()).thenReturn(categoryId);
            when(expense.getDuplicateWarning()).thenReturn(true); when(expense.getDuplicateAcknowledged()).thenReturn(true);
            when(expense.getDuplicateMatchSignature()).thenReturn(SIGNATURE); rows(expense);
            assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, request()));
            assertEquals(EntryType.EXPENSE, expense.getEntryType()); assertEquals(errors, expense.getValidationErrors());
        }
        var credit = row(2, true);
        when(credit.getEntryType()).thenReturn(null); when(credit.getSourceDirection()).thenReturn(ImportSourceDirection.CREDIT);
        when(credit.getCategoryId()).thenReturn(categoryId);
        when(credit.getDuplicateWarning()).thenReturn(true); when(credit.getDuplicateAcknowledged()).thenReturn(true);
        when(credit.getDuplicateMatchSignature()).thenReturn(SIGNATURE); rows(credit);
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, request()));
        assertNull(credit.getEntryType()); verifyNoInteractions(ledger);
    }

    @Test void inactiveMissingOrWrongTypeCategoryRejectsTheEntireSelectedSet() {
        UUID categoryId = UUID.randomUUID(); var row = row(2, true); when(row.getCategoryId()).thenReturn(categoryId); rows(row);
        allowCategories();
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, request()));
        for (var category : List.of(category(categoryId, "EXPENSE", false), category(categoryId, "INCOME", true))) {
            allowCategories(category);
            assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, request()));
        }
        verifyNoInteractions(ledger); assertEquals(0, batch.getConfirmedCount()); assertNull(batch.getConfirmedAt());
    }

    @Test void oneInvalidSelectedRowStopsAllInsertsInsteadOfSkippingIt() {
        var first = row(2, true); var second = row(3, true);
        when(second.getValidationErrors()).thenReturn("[{\"code\":\"INVALID_AMOUNT\"}]"); rows(first, second);
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, request()));
        verifyNoInteractions(ledger); assertEquals(ImportStatus.REVIEWING, batch.getStatus());
        assertEquals(7, batch.getVersion()); assertTrue(first.getSelectedForImport());
    }

    @Test void freshEvidenceMustMatchSavedSignatureAndAcknowledgementWithoutWritingNewWarnings() {
        var first = row(2, true); var second = row(9, false); rows(first, second);
        var before = List.of(facts(first), facts(second));
        assertThrows(ImportConfirmationConflictException.class, () -> service.confirmImport(user, batchId, request()));
        assertEquals(before, List.of(facts(first), facts(second))); verifyNoInteractions(ledger);
        saveEvidence(first, second);
        when(first.getDuplicateAcknowledged()).thenReturn(false);
        assertThrows(ImportConfirmationConflictException.class, () -> service.confirmImport(user, batchId, request()));
        when(first.getDuplicateAcknowledged()).thenReturn(true);
        String original = first.getDuplicateMatchSignature();
        when(first.getDuplicateMatchSignature()).thenReturn("b".repeat(64));
        assertThrows(ImportConfirmationConflictException.class, () -> service.confirmImport(user, batchId, request()));
        when(first.getDuplicateMatchSignature()).thenReturn(original);
        var result = service.confirmImport(user, batchId, request());
        assertEquals(1, result.confirmedCount()); assertEquals(1, result.notImportedCount());
        assertTrue(first.getDuplicateWarning()); assertTrue(first.getDuplicateAcknowledged());
        assertEquals(original, first.getDuplicateMatchSignature());
    }

    @Test void disappearedEvidenceRequiresRereviewInsteadOfSilentlyClearingTheSavedWarning() {
        var row = row(2, true); rows(row);
        when(row.getDuplicateWarning()).thenReturn(true); when(row.getDuplicateAcknowledged()).thenReturn(true);
        when(row.getDuplicateMatchSignature()).thenReturn(SIGNATURE);
        assertThrows(ImportConfirmationConflictException.class, () -> service.confirmImport(user, batchId, request()));
        assertTrue(row.getDuplicateWarning()); assertTrue(row.getDuplicateAcknowledged());
        assertEquals(SIGNATURE, row.getDuplicateMatchSignature()); verifyNoInteractions(ledger);
    }

    @Test void newLedgerEvidenceRequiresRereviewAndDoesNotPersistItsComputedSignature() {
        var row = row(2, true); rows(row);
        var source = source(UUID.randomUUID());
        when(candidates.findExternalDuplicateSources(any(), any(), any(), anyList(), anyList(), anyList(),
                anyList(), anyList(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(List.of(source));
        assertThrows(ImportConfirmationConflictException.class, () -> service.confirmImport(user, batchId, request()));
        assertFalse(row.getDuplicateWarning()); assertFalse(row.getDuplicateAcknowledged());
        assertNull(row.getDuplicateMatchSignature()); assertEquals(7, batch.getVersion()); verifyNoInteractions(ledger);
    }

    @Test void incompleteExternalScanFailsClosedBeforeAnyLedgerOrTerminalStateWrite() {
        rows(row(2, true)); var source = source(UUID.randomUUID());
        when(candidates.findExternalDuplicateSources(any(), any(), any(), anyList(), anyList(), anyList(),
                anyList(), anyList(), anyBoolean(), anyBoolean(), anyInt()))
                .thenReturn(Collections.nCopies(20_001, source));
        assertThrows(InvalidImportRequestException.class, () -> service.confirmImport(user, batchId, request()));
        verifyNoInteractions(ledger); assertEquals(ImportStatus.REVIEWING, batch.getStatus());
        assertNull(batch.getConfirmedAt()); assertEquals(7, batch.getVersion());
    }

    @Test void staleEvidenceOnAnUnselectedUnrelatedRowDoesNotBlockTheValidSelectedSet() {
        var selected = row(2, true); var ignored = row(3, false);
        when(ignored.getDescription()).thenReturn("Different merchant");
        when(ignored.getDuplicateWarning()).thenReturn(true); when(ignored.getDuplicateAcknowledged()).thenReturn(true);
        when(ignored.getDuplicateMatchSignature()).thenReturn(SIGNATURE); rows(selected, ignored);
        var before = facts(ignored); var result = service.confirmImport(user, batchId, request());
        assertEquals(1, result.confirmedCount()); assertEquals(before, facts(ignored));
        assertEquals(2, inserted.getFirst().getSourceRowNumber());
    }

    @Test void onlyRecognizedBusinessUniqueViolationsAreTranslatedToConfirmationConflict() {
        rows(row(2, true));
        for (String name : List.of("uq_ledger_imported_row", "uq_ledger_trusted_bank_txn")) {
            var constraint = new ConstraintViolationException("Synthetic unique failure", new SQLException("synthetic", "23505"), name);
            var failure = new DataIntegrityViolationException("Synthetic persistence failure", constraint);
            doThrow(failure).when(ledger).saveAll(any());
            var conflict = assertThrows(ImportConfirmationConflictException.class, () -> service.confirmImport(user, batchId, request()));
            assertSame(failure, conflict.getCause()); assertEquals(ImportStatus.REVIEWING, batch.getStatus());
        }
    }

    @Test void unknownUniqueConstraintAndNonUniqueDatabaseErrorsPropagateUnchanged() {
        rows(row(2, true));
        for (String[] details : List.of(new String[]{"23505", "ledger_entry_pkey"},
                new String[]{"23514", "uq_ledger_imported_row"}, new String[]{"23503", "fk_entry_category_owner_type"})) {
            var constraint = new ConstraintViolationException("Synthetic database failure", new SQLException("synthetic", details[0]), details[1]);
            var failure = new DataIntegrityViolationException("Synthetic persistence failure", constraint);
            doThrow(failure).when(ledger).saveAll(any());
            assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.confirmImport(user, batchId, request())));
            assertEquals(ImportStatus.REVIEWING, batch.getStatus());
        }
    }

    @Test void flushFailureDoesNotReturnAFakeSuccessfulSummary() {
        rows(row(2, true));
        var failure = new IllegalStateException("Synthetic flush failure");
        doThrow(failure).when(entityManager).flush();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.confirmImport(user, batchId, request())));
        // Actual database rollback, including already executed INSERTs, is covered by PostgreSQL tests.
        verify(entityManager).flush();
    }

    @Test void anActualOptimisticFailureBecomesAConflictInsteadOfAutomaticReplay() {
        rows(row(2, true));
        doThrow(new jakarta.persistence.OptimisticLockException("Synthetic stale managed Batch")).when(entityManager).flush();
        assertThrows(ImportConfirmationConflictException.class, () -> service.confirmImport(user, batchId, request()));
        verify(ledger, times(1)).saveAll(any()); verify(entityManager, times(1)).flush();
    }

    private ConfirmImportRequest request() { return new ConfirmImportRequest(7L); }

    private ImportCandidateRepository.ConfirmationCandidate row(int number, boolean selected) {
        var row = mock(ImportCandidateRepository.ConfirmationCandidate.class);
        when(row.getId()).thenReturn(UUID.randomUUID()); when(row.getBatchId()).thenReturn(batchId); when(row.getUserId()).thenReturn(user);
        when(row.getRowNumber()).thenReturn(number); when(row.getEntryType()).thenReturn(EntryType.EXPENSE);
        when(row.getAmount()).thenReturn(new BigDecimal("30.00")); when(row.getCurrencyCode()).thenReturn("CAD");
        when(row.getTransactionDate()).thenReturn(DATE); when(row.getOccurredAt()).thenReturn(OCCURRED);
        when(row.getDescription()).thenReturn("  Synthetic merchant #12  "); when(row.getValidationErrors()).thenReturn("[]");
        when(row.getSourceDirection()).thenReturn(ImportSourceDirection.DEBIT); when(row.getSelectedForImport()).thenReturn(selected);
        return row;
    }
    private void rows(ImportCandidateRepository.ConfirmationCandidate... rows) {
        when(candidates.findConfirmationCandidates(eq(batchId), eq(user), any())).thenReturn(List.of(rows));
    }
    private CategoryRepository.ConfirmationCategory category(UUID id, String type, boolean active) {
        var category = mock(CategoryRepository.ConfirmationCategory.class);
        when(category.getId()).thenReturn(id); when(category.getUserId()).thenReturn(user);
        when(category.getEntryType()).thenReturn(type); when(category.getActive()).thenReturn(active);
        return category;
    }
    private void allowCategories(CategoryRepository.ConfirmationCategory... rows) {
        when(categories.findForConfirmation(eq(user), anyList())).thenReturn(List.of(rows));
    }
    private ImportCandidateRepository.ExternalDuplicateSource source(UUID id) {
        var source = mock(ImportCandidateRepository.ExternalDuplicateSource.class);
        when(source.getSourceKind()).thenReturn("LEDGER"); when(source.getSourceId()).thenReturn(id);
        when(source.getTransactionDate()).thenReturn(DATE); when(source.getCurrencyCode()).thenReturn("CAD");
        when(source.getEntryType()).thenReturn("EXPENSE"); when(source.getAmount()).thenReturn(new BigDecimal("30.00"));
        when(source.getDescription()).thenReturn("synthetic merchant #12"); return source;
    }
    private void saveEvidence(ImportCandidateRepository.ConfirmationCandidate... projections) {
        var actualRows = Arrays.stream(projections).map(row -> new ImportCandidate(row.getId(), batchId, user,
                row.getRowNumber(), row.getEntryType(), row.getAmount(), row.getCurrencyCode(), row.getTransactionDate(),
                row.getOccurredAt(), row.getDescription(), row.getSourceDirection(), row.getValidationErrors())).toList();
        when(batches.findOwnedForDuplicateScan(batchId, user)).thenReturn(Optional.of(batch));
        when(candidates.findForDuplicateScan(eq(batchId), eq(user), any())).thenReturn(actualRows);
        var scanner = new ImportDuplicateWarningService(batches, candidates);
        ReflectionTestUtils.setField(scanner, "entityManager", entityManager);
        scanner.refreshDuplicateWarnings(user, batchId);
        for (int index = 0; index < projections.length; index++) {
            when(projections[index].getDuplicateWarning()).thenReturn(actualRows.get(index).getDuplicateWarning());
            when(projections[index].getDuplicateMatchSignature()).thenReturn(actualRows.get(index).getDuplicateMatchSignature());
            when(projections[index].getDuplicateAcknowledged()).thenReturn(actualRows.get(index).getDuplicateWarning());
        }
        clearInvocations(batches, candidates, accounts, categories, ledger, entityManager);
    }
    private static List<Object> facts(ImportCandidateRepository.ConfirmationCandidate row) {
        return Arrays.asList(row.getId(), row.getBatchId(), row.getUserId(), row.getRowNumber(), row.getEntryType(),
                row.getAmount(), row.getCurrencyCode(), row.getTransactionDate(), row.getOccurredAt(), row.getDescription(),
                row.getCategoryId(), row.getBankTransactionId(), row.getValidationErrors(), row.getSelectedForImport(),
                row.getDuplicateWarning(), row.getDuplicateAcknowledged(), row.getDuplicateMatchSignature(), row.getSourceDirection());
    }
}
