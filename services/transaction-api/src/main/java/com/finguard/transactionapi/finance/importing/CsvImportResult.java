package com.finguard.transactionapi.finance.importing;

import java.util.List;

/** Several rejected issues may refer to the same record; header issues use record 1. */
public record CsvImportResult(
        List<NormalizedImportRow> acceptedRows,
        List<ImportRowIssue> rejectedIssues,
        List<ImportRowIssue> reviewIssues) {
    public CsvImportResult {
        acceptedRows = List.copyOf(acceptedRows);
        rejectedIssues = List.copyOf(rejectedIssues);
        reviewIssues = List.copyOf(reviewIssues);
    }
}
