DROP INDEX IF EXISTS idx_pretreatment_document_result_title_vector;

ALTER TABLE pretreatment_document_result
    DROP COLUMN IF EXISTS title_decision,
    DROP COLUMN IF EXISTS title_vector_score,
    DROP COLUMN IF EXISTS title_best_profile_term,
    DROP COLUMN IF EXISTS title_threshold_passes_json,
    DROP COLUMN IF EXISTS title_vector_decision;
