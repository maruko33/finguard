package com.finguard.transactionapi.transaction.dto;

import com.finguard.transactionapi.transaction.domain.TransactionStatus;

import java.util.UUID;
import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
    UUID id,
    BigDecimal amount,
    String currency,
    TransactionStatus status,
    Instant createdAt
) {
    
}
