package com.finguard.transactionapi.finance.importing;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Internal, in-memory orchestration only: no Spring context, persistence, or external calls. */
public final class CsvImportProcessor {
    private final CsvParser parser;
    private final EntryValidator validator = new EntryValidator();

    public CsvImportProcessor() {
        this(new CsvParser());
    }

    public CsvImportProcessor(CsvParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public CsvImportResult process(InputStream input, BankType bankType) {
        Objects.requireNonNull(bankType, "bankType");
        CsvParseResult csv = parser.parse(input);
        BankCsvProfile profile = switch (bankType) {
            case RBC_VISA -> new RbcVisaCsvProfile();
        };
        HeaderValidationResult headers = profile.validateHeaders(csv.headers());
        List<NormalizedImportRow> accepted = new ArrayList<>();
        List<ImportRowIssue> rejected = new ArrayList<>(headers.issues());
        List<ImportRowIssue> review = new ArrayList<>();
        if (!headers.isValid()) {
            for (ParsedCsvRow row : csv.rows()) {
                rejected.add(new ImportRowIssue(row.sourceRowNumber(), ImportIssueCode.INVALID_HEADERS));
            }
            return new CsvImportResult(accepted, rejected, review);
        }

        for (ParsedCsvRow row : csv.rows()) {
            BankNormalizeResult normalized = profile.normalize(row, headers);
            for (ImportRowIssue issue : normalized.issues()) {
                if (issue.code() == ImportIssueCode.REVIEW_REQUIRED) review.add(issue);
                else rejected.add(issue);
            }
            if (normalized.candidate().isPresent()) {
                NormalizedImportRow candidate = normalized.candidate().orElseThrow();
                EntryValidationResult validation = validator.validate(candidate);
                if (validation.isValid()) accepted.add(candidate);
                else rejected.addAll(validation.issues());
            }
        }
        return new CsvImportResult(accepted, rejected, review);
    }
}
