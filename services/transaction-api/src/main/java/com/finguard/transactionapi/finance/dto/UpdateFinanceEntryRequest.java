package com.finguard.transactionapi.finance.dto;

import com.finguard.transactionapi.finance.domain.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** All editable fields are replaced; null category/occurrence explicitly clears them. */
public record UpdateFinanceEntryRequest(UUID categoryId, EntryType entryType,
        BigDecimal amount, String currencyCode, LocalDate transactionDate,
        Instant occurredAt, String description, Long expectedVersion) {}
