package com.finguard.transactionapi.finance.importing;

import java.util.Objects;

/** Header issues use record 1; data issues retain their logical CSV record number. */
public record ImportRowIssue(long sourceRowNumber, ImportIssueCode code) {
    public ImportRowIssue {
        if (sourceRowNumber < 1) throw new IllegalArgumentException("Source record must be positive");
        Objects.requireNonNull(code, "code");
    }

    public String message() {
        return code.message();
    }
}
