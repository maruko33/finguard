package com.finguard.transactionapi.finance.importing;

import java.util.Objects;

/** A safe file-level failure: never includes CSV content or a library exception. */
public final class CsvParseException extends IllegalArgumentException {
    private final ImportIssueCode code;

    public CsvParseException(ImportIssueCode code) {
        super(Objects.requireNonNull(code, "code").message());
        this.code = code;
    }

    public ImportIssueCode code() {
        return code;
    }
}
