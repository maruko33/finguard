-- FinGuard Phase 9 Finance Schema, PostgreSQL 17
-- Additive V2 migration; reviewed proposal is retained in docs/phase9/.
-- Preserves pre-existing Phase 8 transaction/risk tables.
-- Java application generates UUIDs (no dependency on pgcrypto/uuid-ossp).

CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    auth_subject VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    preferred_currency_code CHAR(3) NOT NULL DEFAULT 'CAD',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_user_currency CHECK (preferred_currency_code ~ '^[A-Z]{3}$')
);

CREATE TABLE financial_account (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    name VARCHAR(120) NOT NULL,
    account_type VARCHAR(30) NOT NULL,
    currency_code CHAR(3) NOT NULL DEFAULT 'CAD',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_account_type CHECK (account_type IN ('CASH','CHEQUING','SAVINGS','CREDIT_CARD')),
    CONSTRAINT chk_account_currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT uq_account_user_name UNIQUE (user_id, name),
    CONSTRAINT uq_account_id_user UNIQUE (id, user_id)
);

CREATE TABLE category (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    name VARCHAR(100) NOT NULL,
    entry_type VARCHAR(10) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_category_entry_type CHECK (entry_type IN ('INCOME','EXPENSE')),
    CONSTRAINT uq_category_identity_owner_type UNIQUE (id, user_id, entry_type)
);
CREATE UNIQUE INDEX uq_category_name_per_type_ci ON category (user_id, entry_type, lower(name));

CREATE TABLE import_batch (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    account_id UUID NOT NULL,
    upload_key UUID NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    status VARCHAR(15) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 0,
    confirmed_count INTEGER NOT NULL DEFAULT 0,
    excluded_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ, -- confirmation/cancellation/failure completion time for staging retention
    CONSTRAINT fk_batch_account_owner FOREIGN KEY (account_id, user_id)
        REFERENCES financial_account (id, user_id),
    CONSTRAINT uq_batch_upload_retry UNIQUE (user_id, upload_key),
    CONSTRAINT uq_batch_id_owner UNIQUE (id, user_id),
    CONSTRAINT uq_batch_id_account_owner UNIQUE (id, account_id, user_id),
    CONSTRAINT chk_batch_status CHECK (status IN ('DRAFT','REVIEWING','CONFIRMED','CANCELLED','FAILED')),
    CONSTRAINT chk_batch_version CHECK (version >= 0),
    CONSTRAINT chk_batch_counts CHECK (confirmed_count >= 0 AND excluded_count >= 0),
    CONSTRAINT chk_batch_sha256 CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_batch_confirm_time CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL)),
    CONSTRAINT chk_batch_closed_time CHECK ((status IN ('CONFIRMED','CANCELLED','FAILED')) = (closed_at IS NOT NULL))
);
CREATE INDEX idx_import_batch_user_status ON import_batch (user_id, status, created_at DESC);
CREATE INDEX idx_import_batch_account_status ON import_batch (account_id, status);

-- Staging data intentionally allows missing parsed fields on invalid CSV rows.
-- Such rows cannot be selected for confirmation until validation is resolved.
CREATE TABLE import_candidate (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    user_id UUID NOT NULL,
    row_number INTEGER NOT NULL,
    entry_type VARCHAR(10),
    amount NUMERIC(19,2),
    currency_code CHAR(3),
    transaction_date DATE,
    occurred_at TIMESTAMPTZ,
    description VARCHAR(500),
    category_id UUID,
    bank_transaction_id VARCHAR(255),
    validation_errors JSONB NOT NULL DEFAULT '[]'::jsonb,
    selected_for_import BOOLEAN NOT NULL DEFAULT FALSE,
    duplicate_warning BOOLEAN NOT NULL DEFAULT FALSE,
    duplicate_acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    duplicate_match_signature CHAR(64),
    CONSTRAINT fk_candidate_batch_owner FOREIGN KEY (batch_id, user_id)
        REFERENCES import_batch (id, user_id),
    CONSTRAINT fk_candidate_category_owner_type FOREIGN KEY (category_id, user_id, entry_type)
        REFERENCES category (id, user_id, entry_type),
    CONSTRAINT uq_candidate_batch_row UNIQUE (batch_id, row_number),
    CONSTRAINT chk_candidate_category_type CHECK (category_id IS NULL OR entry_type IS NOT NULL),
    CONSTRAINT chk_candidate_row CHECK (row_number >= 1),
    CONSTRAINT chk_candidate_type CHECK (entry_type IS NULL OR entry_type IN ('INCOME','EXPENSE')),
    CONSTRAINT chk_candidate_amount CHECK (amount IS NULL OR amount > 0),
    CONSTRAINT chk_candidate_currency CHECK (currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_candidate_errors CHECK (jsonb_typeof(validation_errors) = 'array'),
    CONSTRAINT chk_candidate_signature CHECK (duplicate_match_signature IS NULL OR duplicate_match_signature ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_candidate_ack CHECK (NOT duplicate_acknowledged OR duplicate_warning),
    CONSTRAINT chk_candidate_selected_valid CHECK (
        NOT selected_for_import OR (
            entry_type IS NOT NULL AND amount IS NOT NULL AND currency_code IS NOT NULL
            AND transaction_date IS NOT NULL AND validation_errors = '[]'::jsonb
        )
    )
);
CREATE INDEX idx_candidate_batch_selected ON import_candidate (batch_id, selected_for_import);

-- Only CONFIRMED entries are inserted here; manual entries have no source batch/row.
-- Source candidate records may be purged while source batch + row remain durable.
CREATE TABLE ledger_entry (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    account_id UUID NOT NULL,
    source_batch_id UUID,
    source_row_number INTEGER,
    client_request_key UUID, -- optional idempotency key for manual entry POST retries
    entry_type VARCHAR(10) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    transaction_date DATE NOT NULL,
    occurred_at TIMESTAMPTZ,
    description VARCHAR(500) NOT NULL DEFAULT '',
    category_id UUID,
    bank_transaction_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ, -- application soft-delete; exclude from reporting queries
    CONSTRAINT fk_entry_account_owner FOREIGN KEY (account_id, user_id)
        REFERENCES financial_account (id, user_id),
    CONSTRAINT fk_entry_category_owner_type FOREIGN KEY (category_id, user_id, entry_type)
        REFERENCES category (id, user_id, entry_type),
    CONSTRAINT fk_entry_source_batch_account_owner FOREIGN KEY (source_batch_id, account_id, user_id)
        REFERENCES import_batch (id, account_id, user_id),
    CONSTRAINT chk_entry_type CHECK (entry_type IN ('INCOME','EXPENSE')),
    CONSTRAINT chk_entry_amount CHECK (amount > 0),
    CONSTRAINT chk_entry_currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_entry_source_fields CHECK (
        (source_batch_id IS NULL AND source_row_number IS NULL)
        OR (source_batch_id IS NOT NULL AND source_row_number IS NOT NULL AND source_row_number >= 1)
    )
);

-- Exact retry barriers; do NOT confuse these with heuristics for probable duplicates.
CREATE UNIQUE INDEX uq_ledger_imported_row ON ledger_entry (source_batch_id, source_row_number)
    WHERE source_batch_id IS NOT NULL;
CREATE UNIQUE INDEX uq_ledger_trusted_bank_txn ON ledger_entry (account_id, bank_transaction_id)
    WHERE bank_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX uq_ledger_manual_request ON ledger_entry (user_id, client_request_key)
    WHERE client_request_key IS NOT NULL;

-- Query/report and weak-warning lookup indexes (start simple; measure before tuning).
CREATE INDEX idx_ledger_owner_date ON ledger_entry (user_id, transaction_date DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_ledger_account_date ON ledger_entry (account_id, transaction_date DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_ledger_potential_duplicate_time ON ledger_entry (account_id, occurred_at)
    WHERE occurred_at IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_ledger_category_date ON ledger_entry (user_id, category_id, transaction_date DESC)
    WHERE deleted_at IS NULL;
