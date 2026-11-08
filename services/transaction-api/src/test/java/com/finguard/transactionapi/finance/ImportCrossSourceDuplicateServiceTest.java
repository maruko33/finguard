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
import com.finguard.transactionapi.finance.repository.ImportCandidateRepository.ExternalDuplicateSource;
import com.finguard.transactionapi.finance.service.ImportDuplicateWarningService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportCrossSourceDuplicateServiceTest {
    private static final LocalDate DATE = LocalDate.of(2020, 2, 3);
    private static final PageRequest SCAN = PageRequest.of(0, CsvParser.DEFAULT_MAX_ROWS + 1);
    private final UUID user = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();
    private final ImportBatchRepository batches = mock(ImportBatchRepository.class);
    private final ImportCandidateRepository candidates = mock(ImportCandidateRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final ImportDuplicateWarningService service = new ImportDuplicateWarningService(batches, candidates);
    private ImportBatch batch;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        batch = mock(ImportBatch.class);
        when(batch.getId()).thenReturn(batchId);
        when(batch.getAccountId()).thenReturn(account);
        when(batch.getUserId()).thenReturn(user);
        when(batch.getStatus()).thenReturn(ImportStatus.REVIEWING);
        when(batches.findOwnedForDuplicateScan(batchId, user)).thenReturn(Optional.of(batch));
        when(batches.findOwnedForDuplicateScan(batchId, user, account)).thenReturn(Optional.of(batch));
    }

    @Test void unifiedEntryRejectsNullAndUsesTheOwnedBatchBeforeAnyCandidateQuery() {
        assertThrows(InvalidImportRequestException.class, () -> service.refreshDuplicateWarnings(null, batchId));
        assertThrows(InvalidImportRequestException.class, () -> service.refreshDuplicateWarnings(user, null));
        UUID missing = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        var absent = assertThrows(ImportBatchNotFoundException.class,
                () -> service.refreshDuplicateWarnings(user, missing));
        var denied = assertThrows(ImportBatchNotFoundException.class,
                () -> service.refreshDuplicateWarnings(foreign, batchId));
        assertEquals(absent.getMessage(), denied.getMessage());
        verifyNoInteractions(candidates, entityManager);
    }

    @Test void allTerminalAndDraftStatusesFailBeforeReadingCandidates() {
        for (ImportStatus status : ImportStatus.values()) {
            if (status == ImportStatus.REVIEWING) continue;
            when(batch.getStatus()).thenReturn(status);
            assertThrows(ImportBatchStateConflictException.class, () -> service.refreshDuplicateWarnings(user, batchId));
        }
        verifyNoInteractions(candidates, entityManager);
    }

    @Test void compatibilityEntryRetainsAccountAuthorizationButUsesAllSources() {
        ImportCandidate target = row(2, "Shop #12");
        rows(target);
        sources(List.of(ledger(UUID.randomUUID(), "Shop #12")));
        service.refreshWithinBatchDuplicateWarnings(user, account, batchId);
        assertTrue(target.getDuplicateWarning());
        acknowledge(target);
        String signature = target.getDuplicateMatchSignature();
        clearInvocations(entityManager);
        service.refreshDuplicateWarnings(user, batchId);
        assertEquals(signature, target.getDuplicateMatchSignature());
        assertTrue(target.getDuplicateAcknowledged());
        verifyNoInteractions(entityManager);
        UUID unowned = UUID.randomUUID();
        assertThrows(ImportBatchNotFoundException.class,
                () -> service.refreshWithinBatchDuplicateWarnings(user, unowned, batchId));
        assertTrue(target.getDuplicateAcknowledged());
    }

    @Test void allThreeSourcesAreMergedBeforeOneWriteAndRemovingOneInvalidatesAcknowledgement() {
        ImportCandidate first = spy(row(2, "Shop #12"));
        ImportCandidate second = spy(row(9, "Shop #12"));
        rows(first, second);
        ExternalDuplicateSource other = other(UUID.randomUUID(), UUID.randomUUID(), "Shop #12");
        ExternalDuplicateSource posted = ledger(UUID.randomUUID(), "Shop #12");
        sources(List.of(other, posted));
        service.refreshDuplicateWarnings(user, batchId);
        verify(first, times(1)).refreshDuplicateWarning(anyString());
        verify(second, times(1)).refreshDuplicateWarning(anyString());
        String allSources = first.getDuplicateMatchSignature();
        acknowledge(first); acknowledge(second);
        clearInvocations(entityManager);
        sources(List.of(posted));
        service.refreshDuplicateWarnings(user, batchId);
        assertTrue(first.getDuplicateWarning());
        assertFalse(first.getDuplicateAcknowledged());
        assertNotEquals(allSources, first.getDuplicateMatchSignature());
        verify(entityManager, times(1)).lock(batch, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        String withLedger = first.getDuplicateMatchSignature();
        acknowledge(first); acknowledge(second);
        sources(List.of());
        service.refreshDuplicateWarnings(user, batchId);
        assertTrue(first.getDuplicateWarning(), "The intra-batch evidence survives losing every external source");
        assertFalse(first.getDuplicateAcknowledged());
        assertNotEquals(withLedger, first.getDuplicateMatchSignature());
        acknowledge(first); acknowledge(second);
        ReflectionTestUtils.setField(second, "description", "Different shop");
        service.refreshDuplicateWarnings(user, batchId);
        for (var target : List.of(first, second)) {
            assertFalse(target.getDuplicateWarning());
            assertFalse(target.getDuplicateAcknowledged());
            assertNull(target.getDuplicateMatchSignature());
        }
    }

    @Test void externalEvidenceOrderingAndDuplicateProjectionRowsDoNotChangeTheSignature() {
        ImportCandidate target = row(2, "Shop #12"); rows(target);
        var other = other(UUID.randomUUID(), UUID.randomUUID(), "Shop #12");
        var posted = ledger(UUID.randomUUID(), "Shop #12");
        sources(List.of(other, posted));
        service.refreshDuplicateWarnings(user, batchId);
        String original = target.getDuplicateMatchSignature(); acknowledge(target);
        clearInvocations(entityManager);
        sources(List.of(posted, other, posted, other));
        service.refreshDuplicateWarnings(user, batchId);
        assertEquals(original, target.getDuplicateMatchSignature());
        assertTrue(target.getDuplicateAcknowledged());
        verifyNoInteractions(entityManager);
    }

    @Test void evidenceSourceTagAndOtherBatchIdentityArePartOfTheSignature() {
        ImportCandidate target = row(2, "Shop #12"); rows(target);
        UUID sharedId = UUID.randomUUID();
        sources(List.of(ledger(sharedId, "Shop #12")));
        service.refreshDuplicateWarnings(user, batchId);
        String ledgerSignature = target.getDuplicateMatchSignature(); acknowledge(target);
        sources(List.of(other(sharedId, UUID.randomUUID(), "Shop #12")));
        service.refreshDuplicateWarnings(user, batchId);
        String candidateSignature = target.getDuplicateMatchSignature();
        assertNotEquals(ledgerSignature, candidateSignature);
        assertFalse(target.getDuplicateAcknowledged()); acknowledge(target);
        sources(List.of(other(sharedId, UUID.randomUUID(), "Shop #12")));
        service.refreshDuplicateWarnings(user, batchId);
        assertNotEquals(candidateSignature, target.getDuplicateMatchSignature());
        assertFalse(target.getDuplicateAcknowledged());
    }

    @Test void legacyIntraOnlyV1SignatureAndAcknowledgementRemainUnchanged() {
        UUID fixedBatch = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID fixedAccount = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String legacy = "1959f4f182b841f009426d31ea3f156cbe0f74e3cae8cfcb981f0e98faa37fb7";
        when(batch.getId()).thenReturn(fixedBatch);
        when(batch.getAccountId()).thenReturn(fixedAccount);
        when(batches.findOwnedForDuplicateScan(fixedBatch, user)).thenReturn(Optional.of(batch));
        ImportCandidate first = row(2, "Shop #12"); ImportCandidate second = row(8, "  SHOP  #12  ");
        ReflectionTestUtils.setField(first, "id", UUID.fromString("33333333-3333-3333-3333-333333333333"));
        ReflectionTestUtils.setField(second, "id", UUID.fromString("44444444-4444-4444-4444-444444444444"));
        for (var target : List.of(first, second)) {
            ReflectionTestUtils.setField(target, "batchId", fixedBatch);
            target.refreshDuplicateWarning(legacy); acknowledge(target);
        }
        when(candidates.findForDuplicateScan(fixedBatch, user, SCAN)).thenReturn(List.of(second, first));
        service.refreshDuplicateWarnings(user, fixedBatch);
        assertEquals(legacy, first.getDuplicateMatchSignature());
        assertEquals(legacy, second.getDuplicateMatchSignature());
        assertTrue(first.getDuplicateAcknowledged()); assertTrue(second.getDuplicateAcknowledged());
        verifyNoInteractions(entityManager);
    }

    @Test void unknownCreditCanMatchAnotherCandidateButNeverAnInferredLedgerIncome() {
        ImportCandidate credit = row(2, "Credit refund");
        ReflectionTestUtils.setField(credit, "entryType", null);
        ReflectionTestUtils.setField(credit, "sourceDirection", ImportSourceDirection.CREDIT);
        ReflectionTestUtils.setField(credit, "validationErrors", "[{\"code\":\"REVIEW_REQUIRED\"}]");
        rows(credit);
        var income = ledger(UUID.randomUUID(), "Credit refund");
        when(income.getEntryType()).thenReturn("INCOME");
        // Even an over-inclusive coarse result may not infer a target type.
        sources(List.of(income));
        service.refreshDuplicateWarnings(user, batchId);
        assertFalse(credit.getDuplicateWarning());
        var otherCredit = other(UUID.randomUUID(), UUID.randomUUID(), "Credit refund");
        when(otherCredit.getEntryType()).thenReturn(null);
        when(otherCredit.getSourceDirection()).thenReturn("CREDIT");
        sources(List.of(otherCredit, income));
        service.refreshDuplicateWarnings(user, batchId);
        assertTrue(credit.getDuplicateWarning());
        assertNull(credit.getEntryType());
        assertEquals(ImportSourceDirection.CREDIT, credit.getSourceDirection());
        assertEquals("[{\"code\":\"REVIEW_REQUIRED\"}]", credit.getValidationErrors());
        assertFalse(credit.getSelectedForImport());
        verify(candidates, times(2)).findExternalDuplicateSources(eq(user), eq(batchId), eq(account),
                anyList(), anyList(), anyList(), eq(List.of("CREDIT")), anyList(), eq(true), eq(false), eq(20001));
    }

    @Test void explicitCandidateTypeCanMatchLedgerWithoutInventingAnUnknownSourceDirection() {
        ImportCandidate target = row(2, "Shop #12");
        ReflectionTestUtils.setField(target, "sourceDirection", null); rows(target);
        var unknownDirection = other(UUID.randomUUID(), UUID.randomUUID(), "Shop #12");
        when(unknownDirection.getSourceDirection()).thenReturn(null);
        sources(List.of(unknownDirection));
        service.refreshDuplicateWarnings(user, batchId);
        assertFalse(target.getDuplicateWarning(), "Two unknown directions are not equal candidate evidence");
        sources(List.of(unknownDirection, ledger(UUID.randomUUID(), "Shop #12")));
        service.refreshDuplicateWarnings(user, batchId);
        assertTrue(target.getDuplicateWarning());
        assertNull(target.getSourceDirection());
        assertEquals(EntryType.EXPENSE, target.getEntryType());
    }

    @Test void externalMatchesRequireExactNormalizedFactsAndPreservePunctuationAndNumbers() {
        ImportCandidate target = row(2, "  MILK\u00a0STORE #12  "); rows(target);
        var wrongDate = ledger(UUID.randomUUID(), "milk store #12");
        when(wrongDate.getTransactionDate()).thenReturn(DATE.plusDays(1));
        var wrongCurrency = ledger(UUID.randomUUID(), "milk store #12"); when(wrongCurrency.getCurrencyCode()).thenReturn("USD");
        var wrongType = ledger(UUID.randomUUID(), "milk store #12"); when(wrongType.getEntryType()).thenReturn("INCOME");
        var wrongAmount = ledger(UUID.randomUUID(), "milk store #12"); when(wrongAmount.getAmount()).thenReturn(new BigDecimal("30.01"));
        var wrongDirection = other(UUID.randomUUID(), UUID.randomUUID(), "milk store #12"); when(wrongDirection.getSourceDirection()).thenReturn("CREDIT");
        sources(List.of(wrongDate, wrongCurrency, wrongType, wrongAmount, wrongDirection,
                ledger(UUID.randomUUID(), "milk store #13"), ledger(UUID.randomUUID(), "milk store 12")));
        service.refreshDuplicateWarnings(user, batchId);
        assertFalse(target.getDuplicateWarning());
        var exact = ledger(UUID.randomUUID(), "milk\tstore #12"); when(exact.getAmount()).thenReturn(new BigDecimal("30.0"));
        sources(List.of(exact)); service.refreshDuplicateWarnings(user, batchId);
        assertTrue(target.getDuplicateWarning());
        assertEquals("  MILK\u00a0STORE #12  ", target.getDescription());
    }

    @Test void invalidExternalMoneyAndMissingFactsAreSkippedWithoutInventingValues() {
        ImportCandidate target = row(2, "Shop #12"); rows(target);
        var invalid = new ArrayList<ExternalDuplicateSource>();
        for (BigDecimal amount : List.of(BigDecimal.ZERO, new BigDecimal("-1"), new BigDecimal("30.001"),
                new BigDecimal("100000000000000000.00"))) {
            var source = ledger(UUID.randomUUID(), "Shop #12"); when(source.getAmount()).thenReturn(amount); invalid.add(source);
        }
        var currency = ledger(UUID.randomUUID(), "Shop #12"); when(currency.getCurrencyCode()).thenReturn("ZZZ"); invalid.add(currency);
        var date = ledger(UUID.randomUUID(), "Shop #12"); when(date.getTransactionDate()).thenReturn(null); invalid.add(date);
        var description = ledger(UUID.randomUUID(), null); invalid.add(description);
        var direction = other(UUID.randomUUID(), UUID.randomUUID(), "Shop #12"); when(direction.getSourceDirection()).thenReturn(null); invalid.add(direction);
        sources(invalid); service.refreshDuplicateWarnings(user, batchId);
        assertFalse(target.getDuplicateWarning());
        verifyNoInteractions(entityManager);
    }

    @Test void sourceLimitFailurePreservesAllOldMetadataBeforeAnyWrite() {
        ImportCandidate target = row(2, "Shop #12"); rows(target);
        String old = "a".repeat(64); target.refreshDuplicateWarning(old); acknowledge(target);
        sources(Collections.nCopies(20001, ledger(UUID.randomUUID(), "Shop #12")));
        assertThrows(InvalidImportRequestException.class, () -> service.refreshDuplicateWarnings(user, batchId));
        assertTrue(target.getDuplicateWarning()); assertTrue(target.getDuplicateAcknowledged());
        assertEquals(old, target.getDuplicateMatchSignature());
        verifyNoInteractions(entityManager);
    }

    @Test void externalQueryFailureCannotClearPreviouslySavedEvidence() {
        ImportCandidate target = row(2, "Shop #12"); rows(target);
        String old = "b".repeat(64); target.refreshDuplicateWarning(old); acknowledge(target);
        when(candidates.findExternalDuplicateSources(any(), any(), any(), anyList(), anyList(), anyList(),
                anyList(), anyList(), anyBoolean(), anyBoolean(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("Synthetic database unavailable"));
        assertThrows(DataAccessResourceFailureException.class, () -> service.refreshDuplicateWarnings(user, batchId));
        assertTrue(target.getDuplicateWarning()); assertTrue(target.getDuplicateAcknowledged());
        assertEquals(old, target.getDuplicateMatchSignature());
        verifyNoInteractions(entityManager);
    }

    @Test void oneExternalQueryUsesDeduplicatedDatesAmountsAndTrustedAccountFromLockedBatch() {
        var targets = new ArrayList<ImportCandidate>();
        for (int row = 2; row <= 40; row++) targets.add(row(row, "Shop #12"));
        ReflectionTestUtils.setField(targets.getLast(), "transactionDate", DATE.plusDays(1));
        when(candidates.findForDuplicateScan(batchId, user, SCAN)).thenReturn(targets);
        service.refreshDuplicateWarnings(user, batchId);
        var order = inOrder(batches, candidates);
        order.verify(batches).findOwnedForDuplicateScan(batchId, user);
        order.verify(candidates).findForDuplicateScan(batchId, user, SCAN);
        order.verify(candidates).findExternalDuplicateSources(eq(user), eq(batchId), eq(account),
                argThat(dates -> dates.size() == 2 && dates.containsAll(List.of(DATE, DATE.plusDays(1)))),
                eq(List.of("CAD")), eq(List.of(new BigDecimal("30.00"))),
                eq(List.of("DEBIT")), eq(List.of("EXPENSE")), eq(false), eq(true), eq(20001));
        order.verifyNoMoreInteractions();
        assertFalse(targets.getLast().getDuplicateWarning());
        assertTrue(targets.getFirst().getDuplicateWarning());
    }

    @Test void noComparableFactsSkipExternalQueryAndClearOnlyWarningMetadata() {
        ImportCandidate invalid = row(2, "Shop #12");
        ReflectionTestUtils.setField(invalid, "transactionDate", null);
        ReflectionTestUtils.setField(invalid, "validationErrors", "[{\"code\":\"INVALID_DATE\"}]");
        invalid.refreshDuplicateWarning("c".repeat(64)); acknowledge(invalid); rows(invalid);
        service.refreshDuplicateWarnings(user, batchId);
        assertFalse(invalid.getDuplicateWarning()); assertFalse(invalid.getDuplicateAcknowledged());
        assertNull(invalid.getDuplicateMatchSignature()); assertNull(invalid.getTransactionDate());
        assertEquals("[{\"code\":\"INVALID_DATE\"}]", invalid.getValidationErrors());
        verify(candidates, never()).findExternalDuplicateSources(any(), any(), any(), anyList(), anyList(),
                anyList(), anyList(), anyList(), anyBoolean(), anyBoolean(), anyInt());
        verify(entityManager).lock(batch, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }

    private void rows(ImportCandidate... rows) {
        when(candidates.findForDuplicateScan(batchId, user, SCAN)).thenReturn(List.of(rows));
    }

    private void sources(List<ExternalDuplicateSource> sources) {
        when(candidates.findExternalDuplicateSources(any(), any(), any(), anyList(), anyList(), anyList(),
                anyList(), anyList(), anyBoolean(), anyBoolean(), anyInt())).thenReturn(sources);
    }

    private ImportCandidate row(int rowNumber, String description) {
        return new ImportCandidate(UUID.randomUUID(), batchId, user, rowNumber, EntryType.EXPENSE,
                new BigDecimal("30.00"), "CAD", DATE, null, description, ImportSourceDirection.DEBIT, "[]");
    }

    private static ExternalDuplicateSource ledger(UUID id, String description) {
        return source("LEDGER", id, null, null, description);
    }

    private static ExternalDuplicateSource other(UUID id, UUID batchId, String description) {
        return source("OTHER_REVIEWING_BATCH", id, batchId, "DEBIT", description);
    }

    private static ExternalDuplicateSource source(String kind, UUID id, UUID batchId, String direction, String description) {
        ExternalDuplicateSource source = mock(ExternalDuplicateSource.class);
        when(source.getSourceKind()).thenReturn(kind);
        when(source.getSourceId()).thenReturn(id);
        when(source.getBatchId()).thenReturn(batchId);
        when(source.getTransactionDate()).thenReturn(DATE);
        when(source.getCurrencyCode()).thenReturn("CAD");
        when(source.getSourceDirection()).thenReturn(direction);
        when(source.getEntryType()).thenReturn("EXPENSE");
        when(source.getAmount()).thenReturn(new BigDecimal("30.00"));
        when(source.getDescription()).thenReturn(description);
        return source;
    }

    private static void acknowledge(ImportCandidate target) {
        assertTrue(target.getDuplicateWarning());
        ReflectionTestUtils.setField(target, "duplicateAcknowledged", true);
    }
}
