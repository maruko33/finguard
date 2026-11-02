package com.finguard.transactionapi.finance.dto;

import com.finguard.transactionapi.finance.domain.ImportStatus;
import java.time.Instant;
import java.util.UUID;

/** Read-only list data; candidate counts are derived from stored rows, not batch counters. */
public record ImportBatchSummary(
        UUID id,
        UUID accountId,
        String fileName,
        ImportStatus status,
        long version,
        Instant createdAt,
        long candidateCount,
        long attentionRowCount) {
}
