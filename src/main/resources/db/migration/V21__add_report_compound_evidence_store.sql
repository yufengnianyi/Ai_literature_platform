ALTER TABLE evidence_extraction_run
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(64) NOT NULL DEFAULT 'MODEL_EXTRACTION',
    ADD COLUMN IF NOT EXISTS source_experiment_id UUID REFERENCES rag_eval_experiment(experiment_id);

CREATE UNIQUE INDEX IF NOT EXISTS ux_evidence_run_baseline_source
    ON evidence_extraction_run(source_experiment_id, document_id)
    WHERE source_type = 'ANTIMICROBIAL_SUMMARY_BASELINE';

CREATE TABLE IF NOT EXISTS compound_evidence (
    evidence_id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES evidence_extraction_run(run_id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES rag_document(document_id) ON DELETE CASCADE,
    row_index INTEGER NOT NULL,
    compound_original_name TEXT,
    compound_standard_name TEXT,
    structure_type TEXT,
    source_category TEXT,
    source_description TEXT,
    oomycete_scientific_name TEXT,
    assay_method TEXT,
    activity_data TEXT,
    positive_control TEXT,
    target_or_mechanism TEXT,
    target_validation_method TEXT,
    cytotoxicity TEXT,
    resistance_cross_resistance TEXT,
    synergy TEXT,
    reference_text TEXT,
    patent_information TEXT,
    raw_row_json JSONB NOT NULL,
    row_fingerprint VARCHAR(64) NOT NULL,
    model_confidence DOUBLE PRECISION,
    validation_status VARCHAR(32) NOT NULL
        CHECK (validation_status IN ('VALID', 'INVALID')),
    validation_warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    review_note TEXT,
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    supersedes_id UUID REFERENCES compound_evidence(evidence_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(run_id, row_fingerprint)
);

CREATE INDEX IF NOT EXISTS ix_compound_evidence_current
    ON compound_evidence(is_current, validation_status, review_status);
CREATE INDEX IF NOT EXISTS ix_compound_evidence_document
    ON compound_evidence(document_id, is_current);
CREATE INDEX IF NOT EXISTS ix_compound_evidence_compound
    ON compound_evidence(lower(coalesce(compound_standard_name, compound_original_name)));
CREATE INDEX IF NOT EXISTS ix_compound_evidence_oomycete
    ON compound_evidence(lower(oomycete_scientific_name));
CREATE INDEX IF NOT EXISTS ix_compound_evidence_method
    ON compound_evidence(lower(assay_method));
CREATE INDEX IF NOT EXISTS ix_compound_evidence_search
    ON compound_evidence USING GIN (
        to_tsvector('simple',
            coalesce(compound_original_name, '') || ' ' ||
            coalesce(compound_standard_name, '') || ' ' ||
            coalesce(structure_type, '') || ' ' ||
            coalesce(source_category, '') || ' ' ||
            coalesce(source_description, '') || ' ' ||
            coalesce(oomycete_scientific_name, '') || ' ' ||
            coalesce(assay_method, '') || ' ' ||
            coalesce(activity_data, '') || ' ' ||
            coalesce(target_or_mechanism, '')
        )
    );

CREATE TABLE IF NOT EXISTS evidence_anchor (
    anchor_id BIGSERIAL PRIMARY KEY,
    evidence_id UUID NOT NULL REFERENCES compound_evidence(evidence_id) ON DELETE CASCADE,
    chunk_id VARCHAR(256) NOT NULL,
    section_path TEXT,
    paragraph_index INTEGER,
    sentence_start INTEGER,
    sentence_end INTEGER,
    page_start INTEGER,
    page_end INTEGER,
    exact_quote TEXT NOT NULL,
    quote_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(evidence_id, chunk_id, quote_hash)
);

CREATE INDEX IF NOT EXISTS ix_evidence_anchor_evidence ON evidence_anchor(evidence_id);
CREATE INDEX IF NOT EXISTS ix_evidence_anchor_chunk ON evidence_anchor(chunk_id);
