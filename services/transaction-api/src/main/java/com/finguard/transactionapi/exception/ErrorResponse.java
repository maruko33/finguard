package com.finguard.transactionapi.exception;

public record ErrorResponse(
        String code,
        String message
) {}
