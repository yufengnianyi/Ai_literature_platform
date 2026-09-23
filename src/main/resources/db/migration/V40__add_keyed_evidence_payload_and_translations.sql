-- Store one independently reviewable evidence fact per generic_evidence_record.
-- Keep cells_json during the compatibility window; payload_json gives every value
-- a stable field key so profile changes do not depend on array position.
ALTER TABLE generic_evidence_record
    ADD COLUMN IF NOT EXISTS payload_json JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE generic_evidence_record
    ADD COLUMN IF NOT EXISTS supersedes_record_id UUID
        REFERENCES generic_evidence_record(record_id) ON DELETE SET NULL;

ALTER TABLE generic_evidence_record
    ADD CONSTRAINT ck_generic_evidence_record_payload_object
    CHECK (jsonb_typeof(payload_json) = 'object');

CREATE INDEX IF NOT EXISTS ix_generic_evidence_payload_gin
    ON generic_evidence_record USING GIN (payload_json);

CREATE INDEX IF NOT EXISTS ix_generic_evidence_supersedes
    ON generic_evidence_record(supersedes_record_id)
    WHERE supersedes_record_id IS NOT NULL;

-- Translations are derived, independently reproducible artifacts. They are kept
-- outside the scientific evidence row so a translation rerun never overwrites
-- the extracted fact or its source-language anchor.
CREATE TABLE IF NOT EXISTS evidence_record_translation (
    translation_id UUID PRIMARY KEY,
    record_id UUID NOT NULL
        REFERENCES generic_evidence_record(record_id) ON DELETE CASCADE,
    language_code VARCHAR(16) NOT NULL,
    profile_version VARCHAR(64) NOT NULL,
    translator_model VARCHAR(255),
    prompt_hash VARCHAR(64) NOT NULL,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED'
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_evidence_record_translation_payload_object
        CHECK (jsonb_typeof(payload_json) = 'object'),
    UNIQUE (record_id, language_code, prompt_hash)
);

CREATE INDEX IF NOT EXISTS ix_evidence_record_translation_lookup
    ON evidence_record_translation(record_id, language_code, updated_at DESC);

CREATE INDEX IF NOT EXISTS ix_evidence_record_translation_payload_gin
    ON evidence_record_translation USING GIN (payload_json);
