package com.finguard.transactionapi.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Exact, positive two-decimal finance amount; never silently rounds input. */
public record Money(BigDecimal amount, String currencyCode) {
    public Money {
        Objects.requireNonNull(amount, "amount");
        if (currencyCode == null || !currencyCode.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency must be three uppercase letters");
        }
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        if (amount.signum() <= 0 || amount.precision() > 19) {
            throw new IllegalArgumentException("Amount must be positive NUMERIC(19,2)");
        }
    }
}
