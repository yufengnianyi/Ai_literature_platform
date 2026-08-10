-- Stage 3 (Q1-Q10 classification) and stage 4 (evidence extraction) become independently
-- runnable. Classification keeps living in evidence_multi_profile_batch; extraction moves to
-- its own per-question run so a single classification snapshot can back many extraction
-- experiments (different table/agent settings) without re-paying for classification.

-- 1. Split the single combined prompt_hash into stage-scoped config hashes.
--    prompt_hash keeps holding the legacy combined value so existing rows stay matchable.
ALTER TABLE evidence_multi_profile_batch
    ADD COLUMN IF NOT EXISTS classification_config_hash VARCHAR(64);

ALTER TABLE evidence_multi_profile_batch
    ADD COLUMN IF NOT EXISTS extraction_config_hash VARCHAR(64);

ALTER TABLE evidence_multi_profile_batch
    ADD COLUMN IF NOT EXISTS run_extraction BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX IF NOT EXISTS ix_evidence_multi_profile_batch_classification
    ON evidence_multi_profile_batch(
        source_experiment_id, source_hash, profile_version,
        classification_config_hash, created_at DESC
    );

-- 2. Stage 4 as a first-class run, scoped to exactly one question.
CREATE TABLE IF NOT EXISTS evidence_question_extraction_run (
    run_id UUID PRIMARY KEY,
    question_id VARCHAR(32) NOT NULL,
    label VARCHAR(128),
    source_type VARCHAR(32) NOT NULL
        CHECK (source_type IN ('CLASSIFICATION_RUN', 'EXPERIMENT', 'DOCUMENT_IDS')),
    classification_batch_id UUID
        REFERENCES evidence_multi_profile_batch(batch_id) ON DELETE SET NULL,
    source_experiment_id UUID REFERENCES rag_eval_experiment(experiment_id),
    include_statuses_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    profile_version VARCHAR(64) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    config_hash VARCHAR(64) NOT NULL,
    config_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    model_name VARCHAR(255),
    force BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED')),
    total_documents INTEGER NOT NULL DEFAULT 0,
    processed_documents INTEGER NOT NULL DEFAULT 0,
    completed_documents INTEGER NOT NULL DEFAULT 0,
    no_evidence_documents INTEGER NOT NULL DEFAULT 0,
    failed_documents INTEGER NOT NULL DEFAULT 0,
    evidence_rows INTEGER NOT NULL DEFAULT 0,
    output_path TEXT,
    error_message TEXT,
    elapsed_ms BIGINT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_evidence_question_extraction_run_reuse
    ON evidence_question_extraction_run(
        question_id, input_hash, config_hash, created_at DESC
    );

CREATE INDEX IF NOT EXISTS ix_evidence_question_extraction_run_question
    ON evidence_question_extraction_run(question_id, created_at DESC);

CREATE TABLE IF NOT EXISTS evidence_question_extraction_document (
    run_id UUID NOT NULL
        REFERENCES evidence_question_extraction_run(run_id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES rag_document(document_id) ON DELETE CASCADE,
    document_title TEXT,
    classification_status VARCHAR(32),
    status VARCHAR(32) NOT NULL
        CHECK (status IN (
            'PENDING', 'RUNNING', 'COMPLETED', 'NO_EVIDENCE', 'FAILED', 'NO_CHUNKS'
        )),
    chunk_count INTEGER,
    row_count INTEGER NOT NULL DEFAULT 0,
    elapsed_ms BIGINT,
    output_path TEXT,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, document_id)
);

CREATE INDEX IF NOT EXISTS ix_evidence_question_extraction_document_status
    ON evidence_question_extraction_document(run_id, status);

-- 3. Evidence rows hang off an extraction run instead of a classification batch, so repeated
--    single-question experiments no longer overwrite each other.
ALTER TABLE generic_evidence_record
    ADD COLUMN IF NOT EXISTS extraction_run_id UUID;

-- Drop the batch-scoped foreign key and unique constraint. Their generated names are not
-- stable across environments, so resolve them from the catalog.
DO $$
DECLARE
    target record;
BEGIN
    FOR target IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE rel.relname = 'generic_evidence_record'
          AND nsp.nspname = current_schema()
          AND con.contype IN ('f', 'u')
          AND pg_get_constraintdef(con.oid) LIKE '%batch_id%'
    LOOP
        EXECUTE format(
            'ALTER TABLE generic_evidence_record DROP CONSTRAINT %I', target.conname);
    END LOOP;
END $$;

ALTER TABLE generic_evidence_record
    ALTER COLUMN batch_id DROP NOT NULL;

-- Rows produced by a forced extraction have no classification verdict to inherit.
DO $$
DECLARE
    target record;
BEGIN
    FOR target IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE rel.relname = 'generic_evidence_record'
          AND nsp.nspname = current_schema()
          AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) LIKE '%classification_status%'
    LOOP
        EXECUTE format(
            'ALTER TABLE generic_evidence_record DROP CONSTRAINT %I', target.conname);
    END LOOP;
END $$;

ALTER TABLE generic_evidence_record
    ADD CONSTRAINT ck_generic_evidence_record_classification
    CHECK (classification_status IN ('SUPPORTED', 'UNCERTAIN', 'NOT_CLASSIFIED'));

ALTER TABLE generic_evidence_record
    ADD CONSTRAINT fk_generic_evidence_record_extraction_run
    FOREIGN KEY (extraction_run_id)
    REFERENCES evidence_question_extraction_run(run_id) ON DELETE CASCADE;

-- Legacy rows keep batch_id as their dedup scope; new rows use the extraction run.
CREATE UNIQUE INDEX IF NOT EXISTS ux_generic_evidence_record_scope
    ON generic_evidence_record(
        COALESCE(extraction_run_id, batch_id), document_id, question_id, row_fingerprint);

CREATE INDEX IF NOT EXISTS ix_generic_evidence_record_extraction_run
    ON generic_evidence_record(extraction_run_id, question_id, is_current);
