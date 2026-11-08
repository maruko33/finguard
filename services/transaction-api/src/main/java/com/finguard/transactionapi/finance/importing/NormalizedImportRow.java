package com.finguard.transactionapi.finance.importing;

import com.finguard.transactionapi.finance.domain.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Immutable candidate; EntryValidator decides whether it is acceptable. Not a JPA entity. */
public record NormalizedImportRow(
        long sourceRowNumber,
        LocalDate transactionDate,
        Instant occurredAt,
        String description,
        BigDecimal amount,
        String currencyCode,
        EntryType entryType,
        String bankTransactionId) {
    public NormalizedImportRow {
        if (sourceRowNumber < 2) throw new IllegalArgumentException("Data records start at 2");
    }
}
