ALTER TABLE review_task
    ADD COLUMN IF NOT EXISTS document_count INTEGER,
    ADD COLUMN IF NOT EXISTS document_promotion_ms BIGINT;

CREATE TABLE IF NOT EXISTS review_document_candidate (
    id                    BIGSERIAL PRIMARY KEY,
    task_id               UUID NOT NULL REFERENCES review_task (task_id) ON DELETE CASCADE,
    document_id           UUID NOT NULL,
    document_title        TEXT,
    seed_chunk_count      INTEGER NOT NULL,
    seed_chunk_ids        JSONB,
    seed_max_score        DOUBLE PRECISION,
    seed_avg_top3_score   DOUBLE PRECISION,
    section_prior_score   DOUBLE PRECISION,
    entity_coverage_score DOUBLE PRECISION,
    contribution_score    DOUBLE PRECISION,
    final_score           DOUBLE PRECISION,
    relevance             VARCHAR(32),
    promotion_reason      TEXT,
    synopsis_summary      TEXT,
    innovation_points     JSONB,
    key_findings          JSONB,
    expanded              BOOLEAN DEFAULT FALSE,
    selected              BOOLEAN DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_review_doc_candidate_task
    ON review_document_candidate (task_id);

CREATE INDEX IF NOT EXISTS idx_review_doc_candidate_task_score
    ON review_document_candidate (task_id, final_score DESC);

CREATE UNIQUE INDEX IF NOT EXISTS idx_review_doc_candidate_task_doc
    ON review_document_candidate (task_id, document_id);

ALTER TABLE review_candidate
    ADD COLUMN IF NOT EXISTS section_path TEXT,
    ADD COLUMN IF NOT EXISTS retrieval_phase VARCHAR(32) NOT NULL DEFAULT 'SEED',
    ADD COLUMN IF NOT EXISTS document_score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS document_relevance VARCHAR(32),
    ADD COLUMN IF NOT EXISTS document_reason TEXT;
