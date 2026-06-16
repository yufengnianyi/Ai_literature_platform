DROP TABLE IF EXISTS review_paper_evidence_table CASCADE;
DROP TABLE IF EXISTS review_synthesized_compound CASCADE;
DROP TABLE IF EXISTS review_chunk_anchor CASCADE;
DROP TABLE IF EXISTS review_document_alias_map CASCADE;
DROP TABLE IF EXISTS review_document_knowledge_update CASCADE;
DROP TABLE IF EXISTS review_document_compound_alias CASCADE;
DROP TABLE IF EXISTS review_compound_identity CASCADE;
DROP TABLE IF EXISTS review_document_knowledge CASCADE;
DROP TABLE IF EXISTS review_document_candidate CASCADE;
DROP TABLE IF EXISTS review_evidence CASCADE;
DROP TABLE IF EXISTS review_candidate CASCADE;
DROP TABLE IF EXISTS review_task CASCADE;

CREATE TABLE evidence_extraction_batch (
    batch_id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED')),
    force BOOLEAN NOT NULL DEFAULT FALSE,
    total_documents INTEGER NOT NULL DEFAULT 0,
    processed_documents INTEGER NOT NULL DEFAULT 0,
    skipped_documents INTEGER NOT NULL DEFAULT 0,
    completed_documents INTEGER NOT NULL DEFAULT 0,
    no_evidence_documents INTEGER NOT NULL DEFAULT 0,
    failed_documents INTEGER NOT NULL DEFAULT 0,
    elapsed_ms BIGINT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_evidence_batch_created
    ON evidence_extraction_batch(created_at DESC);
CREATE UNIQUE INDEX ux_evidence_batch_active
    ON evidence_extraction_batch ((status IN ('QUEUED', 'RUNNING')))
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE evidence_extraction_run (
    run_id UUID PRIMARY KEY,
    batch_id UUID REFERENCES evidence_extraction_batch(batch_id) ON DELETE SET NULL,
    document_id UUID NOT NULL REFERENCES rag_document(document_id) ON DELETE CASCADE,
    profile_id VARCHAR(64) NOT NULL,
    source_hash VARCHAR(128),
    prompt_hash VARCHAR(64) NOT NULL,
    model_name VARCHAR(128),
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'NO_EVIDENCE', 'FAILED')),
    skipped BOOLEAN NOT NULL DEFAULT FALSE,
    row_count INTEGER NOT NULL DEFAULT 0,
    output_path TEXT,
    error_code VARCHAR(64),
    error_message TEXT,
    elapsed_ms BIGINT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_evidence_run_document
    ON evidence_extraction_run(document_id, created_at DESC);
CREATE INDEX ix_evidence_run_batch
    ON evidence_extraction_run(batch_id, created_at);
CREATE INDEX ix_evidence_run_status
    ON evidence_extraction_run(status, created_at);
CREATE UNIQUE INDEX ux_evidence_run_active_document
    ON evidence_extraction_run(document_id)
    WHERE status IN ('QUEUED', 'RUNNING');
