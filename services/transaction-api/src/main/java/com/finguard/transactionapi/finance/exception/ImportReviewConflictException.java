package com.finguard.transactionapi.finance.exception;

/** Reload the saved preview and reconcile the decision; never replay it against newer evidence. */
public class ImportReviewConflictException extends RuntimeException {
    public ImportReviewConflictException() {
        super("Import review or duplicate evidence changed; reload before saving");
    }

    public ImportReviewConflictException(Throwable cause) {
        super("Import review or duplicate evidence changed; reload before saving", cause);
    }
}
