package com.finguard.transactionapi.finance.exception;

/** Deliberately does not distinguish missing, foreign or other-batch Candidate IDs. */
public class ImportCandidateNotFoundException extends RuntimeException {
    public ImportCandidateNotFoundException() { super("Import candidate not found"); }
}
