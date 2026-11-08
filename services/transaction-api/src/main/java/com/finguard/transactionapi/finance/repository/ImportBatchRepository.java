package com.finguard.transactionapi.finance.repository;

import com.finguard.transactionapi.finance.domain.ImportBatch;
import com.finguard.transactionapi.finance.domain.ImportStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {
    // Scalar locking read bypasses an outer persistence context, including for CONFIRMED retries.
    @Query(value = """
            SELECT id AS "id", user_id AS "userId", account_id AS "accountId", status AS "status",
                   version AS "version", confirmed_count AS "confirmedCount", excluded_count AS "excludedCount",
                   confirmed_at AS "confirmedAt"
            FROM {h-schema}import_batch
            WHERE id = :batchId AND user_id = :currentUserId
            FOR UPDATE
            """, nativeQuery = true)
    Optional<ConfirmationBatchSnapshot> findConfirmationSnapshot(
            @Param("batchId") UUID batchId, @Param("currentUserId") UUID currentUserId);

    interface ConfirmationBatchSnapshot {
        UUID getId();
        UUID getUserId();
        UUID getAccountId();
        String getStatus();
        long getVersion();
        int getConfirmedCount();
        int getExcludedCount();
        Instant getConfirmedAt();
    }

    Page<ImportBatch> findByUserIdAndStatus(UUID userId, ImportStatus status, Pageable pageable);

    Optional<ImportBatch> findByIdAndUserId(UUID id, UUID userId);

    Optional<ImportBatch> findByUserIdAndUploadKey(UUID userId, UUID uploadKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM ImportBatch b WHERE b.id = :batchId AND b.userId = :currentUserId")
    Optional<ImportBatch> findOwnedForDuplicateScan(@Param("batchId") UUID batchId,
            @Param("currentUserId") UUID currentUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM ImportBatch b WHERE b.id = :batchId AND b.userId = :currentUserId AND b.accountId = :accountId")
    Optional<ImportBatch> findOwnedForDuplicateScan(@Param("batchId") UUID batchId,
            @Param("currentUserId") UUID currentUserId, @Param("accountId") UUID accountId);

    // V2 guarantees a non-null JSON array. Hibernate's placeholder also honors isolated test schemas.
    @Query(value = """
            SELECT c.batch_id AS "batchId",
                   COUNT(*) AS "candidateCount",
                   COUNT(*) FILTER (WHERE jsonb_array_length(c.validation_errors) > 0) AS "attentionRowCount"
            FROM {h-schema}import_candidate c
            WHERE c.user_id = :currentUserId AND c.batch_id IN (:batchIds)
            GROUP BY c.batch_id
            """, nativeQuery = true)
    List<CandidateCounts> countCandidatesByBatchIds(
            @Param("currentUserId") UUID currentUserId, @Param("batchIds") List<UUID> batchIds);

    interface CandidateCounts {
        UUID getBatchId();
        long getCandidateCount();
        long getAttentionRowCount();
    }
}
