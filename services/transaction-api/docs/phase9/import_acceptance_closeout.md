# Import final acceptance and CI handoff

Verified locally on 2026-11-01 (America/Toronto). Branch `feature/finance-recorder`, base HEAD `a1042e91f7b8251d94664b5208509cb2dd18a788`. These results describe the **uncommitted working tree**, not that HEAD alone. See the [complete proposed commit scope](import_closeout_commit_scope.md).

## This closeout change

Added one method to the existing [ImportConfirmationIntegrationTest](../../src/test/java/com/finguard/transactionapi/finance/ImportConfirmationIntegrationTest.java): `suppliedSyntheticCsvCompletesPersistedImportAcceptance`. It reuses the existing isolated PostgreSQL schema, real Spring Services, SQL recorder and independent read helpers. No Import business source, migration, POM, deployment or CI code changed in this closeout. Earlier uncommitted implementation remains required.

The [synthetic CSV](../../src/test/resources/finance/importing/finguard_import_acceptance_synthetic.csv) is byte-identical to the supplied attachment: 474 bytes, SHA-256 `d5d86e861c324bb13048ea2ac9ef1dc1b215dccdd611d04c595825f41cb562e0`. Original CRLF line endings are retained because this fixture also exercises raw-byte upload identity. A default no-index whitespace check flags those CR endings; an explicit per-command `core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol` check accepts them without changing repository/global Git configuration. There is no added trailing space or tab. Its account identifier and descriptions are synthetic. No personal bank CSV was copied. Two earlier Import documents now identify their historical scope; their original test evidence is retained.

## Executed workflow and result

`prepareImport -> listReviewingBatches -> getImportPreview -> refreshDuplicateWarnings -> saveReview -> confirmImport -> independent Ledger/Batch reads -> repeated Confirm -> original-upload-key retry`

All six records persisted as initially unselected Candidates; the header is logical source record 1. The list returned `candidateCount=6`, `attentionRowCount=3` (issue-bearing rows only). Weak duplicate warnings were saved for records 2 and 4; the isolated account had no external matches.

| Source record | Stored facts / issue | Saved review decision | Ledger result |
| ---: | --- | --- | --- |
| 2 | 2026-10-01, CAD 10.25 EXPENSE/DEBIT, SYNTHETIC COFFEE | Selected; current duplicate signature acknowledged | CAD 10.25 EXPENSE |
| 3 | 2026-10-02, CAD 3.50 EXPENSE/DEBIT, SYNTHETIC BUS | Selected | CAD 3.50 EXPENSE |
| 4 | 2026-10-01, CAD 10.25 EXPENSE/DEBIT, synthetic   coffee | Selected; current duplicate signature acknowledged | CAD 10.25 EXPENSE |
| 5 | 2026-10-03, CAD 25.00 CREDIT, null EntryType; REVIEW_REQUIRED | Unselected; issue preserved | None |
| 6 | Null date, CAD 4.00; INVALID_DATE | Unselected; issue preserved | None |
| 7 | 2026-10-04, null amount; INVALID_AMOUNT | Unselected; issue preserved | None |

Records 2 and 4 are deliberately accepted as separate purchases: a weak match is a warning, not a decision to discard one. Comparison normalization does not rewrite the stored description. Credit facts remain reviewable without inventing INCOME or bypassing its issue.

Independent database reads verified `CONFIRMED`, `confirmedCount=3`, `notImportedCount=3` (stored as `excluded_count`), exactly three owned Ledger entries totaling **CAD 24.00**, correct source batch/row identities, and all six Candidates retained. Version progressed `0 -> 1 -> 2 -> 3` across upload, scan, review and confirm. The Batch disappeared from the REVIEWING list; its historical preview remained readable.

Confirm retried with the original review version returned the same durable summary. Retrying the original upload key returned the same confirmed Batch. Full Batch/Candidate/Ledger snapshots were unchanged, including saved decisions and version; the recorded retry SQL contained no INSERT, UPDATE or DELETE.

Actual synthetic evidence from the full run (generated Batch ID varies per run):

```text
IMPORT_SUPPLIED_CSV_ACCEPTANCE batch=2e6123fa-4895-4444-a29f-c2d95d79bfa2 candidates=6 attentionRows=3 warnings=[2,4] selected=[2,3,4] ledgerRows=3 sumCAD=24.00 confirmed=3 notImported=3 version=0->1->2->3 candidatesRetained=6 confirmRetryEqual=true uploadRetryConfirmed=true
```

## Commands and actual execution

Run from `services/transaction-api` using the existing local wrapper and configured local PostgreSQL. Runtime observed: OpenJDK 21.0.12.1, Spring Boot 4.1.0, PostgreSQL 17.11. The wrapper reads local Compose configuration and private `.env`; neither credentials nor that file belong in the commit.

```sh
AWS_REGION=ca-central-1 python3 scripts/mvn-local.py "-Dtest=ImportConfirmationIntegrationTest#suppliedSyntheticCsvCompletesPersistedImportAcceptance" test
AWS_REGION=ca-central-1 python3 scripts/mvn-local.py "-Dtest=Import*Test" test
AWS_REGION=ca-central-1 python3 scripts/mvn-local.py test
```

| Execution | Tests | Failures | Errors | Skipped | Maven time | Finished (Toronto) |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Explicit synthetic acceptance | 1 | 0 | 0 | 0 | 8.866 s | 2026-11-01 21:07:36 -05:00 |
| Import-targeted | 206 | 0 | 0 | 0 | 31.247 s | 2026-11-01 21:08:31 -05:00 |
| Full regression | 340 | 0 | 0 | 0 | 34.700 s | 2026-11-01 21:09:28 -05:00 |

All three commands returned exit 0 / BUILD SUCCESS on their first execution in this closeout. The explicit selector discovered exactly the named acceptance. The targeted run refreshed 15 Surefire XML reports; the full run refreshed 33. The full confirmation integration class executed 24 tests. Counts were checked against newly written XML, not accumulated stale reports. The older 339/205 baseline is superseded by 340/206 after this one new test.

`Import*Test` selects the Import service/integration classes; it is not the parser component wildcard. The full run also executed all 59 `finance.importing` component tests, Manual Ledger CRUD, schema/migration tests and existing transaction tests. No tests were skipped. Existing compiler/JVM/Spring warnings did not fail the builds; this is not a warning-free-build claim.

Local raw logs remain outside the repository: `/tmp/finguard-closeout-acceptance.log`, `/tmp/finguard-closeout-import.log`, `/tmp/finguard-closeout-full.log`. Generated Surefire reports remain ignored under `target/`. Neither logs nor reports are proposed commit files.

## Existing protection tests executed again

The new workflow test complements, rather than duplicates, these existing PostgreSQL tests:

- `ImportConfirmationIntegrationTest#twoRealSessionsConfirmingOneBatchReturnOnePersistedResult`
- `ImportConfirmationIntegrationTest#sameAccountDifferentBatchesSerializeAndRequireRereviewAfterCandidateBecomesLedger`
- `ImportConfirmationIntegrationTest#realFailureAfterOneLedgerInsertRollsBackAllRowsAndBatchAndRetainsReviewDecisions`
- `ImportConfirmationIntegrationTest#failureOfFinalBatchUpdateAlsoRollsBackAlreadyInsertedLedger`
- `ImportConfirmationIntegrationTest#requiredOuterRollbackUndoesTheReturnedConfirmationAndAllLedgerInserts`
- `ImportConfirmationIntegrationTest#ownershipStatusVersionAndEmptySelectionFailWithoutAnyLedgerOrBatchChange`
- `ImportConfirmationIntegrationTest#sourceRowUniqueConflictRollsBackEveryInsertRatherThanSkippingOneSelectedRow`
- `ImportConfirmationIntegrationTest#trustedBankIdConstraintFixtureIsNotConfusedWithSupportedRbcInputIdentity`
- `ImportPreparationIntegrationTest#concurrentIdenticalUploadsUseTwoSessionsAndCreateOneBatchAndCandidateSet`
- `ImportPreparationIntegrationTest#concurrentDifferentFilesHaveOneWinnerAndOneConflictWithoutChangingTheWinner`
- `ImportReviewIntegrationTest#twoRealSessionsWithOneExpectedVersionProduceOneSaveAndOneConflict`
- `ImportReviewIntegrationTest#scannerHoldingBatchLockMakesWaitingOldReviewConflict`
- `LedgerEntryVersionMigrationIntegrationTest#upgradesV2WithoutChangingExistingDataAndEnforcesVersionConstraints`
- `ImportPreviewMetadataMigrationIntegrationTest#upgradesV3WithoutGuessingHistoricalMetadataAndConstrainsKnownValues`

Migration V3 adds nonnegative Ledger version with existing rows initialized to 0. V4 adds nullable bank type/source direction, preserving unknown historical values instead of inferring them. Both are earlier required untracked migrations, unchanged by this closeout. V1/V2 were not edited. Integration fixtures create/drop their own test schemas; PostgreSQL volumes were not reset. The existing application-context smoke test also checked the local public schema, already at V4; it applied no new migration.

Confirmation retains its existing REQUIRED transaction: inserted Ledger rows and final Batch transition commit together; participation in an outer transaction means the caller still controls commit/rollback. The acceptance uses normal independent Service calls so final reads observe committed data. Existing rollback/constraint/concurrency tests exercise failure paths; this closeout does not change locking or transaction policy.

## Remaining boundary and next step

There is no public authenticated Finance API, Angular upload/review UI, or automatic Credit classification. This is an internal-Service acceptance using synthetic data, not browser acceptance or a production guarantee. No new business feature was added.

The existing untracked `.github/workflows/import-tests.yml` is a deferred component-only draft without PostgreSQL coverage; it is excluded from this commit proposal and was not changed. No remote GitHub Run was requested or verified. Local Maven success is not remote CI success.

After owner review and commit of the complete scope, verify that exact commit in a clean checkout and extend the existing workflow with disposable PostgreSQL and non-production configuration. That CI must not depend on private WSL `.env`, AWS SSO, or real bank data. Clean-checkout and remote-run evidence remain pending. No staging, commit, push, tag, branch change, AWS deployment or volume reset was performed.
