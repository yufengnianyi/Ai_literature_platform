CREATE TABLE IF NOT EXISTS rag_eval_experiment (
    experiment_id UUID PRIMARY KEY,
    user_id VARCHAR(256) NOT NULL,
    question TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    metrics_json JSONB,
    report_root TEXT,
    error_code VARCHAR(64),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_rag_eval_experiment_user
    ON rag_eval_experiment (user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS rag_eval_document_judgment (
    id BIGSERIAL PRIMARY KEY,
    experiment_id UUID NOT NULL REFERENCES rag_eval_experiment (experiment_id) ON DELETE CASCADE,
    document_id UUID NOT NULL,
    document_title TEXT,
    llm_label VARCHAR(32) NOT NULL,
    override_label VARCHAR(32),
    effective_label VARCHAR(32) NOT NULL,
    key_entities_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    key_chunk_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    llm_reason TEXT,
    report_path TEXT,
    confidence DOUBLE PRECISION,
    override_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (experiment_id, document_id)
);

CREATE INDEX IF NOT EXISTS ix_rag_eval_document_judgment_experiment
    ON rag_eval_document_judgment (experiment_id);

CREATE INDEX IF NOT EXISTS ix_rag_eval_document_judgment_effective_label
    ON rag_eval_document_judgment (experiment_id, effective_label);

CREATE TABLE IF NOT EXISTS rag_eval_retrieval_hit (
    id BIGSERIAL PRIMARY KEY,
    experiment_id UUID NOT NULL REFERENCES rag_eval_experiment (experiment_id) ON DELETE CASCADE,
    route VARCHAR(32) NOT NULL,
    query TEXT,
    rank INTEGER NOT NULL,
    document_id UUID,
    chunk_id VARCHAR(256),
    score DOUBLE PRECISION,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_rag_eval_retrieval_hit_experiment_route
    ON rag_eval_retrieval_hit (experiment_id, route, rank);

CREATE INDEX IF NOT EXISTS ix_rag_eval_retrieval_hit_document
    ON rag_eval_retrieval_hit (experiment_id, document_id);
