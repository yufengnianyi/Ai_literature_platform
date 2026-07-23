-- Agentized multi-profile evidence pipeline: telemetry, verifier notes, coverage audit.

ALTER TABLE generic_evidence_record
    ADD COLUMN IF NOT EXISTS verification_note TEXT;

CREATE TABLE IF NOT EXISTS evidence_agent_step (
    step_id BIGSERIAL PRIMARY KEY,
    batch_id UUID NOT NULL,
    document_id UUID NOT NULL,
    question_id VARCHAR(32),
    agent_name VARCHAR(64) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 1,
    llm_calls INTEGER NOT NULL DEFAULT 0,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    retry_count INTEGER NOT NULL DEFAULT 0,
    elapsed_ms BIGINT,
    success BOOLEAN NOT NULL DEFAULT TRUE,
    detail_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_evidence_agent_step_query
    ON evidence_agent_step(batch_id, document_id, question_id, agent_name);

CREATE TABLE IF NOT EXISTS evidence_coverage_audit (
    audit_id BIGSERIAL PRIMARY KEY,
    batch_id UUID NOT NULL,
    document_id UUID NOT NULL,
    question_id VARCHAR(32) NOT NULL,
    candidate_count INTEGER NOT NULL DEFAULT 0,
    extracted_before INTEGER NOT NULL DEFAULT 0,
    recovered_count INTEGER NOT NULL DEFAULT 0,
    extracted_after INTEGER NOT NULL DEFAULT 0,
    candidates_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    recovered_fingerprints_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (batch_id, document_id, question_id)
);

CREATE INDEX IF NOT EXISTS ix_evidence_coverage_audit_batch
    ON evidence_coverage_audit(batch_id, question_id);
