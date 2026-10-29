package com.finguard.transactionapi.finance.importing;

import java.util.List;

/** Bank-specific interpretation of records already parsed as CSV. */
public interface BankCsvProfile {
    BankType bankType();

    HeaderValidationResult validateHeaders(List<String> headers);

    BankNormalizeResult normalize(ParsedCsvRow row, HeaderValidationResult headers);
}
