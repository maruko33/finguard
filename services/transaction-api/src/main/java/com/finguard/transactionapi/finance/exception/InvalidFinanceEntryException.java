package com.finguard.transactionapi.finance.exception;

public class InvalidFinanceEntryException extends IllegalArgumentException {
    public InvalidFinanceEntryException(String message) { super(message); }
}
