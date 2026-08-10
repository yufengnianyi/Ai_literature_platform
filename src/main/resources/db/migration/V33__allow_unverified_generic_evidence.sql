DO $$
DECLARE
    target_name TEXT;
BEGIN
    SELECT conname INTO target_name
    FROM pg_constraint
    WHERE conrelid = 'generic_evidence_record'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%validation_status%'
    LIMIT 1;

    IF target_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE generic_evidence_record DROP CONSTRAINT %I', target_name);
    END IF;
END $$;

ALTER TABLE generic_evidence_record
    ADD CONSTRAINT ck_generic_evidence_record_validation_status
    CHECK (validation_status IN ('VALID', 'INVALID', 'UNVERIFIED'));
