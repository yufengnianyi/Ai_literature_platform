ALTER TABLE pretreatment_document_result
    ADD COLUMN IF NOT EXISTS quality_decision VARCHAR(32),
    ADD COLUMN IF NOT EXISTS quality_metrics_json JSONB,
    ADD COLUMN IF NOT EXISTS title_vector_score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS title_best_profile_term TEXT,
    ADD COLUMN IF NOT EXISTS title_threshold_passes_json JSONB,
    ADD COLUMN IF NOT EXISTS title_vector_decision VARCHAR(64),
    ADD COLUMN IF NOT EXISTS reject_reason_code VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_pretreatment_document_result_quality
    ON pretreatment_document_result (run_id, quality_decision);

CREATE INDEX IF NOT EXISTS idx_pretreatment_document_result_title_vector
    ON pretreatment_document_result (run_id, title_vector_decision);
