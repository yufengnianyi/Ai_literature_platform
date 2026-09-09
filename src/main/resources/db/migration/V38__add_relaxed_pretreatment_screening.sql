ALTER TABLE pretreatment_document_result
    ADD COLUMN IF NOT EXISTS quality_status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS relevance_decision VARCHAR(32),
    ADD COLUMN IF NOT EXISTS relevance_source VARCHAR(32);

CREATE INDEX IF NOT EXISTS ix_pretreatment_document_result_relevance
    ON pretreatment_document_result (run_id, relevance_decision, quality_status);

ALTER TABLE pretreatment_run
    ADD COLUMN IF NOT EXISTS abstract_analysis_cohort_id UUID REFERENCES document_cohort(cohort_id),
    ADD COLUMN IF NOT EXISTS full_text_evidence_cohort_id UUID REFERENCES document_cohort(cohort_id),
    ADD COLUMN IF NOT EXISTS review_cohort_id UUID REFERENCES document_cohort(cohort_id);
