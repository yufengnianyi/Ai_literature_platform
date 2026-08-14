ALTER TABLE rag_document
    ADD COLUMN IF NOT EXISTS keywords_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS metadata_enrichment_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS metadata_enriched_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ix_rag_document_keywords_json
    ON rag_document USING GIN (keywords_json);
