package com.finguard.transactionapi.finance.exception;

public class FinancialAccountNotFoundException extends RuntimeException {
    public FinancialAccountNotFoundException() { super("Financial account not found"); }
}
