DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = '${vectorTable}'
          AND column_name = 'id'
    )
    AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = '${vectorTable}'
          AND column_name = 'embedding_id'
    ) THEN
        EXECUTE 'ALTER TABLE ${vectorTable} RENAME COLUMN id TO embedding_id';
    END IF;
END $$;