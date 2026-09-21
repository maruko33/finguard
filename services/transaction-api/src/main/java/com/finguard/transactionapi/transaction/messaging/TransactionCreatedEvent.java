package com.finguard.transactionapi.transaction.messaging;
import java.util.UUID;
import java.math.BigDecimal;

public record TransactionCreatedEvent(
        UUID transactionId,
        BigDecimal amount,
        String currency
) {}
