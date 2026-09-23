-- Each question number has meaning only inside its profile version.
CREATE INDEX IF NOT EXISTS ix_generic_evidence_current_version
    ON generic_evidence_record(document_id, profile_version, question_id)
    WHERE is_current = TRUE;

CREATE INDEX IF NOT EXISTS ix_question_extraction_version_reuse
    ON evidence_question_extraction_run(profile_version, question_id, input_hash, config_hash, status);
