CREATE TABLE IF NOT EXISTS review_document_knowledge (
    document_id             UUID PRIMARY KEY,
    knowledge_json          JSONB NOT NULL,
    coverage_chunk_ids_json JSONB,
    prompt_version          VARCHAR(128) NOT NULL,
    knowledge_version       VARCHAR(64) NOT NULL,
    confidence              DOUBLE PRECISION,
    status                  VARCHAR(32) NOT NULL,
    last_seen_task_id       UUID,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_review_document_knowledge_status
    ON review_document_knowledge (status, updated_at DESC);

CREATE TABLE IF NOT EXISTS review_compound_identity (
    compound_id       UUID PRIMARY KEY,
    canonical_name    TEXT,
    iupac_name        TEXT,
    cas_number        TEXT,
    smiles            TEXT,
    inchi_key         TEXT,
    molecular_formula TEXT,
    structure_type    TEXT,
    synonyms_json     JSONB,
    confidence        DOUBLE PRECISION,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_review_compound_identity_inchi
    ON review_compound_identity (inchi_key)
    WHERE inchi_key IS NOT NULL AND inchi_key <> '';

CREATE UNIQUE INDEX IF NOT EXISTS idx_review_compound_identity_smiles
    ON review_compound_identity (smiles)
    WHERE smiles IS NOT NULL AND smiles <> '';

CREATE UNIQUE INDEX IF NOT EXISTS idx_review_compound_identity_cas
    ON review_compound_identity (cas_number)
    WHERE cas_number IS NOT NULL AND cas_number <> '';

CREATE TABLE IF NOT EXISTS review_document_compound_alias (
    id                     BIGSERIAL PRIMARY KEY,
    document_id            UUID NOT NULL,
    local_alias            TEXT NOT NULL,
    resolved_name          TEXT,
    normalized_compound_id UUID,
    evidence_chunk_id      VARCHAR(256),
    evidence_text          TEXT,
    resolution_status      VARCHAR(32) NOT NULL,
    confidence             DOUBLE PRECISION,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (document_id, local_alias)
);

CREATE INDEX IF NOT EXISTS idx_review_doc_alias_doc
    ON review_document_compound_alias (document_id);

CREATE INDEX IF NOT EXISTS idx_review_doc_alias_compound
    ON review_document_compound_alias (normalized_compound_id);

CREATE TABLE IF NOT EXISTS review_document_knowledge_update (
    id                    BIGSERIAL PRIMARY KEY,
    task_id               UUID,
    document_id           UUID NOT NULL,
    prompt_version        VARCHAR(128) NOT NULL,
    updated_fields_json   JSONB,
    source_chunk_ids_json JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_review_doc_knowledge_update_doc
    ON review_document_knowledge_update (document_id, created_at DESC);
