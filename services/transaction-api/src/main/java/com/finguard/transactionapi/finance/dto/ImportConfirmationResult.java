package com.finguard.transactionapi.finance.dto;

import com.finguard.transactionapi.finance.domain.ImportStatus;
import java.time.Instant;
import java.util.UUID;

/** notImportedCount maps excluded_count; it does not imply an explicit decision for every omitted row. */
public record ImportConfirmationResult(UUID batchId, ImportStatus status, long version,
                                       int confirmedCount, int notImportedCount, Instant confirmedAt) {}
