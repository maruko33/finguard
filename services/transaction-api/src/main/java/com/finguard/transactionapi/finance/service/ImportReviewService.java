package com.finguard.transactionapi.finance.service;

import com.finguard.transactionapi.finance.domain.Category;
import com.finguard.transactionapi.finance.domain.ImportCandidate;
import com.finguard.transactionapi.finance.domain.ImportStatus;
import com.finguard.transactionapi.finance.domain.Money;
import com.finguard.transactionapi.finance.dto.CandidateReviewDecision;
import com.finguard.transactionapi.finance.dto.ImportReviewResult;
import com.finguard.transactionapi.finance.dto.SaveImportReviewRequest;
import com.finguard.transactionapi.finance.exception.*;
import com.finguard.transactionapi.finance.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ImportReviewService {
    private final ImportBatchRepository batches;
    private final ImportCandidateRepository candidates;
    private final FinancialAccountRepository accounts;
    private final CategoryRepository categories;
    private final JsonMapper jsonMapper;

    @PersistenceContext
    private EntityManager entityManager;

    public ImportReviewService(ImportBatchRepository batches, ImportCandidateRepository candidates,
                               FinancialAccountRepository accounts, CategoryRepository categories,
                               JsonMapper jsonMapper) {
        this.batches = batches;
        this.candidates = candidates;
        this.accounts = accounts;
        this.categories = categories;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Trusted internal save. Uses the same Batch-first lock protocol as duplicate refresh.
     * REQUIRED joins the caller: flush exposes errors/version, but the outer transaction owns commit.
     * Account/category validation observes their current values; it does not freeze those external rows.
     * Confirmation must revalidate all selected rows and evidence, including rows omitted here.
     */
    @Transactional
    public ImportReviewResult saveReview(UUID currentUserId, UUID batchId, SaveImportReviewRequest request) {
        var decisions = validateRequest(currentUserId, batchId, request);
        try {
            return save(currentUserId, batchId, request.expectedBatchVersion(), decisions);
        } catch (OptimisticLockException | OptimisticLockingFailureException staleManagedState) {
            throw new ImportReviewConflictException(staleManagedState);
        }
    }

    private ImportReviewResult save(UUID userId, UUID batchId, long expectedVersion,
                                    List<CandidateReviewDecision> decisions) {
        var batch = batches.findOwnedForDuplicateScan(batchId, userId)
                .orElseThrow(ImportBatchNotFoundException::new);
        if (batch.getStatus() != ImportStatus.REVIEWING) throw new ImportBatchStateConflictException();
        if (batch.getVersion() != expectedVersion) throw new ImportReviewConflictException();
        var ids = decisions.stream().map(CandidateReviewDecision::candidateId).toList();
        var rows = candidates.findForReview(batchId, userId, ids);
        var rowsById = new HashMap<UUID, ImportCandidate>();
        for (var snapshot : rows) {
            var row = snapshot.getCandidate();
            if (!userId.equals(row.getUserId()) || !batchId.equals(row.getBatchId())) {
                throw new ImportCandidateNotFoundException();
            }
            // The parent may be freshly loaded while a caller's Candidate is stale in the first-level cache.
            // Reject instead of evicting caller state or overwriting newer decisions/evidence on flush.
            if (!Objects.equals(row.getCategoryId(), snapshot.getCategoryId())
                    || row.getSelectedForImport() != snapshot.getSelectedForImport()
                    || row.getDuplicateAcknowledged() != snapshot.getDuplicateAcknowledged()
                    || row.getDuplicateWarning() != snapshot.getDuplicateWarning()
                    || !Objects.equals(row.getDuplicateMatchSignature(), snapshot.getDuplicateMatchSignature())) {
                throw new ImportReviewConflictException();
            }
            rowsById.put(row.getId(), row);
        }
        if (rowsById.size() != ids.size() || !rowsById.keySet().containsAll(ids)) {
            throw new ImportCandidateNotFoundException();
        }
        String accountCurrency = null;
        if (decisions.stream().anyMatch(CandidateReviewDecision::selectedForImport)) {
            accountCurrency = accounts.findByIdAndUserId(batch.getAccountId(), userId)
                    .orElseThrow(FinancialAccountNotFoundException::new).getCurrencyCode();
        }
        var categoryIds = new HashSet<UUID>();
        for (var decision : decisions) {
            if (requiresCategoryValidation(rowsById.get(decision.candidateId()), decision)) {
                categoryIds.add(decision.categoryId());
            }
        }
        var categoriesById = new HashMap<UUID, Category>();
        if (!categoryIds.isEmpty()) {
            for (var category : categories.findByUserIdAndIdIn(userId,
                    categoryIds.stream().sorted().toList())) categoriesById.put(category.getId(), category);
        }
        // Validate every requested replacement before changing even the first managed Candidate.
        for (var decision : decisions) {
            var row = rowsById.get(decision.candidateId());
            validateAcknowledgement(row, decision);
            if (decision.selectedForImport()) validateSelection(row, accountCurrency);
            if (requiresCategoryValidation(row, decision)) {
                var category = categoriesById.get(decision.categoryId());
                if (category == null || !category.getActive() || row.getEntryType() == null
                        || category.getEntryType() != row.getEntryType()) {
                    throw new InvalidImportRequestException("Invalid category for this review decision");
                }
            }
        }
        int changed = 0;
        for (var decision : decisions) {
            if (rowsById.get(decision.candidateId()).replaceReviewDecision(decision.categoryId(),
                    decision.selectedForImport(), decision.duplicateAcknowledged())) changed++;
        }
        if (changed != 0) {
            // Managed @Version is advanced exactly once; child updates alone cannot do this.
            entityManager.lock(batch, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
            entityManager.flush();
        }
        return new ImportReviewResult(batch.getId(), batch.getStatus(), batch.getVersion(), changed);
    }

    private static boolean requiresCategoryValidation(ImportCandidate row, CandidateReviewDecision decision) {
        return decision.categoryId() != null
                && (decision.selectedForImport() || !decision.categoryId().equals(row.getCategoryId()));
    }

    private static void validateAcknowledgement(ImportCandidate row, CandidateReviewDecision decision) {
        if (decision.duplicateAcknowledged()) {
            if (!row.getDuplicateWarning()) {
                throw new InvalidImportRequestException("There is no saved duplicate warning to acknowledge");
            }
            if (row.getDuplicateMatchSignature() == null
                    || !row.getDuplicateMatchSignature().equals(decision.expectedDuplicateMatchSignature())) {
                throw new ImportReviewConflictException();
            }
        }
        if (decision.selectedForImport() && row.getDuplicateWarning() && !decision.duplicateAcknowledged()) {
            throw new InvalidImportRequestException("Selecting a warned candidate requires acknowledgement");
        }
    }

    private void validateSelection(ImportCandidate row, String accountCurrency) {
        boolean noIssues;
        try {
            var errors = row.getValidationErrors() == null ? null : jsonMapper.readTree(row.getValidationErrors());
            noIssues = errors != null && errors.isArray() && errors.isEmpty();
        } catch (JacksonException invalidStoredErrors) {
            // Do not propagate parser diagnostics that could include stored row content.
            throw new InvalidImportRequestException("Stored validation issues prevent selection");
        }
        if (!noIssues || row.getEntryType() == null || row.getTransactionDate() == null
                || row.getAmount() == null || row.getDescription() == null || row.getDescription().isBlank()
                || row.getDescription().length() > 500) {
            throw new InvalidImportRequestException("Candidate has unresolved issues or incomplete transaction fields");
        }
        try {
            var money = new Money(row.getAmount(), row.getCurrencyCode());
            Currency.getInstance(money.currencyCode());
            if (!money.currencyCode().equals(accountCurrency)) {
                throw new InvalidImportRequestException("Candidate currency must match account currency");
            }
        } catch (IllegalArgumentException | ArithmeticException invalidMoney) {
            throw new InvalidImportRequestException("Candidate amount or currency is invalid");
        }
    }

    private static List<CandidateReviewDecision> validateRequest(UUID userId, UUID batchId,
                                                               SaveImportReviewRequest request) {
        if (userId == null || batchId == null || request == null || request.expectedBatchVersion() == null
                || request.expectedBatchVersion() < 0 || request.decisions() == null
                || request.decisions().isEmpty() || request.decisions().size() > 100) {
            throw new InvalidImportRequestException("User, batch, nonnegative version and 1 to 100 decisions are required");
        }
        var seen = new HashSet<UUID>();
        for (var decision : request.decisions()) {
            if (decision == null || decision.candidateId() == null || decision.selectedForImport() == null
                    || decision.duplicateAcknowledged() == null || !seen.add(decision.candidateId())) {
                throw new InvalidImportRequestException("Each decision requires a unique candidate ID and explicit booleans");
            }
            String signature = decision.expectedDuplicateMatchSignature();
            if (decision.duplicateAcknowledged() ? signature == null || signature.isBlank() : signature != null) {
                throw new InvalidImportRequestException("Provide an evidence signature only when acknowledging a warning");
            }
        }
        return List.copyOf(request.decisions());
    }
}
