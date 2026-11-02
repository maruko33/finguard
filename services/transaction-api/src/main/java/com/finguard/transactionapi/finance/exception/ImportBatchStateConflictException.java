package com.finguard.transactionapi.finance.exception;

public class ImportBatchStateConflictException extends RuntimeException {
    public ImportBatchStateConflictException() { super("Import batch must be REVIEWING"); }
}
