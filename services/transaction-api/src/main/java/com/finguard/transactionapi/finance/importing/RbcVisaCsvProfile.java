package com.finguard.transactionapi.finance.importing;

import com.finguard.transactionapi.finance.domain.EntryType;
import com.finguard.transactionapi.finance.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** The supported RBC Visa export shape; positive credits require human review. */
public final class RbcVisaCsvProfile implements BankCsvProfile {
    private static final int MAX_AMOUNT_TEXT_LENGTH = 128;
    private static final List<String> REQUIRED_HEADERS =
            List.of("Transaction Date", "Description 1", "CAD$", "USD$");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("M/d/uuuu", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern DATE_SHAPE = Pattern.compile("[0-9]{1,2}/[0-9]{1,2}/[0-9]{4}");
    private static final Pattern AMOUNT_SHAPE = Pattern.compile("[+-]?[0-9]+(?:\\.[0-9]+)?");

    @Override
    public BankType bankType() {
        return BankType.RBC_VISA;
    }

    @Override
    public HeaderValidationResult validateHeaders(List<String> headers) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        Set<ImportIssueCode> codes = new LinkedHashSet<>();
        if (headers == null || headers.isEmpty()) {
            codes.add(ImportIssueCode.INVALID_HEADERS);
        } else {
            for (int index = 0; index < headers.size(); index++) {
                String name = stripped(headers.get(index));
                if (index == 0 && name.startsWith("\uFEFF")) {
                    name = name.substring(1).strip();
                }
                if (name.isEmpty()) {
                    codes.add(ImportIssueCode.INVALID_HEADERS);
                }
                if (indexes.putIfAbsent(name, index) != null) {
                    codes.add(ImportIssueCode.DUPLICATE_HEADER);
                }
            }
        }
        if (!indexes.keySet().containsAll(REQUIRED_HEADERS)) {
            codes.add(ImportIssueCode.MISSING_REQUIRED_HEADER);
        }
        return new HeaderValidationResult(indexes,
                codes.stream().map(code -> new ImportRowIssue(1, code)).toList());
    }

    @Override
    public BankNormalizeResult normalize(ParsedCsvRow row, HeaderValidationResult headers) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(headers, "headers");
        long sourceRowNumber = row.sourceRowNumber();
        if (!headers.isValid()) {
            return BankNormalizeResult.issue(sourceRowNumber, ImportIssueCode.INVALID_HEADERS);
        }
        if (row.values().size() != headers.columnIndexes().size()) {
            return BankNormalizeResult.issue(sourceRowNumber, ImportIssueCode.COLUMN_COUNT_MISMATCH);
        }

        LocalDate transactionDate;
        String dateText = value(row, headers, "Transaction Date");
        if (!DATE_SHAPE.matcher(dateText).matches()) {
            return BankNormalizeResult.issue(sourceRowNumber, ImportIssueCode.INVALID_DATE);
        }
        try {
            transactionDate = LocalDate.parse(dateText, DATE_FORMAT);
            if (transactionDate.getYear() < 1) {
                return BankNormalizeResult.issue(sourceRowNumber, ImportIssueCode.INVALID_DATE);
            }
        } catch (DateTimeParseException exception) {
            return BankNormalizeResult.issue(sourceRowNumber, ImportIssueCode.INVALID_DATE);
        }

        String cad = value(row, headers, "CAD$");
        String usd = value(row, headers, "USD$");
        if (!cad.isEmpty() && !usd.isEmpty()) {
            return BankNormalizeResult.issue(sourceRowNumber, ImportIssueCode.AMBIGUOUS_CURRENCY);
        }
        String amountText = cad.isEmpty() ? usd : cad;
        // Bound arbitrary-precision conversion work independently of the whole-file byte limit.
        if (amountText.length() > MAX_AMOUNT_TEXT_LENGTH) {
            return BankNormalizeResult.issue(sourceRowNumber, ImportIssueCode.AMOUNT_TOO_LONG);
        }
        if (!AMOUNT_SHAPE.matcher(amountText).matches()) {
            return BankNormalizeResult.issue(sourceRowNumber, ImportIssueCode.INVALID_AMOUNT);
        }
        BigDecimal signedAmount;
        try {
            signedAmount = new BigDecimal(amountText);
        } catch (NumberFormatException exception) {
            return BankNormalizeResult.issue(sourceRowNumber, ImportIssueCode.INVALID_AMOUNT);
        }
        String currencyCode = cad.isEmpty() ? "USD" : "CAD";
        BigDecimal magnitude = signedAmount.abs();
        try {
            // Validate both signs with the domain rules before classifying; keep the original scale.
            new Money(magnitude, currencyCode);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return BankNormalizeResult.issue(sourceRowNumber, ImportIssueCode.INVALID_AMOUNT);
        }

        String description1 = value(row, headers, "Description 1");
        String description2 = value(row, headers, "Description 2");
        String description = (description1 + " " + description2).strip();
        if (!EntryValidator.validDescription(description)) {
            return BankNormalizeResult.issue(sourceRowNumber, ImportIssueCode.INVALID_DESCRIPTION);
        }
        if (signedAmount.signum() > 0) {
            return BankNormalizeResult.issue(sourceRowNumber, ImportIssueCode.REVIEW_REQUIRED);
        }
        return BankNormalizeResult.candidate(new NormalizedImportRow(sourceRowNumber,
                transactionDate, null, description, magnitude, currencyCode, EntryType.EXPENSE, null));
    }

    private static String value(ParsedCsvRow row, HeaderValidationResult headers, String name) {
        Integer index = headers.columnIndexes().get(name);
        return index == null ? "" : stripped(row.values().get(index));
    }

    private static String stripped(String value) {
        return value == null ? "" : value.strip();
    }
}
