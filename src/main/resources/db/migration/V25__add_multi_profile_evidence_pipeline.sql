CREATE TABLE IF NOT EXISTS evidence_multi_profile_batch (
    batch_id UUID PRIMARY KEY,
    source_experiment_id UUID NOT NULL REFERENCES rag_eval_experiment(experiment_id),
    source_hash VARCHAR(64) NOT NULL,
    profile_version VARCHAR(64) NOT NULL,
    prompt_hash VARCHAR(64) NOT NULL,
    model_name VARCHAR(255),
    force BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED')),
    total_documents INTEGER NOT NULL,
    processed_documents INTEGER NOT NULL DEFAULT 0,
    supported_matches INTEGER NOT NULL DEFAULT 0,
    uncertain_matches INTEGER NOT NULL DEFAULT 0,
    extracted_profiles INTEGER NOT NULL DEFAULT 0,
    no_evidence_profiles INTEGER NOT NULL DEFAULT 0,
    failed_profiles INTEGER NOT NULL DEFAULT 0,
    output_path TEXT,
    error_message TEXT,
    elapsed_ms BIGINT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_evidence_multi_profile_batch_source
    ON evidence_multi_profile_batch(
        source_experiment_id, source_hash, profile_version, prompt_hash, created_at DESC
    );

CREATE TABLE IF NOT EXISTS evidence_multi_profile_document (
    batch_id UUID NOT NULL REFERENCES evidence_multi_profile_batch(batch_id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES rag_document(document_id) ON DELETE CASCADE,
    document_title TEXT,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED', 'NO_CHUNKS')),
    chunk_count INTEGER,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (batch_id, document_id)
);

CREATE INDEX IF NOT EXISTS ix_evidence_multi_profile_document_status
    ON evidence_multi_profile_document(batch_id, status);

CREATE TABLE IF NOT EXISTS evidence_document_question_match (
    batch_id UUID NOT NULL,
    document_id UUID NOT NULL,
    question_id VARCHAR(32) NOT NULL,
    classification_status VARCHAR(32) NOT NULL
        CHECK (classification_status IN ('SUPPORTED', 'UNCERTAIN', 'NOT_SUPPORTED', 'FAILED')),
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0,
    reason TEXT,
    evidence_chunk_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    extraction_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED'
        CHECK (extraction_status IN (
            'NOT_REQUESTED', 'QUEUED', 'RUNNING', 'COMPLETED', 'NO_EVIDENCE', 'FAILED'
        )),
    evidence_count INTEGER NOT NULL DEFAULT 0,
    output_path TEXT,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (batch_id, document_id, question_id),
    FOREIGN KEY (batch_id, document_id)
        REFERENCES evidence_multi_profile_document(batch_id, document_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_evidence_document_question_match_filter
    ON evidence_document_question_match(
        batch_id, question_id, classification_status, extraction_status
    );

CREATE TABLE IF NOT EXISTS generic_evidence_record (
    record_id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    document_id UUID NOT NULL,
    question_id VARCHAR(32) NOT NULL,
    profile_version VARCHAR(64) NOT NULL,
    row_index INTEGER NOT NULL,
    cells_json JSONB NOT NULL,
    row_fingerprint VARCHAR(64) NOT NULL,
    classification_status VARCHAR(32) NOT NULL
        CHECK (classification_status IN ('SUPPORTED', 'UNCERTAIN')),
    validation_status VARCHAR(32) NOT NULL DEFAULT 'VALID'
        CHECK (validation_status IN ('VALID', 'INVALID')),
    review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    review_note TEXT,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (batch_id, document_id, question_id)
        REFERENCES evidence_document_question_match(batch_id, document_id, question_id)
        ON DELETE CASCADE,
    UNIQUE (batch_id, document_id, question_id, row_fingerprint)
);

CREATE INDEX IF NOT EXISTS ix_generic_evidence_record_query
    ON generic_evidence_record(batch_id, question_id, review_status, is_current);

CREATE TABLE IF NOT EXISTS generic_evidence_anchor (
    anchor_id BIGSERIAL PRIMARY KEY,
    record_id UUID NOT NULL REFERENCES generic_evidence_record(record_id) ON DELETE CASCADE,
    chunk_id VARCHAR(256) NOT NULL,
    section_path TEXT,
    paragraph_index INTEGER,
    sentence_start INTEGER,
    sentence_end INTEGER,
    exact_quote TEXT NOT NULL,
    quote_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(record_id, chunk_id, quote_hash)
);

CREATE INDEX IF NOT EXISTS ix_generic_evidence_anchor_record
    ON generic_evidence_anchor(record_id);
