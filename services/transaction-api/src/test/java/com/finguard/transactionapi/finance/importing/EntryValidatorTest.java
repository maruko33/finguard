package com.finguard.transactionapi.finance.importing;

import static org.junit.jupiter.api.Assertions.*;

import com.finguard.transactionapi.finance.domain.EntryType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EntryValidatorTest {
    private final EntryValidator validator = new EntryValidator();

    @Test
    void acceptsExactMonetaryBoundariesAndBothExistingEntryTypes() {
        for (String amount : List.of("0.01", "12.3", "12.3000", "99999999999999999.99")) {
            assertTrue(validator.validate(row(new BigDecimal(amount), "CAD", "Synthetic", EntryType.EXPENSE))
                    .isValid(), amount);
        }
        assertTrue(validator.validate(row(new BigDecimal("12.34"), "USD", "Synthetic", EntryType.INCOME))
                .isValid());
        assertTrue(validator.validate(row(BigDecimal.ONE, "CAD", "a".repeat(500), EntryType.EXPENSE))
                .isValid());
    }

    @Test
    void rejectsNonpositiveLossyAndOverflowAmountsWithoutSilentRounding() {
        for (String amount : List.of("0", "-1", "1.001", "0.0001", "100000000000000000",
                "99999999999999999.999")) {
            var result = validator.validate(row(new BigDecimal(amount), "CAD", "Synthetic", EntryType.EXPENSE));
            assertEquals(List.of(new ImportRowIssue(42, ImportIssueCode.INVALID_AMOUNT)), result.issues(), amount);
        }
    }

    @Test
    void rejectsExtremeExponentsBeforeMoneyScaleConversionCanAllocateUnboundedPowers() {
        assertTimeout(Duration.ofSeconds(1), () -> {
            for (int scale : List.of(Integer.MIN_VALUE, Integer.MAX_VALUE)) {
                var amount = new BigDecimal(BigInteger.ONE, scale);
                var result = validator.validate(row(amount, "CAD", "Synthetic", EntryType.EXPENSE));
                assertEquals(List.of(new ImportRowIssue(42, ImportIssueCode.INVALID_AMOUNT)), result.issues());
            }
        });
    }

    @Test
    void rejectsInvalidIsoCurrencyWithoutMaskingAnIndependentlyInvalidAmount() {
        for (String currency : new String[] {null, "", "cad", " CAD", "ZZZ", "US$", "USDD"}) {
            var result = validator.validate(row(BigDecimal.ONE, currency, "Synthetic", EntryType.EXPENSE));
            assertEquals(List.of(new ImportRowIssue(42, ImportIssueCode.INVALID_CURRENCY)), result.issues());
        }
        var result = validator.validate(row(new BigDecimal("-1"), "ZZZ", "Synthetic", EntryType.EXPENSE));
        assertEquals(Set.of(ImportIssueCode.INVALID_AMOUNT, ImportIssueCode.INVALID_CURRENCY), codes(result));
    }

    @Test
    void returnsStructuredIssuesForAllMissingRequiredFields() {
        var candidate = new NormalizedImportRow(42, null, null, null, null, null, null, null);
        var result = validator.validate(candidate);
        assertFalse(result.isValid());
        assertEquals(Set.of(ImportIssueCode.INVALID_DATE, ImportIssueCode.INVALID_AMOUNT,
                ImportIssueCode.INVALID_CURRENCY, ImportIssueCode.INVALID_ENTRY_TYPE,
                ImportIssueCode.INVALID_DESCRIPTION), codes(result));
        assertTrue(result.issues().stream().allMatch(issue -> issue.sourceRowNumber() == 42));
    }

    @Test
    void rejectsBlankOrOverlongDescriptionsWithoutEchoingThem() {
        for (String description : List.of("", "  ", "\n\r\t", "SYNTHETIC-PRIVATE-".repeat(40))) {
            var result = validator.validate(row(BigDecimal.ONE, "CAD", description, EntryType.EXPENSE));
            assertEquals(List.of(new ImportRowIssue(42, ImportIssueCode.INVALID_DESCRIPTION)), result.issues());
            assertTrue(result.issues().stream().noneMatch(issue -> issue.message().contains("SYNTHETIC-PRIVATE")));
        }
    }

    @Test
    void validationDoesNotMutateOrRoundCandidateAndRequiresNoAccountContext() {
        BigDecimal amount = new BigDecimal("12.3000");
        var candidate = new NormalizedImportRow(42, LocalDate.of(2026, 10, 10),
                Instant.parse("2026-10-10T12:34:56Z"), "  Synthetic merchant  ", amount,
                "USD", EntryType.EXPENSE, null);
        var snapshot = new NormalizedImportRow(candidate.sourceRowNumber(), candidate.transactionDate(),
                candidate.occurredAt(), candidate.description(), candidate.amount(), candidate.currencyCode(),
                candidate.entryType(), candidate.bankTransactionId());

        assertTrue(validator.validate(candidate).isValid());
        assertEquals(snapshot, candidate);
        assertSame(amount, candidate.amount());
        assertEquals(4, candidate.amount().scale());
        assertEquals("  Synthetic merchant  ", candidate.description());
        assertNull(candidate.bankTransactionId());
    }

    @Test
    void treatsNullCandidateAsAProgrammingError() {
        assertThrows(NullPointerException.class, () -> validator.validate(null));
    }

    private static NormalizedImportRow row(BigDecimal amount, String currency, String description, EntryType type) {
        return new NormalizedImportRow(42, LocalDate.of(2026, 10, 10), null, description,
                amount, currency, type, null);
    }

    private static Set<ImportIssueCode> codes(EntryValidationResult result) {
        return result.issues().stream().map(ImportRowIssue::code).collect(Collectors.toSet());
    }
}
