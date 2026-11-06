# Phase 9.3.3 verification: internal ledger update

Verified on 2026-10-24 (America/Toronto), on `feature/finance-recorder` at base commit `ac11710467c2ee7459be13b6b18e0050df767295`, including the existing uncommitted Phase 9.3.2 Create/Read implementation and the uncommitted changes below.

## Files changed in this slice

Existing files extended (relative to `services/transaction-api/`):

- [src/main/java/com/finguard/transactionapi/finance/domain/LedgerEntry.java](../../src/main/java/com/finguard/transactionapi/finance/domain/LedgerEntry.java)
- [src/main/java/com/finguard/transactionapi/finance/service/LedgerEntryService.java](../../src/main/java/com/finguard/transactionapi/finance/service/LedgerEntryService.java)
- [src/main/java/com/finguard/transactionapi/finance/dto/FinanceEntryResponse.java](../../src/main/java/com/finguard/transactionapi/finance/dto/FinanceEntryResponse.java)
- [src/test/java/com/finguard/transactionapi/finance/FinanceSchemaIntegrationTest.java](../../src/test/java/com/finguard/transactionapi/finance/FinanceSchemaIntegrationTest.java)

New files:

- [src/main/java/com/finguard/transactionapi/finance/dto/UpdateFinanceEntryRequest.java](../../src/main/java/com/finguard/transactionapi/finance/dto/UpdateFinanceEntryRequest.java)
- [src/main/java/com/finguard/transactionapi/finance/exception/FinanceEntryConflictException.java](../../src/main/java/com/finguard/transactionapi/finance/exception/FinanceEntryConflictException.java)
- [src/main/resources/db/migration/V3__add_ledger_entry_version.sql](../../src/main/resources/db/migration/V3__add_ledger_entry_version.sql)
- [src/test/java/com/finguard/transactionapi/finance/LedgerEntryUpdateServiceTest.java](../../src/test/java/com/finguard/transactionapi/finance/LedgerEntryUpdateServiceTest.java)
- [src/test/java/com/finguard/transactionapi/finance/LedgerEntryUpdateIntegrationTest.java](../../src/test/java/com/finguard/transactionapi/finance/LedgerEntryUpdateIntegrationTest.java)
- [src/test/java/com/finguard/transactionapi/finance/LedgerEntryVersionMigrationIntegrationTest.java](../../src/test/java/com/finguard/transactionapi/finance/LedgerEntryVersionMigrationIntegrationTest.java)
- `docs/phase9/phase9_3_3_verification.md` (this record)

The earlier uncommitted Create/Read files, repositories, verification record and local ZIP files remain in place. V1, V2, Money, Phase 8 application code, AWS/Terraform, the Maven wrapper and portfolio documentation were not changed by this slice. Existing FinanceSchemaIntegrationTest only changes its fresh-schema migration count from two to three.

## Method and DTO contracts

```java
@Transactional
public FinanceEntryResponse updateEntry(
        UUID currentUserId, UUID entryId, UpdateFinanceEntryRequest request)
```

`UpdateFinanceEntryRequest` is a full replacement of `UUID categoryId`, `EntryType entryType`, `BigDecimal amount`, `String currencyCode`, `LocalDate transactionDate`, `Instant occurredAt`, and `String description`, plus required `Long expectedVersion`. Null category and occurrence time explicitly clear those fields. All other editable fields and a nonnegative expected version are required; an empty description remains valid, with the existing 500-character limit.

The request has no user/account/entry identity, creation timestamp, import metadata, request key, bank transaction ID or deletion metadata. Those scalar entity mappings are immutable for ORM updates. The entity exposes only a whitelist replacement method; it does not assign a version from request data.

`FinanceEntryResponse` adds `long version` to the existing Create/Read/Update response. Newly persisted entries return version 0. The internal Create and Read method signatures are unchanged.

## Validation and transaction semantics

1. The trusted user and entry ID are resolved through `findByIdAndUserIdAndDeletedAtIsNull`. Missing, another user's, and soft-deleted entries raise the same FinanceEntryNotFoundException, before request/version validation. No unauthenticated controller or authentication mechanism was added.
2. A missing/negative expected version raises InvalidFinanceEntryException. A version different from the loaded row raises FinanceEntryConflictException without mutation.
3. Shared private service methods reuse the existing Create rules: positive exact Money within NUMERIC(19,2), Java-recognized uppercase currency, original account owned and active, exact account currency match, and optional category owned/active/matching the replacement entry type. Required fields are checked internally.
4. Only after validation does the managed entity replace editable fields. `updatedAt` advances to the current server time at microsecond precision, or one microsecond beyond the prior value if necessary. This guarantees even identical replacements are dirty and increment the version. `transactionDate` and optional `occurredAt` remain independent.
5. Hibernate's `@Version Long` is null before insertion, allowing Spring Data to detect a new assigned-UUID entity; Hibernate initializes it to 0. Updates use the version in the SQL write predicate and increment it, rather than trusting only the earlier Java version comparison.
6. `entryRepository.flush()` occurs inside the service transaction before response mapping, so the returned version reflects the executed update. Spring OptimisticLockingFailureException and JPA OptimisticLockException become FinanceEntryConflictException with their causes retained. The runtime exception rolls back the transaction; no automatic retry overwrites another writer's data.
7. Default REQUIRED propagation joins a caller's existing transaction. Flush is not commit: when this method owns the transaction, commit occurs before the proxy returns; when a caller owns it, the caller controls commit. An integration test explicitly rolls back after a successful service flush and verifies every row field and version revert.

## Flyway V3

[V3__add_ledger_entry_version.sql](../../src/main/resources/db/migration/V3__add_ledger_entry_version.sql) adds only:

```sql
ALTER TABLE ledger_entry
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_entry_version CHECK (version >= 0);
```

V2 was checked byte-for-byte against both its pre-task contents and the committed version and is unchanged. Existing rows receive version 0. A real PostgreSQL test creates a V2 schema and preexisting manual/imported/soft-deleted data, applies only V3, compares all prior columns, checks new-insert defaults and null/negative rejection, validates Flyway history, and verifies a second migrate applies zero migrations.

The full application context test also applied V3 to the existing local `public` schema, which was previously at V2. The integration tests use separate randomly named schemas and remove only their own schemas. The existing database container and volume were not reset or replaced.

## Full Maven result

Executed from `services/transaction-api/` through the established local wrapper:

```bash
AWS_REGION=ca-central-1 python3 scripts/mvn-local.py test
```

**BUILD SUCCESS — 44 tests, 0 failures, 0 errors, 0 skipped; exit code 0.** This is an executed PostgreSQL 17 result, not a mocked-only claim. The first full suite run passed. The final compiled concurrent-test configuration was checked to use TestConfiguration, keeping its test-only repository read barrier out of application component scanning.

| Test class | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| TransactionApiApplicationTests | 1 | 0 | 0 | 0 |
| TransactionServiceTest | 2 | 0 | 0 | 0 |
| FinanceSchemaIntegrationTest | 5 | 0 | 0 | 0 |
| LedgerEntryServiceIntegrationTest | 7 | 0 | 0 | 0 |
| LedgerEntryServiceTest | 3 | 0 | 0 | 0 |
| LedgerEntryUpdateIntegrationTest | 12 | 0 | 0 | 0 |
| LedgerEntryUpdateServiceTest | 10 | 0 | 0 | 0 |
| LedgerEntryVersionMigrationIntegrationTest | 1 | 0 | 0 | 0 |
| MoneyTest | 1 | 0 | 0 | 0 |
| InMemoryTransactionRepositoryTest | 1 | 0 | 0 | 0 |
| TransactionControllerTest | 1 | 0 | 0 | 0 |

The simultaneous-writes test synchronizes two genuine scoped JPA reads before allowing either writer to continue. It records different `pg_backend_pid()` values and version 0 in both transactions, then asserts exactly one committed response at version 1 and one FinanceEntryConflictException with an actual Spring/JPA optimistic-lock cause. A fresh read and direct SQL verify the winning editable fields, version and preserved metadata; the losing transaction contributes no partial changes. Await/future deadlines bound the test; no timing sleeps or mocked database writes are used.

A separate stale-client test reads twice in independent service transactions, commits the first update, then rejects the second request's old expected version without changing the committed winner.

The local execution log is `/tmp/finguard-933-maven.log`; Surefire XML reports are under `target/surefire-reports/`. These generated artifacts are not staged or committed. The following names/results are transcribed from the actual Surefire XML files.

## Executed test names

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

## Assumptions and remaining limits

- Full replacement is intentional: null optional values clear prior values; omission is not PATCH semantics. The account association is fixed. Both manual and imported entries can have their editable fields updated while preserving provenance.
- Existing Money semantics are retained: exact extra trailing zeros are accepted when no rounding is required; nonzero fractional digits beyond cents are rejected. Finance continues to use two decimal places even for currencies whose usual minor-unit convention differs.
- The caller supplies a trusted internal user ID until real authentication exists. No Delete or List implementation was added.
- PostgreSQL's usual READ_COMMITTED isolation and ledger optimistic locking do not freeze account/category rows. Concurrent deactivation or account-currency changes after validation remain an existing cross-row consistency risk. Composite foreign keys still enforce owner/type consistency; active status and currency equality are service checks. Future account/category write workflows need a coordinated policy.
- Direct SQL or bulk updates that ignore the version can bypass JPA optimistic locking. Future ledger writers, including Soft Delete, must participate in version checking/increments. The current `deletedAt` mapping is deliberately non-updatable in this slice; Soft Delete must revisit that mapping explicitly.
- This run proves the specified local two-session races and rollback behavior; it is not a load benchmark or a distributed deployment test.

No commit, push, database-volume reset, AWS resource operation, or Phase 8 behavior change was performed. The code remains uncommitted for review.
