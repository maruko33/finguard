package com.finguard.transactionapi.finance.importing;

import java.util.List;

public record EntryValidationResult(List<ImportRowIssue> issues) {
    public EntryValidationResult {
        issues = List.copyOf(issues);
    }

    public boolean isValid() {
        return issues.isEmpty();
    }
}
