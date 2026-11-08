package com.finguard.transactionapi.finance.dto;

import com.finguard.transactionapi.finance.domain.EntryType;
import java.time.LocalDate;
import java.util.UUID;

/** Required account scope with optional, inclusive transaction-date bounds. */
public record ListFinanceEntriesFilter(UUID accountId, LocalDate fromTransactionDate,
        LocalDate toTransactionDate, EntryType entryType) {}
