package com.finguard.transactionapi.finance.repository;

import com.finguard.transactionapi.finance.domain.ImportCandidate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import com.finguard.transactionapi.finance.domain.EntryType;
import com.finguard.transactionapi.finance.importing.ImportSourceDirection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportCandidateRepository extends JpaRepository<ImportCandidate, UUID> {
    // Scalar projection reads final facts from the database, never a previously managed Candidate.
    @Query("""
            SELECT c.id AS id, c.batchId AS batchId, c.userId AS userId, c.rowNumber AS rowNumber,
                   c.entryType AS entryType, c.amount AS amount, c.currencyCode AS currencyCode,
                   c.transactionDate AS transactionDate, c.occurredAt AS occurredAt, c.description AS description,
                   c.categoryId AS categoryId, c.bankTransactionId AS bankTransactionId,
                   c.validationErrors AS validationErrors, c.selectedForImport AS selectedForImport,
                   c.duplicateWarning AS duplicateWarning, c.duplicateAcknowledged AS duplicateAcknowledged,
                   c.duplicateMatchSignature AS duplicateMatchSignature, c.sourceDirection AS sourceDirection
            FROM ImportCandidate c WHERE c.batchId = :batchId AND c.userId = :currentUserId
            ORDER BY c.rowNumber ASC
            """)
    List<ConfirmationCandidate> findConfirmationCandidates(@Param("batchId") UUID batchId,
            @Param("currentUserId") UUID currentUserId, Pageable pageable);

    interface ConfirmationCandidate {
        UUID getId();
        UUID getBatchId();
        UUID getUserId();
        int getRowNumber();
        EntryType getEntryType();
        java.math.BigDecimal getAmount();
        String getCurrencyCode();
        LocalDate getTransactionDate();
        Instant getOccurredAt();
        String getDescription();
        UUID getCategoryId();
        String getBankTransactionId();
        String getValidationErrors();
        boolean getSelectedForImport();
        boolean getDuplicateWarning();
        boolean getDuplicateAcknowledged();
        String getDuplicateMatchSignature();
        ImportSourceDirection getSourceDirection();
    }

    // Scalars come from the current SQL row even if an outer REQUIRED context already holds c.
    // Comparing these with the managed entity detects stale review metadata without per-row refreshes.
    @Query("""
            SELECT c AS candidate, c.categoryId AS categoryId, c.selectedForImport AS selectedForImport,
                   c.duplicateAcknowledged AS duplicateAcknowledged, c.duplicateWarning AS duplicateWarning,
                   c.duplicateMatchSignature AS duplicateMatchSignature
            FROM ImportCandidate c
            WHERE c.batchId = :batchId AND c.userId = :currentUserId AND c.id IN (:candidateIds)
            """)
    List<ReviewCandidate> findForReview(@Param("batchId") UUID batchId,
            @Param("currentUserId") UUID currentUserId, @Param("candidateIds") List<UUID> candidateIds);

    interface ReviewCandidate {
        ImportCandidate getCandidate();
        UUID getCategoryId();
        boolean getSelectedForImport();
        boolean getDuplicateAcknowledged();
        boolean getDuplicateWarning();
        String getDuplicateMatchSignature();
    }

    @Query("SELECT c FROM ImportCandidate c WHERE c.batchId = :batchId AND c.userId = :currentUserId")
    List<ImportCandidate> findForDuplicateScan(@Param("batchId") UUID batchId,
            @Param("currentUserId") UUID currentUserId, Pageable pageable);

    // A single PostgreSQL statement observes both sources in one snapshot. No external row locks.
    // The limit is a completeness probe: the caller rejects overflow before changing any metadata.
    String EXTERNAL_DUPLICATE_SOURCES_SQL = """
            SELECT 'OTHER_REVIEWING_BATCH' AS "sourceKind", c.id AS "sourceId", c.batch_id AS "batchId",
                   c.transaction_date AS "transactionDate", c.currency_code AS "currencyCode",
                   c.source_direction AS "sourceDirection", c.entry_type AS "entryType",
                   c.amount AS "amount", c.description AS "description"
            FROM {h-schema}import_candidate c
            JOIN {h-schema}import_batch b ON b.id = c.batch_id AND b.user_id = c.user_id
            WHERE c.user_id = :userId AND b.user_id = :userId AND b.account_id = :accountId
              AND b.status = 'REVIEWING' AND b.id <> :batchId
              AND c.transaction_date IN (:dates) AND c.currency_code IN (:currencies)
              AND c.amount IN (:amounts) AND c.source_direction IN (:directions)
              AND (c.entry_type IN (:types) OR (:includeUnknownType AND c.entry_type IS NULL))
              AND c.description IS NOT NULL
            UNION ALL
            SELECT 'LEDGER' AS "sourceKind", l.id AS "sourceId", CAST(NULL AS uuid) AS "batchId",
                   l.transaction_date AS "transactionDate", l.currency_code AS "currencyCode",
                   CAST(NULL AS varchar) AS "sourceDirection", l.entry_type AS "entryType",
                   l.amount AS "amount", l.description AS "description"
            FROM {h-schema}ledger_entry l
            WHERE :includeLedger AND l.user_id = :userId AND l.account_id = :accountId
              AND l.deleted_at IS NULL AND l.transaction_date IN (:dates)
              AND l.currency_code IN (:currencies) AND l.amount IN (:amounts) AND l.entry_type IN (:types)
            LIMIT :sourceLimit
            """;

    @Query(value = EXTERNAL_DUPLICATE_SOURCES_SQL, nativeQuery = true)
    List<ExternalDuplicateSource> findExternalDuplicateSources(
            @Param("userId") UUID userId, @Param("batchId") UUID batchId, @Param("accountId") UUID accountId,
            @Param("dates") List<LocalDate> dates, @Param("currencies") List<String> currencies,
            @Param("amounts") List<BigDecimal> amounts, @Param("directions") List<String> directions,
            @Param("types") List<String> types, @Param("includeUnknownType") boolean includeUnknownType,
            @Param("includeLedger") boolean includeLedger, @Param("sourceLimit") int sourceLimit);

    interface ExternalDuplicateSource {
        String getSourceKind();
        UUID getSourceId();
        UUID getBatchId();
        LocalDate getTransactionDate();
        String getCurrencyCode();
        String getSourceDirection();
        String getEntryType();
        BigDecimal getAmount();
        String getDescription();
    }

    // Keep null precedence in the database query; callers supply an unsorted pageable.
    @Query(value = """
            SELECT c FROM ImportCandidate c
            WHERE c.batchId = :batchId AND c.userId = :currentUserId
            ORDER BY c.transactionDate DESC NULLS LAST, c.rowNumber ASC
            """, countQuery = """
            SELECT COUNT(c) FROM ImportCandidate c
            WHERE c.batchId = :batchId AND c.userId = :currentUserId
            """)
    Page<ImportCandidate> findPreviewByBatchIdAndUserId(
            @Param("batchId") UUID batchId, @Param("currentUserId") UUID currentUserId,
            Pageable pageable);
}
