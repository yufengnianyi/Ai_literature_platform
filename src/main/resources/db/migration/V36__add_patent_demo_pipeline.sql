CREATE TABLE IF NOT EXISTS patent_demo_run (
    run_id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    manifest_path TEXT NOT NULL,
    pdf_root TEXT NOT NULL,
    category TEXT NOT NULL,
    requested_limit INTEGER NOT NULL,
    seed BIGINT,
    run_extraction BOOLEAN NOT NULL DEFAULT TRUE,
    force BOOLEAN NOT NULL DEFAULT FALSE,
    total_candidates INTEGER NOT NULL DEFAULT 0,
    text_layer_passed INTEGER NOT NULL DEFAULT 0,
    ingested_documents INTEGER NOT NULL DEFAULT 0,
    skipped_documents INTEGER NOT NULL DEFAULT 0,
    failed_documents INTEGER NOT NULL DEFAULT 0,
    cohort_id UUID REFERENCES document_cohort(cohort_id) ON DELETE SET NULL,
    extraction_run_id UUID,
    evidence_rows INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS patent_demo_document (
    run_id UUID NOT NULL REFERENCES patent_demo_run(run_id) ON DELETE CASCADE,
    publication_number TEXT NOT NULL,
    title TEXT,
    pdf_path TEXT,
    status VARCHAR(32) NOT NULL,
    text_layer_status VARCHAR(32),
    page_count INTEGER,
    file_size_bytes BIGINT,
    sampled_text_chars INTEGER,
    usable_page_ratio DOUBLE PRECISION,
    replacement_char_ratio DOUBLE PRECISION,
    selected_page_count INTEGER,
    chunk_count INTEGER,
    document_id UUID REFERENCES rag_document(document_id) ON DELETE SET NULL,
    skip_reason TEXT,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, publication_number)
);

CREATE TABLE IF NOT EXISTS patent_demo_page (
    run_id UUID NOT NULL,
    publication_number TEXT NOT NULL,
    page_number INTEGER NOT NULL,
    score INTEGER NOT NULL DEFAULT 0,
    role TEXT,
    selected BOOLEAN NOT NULL DEFAULT FALSE,
    reason TEXT,
    text_chars INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, publication_number, page_number),
    FOREIGN KEY (run_id, publication_number)
        REFERENCES patent_demo_document(run_id, publication_number) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS ix_patent_demo_document_status
    ON patent_demo_document(run_id, status);

CREATE INDEX IF NOT EXISTS ix_patent_demo_document_text_layer
    ON patent_demo_document(run_id, text_layer_status);

CREATE INDEX IF NOT EXISTS ix_patent_demo_page_selected
    ON patent_demo_page(run_id, publication_number, selected);
