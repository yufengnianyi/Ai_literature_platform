CREATE TABLE IF NOT EXISTS rag_eval_antimicrobial_result (
    experiment_id UUID NOT NULL REFERENCES rag_eval_experiment (experiment_id) ON DELETE CASCADE,
    document_id UUID NOT NULL,
    document_title TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    relevant BOOLEAN,
    chunk_count INTEGER,
    judgment_reason TEXT,
    output_path TEXT,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (experiment_id, document_id)
);

CREATE INDEX IF NOT EXISTS ix_rag_eval_antimicrobial_result_status
    ON rag_eval_antimicrobial_result (experiment_id, status);
