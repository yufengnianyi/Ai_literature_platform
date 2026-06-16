ALTER TABLE compound_evidence
    ADD COLUMN IF NOT EXISTS name_kind VARCHAR(32)
        CHECK (name_kind IN ('PURE_COMPOUND', 'NATURAL_EXTRACT', 'LOCAL_LABEL')),
    ADD COLUMN IF NOT EXISTS dedup_key VARCHAR(256);

CREATE INDEX IF NOT EXISTS ix_compound_evidence_dedup_key
    ON compound_evidence(dedup_key)
    WHERE is_current = TRUE AND dedup_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_compound_evidence_name_kind
    ON compound_evidence(name_kind)
    WHERE is_current = TRUE;
