package com.finguard.transactionapi.finance.dto;

import com.finguard.transactionapi.finance.domain.EntryType;
import com.finguard.transactionapi.finance.domain.LedgerEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FinanceEntryResponse(UUID id, UUID accountId, UUID categoryId, EntryType entryType,
        BigDecimal amount, String currencyCode, LocalDate transactionDate, Instant occurredAt,
        String description, Instant createdAt, Instant updatedAt, long version) {
    public static FinanceEntryResponse from(LedgerEntry entry) {
        return new FinanceEntryResponse(entry.getId(), entry.getAccountId(), entry.getCategoryId(),
                entry.getEntryType(), entry.getAmount(), entry.getCurrencyCode(), entry.getTransactionDate(),
                entry.getOccurredAt(), entry.getDescription(), entry.getCreatedAt(), entry.getUpdatedAt(), entry.getVersion());
    }
}
