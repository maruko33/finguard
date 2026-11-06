# Codex task — FinGuard Phase 9 database foundation

You are working locally in the FinGuard Java 21 / Spring Boot 4.1 repository. The Finance MVP is a **separate domain** from the existing Phase 8 banking-risk Transaction API. Inspect all existing code, `pom.xml`, migrations, Docker Compose, and tests first.

The reviewed proposed design is provided in `phase9_finance_schema.sql`, `phase9_finance_erd.puml`, and `phase9_schema_design_notes.md` (copy these into `docs/phase9/` as design artifacts). Treat the SQL as a **proposal requiring validation**, not as something already executed.

## Implement this narrow milestone

1. Inspect `src/main/resources/db/migration` and choose the next available Flyway version; **create an additive migration**, do not edit already applied V1 or modify the Phase 8 risk `transactions` table. Implement/adjust the proposed six tables and indexes. Preserve owner/type composite FKs, source batch+row uniqueness, positive exact `NUMERIC(19,2)` amounts, nullable CSV timestamps, and candidate retention independence.
2. Validate the SQL against actual local PostgreSQL 17, fix any syntax/DDL issues, and report each deviation from the proposal. Do not reset volumes or drop data.
3. Add Java finance-domain enums/entities/value types as needed (`Money` / `BigDecimal`; `EntryType`, `ImportStatus`; `LedgerEntry`, `ImportBatch`, `ImportCandidate`, `FinancialAccount`, `Category`). Make Spring Data JPA mappings and persistence relationships correct; keep Phase 8 domain separate. Add `@Version` on the import batch and appropriate repository locking methods **if implementing repository interfaces**.
4. Add at least integration tests for Flyway migration + primary/foreign/unique/check constraints with a real PostgreSQL test database (Testcontainers preferred if already practical). Assert duplicate source batch+row is rejected; two identical amount/time rows with different source rows remain valid; ownership and category type FKs work. Do **not** declare Testcontainers to have run if Docker is unavailable.
5. Update a short README subsection with local migration workflow and remaining design risks.

## Stop before implementing

- **Do NOT** generate the entire Angular frontend, implement an unauthenticated CSV API, create AWS resources, modify Terraform, or implement speculative AI classification.
- Do not invent OAuth/identity plumbing. If authentication is missing, keep APIs disabled/unexposed and explicitly report that blocker; schema can be validated with isolated fixtures.
- Do not add secrets or real bank statements to git; do not print `.env` values.

## Verification report

Return a concise list of files changed, migration version, SQL changes with reasons, actual commands run + results, and any unresolved items. Keep the diff small and explain major tradeoffs. Preserve existing Maven tests and rerun `scripts/mvn-local.py` if available.
