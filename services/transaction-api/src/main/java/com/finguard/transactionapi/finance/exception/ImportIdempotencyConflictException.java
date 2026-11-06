package com.finguard.transactionapi.finance.exception;

public class ImportIdempotencyConflictException extends RuntimeException {
    public ImportIdempotencyConflictException() {
        super("Upload key is already associated with a different or unverified import request");
    }
}
