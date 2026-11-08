# Proposed Import closeout commit scope

Inventory date: 2026-11-01. Branch `feature/finance-recorder`; base HEAD `a1042e91f7b8251d94664b5208509cb2dd18a788`. **72 proposed files**, plus **1 existing CI draft excluded and preserved**. The Git index is empty: this is an explicit review inventory, not a staged commit. Paths below are repository-relative; `M` means modified tracked file and `??` means untracked.

The [verification record](import_acceptance_closeout.md) reports 1 explicit acceptance / 206 Import-targeted / 340 full-suite tests passing. Most required Import code is still untracked: `git diff --stat` alone is not a complete implementation inventory, and the base HEAD alone does not reproduce this result.

## Changes made in this closeout

- Extended the existing untracked `ImportConfirmationIntegrationTest.java` with one supplied-fixture acceptance method.
- Added the supplied synthetic CSV as a classpath test resource, byte-identical to the attachment.
- Added this scope record and `import_acceptance_closeout.md`.
- Marked the two earlier Import verification/design notes as historical and linked the current result; removed the link to the excluded CI draft.
- No main source, migration, POM, CI or infrastructure changes were made in this closeout. All earlier implementation and manual CRUD regression work is preserved below.

## Files proposed for owner review

### Main source (37)

```text
M  services/transaction-api/src/main/java/com/finguard/transactionapi/finance/domain/ImportBatch.java
M  services/transaction-api/src/main/java/com/finguard/transactionapi/finance/domain/ImportCandidate.java
M  services/transaction-api/src/main/java/com/finguard/transactionapi/finance/domain/LedgerEntry.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/dto/CandidateReviewDecision.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/dto/CandidateSummary.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/dto/ConfirmImportRequest.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/dto/ImportBatchSummary.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/dto/ImportConfirmationResult.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/dto/ImportPreviewResponse.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/dto/ImportReviewResult.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/dto/SaveImportReviewRequest.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/exception/ImportBatchNotFoundException.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/exception/ImportBatchStateConflictException.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/exception/ImportCandidateNotFoundException.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/exception/ImportConfirmationConflictException.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/exception/ImportIdempotencyConflictException.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/exception/ImportReviewConflictException.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/exception/InvalidImportBatchQueryException.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/exception/InvalidImportRequestException.java
M  services/transaction-api/src/main/java/com/finguard/transactionapi/finance/importing/BankNormalizeResult.java
M  services/transaction-api/src/main/java/com/finguard/transactionapi/finance/importing/CsvImportProcessor.java
M  services/transaction-api/src/main/java/com/finguard/transactionapi/finance/importing/CsvImportResult.java
M  services/transaction-api/src/main/java/com/finguard/transactionapi/finance/importing/ImportIssueCode.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/importing/ImportRowOutcome.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/importing/ImportRowStatus.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/importing/ImportSourceDirection.java
M  services/transaction-api/src/main/java/com/finguard/transactionapi/finance/importing/RbcVisaCsvProfile.java
M  services/transaction-api/src/main/java/com/finguard/transactionapi/finance/repository/CategoryRepository.java
M  services/transaction-api/src/main/java/com/finguard/transactionapi/finance/repository/FinancialAccountRepository.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/repository/ImportBatchRepository.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/repository/ImportCandidateRepository.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/service/ImportBatchQueryService.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/service/ImportConfirmationService.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/service/ImportDuplicateDetector.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/service/ImportDuplicateWarningService.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/service/ImportPreparationService.java
?? services/transaction-api/src/main/java/com/finguard/transactionapi/finance/service/ImportReviewService.java
```

### Required migrations (2)

```text
?? services/transaction-api/src/main/resources/db/migration/V3__add_ledger_entry_version.sql
?? services/transaction-api/src/main/resources/db/migration/V4__add_import_preview_metadata.sql
```

### Tests and synthetic fixture (25)

```text
M  services/transaction-api/src/test/java/com/finguard/transactionapi/finance/FinanceSchemaIntegrationTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/ImportBatchQueryIntegrationTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/ImportBatchQueryServiceTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/ImportConfirmationIntegrationTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/ImportConfirmationServiceTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/ImportCrossSourceDuplicateIntegrationTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/ImportCrossSourceDuplicateServiceTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/ImportDuplicateWarningIntegrationTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/ImportDuplicateWarningServiceTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/ImportPreparationIntegrationTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/ImportPreparationServiceTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/ImportPreviewIntegrationTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/ImportPreviewMetadataMigrationIntegrationTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/ImportPreviewServiceTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/ImportReviewIntegrationTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/ImportReviewServiceTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/LedgerEntryDeleteAndListIntegrationTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/LedgerEntryDeleteAndListServiceTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/LedgerEntryServiceIntegrationTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/LedgerEntryServiceTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/LedgerEntryUpdateIntegrationTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/LedgerEntryUpdateServiceTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/LedgerEntryVersionMigrationIntegrationTest.java
?? services/transaction-api/src/test/java/com/finguard/transactionapi/finance/importing/CsvPreviewOutcomeTest.java
?? services/transaction-api/src/test/resources/finance/importing/finguard_import_acceptance_synthetic.csv
```

### Verification and scope documents (8)

```text
?? services/transaction-api/docs/phase9/import_acceptance_closeout.md
?? services/transaction-api/docs/phase9/import_closeout_commit_scope.md
?? services/transaction-api/docs/phase9/phase9_3_2_verification.md
?? services/transaction-api/docs/phase9/phase9_3_3_verification.md
?? services/transaction-api/docs/phase9/phase9_3_4_contract.md
?? services/transaction-api/docs/phase9/phase9_3_4_verification.md
M  services/transaction-api/docs/phase9/phase9_6_1_verification.md
M  services/transaction-api/docs/phase9/phase9_6_2_review_data_requirements.md
```

## Prerequisites already in HEAD, unchanged

No new POM or wrapper change is missing. The following tracked baseline files remain necessary and are already present at the stated HEAD:

```text
.env.example
.gitignore
compose.yaml
services/transaction-api/pom.xml
services/transaction-api/mvnw
services/transaction-api/mvnw.cmd
services/transaction-api/.mvn/wrapper/maven-wrapper.properties
services/transaction-api/scripts/mvn-local.py
services/transaction-api/src/main/resources/application.properties
services/transaction-api/src/main/resources/db/migration/V1__create_transactions_table.sql
services/transaction-api/src/main/resources/db/migration/V2__create_finance_foundation.sql
```

The POM already specifies Java 21 / Spring Boot 4.1.0 and Commons CSV 1.14.1. Existing tracked Finance domain/value objects, Manual Ledger Services/DTOs/repositories and parser tests also come from that baseline. The seven pending Ledger CRUD/migration test classes are earlier regression work; include them as listed (or in an explicit prerequisite commit) to reproduce the complete reported suite.

V3 and V4 above are mandatory untracked dependencies. Do not omit them or edit already-applied V1/V2. No additional migration was created in the closeout.

## Excluded and preserved

```text
.github/workflows/import-tests.yml
```

This earlier untracked draft runs `com.finguard.transactionapi.finance.importing.*Test`; it does not supply PostgreSQL or cover persisted Import acceptance. Defer it to the next CI review without deleting or staging it here. No remote CI result is claimed.

Do not add private `.env`, personal `download-transactions.csv` or other bank exports, credentials, local ZIPs, logs, `target/`, database dumps, Terraform state/plans, or generated artifacts. No such new pending item appeared in the reviewed scope. Only the named synthetic CSV is included. It intentionally preserves the supplied CRLF bytes/hash; default whitespace checking flags CR-at-EOL, so review that fixture with the per-command CRLF-aware whitespace setting recorded in the verification report. No Git configuration or fixture normalization was applied. Existing local ignored files remain untouched.

Preexisting repository hygiene debt remains outside this change: the base HEAD already tracks 4,348 risk-worker `.venv` files and `infra/terraform/risk_worker_lambda_payload.zip`. They are unchanged and are not additional files in this proposal. This closeout does not delete/untrack them or claim a history-wide secret audit.

## Handoff

Review this explicit list and the diff, then let the owner decide whether to commit. Do not blanket-stage the repository. There are no known missing local source/migration/test prerequisites in this inventory; an isolated clean-checkout run of the eventual commit remains unverified. Start the next CI task from that complete reviewed commit, not from the old base HEAD. No staging, commit or push has been performed.
