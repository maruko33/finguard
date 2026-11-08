package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.ImportBatch;
import com.finguard.transactionapi.finance.domain.ImportStatus;
import com.finguard.transactionapi.finance.dto.ImportBatchSummary;
import com.finguard.transactionapi.finance.exception.InvalidImportBatchQueryException;
import com.finguard.transactionapi.finance.repository.ImportBatchRepository;
import com.finguard.transactionapi.finance.repository.ImportCandidateRepository;
import com.finguard.transactionapi.finance.service.ImportBatchQueryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportBatchQueryServiceTest {
    private static final Sort ORDER = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private final ImportBatchRepository batches = mock(ImportBatchRepository.class);
    private final ImportBatchQueryService service = new ImportBatchQueryService(batches, mock(ImportCandidateRepository.class));
    private final UUID owner = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final Instant createdAt = Instant.parse("2026-11-01T12:00:00Z");

    @Test
    void rejectsMissingIdentityAndPagedRequestBeforeRepositoryAccess() {
        assertThrows(InvalidImportBatchQueryException.class,
                () -> service.listReviewingBatches(null, PageRequest.of(0, 15)));
        assertThrows(InvalidImportBatchQueryException.class,
                () -> service.listReviewingBatches(owner, null));
        assertThrows(InvalidImportBatchQueryException.class,
                () -> service.listReviewingBatches(owner, Pageable.unpaged()));
        verifyNoInteractions(batches);
    }

    @Test
    void rejectsInvalidPageNumbersSizesAndOffsetsBeforeRepositoryAccess() {
        for (int[] bounds : List.of(new int[] {-1, 15}, new int[] {0, 0},
                new int[] {0, -1}, new int[] {0, 101}, new int[] {Integer.MAX_VALUE, 2})) {
            Pageable page = suppliedPage(bounds[0], bounds[1], Sort.unsorted());
            assertThrows(InvalidImportBatchQueryException.class,
                    () -> service.listReviewingBatches(owner, page));
        }
        verifyNoInteractions(batches);
    }

    @Test
    void rejectsNullAndUnsupportedSortsBeforeRepositoryAccess() {
        assertThrows(InvalidImportBatchQueryException.class,
                () -> service.listReviewingBatches(owner, suppliedPage(0, 15, null)));
        for (Sort sort : List.of(Sort.by("createdAt"), Sort.by("fileName"),
                Sort.by(Sort.Order.desc("createdAt")),
                Sort.by(Sort.Order.desc("id"), Sort.Order.desc("createdAt")),
                Sort.by(Sort.Order.desc("createdAt").ignoreCase(), Sort.Order.desc("id")),
                Sort.by(Sort.Order.desc("createdAt").nullsLast(), Sort.Order.desc("id")))) {
            assertThrows(InvalidImportBatchQueryException.class,
                    () -> service.listReviewingBatches(owner, PageRequest.of(0, 15, sort)));
        }
        verifyNoInteractions(batches);
    }

    @Test
    void defaultCallerPageIsScopedToReviewingOwnerAndUsesCanonicalSort() {
        ImportBatch batch = batch();
        var aggregate = counts(batch.getId(), 4, 3);
        PageRequest canonical = PageRequest.of(0, 15, ORDER);
        when(batches.findByUserIdAndStatus(owner, ImportStatus.REVIEWING, canonical))
                .thenReturn(new PageImpl<>(List.of(batch), canonical, 1));
        when(batches.countCandidatesByBatchIds(owner, List.of(batch.getId())))
                .thenReturn(List.of(aggregate));

        Page<ImportBatchSummary> result = service.listReviewingBatches(owner, PageRequest.of(0, 15));

        assertEquals(0, result.getNumber());
        assertEquals(15, result.getSize());
        assertEquals(ORDER, result.getSort());
        assertEquals(1, result.getTotalElements());
        ImportBatchSummary summary = result.getContent().getFirst();
        assertEquals(batch.getId(), summary.id());
        assertEquals(account, summary.accountId());
        assertEquals("synthetic.csv", summary.fileName());
        assertEquals(ImportStatus.REVIEWING, summary.status());
        assertEquals(7, summary.version());
        assertEquals(createdAt, summary.createdAt());
        assertEquals(4, summary.candidateCount());
        assertEquals(3, summary.attentionRowCount());
        verify(batches).findByUserIdAndStatus(owner, ImportStatus.REVIEWING, canonical);
        verify(batches).countCandidatesByBatchIds(owner, List.of(batch.getId()));
        verifyNoMoreInteractions(batches);
    }

    @Test
    void acceptsExactSupportedSortAndInclusivePageSizeBounds() {
        for (int size : List.of(1, 100)) {
            PageRequest page = PageRequest.of(0, size, ORDER);
            when(batches.findByUserIdAndStatus(owner, ImportStatus.REVIEWING, page))
                    .thenReturn(Page.empty(page));

            Page<ImportBatchSummary> result = service.listReviewingBatches(owner, page);

            assertEquals(page, result.getPageable());
            assertEquals(0, result.getTotalElements());
            verify(batches).findByUserIdAndStatus(owner, ImportStatus.REVIEWING, page);
        }
        verifyNoMoreInteractions(batches);
    }

    @Test
    void oneAggregateUsesOnlyPageIdsAndMapsByIdWhilePreservingOrderAndTotals() {
        ImportBatch first = batch();
        ImportBatch empty = batch();
        ImportBatch last = batch();
        List<UUID> ids = List.of(first.getId(), empty.getId(), last.getId());
        var lastCounts = counts(last.getId(), 10, 2);
        var firstCounts = counts(first.getId(), 4, 3);
        PageRequest canonical = PageRequest.of(1, 3, ORDER);
        when(batches.findByUserIdAndStatus(owner, ImportStatus.REVIEWING, canonical))
                .thenReturn(new PageImpl<>(List.of(first, empty, last), canonical, 17));
        // Database group order need not match the already-paged batch order.
        when(batches.countCandidatesByBatchIds(owner, ids))
                .thenReturn(List.of(lastCounts, firstCounts));

        Page<ImportBatchSummary> result = service.listReviewingBatches(owner, PageRequest.of(1, 3));

        assertEquals(ids, result.getContent().stream().map(ImportBatchSummary::id).toList());
        assertEquals(List.of(4L, 0L, 10L), result.getContent().stream()
                .map(ImportBatchSummary::candidateCount).toList());
        assertEquals(List.of(3L, 0L, 2L), result.getContent().stream()
                .map(ImportBatchSummary::attentionRowCount).toList());
        assertEquals(17, result.getTotalElements());
        assertEquals(6, result.getTotalPages());
        assertEquals(canonical, result.getPageable());
        verify(batches).findByUserIdAndStatus(owner, ImportStatus.REVIEWING, canonical);
        verify(batches).countCandidatesByBatchIds(owner, ids);
        verifyNoMoreInteractions(batches);
    }

    @Test
    void beyondLastPageRetainsTotalAndDoesNotIssueEmptyIdAggregate() {
        PageRequest canonical = PageRequest.of(2, 15, ORDER);
        when(batches.findByUserIdAndStatus(owner, ImportStatus.REVIEWING, canonical))
                .thenReturn(new PageImpl<>(List.of(), canonical, 17));

        Page<ImportBatchSummary> result = service.listReviewingBatches(owner, PageRequest.of(2, 15));

        assertTrue(result.isEmpty());
        assertEquals(17, result.getTotalElements());
        assertEquals(2, result.getTotalPages());
        assertEquals(canonical, result.getPageable());
        verify(batches).findByUserIdAndStatus(owner, ImportStatus.REVIEWING, canonical);
        verifyNoMoreInteractions(batches);
    }

    @Test
    void noMatchingBatchReturnsEmptyPageWithoutAggregate() {
        PageRequest canonical = PageRequest.of(0, 15, ORDER);
        when(batches.findByUserIdAndStatus(owner, ImportStatus.REVIEWING, canonical))
                .thenReturn(Page.empty(canonical));

        Page<ImportBatchSummary> result = service.listReviewingBatches(owner, PageRequest.of(0, 15));

        assertTrue(result.isEmpty());
        assertEquals(0, result.getTotalElements());
        assertEquals(canonical, result.getPageable());
        verify(batches).findByUserIdAndStatus(owner, ImportStatus.REVIEWING, canonical);
        verifyNoMoreInteractions(batches);
    }

    private ImportBatch batch() {
        ImportBatch batch = mock(ImportBatch.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        when(batch.getAccountId()).thenReturn(account);
        when(batch.getFileName()).thenReturn("synthetic.csv");
        when(batch.getStatus()).thenReturn(ImportStatus.REVIEWING);
        when(batch.getVersion()).thenReturn(7L);
        when(batch.getCreatedAt()).thenReturn(createdAt);
        return batch;
    }

    private static ImportBatchRepository.CandidateCounts counts(UUID id, long total, long attention) {
        var counts = mock(ImportBatchRepository.CandidateCounts.class);
        when(counts.getBatchId()).thenReturn(id);
        when(counts.getCandidateCount()).thenReturn(total);
        when(counts.getAttentionRowCount()).thenReturn(attention);
        return counts;
    }

    private static Pageable suppliedPage(int number, int size, Sort sort) {
        Pageable page = mock(Pageable.class);
        when(page.isUnpaged()).thenReturn(false);
        when(page.getPageNumber()).thenReturn(number);
        when(page.getPageSize()).thenReturn(size);
        when(page.getOffset()).thenReturn((long) number * size);
        when(page.getSort()).thenReturn(sort);
        return page;
    }
}
