package com.finguard.transactionapi.finance.importing;

import com.finguard.transactionapi.finance.domain.EntryType;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@ExtendWith(OutputCaptureExtension.class)
class CsvPreviewOutcomeTest {
    private static final String HEADER = "Transaction Date,Description 1,CAD$,USD$";

    @Test
    void validCreditRetainsDisplayableFactsWithoutBecomingAnAcceptedEntry() {
        String csv = "Transaction Date,Description 1,CAD$,USD$\n"
                + "11/1/2026,Synthetic credit,12.3400,";
        CsvImportResult result = new CsvImportProcessor().process(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), BankType.RBC_VISA);
        var json = JsonMapper.builder().build().valueToTree(result);

        assertThat(result.acceptedRows()).isEmpty();
        assertThat(result.reviewIssues()).containsExactly(
                new ImportRowIssue(2, ImportIssueCode.REVIEW_REQUIRED));
        assertAll(
                () -> assertThat(json.findValuesAsString("transactionDate")).containsExactly("2026-11-01"),
                () -> assertThat(json.findValuesAsString("description")).containsExactly("Synthetic credit"),
                () -> assertThat(json.findValuesAsString("amount")).singleElement().satisfies(
                        value -> assertThat(new BigDecimal(value)).isEqualByComparingTo("12.34")));
        ImportRowOutcome row = result.rows().getFirst();
        assertThat(row.status()).isEqualTo(ImportRowStatus.REVIEW_REQUIRED);
        assertThat(row.sourceDirection()).isEqualTo(ImportSourceDirection.CREDIT);
        assertThat(row.entryType()).isNull();
        assertThat(row.amount()).isEqualByComparingTo("12.34");
        assertThat(row.currencyCode()).isEqualTo("CAD");
    }

    @Test
    void invalidDateRetainsOtherValidatedTransactionFacts() {
        CsvImportResult result = process("Transaction Date,Description 1,Description 2,CAD$,USD$,Cheque Number\n"
                + "2/30/2026,Synthetic shop,North,-3.5000,,SYNTHETIC-REF");

        ImportRowOutcome row = result.rows().getFirst();
        assertThat(row.sourceRowNumber()).isEqualTo(2);
        assertThat(row.status()).isEqualTo(ImportRowStatus.REJECTED);
        assertThat(row.transactionDate()).isNull();
        assertThat(row.description()).isEqualTo("Synthetic shop North");
        assertThat(row.amount()).isEqualTo(new BigDecimal("3.5000"));
        assertThat(row.currencyCode()).isEqualTo("CAD");
        assertThat(row.sourceDirection()).isEqualTo(ImportSourceDirection.DEBIT);
        assertThat(row.entryType()).isEqualTo(EntryType.EXPENSE);
        assertThat(row.occurredAt()).isNull();
        assertThat(row.bankTransactionId()).isNull();
        assertThat(row.issues()).containsExactly(new ImportRowIssue(2, ImportIssueCode.INVALID_DATE));
        assertThat(result.totalCount()).isEqualTo(1);
        assertThat(result.rejectedCount()).isEqualTo(1);
        assertThat(result.acceptedRows()).isEmpty();
    }

    @Test
    void mixedInputPreservesSourceOrderAndCountsRowsInsteadOfIssues() {
        CsvImportResult result = process(String.join("\n", HEADER,
                "11/1/2026,Purchase,-2.00,",
                "",
                "11/1/2026,Credit,,5.00",
                "2/30/2026,,0.001,",
                "11/1/2026,Ambiguous,-1.00,-2.00",
                "11/1/2026,\"Multi\nline purchase\",,-3.00",
                "11/1/2026,No amount,,"));

        assertThat(result.rows()).extracting(ImportRowOutcome::sourceRowNumber)
                .containsExactly(2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(result.rows()).extracting(ImportRowOutcome::status).containsExactly(
                ImportRowStatus.ACCEPTED, ImportRowStatus.REVIEW_REQUIRED, ImportRowStatus.REJECTED,
                ImportRowStatus.REJECTED, ImportRowStatus.ACCEPTED, ImportRowStatus.REJECTED);
        assertThat(result.totalCount()).isEqualTo(6);
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(result.reviewRequiredCount()).isEqualTo(1);
        assertThat(result.rejectedCount()).isEqualTo(3);
        assertThat(result.acceptedCount() + result.reviewRequiredCount() + result.rejectedCount())
                .isEqualTo(result.totalCount());
        assertThat(result.rows().get(2).issues()).extracting(ImportRowIssue::code).containsExactlyInAnyOrder(
                ImportIssueCode.INVALID_DATE, ImportIssueCode.INVALID_AMOUNT, ImportIssueCode.INVALID_DESCRIPTION);
        assertThat(result.rejectedIssues()).hasSize(5);
        assertThat(result.acceptedRows()).extracting(NormalizedImportRow::sourceRowNumber).containsExactly(2L, 6L);
        assertThat(result.acceptedRows()).extracting(NormalizedImportRow::entryType)
                .containsOnly(EntryType.EXPENSE);
        assertThat(result.rows().get(4).description()).isEqualTo("Multi\nline purchase");
        assertThat(result.rows().get(4).currencyCode()).isEqualTo("USD");
        assertThat(result.reviewIssues()).containsExactly(new ImportRowIssue(3, ImportIssueCode.REVIEW_REQUIRED));
        assertThat(result.headerIssues()).isEmpty();

        ImportRowOutcome ambiguous = result.rows().get(3);
        assertThat(ambiguous.amount()).isNull();
        assertThat(ambiguous.currencyCode()).isNull();
        assertThat(ambiguous.sourceDirection()).isNull();
        assertThat(ambiguous.entryType()).isNull();
        assertThat(ambiguous.transactionDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(ambiguous.description()).isEqualTo("Ambiguous");
    }

    @Test
    void invalidPositiveAmountsRemainRejectedWhileKnownCurrencyAndDirectionSurvive() {
        CsvImportResult result = process(HEADER + "\n11/1/2026,Lossy CAD,0.001,\n"
                + "11/1/2026,Overflow USD,,100000000000000000.00");

        assertThat(result.rows()).extracting(ImportRowOutcome::currencyCode).containsExactly("CAD", "USD");
        for (ImportRowOutcome row : result.rows()) {
            assertThat(row.status()).isEqualTo(ImportRowStatus.REJECTED);
            assertThat(row.amount()).isNull();
            assertThat(row.entryType()).isNull();
            assertThat(row.sourceDirection()).isEqualTo(ImportSourceDirection.CREDIT);
            assertThat(row.transactionDate()).isEqualTo(LocalDate.of(2026, 11, 1));
            assertThat(row.description()).isNotBlank();
            assertThat(row.issues()).containsExactly(new ImportRowIssue(row.sourceRowNumber(), ImportIssueCode.INVALID_AMOUNT));
        }
        assertThat(result.reviewRequiredCount()).isZero();
        assertThat(result.reviewIssues()).isEmpty();
        assertThat(result.acceptedRows()).isEmpty();
    }

    @Test
    void invalidDescriptionsBecomeNullWithoutLosingOtherFactsForEitherSign() {
        CsvImportResult result = process(String.join("\n", HEADER,
                "11/1/2026,   ,-1.00,",
                "11/1/2026," + "x".repeat(501) + ",,2.00"));

        assertThat(result.rows()).extracting(ImportRowOutcome::sourceDirection)
                .containsExactly(ImportSourceDirection.DEBIT, ImportSourceDirection.CREDIT);
        for (ImportRowOutcome row : result.rows()) {
            assertThat(row.status()).isEqualTo(ImportRowStatus.REJECTED);
            assertThat(row.description()).isNull();
            assertThat(row.transactionDate()).isEqualTo(LocalDate.of(2026, 11, 1));
            assertThat(row.amount()).isPositive();
            assertThat(row.currencyCode()).isNotBlank();
            assertThat(row.issues()).containsExactly(new ImportRowIssue(row.sourceRowNumber(), ImportIssueCode.INVALID_DESCRIPTION));
        }
        assertThat(result.reviewIssues()).isEmpty();
        assertThat(result.acceptedRows()).isEmpty();
    }

    @Test
    void headerAndWidthErrorsDoNotGuessTransactionFieldsOrCountHeadersAsData() {
        CsvImportResult badHeaders = process(HEADER + ", CAD$ \n"
                + "11/1/2026,Shop,-1.00,,\n11/1/2026,Credit,2.00,,");
        assertThat(badHeaders.headerIssues()).containsExactly(new ImportRowIssue(1, ImportIssueCode.DUPLICATE_HEADER));
        assertThat(badHeaders.totalCount()).isEqualTo(2);
        assertThat(badHeaders.rejectedCount()).isEqualTo(2);
        assertThat(badHeaders.rows()).extracting(ImportRowOutcome::sourceRowNumber).containsExactly(2L, 3L);
        for (ImportRowOutcome row : badHeaders.rows()) {
            assertNoTransactionFields(row);
            assertThat(row.issues()).containsExactly(new ImportRowIssue(row.sourceRowNumber(), ImportIssueCode.INVALID_HEADERS));
        }

        CsvImportResult badWidths = process(HEADER + "\n11/1/2026,Shop,-1.00\n11/1/2026,Credit,2.00,,extra");
        assertThat(badWidths.headerIssues()).isEmpty();
        assertThat(badWidths.rows()).hasSize(2);
        for (ImportRowOutcome row : badWidths.rows()) {
            assertNoTransactionFields(row);
            assertThat(row.issues()).containsExactly(new ImportRowIssue(row.sourceRowNumber(), ImportIssueCode.COLUMN_COUNT_MISMATCH));
        }
    }

    @Test
    void diagnosticsDoNotLeakRetainedOrRejectedSyntheticSensitiveValues(CapturedOutput output) {
        String marker = "SYNTHETIC-SENSITIVE-MARKER";
        CsvImportResult result = process(HEADER + ",Unused private column\n"
                + "11/1/2026," + marker + ",-4.00,," + marker + "\n"
                + "11/1/2026," + marker + ",2.00,," + marker + "\n"
                + marker + "," + marker + ",not-an-amount,," + marker);

        assertThat(result.rows()).extracting(ImportRowOutcome::description).containsOnly(marker);
        assertThat(result.toString()).doesNotContain(marker);
        assertThat(result.rows().toString()).doesNotContain(marker);
        assertThat(result.rejectedIssues().toString()).doesNotContain(marker);
        assertThat(result.reviewIssues().toString()).doesNotContain(marker);
        assertThat(output.getAll()).doesNotContain(marker);
        assertThat(result.rows()).allSatisfy(row -> assertThat(row.bankTransactionId()).isNull());
    }

    @Test
    void immutableAuthoritativeRowsKeepDerivedViewsAndCountsConsistent() {
        CsvImportResult result = process(HEADER + "\n11/1/2026,Shop,-1.00,\n11/1/2026,Credit,2.00,");
        var mutableRows = new ArrayList<>(result.rows());
        CsvImportResult snapshot = new CsvImportResult(mutableRows, List.of());
        mutableRows.clear();

        assertThat(snapshot.totalCount()).isEqualTo(2);
        assertThat(snapshot.acceptedCount()).isEqualTo(snapshot.acceptedRows().size());
        assertThat(snapshot.reviewRequiredCount()).isEqualTo(1);
        assertThat(snapshot.rejectedCount()).isZero();
        assertThat(snapshot.rows()).isEqualTo(result.rows());
        assertThatThrownBy(() -> snapshot.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.acceptedRows().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.reviewIssues().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsDuplicateOutOfOrderOrMisattributedSourceRecords() {
        CsvImportResult result = process(HEADER + "\n11/1/2026,Shop,-1.00,\n11/1/2026,Credit,2.00,");
        ImportRowOutcome first = result.rows().getFirst();
        ImportRowOutcome second = result.rows().getLast();
        assertThatThrownBy(() -> new CsvImportResult(List.of(first, first), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CsvImportResult(List.of(second, first), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImportRowOutcome(2, ImportRowStatus.REJECTED,
                null, null, null, null, null, null, null, null,
                List.of(new ImportRowIssue(3, ImportIssueCode.INVALID_DATE))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CsvImportResult process(String csv) {
        return new CsvImportProcessor().process(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), BankType.RBC_VISA);
    }

    private static void assertNoTransactionFields(ImportRowOutcome row) {
        assertThat(row.status()).isEqualTo(ImportRowStatus.REJECTED);
        assertThat(row.transactionDate()).isNull();
        assertThat(row.occurredAt()).isNull();
        assertThat(row.description()).isNull();
        assertThat(row.amount()).isNull();
        assertThat(row.currencyCode()).isNull();
        assertThat(row.entryType()).isNull();
        assertThat(row.sourceDirection()).isNull();
        assertThat(row.bankTransactionId()).isNull();
    }
}
