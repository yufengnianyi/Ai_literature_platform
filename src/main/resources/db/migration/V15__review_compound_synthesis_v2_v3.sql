-- v2+v3: Compound evidence synthesis tables

CREATE TABLE IF NOT EXISTS review_chunk_anchor (
    id BIGSERIAL PRIMARY KEY,
    task_id UUID NOT NULL,
    document_id UUID,
    chunk_id VARCHAR(128) NOT NULL,
    anchor_type VARCHAR(40) NOT NULL,
    reason VARCHAR(255),
    matched_tokens TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS ix_review_chunk_anchor_task ON review_chunk_anchor(task_id);

CREATE TABLE IF NOT EXISTS review_document_alias_map (
    document_id UUID NOT NULL,
    local_alias VARCHAR(128) NOT NULL,
    canonical_name VARCHAR(255),
    resolution_status VARCHAR(20),
    PRIMARY KEY (document_id, local_alias)
);

CREATE TABLE IF NOT EXISTS review_synthesized_compound (
    id BIGSERIAL PRIMARY KEY,
    task_id UUID NOT NULL,
    document_id UUID,
    compound_key VARCHAR(255) NOT NULL,
    compound_name VARCHAR(255),
    role VARCHAR(32),
    payload_json JSONB NOT NULL,
    coverage_warnings JSONB,
    evidence_fingerprint VARCHAR(64),
    confidence DOUBLE PRECISION,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (task_id, document_id, compound_key)
);
CREATE INDEX IF NOT EXISTS ix_synthesized_compound_task ON review_synthesized_compound(task_id);
