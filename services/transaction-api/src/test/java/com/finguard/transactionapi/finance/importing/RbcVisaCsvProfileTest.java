package com.finguard.transactionapi.finance.importing;

import static org.junit.jupiter.api.Assertions.*;

import com.finguard.transactionapi.finance.domain.EntryType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RbcVisaCsvProfileTest {
    private static final List<String> REQUIRED =
            List.of("Transaction Date", "Description 1", "CAD$", "USD$");
    private final RbcVisaCsvProfile profile = new RbcVisaCsvProfile();

    @Test
    void normalizesCadPurchaseAndDescriptionWithoutTreatingChequeAsTransactionId() {
        var headers = profile.validateHeaders(List.of("Transaction Date", "Description 1",
                "Description 2", "CAD$", "USD$", "Cheque Number", "Extra Export Column"));
        var result = profile.normalize(new ParsedCsvRow(9, List.of("10/10/2026", "  Groceries ",
                " Toronto  ", "-12.30", "", "SYNTHETIC-CHEQUE", "unused")), headers);
        var row = result.candidate().orElseThrow();

        assertEquals(BankType.RBC_VISA, profile.bankType());
        assertTrue(result.issues().isEmpty());
        assertEquals(9, row.sourceRowNumber());
        assertEquals(LocalDate.of(2026, 10, 10), row.transactionDate());
        assertEquals("Groceries Toronto", row.description());
        assertEquals(new BigDecimal("12.30"), row.amount());
        assertEquals("CAD", row.currencyCode());
        assertEquals(EntryType.EXPENSE, row.entryType());
        assertNull(row.occurredAt());
        assertNull(row.bankTransactionId());
    }

    @Test
    void preservesUsdAndNormalizesOuterHeaderWhitespaceAndLeadingBom() {
        var headers = profile.validateHeaders(
                List.of(" \uFEFF Transaction Date ", " Description 1 ", "CAD$ ", " USD$"));
        assertTrue(headers.isValid());
        var row = profile.normalize(new ParsedCsvRow(2,
                List.of(" 2/29/2024 ", " Synthetic merchant ", " ", " -3.45 ")), headers)
                .candidate().orElseThrow();
        assertEquals("USD", row.currencyCode());
        assertEquals(new BigDecimal("3.45"), row.amount());
        assertEquals("Synthetic merchant", row.description());
    }

    @Test
    void allowsMissingOrEmptyOptionalCellsAndPreservesEmbeddedDescriptionNewlines() {
        var headers = profile.validateHeaders(List.of("Transaction Date", "Description 1",
                "Description 2", "CAD$", "USD$", "Cheque Number"));
        var row = profile.normalize(new ParsedCsvRow(2,
                List.of("1/2/2026", " Synthetic, merchant\nsecond line ", "", "-5", "", "")), headers)
                .candidate().orElseThrow();
        assertEquals("Synthetic, merchant\nsecond line", row.description());
        assertNull(row.bankTransactionId());
    }

    @Test
    void rejectsInvalidCalendarDatesAndNoncontractDateFormatsStrictly() {
        for (String date : List.of("2/29/2025", "4/31/2026", "13/1/2026", "0/1/2026",
                "1/0/2026", "2026-10-10", "10/10/26", "10/10/0000",
                "10/10/+10000", "10/10/-0001", "10/10/2026 trailing")) {
            assertIssue(normalize(date, "-1", ""), ImportIssueCode.INVALID_DATE, 17);
        }
        assertTrue(normalize("02/29/2024", "-1", "").candidate().isPresent());
    }

    @Test
    void rejectsInvalidAmountSyntaxAndZeroWithoutGuessingFormats() {
        for (String amount : List.of("not-an-amount", "-1e2", "-1,234.56", "CAD -12.30",
                "(12.30)", "-.50", "-12.", "0", "-0.00", "+0.00", "-NaN")) {
            assertIssue(normalize("10/10/2026", amount, ""), ImportIssueCode.INVALID_AMOUNT, 17);
        }
    }

    @Test
    void sendsValidPositiveCreditsToReviewWithoutGuessingTheirClassification() {
        for (String credit : List.of("12.30", "+12.30", "0.01", "12.3000", "99999999999999999.99")) {
            var result = normalize("10/10/2026", credit, "");
            assertIssue(result, ImportIssueCode.REVIEW_REQUIRED, 17);
            assertTrue(result.candidate().isEmpty());
            assertIssue(normalize("10/10/2026", "", credit), ImportIssueCode.REVIEW_REQUIRED, 17);
        }
    }

    @Test
    void rejectsBothPopulatedCurrenciesAndNoAmount() {
        assertIssue(normalize("10/10/2026", "-5.00", "-2.00"),
                ImportIssueCode.AMBIGUOUS_CURRENCY, 17);
        assertIssue(normalize("10/10/2026", "0", "-2.00"),
                ImportIssueCode.AMBIGUOUS_CURRENCY, 17);
        assertIssue(normalize("10/10/2026", " ", ""),
                ImportIssueCode.INVALID_AMOUNT, 17);
    }

    @Test
    void rejectsMissingWrongBankAndCaseOrInternalWhitespaceMismatchedHeaders() {
        for (List<String> names : List.of(
                List.of("Date", "Description", "Debit", "Credit"),
                List.of("Transaction Date", "Description 1", "CAD$"),
                List.of("transaction date", "Description 1", "CAD$", "USD$"),
                List.of("Transaction  Date", "Description 1", "CAD$", "USD$"))) {
            var result = profile.validateHeaders(names);
            assertFalse(result.isValid());
            assertTrue(result.issues().stream()
                    .anyMatch(issue -> issue.code() == ImportIssueCode.MISSING_REQUIRED_HEADER));
            assertTrue(result.issues().stream().allMatch(issue -> issue.sourceRowNumber() == 1));
        }
    }

    @Test
    void rejectsAmbiguousDuplicatesIncludingUnknownColumnsAfterWhitespaceNormalization() {
        for (String duplicate : List.of(" CAD$ ", " Unknown ")) {
            var names = new ArrayList<>(REQUIRED);
            names.add("Unknown");
            names.add(duplicate);
            var result = profile.validateHeaders(names);
            assertFalse(result.isValid());
            assertTrue(result.issues().stream()
                    .anyMatch(issue -> issue.code() == ImportIssueCode.DUPLICATE_HEADER));
        }
    }

    @Test
    void rejectsEmptyHeadersAndBlankHeaderNames() {
        for (List<String> headers : List.of(List.<String>of(),
                List.of("Transaction Date", "Description 1", "CAD$", "USD$", " "),
                Arrays.asList("Transaction Date", "Description 1", "CAD$", "USD$", null))) {
            assertTrue(profile.validateHeaders(headers).issues().stream()
                    .anyMatch(issue -> issue.code() == ImportIssueCode.INVALID_HEADERS));
        }
        assertFalse(profile.validateHeaders(null).isValid());
    }

    @Test
    void rejectsInvalidHeadersOrWrongRecordWidthWithoutLosingSourceRecordNumber() {
        var source = new ParsedCsvRow(42, List.of("10/10/2026", "Synthetic", "-1", ""));
        assertIssue(profile.normalize(source, profile.validateHeaders(List.of("wrong"))),
                ImportIssueCode.INVALID_HEADERS, 42);
        var headers = profile.validateHeaders(REQUIRED);
        for (List<String> values : List.of(List.of("10/10/2026", "Synthetic", "-1"),
                List.of("10/10/2026", "Synthetic", "-1", "", "extra"))) {
            assertIssue(profile.normalize(new ParsedCsvRow(42, values), headers),
                    ImportIssueCode.COLUMN_COUNT_MISMATCH, 42);
        }
    }

    @Test
    void rejectsInvalidNegativeAmountsAndPreservesLosslessMagnitude() {
        var validator = new EntryValidator();
        for (String amount : List.of("-1.001", "-100000000000000000")) {
            assertIssue(normalize("10/10/2026", amount, ""), ImportIssueCode.INVALID_AMOUNT, 17);
            assertIssue(normalize("10/10/2026", "", amount), ImportIssueCode.INVALID_AMOUNT, 17);
        }
        var lossless = normalize("10/10/2026", "-12.3000", "").candidate().orElseThrow();
        assertEquals(4, lossless.amount().scale());
        assertTrue(validator.validate(lossless).isValid());
    }

    @Test
    void issueMessagesNeverEchoSyntheticSensitiveInput() {
        String marker = "SYNTHETIC-ACCOUNT-1234";
        for (BankNormalizeResult result : List.of(normalize(marker, "-1", ""),
                normalize("10/10/2026", marker, ""))) {
            assertTrue(result.candidate().isEmpty());
            assertFalse(result.issues().toString().contains(marker));
            assertTrue(result.issues().stream().noneMatch(issue -> issue.message().contains(marker)));
        }
        var names = new ArrayList<>(REQUIRED);
        names.add(marker);
        names.add(marker);
        assertTrue(profile.validateHeaders(names).issues().stream()
                .noneMatch(issue -> issue.message().contains(marker)));
    }


    @Test
    void boundsNumericTextBeforeBigDecimalConversionIncludingPositiveCredits() {
        var exact = normalize("10/10/2026", "-1." + "0".repeat(125), "").candidate().orElseThrow();
        assertTrue(new EntryValidator().validate(exact).isValid());
        assertIssue(normalize("10/10/2026", "1." + "0".repeat(126), ""),
                ImportIssueCode.REVIEW_REQUIRED, 17);
        for (String amount : List.of("-1." + "0".repeat(126), "1." + "0".repeat(127),
                "-" + "9".repeat(200_000), "9".repeat(200_000))) {
            assertIssue(normalize("10/10/2026", amount, ""), ImportIssueCode.AMOUNT_TOO_LONG, 17);
        }
    }


    @Test
    void rejectsLossyPositiveAmountsBeforeReviewClassification() {
        for (String amount : List.of("0.001", "+12.345", "99999999999999999.999")) {
            assertIssue(normalize("10/10/2026", amount, ""), ImportIssueCode.INVALID_AMOUNT, 17);
            assertIssue(normalize("10/10/2026", "", amount), ImportIssueCode.INVALID_AMOUNT, 17);
        }
    }

    @Test
    void rejectsOverflowingPositiveAmountsBeforeReviewClassification() {
        for (String amount : List.of("100000000000000000", "+100000000000000000.00")) {
            assertIssue(normalize("10/10/2026", amount, ""), ImportIssueCode.INVALID_AMOUNT, 17);
            assertIssue(normalize("10/10/2026", "", amount), ImportIssueCode.INVALID_AMOUNT, 17);
        }
    }

    @Test
    void rejectsMissingBlankAndOverlongDescriptionsForEitherAmountSign() {
        var headers = profile.validateHeaders(REQUIRED);
        for (String amount : List.of("1.00", "-1.00")) {
            for (String description : List.of("", " \t\r\n ", "x".repeat(501))) {
                for (List<String> values : List.of(
                        List.of("10/10/2026", description, amount, ""),
                        List.of("10/10/2026", description, "", amount))) {
                    assertIssue(profile.normalize(new ParsedCsvRow(42, values), headers),
                            ImportIssueCode.INVALID_DESCRIPTION, 42);
                }
            }
        }
    }

    @Test
    void validatesCombinedDescriptionBeforeReviewAndPurchaseClassification() {
        var headers = profile.validateHeaders(List.of("Transaction Date", "Description 1",
                "Description 2", "CAD$", "USD$"));
        for (String amount : List.of("1.00", "-1.00")) {
            for (String description : List.of("Supplemental description", "x".repeat(500))) {
                var result = profile.normalize(new ParsedCsvRow(42,
                        List.of("10/10/2026", " ", description, amount, "")), headers);
                if (amount.startsWith("-")) {
                    assertEquals(description, result.candidate().orElseThrow().description());
                    assertTrue(result.issues().isEmpty());
                } else {
                    assertIssue(result, ImportIssueCode.REVIEW_REQUIRED, 42);
                }
            }
            assertIssue(profile.normalize(new ParsedCsvRow(42,
                    List.of("10/10/2026", "x".repeat(499), "y", amount, "")), headers),
                    ImportIssueCode.INVALID_DESCRIPTION, 42);
        }
    }

    private BankNormalizeResult normalize(String date, String cad, String usd) {
        return profile.normalize(new ParsedCsvRow(17, List.of(date, "Synthetic merchant", cad, usd)),
                profile.validateHeaders(REQUIRED));
    }

    private static void assertIssue(BankNormalizeResult result, ImportIssueCode code, long source) {
        assertTrue(result.candidate().isEmpty());
        assertEquals(List.of(new ImportRowIssue(source, code)), result.issues());
    }
}
