# Phase 9.3.2 verification

Implemented internal manual ledger creation and lookup. No Finance REST endpoints or authentication were added. Finance repositories use the existing application EntityManager/DataSource.

## Files

Created under src/main/java/com/finguard/transactionapi/finance:
- dto/CreateFinanceEntryRequest.java
- dto/FinanceEntryResponse.java
- repository/LedgerEntryRepository.java
- repository/FinancialAccountRepository.java
- repository/CategoryRepository.java
- exception/FinanceEntryNotFoundException.java
- exception/InvalidFinanceEntryException.java

Completed the pre-existing untracked service/LedgerEntryService.java stub.
Updated domain/LedgerEntry.java by adding a manual factory; retained all existing entity mappings and the protected JPA constructor. No migration or Money changes.

Created tests:
- src/test/java/com/finguard/transactionapi/finance/LedgerEntryServiceTest.java
- src/test/java/com/finguard/transactionapi/finance/LedgerEntryServiceIntegrationTest.java

## Contracts and rules

- `@Transactional public FinanceEntryResponse createEntry(UUID currentUserId, CreateFinanceEntryRequest request)`
- `@Transactional(readOnly = true) public FinanceEntryResponse findById(UUID currentUserId, UUID entryId)`

The request contains accountId, nullable categoryId, entryType, BigDecimal amount, currencyCode, LocalDate transactionDate, nullable Instant occurredAt, and description. It has no owner ID or import/source fields.

Creation requires a trusted nonnull user, owned active account, and (when supplied) owned active category matching entry type. Money validates positive NUMERIC(19,2) amounts without rounding; the service additionally requires a Java-recognized ISO currency and exact account currency match. Required fields and the 500-character description limit are checked internally, without relying on future controller validation. Empty descriptions are valid; null descriptions are rejected. The existing Money behavior accepts exact trailing zeros beyond two decimal places when no value is lost.

The manual factory generates a UUID and equal created/updated timestamps at PostgreSQL microsecond precision. Import metadata, request keys, bank IDs and deletion time remain null. Transaction date and occurrence time remain independent.

Lookup uses only `findByIdAndUserIdAndDeletedAtIsNull`. Missing, unauthorized and deleted entries all raise the same FinanceEntryNotFoundException. Validation uses InvalidFinanceEntryException.

## Test results

Command (from services/transaction-api):
`AWS_REGION=ca-central-1 python3 scripts/mvn-local.py test`

BUILD SUCCESS: 21 tests, 0 failures, 0 errors, 0 skipped.

New integration tests (7/7 passed):
- validExpensePersistsAndReadsWithIndependentDates
- validIncomeAndNullCategory
- rejectsInvalidAccountOwnerAndInactiveOrMissingAccount
- rejectsInvalidCategoryOwnerTypeInactiveAndMissing
- rejectsZeroNegativeScaleAndOverflowWithoutPersistence
- rejectsInvalidAndMismatchedCurrency
- hidesOtherUsersMissingAndSoftDeletedEntries

New unit tests (3/3 passed):
- rejectsMissingRequiredFieldsBeforeRepositoryAccess
- rejectsInvalidMoneyBeforeRepositoryAccess
- lookupUsesOwnerAndSoftDeletePredicateOnly

Existing FinanceSchemaIntegrationTest (5/5 passed):
- sourceUniquenessAllowsIdenticalValuesAndCandidatePurge
- ownershipTypeAndSourceChecks
- candidateAndBatchConstraints
- durableIdempotencyKeysAndTerminalTimestamps
- hibernateValidatesAndLoadsAllFinanceMappings

Existing MoneyTest (1/1 passed): preservesExactCentsAndRejectsLossyOrInvalidAmounts.
Existing TransactionApiApplicationTests (1), TransactionServiceTest (2), TransactionControllerTest (1), and InMemoryTransactionRepositoryTest (1) all passed.

An initial full run without AWS_REGION had 21 tests, 0 failures and 1 error in the existing application context test because its SQS bean requires a region. The documented region setting resolved it. No AWS resources were created. Integration fixtures use isolated randomly named schemas, following the existing PostgreSQL test pattern; only those test schemas are removed.

## Assumptions and remaining risks

No schema incompatibility requires migration. V2 permits null category and occurrence time. The existing entity lacked a public construction path, requiring the additive factory.

V2 currency checks accept any three uppercase letters; the service deliberately narrows new manual inputs to Java-recognized ISO codes. The existing two-decimal Money convention applies even to currencies whose conventional minor units differ.

Current-user IDs remain trusted internal inputs until real authentication is implemented. These services must not be exposed using client-supplied owner IDs.

At default READ_COMMITTED isolation, an account/category can be deactivated or account currency changed concurrently after validation. Existing composite database foreign keys enforce owner/category-type consistency, but active status and currency equality are application checks without database enforcement or locking. Future account/category update workflows need a coordinated concurrency policy.

Creation has no idempotency contract in this slice: retries can create duplicate manual entries. V2 supports a client_request_key for a future explicit retry contract.

No database reset, volume destruction, commit or push was performed.
