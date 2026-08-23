package com.finguard.transactionapi.transaction.dto;
import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateTransactionRequest(
    @NotNull
    @Positive
    BigDecimal amount,
    @NotBlank
    String currency
) {
    

}
