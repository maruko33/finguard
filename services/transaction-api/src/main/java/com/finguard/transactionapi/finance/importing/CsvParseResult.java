package com.finguard.transactionapi.finance.importing;

import java.util.List;

/** Syntax-only result; header names remain uninterpreted until profile validation. */
public record CsvParseResult(List<String> headers, List<ParsedCsvRow> rows) {
    public CsvParseResult {
        headers = List.copyOf(headers);
        rows = List.copyOf(rows);
    }
}
