package com.finguard.transactionapi.finance.importing;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One authoritative outcome; compatibility accessors are projections, not parallel state. */
public record BankNormalizeResult(ImportRowOutcome outcome) {
    public BankNormalizeResult {
        Objects.requireNonNull(outcome, "outcome");
    }

    public Optional<NormalizedImportRow> candidate() {
        return outcome.candidate();
    }

    public List<ImportRowIssue> issues() {
        return outcome.issues();
    }
}
