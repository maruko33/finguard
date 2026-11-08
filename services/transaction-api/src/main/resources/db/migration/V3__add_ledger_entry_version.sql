-- Add optimistic locking without changing the already-applied Finance foundation.
-- Existing rows start at version 0; Hibernate increments it on versioned updates.
ALTER TABLE ledger_entry
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_entry_version CHECK (version >= 0);
