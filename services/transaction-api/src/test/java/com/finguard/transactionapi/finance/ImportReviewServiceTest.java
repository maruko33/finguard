package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.*;
import com.finguard.transactionapi.finance.dto.*;
import com.finguard.transactionapi.finance.exception.*;
import com.finguard.transactionapi.finance.importing.ImportSourceDirection;
import com.finguard.transactionapi.finance.repository.*;
import com.finguard.transactionapi.finance.service.ImportReviewService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportReviewServiceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 1);
    private static final String SIGNATURE = "a".repeat(64);
    private final UUID user = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();
    private final ImportBatchRepository batches = mock(ImportBatchRepository.class);
    private final ImportCandidateRepository candidates = mock(ImportCandidateRepository.class);
    private final FinancialAccountRepository accounts = mock(FinancialAccountRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final ImportBatch batch = mock(ImportBatch.class);
    private final FinancialAccount account = mock(FinancialAccount.class);
    private final AtomicLong managedVersion = new AtomicLong(7);
    private final ImportReviewService service = new ImportReviewService(batches, candidates, accounts, categories,
            JsonMapper.builder().build());

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        when(batch.getId()).thenReturn(batchId);
        when(batch.getUserId()).thenReturn(user);
        when(batch.getAccountId()).thenReturn(accountId);
        when(batch.getStatus()).thenReturn(ImportStatus.REVIEWING);
        when(batch.getVersion()).thenAnswer(ignored -> managedVersion.get());
        when(batches.findOwnedForDuplicateScan(batchId, user)).thenReturn(Optional.of(batch));
        when(account.getId()).thenReturn(accountId);
        when(account.getUserId()).thenReturn(user);
        when(account.getCurrencyCode()).thenReturn("CAD");
        when(account.getActive()).thenReturn(true);
        when(accounts.findByIdAndUserId(accountId, user)).thenReturn(Optional.of(account));
        // Model the version assigned by Hibernate; the service must read it rather than assign it itself.
        doAnswer(ignored -> { managedVersion.incrementAndGet(); return null; })
                .when(entityManager).lock(batch, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }

    @Test void invalidRequestShapesAreRejectedBeforeRepositoryAccess() {
        ImportCandidate row = row(2);
        var valid = decision(row, false, null, false, null);
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(null, batchId, request(valid)));
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(user, null, request(valid)));
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(user, batchId, null));
        var invalid = List.of(
                new SaveImportReviewRequest(null, List.of(valid)),
                new SaveImportReviewRequest(-1L, List.of(valid)),
                new SaveImportReviewRequest(7L, null),
                new SaveImportReviewRequest(7L, List.of()),
                new SaveImportReviewRequest(7L, Collections.nCopies(101, valid)),
                new SaveImportReviewRequest(7L, List.of(valid, valid)),
                new SaveImportReviewRequest(7L, Arrays.asList((CandidateReviewDecision) null)),
                request(new CandidateReviewDecision(null, false, null, false, null)),
                request(new CandidateReviewDecision(row.getId(), null, null, false, null)),
                request(new CandidateReviewDecision(row.getId(), false, null, null, null)),
                request(decision(row, false, null, false, SIGNATURE)),
                request(decision(row, false, null, true, null)),
                request(decision(row, false, null, true, " ")));
        for (var request : invalid) assertThrows(InvalidImportRequestException.class,
                () -> service.saveReview(user, batchId, request));
        verifyNoInteractions(batches, candidates, accounts, categories, entityManager);
    }

    @Test void absentOrForeignBatchHasSameNotFoundBeforeAnyCandidateAccess() {
        ImportCandidate row = row(2);
        var request = request(decision(row, false, null, false, null));
        var absent = assertThrows(ImportBatchNotFoundException.class,
                () -> service.saveReview(user, UUID.randomUUID(), request));
        var foreign = assertThrows(ImportBatchNotFoundException.class,
                () -> service.saveReview(UUID.randomUUID(), batchId, request));
        assertEquals(absent.getMessage(), foreign.getMessage());
        verifyNoInteractions(candidates, accounts, categories, entityManager);
    }

    @Test void everyNonReviewingStateAndStaleVersionFailBeforeCandidateQueries() {
        ImportCandidate row = row(2);
        var request = request(decision(row, false, null, false, null));
        for (ImportStatus state : ImportStatus.values()) {
            if (state == ImportStatus.REVIEWING) continue;
            when(batch.getStatus()).thenReturn(state);
            assertThrows(ImportBatchStateConflictException.class, () -> service.saveReview(user, batchId, request));
        }
        when(batch.getStatus()).thenReturn(ImportStatus.REVIEWING);
        managedVersion.set(8);
        assertThrows(ImportReviewConflictException.class, () -> service.saveReview(user, batchId, request));
        verifyNoInteractions(candidates, accounts, categories, entityManager);
    }

    @Test void allCandidateIdsMustResolveInTheOwnedBatchBeforeAnyDecisionIsApplied() {
        ImportCandidate valid = row(2), missing = row(3);
        rows(valid);
        var before = facts(valid);
        assertThrows(ImportCandidateNotFoundException.class, () -> service.saveReview(user, batchId,
                request(decision(valid, true, null, false, null), decision(missing, false, null, false, null))));
        assertFalse(valid.getSelectedForImport()); assertEquals(before, facts(valid));
        verify(candidates).findForReview(eq(batchId), eq(user), argThat(ids -> ids.size() == 2
                && ids.containsAll(List.of(valid.getId(), missing.getId()))));
        verifyNoInteractions(entityManager);
    }

    @Test void validSelectionAndManualCategoryChangeOnlyTheWhitelistAndReturnManagedVersion() {
        ImportCandidate row = row(2); rows(row);
        ReflectionTestUtils.setField(row, "amount", new BigDecimal("30.0"));
        ReflectionTestUtils.setField(row, "description", "  Synthetic shop #12  ");
        UUID category = UUID.randomUUID(); allowCategories(category(category, EntryType.EXPENSE, true));
        var before = facts(row);
        var result = service.saveReview(user, batchId, request(decision(row, true, category, false, null)));
        assertEquals(batchId, result.batchId()); assertEquals(ImportStatus.REVIEWING, result.status());
        assertEquals(8, result.version()); assertEquals(1, result.changedCandidateCount());
        assertTrue(row.getSelectedForImport()); assertEquals(category, row.getCategoryId());
        assertFalse(row.getDuplicateAcknowledged()); assertEquals(before, facts(row));
        var order = inOrder(batches, candidates, entityManager);
        order.verify(batches).findOwnedForDuplicateScan(batchId, user);
        order.verify(candidates).findForReview(eq(batchId), eq(user), eq(List.of(row.getId())));
        order.verify(entityManager).lock(batch, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        order.verify(entityManager).flush();
        verify(entityManager, times(1)).lock(batch, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }

    @Test void inactiveAccountDoesNotPreventReviewOfAlreadySavedCandidates() {
        ImportCandidate row = row(2); rows(row);
        when(account.getActive()).thenReturn(false);
        var result = service.saveReview(user, batchId, request(decision(row, true, null, false, null)));
        assertTrue(row.getSelectedForImport()); assertEquals(1, result.changedCandidateCount());
    }

    @Test void excludingAnInvalidRowPreservesItsIssuesAndAllTransactionFacts() {
        ImportCandidate row = row(2); rows(row);
        ReflectionTestUtils.setField(row, "transactionDate", null);
        ReflectionTestUtils.setField(row, "amount", null);
        ReflectionTestUtils.setField(row, "validationErrors", "[{\"code\":\"INVALID_DATE\"},{\"code\":\"INVALID_AMOUNT\"}]");
        // Simulate a stale historical decision; clearing it must not require fixing the transaction.
        ReflectionTestUtils.setField(row, "selectedForImport", true);
        var before = facts(row);
        var result = service.saveReview(user, batchId, request(decision(row, false, null, false, null)));
        assertFalse(row.getSelectedForImport()); assertEquals(before, facts(row));
        assertEquals(1, result.changedCandidateCount()); verifyNoInteractions(accounts, categories);
    }

    @Test void unresolvedIssuesOrMalformedStoredIssueDataCannotBeSelected() {
        for (String issues : Arrays.asList("[{\"code\":\"INVALID_AMOUNT\"}]", "[\"issue\"]", "{}", "null", "broken", null)) {
            ImportCandidate row = row(2); rows(row); ReflectionTestUtils.setField(row, "validationErrors", issues);
            row.refreshDuplicateWarning(SIGNATURE);
            assertThrows(InvalidImportRequestException.class, () -> service.saveReview(user, batchId,
                    request(decision(row, true, null, true, SIGNATURE))));
            assertFalse(row.getSelectedForImport()); assertFalse(row.getDuplicateAcknowledged());
            assertEquals(issues, row.getValidationErrors());
        }
        verifyNoInteractions(entityManager);
    }

    @Test void selectionReusesExactMoneyAndRequiredFieldChecksWithoutRepairingStoredValues() {
        Object[][] invalid = {
                {"entryType", null}, {"transactionDate", null}, {"amount", null}, {"currencyCode", null},
                {"amount", BigDecimal.ZERO}, {"amount", new BigDecimal("-1.00")},
                {"amount", new BigDecimal("30.001")}, {"amount", new BigDecimal("100000000000000000.00")},
                {"currencyCode", "ZZZ"}, {"currencyCode", "cad"}, {"currencyCode", "USD"},
                {"description", null}, {"description", " \t\n"}, {"description", "x".repeat(501)}
        };
        for (Object[] bad : invalid) {
            ImportCandidate row = row(2); ReflectionTestUtils.setField(row, (String) bad[0], bad[1]); rows(row);
            var before = facts(row);
            assertThrows(InvalidImportRequestException.class,
                    () -> service.saveReview(user, batchId, request(decision(row, true, null, false, null))));
            assertFalse(row.getSelectedForImport()); assertEquals(before, facts(row));
        }
        verifyNoInteractions(entityManager);
    }

    @Test void unknownCreditCannotAcquireATypeThroughCategoryOrAcknowledgement() {
        ImportCandidate credit = row(2); rows(credit);
        ReflectionTestUtils.setField(credit, "entryType", null);
        ReflectionTestUtils.setField(credit, "sourceDirection", ImportSourceDirection.CREDIT);
        ReflectionTestUtils.setField(credit, "validationErrors", "[{\"code\":\"REVIEW_REQUIRED\"}]");
        credit.refreshDuplicateWarning(SIGNATURE);
        UUID income = UUID.randomUUID(); allowCategories(category(income, EntryType.INCOME, true));
        var before = facts(credit);
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(user, batchId,
                request(decision(credit, true, income, true, SIGNATURE))));
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(user, batchId,
                request(decision(credit, false, income, true, SIGNATURE))));
        assertNull(credit.getEntryType()); assertNull(credit.getCategoryId()); assertFalse(credit.getSelectedForImport());
        assertFalse(credit.getDuplicateAcknowledged()); assertEquals(before, facts(credit));
        // Acknowledging the saved warning while leaving the Credit unselected is a valid human decision.
        var result = service.saveReview(user, batchId, request(decision(credit, false, null, true, SIGNATURE)));
        assertEquals(1, result.changedCandidateCount()); assertTrue(credit.getDuplicateAcknowledged());
        assertFalse(credit.getSelectedForImport()); assertEquals(before, facts(credit));
    }

    @Test void changedCategoryMustBeOwnedActiveAndMatchTheUnchangedEntryType() {
        ImportCandidate row = row(2); rows(row); UUID category = UUID.randomUUID();
        allowCategories();
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(user, batchId,
                request(decision(row, false, category, false, null))));
        for (Category invalid : List.of(category(category, EntryType.EXPENSE, false),
                category(category, EntryType.INCOME, true))) {
            allowCategories(invalid);
            assertThrows(InvalidImportRequestException.class, () -> service.saveReview(user, batchId,
                    request(decision(row, false, category, false, null))));
            assertNull(row.getCategoryId());
        }
        verify(categories, times(3)).findByUserIdAndIdIn(eq(user), eq(List.of(category)));
        verifyNoInteractions(entityManager);
    }

    @Test void unchangedInactiveCategoryCanBeRetainedWhileDeselectingButCannotBeSelected() {
        ImportCandidate row = row(2); rows(row); UUID historical = UUID.randomUUID();
        ReflectionTestUtils.setField(row, "categoryId", historical);
        ReflectionTestUtils.setField(row, "selectedForImport", true);
        allowCategories(category(historical, EntryType.EXPENSE, false));
        var result = service.saveReview(user, batchId, request(decision(row, false, historical, false, null)));
        assertEquals(historical, row.getCategoryId()); assertFalse(row.getSelectedForImport());
        assertEquals(1, result.changedCandidateCount()); verifyNoInteractions(categories, accounts);
        clearInvocations(entityManager);
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(user, batchId,
                new SaveImportReviewRequest(8L, List.of(decision(row, true, historical, false, null)))));
        assertFalse(row.getSelectedForImport()); assertEquals(historical, row.getCategoryId());
        verifyNoInteractions(entityManager);
    }

    @Test void nullCategoryExplicitlyClearsTheSavedCategoryAndRemainsOptionalForSelection() {
        ImportCandidate row = row(2); rows(row);
        ReflectionTestUtils.setField(row, "categoryId", UUID.randomUUID());
        var result = service.saveReview(user, batchId, request(decision(row, true, null, false, null)));
        assertNull(row.getCategoryId()); assertTrue(row.getSelectedForImport());
        assertEquals(1, result.changedCandidateCount()); verifyNoInteractions(categories);
    }

    @Test void acknowledgementsBindToAnExistingSavedWarningAndExactSignature() {
        ImportCandidate row = row(2); rows(row);
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(user, batchId,
                request(decision(row, false, null, true, SIGNATURE))));
        ReflectionTestUtils.setField(row, "duplicateWarning", true);
        assertThrows(ImportReviewConflictException.class, () -> service.saveReview(user, batchId,
                request(decision(row, false, null, true, SIGNATURE))));
        row.refreshDuplicateWarning(SIGNATURE);
        for (String wrong : List.of("b".repeat(64), "opaque-but-not-current")) {
            assertThrows(ImportReviewConflictException.class, () -> service.saveReview(user, batchId,
                    request(decision(row, false, null, true, wrong))));
        }
        assertFalse(row.getDuplicateAcknowledged()); verifyNoInteractions(entityManager);
        var before = facts(row);
        var result = service.saveReview(user, batchId, request(decision(row, true, null, true, SIGNATURE)));
        assertTrue(row.getSelectedForImport()); assertTrue(row.getDuplicateAcknowledged());
        assertTrue(row.getDuplicateWarning()); assertEquals(SIGNATURE, row.getDuplicateMatchSignature());
        assertEquals(before, facts(row)); assertEquals(1, result.changedCandidateCount());
    }

    @Test void warningRequiresAcknowledgementForSelectionAndRevocationRequiresDeselection() {
        ImportCandidate row = row(2); rows(row); row.refreshDuplicateWarning(SIGNATURE);
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(user, batchId,
                request(decision(row, true, null, false, null))));
        ReflectionTestUtils.setField(row, "selectedForImport", true);
        ReflectionTestUtils.setField(row, "duplicateAcknowledged", true);
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(user, batchId,
                request(decision(row, true, null, false, null))));
        assertTrue(row.getSelectedForImport()); assertTrue(row.getDuplicateAcknowledged());
        verifyNoInteractions(entityManager);
        var result = service.saveReview(user, batchId, request(decision(row, false, null, false, null)));
        assertFalse(row.getSelectedForImport()); assertFalse(row.getDuplicateAcknowledged());
        assertTrue(row.getDuplicateWarning()); assertEquals(SIGNATURE, row.getDuplicateMatchSignature());
        assertEquals(1, result.changedCandidateCount());
    }

    @Test void allRowsAreValidatedBeforeApplyingEvenTheFirstOtherwiseValidDecision() {
        ImportCandidate valid = row(2), invalid = row(3); rows(valid, invalid);
        ReflectionTestUtils.setField(invalid, "validationErrors", "[{\"code\":\"INVALID_DATE\"}]");
        UUID category = UUID.randomUUID(); allowCategories(category(category, EntryType.EXPENSE, true));
        var beforeValid = facts(valid); var beforeInvalid = facts(invalid);
        assertThrows(InvalidImportRequestException.class, () -> service.saveReview(user, batchId,
                request(decision(valid, true, category, false, null), decision(invalid, true, null, false, null))));
        assertFalse(valid.getSelectedForImport()); assertNull(valid.getCategoryId());
        assertFalse(invalid.getSelectedForImport()); assertEquals(beforeValid, facts(valid));
        assertEquals(beforeInvalid, facts(invalid)); verifyNoInteractions(entityManager);
    }

    @Test void unchangedCurrentVersionIsANoopButAnOldExpectedVersionStillConflicts() {
        ImportCandidate row = row(2); rows(row);
        var decision = decision(row, false, null, false, null);
        var result = service.saveReview(user, batchId, request(decision));
        assertEquals(0, result.changedCandidateCount()); assertEquals(7, result.version());
        verifyNoInteractions(entityManager);
        clearInvocations(candidates, accounts, categories);
        assertThrows(ImportReviewConflictException.class, () -> service.saveReview(user, batchId,
                new SaveImportReviewRequest(6L, List.of(decision))));
        verifyNoInteractions(candidates, accounts, categories, entityManager);
    }

    @Test void changeCountIsPerCandidateAndHundredDecisionsUseOneCategoryAndAccountLookup() {
        UUID category = UUID.randomUUID(); allowCategories(category(category, EntryType.EXPENSE, true));
        var requested = new ArrayList<CandidateReviewDecision>(); var rows = new ArrayList<ImportCandidate>();
        for (int number = 2; number <= 101; number++) {
            ImportCandidate row = row(number); rows.add(row);
            requested.add(decision(row, true, category, false, null));
        }
        rows(rows.toArray(ImportCandidate[]::new));
        var result = service.saveReview(user, batchId, new SaveImportReviewRequest(7L, requested));
        assertEquals(100, result.changedCandidateCount()); assertEquals(8, result.version());
        assertTrue(rows.stream().allMatch(r -> r.getSelectedForImport() && category.equals(r.getCategoryId())));
        verify(candidates, times(1)).findForReview(eq(batchId), eq(user), argThat(ids -> ids.size() == 100));
        verify(accounts, times(1)).findByIdAndUserId(accountId, user);
        verify(categories, times(1)).findByUserIdAndIdIn(user, List.of(category));
        verify(entityManager, times(1)).lock(batch, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        verify(entityManager, times(1)).flush();
    }

    @Test void changedCountExcludesListedNoopsAndNeverLoadsOrChangesUnlistedCandidates() {
        ImportCandidate unchanged = row(2), changed = row(3), unlisted = row(4); rows(unchanged, changed);
        unlisted.refreshDuplicateWarning(SIGNATURE);
        ReflectionTestUtils.setField(unlisted, "duplicateAcknowledged", true);
        ReflectionTestUtils.setField(unlisted, "selectedForImport", true);
        var unlistedBefore = facts(unlisted);
        var result = service.saveReview(user, batchId, request(decision(unchanged, false, null, false, null),
                decision(changed, true, null, false, null)));
        assertEquals(1, result.changedCandidateCount()); assertFalse(unchanged.getSelectedForImport());
        assertTrue(changed.getSelectedForImport()); assertEquals(unlistedBefore, facts(unlisted));
        assertTrue(unlisted.getSelectedForImport()); assertTrue(unlisted.getDuplicateAcknowledged());
        verify(candidates).findForReview(eq(batchId), eq(user), argThat(ids -> !ids.contains(unlisted.getId())));
        verify(candidates, never()).findForDuplicateScan(any(), any(), any());
    }

    @Test void alreadyAcknowledgedSelectedDecisionIsAnExactNoopWithTheCurrentToken() {
        ImportCandidate row = row(2); rows(row); row.refreshDuplicateWarning(SIGNATURE);
        ReflectionTestUtils.setField(row, "selectedForImport", true);
        ReflectionTestUtils.setField(row, "duplicateAcknowledged", true);
        var result = service.saveReview(user, batchId, request(decision(row, true, null, true, SIGNATURE)));
        assertEquals(0, result.changedCandidateCount()); assertEquals(7, result.version());
        assertTrue(row.getDuplicateWarning()); assertTrue(row.getDuplicateAcknowledged());
        verifyNoInteractions(entityManager);
    }

    @Test void databaseFailureIsNotConvertedIntoASuccessfulResult() {
        ImportCandidate row = row(2); rows(row);
        var failure = new DataAccessResourceFailureException("Synthetic flush failure");
        doThrow(failure).when(entityManager).flush();
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> service.saveReview(user, batchId, request(decision(row, true, null, false, null)))));
        verify(entityManager).lock(batch, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }

    @Test void actualOptimisticVersionFailureIsReportedAsAConflict() {
        ImportCandidate row = row(2); rows(row);
        doThrow(new jakarta.persistence.OptimisticLockException("Synthetic stale managed Batch"))
                .when(entityManager).lock(batch, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        assertThrows(ImportReviewConflictException.class,
                () -> service.saveReview(user, batchId, request(decision(row, true, null, false, null))));
    }

    @Test void staleManagedCandidateCannotOverrideFreshDatabaseReviewMetadata() {
        for (int field = 0; field < 5; field++) {
            ImportCandidate row = row(2);
            var loaded = projection(row);
            switch (field) {
                case 0 -> when(loaded.getCategoryId()).thenReturn(UUID.randomUUID());
                case 1 -> when(loaded.getSelectedForImport()).thenReturn(true);
                case 2 -> when(loaded.getDuplicateAcknowledged()).thenReturn(true);
                case 3 -> when(loaded.getDuplicateWarning()).thenReturn(true);
                case 4 -> when(loaded.getDuplicateMatchSignature()).thenReturn(SIGNATURE);
                default -> throw new AssertionError();
            }
            when(candidates.findForReview(eq(batchId), eq(user), anyList())).thenReturn(List.of(loaded));
            var before = facts(row);
            assertThrows(ImportReviewConflictException.class,
                    () -> service.saveReview(user, batchId, request(decision(row, true, null, false, null))));
            assertEquals(before, facts(row)); assertFalse(row.getSelectedForImport());
            assertNull(row.getCategoryId()); assertFalse(row.getDuplicateAcknowledged());
        }
        verifyNoInteractions(entityManager);
    }

    private void rows(ImportCandidate... rows) {
        var snapshots = Arrays.stream(rows).map(this::projection).toList();
        when(candidates.findForReview(eq(batchId), eq(user), anyList())).thenReturn(snapshots);
    }
    private ImportCandidateRepository.ReviewCandidate projection(ImportCandidate row) {
        var loaded = mock(ImportCandidateRepository.ReviewCandidate.class);
        when(loaded.getCandidate()).thenReturn(row);
        when(loaded.getCategoryId()).thenAnswer(ignored -> row.getCategoryId());
        when(loaded.getSelectedForImport()).thenAnswer(ignored -> row.getSelectedForImport());
        when(loaded.getDuplicateAcknowledged()).thenAnswer(ignored -> row.getDuplicateAcknowledged());
        when(loaded.getDuplicateWarning()).thenAnswer(ignored -> row.getDuplicateWarning());
        when(loaded.getDuplicateMatchSignature()).thenAnswer(ignored -> row.getDuplicateMatchSignature());
        return loaded;
    }
    private void allowCategories(Category... values) {
        when(categories.findByUserIdAndIdIn(eq(user), anyList())).thenReturn(List.of(values));
    }
    private Category category(UUID id, EntryType type, boolean active) {
        Category category = mock(Category.class);
        when(category.getId()).thenReturn(id); when(category.getUserId()).thenReturn(user);
        when(category.getEntryType()).thenReturn(type); when(category.getActive()).thenReturn(active);
        return category;
    }
    private ImportCandidate row(int rowNumber) {
        return new ImportCandidate(UUID.randomUUID(), batchId, user, rowNumber, EntryType.EXPENSE,
                new BigDecimal("30.00"), "CAD", DATE, Instant.parse("2026-11-01T12:00:00Z"),
                "Synthetic shop #12", ImportSourceDirection.DEBIT, "[]");
    }
    private static CandidateReviewDecision decision(ImportCandidate row, boolean selected, UUID category,
                                                    boolean acknowledged, String signature) {
        return new CandidateReviewDecision(row.getId(), selected, category, acknowledged, signature);
    }
    private static SaveImportReviewRequest request(CandidateReviewDecision... decisions) {
        return new SaveImportReviewRequest(7L, List.of(decisions));
    }
    private static List<Object> facts(ImportCandidate row) {
        return Arrays.asList(row.getId(), row.getBatchId(), row.getUserId(), row.getRowNumber(), row.getEntryType(),
                row.getAmount(), row.getCurrencyCode(), row.getTransactionDate(), row.getOccurredAt(),
                row.getDescription(), row.getSourceDirection(), row.getBankTransactionId(), row.getValidationErrors(),
                row.getDuplicateWarning(), row.getDuplicateMatchSignature());
    }
}
