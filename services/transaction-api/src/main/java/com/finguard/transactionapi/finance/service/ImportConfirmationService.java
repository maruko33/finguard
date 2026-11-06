package com.finguard.transactionapi.finance.service;

import com.finguard.transactionapi.finance.domain.*;
import com.finguard.transactionapi.finance.dto.ConfirmImportRequest;
import com.finguard.transactionapi.finance.dto.ImportConfirmationResult;
import com.finguard.transactionapi.finance.exception.*;
import com.finguard.transactionapi.finance.importing.CsvParser;
import com.finguard.transactionapi.finance.repository.*;
import com.finguard.transactionapi.finance.repository.ImportCandidateRepository.ConfirmationCandidate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ImportConfirmationService {
    private static final Set<String> BUSINESS_UNIQUES = Set.of("uq_ledger_imported_row", "uq_ledger_trusted_bank_txn");
    private final ImportBatchRepository batches;
    private final ImportCandidateRepository candidates;
    private final FinancialAccountRepository accounts;
    private final CategoryRepository categories;
    private final LedgerEntryRepository ledger;
    private final JsonMapper jsonMapper;
    private final ImportDuplicateDetector detector;

    @PersistenceContext
    private EntityManager entityManager;

    public ImportConfirmationService(ImportBatchRepository batches, ImportCandidateRepository candidates,
            FinancialAccountRepository accounts, CategoryRepository categories, LedgerEntryRepository ledger,
            JsonMapper jsonMapper) {
        this.batches = batches;
        this.candidates = candidates;
        this.accounts = accounts;
        this.categories = categories;
        this.ledger = ledger;
        this.jsonMapper = jsonMapper;
        this.detector = new ImportDuplicateDetector(candidates);
    }

    /**
     * REQUIRED: the caller owns the final commit/rollback when joining an outer transaction.
     * Lock order is target Batch -> Account (NO KEY UPDATE) -> Categories by ID (SHARE).
     * Only participating confirmations serialize on Account; Manual CRUD/direct SQL may bypass it.
     * External duplicate sources are observed after the account lock, without locking other Batches.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ImportConfirmationResult confirmImport(UUID currentUserId, UUID batchId, ConfirmImportRequest request) {
        if (currentUserId == null || batchId == null || request == null
                || request.expectedBatchVersion() == null || request.expectedBatchVersion() < 0) {
            throw new InvalidImportRequestException("User, batch and nonnegative expected version are required");
        }
        requireCompatibleTransaction();
        try {
            return confirm(currentUserId, batchId, request.expectedBatchVersion());
        } catch (OptimisticLockException | OptimisticLockingFailureException staleState) {
            throw new ImportConfirmationConflictException(staleState);
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint
                        && "23505".equals(constraint.getSQLState())
                        && constraint.getConstraintName() != null
                        && BUSINESS_UNIQUES.contains(constraint.getConstraintName())) {
                    throw new ImportConfirmationConflictException(failure);
                }
            }
            throw failure;
        }
    }

    private ImportConfirmationResult confirm(UUID userId, UUID batchId, long expectedVersion) {
        var snapshot = batches.findConfirmationSnapshot(batchId, userId)
                .orElseThrow(ImportBatchNotFoundException::new);
        var status = ImportStatus.valueOf(snapshot.getStatus());
        if (status == ImportStatus.CONFIRMED) {
            // Durable historical result; no Candidate/Account/Ledger lookup or expected-version comparison.
            return new ImportConfirmationResult(snapshot.getId(), status, snapshot.getVersion(),
                    snapshot.getConfirmedCount(), snapshot.getExcludedCount(), snapshot.getConfirmedAt());
        }
        if (status != ImportStatus.REVIEWING) throw new ImportBatchStateConflictException();
        if (snapshot.getVersion() != expectedVersion) throw new ImportConfirmationConflictException();
        // The row is already locked. Reject a stale managed Batch without clearing the caller's context.
        var batch = batches.findByIdAndUserId(batchId, userId).orElseThrow(ImportBatchNotFoundException::new);
        if (batch.getVersion() != snapshot.getVersion() || batch.getStatus() != status
                || !userId.equals(batch.getUserId()) || !snapshot.getAccountId().equals(batch.getAccountId())
                || batch.getConfirmedCount() != snapshot.getConfirmedCount()
                || batch.getExcludedCount() != snapshot.getExcludedCount()
                || !Objects.equals(batch.getConfirmedAt(), snapshot.getConfirmedAt()) || batch.getClosedAt() != null) {
            throw new ImportConfirmationConflictException();
        }
        var account = accounts.findOwnedForConfirmation(snapshot.getAccountId(), userId)
                .orElseThrow(FinancialAccountNotFoundException::new);
        if (!account.getActive()) throw new InvalidImportRequestException("Inactive account cannot confirm imports");
        var rows = candidates.findConfirmationCandidates(batchId, userId,
                PageRequest.of(0, CsvParser.DEFAULT_MAX_ROWS + 1));
        if (rows.size() > CsvParser.DEFAULT_MAX_ROWS) {
            throw new InvalidImportRequestException("Import batch exceeds the supported candidate limit");
        }
        for (var row : rows) {
            if (!userId.equals(row.getUserId()) || !batchId.equals(row.getBatchId())) {
                throw new ImportCandidateNotFoundException();
            }
        }
        var selected = rows.stream().filter(ConfirmationCandidate::getSelectedForImport).toList();
        if (selected.isEmpty()) throw new InvalidImportRequestException("Select at least one candidate before confirming");
        var categoryIds = selected.stream().map(ConfirmationCandidate::getCategoryId).filter(Objects::nonNull)
                .distinct().sorted().toList();
        var categoryById = new HashMap<UUID, CategoryRepository.ConfirmationCategory>();
        if (!categoryIds.isEmpty()) {
            for (var category : categories.findForConfirmation(userId, categoryIds)) categoryById.put(category.getId(), category);
        }
        var moneyById = new HashMap<UUID, Money>();
        for (var row : selected) {
            moneyById.put(row.getId(), validateSelected(row, account.getCurrencyCode()));
            if (row.getCategoryId() != null) {
                var category = categoryById.get(row.getCategoryId());
                if (category == null || !userId.equals(category.getUserId()) || !category.getActive()
                        || !row.getEntryType().name().equals(category.getEntryType())) {
                    throw new InvalidImportRequestException("Invalid category for import confirmation");
                }
            }
        }
        // Compute current complete evidence without applying refreshDuplicateWarning or touching decisions.
        var signatures = detector.findSignatures(userId, batchId, snapshot.getAccountId(), rows.stream()
                .map(row -> new ImportDuplicateDetector.CandidateFacts(row.getId(), row.getEntryType(), row.getAmount(),
                        row.getCurrencyCode(), row.getTransactionDate(), row.getDescription(), row.getSourceDirection()))
                .toList());
        for (var row : selected) {
            String currentSignature = signatures.get(row.getId());
            if (!Objects.equals(currentSignature, row.getDuplicateMatchSignature())
                    || row.getDuplicateWarning() != (currentSignature != null)
                    || (currentSignature != null && !row.getDuplicateAcknowledged())
                    || (currentSignature == null && row.getDuplicateAcknowledged())) {
                throw new ImportConfirmationConflictException();
            }
        }
        var entries = new ArrayList<LedgerEntry>(selected.size());
        for (var row : selected) {
            // No current profile supplies a verified bank transaction ID. Never promote an arbitrary staging ID.
            entries.add(LedgerEntry.imported(userId, snapshot.getAccountId(), batchId, row.getRowNumber(),
                    row.getCategoryId(), row.getEntryType(), moneyById.get(row.getId()), row.getTransactionDate(),
                    row.getOccurredAt(), row.getDescription()));
        }
        ledger.saveAll(entries);
        batch.markConfirmed(entries.size(), rows.size() - entries.size(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        // Executes INSERTs and the one versioned status UPDATE. The transaction may still roll back later.
        entityManager.flush();
        return new ImportConfirmationResult(batch.getId(), batch.getStatus(), batch.getVersion(),
                batch.getConfirmedCount(), batch.getExcludedCount(), batch.getConfirmedAt());
    }

    private Money validateSelected(ConfirmationCandidate row, String accountCurrency) {
        boolean noIssues;
        try {
            var errors = row.getValidationErrors() == null ? null : jsonMapper.readTree(row.getValidationErrors());
            noIssues = errors != null && errors.isArray() && errors.isEmpty();
        } catch (JacksonException malformedStoredIssues) {
            throw new InvalidImportRequestException("Stored validation issues prevent confirmation");
        }
        if (!noIssues || row.getEntryType() == null || row.getTransactionDate() == null || row.getAmount() == null
                || row.getDescription() == null || row.getDescription().isBlank() || row.getDescription().length() > 500
                || row.getRowNumber() < 1) {
            throw new InvalidImportRequestException("Selected candidate has unresolved issues or incomplete fields");
        }
        try {
            var money = new Money(row.getAmount(), row.getCurrencyCode());
            Currency.getInstance(money.currencyCode());
            if (!money.currencyCode().equals(accountCurrency)) {
                throw new InvalidImportRequestException("Candidate currency must match account currency");
            }
            return money;
        } catch (IllegalArgumentException | ArithmeticException invalidMoney) {
            throw new InvalidImportRequestException("Selected candidate amount or currency is invalid");
        }
    }

    private void requireCompatibleTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new InvalidImportRequestException("Confirmation requires a writable READ_COMMITTED transaction");
        }
        Object[] settings = (Object[]) entityManager.createNativeQuery(
                "SELECT current_setting('transaction_isolation'), current_setting('transaction_read_only')")
                .setFlushMode(FlushModeType.COMMIT).getSingleResult();
        if (!"read committed".equals(settings[0]) || !"off".equals(settings[1])) {
            throw new InvalidImportRequestException("Confirmation requires a writable READ_COMMITTED transaction");
        }
    }
}
