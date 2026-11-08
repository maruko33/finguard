package com.finguard.transactionapi.finance.service;

import com.finguard.transactionapi.finance.domain.ImportBatch;
import com.finguard.transactionapi.finance.domain.ImportStatus;
import com.finguard.transactionapi.finance.dto.CandidateSummary;
import com.finguard.transactionapi.finance.dto.ImportBatchSummary;
import com.finguard.transactionapi.finance.dto.ImportPreviewResponse;
import com.finguard.transactionapi.finance.exception.ImportBatchNotFoundException;
import com.finguard.transactionapi.finance.exception.InvalidImportBatchQueryException;
import com.finguard.transactionapi.finance.repository.ImportBatchRepository;
import com.finguard.transactionapi.finance.repository.ImportCandidateRepository;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportBatchQueryService {
    private static final Sort LIST_ORDER = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final Sort PREVIEW_ORDER = Sort.by(
            Sort.Order.desc("transactionDate").nullsLast(), Sort.Order.asc("rowNumber"));
    private final ImportBatchRepository batchRepository;
    private final ImportCandidateRepository candidateRepository;

    public ImportBatchQueryService(ImportBatchRepository batchRepository,
                                   ImportCandidateRepository candidateRepository) {
        this.batchRepository = batchRepository;
        this.candidateRepository = candidateRepository;
    }

    /** Internal trusted-user query. Callers use PageRequest.of(0, 15) for the default page. */
    @Transactional(readOnly = true)
    public Page<ImportBatchSummary> listReviewingBatches(UUID currentUserId, Pageable pageable) {
        PageRequest request = validatePageRequest(currentUserId, pageable, LIST_ORDER);
        Page<ImportBatch> batches = batchRepository.findByUserIdAndStatus(
                currentUserId, ImportStatus.REVIEWING, request);
        var countsByBatch = new HashMap<UUID, ImportBatchRepository.CandidateCounts>();
        if (batches.hasContent()) {
            var batchIds = batches.getContent().stream().map(ImportBatch::getId).toList();
            for (var counts : batchRepository.countCandidatesByBatchIds(currentUserId, batchIds)) {
                countsByBatch.put(counts.getBatchId(), counts);
            }
        }
        // Page.map retains the original total, including an empty page beyond the last page.
        return batches.map(batch -> {
            var counts = countsByBatch.get(batch.getId());
            return new ImportBatchSummary(batch.getId(), batch.getAccountId(), batch.getFileName(),
                    batch.getStatus(), batch.getVersion(), batch.getCreatedAt(),
                    counts == null ? 0 : counts.getCandidateCount(),
                    counts == null ? 0 : counts.getAttentionRowCount());
        });
    }

    /** Reads saved data only. Callers use PageRequest.of(0, 15) for the default page. */
    @Transactional(readOnly = true)
    public ImportPreviewResponse getImportPreview(UUID currentUserId, UUID batchId, Pageable pageable) {
        if (batchId == null) {
            throw new InvalidImportBatchQueryException("Batch ID is required");
        }
        PageRequest request = validatePageRequest(currentUserId, pageable, PREVIEW_ORDER);
        ImportBatch batch = batchRepository.findByIdAndUserId(batchId, currentUserId)
                .orElseThrow(ImportBatchNotFoundException::new);
        // The query owns NULLS LAST ordering; avoid appending dynamic Spring Data sorting.
        var candidates = candidateRepository.findPreviewByBatchIdAndUserId(batchId, currentUserId,
                PageRequest.of(request.getPageNumber(), request.getPageSize()));
        var summaries = candidates.map(candidate -> new CandidateSummary(
                candidate.getId(), candidate.getRowNumber(), candidate.getEntryType(),
                candidate.getAmount(), candidate.getCurrencyCode(), candidate.getTransactionDate(),
                candidate.getOccurredAt(), candidate.getDescription(), candidate.getCategoryId(),
                candidate.getSelectedForImport(), candidate.getValidationErrors(),
                candidate.getDuplicateWarning(), candidate.getDuplicateAcknowledged(), candidate.getSourceDirection(),
                candidate.getDuplicateMatchSignature()));
        // Expose the effective order and retain the total even when the requested page is empty.
        var page = new PageImpl<>(summaries.getContent(), request, summaries.getTotalElements());
        return new ImportPreviewResponse(batch.getId(), batch.getAccountId(), batch.getFileName(),
                batch.getStatus(), batch.getVersion(), page);
    }

    private PageRequest validatePageRequest(UUID currentUserId, Pageable pageable, Sort supportedOrder) {
        if (currentUserId == null || pageable == null || pageable.isUnpaged()) {
            throw new InvalidImportBatchQueryException("User and paged request are required");
        }
        int pageNumber = pageable.getPageNumber();
        int pageSize = pageable.getPageSize();
        if (pageNumber < 0 || pageSize < 1 || pageSize > 100
                || (long) pageNumber * pageSize > Integer.MAX_VALUE) {
            throw new InvalidImportBatchQueryException("Invalid page number or size; maximum page size is 100");
        }
        Sort sort = pageable.getSort();
        if (sort == null || (sort.isSorted() && !supportedOrder.equals(sort))) {
            throw new InvalidImportBatchQueryException("Supported order is " + supportedOrder);
        }
        return PageRequest.of(pageNumber, pageSize, supportedOrder);
    }
}
