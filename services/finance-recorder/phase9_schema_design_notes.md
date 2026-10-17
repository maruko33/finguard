# FinGuard Phase 9 — Finance Schema Design v1 (Design Freeze Candidate)

**Scope:** Phase 9 personal-finance MVP, additive to Phase 8 Transaction API / SQS / Lambda. **Status:** designed, **NOT migrated or executed**. Source artifacts: `phase9_finance_schema.sql` and `phase9_finance_erd.puml`.

## 0. Product-level decisions (proposed defaults; revisit if requirements change)

- Deploy **one Spring Boot modular monolith**, Finance Domain isolated from the Phase 8 risk `Transaction` domain and its tables. Angular uses REST; it does not connect to PostgreSQL.
- Start with **INCOME** and **EXPENSE** only, primarily **CAD**. Account-to-account transfers and multi-currency FX accounting are separate later changes. `Money` in Java uses `BigDecimal` + currency; DB stores `NUMERIC(19,2)` + 3-character currency code.
- Java `UUID` IDs are application-generated; no `SERIAL`, `double`, or PostgreSQL native enums. Business enums are represented by `VARCHAR` + `CHECK` constraints, and by Java enums.
- **User-owned categories** are database rows, not enums. Category can be renamed/disabled; a Ledger Entry can refer to an inactive category for history. An INCOME category cannot attach to an EXPENSE record.
- Per user: one or more `FinancialAccount`s; each entry belongs to one account. Frontend and APIs must never accept a user identity supplied by untrusted request body for authorization; derive current user from verified authentication.
- Personal bookkeeping is **not** a double-entry accounting engine or a payment platform. No public deployment containing personal financial data until proper authentication/authorization + HTTPS are implemented.

## 1. Six database tables and lifecycles

| Table | Responsibility | Retention |
| --- | --- | --- |
| `app_user` | Application identity (`auth_subject` from verified identity provider/session, not plaintext credentials) | while account active / subject to privacy controls |
| `financial_account` | Chequing, savings, cash, credit card bookkeeping source; one owner | durable; deactivate rather than cascade delete |
| `category` | User-defined income or expense label | durable; `active=false` rather than deleting a referenced category |
| `import_batch` | **One upload workflow**, holds upload retry key, file hash, owner/account, status, optimistic-lock version, summary counts | retain history after staging cleanup |
| `import_candidate` | **One CSV row staged for review**; parse/validation errors, suggested category, warning/selection decision | delete terminal batches' candidates after retention window (suggest 30 days after `closed_at`); not required for long-term source linkage |
| `ledger_entry` | Committed expense/income including manually entered data | durable; application `deleted_at` for logical deletion and audit-friendly correction |

`ImportBatch` is created on **upload**, not only on confirm. `ImportCandidate` rows are drafts, even when stored in PostgreSQL. `LedgerEntry` is the canonical committed record and reporting source. On confirm **copy** selected valid candidates into `ledger_entry`, do not move/rename a database table. In a single transaction mark `import_batch.status=CONFIRMED`, record counts and `confirmed_at` / `closed_at`; the candidates may be purged later, independently.

Valid ImportBatch transitions: `DRAFT → REVIEWING → CONFIRMED` or `REVIEWING → CANCELLED`; `DRAFT → FAILED` for fatal processing. Do not permit `CONFIRMED → REVIEWING`. The SQL constraints require `closed_at` for `CONFIRMED`/`CANCELLED`/`FAILED`, and `confirmed_at` *only* for `CONFIRMED`.

## 2. Keys, FKs, consistency

- Each table's `id UUID` is its **Primary Key**.
- `UNIQUE(import_candidate.batch_id, row_number)` prevents staging the *same file row in the same Batch* twice.
- `UNIQUE(ledger_entry.source_batch_id, source_row_number)` prevents the *same Candidate* from being copied twice, **even after its staging row is purged**. Source columns both nullable for manual entries. `ledger_entry.source_batch_id` references the durable `import_batch`, not the purged Candidate.
- `UNIQUE(import_batch.user_id, upload_key)` handles **client retry of the same upload request**. Same key + same file hash → return existing preview; same key + different file hash → `409 Conflict`. The file hash is not an automatic duplicate-transaction rejection: the same CSV may contain legitimately repeated purchases.
- `UNIQUE(ledger_entry.account_id, bank_transaction_id) WHERE bank_transaction_id IS NOT NULL` blocks true duplicate imported bank IDs, **only populate this field if the source provides a stable, reliable account-scoped transaction ID**. Do *not* synthesize this value from amount/time/description.
- `UNIQUE(ledger_entry.user_id, client_request_key)` (when non-null) handles optional idempotency of manual `POST /entries` retries.
- Composite ownership foreign keys `(account_id, user_id)` / `(category_id, user_id, entry_type)` prevent a user's record from accidentally referencing another user's account/category or a category of the opposite type. More checks still belong in service authorization and validation.
- Foreign keys intentionally do **not** cascade-delete a user's committed ledger. Account erasure/privacy deletion requires a separate, explicit administrative operation and policy.

## 3. Duplicate detection is NOT idempotency

**Business duplicate warning (heuristic):** compare a candidate to other candidates from this Batch, active previews from other batches, and committed Ledger Entries for the **same financial account**, same entry type, amount and currency. If **both sides provide a trusted transaction timestamp**, warn when timestamps differ by at most **15 minutes**. The suggested window is a *product rule, not a unique index*. If the bank CSV only has a DATE, show a weaker *same-day* warning instead; never invent 00:00 as the actual transaction time. Confirming repeated ¥30 game purchases is allowed after explicit user review.

**Strong duplicate:** trusted bank-specific transaction ID for that account (if present), or *same batch+row already inserted*. Block these using DB unique constraints.

For warning acknowledgment, store `duplicate_warning`, `duplicate_acknowledged`, and a SHA-256 `duplicate_match_signature` over the currently matched record identities and relevant candidate fields. Compute a deterministic, versioned format in the application. On candidate edit reset acknowledgment and recompute. At confirm, **re-evaluate matches**. If a warning was added or signature changed since approval, return `409` requiring an updated review; do not silently discard or accept changed duplicates. Detection is *best-effort* for weak matches, not mathematically perfect; no reliable bank ID means absolute duplicate prevention without blocking legitimate repeats is impossible.

## 4. Confirm transaction and concurrency

At a `@Transactional` service boundary (PostgreSQL default READ COMMITTED):

1. Derive authenticated `userId` from server security context. `SELECT import_batch ... WHERE id=? AND user_id=? FOR UPDATE`; if confirmed, return **stored summary** without inserting again; if cancelled/failed, return `409`.
2. `SELECT financial_account ... WHERE id=? AND user_id=? FOR UPDATE` to serialize **concurrent confirmations for the same account**. Any other write path requiring the *same weak duplicate guarantee* (e.g. manual creation) must obey the same account lock; trusted-bank-ID uniqueness is still enforced regardless.
3. Load selected candidates; check required fields and category ownership/type; calculate fresh duplicate matches. If new warning appears, require renewed review and return `409`; no inserts occur.
4. Bulk insert eligible candidates into `ledger_entry` using stable `source_batch_id + source_row_number`; propagate unique conflicts as `409` with rollback, not blanket `ON CONFLICT DO NOTHING` hiding data problems.
5. Update batch to `CONFIRMED`, set counts + `confirmed_at` and `closed_at`, commit. If any write fails, entire transaction rolls back. API returns the successful result. Repeated confirm gets the stored result.

**Review editing:** require `status=REVIEWING`, ownership, `@Version` optimistic-lock check (or HTTP `If-Match`) so two browser tabs do not overwrite each other's choices. `PATCH /review` should reset acknowledgment if relevant input changes.

## 5. Invalid CSV and privacy

- Parsing/validation errors can leave row fields NULL in **staging candidates**; `validation_errors JSONB` stores row-level error codes/messages; invalid rows default to `selected_for_import=false`. The CHECK prohibits selecting any row missing type, amount, currency, date or having remaining validation errors.
- A fatally unparseable CSV can fail upload with HTTP 422 without producing a reviewable Batch. If a batch was already persisted, set appropriate terminal state and `closed_at` on failure.
- Never print CSV contents, bank identifiers, file hash inputs, financial amounts, credentials, or arbitrary third-party descriptions in application logs. Do not forward raw bank CSV to AI by default.
- **Staging retention:** propose 30 days from `closed_at` for confirmed/cancelled/failed batches, then purge candidates in a controlled cleanup job. Keep summary in `import_batch` and stable source pointers in `ledger_entry`. Reviewable batches must not be purged merely because they are old without an explicit expiry/cancel UX policy.
- Soft deleted `ledger_entry.deleted_at != null` rows are excluded from reporting and weak-match detection. Trusted bank ID/source-row uniqueness intentionally still covers those rows (restore/correction rather than duplicate reimport). Full erasure privacy semantics remain a separate requirement.

## 6. Indexes and performance

- Operational: `import_batch(user_id,status,created_at DESC)` for Resume, `import_batch(account_id,status)` for pending preview lookup, `import_candidate(batch_id,selected_for_import)` for Confirm.
- Reporting: `ledger_entry(user_id,transaction_date DESC)`, `(user_id,category_id,transaction_date DESC)` with `deleted_at IS NULL`.
- Dup warnings: `ledger_entry(account_id,occurred_at)` partial index, and a date index for DATE-only matches. These indexes support efficient **set-based queries**; do not loop through 100 candidates doing 100 individual SELECTs.
- No promised P95 until load testing; prefer correct and measurable queries over premature optimization.

## 7. API names and initial implementation order

API prefix ` /api/v1/finance ` (align the sequence diagram if it previously used `/api/v1/import-batches`):

- `POST /entries`, `GET /entries`, `PATCH /entries/{id}`, `DELETE /entries/{id}` — manual CRUD, authenticated user only.
- `POST /imports` — multipart CSV, accountId, `Idempotency-Key`; returns preview ID.
- `GET /imports/{id}` and `GET /imports?status=REVIEWING` — preview/restore.
- `PATCH /imports/{id}/review` — modify candidates, selection, categories, acknowledge duplicate warnings with version control.
- `POST /imports/{id}/confirm` — atomic Confirm.
- `POST /imports/{id}/cancel` — mark cancelled, schedule staging cleanup.
- `GET /reports/monthly?month=YYYY-MM` — aggregated DTO only, Angular renders chart.

**Delivery order:** (1) apply migration locally and integration-test constraints; (2) implement `LedgerEntry` manual CRUD + basic category/account setup; (3) implement upload/preview and persisted candidate review; (4) confirm/idempotency + duplicate warning scenarios; (5) monthly reporting DTO; (6) Angular screens; (7) later AI/budget/offline increments. Auth must gate any personal data access, **never run an unauthenticated publicly accessible finance application**.

## 8. Pre-merge / migration checklist

1. Inspect existing Spring Boot `db/migration/V*.sql`: name this file with the **next unused version**; **never edit applied V1** and never touch Phase 8's transaction table.
2. Check compatibility with Java 21 / Spring Boot 4.1 and local Docker PostgreSQL 17. Run Flyway locally only, not AWS; back up meaningful local data first.
3. Confirm `import_batch` idempotent upload, concurrent Confirm A/A, and concurrent Confirm A/B behavior with integration tests, not mocks alone.
4. Verify FK rejects cross-user account/category and mismatched INCOME/EXPENSE category; row uniqueness rejects duplicates and invalid selected candidates cannot be inserted.
5. Verify `BigDecimal` precision and date/timestamp rules, zero/negative amount validation, reports excluding logically deleted entries.
6. Check `git diff --check`, `mvn clean package` via `scripts/mvn-local.py` where available; stage no `.env`, logs, CSV containing actual financial data, Secrets Manager values, or Terraform State.

**Scope note:** Requirements for the auth provider, production privacy retention, and official bank CSV templates need owner confirmation before publishing/production use. The SQL file defines the *data model*, not an authentication implementation or complete accounting policy.
