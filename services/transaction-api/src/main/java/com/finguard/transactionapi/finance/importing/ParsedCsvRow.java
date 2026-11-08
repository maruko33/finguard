package com.finguard.transactionapi.finance.importing;

import java.util.List;

/** Uninterpreted CSV cells. Do not log these values. */
public record ParsedCsvRow(long sourceRowNumber, List<String> values) {
    public ParsedCsvRow {
        if (sourceRowNumber < 2) throw new IllegalArgumentException("Data records start at 2");
        values = List.copyOf(values);
    }
}
