package com.finguard.transactionapi.finance.importing;

import com.finguard.transactionapi.finance.domain.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/** Validates complete import candidates without database or account context. */
public final class EntryValidator {
    public EntryValidationResult validate(NormalizedImportRow row) {
        Objects.requireNonNull(row, "row");
        List<ImportRowIssue> issues = new ArrayList<>();
        boolean validCurrency = validCurrency(row.currencyCode());
        if (!validCurrency) {
            issues.add(issue(row, ImportIssueCode.INVALID_CURRENCY));
        }
        // A known currency lets Money validate the amount independently when the supplied currency is invalid.
        if (!validAmount(row.amount(), validCurrency ? row.currencyCode() : "CAD")) {
            issues.add(issue(row, ImportIssueCode.INVALID_AMOUNT));
        }
        if (row.entryType() == null) {
            issues.add(issue(row, ImportIssueCode.INVALID_ENTRY_TYPE));
        }
        if (row.transactionDate() == null) {
            issues.add(issue(row, ImportIssueCode.INVALID_DATE));
        }
        if (!validDescription(row.description())) {
            issues.add(issue(row, ImportIssueCode.INVALID_DESCRIPTION));
        }
        return new EntryValidationResult(issues);
    }

    // Shared with profiles that must validate content before deciding its transaction type.
    static boolean validDescription(String description) {
        return description != null && !description.isBlank() && description.length() <= 500;
    }

    private static boolean validCurrency(String currencyCode) {
        if (currencyCode == null || !currencyCode.matches("[A-Z]{3}")) {
            return false;
        }
        try {
            Currency.getInstance(currencyCode);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean validAmount(BigDecimal amount, String currencyCode) {
        if (amount == null) {
            return false;
        }
        // Bound exponent expansion before Money.setScale; Money owns the monetary invariants.
        long scaleChange = 2L - amount.scale();
        if (scaleChange > 19 || scaleChange < -(long) amount.precision()) {
            return false;
        }
        try {
            new Money(amount, currencyCode);
            return true;
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return false;
        }
    }

    private static ImportRowIssue issue(NormalizedImportRow row, ImportIssueCode code) {
        return new ImportRowIssue(row.sourceRowNumber(), code);
    }
}
