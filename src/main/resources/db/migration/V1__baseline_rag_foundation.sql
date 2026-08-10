CREATE TABLE IF NOT EXISTS app_user (
    user_id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(128),
    password VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rag_document (
    document_id UUID PRIMARY KEY,
    duplicate_of_document_id UUID,
    latest_preprocess_job_id UUID,
    canonical_key TEXT,
    doi_raw TEXT,
    doi_normalized TEXT,
    pdf_sha256 VARCHAR(64),
    title TEXT,
    authors_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    affiliations_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    abstract_text TEXT,
    journal TEXT,
    publication_date TEXT,
    publication_year INTEGER,
    synopsis_json JSONB,
    synopsis_text TEXT,
    source_filename TEXT,
    storage_root TEXT,
    status VARCHAR(32) NOT NULL,
    preprocess_status VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE rag_document
    ADD COLUMN IF NOT EXISTS duplicate_of_document_id UUID,
    ADD COLUMN IF NOT EXISTS latest_preprocess_job_id UUID,
    ADD COLUMN IF NOT EXISTS canonical_key TEXT,
    ADD COLUMN IF NOT EXISTS doi_raw TEXT,
    ADD COLUMN IF NOT EXISTS doi_normalized TEXT,
    ADD COLUMN IF NOT EXISTS pdf_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS title TEXT,
    ADD COLUMN IF NOT EXISTS authors_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS affiliations_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS abstract_text TEXT,
    ADD COLUMN IF NOT EXISTS journal TEXT,
    ADD COLUMN IF NOT EXISTS publication_date TEXT,
    ADD COLUMN IF NOT EXISTS publication_year INTEGER,
    ADD COLUMN IF NOT EXISTS synopsis_json JSONB,
    ADD COLUMN IF NOT EXISTS synopsis_text TEXT,
    ADD COLUMN IF NOT EXISTS source_filename TEXT,
    ADD COLUMN IF NOT EXISTS storage_root TEXT,
    ADD COLUMN IF NOT EXISTS status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS preprocess_status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX IF NOT EXISTS ix_rag_document_status
    ON rag_document(status, duplicate_of_document_id);

CREATE INDEX IF NOT EXISTS ix_rag_document_doi
    ON rag_document(doi_normalized);

CREATE INDEX IF NOT EXISTS ix_rag_document_pdf_sha
    ON rag_document(pdf_sha256);

CREATE TABLE IF NOT EXISTS document_preprocess_batch (
    batch_id UUID PRIMARY KEY,
    source_folder TEXT,
    status VARCHAR(32) NOT NULL,
    total_files INTEGER,
    processed_files INTEGER DEFAULT 0,
    completed_files INTEGER DEFAULT 0,
    duplicate_files INTEGER DEFAULT 0,
    failed_files INTEGER DEFAULT 0,
    chunk_count INTEGER DEFAULT 0,
    upload_ms BIGINT,
    header_ms BIGINT,
    fulltext_ms BIGINT,
    tei_parse_ms BIGINT,
    jsonl_ms BIGINT,
    total_elapsed_ms BIGINT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS document_preprocess_job (
    job_id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES rag_document(document_id) ON DELETE CASCADE,
    batch_id UUID REFERENCES document_preprocess_batch(batch_id) ON DELETE SET NULL,
    status VARCHAR(32) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    duplicate_reason VARCHAR(64),
    error_code VARCHAR(64),
    error_message TEXT,
    upload_ms BIGINT,
    header_ms BIGINT,
    fulltext_ms BIGINT,
    tei_parse_ms BIGINT,
    jsonl_ms BIGINT,
    total_ms BIGINT,
    chunk_count INTEGER,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_document_preprocess_job_document
    ON document_preprocess_job(document_id, created_at DESC);

CREATE TABLE IF NOT EXISTS rag_ingestion_batch (
    batch_id UUID PRIMARY KEY,
    source_folder TEXT,
    status VARCHAR(32) NOT NULL,
    total_files INTEGER,
    processed_files INTEGER DEFAULT 0,
    completed_files INTEGER DEFAULT 0,
    duplicate_files INTEGER DEFAULT 0,
    failed_files INTEGER DEFAULT 0,
    chunk_count INTEGER DEFAULT 0,
    estimated_tokens_total BIGINT DEFAULT 0,
    provider_tokens_total BIGINT DEFAULT 0,
    upload_ms BIGINT,
    header_ms BIGINT,
    fulltext_ms BIGINT,
    tei_parse_ms BIGINT,
    jsonl_ms BIGINT,
    embed_ms BIGINT,
    persist_ms BIGINT,
    total_elapsed_ms BIGINT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rag_ingestion_job (
    job_id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES rag_document(document_id) ON DELETE CASCADE,
    batch_id UUID REFERENCES rag_ingestion_batch(batch_id) ON DELETE SET NULL,
    status VARCHAR(32) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    duplicate_reason VARCHAR(64),
    error_code VARCHAR(64),
    error_message TEXT,
    upload_ms BIGINT,
    header_ms BIGINT,
    fulltext_ms BIGINT,
    tei_parse_ms BIGINT,
    jsonl_ms BIGINT,
    embed_ms BIGINT,
    persist_ms BIGINT,
    total_ms BIGINT,
    chunk_count INTEGER,
    estimated_tokens_total BIGINT,
    provider_tokens_total BIGINT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_rag_ingestion_job_document
    ON rag_ingestion_job(document_id, created_at DESC);

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS ${vectorTable} (
    embedding_id UUID PRIMARY KEY,
    embedding vector(${embeddingDimension}),
    text TEXT,
    metadata JSONB
);
