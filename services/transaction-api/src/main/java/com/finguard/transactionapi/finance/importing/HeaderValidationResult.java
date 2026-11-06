package com.finguard.transactionapi.finance.importing;

import java.util.List;
import java.util.Map;

public record HeaderValidationResult(Map<String, Integer> columnIndexes, List<ImportRowIssue> issues) {
    public HeaderValidationResult {
        columnIndexes = Map.copyOf(columnIndexes);
        issues = List.copyOf(issues);
    }

    public boolean isValid() {
        return issues.isEmpty();
    }
}
