package com.finguard.transactionapi.finance.importing;

import java.util.List;
import java.util.stream.Stream;

/** Source-ordered data outcomes are authoritative; header diagnostics are not data records. */
public record CsvImportResult(List<ImportRowOutcome> rows, List<ImportRowIssue> headerIssues) {
    public CsvImportResult {
        rows = List.copyOf(rows);
        headerIssues = List.copyOf(headerIssues);
        if (headerIssues.stream().anyMatch(issue -> issue.sourceRowNumber() != 1)) {
            throw new IllegalArgumentException("Header issues must refer to record 1");
        }
        long previous = 1;
        for (ImportRowOutcome row : rows) {
            if (row.sourceRowNumber() <= previous) {
                throw new IllegalArgumentException("Outcomes must have unique source records in ascending order");
            }
            if (!headerIssues.isEmpty() && row.status() != ImportRowStatus.REJECTED) {
                throw new IllegalArgumentException("Invalid headers cannot produce accepted or review outcomes");
            }
            previous = row.sourceRowNumber();
        }
    }

    public long totalCount() {
        return rows.size();
    }

    public long acceptedCount() {
        return count(ImportRowStatus.ACCEPTED);
    }

    public long reviewRequiredCount() {
        return count(ImportRowStatus.REVIEW_REQUIRED);
    }

    public long rejectedCount() {
        return count(ImportRowStatus.REJECTED);
    }

    private long count(ImportRowStatus status) {
        return rows.stream().filter(row -> row.status() == status).count();
    }

    // Compatibility views are computed from rows; none can be independently mutated or supplied.
    public List<NormalizedImportRow> acceptedRows() {
        return rows.stream().flatMap(row -> row.candidate().stream()).toList();
    }

    public List<ImportRowIssue> rejectedIssues() {
        return Stream.concat(headerIssues.stream(), rows.stream()
                .filter(row -> row.status() == ImportRowStatus.REJECTED)
                .flatMap(row -> row.issues().stream())).toList();
    }

    public List<ImportRowIssue> reviewIssues() {
        return rows.stream().filter(row -> row.status() == ImportRowStatus.REVIEW_REQUIRED)
                .flatMap(row -> row.issues().stream()).toList();
    }
}
