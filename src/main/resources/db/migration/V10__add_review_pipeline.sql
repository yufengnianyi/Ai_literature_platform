-- ====== 为 rag_document 添加全文检索支持（Phase 1 文献级粗召回） ======
ALTER TABLE rag_document
    ADD COLUMN IF NOT EXISTS fts_vector tsvector;

UPDATE rag_document
SET fts_vector = to_tsvector('english',
    coalesce(title, '') || ' ' || coalesce(abstract_text, ''));

CREATE INDEX IF NOT EXISTS idx_rag_document_fts
    ON rag_document USING GIN (fts_vector);

CREATE OR REPLACE FUNCTION rag_document_fts_trigger() RETURNS trigger AS $$
BEGIN
    NEW.fts_vector := to_tsvector('english',
        coalesce(NEW.title, '') || ' ' || coalesce(NEW.abstract_text, ''));
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_rag_document_fts ON rag_document;
CREATE TRIGGER trg_rag_document_fts
    BEFORE INSERT OR UPDATE OF title, abstract_text ON rag_document
    FOR EACH ROW EXECUTE FUNCTION rag_document_fts_trigger();

-- ====== 综述任务表 ======
CREATE TABLE IF NOT EXISTS review_task (
    task_id         UUID PRIMARY KEY,
    user_id         VARCHAR(256) NOT NULL,
    question        TEXT NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'QUEUED',
    stage           VARCHAR(64),
    query_analysis  JSONB,
    report_json     JSONB,
    report_markdown TEXT,
    candidate_count INTEGER,
    evidence_count  INTEGER,
    retrieval_ms    BIGINT,
    rerank_ms       BIGINT,
    extraction_ms   BIGINT,
    fusion_ms       BIGINT,
    report_ms       BIGINT,
    total_ms        BIGINT,
    error_code      VARCHAR(64),
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_review_task_user
    ON review_task (user_id, created_at DESC);

-- ====== 候选文献片段 ======
CREATE TABLE IF NOT EXISTS review_candidate (
    id               BIGSERIAL PRIMARY KEY,
    task_id          UUID NOT NULL REFERENCES review_task (task_id) ON DELETE CASCADE,
    chunk_id         VARCHAR(256) NOT NULL,
    document_id      UUID,
    document_title   TEXT,
    retrieval_score  DOUBLE PRECISION,
    retrieval_source VARCHAR(32),
    rerank_score     DOUBLE PRECISION,
    relevance        VARCHAR(32),
    screening_reason TEXT,
    included         BOOLEAN DEFAULT FALSE,
    chunk_text       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_review_candidate_task
    ON review_candidate (task_id);

-- ====== 结构化证据 ======
CREATE TABLE IF NOT EXISTS review_evidence (
    id               BIGSERIAL PRIMARY KEY,
    task_id          UUID NOT NULL REFERENCES review_task (task_id) ON DELETE CASCADE,
    candidate_id     BIGINT REFERENCES review_candidate (id),
    chunk_id         VARCHAR(256),
    document_id      UUID,
    claim            TEXT,
    finding          TEXT,
    methodology      TEXT,
    entities         JSONB,
    evidence_type    VARCHAR(32),
    confidence       DOUBLE PRECISION,
    original_text    TEXT,
    normalized_group VARCHAR(256),
    sub_question     TEXT,
    consistency      VARCHAR(32),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_review_evidence_task
    ON review_evidence (task_id);
CREATE INDEX IF NOT EXISTS idx_review_evidence_group
    ON review_evidence (task_id, normalized_group);
