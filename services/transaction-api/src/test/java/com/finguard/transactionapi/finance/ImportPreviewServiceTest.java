package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.EntryType;
import com.finguard.transactionapi.finance.domain.ImportBatch;
import com.finguard.transactionapi.finance.domain.ImportCandidate;
import com.finguard.transactionapi.finance.domain.ImportStatus;
import com.finguard.transactionapi.finance.dto.CandidateSummary;
import com.finguard.transactionapi.finance.exception.ImportBatchNotFoundException;
import com.finguard.transactionapi.finance.exception.InvalidImportBatchQueryException;
import com.finguard.transactionapi.finance.repository.ImportBatchRepository;
import com.finguard.transactionapi.finance.repository.ImportCandidateRepository;
import com.finguard.transactionapi.finance.service.ImportBatchQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportPreviewServiceTest {
    private static final Sort ORDER = Sort.by(Sort.Order.desc("transactionDate").nullsLast(),
            Sort.Order.asc("rowNumber"));
    private final ImportBatchRepository batches = mock(ImportBatchRepository.class);
    private final ImportCandidateRepository candidates = mock(ImportCandidateRepository.class);
    private final ImportBatchQueryService service = new ImportBatchQueryService(batches, candidates);
    private final UUID owner = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @Test
    void rejectsMissingArgumentsAndUnpagedBeforeRepositoryAccess() {
        assertThrows(InvalidImportBatchQueryException.class,
                () -> service.getImportPreview(null, batchId, PageRequest.of(0, 15)));
        assertThrows(InvalidImportBatchQueryException.class,
                () -> service.getImportPreview(owner, null, PageRequest.of(0, 15)));
        assertThrows(InvalidImportBatchQueryException.class,
                () -> service.getImportPreview(owner, batchId, null));
        assertThrows(InvalidImportBatchQueryException.class,
                () -> service.getImportPreview(owner, batchId, Pageable.unpaged()));
        verifyNoInteractions(batches, candidates);
    }

    @Test
    void rejectsInvalidPageBoundsAndOverflowBeforeRepositoryAccess() {
        for (int[] bounds : List.of(new int[] {-1, 15}, new int[] {0, 0},
                new int[] {0, -1}, new int[] {0, 101}, new int[] {Integer.MAX_VALUE, 2})) {
            Pageable page = suppliedPage(bounds[0], bounds[1], Sort.unsorted());
            assertThrows(InvalidImportBatchQueryException.class,
                    () -> service.getImportPreview(owner, batchId, page));
        }
        verifyNoInteractions(batches, candidates);
    }

    @Test
    void rejectsNullOrUnsupportedSortIncludingBatchListOrdering() {
        assertThrows(InvalidImportBatchQueryException.class,
                () -> service.getImportPreview(owner, batchId, suppliedPage(0, 15, null)));
        for (Sort sort : List.of(Sort.by("transactionDate"),
                Sort.by(Sort.Order.desc("transactionDate"), Sort.Order.asc("rowNumber")),
                Sort.by(Sort.Order.desc("transactionDate").nullsFirst(), Sort.Order.asc("rowNumber")),
                Sort.by(Sort.Order.asc("rowNumber"), Sort.Order.desc("transactionDate").nullsLast()),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")),
                Sort.by(Sort.Order.desc("transactionDate").nullsLast().ignoreCase(), Sort.Order.asc("rowNumber")),
                Sort.by(Sort.Order.desc("transactionDate").nullsLast(), Sort.Order.desc("rowNumber")))) {
            assertThrows(InvalidImportBatchQueryException.class,
                    () -> service.getImportPreview(owner, batchId, PageRequest.of(0, 15, sort)));
        }
        verifyNoInteractions(batches, candidates);
    }

    @Test
    void acceptsUnsortedDefaultAndExactOrderWithInclusiveSizeBounds() {
        ImportBatch batch = batch(ImportStatus.REVIEWING);
        when(batches.findByIdAndUserId(batchId, owner)).thenReturn(Optional.of(batch));
        for (int size : List.of(1, 15, 100)) {
            PageRequest repositoryPage = PageRequest.of(0, size);
            when(candidates.findPreviewByBatchIdAndUserId(batchId, owner, repositoryPage))
                    .thenReturn(Page.empty(repositoryPage));
            for (Sort requestedSort : List.of(Sort.unsorted(), ORDER)) {
                var response = service.getImportPreview(owner, batchId, PageRequest.of(0, size, requestedSort));
                assertEquals(PageRequest.of(0, size, ORDER), response.candidates().getPageable());
                assertEquals(0, response.candidates().getTotalElements());
            }
            verify(candidates, times(2)).findPreviewByBatchIdAndUserId(batchId, owner, repositoryPage);
        }
        verify(batches, times(6)).findByIdAndUserId(batchId, owner);
        verifyNoMoreInteractions(batches, candidates);
    }

    @Test
    void missingAndForeignBatchesHaveIdenticalNotFoundWithoutCandidateAccess() {
        UUID foreignBatch = UUID.randomUUID();
        when(batches.findByIdAndUserId(batchId, owner)).thenReturn(Optional.empty());
        when(batches.findByIdAndUserId(foreignBatch, owner)).thenReturn(Optional.empty());
        var missing = assertThrows(ImportBatchNotFoundException.class,
                () -> service.getImportPreview(owner, batchId, PageRequest.of(0, 15)));
        var foreign = assertThrows(ImportBatchNotFoundException.class,
                () -> service.getImportPreview(owner, foreignBatch, PageRequest.of(0, 15)));
        assertEquals("Import batch not found", missing.getMessage());
        assertEquals(missing.getMessage(), foreign.getMessage());
        verify(batches).findByIdAndUserId(batchId, owner);
        verify(batches).findByIdAndUserId(foreignBatch, owner);
        verifyNoMoreInteractions(batches);
        verifyNoInteractions(candidates);
    }

    @Test
    void checksOwnershipFirstAndPreservesEveryReviewFieldIncludingNullsAndIssues() {
        ImportBatch batch = batch(ImportStatus.REVIEWING);
        LocalDate date = LocalDate.of(2026, 11, 1);
        ImportCandidate normal = candidate(1, EntryType.EXPENSE, new BigDecimal("12.34"), date,
                "Synthetic normal", "[]");
        UUID categoryId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-11-01T10:20:30Z");
        when(normal.getCategoryId()).thenReturn(categoryId);
        when(normal.getOccurredAt()).thenReturn(occurredAt);
        when(normal.getSelectedForImport()).thenReturn(true);
        when(normal.getDuplicateWarning()).thenReturn(true);
        when(normal.getDuplicateAcknowledged()).thenReturn(true);
        when(normal.getDuplicateMatchSignature()).thenReturn("a".repeat(64));
        ImportCandidate invalidDate = candidate(2, EntryType.EXPENSE, new BigDecimal("3.21"), null,
                "Synthetic invalid date", "[{\"code\":\"INVALID_DATE\"}]");
        ImportCandidate invalidAmount = candidate(3, EntryType.EXPENSE, null, date,
                "Synthetic invalid amount", "[{\"code\":\"INVALID_AMOUNT\"}]");
        ImportCandidate credit = candidate(4, null, new BigDecimal("5.00"), date,
                "Synthetic credit", "[{\"code\":\"REVIEW_REQUIRED\"}]");
        List<ImportCandidate> stored = List.of(normal, invalidAmount, credit, invalidDate);
        PageRequest repositoryPage = PageRequest.of(0, 4);
        when(batches.findByIdAndUserId(batchId, owner)).thenReturn(Optional.of(batch));
        when(candidates.findPreviewByBatchIdAndUserId(batchId, owner, repositoryPage))
                .thenReturn(new PageImpl<>(stored, repositoryPage, 17));

        var result = service.getImportPreview(owner, batchId, repositoryPage);

        assertEquals(batchId, result.id());
        assertEquals(accountId, result.accountId());
        assertEquals("synthetic-preview.csv", result.fileName());
        assertEquals(ImportStatus.REVIEWING, result.status());
        assertEquals(7, result.version());
        assertEquals(17, result.candidates().getTotalElements());
        assertEquals(PageRequest.of(0, 4, ORDER), result.candidates().getPageable());
        assertEquals(List.of(1, 3, 4, 2), result.candidates().stream().map(CandidateSummary::rowNumber).toList());
        for (int index = 0; index < stored.size(); index++) {
            assertStoredFields(stored.get(index), result.candidates().getContent().get(index));
        }
        assertEquals("a".repeat(64), result.candidates().getContent().getFirst().duplicateMatchSignature());
        assertNull(result.candidates().getContent().get(1).duplicateMatchSignature());
        CandidateSummary creditResult = result.candidates().getContent().get(2);
        assertNull(creditResult.entryType());
        assertFalse(creditResult.selectedForImport());
        assertNull(result.candidates().getContent().get(3).transactionDate());
        assertNull(result.candidates().getContent().get(1).amount());
        var calls = inOrder(batches, candidates);
        calls.verify(batches).findByIdAndUserId(batchId, owner);
        calls.verify(candidates).findPreviewByBatchIdAndUserId(batchId, owner, repositoryPage);
        calls.verifyNoMoreInteractions();
        for (ImportCandidate row : stored) {
            verify(row, never()).getBankTransactionId();
        }
        verify(batch, never()).getUploadKey();
        verify(batch, never()).getFileSha256();
    }

    @Test
    void emptyAndBeyondLastPagesKeepBatchMetadataAndCorrectTotals() {
        ImportBatch batch = batch(ImportStatus.REVIEWING);
        when(batches.findByIdAndUserId(batchId, owner)).thenReturn(Optional.of(batch));
        PageRequest first = PageRequest.of(0, 15);
        PageRequest beyond = PageRequest.of(2, 15);
        when(candidates.findPreviewByBatchIdAndUserId(batchId, owner, first)).thenReturn(Page.empty(first));
        when(candidates.findPreviewByBatchIdAndUserId(batchId, owner, beyond))
                .thenReturn(new PageImpl<>(List.of(), beyond, 17));
        var empty = service.getImportPreview(owner, batchId, first);
        var pastEnd = service.getImportPreview(owner, batchId, beyond);
        assertEquals(batchId, empty.id());
        assertTrue(empty.candidates().isEmpty());
        assertEquals(0, empty.candidates().getTotalElements());
        assertEquals(batchId, pastEnd.id());
        assertTrue(pastEnd.candidates().isEmpty());
        assertEquals(17, pastEnd.candidates().getTotalElements());
        assertEquals(2, pastEnd.candidates().getTotalPages());
        assertEquals(PageRequest.of(2, 15, ORDER), pastEnd.candidates().getPageable());
        verify(batches, times(2)).findByIdAndUserId(batchId, owner);
        verify(candidates).findPreviewByBatchIdAndUserId(batchId, owner, first);
        verify(candidates).findPreviewByBatchIdAndUserId(batchId, owner, beyond);
        verifyNoMoreInteractions(batches, candidates);
    }

    @Test
    void returnsActualBatchStatusWithoutRequiringReviewing() {
        PageRequest page = PageRequest.of(0, 15);
        when(candidates.findPreviewByBatchIdAndUserId(batchId, owner, page)).thenReturn(Page.empty(page));
        for (ImportStatus status : ImportStatus.values()) {
            ImportBatch batch = batch(status);
            when(batches.findByIdAndUserId(batchId, owner)).thenReturn(Optional.of(batch));
            var result = service.getImportPreview(owner, batchId, page);
            assertEquals(status, result.status());
            assertEquals(7, result.version());
        }
        verify(batches, times(ImportStatus.values().length)).findByIdAndUserId(batchId, owner);
        verify(candidates, times(ImportStatus.values().length)).findPreviewByBatchIdAndUserId(batchId, owner, page);
        verifyNoMoreInteractions(batches, candidates);
    }

    @Test
    void batchDatabaseFailurePropagatesWithoutQueryingCandidates() {
        var failure = new DataRetrievalFailureException("Synthetic batch database failure");
        when(batches.findByIdAndUserId(batchId, owner)).thenThrow(failure);
        assertSame(failure, assertThrows(DataRetrievalFailureException.class,
                () -> service.getImportPreview(owner, batchId, PageRequest.of(0, 15))));
        verify(batches).findByIdAndUserId(batchId, owner);
        verifyNoMoreInteractions(batches);
        verifyNoInteractions(candidates);
    }

    @Test
    void candidateDatabaseFailurePropagatesInsteadOfReturningAnEmptyPreview() {
        ImportBatch batch = batch(ImportStatus.REVIEWING);
        PageRequest page = PageRequest.of(0, 15);
        var failure = new DataRetrievalFailureException("Synthetic candidate database failure");
        when(batches.findByIdAndUserId(batchId, owner)).thenReturn(Optional.of(batch));
        when(candidates.findPreviewByBatchIdAndUserId(batchId, owner, page)).thenThrow(failure);
        assertSame(failure, assertThrows(DataRetrievalFailureException.class,
                () -> service.getImportPreview(owner, batchId, page)));
        verify(batches).findByIdAndUserId(batchId, owner);
        verify(candidates).findPreviewByBatchIdAndUserId(batchId, owner, page);
        verifyNoMoreInteractions(batches, candidates);
    }

    private ImportBatch batch(ImportStatus status) {
        ImportBatch batch = mock(ImportBatch.class);
        when(batch.getId()).thenReturn(batchId);
        when(batch.getAccountId()).thenReturn(accountId);
        when(batch.getFileName()).thenReturn("synthetic-preview.csv");
        when(batch.getStatus()).thenReturn(status);
        when(batch.getVersion()).thenReturn(7L);
        return batch;
    }

    private static ImportCandidate candidate(int row, EntryType entryType, BigDecimal amount,
                                             LocalDate date, String description, String errors) {
        ImportCandidate candidate = mock(ImportCandidate.class);
        when(candidate.getId()).thenReturn(UUID.randomUUID());
        when(candidate.getRowNumber()).thenReturn(row);
        when(candidate.getEntryType()).thenReturn(entryType);
        when(candidate.getAmount()).thenReturn(amount);
        when(candidate.getCurrencyCode()).thenReturn("CAD");
        when(candidate.getTransactionDate()).thenReturn(date);
        when(candidate.getDescription()).thenReturn(description);
        when(candidate.getValidationErrors()).thenReturn(errors);
        return candidate;
    }

    private static void assertStoredFields(ImportCandidate stored, CandidateSummary result) {
        assertEquals(stored.getId(), result.candidateId());
        assertEquals(stored.getRowNumber(), result.rowNumber());
        assertEquals(stored.getEntryType(), result.entryType());
        assertEquals(stored.getAmount(), result.amount());
        assertEquals(stored.getCurrencyCode(), result.currencyCode());
        assertEquals(stored.getTransactionDate(), result.transactionDate());
        assertEquals(stored.getOccurredAt(), result.occurredAt());
        assertEquals(stored.getDescription(), result.description());
        assertEquals(stored.getCategoryId(), result.categoryId());
        assertEquals(stored.getSelectedForImport(), result.selectedForImport());
        assertEquals(stored.getValidationErrors(), result.validationErrors());
        assertEquals(stored.getDuplicateWarning(), result.duplicateWarning());
        assertEquals(stored.getDuplicateAcknowledged(), result.duplicateAcknowledged());
    }

    private static Pageable suppliedPage(int page, int size, Sort sort) {
        Pageable pageable = mock(Pageable.class);
        when(pageable.isUnpaged()).thenReturn(false);
        when(pageable.getPageNumber()).thenReturn(page);
        when(pageable.getPageSize()).thenReturn(size);
        when(pageable.getOffset()).thenReturn((long) page * size);
        when(pageable.getSort()).thenReturn(sort);
        return pageable;
    }
}
