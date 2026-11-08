package com.finguard.transactionapi.finance.exception;

public class ImportBatchNotFoundException extends RuntimeException {
    public ImportBatchNotFoundException() { super("Import batch not found"); }
}
