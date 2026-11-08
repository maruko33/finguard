package com.finguard.transactionapi.finance.importing;

import com.finguard.transactionapi.finance.domain.EntryType;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class CsvImportProcessorTest {
    private static final String HEADER =
            "Transaction Date,Description 1,Description 2,CAD$,USD$,Cheque Number";
    private final CsvImportProcessor processor = new CsvImportProcessor();

    @Test
    void processesMixedCsvWithoutLosingAnyDataRecord() {
        String csv = String.join("\r\n",
                "\uFEFF" + HEADER,
                "10/25/2026,\"Synthetic, Cafe\",Patio,-12.3400,,SYNTHETIC-REF",
                "",
                "10/24/2026,\"Line one\r\nLine two\",,-5.00,,",
                "10/23/2026,USD shop,,,-7.20,",
                "2/30/2026,Invalid date,,-1.00,,",
                "10/25/2026,Credit needing review,,25.00,,",
                "10/25/2026,Two currencies,,-1.00,-2.00,",
                "10/25/2026,Zero,,0,,",
                "10/25/2026,Lossy scale,,-1.001,,",
                "10/25/2026,Overflow,,-100000000000000000.00,,",
                "10/25/2026,Missing amount,,,,",
                "10/25/2026,Invalid amount,,not-an-amount,,",
                "10/25/2026,,,-1.00,,",
                "10/25/2026,Missing columns",
                ",,,,,");
        CsvImportResult result = process(csv);

        assertThat(result.acceptedRows()).extracting(NormalizedImportRow::sourceRowNumber)
                .containsExactly(2L, 3L, 4L);
        NormalizedImportRow cad = result.acceptedRows().getFirst();
        assertThat(cad.transactionDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(cad.description()).isEqualTo("Synthetic, Cafe Patio");
        assertThat(cad.amount()).isEqualTo(new BigDecimal("12.3400"));
        assertThat(cad.currencyCode()).isEqualTo("CAD");
        assertThat(cad.entryType()).isEqualTo(EntryType.EXPENSE);
        assertThat(cad.occurredAt()).isNull();
        assertThat(cad.bankTransactionId()).isNull();
        assertThat(result.acceptedRows().get(1).description()).isEqualTo("Line one\r\nLine two");
        assertThat(result.acceptedRows().get(2).currencyCode()).isEqualTo("USD");

        assertThat(result.reviewIssues()).containsExactly(
                new ImportRowIssue(6, ImportIssueCode.REVIEW_REQUIRED));
        assertThat(result.rejectedIssues()).extracting(ImportRowIssue::code)
                .contains(ImportIssueCode.INVALID_DATE, ImportIssueCode.AMBIGUOUS_CURRENCY,
                        ImportIssueCode.INVALID_AMOUNT, ImportIssueCode.INVALID_DESCRIPTION,
                        ImportIssueCode.COLUMN_COUNT_MISMATCH);
        Set<Long> rejected = new HashSet<>(result.rejectedIssues().stream()
                .map(ImportRowIssue::sourceRowNumber).toList());
        assertThat(rejected).containsExactlyInAnyOrder(5L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L);
        Set<Long> all = new HashSet<>(rejected);
        result.acceptedRows().forEach(row -> assertThat(all.add(row.sourceRowNumber())).isTrue());
        result.reviewIssues().forEach(issue -> assertThat(all.add(issue.sourceRowNumber())).isTrue());
        assertThat(all).containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(2, 15).boxed().toList());
    }

    @Test
    void checksCreditMoneyBeforeSeparatingReviewAndRejectedRecords() {
        CsvImportResult result = process(String.join("\n",
                HEADER,
                "10/25/2026,CAD credit,,12.3400,,",
                "10/25/2026,USD credit,,,5.00,",
                "10/25/2026,Lossy credit,,1.001,,",
                "10/25/2026,Overflowing credit,,,100000000000000000.00,",
                "10/25/2026,Purchase,,-3.50,,"));

        assertThat(result.acceptedRows()).extracting(NormalizedImportRow::sourceRowNumber)
                .containsExactly(6L);
        assertThat(result.acceptedRows()).extracting(NormalizedImportRow::entryType)
                .containsExactly(EntryType.EXPENSE);
        assertThat(result.reviewIssues()).containsExactly(
                new ImportRowIssue(2, ImportIssueCode.REVIEW_REQUIRED),
                new ImportRowIssue(3, ImportIssueCode.REVIEW_REQUIRED));
        assertThat(result.rejectedIssues()).containsExactly(
                new ImportRowIssue(4, ImportIssueCode.INVALID_AMOUNT),
                new ImportRowIssue(5, ImportIssueCode.INVALID_AMOUNT));
    }

    @Test
    void rejectsInvalidCombinedDescriptionsForPurchasesAndCredits() {
        String descriptionPart = "x".repeat(250);
        CsvImportResult result = process(String.join("\n",
                HEADER,
                "10/25/2026,,,1.00,,",
                "10/25/2026,,,-1.00,,",
                "10/25/2026,   ,   ,1.00,,",
                "10/25/2026,   ,   ,-1.00,,",
                "10/25/2026," + descriptionPart + "," + descriptionPart + ",1.00,,",
                "10/25/2026," + descriptionPart + "," + descriptionPart + ",-1.00,,"));

        assertThat(result.acceptedRows()).isEmpty();
        assertThat(result.reviewIssues()).isEmpty();
        assertThat(result.rejectedIssues()).containsExactlyElementsOf(LongStream.rangeClosed(2, 7)
                .mapToObj(sourceRowNumber ->
                        new ImportRowIssue(sourceRowNumber, ImportIssueCode.INVALID_DESCRIPTION)).toList());
    }

    @Test
    void headerFailureBlocksNormalizationAndAccountsForEveryRow() {
        CsvImportResult result = process("Date,Merchant,Amount\n"
                + "10/25/2026,Shop,-1.00\n10/25/2026,Credit,1.00");
        assertThat(result.acceptedRows()).isEmpty();
        assertThat(result.reviewIssues()).isEmpty();
        assertThat(result.rejectedIssues()).anyMatch(issue ->
                issue.sourceRowNumber() == 1 && issue.code() == ImportIssueCode.MISSING_REQUIRED_HEADER);
        assertThat(result.rejectedIssues()).filteredOn(issue -> issue.sourceRowNumber() > 1)
                .containsExactly(new ImportRowIssue(2, ImportIssueCode.INVALID_HEADERS),
                        new ImportRowIssue(3, ImportIssueCode.INVALID_HEADERS));
    }

    @Test
    void permitsHeaderOnlyCsvAndAbsentOptionalColumns() {
        String minimalHeader = "Transaction Date,Description 1,CAD$,USD$";
        assertThat(process(minimalHeader).acceptedRows()).isEmpty();
        assertThat(process(minimalHeader).rejectedIssues()).isEmpty();
        CsvImportResult result = process(minimalHeader + "\n10/25/2026,Purchase,-2.00,");
        assertThat(result.acceptedRows()).hasSize(1);
        assertThat(result.acceptedRows().getFirst().description()).isEqualTo("Purchase");
        assertThat(result.rejectedIssues()).isEmpty();
        assertThat(result.reviewIssues()).isEmpty();
    }

    @Test
    void malformedFileReturnsNoPartiallyAcceptedResult() {
        assertThatThrownBy(() -> process(HEADER + "\n10/25/2026,Valid,,-1.00,,\n\"unterminated"))
                .isInstanceOfSatisfying(CsvParseException.class,
                        error -> assertThat(error.code()).isEqualTo(ImportIssueCode.INVALID_CSV));
    }

    @Test
    void diagnosticsAndCapturedOutputDoNotExposeSyntheticSensitiveValues(CapturedOutput output) {
        String marker = "SYNTHETIC-SENSITIVE-MARKER";
        CsvImportResult result = process(HEADER + "\n10/25/2026," + marker + ",," + marker + ",,");
        assertThat(result.rejectedIssues()).containsExactly(
                new ImportRowIssue(2, ImportIssueCode.INVALID_AMOUNT));
        assertThat(result.rejectedIssues().getFirst().message()).doesNotContain(marker);
        assertThat(result.rejectedIssues().toString()).doesNotContain(marker);
        assertThat(output.getAll()).doesNotContain(marker);

        CsvImportResult badHeader = process(marker + ",Description 1,CAD$,USD$\n10/25/2026,Shop,-1,");
        assertThat(badHeader.rejectedIssues().toString()).doesNotContain(marker);
        assertThat(output.getAll()).doesNotContain(marker);
    }

    @Test
    void resultCollectionsAreDefensiveSnapshots() {
        List<String> values = new ArrayList<>(List.of("10/25/2026", "Shop", "-1.00", ""));
        ParsedCsvRow parsed = new ParsedCsvRow(2, values);
        values.set(1, "Modified");
        assertThat(parsed.values().get(1)).isEqualTo("Shop");
        assertThatThrownBy(() -> parsed.values().add("Extra")).isInstanceOf(UnsupportedOperationException.class);

        CsvImportResult result = process(HEADER + "\n10/25/2026,Shop,,-1.00,,");
        assertThatThrownBy(() -> result.acceptedRows().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.rejectedIssues().add(
                new ImportRowIssue(2, ImportIssueCode.INVALID_AMOUNT))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.reviewIssues().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private CsvImportResult process(String csv) {
        return processor.process(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), BankType.RBC_VISA);
    }
}
