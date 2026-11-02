package com.finguard.transactionapi.finance.dto;

import com.finguard.transactionapi.finance.domain.EntryType;
import com.finguard.transactionapi.finance.importing.ImportSourceDirection;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Stored review fields only; validationErrors retains the existing JSON representation. */
public record CandidateSummary(
        UUID candidateId,
        int rowNumber,
        EntryType entryType,
        BigDecimal amount,
        String currencyCode,
        LocalDate transactionDate,
        Instant occurredAt,
        String description,
        UUID categoryId,
        boolean selectedForImport,
        String validationErrors,
        boolean duplicateWarning,
        boolean duplicateAcknowledged,
        ImportSourceDirection sourceDirection,
        String duplicateMatchSignature) {
}
