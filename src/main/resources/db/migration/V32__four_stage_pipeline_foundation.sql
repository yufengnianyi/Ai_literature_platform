ALTER TABLE evidence_multi_profile_batch
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(32) NOT NULL DEFAULT 'EXPERIMENT',
    ADD COLUMN IF NOT EXISTS source_pretreatment_run_id UUID REFERENCES pretreatment_run(run_id);

ALTER TABLE evidence_multi_profile_batch
    ALTER COLUMN source_experiment_id DROP NOT NULL;

DO $$
DECLARE
    target record;
BEGIN
    FOR target IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE rel.relname = 'evidence_question_extraction_run'
          AND nsp.nspname = current_schema()
          AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) LIKE '%source_type%'
    LOOP
        EXECUTE format(
            'ALTER TABLE evidence_question_extraction_run DROP CONSTRAINT %I',
            target.conname);
    END LOOP;
END $$;

ALTER TABLE evidence_question_extraction_run
    ADD CONSTRAINT evidence_question_extraction_run_source_type_check
    CHECK (source_type IN ('CLASSIFICATION_RUN', 'EXPERIMENT', 'DOCUMENT_IDS', 'COHORT'));

ALTER TABLE evidence_question_extraction_run
    ADD COLUMN IF NOT EXISTS cohort_id UUID;

ALTER TABLE pretreatment_run
    ADD COLUMN IF NOT EXISTS accepted_cohort_id UUID,
    ADD COLUMN IF NOT EXISTS rejected_cohort_id UUID;

CREATE TABLE IF NOT EXISTS document_cohort (
    cohort_id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_ref_id UUID,
    input_hash VARCHAR(64) NOT NULL,
    frozen BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_document_cohort_name
    ON document_cohort(name);

CREATE TABLE IF NOT EXISTS cohort_member (
    cohort_id UUID NOT NULL REFERENCES document_cohort(cohort_id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES rag_document(document_id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    added_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (cohort_id, document_id)
);

CREATE INDEX IF NOT EXISTS ix_cohort_member_document
    ON cohort_member(document_id);

CREATE TABLE IF NOT EXISTS stage_run (
    run_id UUID PRIMARY KEY,
    stage VARCHAR(64) NOT NULL,
    cohort_id UUID REFERENCES document_cohort(cohort_id) ON DELETE SET NULL,
    input_hash VARCHAR(64) NOT NULL,
    config_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    config_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_ref_id UUID,
    total_documents INTEGER NOT NULL DEFAULT 0,
    processed_documents INTEGER NOT NULL DEFAULT 0,
    completed_documents INTEGER NOT NULL DEFAULT 0,
    failed_documents INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_stage_run_reuse
    ON stage_run(stage, cohort_id, input_hash, config_hash, status, created_at DESC);

CREATE TABLE IF NOT EXISTS stage_run_document (
    run_id UUID NOT NULL REFERENCES stage_run(run_id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES rag_document(document_id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL,
    error_message TEXT,
    elapsed_ms BIGINT,
    output_path TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, document_id)
);

CREATE INDEX IF NOT EXISTS ix_stage_run_document_status
    ON stage_run_document(run_id, status);
