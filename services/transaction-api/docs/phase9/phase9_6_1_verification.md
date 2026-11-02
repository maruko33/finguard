# Phase 9.6.1 — RBC CSV parsing, normalization and validation

> Historical scope: the results below describe the parsing/row-outcome slice at that time. Persisted preview, review decisions and atomic confirmation have since been implemented in the working tree; see the [Import closeout verification](import_acceptance_closeout.md) for current executed evidence. Earlier counts are not the final regression total.

## Historical follow-up: complete per-row preview outcomes — 2026-11-01

Base branch/HEAD: `feature/finance-recorder` / `a1042e91f7b8251d94664b5208509cb2dd18a788`. The working tree already contained the October 28 Money/description fix and unrelated, uncommitted ledger migration/tests/docs. The POM had exactly one Commons CSV dependency. No CI workflow existed. No finance ZIP was used. This follow-up remains uncommitted and does not implement persistent Phase 9.6.2.

### Current result contract

- `CsvImportResult(List<ImportRowOutcome> rows, List<ImportRowIssue> headerIssues)` holds one authoritative, source-ordered, immutable list. Header diagnostics remain separate from data records.
- `ImportRowOutcome` carries source record number, `ACCEPTED / REVIEW_REQUIRED / REJECTED`, nullable allowlisted transaction date, occurred-at, description, positive amount magnitude, currency, EntryType, observed `DEBIT / CREDIT` direction, bank transaction ID, and safe issues. Invalid/unreliable fields are null; raw cells and unrelated columns are not retained.
- `BankNormalizeResult(ImportRowOutcome outcome)` wraps that single row. Its existing `candidate()` and `issues()` methods are projections. Only accepted rows expose a complete `NormalizedImportRow`.
- `acceptedRows()`, `rejectedIssues()`, and `reviewIssues()` remain derived compatibility views, not independently stored or supplied collections. `totalCount()`, `acceptedCount()`, `reviewRequiredCount()`, and `rejectedCount()` count rows, not issues.
- Independent date, amount and description parsing preserves the other validated fields when one fails. Multiple issues still produce one rejected row. Complete accepted candidates still pass EntryValidator.
- Both signs continue through the existing Money rules. Valid credits retain their facts and observed CREDIT direction, with `entryType=null`; they are not accepted ledger candidates. Invalid precision/overflow/description rejects a credit before review classification.
- Known currency and a parsed nonzero source direction can remain even if Money rejects the magnitude. For example, `0.001` in CAD$ retains CAD/CREDIT but has `amount=null` and `REJECTED`: an observed sign is not proof of a valid monetary value.
- Invalid headers or inconsistent row width retain only source/status/issues. Delimiter errors can shift columns, so guessing which positional cells remain reliable could assign the wrong financial facts.
- File-level exceptions, UTF-8/CSV dialect, logical numbering, 2 MiB/5,000-record limits, 128-character amount bound, and Money invariants remain unchanged. Outcome diagnostic strings omit transaction facts; explicitly requested preview fields remain available.

### Reproduction and actual verification

Before changing production code, `CsvPreviewOutcomeTest.validCreditRetainsDisplayableFactsWithoutBecomingAnAcceptedEntry` serialized the old result and failed because date, description and amount were absent: **1 test, 1 failure, 0 errors, 0 skipped**, with three missing-field assertions. This was an executed assertion failure, not a compilation failure or a fabricated accepted-credit expectation. Local log: `/tmp/finguard-preview-red.log`.

From `services/transaction-api`, the same Import-only command used by CI passed with database/AWS environment variables removed:

```sh
./mvnw --batch-mode --no-transfer-progress -Dtest='com.finguard.transactionapi.finance.importing.*Test' test
```

**59 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS; 4.263 s.** All previous 50 Import tests plus 9 new preview tests passed. Log: `/tmp/finguard-preview-import-tests.log`.

Existing full local regression:

```sh
AWS_REGION=ca-central-1 python3 scripts/mvn-local.py test
```

**134 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS; 13.049 s.** This includes the preexisting local PostgreSQL/ledger tests and migration files still outside the last commit. No volumes were reset. Log: `/tmp/finguard-preview-full-tests.log`.

### CI coverage and unverified scope

The earlier local draft `.github/workflows/import-tests.yml` (excluded from the closeout commit scope and deferred to the CI task) uses Java 21 and the Maven wrapper, read-only repository permission, path-filtered push/PR triggers and a manual trigger. It runs only tests in `com.finguard.transactionapi.finance.importing`; it does not start PostgreSQL, configure AWS credentials or run deployments. Maven still compiles the project/test sources before selecting these tests.

YAML syntax/structure checks passed locally; actionlint was unavailable. The exact test command passed locally. **Remote GitHub CI is pending: no push, dispatch, or remote Run has occurred.** Runner/action integration and fresh remote dependency resolution remain unverified. Persistence, authentication, account association, classification, deduplication and confirmation are outside this feature.

### Changed files

Production: `BankNormalizeResult`, `CsvImportResult`, `CsvImportProcessor`, `RbcVisaCsvProfile`; new `ImportRowOutcome`, `ImportRowStatus`, `ImportSourceDirection`.
Tests: new `CsvPreviewOutcomeTest`.
CI: new `.github/workflows/import-tests.yml`.
Docs: this report and [the remaining Phase 9.6.2 requirements](phase9_6_2_review_data_requirements.md).
POM, Money, EntryValidator, parser, migrations and manual CRUD are unchanged by this follow-up.

### Actual synthetic example

Executed through the compiled CsvImportProcessor and serialized without a Spring context. Full local output: `/tmp/finguard-preview-example.json`.

```csv
Transaction Date,Description 1,CAD$,USD$
11/1/2026,Cafe,-10.00,
11/1/2026,Credit,5.00,
2/30/2026,Shop,-3.50,
11/1/2026,Lossy credit,0.001,
```

| Source | Status | Date | Description | Amount | Currency | Direction | EntryType | Issues |
| ---: | --- | --- | --- | ---: | --- | --- | --- | --- |
| 2 | ACCEPTED | 2026-11-01 | Cafe | 10.00 | CAD | DEBIT | EXPENSE | none |
| 3 | REVIEW_REQUIRED | 2026-11-01 | Credit | 5.00 | CAD | CREDIT | null | REVIEW_REQUIRED |
| 4 | REJECTED | null | Shop | 3.50 | CAD | DEBIT | EXPENSE | INVALID_DATE |
| 5 | REJECTED | 2026-11-01 | Lossy credit | null | CAD | CREDIT | null | INVALID_AMOUNT |

Summary: total=4, accepted=1, review-required=1, rejected=2. Each data source record appears exactly once.

### New test names (all passed)

- `mixedInputPreservesSourceOrderAndCountsRowsInsteadOfIssues`
- `rejectsDuplicateOutOfOrderOrMisattributedSourceRecords`
- `headerAndWidthErrorsDoNotGuessTransactionFieldsOrCountHeadersAsData`
- `validCreditRetainsDisplayableFactsWithoutBecomingAnAcceptedEntry`
- `diagnosticsDoNotLeakRetainedOrRejectedSyntheticSensitiveValues(CapturedOutput)`
- `immutableAuthoritativeRowsKeepDerivedViewsAndCountsConsistent`
- `invalidDescriptionsBecomeNullWithoutLosingOtherFactsForEitherSign`
- `invalidDateRetainsOtherValidatedTransactionFacts`
- `invalidPositiveAmountsRemainRejectedWhileKnownCurrencyAndDirectionSurvive`

<details>
<summary>Historical verification through 2026-10-29 (superseded by the current follow-up above)</summary>

Verification date: 2026-10-28 (targeted review fix; original slice verified 2026-10-25). Branch: `feature/finance-recorder`.
Starting and final commit: `258ab0f04bc453bdf56192e0e996e53a370a48e0`.
This report records verification before the user-authorized commit, not a release or deployment.

## Implemented flow

`InputStream → CsvParser → selected RbcVisaCsvProfile → header validation → row normalization → EntryValidator → CsvImportResult`

This is an internal, nonpersistent flow. The new production classes depend on Java, Apache Commons CSV, and the existing `Money`/`EntryType` domain classes. They contain no Spring components, repository calls, JPA mappings, AWS clients, logging, authentication or REST endpoints. No import batch/candidate persistence or ledger insertion is implemented. The in-memory tests run without a Spring application context; the full regression suite separately exercises the existing PostgreSQL integration setup.

## Dependency

Added `org.apache.commons:commons-csv:1.14.1` in `pom.xml`. Commons CSV supplies record iteration, quoted commas, escaped quotes and quoted multiline fields, avoiding a custom CSV parser or comma splitting. The selected version is a published release in the [Apache release history](https://commons.apache.org/proper/commons-csv/changes.html). Parser configuration uses the documented [CSVFormat builder](https://commons.apache.org/proper/commons-csv/apidocs/org/apache/commons/csv/CSVFormat.Builder.html).

Iteration is bounded by our input byte guard and data-record check. The library's row limit is not used to silently truncate the file.

## Final contracts and responsibilities

All new production types are in `com.finguard.transactionapi.finance.importing`.

| Type | Contract / responsibility |
| --- | --- |
| CsvParser | `CsvParser()`; `CsvParser(long maxBytes, int maxRows)`; `CsvParseResult parse(InputStream input)`. Syntax, encoding, records and limits only; consumes and closes the supplied stream on success/failure. |
| CsvParseException | File-level rejection with `ImportIssueCode code()` and fixed safe message; no raw input, nested library cause or suppressed close exception. |
| BankType | Explicit selected format: `RBC_VISA`. No bank autodetection. |
| BankCsvProfile | `BankType bankType()`; `HeaderValidationResult validateHeaders(List<String> headers)`; `BankNormalizeResult normalize(ParsedCsvRow row, HeaderValidationResult headers)`. |
| RbcVisaCsvProfile | Implements RBC mapping; validates absolute amount through Money and the shared description predicate before sign-based acceptance/review classification. |
| EntryValidator | `EntryValidationResult validate(NormalizedImportRow row)`. Structured field issues, existing Money validation and recognized currency codes; no account/category checks without context. |
| CsvImportProcessor | `CsvImportProcessor()`; `CsvImportProcessor(CsvParser parser)`; `CsvImportResult process(InputStream input, BankType bankType)`. Small internal orchestration and result separation. |
| ImportIssueCode | Stable enum codes with fixed `message()`; no interpolation of input fields. |
| ImportRowIssue | Immutable `(long sourceRowNumber, ImportIssueCode code)`, with safe `message()`. |

Immutable result/data records:

```java
ParsedCsvRow(long sourceRowNumber, List<String> values)
CsvParseResult(List<String> headers, List<ParsedCsvRow> rows)
HeaderValidationResult(Map<String, Integer> columnIndexes, List<ImportRowIssue> issues)
NormalizedImportRow(long sourceRowNumber, LocalDate transactionDate,
    Instant occurredAt, String description, BigDecimal amount,
    String currencyCode, EntryType entryType, String bankTransactionId)
BankNormalizeResult(Optional<NormalizedImportRow> candidate, List<ImportRowIssue> issues)
EntryValidationResult(List<ImportRowIssue> issues)
CsvImportResult(List<NormalizedImportRow> acceptedRows,
    List<ImportRowIssue> rejectedIssues, List<ImportRowIssue> reviewIssues)
```

Collections are defensive immutable copies. `BankNormalizeResult` requires exactly one candidate or nonempty issues, never both/neither. Normalized candidates are not automatically accepted; `EntryValidator` runs before they enter `acceptedRows`. Header and entry validation results expose `isValid()`. Passing a null normalized candidate is a programming error, while missing/invalid candidate fields produce structured issues.

## CSV dialect and resource limits

- UTF-8 only, malformed encoding rejected instead of replaced. A leading UTF-8 BOM is removed before parsing a possibly quoted first header; its bytes count toward the limit.
- Default maximum: 2,097,152 bytes and 5,000 data records. Constructor limits must be positive. At most one probe byte beyond the byte limit is consumed. The parser stops after detecting the first excess data record, allowing bounded decoder/library read-ahead; it does not first load an unlimited input.
- Header is logical CSV record 1, data starts at 2. A quoted multiline field remains one record. Empty physical lines do not consume record numbers. Whitespace-only preheader records are skipped; whitespace-only, quoted-empty and delimited-empty records after the header remain traceable data records and can be rejected by the profile.
- Empty/blank-only input is rejected; header-only input is valid and has no data outcomes.
- Commas, escaped double quotes, CRLF, quoted newlines and empty trailing cells are supported. Cell counts are retained; the profile reports width mismatches instead of dropping cells.
- Unclosed quoted fields and trailing nonwhitespace text after a closing quote are rejected. **Commons CSV treats a quote inside an unquoted field as a literal. This implementation does not claim strict RFC 4180 validation.**
- Syntax, encoding, byte/row-limit and IO failures reject the whole file with no partial accepted result. These are file-level exceptions, not guessed row positions; all successfully parsed normalization/validation outcomes carry source record numbers.

## Exact RBC mapping assumptions

1. Required case-sensitive names: `Transaction Date`, `Description 1`, `CAD$`, `USD$`. Optional: `Description 2`, `Cheque Number`. Outer whitespace and a leading BOM on the first header are normalized; internal spacing and letter case are not guessed.
2. Empty names and duplicate names after normalization are invalid, including duplicate unknown columns. Unique additional columns are allowed but ignored. This verifies the selected export shape, not a file's authenticity or provenance.
3. Dates must have one/two-digit month/day and exactly four-digit year, strictly resolved as `M/d/uuuu`, years 0001–9999. Invalid calendar dates fail. No timezone/time-of-day is fabricated; `occurredAt=null`.
4. Description parts are stripped at their edges and joined with one space; embedded commas/newlines are preserved. The validator requires usable nonblank text of at most 500 Java characters, consistent with the existing Java length check. This stricter import nonblank requirement does not change manual CRUD.
5. Exactly one nonblank amount column determines CAD or USD. Both populated yields `AMBIGUOUS_CURRENCY`; neither yields `INVALID_AMOUNT`. No conversion or exchange rate is inferred.
6. Supported monetary text is plain signed decimal: `[+-]?[0-9]+(?:\.[0-9]+)?`. Group separators, exponent notation, parentheses, currency symbols and abbreviated forms such as `.50` are not guessed.
7. **Amount text after stripping is limited to 128 characters**, checked before regex/BigDecimal conversion. Longer input produces `AMOUNT_TOO_LONG`, including overly long zero-padded representations whose numeric value could otherwise fit. This bounds conversion work independently of the file byte limit.
8. Both signs first pass existing Money checks on the absolute amount and the shared nonblank/500-character combined-description rule. Zero, lossy precision, overflow and invalid descriptions are rejected. Valid negative amounts become positive-magnitude EXPENSE candidates; valid positive credits produce `REVIEW_REQUIRED`, never automatic INCOME/payment/refund classification.
9. `Money` remains the authority for positive exact `NUMERIC(19,2)`: no silent rounding; lossy extra decimals and overflow fail. Lossless trailing zeros are accepted, and the candidate amount/scale is not mutated by validation. The validator checks currency recognition using `Currency.getInstance`, matching existing service policy, and guards extreme BigDecimal scale expansion before calling Money.
10. `bankTransactionId=null`: Cheque Number is not used as a unique transaction identifier. No owner/account/category is invented.

## Result semantics and remaining ambiguities

- **Accepted**: negative purchase normalized successfully and passed complete candidate validation. Both CAD and USD are preserved, without account compatibility checks in this context-free slice.
- **Rejected**: row structural/format/validation issues. RBC normalization reports the first blocking issue; candidate validation can report several issues for one row.
- **Review required**: a positive credit with a valid date, one amount currency, valid Money magnitude and valid combined description. Lossy/overflowing positive amounts produce `INVALID_AMOUNT`; missing, blank or overlong combined descriptions produce `INVALID_DESCRIPTION`. A valid credit has no fabricated EntryType or accepted candidate and cannot be inserted. Its financial meaning still requires review; future confirmation must also validate the applicable account/user context.
- Invalid headers block all normalization. Header issues use source record 1, and every parsed data row also receives `INVALID_HEADERS`; no rows silently disappear.
- Count distinct source records, not issue-list length, when counting rejected transactions.
- Real bank-export variations, supported numeric formats and payment/refund semantics still need explicit verified business rules before expanding this profile. No real customer CSV or manufactured bank/deployment evidence was used.
- This slice provides no import deduplication, persistent preview, authentication, account association or concurrency guarantees for future confirmation. Existing ledger optimistic-lock behavior is unchanged.
- Phase 9.6.2 must retain safe, structured reviewable transaction facts for valid credits rather than persisting issue codes alone; see the [separate requirements](phase9_6_2_review_data_requirements.md). The current in-memory review result remains issue-only.
- Raw parsed/normalized values are held in memory and must not be logged by future callers; current code does not log them. Tests inspect safe messages, exception stack traces/causes/suppressed exceptions and captured output using synthetic markers.

## Executed verification

From `services/transaction-api`:

```sh
AWS_REGION=ca-central-1 python3 scripts/mvn-local.py test
```

Final run (2026-10-28): **BUILD SUCCESS — 125 tests, 0 failures, 0 errors, 0 skipped**, Maven elapsed **36.042 s**.
This includes **75 existing tests + 50 import tests**. The targeted fix adds six regression tests to the previous 119-test suite and corrects the existing positive/negative normalization expectations.

The established wrapper and existing PostgreSQL integration setup were used. Existing Create/Read/Update/Delete/List, optimistic locking/concurrent writes, V1–V3 schema/migration and transaction-domain tests all passed. No database volumes were reset. New import tests operate entirely in memory; production import code has no database/AWS access.

Full local run log: `/tmp/finguard-961-credit-maven.log`; Surefire XML: `target/surefire-reports/TEST-*.xml`. These are generated local evidence, not committed artifacts.

### Test class results

| Test class | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| TransactionApiApplicationTests | 1 | 0 | 0 | 0 |
| TransactionServiceTest | 2 | 0 | 0 | 0 |
| FinanceSchemaIntegrationTest | 5 | 0 | 0 | 0 |
| LedgerEntryDeleteAndListIntegrationTest | 16 | 0 | 0 | 0 |
| LedgerEntryDeleteAndListServiceTest | 15 | 0 | 0 | 0 |
| LedgerEntryServiceIntegrationTest | 7 | 0 | 0 | 0 |
| LedgerEntryServiceTest | 3 | 0 | 0 | 0 |
| LedgerEntryUpdateIntegrationTest | 12 | 0 | 0 | 0 |
| LedgerEntryUpdateServiceTest | 10 | 0 | 0 | 0 |
| LedgerEntryVersionMigrationIntegrationTest | 1 | 0 | 0 | 0 |
| MoneyTest | 1 | 0 | 0 | 0 |
| CsvImportProcessorTest | 8 | 0 | 0 | 0 |
| CsvParserTest | 16 | 0 | 0 | 0 |
| EntryValidatorTest | 8 | 0 | 0 | 0 |
| RbcVisaCsvProfileTest | 18 | 0 | 0 | 0 |
| InMemoryTransactionRepositoryTest | 1 | 0 | 0 | 0 |
| TransactionControllerTest | 1 | 0 | 0 | 0 |

### Exact import test names

#### CsvImportProcessorTest

- `headerFailureBlocksNormalizationAndAccountsForEveryRow` — passed.
- `rejectsInvalidCombinedDescriptionsForPurchasesAndCredits` — passed.
- `processesMixedCsvWithoutLosingAnyDataRecord` — passed.
- `permitsHeaderOnlyCsvAndAbsentOptionalColumns` — passed.
- `resultCollectionsAreDefensiveSnapshots` — passed.
- `diagnosticsAndCapturedOutputDoNotExposeSyntheticSensitiveValues(CapturedOutput)` — passed.
- `malformedFileReturnsNoPartiallyAcceptedResult` — passed.
- `checksCreditMoneyBeforeSeparatingReviewAndRejectedRecords` — passed.

#### CsvParserTest

- `enforcesByteLimitIncludingBomWithoutOverreadingBeyondOneProbe` — passed.
- `defaultByteLimitAcceptsTwoMiBAndRejectsTheNextByteDuringProcessing` — passed.
- `preservesQuotedCommasEscapedQuotesAndEmptyOptionalCells` — passed.
- `removesUtf8BomBeforeQuotedHeaderAndAcceptsCrLf` — passed.
- `defaultRowLimitAllowsFiveThousandDataRecordsAndRejectsNextRecord` — passed.
- `rowLimitStopsStreamingBeforeReadingTheWholeInput` — passed.
- `preservesDelimitedEmptyAndQuotedEmptyDataRecordsWithoutSilentDrops` — passed.
- `rejectsMalformedUtf8InsteadOfReplacingInvalidBytes` — passed.
- `convertsIoFailuresWithoutLeakingMessagesCausesOrSuppressedExceptions` — passed.
- `rejectsNonPositiveResourceLimits` — passed.
- `allowsHeaderOnlyCsvButRejectsEmptyAndBlankOnlyInputs` — passed.
- `rejectsMalformedQuotedFieldsWithSafeErrors` — passed.
- `usesCommonsCsvLiteralBareQuoteDialectWithoutClaimingStrictRfc` — passed.
- `sanitizesSuppressedCloseFailuresAfterStructuredRowLimitFailure` — passed.
- `numbersLogicalRecordsAcrossBlankLinesAndQuotedMultilineValues` — passed.
- `leavesVariableRecordWidthsAndBankSpecificValuesForProfileValidation` — passed.

#### EntryValidatorTest

- `returnsStructuredIssuesForAllMissingRequiredFields` — passed.
- `acceptsExactMonetaryBoundariesAndBothExistingEntryTypes` — passed.
- `treatsNullCandidateAsAProgrammingError` — passed.
- `rejectsNonpositiveLossyAndOverflowAmountsWithoutSilentRounding` — passed.
- `rejectsBlankOrOverlongDescriptionsWithoutEchoingThem` — passed.
- `rejectsInvalidIsoCurrencyWithoutMaskingAnIndependentlyInvalidAmount` — passed.
- `rejectsExtremeExponentsBeforeMoneyScaleConversionCanAllocateUnboundedPowers` — passed.
- `validationDoesNotMutateOrRoundCandidateAndRequiresNoAccountContext` — passed.

#### RbcVisaCsvProfileTest

- `sendsValidPositiveCreditsToReviewWithoutGuessingTheirClassification` — passed.
- `allowsMissingOrEmptyOptionalCellsAndPreservesEmbeddedDescriptionNewlines` — passed.
- `normalizesCadPurchaseAndDescriptionWithoutTreatingChequeAsTransactionId` — passed.
- `rejectsAmbiguousDuplicatesIncludingUnknownColumnsAfterWhitespaceNormalization` — passed.
- `boundsNumericTextBeforeBigDecimalConversionIncludingPositiveCredits` — passed.
- `rejectsInvalidCalendarDatesAndNoncontractDateFormatsStrictly` — passed.
- `rejectsOverflowingPositiveAmountsBeforeReviewClassification` — passed.
- `rejectsBothPopulatedCurrenciesAndNoAmount` — passed.
- `rejectsLossyPositiveAmountsBeforeReviewClassification` — passed.
- `rejectsEmptyHeadersAndBlankHeaderNames` — passed.
- `validatesCombinedDescriptionBeforeReviewAndPurchaseClassification` — passed.
- `issueMessagesNeverEchoSyntheticSensitiveInput` — passed.
- `rejectsInvalidHeadersOrWrongRecordWidthWithoutLosingSourceRecordNumber` — passed.
- `rejectsMissingWrongBankAndCaseOrInternalWhitespaceMismatchedHeaders` — passed.
- `rejectsInvalidNegativeAmountsAndPreservesLosslessMagnitude` — passed.
- `rejectsMissingBlankAndOverlongDescriptionsForEitherAmountSign` — passed.
- `rejectsInvalidAmountSyntaxAndZeroWithoutGuessingFormats` — passed.
- `preservesUsdAndNormalizesOuterHeaderWhitespaceAndLeadingBom` — passed.

## Files created or modified

Paths below are relative to `services/transaction-api`.

| Change | File |
| --- | --- |
| Modified | [pom.xml](../../pom.xml) |
| Created | [src/main/java/com/finguard/transactionapi/finance/importing/BankCsvProfile.java](../../src/main/java/com/finguard/transactionapi/finance/importing/BankCsvProfile.java) |
| Created | [src/main/java/com/finguard/transactionapi/finance/importing/BankNormalizeResult.java](../../src/main/java/com/finguard/transactionapi/finance/importing/BankNormalizeResult.java) |
| Created | [src/main/java/com/finguard/transactionapi/finance/importing/BankType.java](../../src/main/java/com/finguard/transactionapi/finance/importing/BankType.java) |
| Created | [src/main/java/com/finguard/transactionapi/finance/importing/CsvImportProcessor.java](../../src/main/java/com/finguard/transactionapi/finance/importing/CsvImportProcessor.java) |
| Created | [src/main/java/com/finguard/transactionapi/finance/importing/CsvImportResult.java](../../src/main/java/com/finguard/transactionapi/finance/importing/CsvImportResult.java) |
| Created | [src/main/java/com/finguard/transactionapi/finance/importing/CsvParseException.java](../../src/main/java/com/finguard/transactionapi/finance/importing/CsvParseException.java) |
| Created | [src/main/java/com/finguard/transactionapi/finance/importing/CsvParseResult.java](../../src/main/java/com/finguard/transactionapi/finance/importing/CsvParseResult.java) |
| Created | [src/main/java/com/finguard/transactionapi/finance/importing/CsvParser.java](../../src/main/java/com/finguard/transactionapi/finance/importing/CsvParser.java) |
| Created | [src/main/java/com/finguard/transactionapi/finance/importing/EntryValidationResult.java](../../src/main/java/com/finguard/transactionapi/finance/importing/EntryValidationResult.java) |
| Created | [src/main/java/com/finguard/transactionapi/finance/importing/EntryValidator.java](../../src/main/java/com/finguard/transactionapi/finance/importing/EntryValidator.java) |
| Created | [src/main/java/com/finguard/transactionapi/finance/importing/HeaderValidationResult.java](../../src/main/java/com/finguard/transactionapi/finance/importing/HeaderValidationResult.java) |
| Created | [src/main/java/com/finguard/transactionapi/finance/importing/ImportIssueCode.java](../../src/main/java/com/finguard/transactionapi/finance/importing/ImportIssueCode.java) |
| Created | [src/main/java/com/finguard/transactionapi/finance/importing/ImportRowIssue.java](../../src/main/java/com/finguard/transactionapi/finance/importing/ImportRowIssue.java) |
| Created | [src/main/java/com/finguard/transactionapi/finance/importing/NormalizedImportRow.java](../../src/main/java/com/finguard/transactionapi/finance/importing/NormalizedImportRow.java) |
| Created | [src/main/java/com/finguard/transactionapi/finance/importing/ParsedCsvRow.java](../../src/main/java/com/finguard/transactionapi/finance/importing/ParsedCsvRow.java) |
| Created | [src/main/java/com/finguard/transactionapi/finance/importing/RbcVisaCsvProfile.java](../../src/main/java/com/finguard/transactionapi/finance/importing/RbcVisaCsvProfile.java) |
| Created | [src/test/java/com/finguard/transactionapi/finance/importing/CsvImportProcessorTest.java](../../src/test/java/com/finguard/transactionapi/finance/importing/CsvImportProcessorTest.java) |
| Created | [src/test/java/com/finguard/transactionapi/finance/importing/CsvParserTest.java](../../src/test/java/com/finguard/transactionapi/finance/importing/CsvParserTest.java) |
| Created | [src/test/java/com/finguard/transactionapi/finance/importing/EntryValidatorTest.java](../../src/test/java/com/finguard/transactionapi/finance/importing/EntryValidatorTest.java) |
| Created | [src/test/java/com/finguard/transactionapi/finance/importing/RbcVisaCsvProfileTest.java](../../src/test/java/com/finguard/transactionapi/finance/importing/RbcVisaCsvProfileTest.java) |
| Created | [docs/phase9/phase9_6_1_verification.md](phase9_6_1_verification.md) |

## Original implementation diff (before targeted review fix)

- The original slice changed 22 files: 1 existing POM + 16 new production Java files + 4 new test files + this new report.
- Implementation/test/dependency diff excluding this report: **+1284 / -0 lines**.
- Compared with the original-slice pre-task SHA-256 baseline, the POM was the only changed existing file; no existing files were removed. Existing Finance CRUD, Money, migrations, prior tests/docs and local archives are unchanged.
- Branch/HEAD are unchanged. No files are staged by this task; no commit or push was performed.
- `git diff --check` passed. Ordinary Git diff/stat omits the new untracked sources and also includes earlier unrelated work, so the task summary above uses the pre-task baseline.

## Targeted review fix — 2026-10-28

Confirmed defect: positive credits were returned as review-required before Money and description validation. The profile now constructs Money from the absolute amount for both signs before classification. Existing Money rules remain unchanged; the original candidate magnitude/scale is retained without rounding. The profile and EntryValidator share one package-private description predicate, so blank/overlong combined descriptions fail consistently. No fake positive-credit EntryType or extra validator hierarchy was introduced.

| Change | File |
| --- | --- |
| Modified | [RbcVisaCsvProfile.java](../../src/main/java/com/finguard/transactionapi/finance/importing/RbcVisaCsvProfile.java) |
| Modified | [EntryValidator.java](../../src/main/java/com/finguard/transactionapi/finance/importing/EntryValidator.java) |
| Modified | [RbcVisaCsvProfileTest.java](../../src/test/java/com/finguard/transactionapi/finance/importing/RbcVisaCsvProfileTest.java) |
| Modified | [CsvImportProcessorTest.java](../../src/test/java/com/finguard/transactionapi/finance/importing/CsvImportProcessorTest.java) |
| Modified | [phase9_6_1_verification.md](phase9_6_1_verification.md) |
| Created | [phase9_6_2_review_data_requirements.md](phase9_6_2_review_data_requirements.md) |

The four new profile tests cover lossy positive amounts, positive overflow, missing/blank/overlong descriptions for either sign, and combined-description boundaries. Two new in-memory end-to-end tests verify valid CAD/USD credits remain review-only, invalid credits are rejected, valid purchases remain expenses, and every source record reaches the correct result bucket.

Only these six files changed from the pre-fix baseline. No Money/domain classes, manual CRUD, migrations, dependency versions, persistence or controllers changed. The full suite above exercised the established PostgreSQL tests without resetting database volumes. Branch/HEAD and the staging index remain unchanged; no commit or push was performed.

## Commit scope note — 2026-10-29

The CSV commit includes the complete Phase 9.6.1 import package, its tests, the Commons CSV dependency and these import documents. A duplicate Commons CSV dependency declaration was removed during diff review. The full working-tree wrapper test run after deduplication passed on 2026-10-29: 125 tests, 0 failures/errors/skips, BUILD SUCCESS, Maven elapsed 35.114 s (local log: /tmp/finguard-961-precommit-maven.log).

The 125-test verification totals refer to the full local working tree. Previously implemented ledger regression tests, the V3 ledger-version migration and the corresponding schema-test update remain outside this CSV commit, along with older Phase 9.3 documents and the local finance.zip archive. In particular, a fresh checkout cannot reproduce the existing PostgreSQL/CRUD regression results until that earlier migration/test work is committed separately. No files were deleted or database volumes reset.

</details>
