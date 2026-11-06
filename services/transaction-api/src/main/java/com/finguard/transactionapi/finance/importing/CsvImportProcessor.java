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
        List<ImportRowOutcome> outcomes = new ArrayList<>();
        for (ParsedCsvRow row : csv.rows()) {
            if (!headers.isValid()) {
                outcomes.add(ImportRowOutcome.structuralRejection(row.sourceRowNumber(),
                        ImportIssueCode.INVALID_HEADERS));
                continue;
            }
            ImportRowOutcome outcome = profile.normalize(row, headers).outcome();
            if (outcome.candidate().isPresent()) {
                EntryValidationResult validation = validator.validate(outcome.candidate().orElseThrow());
                if (!validation.isValid()) outcome = outcome.reject(validation.issues());
            }
            outcomes.add(outcome);
        }
        return new CsvImportResult(outcomes, headers.issues());
    }
}
