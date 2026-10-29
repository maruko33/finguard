package com.finguard.transactionapi.finance.importing;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exactly one candidate or one or more issues; normalization never silently drops a row. */
public record BankNormalizeResult(Optional<NormalizedImportRow> candidate, List<ImportRowIssue> issues) {
    public BankNormalizeResult {
        Objects.requireNonNull(candidate, "candidate");
        issues = List.copyOf(issues);
        if (candidate.isPresent() != issues.isEmpty()) {
            throw new IllegalArgumentException("Expected either a candidate or issues");
        }
    }

    public static BankNormalizeResult candidate(NormalizedImportRow row) {
        return new BankNormalizeResult(Optional.of(row), List.of());
    }

    public static BankNormalizeResult issue(long sourceRowNumber, ImportIssueCode code) {
        return new BankNormalizeResult(Optional.empty(), List.of(new ImportRowIssue(sourceRowNumber, code)));
    }
}
