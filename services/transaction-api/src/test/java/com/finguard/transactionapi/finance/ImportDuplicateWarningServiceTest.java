package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.EntryType;
import com.finguard.transactionapi.finance.domain.ImportBatch;
import com.finguard.transactionapi.finance.domain.ImportCandidate;
import com.finguard.transactionapi.finance.domain.ImportStatus;
import com.finguard.transactionapi.finance.exception.ImportBatchNotFoundException;
import com.finguard.transactionapi.finance.exception.ImportBatchStateConflictException;
import com.finguard.transactionapi.finance.exception.InvalidImportRequestException;
import com.finguard.transactionapi.finance.importing.CsvParser;
import com.finguard.transactionapi.finance.importing.ImportSourceDirection;
import com.finguard.transactionapi.finance.repository.ImportBatchRepository;
import com.finguard.transactionapi.finance.repository.ImportCandidateRepository;
import com.finguard.transactionapi.finance.service.ImportDuplicateWarningService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportDuplicateWarningServiceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 1);
    private static final PageRequest SCAN = PageRequest.of(0, CsvParser.DEFAULT_MAX_ROWS + 1);
    private final UUID user = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();
    private final ImportBatchRepository batches = mock(ImportBatchRepository.class);
    private final ImportCandidateRepository candidates = mock(ImportCandidateRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final ImportDuplicateWarningService service = new ImportDuplicateWarningService(batches, candidates);

    @BeforeEach void injectEntityManager() {
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
    }

    @Test void rejectsNullArgumentsBeforeAnyRepositoryAccess() {
        assertThrows(InvalidImportRequestException.class,
                () -> service.refreshWithinBatchDuplicateWarnings(null, account, batchId));
        assertThrows(InvalidImportRequestException.class,
                () -> service.refreshWithinBatchDuplicateWarnings(user, null, batchId));
        assertThrows(InvalidImportRequestException.class,
                () -> service.refreshWithinBatchDuplicateWarnings(user, account, null));
        verifyNoInteractions(batches, candidates, entityManager);
    }

    @Test void absentForeignAndWrongAccountBatchesShareNotFoundWithoutCandidateAccess() {
        UUID missing = UUID.randomUUID();
        UUID otherAccount = UUID.randomUUID();
        when(batches.findOwnedForDuplicateScan(batchId, user, account)).thenReturn(Optional.empty());
        when(batches.findOwnedForDuplicateScan(missing, user, account)).thenReturn(Optional.empty());
        when(batches.findOwnedForDuplicateScan(batchId, user, otherAccount)).thenReturn(Optional.empty());
        var foreign = assertThrows(ImportBatchNotFoundException.class,
                () -> service.refreshWithinBatchDuplicateWarnings(user, account, batchId));
        var absent = assertThrows(ImportBatchNotFoundException.class,
                () -> service.refreshWithinBatchDuplicateWarnings(user, account, missing));
        var wrongAccount = assertThrows(ImportBatchNotFoundException.class,
                () -> service.refreshWithinBatchDuplicateWarnings(user, otherAccount, batchId));
        assertEquals(foreign.getMessage(), absent.getMessage());
        assertEquals(foreign.getMessage(), wrongAccount.getMessage());
        verifyNoInteractions(candidates, entityManager);
    }

    @Test void everyNonReviewingStateIsRejectedBeforeCandidateAccessOrVersionChange() {
        for (ImportStatus status : ImportStatus.values()) {
            if (status == ImportStatus.REVIEWING) continue;
            ImportBatch batch = batch(batchId, account, status);
            when(batches.findOwnedForDuplicateScan(batchId, user, account)).thenReturn(Optional.of(batch));
            assertThrows(ImportBatchStateConflictException.class,
                    () -> service.refreshWithinBatchDuplicateWarnings(user, account, batchId));
        }
        verifyNoInteractions(candidates, entityManager);
    }

    @Test void scanLimitIsCheckedBeforeMutatingAnyCandidate() {
        ImportCandidate first = row(2, "30.00", "Synthetic shop");
        List<ImportCandidate> oversized = Collections.nCopies(CsvParser.DEFAULT_MAX_ROWS + 1, first);
        ImportBatch batch = allow(batchId, account, oversized);
        assertThrows(InvalidImportRequestException.class,
                () -> service.refreshWithinBatchDuplicateWarnings(user, account, batchId));
        assertFalse(first.getDuplicateWarning());
        verify(candidates).findForDuplicateScan(batchId, user, SCAN);
        verifyNoInteractions(entityManager);
        assertEquals(7, batch.getVersion());
    }

    @Test void equalNumericAmountsMatchNonAdjacentRowsWithoutChangingTheirStoredScales() {
        ImportCandidate first = row(2, "30.0", "Synthetic shop");
        ImportCandidate adjacentDifferent = row(3, "31.00", "Synthetic shop");
        ImportCandidate later = row(12, "30.00", "Synthetic shop");
        ImportBatch batch = allow(batchId, account, List.of(first, adjacentDifferent, later));
        var before = List.of(facts(first), facts(adjacentDifferent), facts(later));
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        assertTrue(first.getDuplicateWarning());
        assertTrue(later.getDuplicateWarning());
        assertFalse(adjacentDifferent.getDuplicateWarning());
        assertEquals(first.getDuplicateMatchSignature(), later.getDuplicateMatchSignature());
        assertTrue(first.getDuplicateMatchSignature().matches("[0-9a-f]{64}"));
        assertEquals(1, first.getAmount().scale());
        assertEquals(2, later.getAmount().scale());
        assertEquals(before, List.of(facts(first), facts(adjacentDifferent), facts(later)));
        verify(entityManager).lock(batch, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        verifyNoMoreInteractions(entityManager);
        var order = inOrder(batches, candidates);
        order.verify(batches).findOwnedForDuplicateScan(batchId, user, account);
        order.verify(candidates).findForDuplicateScan(batchId, user, SCAN);
        order.verify(candidates).findExternalDuplicateSources(eq(user), eq(batchId), eq(account),
                anyList(), anyList(), anyList(), anyList(), anyList(), anyBoolean(), anyBoolean(), eq(20001));
        order.verifyNoMoreInteractions();
    }

    @Test void differentDateCurrencyDirectionTypeAmountAndDescriptionNeverMixGroups() {
        var rows = new ArrayList<ImportCandidate>();
        rows.add(row(2, "30.00", "Store #12"));
        ImportCandidate date = row(3, "30.00", "Store #12");
        ReflectionTestUtils.setField(date, "transactionDate", DATE.plusDays(1)); rows.add(date);
        ImportCandidate currency = row(4, "30.00", "Store #12");
        ReflectionTestUtils.setField(currency, "currencyCode", "USD"); rows.add(currency);
        ImportCandidate direction = row(5, "30.00", "Store #12");
        ReflectionTestUtils.setField(direction, "sourceDirection", ImportSourceDirection.CREDIT); rows.add(direction);
        ImportCandidate type = row(6, "30.00", "Store #12");
        ReflectionTestUtils.setField(type, "entryType", EntryType.INCOME); rows.add(type);
        rows.add(row(7, "30.01", "Store #12"));
        rows.add(row(8, "30.00", "Store #13"));
        rows.add(row(9, "30.00", "Store 12"));
        allow(batchId, account, rows);
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        assertTrue(rows.stream().noneMatch(ImportCandidate::getDuplicateWarning));
        verifyNoInteractions(entityManager);
    }

    @Test void normalizationIsUnicodeWhitespaceAwareAndIndependentOfDefaultLocaleAndQueryOrder() {
        ImportCandidate first = row(2, "30.00", "  MILK\u00a0STORE\t#12\n");
        ImportCandidate second = row(9, "30.00", "milk store #12");
        allow(batchId, account, List.of(first, second));
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
            assertTrue(first.getDuplicateWarning());
            String signature = first.getDuplicateMatchSignature();
            acknowledge(first); acknowledge(second);
            clearInvocations(entityManager);
            Locale.setDefault(Locale.US);
            when(candidates.findForDuplicateScan(batchId, user, SCAN)).thenReturn(List.of(second, first));
            service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
            assertEquals(signature, first.getDuplicateMatchSignature());
            assertEquals(signature, second.getDuplicateMatchSignature());
            assertTrue(first.getDuplicateAcknowledged());
            assertTrue(second.getDuplicateAcknowledged());
            assertEquals("  MILK\u00a0STORE\t#12\n", first.getDescription());
            verifyNoInteractions(entityManager);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test void singletonCannotMatchItselfAndThreeDistinctMembersAllReceiveTheSameWarning() {
        ImportCandidate lone = row(2, "30.00", "Synthetic shop");
        ImportBatch batch = allow(batchId, account, List.of(lone, lone));
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        assertFalse(lone.getDuplicateWarning(), "Repeating one ID is not evidence of two transactions");
        verifyNoInteractions(entityManager);
        ImportCandidate second = row(8, "30.00", "Synthetic shop");
        ImportCandidate third = row(50, "30.00", "Synthetic shop");
        when(candidates.findForDuplicateScan(batchId, user, SCAN)).thenReturn(List.of(third, lone, second));
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        for (var candidate : List.of(lone, second, third)) {
            assertTrue(candidate.getDuplicateWarning());
            assertFalse(candidate.getDuplicateAcknowledged());
            assertEquals(lone.getDuplicateMatchSignature(), candidate.getDuplicateMatchSignature());
        }
        verify(entityManager).lock(batch, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        verifyNoMoreInteractions(entityManager);
    }

    @Test void reviewRequiredCreditsMatchWithoutClassificationOrAnyFactIssueOrSelectionChange() {
        ImportCandidate first = row(2, "30.00", "Synthetic credit");
        ImportCandidate second = row(8, "30.00", "Synthetic credit");
        for (var candidate : List.of(first, second)) {
            ReflectionTestUtils.setField(candidate, "sourceDirection", ImportSourceDirection.CREDIT);
            ReflectionTestUtils.setField(candidate, "entryType", null);
            ReflectionTestUtils.setField(candidate, "validationErrors", "[{\"code\":\"REVIEW_REQUIRED\"}]");
        }
        var before = List.of(facts(first), facts(second));
        allow(batchId, account, List.of(first, second));
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        assertTrue(first.getDuplicateWarning());
        assertTrue(second.getDuplicateWarning());
        assertNull(first.getEntryType());
        assertFalse(first.getSelectedForImport());
        assertEquals(before, List.of(facts(first), facts(second)));
    }

    @Test void missingOrInvalidComparisonFactsAreSkippedWithoutRepairingDataOrIssues() {
        var rows = new ArrayList<ImportCandidate>();
        Object[][] invalidFacts = {
                {"transactionDate", null}, {"amount", null}, {"currencyCode", null},
                {"sourceDirection", null}, {"description", null}, {"description", " \t\n "},
                {"currencyCode", "cad"}, {"currencyCode", "ZZZ"},
                {"amount", BigDecimal.ZERO}, {"amount", new BigDecimal("-1.00")},
                {"amount", new BigDecimal("30.001")},
                {"amount", new BigDecimal("100000000000000000.00")}
        };
        for (Object[] invalid : invalidFacts) {
            for (int index = 0; index < 2; index++) {
                ImportCandidate candidate = row(rows.size() + 2, "30.00", "Synthetic invalid");
                ReflectionTestUtils.setField(candidate, (String) invalid[0], invalid[1]);
                ReflectionTestUtils.setField(candidate, "validationErrors", "[{\"code\":\"INVALID_AMOUNT\"}]");
                rows.add(candidate);
            }
        }
        var before = rows.stream().map(ImportDuplicateWarningServiceTest::facts).toList();
        allow(batchId, account, rows);
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        assertTrue(rows.stream().noneMatch(ImportCandidate::getDuplicateWarning));
        assertEquals(before, rows.stream().map(ImportDuplicateWarningServiceTest::facts).toList());
        verifyNoInteractions(entityManager);
    }

    @Test void identicalRescanPreservesAcknowledgementsAndDoesNotRequestAnotherVersionIncrement() {
        ImportCandidate first = row(2, "30.00", "Synthetic shop");
        ImportCandidate second = row(9, "30.00", "Synthetic shop");
        ImportBatch batch = allow(batchId, account, List.of(first, second));
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        String signature = first.getDuplicateMatchSignature();
        acknowledge(first); acknowledge(second);
        clearInvocations(entityManager);
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        assertTrue(first.getDuplicateAcknowledged());
        assertTrue(second.getDuplicateAcknowledged());
        assertEquals(signature, first.getDuplicateMatchSignature());
        assertEquals(signature, second.getDuplicateMatchSignature());
        verifyNoInteractions(entityManager);
        assertEquals(7, batch.getVersion(), "Only Hibernate, not service assignment, owns the mapped version");
    }

    @Test void memberAndKeyEvidenceChangesInvalidateOldAcknowledgementsForTheEntireGroup() {
        ImportCandidate first = row(2, "30.00", "Synthetic shop");
        ImportCandidate second = row(9, "30.00", "Synthetic shop");
        ImportBatch batch = allow(batchId, account, List.of(first, second));
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        String initial = first.getDuplicateMatchSignature();
        acknowledge(first); acknowledge(second);
        ImportCandidate third = row(20, "30.00", "Synthetic shop");
        when(candidates.findForDuplicateScan(batchId, user, SCAN)).thenReturn(List.of(first, second, third));
        clearInvocations(entityManager);
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        String expanded = first.getDuplicateMatchSignature();
        assertNotEquals(initial, expanded);
        for (var candidate : List.of(first, second, third)) {
            assertFalse(candidate.getDuplicateAcknowledged());
            assertEquals(expanded, candidate.getDuplicateMatchSignature());
            acknowledge(candidate);
            ReflectionTestUtils.setField(candidate, "description", "Changed shop #2");
        }
        verify(entityManager).lock(batch, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        clearInvocations(entityManager);
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        assertNotEquals(expanded, first.getDuplicateMatchSignature());
        assertTrue(List.of(first, second, third).stream().noneMatch(ImportCandidate::getDuplicateAcknowledged));
        verify(entityManager).lock(batch, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        verifyNoMoreInteractions(entityManager);
    }

    @Test void disappearedMatchClearsAllWarningMetadataAndSubsequentScanIsANoop() {
        ImportCandidate first = row(2, "30.00", "Synthetic shop");
        ImportCandidate second = row(9, "30.00", "Synthetic shop");
        ImportBatch batch = allow(batchId, account, List.of(first, second));
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        acknowledge(first); acknowledge(second);
        ReflectionTestUtils.setField(second, "transactionDate", null);
        String issues = "[{\"code\":\"INVALID_DATE\"}]";
        ReflectionTestUtils.setField(second, "validationErrors", issues);
        clearInvocations(entityManager);
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        for (var candidate : List.of(first, second)) {
            assertFalse(candidate.getDuplicateWarning());
            assertFalse(candidate.getDuplicateAcknowledged());
            assertNull(candidate.getDuplicateMatchSignature());
        }
        assertNull(second.getTransactionDate());
        assertEquals(issues, second.getValidationErrors());
        verify(entityManager).lock(batch, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        clearInvocations(entityManager);
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        verifyNoInteractions(entityManager);
    }

    @Test void signatureIncludesBatchAndAccountScopeAndIgnoresRowNumbersAndBankIds() {
        ImportCandidate first = row(2, "30.00", "Synthetic shop");
        ImportCandidate second = row(9, "30.00", "Synthetic shop");
        allow(batchId, account, List.of(first, second));
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        String initial = first.getDuplicateMatchSignature();
        acknowledge(first); acknowledge(second);
        ReflectionTestUtils.setField(first, "rowNumber", 77);
        ReflectionTestUtils.setField(second, "bankTransactionId", "synthetic-unverified-bank-id");
        clearInvocations(entityManager);
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        assertEquals(initial, first.getDuplicateMatchSignature());
        assertTrue(first.getDuplicateAcknowledged());
        verifyNoInteractions(entityManager);

        UUID otherBatch = UUID.randomUUID();
        ReflectionTestUtils.setField(first, "batchId", otherBatch);
        ReflectionTestUtils.setField(second, "batchId", otherBatch);
        allow(otherBatch, account, List.of(first, second));
        service.refreshWithinBatchDuplicateWarnings(user, account, otherBatch);
        String otherBatchSignature = first.getDuplicateMatchSignature();
        assertNotEquals(initial, otherBatchSignature);
        UUID otherAccount = UUID.randomUUID();
        allow(otherBatch, otherAccount, List.of(first, second));
        service.refreshWithinBatchDuplicateWarnings(user, otherAccount, otherBatch);
        assertNotEquals(otherBatchSignature, first.getDuplicateMatchSignature());
    }

    private ImportBatch allow(UUID id, UUID accountId, List<ImportCandidate> rows) {
        ImportBatch batch = batch(id, accountId, ImportStatus.REVIEWING);
        when(batches.findOwnedForDuplicateScan(id, user, accountId)).thenReturn(Optional.of(batch));
        when(candidates.findForDuplicateScan(id, user, SCAN)).thenReturn(rows);
        return batch;
    }

    private ImportBatch batch(UUID id, UUID accountId, ImportStatus status) {
        ImportBatch batch = mock(ImportBatch.class);
        when(batch.getId()).thenReturn(id);
        when(batch.getAccountId()).thenReturn(accountId);
        when(batch.getUserId()).thenReturn(user);
        when(batch.getStatus()).thenReturn(status);
        when(batch.getVersion()).thenReturn(7L);
        return batch;
    }

    private ImportCandidate row(int rowNumber, String amount, String description) {
        return new ImportCandidate(UUID.randomUUID(), batchId, user, rowNumber, EntryType.EXPENSE,
                new BigDecimal(amount), "CAD", DATE, Instant.parse("2026-11-01T12:00:00Z"),
                description, ImportSourceDirection.DEBIT, "[]");
    }

    private static void acknowledge(ImportCandidate candidate) {
        assertTrue(candidate.getDuplicateWarning());
        ReflectionTestUtils.setField(candidate, "duplicateAcknowledged", true);
    }

    private static List<Object> facts(ImportCandidate row) {
        return Arrays.asList(row.getId(), row.getBatchId(), row.getUserId(), row.getRowNumber(),
                row.getEntryType(), row.getAmount(), row.getCurrencyCode(), row.getTransactionDate(),
                row.getOccurredAt(), row.getDescription(), row.getSourceDirection(), row.getCategoryId(),
                row.getBankTransactionId(), row.getValidationErrors(), row.getSelectedForImport());
    }
}
