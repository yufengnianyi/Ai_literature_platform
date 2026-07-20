CREATE TABLE IF NOT EXISTS entity_library (
    entity_id UUID PRIMARY KEY,
    entity_type VARCHAR(64) NOT NULL,
    normalized_key VARCHAR(512) NOT NULL,
    canonical_name TEXT NOT NULL,
    aliases_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    definition TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    source_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (entity_type, normalized_key)
);

CREATE INDEX IF NOT EXISTS ix_entity_library_type_name
    ON entity_library(entity_type, canonical_name);

CREATE INDEX IF NOT EXISTS ix_entity_library_status
    ON entity_library(status, updated_at DESC);

CREATE TABLE IF NOT EXISTS entity_library_evidence (
    evidence_id BIGSERIAL PRIMARY KEY,
    entity_id UUID NOT NULL REFERENCES entity_library(entity_id) ON DELETE CASCADE,
    reason TEXT,
    evidence_text TEXT NOT NULL,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0,
    source_document_id UUID,
    source_title TEXT,
    quote_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (entity_id, quote_hash)
);

CREATE INDEX IF NOT EXISTS ix_entity_library_evidence_entity
    ON entity_library_evidence(entity_id, created_at DESC);

CREATE TABLE IF NOT EXISTS entity_review_candidate (
    candidate_id UUID PRIMARY KEY,
    entity_type VARCHAR(64) NOT NULL,
    mention_text TEXT,
    canonical_name TEXT NOT NULL,
    normalized_key VARCHAR(512) NOT NULL,
    aliases_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    reason TEXT,
    evidence_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0,
    source_document_id UUID,
    source_title TEXT,
    review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    review_note TEXT,
    reviewed_at TIMESTAMPTZ,
    matched_entity_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_entity_review_candidate_status
    ON entity_review_candidate(review_status, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_entity_review_candidate_key
    ON entity_review_candidate(entity_type, normalized_key);

CREATE INDEX IF NOT EXISTS ix_entity_review_candidate_source
    ON entity_review_candidate(source_document_id);
