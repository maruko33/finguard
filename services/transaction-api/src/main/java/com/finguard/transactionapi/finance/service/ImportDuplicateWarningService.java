package com.finguard.transactionapi.finance.service;

import com.finguard.transactionapi.finance.domain.ImportBatch;
import com.finguard.transactionapi.finance.domain.ImportStatus;
import com.finguard.transactionapi.finance.exception.ImportBatchNotFoundException;
import com.finguard.transactionapi.finance.exception.ImportBatchStateConflictException;
import com.finguard.transactionapi.finance.exception.InvalidImportRequestException;
import com.finguard.transactionapi.finance.importing.CsvParser;
import com.finguard.transactionapi.finance.repository.ImportBatchRepository;
import com.finguard.transactionapi.finance.repository.ImportCandidateRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class ImportDuplicateWarningService {
    private final ImportBatchRepository batches;
    private final ImportCandidateRepository candidates;
    private final ImportDuplicateDetector detector;

    @PersistenceContext
    private EntityManager entityManager;

    public ImportDuplicateWarningService(ImportBatchRepository batches, ImportCandidateRepository candidates) {
        this.batches = batches;
        this.candidates = candidates;
        this.detector = new ImportDuplicateDetector(candidates);
    }

    /**
     * Trusted internal mutation; REQUIRED joins the caller's transaction and rollback.
     * Only the target Batch is locked. Candidate editing and confirmation must lock that same Batch.
     * External sources share one statement snapshot, not a permanent guarantee of uniqueness.
     * Future Review/Confirm must revalidate evidence; direct SQL bypassing the lock protocol is unprotected.
     */
    @Transactional
    public void refreshDuplicateWarnings(UUID currentUserId, UUID batchId) {
        if (currentUserId == null || batchId == null) {
            throw new InvalidImportRequestException("User and batch are required");
        }
        var batch = batches.findOwnedForDuplicateScan(batchId, currentUserId)
                .orElseThrow(ImportBatchNotFoundException::new);
        refresh(batch, currentUserId);
    }

    /**
     * Compatibility entry point: now refreshes ALL three sources, retaining the account ownership check.
     * Both public entry points use the same implementation and effective REQUIRED transaction boundary.
     */
    @Transactional
    public void refreshWithinBatchDuplicateWarnings(UUID currentUserId, UUID accountId, UUID batchId) {
        if (currentUserId == null || accountId == null || batchId == null) {
            throw new InvalidImportRequestException("User, account and batch are required");
        }
        var batch = batches.findOwnedForDuplicateScan(batchId, currentUserId, accountId)
                .orElseThrow(ImportBatchNotFoundException::new);
        refresh(batch, currentUserId);
    }

    private void refresh(ImportBatch batch, UUID currentUserId) {
        if (batch.getStatus() != ImportStatus.REVIEWING) throw new ImportBatchStateConflictException();
        var rows = candidates.findForDuplicateScan(batch.getId(), currentUserId,
                PageRequest.of(0, CsvParser.DEFAULT_MAX_ROWS + 1));
        if (rows.size() > CsvParser.DEFAULT_MAX_ROWS) {
            throw new InvalidImportRequestException("Import batch exceeds the supported candidate limit");
        }
        var signatures = detector.findSignatures(currentUserId, batch.getId(), batch.getAccountId(),
                rows.stream().map(ImportDuplicateDetector.CandidateFacts::from).toList());
        boolean changed = false;
        for (var row : rows) {
            if (row.refreshDuplicateWarning(signatures.get(row.getId()))) changed = true;
        }
        if (changed) entityManager.lock(batch, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }

}
