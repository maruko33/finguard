package com.finguard.transactionapi.finance.exception;

public class FinanceEntryNotFoundException extends RuntimeException {
    public FinanceEntryNotFoundException() { super("Finance entry not found"); }
}
