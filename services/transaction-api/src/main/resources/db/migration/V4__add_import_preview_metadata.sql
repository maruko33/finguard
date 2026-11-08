-- Preserve historical unknown values as NULL; never infer bank format or source direction.
ALTER TABLE import_batch
    ADD COLUMN bank_type VARCHAR(30),
    ADD CONSTRAINT chk_batch_bank_type CHECK (bank_type IS NULL OR bank_type IN ('RBC_VISA'));

ALTER TABLE import_candidate
    ADD COLUMN source_direction VARCHAR(10),
    ADD CONSTRAINT chk_candidate_source_direction
        CHECK (source_direction IS NULL OR source_direction IN ('DEBIT', 'CREDIT'));
