package com.finguard.transactionapi.finance.importing;

import com.finguard.transactionapi.finance.domain.EntryType;
import com.finguard.transactionapi.finance.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
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
            return new BankNormalizeResult(ImportRowOutcome.structuralRejection(
                    sourceRowNumber, ImportIssueCode.INVALID_HEADERS));
        }
        if (row.values().size() != headers.columnIndexes().size()) {
            // A missing/extra delimiter can shift fields; do not guess which cells still align.
            return new BankNormalizeResult(ImportRowOutcome.structuralRejection(
                    sourceRowNumber, ImportIssueCode.COLUMN_COUNT_MISMATCH));
        }

        List<ImportRowIssue> issues = new ArrayList<>();
        LocalDate transactionDate = null;
        String dateText = value(row, headers, "Transaction Date");
        if (DATE_SHAPE.matcher(dateText).matches()) {
            try {
                LocalDate parsed = LocalDate.parse(dateText, DATE_FORMAT);
                if (parsed.getYear() >= 1) transactionDate = parsed;
            } catch (DateTimeParseException exception) {
                // Keep only a safe issue; other fields are parsed independently below.
            }
        }
        if (transactionDate == null) {
            issues.add(new ImportRowIssue(sourceRowNumber, ImportIssueCode.INVALID_DATE));
        }

        String cad = value(row, headers, "CAD$");
        String usd = value(row, headers, "USD$");
        String currencyCode = null;
        BigDecimal amount = null;
        ImportSourceDirection direction = null;
        EntryType entryType = null;
        if (!cad.isEmpty() && !usd.isEmpty()) {
            issues.add(new ImportRowIssue(sourceRowNumber, ImportIssueCode.AMBIGUOUS_CURRENCY));
        } else if (cad.isEmpty() && usd.isEmpty()) {
            issues.add(new ImportRowIssue(sourceRowNumber, ImportIssueCode.INVALID_AMOUNT));
        } else {
            // The populated column identifies currency independently of amount correctness.
            currencyCode = cad.isEmpty() ? "USD" : "CAD";
            String amountText = cad.isEmpty() ? usd : cad;
            if (amountText.length() > MAX_AMOUNT_TEXT_LENGTH) {
                issues.add(new ImportRowIssue(sourceRowNumber, ImportIssueCode.AMOUNT_TOO_LONG));
            } else if (!AMOUNT_SHAPE.matcher(amountText).matches()) {
                issues.add(new ImportRowIssue(sourceRowNumber, ImportIssueCode.INVALID_AMOUNT));
            } else {
                BigDecimal signedAmount = new BigDecimal(amountText);
                if (signedAmount.signum() != 0) {
                    direction = signedAmount.signum() > 0
                            ? ImportSourceDirection.CREDIT : ImportSourceDirection.DEBIT;
                }
                BigDecimal magnitude = signedAmount.abs();
                try {
                    // Both signs use the existing domain invariants before any review classification.
                    new Money(magnitude, currencyCode);
                    amount = magnitude; // Preserve exact value and scale; no rounding.
                    if (direction == ImportSourceDirection.DEBIT) entryType = EntryType.EXPENSE;
                } catch (ArithmeticException | IllegalArgumentException exception) {
                    issues.add(new ImportRowIssue(sourceRowNumber, ImportIssueCode.INVALID_AMOUNT));
                }
            }
        }

        String description = (value(row, headers, "Description 1") + " "
                + value(row, headers, "Description 2")).strip();
        if (!EntryValidator.validDescription(description)) {
            description = null;
            issues.add(new ImportRowIssue(sourceRowNumber, ImportIssueCode.INVALID_DESCRIPTION));
        }

        ImportRowStatus status;
        if (!issues.isEmpty()) {
            status = ImportRowStatus.REJECTED;
        } else if (direction == ImportSourceDirection.CREDIT) {
            status = ImportRowStatus.REVIEW_REQUIRED;
            issues.add(new ImportRowIssue(sourceRowNumber, ImportIssueCode.REVIEW_REQUIRED));
        } else {
            status = ImportRowStatus.ACCEPTED;
        }
        return new BankNormalizeResult(new ImportRowOutcome(sourceRowNumber, status,
                transactionDate, null, description, amount, currencyCode, entryType,
                direction, null, issues));
    }

    private static String value(ParsedCsvRow row, HeaderValidationResult headers, String name) {
        Integer index = headers.columnIndexes().get(name);
        return index == null ? "" : stripped(row.values().get(index));
    }

    private static String stripped(String value) {
        return value == null ? "" : value.strip();
    }
}
