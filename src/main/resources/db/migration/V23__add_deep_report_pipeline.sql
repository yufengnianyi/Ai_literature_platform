ALTER TABLE report_run
    DROP CONSTRAINT IF EXISTS report_run_status_check;

ALTER TABLE report_run
    ADD CONSTRAINT report_run_status_check
        CHECK (status IN (
            'QUEUED',
            'REWRITING',
            'MATCHING',
            'GENERATING',
            'PLANNING',
            'ANALYZING_EVIDENCE',
            'RETRIEVING_LITERATURE',
            'ANALYZING_LITERATURE',
            'SYNTHESIZING',
            'VALIDATING',
            'COMPLETED',
            'PARTIAL_COMPLETED',
            'FAILED'
        )),
    ADD COLUMN IF NOT EXISTS phase_message TEXT,
    ADD COLUMN IF NOT EXISTS progress_percent INTEGER NOT NULL DEFAULT 0
        CHECK (progress_percent BETWEEN 0 AND 100),
    ADD COLUMN IF NOT EXISTS selected_document_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS analyzed_document_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS warnings_json JSONB NOT NULL DEFAULT '[]'::jsonb;

DROP INDEX IF EXISTS ux_report_run_active_conversation;
CREATE UNIQUE INDEX ux_report_run_active_conversation
    ON report_run(user_id, conversation_id)
    WHERE status NOT IN ('COMPLETED', 'PARTIAL_COMPLETED', 'FAILED');

CREATE TABLE report_literature_analysis_cache (
    cache_id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES rag_document(document_id) ON DELETE CASCADE,
    document_hash VARCHAR(128) NOT NULL,
    prompt_hash VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    analysis_json JSONB NOT NULL,
    chunk_count INTEGER NOT NULL,
    analyzed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(document_id, document_hash, prompt_hash, model_name)
);

CREATE INDEX ix_report_literature_cache_document
    ON report_literature_analysis_cache(document_id, analyzed_at DESC);

CREATE TABLE report_literature_link (
    report_id UUID NOT NULL REFERENCES report_run(report_id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES rag_document(document_id),
    source_type VARCHAR(16) NOT NULL CHECK (source_type IN ('DIRECT', 'SUPPLEMENTAL')),
    rank INTEGER NOT NULL,
    relevance_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    selection_reason TEXT,
    analysis_status VARCHAR(16) NOT NULL
        CHECK (analysis_status IN ('PENDING', 'CACHED', 'COMPLETED', 'FAILED')),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (report_id, document_id)
);

CREATE INDEX ix_report_literature_link_rank
    ON report_literature_link(report_id, rank);

CREATE TABLE report_claim (
    claim_id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES report_run(report_id) ON DELETE CASCADE,
    section_key VARCHAR(64) NOT NULL,
    claim_text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_report_claim_report
    ON report_claim(report_id, section_key);

CREATE TABLE report_claim_evidence (
    claim_id UUID NOT NULL REFERENCES report_claim(claim_id) ON DELETE CASCADE,
    evidence_id UUID NOT NULL REFERENCES compound_evidence(evidence_id),
    PRIMARY KEY (claim_id, evidence_id)
);

CREATE TABLE report_claim_chunk (
    claim_id UUID NOT NULL REFERENCES report_claim(claim_id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES rag_document(document_id),
    chunk_id VARCHAR(256) NOT NULL,
    PRIMARY KEY (claim_id, document_id, chunk_id)
);
