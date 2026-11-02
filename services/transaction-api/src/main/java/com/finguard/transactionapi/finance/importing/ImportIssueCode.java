package com.finguard.transactionapi.finance.importing;

/** Stable codes and fixed diagnostics: never interpolate CSV values or library errors. */
public enum ImportIssueCode {
    EMPTY_FILE("CSV must contain a header record."),
    INVALID_CSV("CSV syntax is invalid."),
    INVALID_ENCODING("CSV must use valid UTF-8 encoding."),
    IO_ERROR("CSV input could not be read."),
    FILE_TOO_LARGE("CSV exceeds the configured byte limit."),
    TOO_MANY_ROWS("CSV exceeds the configured data record limit."),
    INVALID_HEADERS("CSV headers do not match the selected bank profile."),
    MISSING_REQUIRED_HEADER("A required bank CSV column is missing."),
    DUPLICATE_HEADER("CSV contains duplicate column names after normalization."),
    COLUMN_COUNT_MISMATCH("Record column count does not match the header."),
    INVALID_DATE("Transaction date is missing or invalid."),
    INVALID_AMOUNT("Amount must be a valid positive, exact NUMERIC(19,2) value."),
    AMOUNT_TOO_LONG("Amount text exceeds the 128-character limit."),
    AMBIGUOUS_CURRENCY("Record contains amounts in both currency columns."),
    REVIEW_REQUIRED("Positive credit requires review of its transaction meaning."),
    INVALID_CURRENCY("Currency must be a recognized uppercase three-letter code."),
    ACCOUNT_CURRENCY_MISMATCH("Transaction currency does not match the financial account currency."),
    INVALID_ENTRY_TYPE("Entry type is required."),
    INVALID_DESCRIPTION("Description must contain text and be at most 500 characters.");

    private final String message;

    ImportIssueCode(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
