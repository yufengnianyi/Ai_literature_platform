CREATE TABLE IF NOT EXISTS review_paper_evidence_table (
    id BIGSERIAL PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES review_task (task_id) ON DELETE CASCADE,
    document_id UUID NOT NULL,
    document_title TEXT,
    review_question TEXT,
    paper_summary TEXT,
    headers_json JSONB NOT NULL,
    rows_json JSONB NOT NULL,
    source_chunk_ids JSONB NOT NULL,
    iterations INTEGER NOT NULL DEFAULT 1,
    confidence DOUBLE PRECISION,
    warnings_json JSONB,
    payload_json JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (task_id, document_id)
);

CREATE INDEX IF NOT EXISTS ix_review_paper_evidence_table_task
    ON review_paper_evidence_table (task_id);
