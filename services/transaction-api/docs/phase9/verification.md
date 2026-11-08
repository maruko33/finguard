# Phase 9 verification — 2026-10-17

Implemented in `services/transaction-api` (the brief's folder contains only design inputs).

Changed files:
- `src/main/resources/db/migration/V2__create_finance_foundation.sql`
- `src/main/java/com/finguard/transactionapi/finance/domain/`: `AppUser.java`, `FinancialAccount.java`, `Category.java`, `ImportBatch.java`, `ImportCandidate.java`, `LedgerEntry.java`, `EntryType.java`, `ImportStatus.java`, `AccountType.java`, `Money.java`
- `src/test/java/com/finguard/transactionapi/finance/`: `FinanceSchemaIntegrationTest.java`, `MoneyTest.java`
- `README.md`
- `docs/phase9/`: original `phase9_finance_schema.sql`, `phase9_finance_erd.puml`, `phase9_schema_design_notes.md`, and this report.

Only V1 existed before implementation, so V2 was selected. V1 and all Phase 8 code remain unchanged. The original design artifacts are copied verbatim. SQL deviations: require non-null source row with non-null batch to close a CHECK/UNKNOWN loophole; require candidate entry type with a category to prevent NULL bypassing the composite category FK. Updated migration header only otherwise. All six tables, indexes and owner/type composite FKs are retained. Java CHAR columns explicitly use JDBC CHAR to pass Hibernate validation. Relationships are lazy read-only views over scalar FK columns; no cascaded deletes, repositories, controllers, or workflow services were introduced.

Commands and results:
- `docker compose -f ../../compose.yaml ps`: initially denied by sandbox; approved retry succeeded, no containers running.
- `docker compose -f ../../compose.yaml up -d postgres`: started existing PostgreSQL service with its existing volume.
- `python3 scripts/mvn-local.py clean package`: first run applied V2 to local public schema and isolated tests; exposed Hibernate CHAR mapping mismatch. Second run after mapping fix passed finance tests but failed existing application context because no AWS region was configured.
- `AWS_REGION=ca-central-1 python3 scripts/mvn-local.py clean package`: **BUILD SUCCESS**, 11 tests, zero failures/errors/skips, Java 21 / Spring Boot 4.1.0 / PostgreSQL 17.11. Includes 5 real PostgreSQL integration tests and Money validation. No Testcontainers used. Existing context test validated local public schema at V2; isolated integration tests migrated V1 + V2 and removed only their generated test schema.
- `git diff --check`: passed.
- `diff -u` between proposal and migration: confirmed only the documented header and two constraint changes (exit 1 means expected differences).

Coverage includes primary key, foreign key, unique and check constraints; cross-owner accounts/categories/batches; mismatched category type; source field pairing; zero/negative/overflow amounts; invalid currency; invalid selected staging rows; category name case uniqueness; terminal timestamps; candidate JSON/acknowledgment checks; source retry rejection after staging purge; identical amounts with NULL and identical timestamps on different source rows; manual/bank idempotency after soft deletion; Flyway validation and Hibernate validation/loading of all six entity types.

Remaining work: verified authentication/authorization blocks finance API exposure. Future services must enforce batch transitions, exact input scale through Money, category/account rules, confirmation locks, duplicate warning freshness, and retention/privacy policy. Repository locking/concurrency tests are deferred with those workflows. No AWS resources were created or SQS messages sent; no volume reset or existing application data deletion occurred.
