package com.finguard.transactionapi.finance.exception;

/** Reload/review saved decisions and duplicate evidence before attempting confirmation again. */
public class ImportConfirmationConflictException extends RuntimeException {
    public ImportConfirmationConflictException() {
        super("Import confirmation conflicts with current state or evidence; review again");
    }
    public ImportConfirmationConflictException(Throwable cause) {
        super("Import confirmation conflicts with current state or evidence; review again", cause);
    }
}
