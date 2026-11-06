# Phase 9.6.2 — Reviewable positive-credit data requirements

Status at closeout (2026-11-01): internal persisted preview, manual review and atomic confirmation are now implemented in the working tree. The [Import closeout verification](import_acceptance_closeout.md) records the current executed result. Valid positive credits retain facts and CREDIT direction with null EntryType and remain ineligible for confirmation.

The original design note below is historical: statements about future/unimplemented persistence and confirmation describe its earlier scope, not the current implementation.
Recorded: 2026-10-28, following the Phase 9.6.1 targeted credit-validation review.

## Required behavior

Phase 9.6.2 must retain safe, structured transaction data for valid positive RBC credits. Persisting only a source record number and `REVIEW_REQUIRED` issue code is insufficient: the reviewer needs the transaction facts to decide its meaning.

The future review representation must preserve:

- Source provenance: selected bank/profile, import/batch association and logical CSV source record number.
- Parsed transaction date and nullable occurred-at value; do not invent a time for date-only input.
- Validated normalized description, exact positive amount magnitude and original CAD/USD currency.
- The observed credit direction/sign, separately from its unresolved business classification.
- Review status and safe, structured reason codes associated with that same transaction.

Do not assign a provisional INCOME or EXPENSE merely to fit `NormalizedImportRow` or permit ledger insertion. Payment/refund/income interpretation remains unresolved until the reviewed confirmation rules support it. The current in-memory ImportRowOutcome now preserves these facts and leaves positive-credit EntryType null. Persistent ownership, schema and confirmation design still require their own review; this note does not prescribe a persistence schema.

## Validation and privacy requirements

- Keep the Phase 9.6.1 ordering: both signs pass existing Money invariants and required-description validation before acceptance/review classification. Invalid precision, overflow, zero and missing/invalid descriptions belong in rejected outcomes.
- Preserve the minimum validated fields necessary for review; do not persist entire raw CSV records or unrelated account/card columns as a shortcut.
- Treat retained descriptions and amounts as private financial data. Associate review data with trusted ownership context, scope reads accordingly, and keep these values out of logs and diagnostic messages.
- Do not promote Cheque Number to a guaranteed unique transaction identifier.
- Revalidate applicable transaction and account/user-context rules before a future confirmation may create a ledger entry.

## Phase 9.6.2 acceptance checks

1. A valid positive CAD or USD credit survives preview persistence/retrieval with its source record, date, description, exact amount, currency and observed credit direction available for review.
2. Its business classification remains unresolved; preview creation does not insert a ledger entry.
3. Invalid monetary values and descriptions remain rejected rather than being promoted to reviewable credits.
4. Safe reason codes remain associated with the structured transaction, without raw sensitive input in diagnostics.
5. Ownership checks and data-minimization tests cover the future persistent representation.

The 2026-11-01 follow-up now returns one structured in-memory outcome for every accepted, review-required or rejected data record. Valid credits retain their details and source direction without a fabricated EntryType; rejected rows retain independently reliable fields with invalid fields null. Phase 9.6.2 must carry this structured data through persistent preview instead of reducing it back to issue codes alone. Persistence/retrieval, trusted ownership enforcement and confirmation remain unimplemented; no controller, classifier or confirmation workflow was added.
