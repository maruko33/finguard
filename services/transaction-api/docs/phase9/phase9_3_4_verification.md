# Phase 9.3.4 verification: internal ledger Soft Delete and List

Verified on 2026-10-25 (America/Toronto) on `feature/finance-recorder`, base commit `ac11710467c2ee7459be13b6b18e0050df767295`. The tested working tree includes the earlier uncommitted Create/Read/Update changes. This record describes only the new Soft Delete/List slice.

## Changed and created files

Modified relative to the working tree at the start of this slice (paths below are relative to `services/transaction-api/`):

- [src/main/java/com/finguard/transactionapi/finance/domain/LedgerEntry.java](../../src/main/java/com/finguard/transactionapi/finance/domain/LedgerEntry.java)
- [src/main/java/com/finguard/transactionapi/finance/service/LedgerEntryService.java](../../src/main/java/com/finguard/transactionapi/finance/service/LedgerEntryService.java)
- [src/main/java/com/finguard/transactionapi/finance/repository/LedgerEntryRepository.java](../../src/main/java/com/finguard/transactionapi/finance/repository/LedgerEntryRepository.java)

New files:

- [src/main/java/com/finguard/transactionapi/finance/dto/ListFinanceEntriesFilter.java](../../src/main/java/com/finguard/transactionapi/finance/dto/ListFinanceEntriesFilter.java)
- [src/main/java/com/finguard/transactionapi/finance/exception/FinancialAccountNotFoundException.java](../../src/main/java/com/finguard/transactionapi/finance/exception/FinancialAccountNotFoundException.java)
- [src/test/java/com/finguard/transactionapi/finance/LedgerEntryDeleteAndListServiceTest.java](../../src/test/java/com/finguard/transactionapi/finance/LedgerEntryDeleteAndListServiceTest.java)
- [src/test/java/com/finguard/transactionapi/finance/LedgerEntryDeleteAndListIntegrationTest.java](../../src/test/java/com/finguard/transactionapi/finance/LedgerEntryDeleteAndListIntegrationTest.java)
- `docs/phase9/phase9_3_4_verification.md` (this record).

The existing empty `phase9_3_4_contract.md`, earlier verification records, source ZIPs, Create/Read/Update tests, and Money/response implementations were preserved. No portfolio or isolated-review worktree was modified.

## Contracts

```java
@Transactional
public void deleteEntry(UUID currentUserId, UUID entryId, Long expectedVersion)

@Transactional(readOnly = true)
public Page<FinanceEntryResponse> listEntries(
        UUID currentUserId, ListFinanceEntriesFilter filter, Pageable pageable)
```

`ListFinanceEntriesFilter` contains required `UUID accountId`, optional inclusive `LocalDate fromTransactionDate`, optional inclusive `LocalDate toTransactionDate`, and optional `EntryType entryType`. List has no expectedVersion parameter and maps only scalar entity fields to the existing response, including its version.

List rejects null user/filter/account/pageable, unpaged requests, negative page numbers, sizes outside 1..100, offsets beyond JPA's Integer.MAX_VALUE limit, reversed date ranges, and unsupported sort fields/order/options. The default caller convention is page 0, size 20; the service does not substitute defaults for null input. Unsorted requests and exactly `transactionDate DESC, id DESC` are accepted and normalized to a PageRequest with that fixed order. Missing and foreign accounts both raise FinancialAccountNotFoundException before any ledger query. Owned inactive accounts are accepted.

## Soft Delete mapping and transaction behavior

The scalar `deleted_at` column now uses JPA's default `updatable = true`; all protected ownership/account/source/creation columns and the existing `@Version Long` mapping remain intact. No migration was added or edited: V1, V2 and V3 match the pre-task bytes.

Delete first uses the existing `findByIdAndUserIdAndDeletedAtIsNull`. Missing, foreign-owned and already-deleted entries raise the same FinanceEntryNotFoundException, even before version validation. Null/negative expected versions raise InvalidFinanceEntryException; unequal versions raise FinanceEntryConflictException before mutation. Neither account nor category activity is required for deletion.

The domain `markDeleted()` changes only deletedAt and updatedAt. deletedAt is the server time truncated to PostgreSQL microseconds. updatedAt advances monotonically, even if the prior stored timestamp is ahead of the server clock. Hibernate increments version during the SQL UPDATE. No repository physical-delete method or unconditional bulk update is called; financial fields, source metadata, unique/idempotency keys and the row itself remain present.

Update and Delete share private version validation and flush/exception translation. Explicit `flush()` executes the version-checked SQL update inside the service transaction. A stale database write raises a Spring OptimisticLockingFailureException or JPA OptimisticLockException; the service translates it to FinanceEntryConflictException with the cause retained. The RuntimeException rolls back the transaction.

Default REQUIRED propagation joins an existing caller transaction. A standalone service call commits before the proxy returns; a joined call flushes but leaves final commit to the outer caller. The outer-rollback integration test verifies deletedAt, updatedAt, version and all original fields return to their prior values. Flush-time translation does not promise to translate unrelated or later outer-transaction commit failures.

## Repository predicates and PostgreSQL pagination

LedgerEntryRepository additionally extends JpaSpecificationExecutor<LedgerEntry>; the existing secure single-entry lookup remains unchanged. The list specification applies these predicates in the database:

```text
user_id = currentUserId
AND account_id = filter.accountId
AND deleted_at IS NULL
AND transaction_date >= fromTransactionDate  (when supplied)
AND transaction_date <= toTransactionDate    (when supplied)
AND entry_type = entryType                   (when supplied)
ORDER BY transaction_date DESC, id DESC
OFFSET requestedOffset FETCH FIRST requestedSize ROWS ONLY
```

The count uses the same predicates without page bounds. Spring Data may omit a count when it can infer the total from a short page. Owned accounts without matching rows return an empty Page. Manual and confirmed imported ledger rows are included without traversing account, category or source associations. There is no full-collection fetch followed by in-memory filtering/pagination.

A Hibernate StatementInspector test captured actual executed SQL with all optional filters active on a full second page. It required exactly three SELECT statements: owned-account check, one bounded ledger content query, and one filtered count. It verified all predicates, date/UUID ordering, OFFSET and FETCH/LIMIT, and no extra association queries. Other tests verify inclusive endpoints, createdAt independence, page metadata, unchanged versions/data, deleted-row exclusion from counts, and PostgreSQL's unsigned UUID tie-break order.

## Reference ZIP reconciliation

The supplied `finguard-delete-list-review-snippets.zip` was read as reference material; nothing was extracted over the working tree. Its DTO fields, specification approach and transaction intent match the implementation. No source change was needed after comparison.

Current exception constructors are no-argument/Throwable; copying the ZIP's String constructor calls would not compile. The implementation uses a dedicated account Not Found exception for both missing/foreign accounts. It retains actual server deletion time and separately advances updatedAt, avoiding the reference snippet's possible future deletedAt. The implementation also bounds offsets to the JPA integer limit.

## Executed verification

Command, run from `services/transaction-api/`:

```bash
AWS_REGION=ca-central-1 python3 scripts/mvn-local.py test
```

**BUILD SUCCESS: 75 tests, 0 failures, 0 errors, 0 skipped; Maven exit 0.** New coverage consists of 15 unit tests and 16 real PostgreSQL integration tests; all previous 44 tests, including V3 and optimistic Update tests, still pass. The earlier approval failure prevented execution entirely; this successful run is the first executed full suite for this slice.

The public schema was already at V3 and Flyway reported no migration necessary. Integration fixtures use isolated random schemas, removing only their own schemas. The existing PostgreSQL 17 container/volume was not reset or replaced. The final source hashes match the source snapshot captured before this test run.

Both Update-wins and Delete-wins tests hold two real service transactions open after each has read version 0. They assert distinct PostgreSQL backend PIDs, coordinate the winning commit, and require one real optimistic-lock failure rather than only an expectedVersion mismatch. Fresh reads/direct SQL prove exactly one version-1 result, unchanged protected metadata, no partial deletion, and no resurrection of a row when Delete wins.

Local execution log: `/tmp/finguard-934-maven-1.log`. Exact results below come from `target/surefire-reports/TEST-*.xml`; generated logs/reports are not staged or committed.

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
| InMemoryTransactionRepositoryTest | 1 | 0 | 0 | 0 |
| TransactionControllerTest | 1 | 0 | 0 | 0 |

## Exact executed test names

### TransactionApiApplicationTests — 1/1 passed

- `contextLoads`

### TransactionServiceTest — 2/2 passed

- `shouldCreateTransactionAndPublishEvent`
- `findMissingTransactionThrowsException`

### FinanceSchemaIntegrationTest — 5/5 passed

- `durableIdempotencyKeysAndTerminalTimestamps`
- `ownershipTypeAndSourceChecks`
- `candidateAndBatchConstraints`
- `hibernateValidatesAndLoadsAllFinanceMappings`
- `sourceUniquenessAllowsIdenticalValuesAndCandidatePurge`

### LedgerEntryDeleteAndListIntegrationTest — 16/16 passed

- `actualUpdateVersusDeleteConflictRollsBackDeleteWhenUpdateCommitsFirst`
- `missingAndForeignAccountsReturnSameNotFoundBeforeAnyLedgerQuery`
- `foreignMissingAndRepeatedDeletesShareNotFoundAndPreserveEveryColumn`
- `sendsAllPredicatesOrderingOffsetAndLimitToPostgresWithOneContentQueryOneCountAndNoNPlusOne`
- `listsHistoricalEntriesOfInactiveOwnedAccountWithoutMutatingRows`
- `actualUpdateVersusDeleteConflictRollsBackUpdateWhenDeleteCommitsFirst`
- `returnsEmptyPageForOwnedAccountWithoutMatchesAndForPageBeyondEnd`
- `invalidAndStaleDeleteVersionsLeaveEntireCommittedRowUnchanged`
- `filtersInclusiveTransactionDatesIndependentlyOfCreationTimeAndOptionalEntryType`
- `outerTransactionRollbackRestoresFlushedDeletionVersionAndTimestamps`
- `paginatesByTransactionDateThenPostgresUuidOrderAndIncludesManualAndConfirmedImportedRows`
- `rejectsInvalidListArgumentsDateRangesPaginationAndUnsupportedSortWithoutMutation`
- `softDeleteKeepsUpdatedAtMonotonicEvenWhenStoredTimeIsAheadOfServerClock`
- `deletesOwnedEntryAfterOriginalAccountAndCategoryBecomeInactive`
- `softDeleteRetainsRowAndAllOriginalFinancialAndImportMetadataButHidesReadAndList`
- `excludesDeletedEntriesFromEveryPageAndFromDatabaseTotalCount`

### LedgerEntryDeleteAndListServiceTest — 15/15 passed

- `deleteDoesNotMoveUpdatedAtBackwardWhenStoredTimestampIsAheadOfServerClock`
- `deleteMarksServerTimestampPreservesMetadataAndFlushesWithoutAccountRevalidation`
- `deleteWithNullIdentityIsNotFoundWithoutRepositoryAccess`
- `listRejectsMissingRequiredInputsWithoutRepositoryAccess`
- `deleteHidesInaccessibleEntryBeforeVersionValidation`
- `deleteTranslatesSpringOptimisticLockFailureAndPreservesCause`
- `listRejectsInvertedDateRangeWithoutRepositoryAccess`
- `listHidesMissingOrForeignAccountBeforeQueryingEntries`
- `listRejectsUnsupportedSortWithoutRepositoryAccess`
- `listNormalizesUnsortedPageAndPreservesResponseVersionAndPaginationMetadata`
- `listRejectsInvalidPaginationWithoutRepositoryAccess`
- `deleteTranslatesJpaOptimisticLockFailureAndPreservesCause`
- `listAcceptsSupportedSortMaximumPageSizeAndHistoricalInactiveAccount`
- `deleteRejectsStaleExpectedVersionWithoutMutation`
- `deleteRejectsNullAndNegativeExpectedVersionWithoutMutation`

### LedgerEntryServiceIntegrationTest — 7/7 passed

- `validIncomeAndNullCategory`
- `hidesOtherUsersMissingAndSoftDeletedEntries`
- `rejectsInvalidAndMismatchedCurrency`
- `validExpensePersistsAndReadsWithIndependentDates`
- `rejectsZeroNegativeScaleAndOverflowWithoutPersistence`
- `rejectsInvalidAccountOwnerAndInactiveOrMissingAccount`
- `rejectsInvalidCategoryOwnerTypeInactiveAndMissing`

### LedgerEntryServiceTest — 3/3 passed

- `rejectsInvalidMoneyBeforeRepositoryAccess`
- `rejectsMissingRequiredFieldsBeforeRepositoryAccess`
- `lookupUsesOwnerAndSoftDeletePredicateOnly`

### LedgerEntryUpdateIntegrationTest — 12/12 passed

- `rejectsForeignWrongTypeInactiveAndMissingCategoriesWithoutAnyPersistedChanges`
- `rejectsStaleExpectedVersionFromIndependentServiceReadsAndPreservesWinner`
- `replacesEveryEditableFieldAndPreservesIdentityAndSourceMetadata`
- `identicalReplacementStillAdvancesVersionAndUpdatedAt`
- `rejectsInvalidOrMismatchedCurrencyWithoutAnyPersistedChanges`
- `hidesOtherUsersMissingAndSoftDeletedEntriesWithoutChanges`
- `revalidatesOriginalAccountActivity`
- `rejectsInvalidAmountsWithoutAnyPersistedChanges`
- `rejectsMissingRequiredFieldsAndInvalidExpectedVersionsWithoutAnyPersistedChanges`
- `joinsOuterTransactionAndRollsBackAllFlushedChanges`
- `simultaneousWritesFromTwoPostgresSessionsCommitOneAndRollbackTheActualLockConflict`
- `replacesIncomeWithExpenseAndClearsNullableFields`

### LedgerEntryUpdateServiceTest — 10/10 passed

- `translatesJpaOptimisticLockFailureAndPreservesCause`
- `rejectsMissingRequiredFieldsAndInvalidExpectedVersionsBeforeMutation`
- `invalidMoneyAndCurrencyDoNotMutateTheManagedEntry`
- `inaccessibleEntryIsHiddenBeforeRequestValidation`
- `nullIdentityReturnsNotFoundWithoutRepositoryAccess`
- `staleExpectedVersionIsRejectedBeforeValidationOrMutation`
- `fullReplacementClearsOptionalsAndReturnsVersionAfterFlush`
- `translatesSpringOptimisticLockFailureAndPreservesCause`
- `revalidatesTheExistingAccountBeforeMutation`
- `categoryMustBelongToOwnerBeActiveAndMatchReplacementType`

### LedgerEntryVersionMigrationIntegrationTest — 1/1 passed

- `upgradesV2WithoutChangingExistingDataAndEnforcesVersionConstraints`

### MoneyTest — 1/1 passed

- `preservesExactCentsAndRejectsLossyOrInvalidAmounts`

### InMemoryTransactionRepositoryTest — 1/1 passed

- `saveAndFindById`

### TransactionControllerTest — 1/1 passed

- `createTransactionReturns201`

## Diff summary

These counts compare against the captured working tree at the start of this slice, so they exclude prior uncommitted Create/Read/Update work. They include untracked new source/test files that ordinary `git diff --stat` would omit.

| File | Added | Removed |
| --- | ---: | ---: |
| `src/main/java/com/finguard/transactionapi/finance/domain/LedgerEntry.java` | 14 | 3 |
| `src/main/java/com/finguard/transactionapi/finance/service/LedgerEntryService.java` | 85 | 13 |
| `src/main/java/com/finguard/transactionapi/finance/repository/LedgerEntryRepository.java` | 2 | 1 |
| `src/main/java/com/finguard/transactionapi/finance/dto/ListFinanceEntriesFilter.java` | 9 | 0 |
| `src/main/java/com/finguard/transactionapi/finance/exception/FinancialAccountNotFoundException.java` | 5 | 0 |
| `src/test/java/com/finguard/transactionapi/finance/LedgerEntryDeleteAndListServiceTest.java` | 294 | 0 |
| `src/test/java/com/finguard/transactionapi/finance/LedgerEntryDeleteAndListIntegrationTest.java` | 622 | 0 |

Seven source/test files: **+1031 / -17**. One additional verification document is this record. No staging, commit or push was performed.

## Known limits and assumptions

- User IDs remain trusted internal inputs; no authentication or Finance REST controller was added.
- List uses ordinary read-only transactions at the configured PostgreSQL isolation. At READ_COMMITTED, content/count are not guaranteed to share one snapshot when other transactions write concurrently. Offset pages can shift between requests; the deterministic UUID tie-breaker does not provide snapshot pagination.
- Direct SQL or bulk writes that ignore version checks can bypass JPA optimistic locking; future ledger writers must participate in the version protocol.
- List relies on the existing ledger invariant that confirmed imports are materialized as ledger rows. It does not implement CSV import/confirmation or query staging candidates; confirmed import fixtures demonstrate inclusion.
- Existing Create/Update account/category cross-row validation limitations remain outside this slice. Delete/List deliberately permit historical inactive accounts.
- Verification demonstrates local PostgreSQL correctness for the specified cases; it is not a load test or current AWS deployment claim.

No Phase 8 transaction/risk, AWS/Terraform, Angular, AI, authentication, portfolio documentation, or already-applied migration changes were made. Implementation remains uncommitted for Design Owner review.
