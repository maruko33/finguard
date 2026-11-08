package com.finguard.transactionapi.finance.exception;

/** Caller must reload the entry and reconcile its edits before retrying. */
public class FinanceEntryConflictException extends RuntimeException {
    public FinanceEntryConflictException() {
        super("Finance entry was changed; reload before updating");
    }

    public FinanceEntryConflictException(Throwable cause) {
        super("Finance entry was changed; reload before updating", cause);
    }
}
