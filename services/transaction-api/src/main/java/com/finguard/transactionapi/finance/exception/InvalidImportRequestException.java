package com.finguard.transactionapi.finance.exception;

public class InvalidImportRequestException extends IllegalArgumentException {
    public InvalidImportRequestException(String message) { super(message); }
}
