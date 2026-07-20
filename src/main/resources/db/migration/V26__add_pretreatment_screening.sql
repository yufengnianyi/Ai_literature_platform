CREATE TABLE IF NOT EXISTS pretreatment_run (
    run_id UUID PRIMARY KEY,
    mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    config_json JSONB,
    output_dir TEXT,
    total_artifacts INTEGER DEFAULT 0,
    processed_documents INTEGER DEFAULT 0,
    accepted_documents INTEGER DEFAULT 0,
    rejected_documents INTEGER DEFAULT 0,
    uncertain_documents INTEGER DEFAULT 0,
    skipped_documents INTEGER DEFAULT 0,
    vectors_removed INTEGER DEFAULT 0,
    dry_run BOOLEAN DEFAULT TRUE,
    error_code VARCHAR(64),
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pretreatment_run_created_at
    ON pretreatment_run (created_at DESC);

CREATE TABLE IF NOT EXISTS pretreatment_document_result (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES pretreatment_run(run_id) ON DELETE CASCADE,
    document_id UUID,
    storage_dir TEXT,
    title TEXT,
    journal TEXT,
    doi TEXT,
    title_decision VARCHAR(64),
    journal_quality VARCHAR(32),
    llm_label VARCHAR(64),
    confidence DOUBLE PRECISION DEFAULT 0,
    final_decision VARCHAR(32) NOT NULL,
    taxa_json JSONB,
    research_focus TEXT,
    evidence_chunk_ids_json JSONB,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pretreatment_document_result_run
    ON pretreatment_document_result (run_id);

CREATE INDEX IF NOT EXISTS idx_pretreatment_document_result_document
    ON pretreatment_document_result (document_id);

CREATE INDEX IF NOT EXISTS idx_pretreatment_document_result_decision
    ON pretreatment_document_result (run_id, final_decision);
